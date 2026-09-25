package com.uasflightdeck.sentry

import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.uasflightdeck.sentry.core.ShareLog
import java.io.File
import java.util.concurrent.Executors

/**
 * 0.4.2: every [SentryBus.log] line is also appended to `files/sentry-log.txt` ("<epoch ms>\t<text>") so Settings →
 * Diagnostics → Share log can send the last 24 h. Writes happen on one background thread; the file is trimmed to
 * 24 h (and at most [MAX_BYTES]) at start-up and every [TRIM_EVERY] lines.
 */
object LogStore {
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val TRIM_EVERY = 2000
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sentry-log").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    private var appended = 0

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "sentry-log.txt")
        file = f
        io.execute { trim(f) }
    }

    fun append(timeMs: Long, text: String) {
        val f = file ?: return
        io.execute {
            runCatching { f.appendText(ShareLog.encode(ShareLog.Entry(timeMs, text)) + "\n") }
            if (++appended % TRIM_EVERY == 0) trim(f)
        }
    }

    private fun trim(f: File) {
        runCatching {
            if (!f.exists()) return
            val from = System.currentTimeMillis() - ShareLog.WINDOW_MS
            var lines = f.readLines().filter { l -> ShareLog.parse(l)?.let { it.timeMs >= from } == true }
            while (lines.isNotEmpty() && lines.sumOf { it.length + 1L } > MAX_BYTES) lines = lines.drop(lines.size / 10 + 1)
            f.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
        }
    }

    /** The shareable text: the last 24 h, redacted (see [ShareLog]). Runs on the caller's thread (call off the UI). */
    fun build(ctx: Context): String {
        val f = file
        val entries = io.submit<List<ShareLog.Entry>> {
            if (f == null || !f.exists()) emptyList() else f.readLines().mapNotNull { ShareLog.parse(it) }
        }.get()
        val s = Settings(ctx)
        val redact = LinkedHashMap<String, String>()
        DroneHistory.entries(ctx).forEach { redact[it.callsign] = "<drone callsign>" }
        DroneHistory.inFeedNow.forEach { d -> d.callsign?.let { redact[it] = "<drone callsign>" }; redact[d.name] = "<drone callsign>" }
        s.fleetToken.takeIf { it.isNotBlank() }?.let { redact[it] = "<token>" }
        val bound = s.pinnedSerial.takeIf { it.isNotBlank() }
        bound?.let { redact.remove(it) }
        return ShareLog.format(System.currentTimeMillis(), device(ctx), bound, entries, redact)
    }

    fun device(ctx: Context): ShareLog.Device {
        val dm = ctx.resources.displayMetrics
        val cfg = ctx.resources.configuration
        val pInfo = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION") val code = pInfo?.versionCode ?: 0
        return ShareLog.Device(
            appVersion = "${pInfo?.versionName ?: "?"} ($code)",
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            display = "${dm.widthPixels}x${dm.heightPixels} px · ${dm.densityDpi} dpi (density ${dm.density}) · " +
                "${cfg.screenWidthDp}x${cfg.screenHeightDp} dp · font scale ${cfg.fontScale}",
        )
    }
}
