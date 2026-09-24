package com.uasflightdeck.sentry

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import com.uasflightdeck.sentry.core.ClipTimeline
import com.uasflightdeck.sentry.core.VoiceGrammar
import kotlinx.coroutines.delay
import java.nio.ByteOrder

/**
 * Sentry's own voice (v0.3.5): the clip bank in assets/voice (Piper, en_US-kristin-medium, public domain), for
 * controllers with no text-to-speech engine. The DJI RC Plus has none.
 *
 * At start every clip is decoded once to 16-bit PCM in memory (about 5 MB). An utterance is tokenised by
 * [VoiceGrammar], stitched into ONE PCM buffer (clips back to back, punctuation as short silences) and played as a
 * single static AudioTrack, so there are no gaps between clips. Same audio attributes as the TTS path; the caller
 * holds audio focus. Every utterance is logged with each clip's start time.
 */
class ClipVoice(private val ctx: Context, private val attrs: AudioAttributes) {
    @Volatile var ready = false; private set
    @Volatile var status = "not loaded"; private set
    @Volatile private var grammar: VoiceGrammar? = null
    private val pcm = java.util.concurrent.ConcurrentHashMap<String, ShortArray>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Blocking; call off the main thread. The voice is usable as soon as the manifest is read and one clip
     * decodes (a few hundred ms); the rest of the bank is then decoded in the background, and any clip a callout
     * needs before that is decoded on demand.
     */
    fun load() {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val manifest = VoiceGrammar.parseManifest(ctx.assets.open("voice/manifest.tsv").bufferedReader().use { it.readText() })
            val g = VoiceGrammar(manifest)
            val missingRules = VoiceGrammar.RULE_IDS.filter { it !in manifest }
            if (clip("warning") == null || missingRules.isNotEmpty()) {
                status = "bundled voice damaged"; SentryBus.log("Voice: bundled voice unusable (decode failed or missing $missingRules)"); return
            }
            grammar = g
            ready = true; status = "bundled voice"
            SentryBus.log("Voice: bundled voice ready in ${SystemClock.elapsedRealtime() - t0} ms (${manifest.size} clips; decoding the rest now)")
            // most used first: numbers, letters, directions, then everything else
            val order = manifest.keys.sortedBy { id -> when { id.startsWith("n") && id.drop(1).all { it.isDigit() } -> 0; id.startsWith("l_") -> 1; else -> 2 } }
            for (id in order) clip(id)
            if (VoiceGrammar.RULE_IDS.any { it in failed }) { ready = false; status = "bundled voice damaged" }
            SentryBus.log("Voice: bundled voice decoded ${pcm.size}/${manifest.size} clips in ${SystemClock.elapsedRealtime() - t0} ms" +
                if (failed.isEmpty()) "" else "; FAILED: ${failed.take(8)}")
        } catch (e: Exception) {
            ready = false; status = "bundled voice missing"
            SentryBus.log("Voice: bundled voice failed to load: $e")
        }
    }

    private fun clip(id: String): ShortArray? {
        pcm[id]?.let { return it }
        if (id in failed) return null
        val s = runCatching { decode("voice/$id.ogg") }.getOrNull()
        if (s == null || s.isEmpty()) { failed += id; return null }
        pcm[id] = s
        return s
    }

    /**
     * Speak [speech] and suspend until it has played. Returns false if nothing could be played.
     */
    suspend fun speak(speech: String, volume: Float): Boolean {
        val g = grammar ?: return false
        if (!ready) return false
        val r = g.tokenize(speech)
        val isPause = { t: String -> t == VoiceGrammar.PAUSE_SHORT || t == VoiceGrammar.PAUSE_LONG }
        val clips = HashMap<String, ShortArray>()
        for (t in r.tokens) if (!isPause(t) && t !in clips) clip(t)?.let { clips[t] = it }
        val tokens = r.tokens.filter { isPause(it) || clips.containsKey(it) }
        val timeline = ClipTimeline.build(tokens) { id -> (clips.getValue(id).size * 1000L / RATE).toInt() }
        val total = timeline.sumOf { if (isPause(it.id)) it.lengthMs * RATE / 1000 else clips.getValue(it.id).size }
        if (total == 0) return false
        val buf = ShortArray(total)
        var at = 0
        for (e in timeline) {
            val c = clips[e.id]
            if (c == null) at += e.lengthMs * RATE / 1000 else { System.arraycopy(c, 0, buf, at, c.size); at += c.size }
        }
        val dropped = r.tokens.count { !isPause(it) && !clips.containsKey(it) }
        SentryBus.log("CLIPVOICE ${ClipTimeline.totalMs(timeline)} ms: " + timeline.joinToString(" ") {
            if (isPause(it.id)) it.id else "${it.id}@${it.startMs}"
        } + (if (r.spelled.isNotEmpty()) "  (spelled: ${r.spelled})" else "") +
            (if (r.missing.isNotEmpty() || dropped > 0) "  MISSING: ${r.missing} + $dropped undecodable" else ""))
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buf.size * 2)
                .build()
        } catch (e: Exception) { SentryBus.log("Voice: AudioTrack failed: $e"); return false }
        try {
            if (track.write(buf, 0, buf.size) < 0) { SentryBus.log("Voice: AudioTrack write failed"); return false }
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
            val endMs = SystemClock.elapsedRealtime() + buf.size * 1000L / RATE + 1500
            while (track.playbackHeadPosition < buf.size && SystemClock.elapsedRealtime() < endMs) delay(40)
            return true
        } catch (e: Exception) {
            SentryBus.log("Voice: clip playback failed: $e"); return false
        } finally {
            runCatching { track.stop() }; track.release()
        }
    }

    private fun decode(asset: String): ShortArray {
        val afd = ctx.assets.openFd(asset)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            ex.selectTrack(0)
            val fmt = ex.getTrackFormat(0)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            val out = ShortArrayBuilder()
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var outDone = false
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        val b = codec.getOutputBuffer(o)!!
                        b.position(info.offset); b.limit(info.offset + info.size)
                        out.add(b.order(ByteOrder.nativeOrder()).asShortBuffer())
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            } finally { codec.stop(); codec.release() }
            return out.toArray()
        } finally { ex.release(); afd.close() }
    }

    private class ShortArrayBuilder {
        private var a = ShortArray(16_384); private var n = 0
        fun add(sb: java.nio.ShortBuffer) {
            val k = sb.remaining()
            if (n + k > a.size) a = a.copyOf(maxOf(a.size * 2, n + k))
            sb.get(a, n, k); n += k
        }
        fun toArray() = a.copyOf(n)
    }

    companion object { const val RATE = 22_050 }
}
