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
import com.uasflightdeck.sentry.core.CalloutQueue
import com.uasflightdeck.sentry.core.Severity
import com.uasflightdeck.sentry.core.VoicePolicy
import com.uasflightdeck.sentry.core.VoicePolicy.Engine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tone + speech for every callout, through TWO stacked voices (v0.3.5):
 *
 *  1. the device's text-to-speech engine, when one is installed and ready;
 *  2. Sentry's own bundled voice ([ClipVoice]), always loaded. The DJI RC Plus ships with NO TTS engine.
 *
 * TTS is used while it works. If it is missing, never initialises, or fails on a callout (speak() error, onError,
 * no start within 3 s, or no finish in time), that callout is spoken by the bundled voice at once and the bundled voice stays in
 * charge ("sticky") until TTS is tried again [TTS_RETRY_MS] later. [status] always names the voice in use.
 *
 * - Audio attributes USAGE_ASSISTANCE_NAVIGATION_GUIDANCE and focus AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (never
 *   GAIN), requested for ONE callout (tone + words) and released right after it. No media session.
 * - A distinct tone per severity plays BEFORE the words.
 * - Queue ([CalloutQueue]): one waiting item per aircraft, most severe first, closer aircraft first, anything
 *   that waited more than 15 s is dropped (stale speech is wrong speech).
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

    private val policy = VoicePolicy(TTS_RETRY_MS)

    private val clips = ClipVoice(ctx, attrs)
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsLabel = "TTS"
    @Volatile private var ttsState = "starting"
    /** Debug / test: pretend there is no TTS engine (the RC Plus case). */
    @Volatile var forceBundled = false

    @Volatile var enabled = true
    @Volatile var volume = 1.0f

    /** Which voice the next callout will use. */
    val engine: Engine
        get() = synchronized(policy) {
            policy.choose(System.currentTimeMillis(), ttsReady && tts != null, clips.ready, forceBundled)
        }
    val ready: Boolean get() = engine != Engine.NONE
    /** For the Voice row: "Google TTS", "bundled voice", or why there is none. */
    val status: String
        get() = when (engine) {
            Engine.TTS -> ttsLabel
            Engine.BUNDLED -> "bundled voice"
            Engine.NONE -> if (clips.status == "not loaded") "loading voice" else "${clips.status}; TTS $ttsState"
        }

    private val queue = CalloutQueue()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val done = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var worker: Job? = null
    private var initAttempts = 0

    fun start() {
        scope.launch(Dispatchers.IO) { clips.load(); signal.trySend(Unit) }
        initTts()
        worker = scope.launch { loop() }
    }

    private fun initTts() {
        initAttempts++
        ttsState = "starting"
        tts = try { TextToSpeech(ctx.applicationContext, this) } catch (e: Exception) {
            SentryBus.log("Voice: TTS unavailable: $e"); ttsState = "unavailable"; scheduleTtsRetry(); null
        }
    }

    private fun scheduleTtsRetry() {
        // auto-regain: engines can come up late after boot, or be installed later. The bundled voice covers meanwhile.
        scope.launch { delay(minOf(TTS_RETRY_MS, 5_000L * initAttempts)); runCatching { tts?.shutdown() }; tts = null; initTts() }
    }

    /**
     * With no engine installed (the RC Plus), Android calls this with ERROR from INSIDE the TextToSpeech
     * constructor, before [tts] is assigned. So the result is handled on the scope, once the constructor returned.
     */
    override fun onInit(st: Int) {
        scope.launch {
            var waited = 0
            while (tts == null && waited < 2000) { delay(20); waited += 20 }
            handleInit(st)
        }
    }

    private fun handleInit(st: Int) {
        val t = tts
        if (st != TextToSpeech.SUCCESS || t == null) {
            ttsReady = false; ttsState = if (st == TextToSpeech.SUCCESS) "lost" else "no engine / init failed ($st)"
            SentryBus.log("Voice: TTS init failed status=$st (attempt $initAttempts, engines=${runCatching { t?.engines?.size }.getOrNull() ?: -1}); " +
                "bundled voice ${if (clips.ready) "in use" else clips.status}")
            scheduleTtsRetry()
            return
        }
        t.setAudioAttributes(attrs)
        val lr = t.setLanguage(Locale.US)
        t.setSpeechRate(1.05f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { id?.let { started.remove(it)?.complete(Unit) } }
            override fun onDone(id: String?) { id?.let { done.remove(it)?.complete(true) } }
            @Deprecated("deprecated in API") override fun onError(id: String?) { id?.let { done.remove(it)?.complete(false) } }
            override fun onError(id: String?, errorCode: Int) { id?.let { done.remove(it)?.complete(false) } }
        })
        ttsReady = lr != TextToSpeech.LANG_MISSING_DATA && lr != TextToSpeech.LANG_NOT_SUPPORTED
        val eng = t.defaultEngine ?: "tts"
        ttsLabel = when {
            eng == "com.google.android.tts" -> "Google TTS"
            eng.contains("pico") -> "Pico TTS"
            else -> "TTS $eng"
        }
        ttsState = if (ttsReady) "ready ($eng)" else "no US English voice"
        SentryBus.log("Voice: TTS $ttsState")
        if (!ttsReady) scheduleTtsRetry()
    }

    fun say(ev: AlertEvent) {
        synchronized(queue) { queue.offer(ev, System.currentTimeMillis()) }
        signal.trySend(Unit)
    }

    private suspend fun loop() {
        while (true) {
            signal.receive()
            while (true) {
                val item = synchronized(queue) {
                    queue.poll(System.currentTimeMillis()) { SentryBus.log("Voice: dropped stale callout: ${it.ev.text}") }
                } ?: break
                if (!enabled) { SentryBus.log("MUTED [${item.ev.severity.label}] ${item.ev.speech}"); continue }
                // Ducking is best-effort: we speak even if focus is refused. Focus is held for this one
                // callout only and released right after it, never across the queue.
                val g = am.requestAudioFocus(focusReq)
                if (g != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) SentryBus.log("Voice: audio focus not granted ($g)")
                try {
                    playTone(item.ev.severity)
                    speak(item.ev)
                } finally {
                    am.abandonAudioFocusRequest(focusReq)
                }
            }
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
        // First callout after arming can beat TTS init / clip decoding by a moment: wait briefly for EITHER voice.
        var waited = 0
        while (!ready && waited < 6000) { delay(200); waited += 200 }
        val via = engine
        SentryBus.log("SPEAK[${via.name.lowercase()}] [${ev.severity.label}] ${ev.speech}")
        when (via) {
            Engine.TTS -> if (!speakTts(ev)) {
                synchronized(policy) { policy.ttsFailed(System.currentTimeMillis()) }
                SentryBus.log("Voice: TTS failed on a callout; bundled voice takes over (TTS retried in ${TTS_RETRY_MS / 1000} s)")
                if (!clips.speak(ev.speech, volume)) SentryBus.log("Voice: bundled voice also failed; tone + notification only")
            }
            Engine.BUNDLED -> if (!clips.speak(ev.speech, volume)) SentryBus.log("Voice: bundled voice failed; tone + notification only")
            Engine.NONE -> SentryBus.log("Voice: no voice ($status); tone + notification only")
        }
    }

    /** true = the engine reported the utterance done. */
    private suspend fun speakTts(ev: AlertEvent): Boolean {
        val t = tts ?: return false
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<Boolean>()
        val s0 = CompletableDeferred<Unit>()
        done[id] = d
        started[id] = s0
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        val r = try { t.speak(ev.speech, TextToSpeech.QUEUE_ADD, params, id) } catch (e: Exception) { TextToSpeech.ERROR }
        if (r != TextToSpeech.SUCCESS) { done.remove(id); started.remove(id); SentryBus.log("Voice: TTS speak() returned $r"); return false }
        // A dead engine says nothing at all (no onError): fail over fast. It must START within 3 s, and finish within
        // 3 s + 90 ms per character (a 110-character warning: 12.9 s; Google TTS takes about 7 s), capped at 15 s.
        val fail = { why: String -> done.remove(id); started.remove(id); runCatching { t.stop() }; SentryBus.log("Voice: TTS $why"); false }
        if (withTimeoutOrNull(3_000) { s0.await() } == null && !d.isCompleted) return fail("did not start within 3 s")
        val limit = minOf(15_000L, 3_000L + 90L * ev.speech.length)
        val ok = withTimeoutOrNull(limit) { d.await() } ?: return fail("utterance not finished within ${limit / 1000.0} s")
        started.remove(id)
        if (!ok) SentryBus.log("Voice: TTS reported an error on the utterance")
        return ok
    }

    fun shutdown() {
        worker?.cancel()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null; ttsReady = false; ttsState = "stopped"
    }

    companion object {
        /** After TTS fails, the bundled voice is used for this long before TTS is tried again. */
        const val TTS_RETRY_MS = 5 * 60_000L
    }
}
