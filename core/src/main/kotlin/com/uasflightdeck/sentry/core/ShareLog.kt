package com.uasflightdeck.sentry.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 0.4.2 "Share log" (Settings → Diagnostics): the last 24 h of Sentry's log as plain text, for a pilot to send to
 * the maintainer. Pure: the app hands it the stored lines, the device facts and the strings to redact.
 *
 * Privacy: no personal identifiers beyond the bound serial. Drone callsigns / names (they can carry a pilot's name),
 * the fleet token, and every URL's host (worker, station) are replaced before anything leaves the controller.
 * Crewed-aircraft ids (public ADS-B) are kept: they are what the log is for.
 */
object ShareLog {
    /** One stored log line. The store's file format is "<epoch ms>\t<text>" per line. */
    data class Entry(val timeMs: Long, val text: String)

    data class Device(
        val appVersion: String,
        val model: String,
        val android: String,
        /** "1920x1200 px · 400 dpi (density 2.5) · 768x480 dp · font scale 1.0" */
        val display: String,
    )

    const val WINDOW_MS = 24 * 60 * 60 * 1000L

    fun encode(e: Entry): String = "${e.timeMs}\t${e.text.replace('\n', ' ').replace('\t', ' ')}"

    fun parse(line: String): Entry? {
        val tab = line.indexOf('\t')
        if (tab <= 0) return null
        val t = line.substring(0, tab).toLongOrNull() ?: return null
        return Entry(t, line.substring(tab + 1))
    }

    private val URL = Regex("""(https?|udp)://[^\s/:"')]+(:\d+)?""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val IPV4 = Regex("""\b(\d{1,3}\.){3}\d{1,3}\b""")
    /** The sentences that name the bound drone, whatever history remembers: "Watching X, this controller's aircraft." */
    private val WATCHING = Regex("""\b((?:[Nn]ow )?[Ww]atching) (.+?)(?=, this controller's aircraft|, using |\.|$)""")
    /** Pre-flight "Bound aircraft: <serial> · <callsign> · airborne": keep the serial, drop the callsign. */
    private val BOUND_ITEM = Regex("""(Bound aircraft: [A-Z0-9]+) · (.+?) · """)

    /**
     * Literal [redact] strings (case-insensitive, longest first) -> their placeholder; then URL hosts, e-mail
     * addresses and IPv4 addresses. Blank / 1-2 character literals are ignored (they would shred ordinary words).
     */
    fun scrub(text: String, redact: Map<String, String>): String {
        var s = text
        for ((lit, ph) in redact.entries.filter { it.key.trim().length >= 3 }.sortedByDescending { it.key.length }) {
            s = s.replace(lit.trim(), ph, ignoreCase = true)
        }
        s = WATCHING.replace(s) { m -> "${m.groupValues[1]} <drone callsign>" }
        s = BOUND_ITEM.replace(s) { m -> "${m.groupValues[1]} · <drone callsign> · " }
        s = URL.replace(s) { m -> "${m.groupValues[1]}://<host>" }
        s = EMAIL.replace(s, "<email>")
        s = IPV4.replace(s, "<ip>")
        return s
    }

    fun format(
        nowMs: Long,
        device: Device,
        boundSerial: String?,
        entries: List<Entry>,
        redact: Map<String, String>,
        zone: TimeZone = TimeZone.getDefault(),
        windowMs: Long = WINDOW_MS,
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).apply { timeZone = zone }
        val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
        val from = nowMs - windowMs
        val kept = entries.filter { it.timeMs in from..nowMs }.sortedBy { it.timeMs }
        val sb = StringBuilder()
        sb.append("Flight Deck Sentry log\n")
        sb.append("Generated ${stamp.format(Date(nowMs))} · last ${windowMs / 3_600_000} h · ${kept.size} lines\n")
        sb.append("App ${device.appVersion} · ${device.model} · ${device.android}\n")
        sb.append("Display ${device.display}\n")
        sb.append("Bound serial: ${boundSerial?.takeIf { it.isNotBlank() } ?: "none (controller only)"}\n")
        sb.append("Redacted: drone callsigns and names, fleet token, server / station addresses.\n")
        sb.append("Contents: every alert (time, tier, sound, banner text), pilot actions and mutes, source health changes, ")
        sb.append("pre-flight results, resource summaries.\n")
        sb.append("-".repeat(72)).append('\n')
        if (kept.isEmpty()) sb.append("(no log lines in this period)\n")
        for (e in kept) sb.append(line.format(Date(e.timeMs))).append("  ").append(scrub(e.text, redact)).append('\n')
        return sb.toString()
    }
}
