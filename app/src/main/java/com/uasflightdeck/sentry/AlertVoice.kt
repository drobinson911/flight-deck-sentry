package com.uasflightdeck.sentry

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.uasflightdeck.sentry.core.AlertEvent
import com.uasflightdeck.sentry.core.Severity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tone + speech for every callout.
 *
 * - Audio attributes USAGE_ASSISTANCE_NAVIGATION_GUIDANCE and focus
 *   AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, so DroneSense's audio ducks under us
 *   instead of being paused or fighting us.
 * - A distinct tone per severity plays BEFORE the words.
 * - Queue is priority-ordered, one pending item per aircraft (a newer callout
 *   about N388KM replaces an unspoken older one), and anything older than
 *   15 s is dropped rather than spoken late — stale speech is wrong speech.
 * - If TTS is unavailable, tones still play, the heads-up notification still
 *   posts, and the UI shows VOICE UNAVAILABLE in red.
 */
class AlertVoice(private val ctx: Context, private val scope: CoroutineScope) : TextToSpeech.OnInitListener {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()

    @Volatile private var tts: TextToSpeech? = null
    @Volatile var ready = false; private set
    @Volatile var status = "starting"; private set
    @Volatile var enabled = true
    @Volatile var volume = 1.0f

    private data class Item(val ev: AlertEvent, val enqueuedMs: Long)
    private val pending = ArrayList<Item>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val done = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var worker: Job? = null
    private var initAttempts = 0

    fun start() {
        initTts()
        worker = scope.launch { loop() }
    }

    private fun initTts() {
        initAttempts++
        status = "starting"
        tts = TextToSpeech(ctx.applicationContext, this)
    }

    override fun onInit(st: Int) {
        val t = tts ?: return
        if (st != TextToSpeech.SUCCESS) {
            ready = false; status = "TTS init failed ($st)"
            SentryBus.log("Voice: TTS init failed status=$st (attempt $initAttempts)")
            // auto-regain: retry with backoff (engines can come up late after boot)
            scope.launch { delay(minOf(60_000L, 5_000L * initAttempts)); runCatching { t.shutdown() }; initTts() }
            return
        }
        t.setAudioAttributes(attrs)
        val lr = t.setLanguage(Locale.US)
        t.setSpeechRate(1.05f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { done.remove(it)?.complete(Unit) } }
            @Deprecated("deprecated in API") override fun onError(id: String?) { id?.let { done.remove(it)?.complete(Unit) } }
            override fun onError(id: String?, errorCode: Int) { id?.let { done.remove(it)?.complete(Unit) } }
        })
        ready = lr != TextToSpeech.LANG_MISSING_DATA && lr != TextToSpeech.LANG_NOT_SUPPORTED
        status = if (ready) "ready (${t.defaultEngine ?: "tts"})" else "no US English voice"
        SentryBus.log("Voice: TTS $status")
    }

    fun say(ev: AlertEvent) {
        synchronized(pending) {
            if (ev.hex != null) pending.removeAll { it.ev.hex == ev.hex }
            pending += Item(ev, System.currentTimeMillis())
            pending.sortByDescending { it.ev.severity.rank }
        }
        signal.trySend(Unit)
    }

    private suspend fun loop() {
        while (true) {
            signal.receive()
            var hadFocus = false
            while (true) {
                val item = synchronized(pending) { if (pending.isEmpty()) null else pending.removeAt(0) } ?: break
                if (System.currentTimeMillis() - item.enqueuedMs > 15_000) {
                    SentryBus.log("Voice: dropped stale callout: ${item.ev.text}")
                    continue
                }
                if (!enabled) { SentryBus.log("MUTED [${item.ev.severity.label}] ${item.ev.speech}"); continue }
                if (!hadFocus) {
                    // Ducking is best-effort: we speak even if focus is refused.
                    val g = am.requestAudioFocus(focusReq)
                    if (g != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) SentryBus.log("Voice: audio focus not granted ($g)")
                    hadFocus = true
                }
                playTone(item.ev.severity)
                speak(item.ev)
            }
            if (hadFocus) am.abandonAudioFocusRequest(focusReq)
        }
    }

    private suspend fun playTone(sev: Severity) {
        val (tone, ms) = when (sev) {
            Severity.WARNING -> ToneGenerator.TONE_CDMA_HIGH_SS to 700
            Severity.CAUTION -> ToneGenerator.TONE_PROP_BEEP2 to 450
            Severity.ADVISORY -> ToneGenerator.TONE_PROP_BEEP to 250
            else -> ToneGenerator.TONE_PROP_ACK to 200
        }
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, (volume * 100).toInt().coerceIn(10, 100))
            tg.startTone(tone, ms)
            delay(ms + 80L)
            tg.release()
        } catch (e: RuntimeException) {
            SentryBus.log("Voice: tone failed: ${e.message}")
        }
    }

    private suspend fun speak(ev: AlertEvent) {
        SentryBus.log("SPEAK [${ev.severity.label}] ${ev.speech}")
        val t = tts
        if (t == null || !ready) { SentryBus.log("Voice: TTS not ready ($status); text only"); return }
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<Unit>()
        done[id] = d
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        val r = t.speak(ev.speech, TextToSpeech.QUEUE_ADD, params, id)
        if (r != TextToSpeech.SUCCESS) { done.remove(id); SentryBus.log("Voice: speak() returned $r"); return }
        withTimeoutOrNull(15_000) { d.await() } ?: run { done.remove(id); SentryBus.log("Voice: utterance timeout") }
    }

    fun shutdown() {
        worker?.cancel()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null; ready = false; status = "stopped"
    }
}
