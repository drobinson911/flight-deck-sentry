package com.uasflightdeck.sentry.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Test helper: the demo data through the FULL live path (DroneSelector -> AlertEngine -> MuteBook ->
 * OutputPlanner) at 1 s ticks, exactly as SentryService runs it, with a printable alert timeline.
 */
object ReplayTimeline {
    private val assets = File(System.getProperty("sentry.assets") ?: "../app/src/main/assets")
    private fun read(name: String) = File(assets, "replay/$name").readText()

    fun scenario(cloudView: Boolean = false, crossing: Boolean = false) =
        DemoReplayFixture.load(read("demo_drone.json"), read("n388km_merged.json"), read("tfr_demo.json"), cloudView, crossing)

    data class Run(val outputs: List<OutputPlanner.Output>, val modes: List<Pair<Long, SelectionMode>>, val views: Map<Long, List<AlertEngine.TargetView>>) {
        val events get() = outputs.map { it.event }
    }

    fun run(
        sc: ReplayScenario, pinned: String = DemoReplayFixture.DRONE_SERIAL, cylinders: List<Cylinder> = Cylinder.DEFAULTS,
        cfg: SentryConfig = SentryConfig(), style: AlertStyle = AlertStyle.STANDARD, tickMs: Long = 1000,
    ): Run {
        val selector = DroneSelector(DroneSelector.SelectorConfig(pinnedSerial = pinned))
        val engine = AlertEngine(cfg.copy(cadenceScale = style.cadenceScale, advisorySound = style.advisorySound), externalSelection = true)
        val mutes = MuteBook()
        val out = ArrayList<OutputPlanner.Output>(); val modes = ArrayList<Pair<Long, SelectionMode>>()
        val views = LinkedHashMap<Long, List<AlertEngine.TargetView>>()
        var t = sc.startMs
        while (t <= sc.endMs) {
            val sel = selector.step(t, sc.dronesAt(t), DemoReplayFixture.launchController(t))
            val zones = sc.zones + if (sel.mode == SelectionMode.CONTROLLER && sel.ownship != null)
                cylinders.filter { it.enabled }.map { it.toZone(sel.ownship!!.pos) } else emptyList()
            val res = engine.step(t, sel.ownship, sc.trafficAt(t), zones, 0.0)
            val (_, soundsOn) = mutes.step(t, res.targets, res.events)
            out += OutputPlanner.plan(sel.events + res.events + listOfNotNull(soundsOn), mutes, style, t)
            modes += t to sel.mode
            views[t] = res.targets
            t += tickMs
        }
        return Run(out, modes, views)
    }

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }
    fun hms(ms: Long): String = fmt.format(Date(ms))

    fun line(o: OutputPlanner.Output): String {
        val e = o.event
        val snd = if (o.sounds) "${o.level!!.key}/${o.cue.name.lowercase()}" else if (o.level != null && e.cue != Cue.NONE) "(${o.suppressed})" else "-"
        val b = e.banner
        val s = e.closenessS?.let { String.format(Locale.US, " S=%.2f", it) } ?: ""
        return "${hms(e.timeMs)}  ${(e.tier?.label ?: e.severity.label).padEnd(14)} ${e.kind.name.padEnd(16)} ${e.phase.name.padEnd(10)} " +
            "sound=${snd.padEnd(18)} banner=${o.banner.name.padEnd(6)} " +
            (if (b != null) "${b.title} | ${b.line2} | ${b.line3}${b.line4?.let { " | $it" } ?: ""}" else e.text) + s
    }

    fun dump(label: String, r: Run) {
        println("── $label ──")
        r.outputs.forEach { println(line(it)) }
    }
}
