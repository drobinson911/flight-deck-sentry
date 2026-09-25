package com.uasflightdeck.sentry

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import com.uasflightdeck.sentry.core.Cue
import com.uasflightdeck.sentry.core.Playback
import com.uasflightdeck.sentry.core.SoundChoice
import com.uasflightdeck.sentry.core.SoundLevel

/**
 * Plays the controller's own sounds and vibrates (v0.4.0; no voice).
 *
 *  - One sound at a time: a new one stops the one playing ("stop a longer sound at the next event").
 *  - WARNING and COLLISION RISK on USAGE_ALARM, everything else USAGE_NOTIFICATION_EVENT; audio focus
 *    AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK for the length of the sound only (never GAIN: DroneSense's audio is ducked,
 *    never stopped), released right after.
 *  - The sound for a level comes from [SoundChoice]: the pilot's pick, else the level's default file in the
 *    controller's library, else the default alarm / notification sound, else a generated tone. Never silence.
 *  - All audio work runs on one background thread, so the 1 s engine tick is never blocked.
 */
class SoundPlayer(ctx: Context) {
    private val app = ctx.applicationContext
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator? = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val thread = HandlerThread("sentry-audio").also { it.start() }
    private val h = Handler(thread.looper)

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var focus: AudioFocusRequest? = null
    private val stopRunnable = Runnable { stopNow() }

    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    private val cache = HashMap<Pair<SoundLevel, String>, SoundChoice.Resolved>()

    /** Which URI a level plays with the stored choice [stored] ("" = default). Cached; call [forget] after a change. */
    @Synchronized fun resolve(level: SoundLevel, stored: String): SoundChoice.Resolved = cache.getOrPut(level to stored) {
        SoundChoice.resolve(level, stored, ::resolves, ::byName,
            RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)?.toString(),
            RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_NOTIFICATION)?.toString()
                ?: android.provider.Settings.System.DEFAULT_NOTIFICATION_URI.toString())
    }

    @Synchronized fun forget() = cache.clear()

    /** The sound's title for Settings ("Capella"), or "tone". */
    fun title(level: SoundLevel, stored: String): String {
        val r = resolve(level, stored)
        val u = r.uri ?: return "generated tone"
        val t = runCatching { RingtoneManager.getRingtone(app, Uri.parse(u))?.getTitle(app) }.getOrNull() ?: u
        return when (r.source) {
            SoundChoice.Source.PICKED -> t
            SoundChoice.Source.LEVEL_DEFAULT -> "$t (default)"
            SoundChoice.Source.DEFAULT_ALARM -> "$t (controller's alarm sound)"
            SoundChoice.Source.DEFAULT_NOTIFICATION -> "$t (controller's notification sound)"
            SoundChoice.Source.TONE -> "generated tone"
        }
    }

    private fun resolves(uri: String): Boolean = runCatching {
        app.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** A sound in the controller's own library by file name ("Capella.ogg"), as a content URI. */
    private fun byName(name: String): String? = runCatching {
        app.contentResolver.query(MediaStore.Audio.Media.INTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { c ->
            if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Audio.Media.INTERNAL_CONTENT_URI, c.getLong(0).toString()).toString() else null
        }
    }.getOrNull()

    private fun attrs(level: SoundLevel): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (level.alarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Play [level] as [cue] (FULL / SHORT) at [volumePct]. [onStarted] gets the wall-clock time the sound started
     * (for the latency metric) and what played.
     */
    fun play(level: SoundLevel, cue: Cue, stored: String, volumePct: Int, onStarted: (Long, String) -> Unit = { _, _ -> }) {
        val spec = Playback.spec(cue) ?: return
        val r = resolve(level, stored)
        h.post {
            stopNow()
            val vol = (volumePct.coerceIn(0, 100) / 100f) * spec.volumeScale
            val a = attrs(level)
            val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(a)
                .setOnAudioFocusChangeListener({ }, h).build()
            runCatching { am.requestAudioFocus(fr) }
            focus = fr
            val ok = r.uri != null && runCatching {
                val mp = MediaPlayer()
                mp.setAudioAttributes(a)
                mp.setDataSource(app, Uri.parse(r.uri))
                mp.setVolume(vol, vol)
                mp.prepare()
                mp.setOnCompletionListener { stopNow() }
                mp.start()
                player = mp
                onStarted(System.currentTimeMillis(), "${level.key}/${cue.name.lowercase()} ${r.source.name.lowercase()}")
                h.postDelayed(stopRunnable, minOf(spec.maxMs, mp.duration.toLong().takeIf { it > 0 } ?: spec.maxMs))
            }.onFailure { SentryBus.log("Sound ${level.key} failed (${it.message}); playing a tone") }.isSuccess
            if (!ok) {
                // Never silence: a generated beep on the same stream.
                runCatching {
                    val tg = ToneGenerator(if (level.alarmStream) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION, (vol * 100).toInt())
                    tone = tg
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, spec.maxMs.toInt().coerceAtMost(1500))
                    onStarted(System.currentTimeMillis(), "${level.key}/${cue.name.lowercase()} tone")
                    h.postDelayed(stopRunnable, spec.maxMs)
                }
            }
        }
    }

    fun vibrate(level: SoundLevel) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val a = AudioAttributes.Builder().setUsage(if (level.alarmStream) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
            v.vibrate(VibrationEffect.createWaveform(level.vibration, -1), a)
        }
    }

    fun stop() = h.post { stopNow() }

    private fun stopNow() {
        h.removeCallbacks(stopRunnable)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        tone?.let { runCatching { it.stopTone() }; runCatching { it.release() } }
        tone = null
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
    }

    /** Alarm and notification streams both above zero (pre-flight "sound audible"). */
    fun audible(): Pair<Boolean, String> {
        val alarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val notif = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val maxA = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val maxN = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        val ok = alarm > 0 && notif > 0
        return ok to "alarm $alarm/$maxA, notification $notif/$maxN" + if (Build.VERSION.SDK_INT >= 23 && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) " · ringer ${if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) "silent" else "vibrate"}" else ""
    }

    fun shutdown() { h.post { stopNow() }; thread.quitSafely() }
}
