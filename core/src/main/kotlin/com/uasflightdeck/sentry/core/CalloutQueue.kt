package com.uasflightdeck.sentry.core

/**
 * The voice queue (pure, shared by every voice path). At most ONE waiting callout per aircraft: a newer one about
 * N388KM replaces an unspoken older one. Most severe first; at the same severity the closer aircraft first; then
 * first come, first served. A callout that has waited longer than [maxWaitMs] is dropped, never spoken late.
 * Not thread-safe: the caller synchronises.
 */
class CalloutQueue(private val maxWaitMs: Long = 15_000) {
    data class Item(val ev: AlertEvent, val enqueuedMs: Long, val seq: Long)

    private val items = ArrayList<Item>()
    private var seq = 0L

    val size get() = items.size

    fun offer(ev: AlertEvent, nowMs: Long) {
        if (ev.hex != null) items.removeAll { it.ev.hex == ev.hex }
        items += Item(ev, nowMs, seq++)
        items.sortWith(ORDER)
    }

    /** The next callout to speak, or null. Stale ones are removed and handed to [onDropped]. */
    fun poll(nowMs: Long, onDropped: (Item) -> Unit = {}): Item? {
        while (items.isNotEmpty()) {
            val it = items.removeAt(0)
            if (nowMs - it.enqueuedMs > maxWaitMs) { onDropped(it); continue }
            return it
        }
        return null
    }

    fun clear() = items.clear()

    private companion object {
        val ORDER = compareByDescending<Item> { it.ev.severity.rank }
            .thenBy { it.ev.distNm ?: Double.MAX_VALUE }
            .thenBy { it.seq }
    }
}

/**
 * Where each clip of a bundled-voice utterance starts, for logging and tests: clips back to back, with the
 * pause markers as silence. [clipMs] gives each clip's length.
 */
object ClipTimeline {
    const val PAUSE_SHORT_MS = 60
    const val PAUSE_LONG_MS = 150

    data class Entry(val id: String, val startMs: Int, val lengthMs: Int)

    fun build(tokens: List<String>, clipMs: (String) -> Int): List<Entry> {
        var t = 0
        val out = ArrayList<Entry>()
        for (tok in tokens) {
            val len = when (tok) {
                VoiceGrammar.PAUSE_SHORT -> PAUSE_SHORT_MS
                VoiceGrammar.PAUSE_LONG -> PAUSE_LONG_MS
                else -> clipMs(tok)
            }
            out += Entry(tok, t, len)
            t += len
        }
        return out
    }

    fun totalMs(e: List<Entry>) = e.lastOrNull()?.let { it.startMs + it.lengthMs } ?: 0
}
