package com.uasflightdeck.sentry.core

/**
 * v0.4.0 output: the controller's own sounds + a vibration + a heads-up banner. NO voice. Everything here is pure
 * so every rule is unit-tested; the app only plays, vibrates and posts what [OutputPlanner] decides.
 */
enum class SoundLevel(
    val key: String,
    val label: String,
    /** Played on the alarm stream (USAGE_ALARM); the rest on USAGE_NOTIFICATION_EVENT. */
    val alarmStream: Boolean,
    /**
     * Default sound, by the file name in the controller's own sound library (first one present wins). If none of
     * these exists on the device: the default alarm sound ([alarmStream] levels), then the default notification
     * sound, then a generated tone. Never silence. Verified present on the Android 10 (API 29) image.
     */
    val defaults: List<String>,
    /** Vibration waveform (off, on, off, on ...), starting with no delay. */
    val vibration: LongArray,
) {
    ADVISORY("advisory", "Advisory (inside 3 mi)", false, listOf("Tejat.ogg", "Talitha.ogg"), longArrayOf(0, 150)),
    TRACK("track", "Track alert", false, listOf("Canopus.ogg", "Cricket.ogg"), longArrayOf(0, 150)),
    CAUTION("caution", "Caution (inside 1 mi)", false, listOf("Capella.ogg", "Castor.ogg"), longArrayOf(0, 150, 100, 150)),
    WARNING("warning", "Warning", true, listOf("Alarm_Beep_03.ogg", "Oxygen.ogg"), longArrayOf(0, 300, 150, 300, 150, 300)),
    COLLISION("collision", "Collision risk", true, listOf("Alarm_Buzzer.ogg", "Alarm_Beep_01.ogg"), longArrayOf(0, 500, 200, 500, 200, 500)),
    ZONE("zone", "Zone entry (TFR / geofence / cylinder)", false, listOf("Spica.ogg", "Proxima.ogg"), longArrayOf(0, 150, 100, 150)),
    PASSING("passing", "Passing, diverging (after a warning)", false, listOf("Deneb.ogg", "Shaula.ogg"), longArrayOf(0, 100)),
    INTERNET("internet", "Internet lost / regained", false, listOf("Drip.ogg", "Rubidium.ogg"), longArrayOf(0, 100)),
    BOUND("bound", "Bound aircraft acquired / lost", false, listOf("Hojus.ogg", "Polaris.ogg"), longArrayOf(0, 100)),
    PREFLIGHT("preflight", "Pre-flight result · sounds back on", false, listOf("Voila.ogg", "Antimony.ogg"), longArrayOf(0, 100));

    val isTraffic get() = this in TRAFFIC

    companion object {
        val TRAFFIC = setOf(ADVISORY, TRACK, CAUTION, WARNING, COLLISION, ZONE, PASSING)
        /** Most urgent first: of several sounds in one tick, only the first is played. */
        val PRIORITY = listOf(COLLISION, WARNING, ZONE, CAUTION, TRACK, PASSING, ADVISORY, INTERNET, BOUND, PREFLIGHT)
        fun of(key: String): SoundLevel? = entries.firstOrNull { it.key == key }

        fun forTier(t: Tier): SoundLevel? = when (t) {
            Tier.COLLISION -> COLLISION
            Tier.WARNING -> WARNING
            Tier.CAUTION -> CAUTION
            Tier.TRACK -> TRACK
            Tier.ADVISORY -> ADVISORY
            Tier.NONE -> null
        }
    }
}

/**
 * STANDARD: advisory banner-only (fleet simulation: 87 of 98 false alarms were the 3 nm ring alone), caution and up
 * sound. QUIET: every cadence interval doubled, advisory & caution banner-only. LOUD: advisory sounds too.
 */
enum class AlertStyle(val label: String) {
    STANDARD("Standard"), QUIET("Quiet"), LOUD("Loud");
    val cadenceScale get() = if (this == QUIET) 2.0 else 1.0
    val advisorySound get() = this == LOUD
}

/**
 * How a sound is played. FULL (escalations, the collision tone): the whole sound, capped at [FULL_MAX_MS]. SHORT
 * (cadence repeats): the softer variant, [SHORT_VOLUME] of the level's volume, cut at [SHORT_MAX_MS].
 */
object Playback {
    const val FULL_MAX_MS = 2_500L
    const val SHORT_MAX_MS = 700L
    const val SHORT_VOLUME = 0.6f

    data class Spec(val maxMs: Long, val volumeScale: Float)

    fun spec(cue: Cue): Spec? = when (cue) {
        Cue.FULL -> Spec(FULL_MAX_MS, 1f)
        Cue.SHORT -> Spec(SHORT_MAX_MS, SHORT_VOLUME)
        Cue.NONE -> null
    }
}

/**
 * Which sound a level plays. [stored] is the Settings value: "" = Sentry's default for the level, or a content URI
 * the pilot picked. Resolution never ends in silence: picked URI (if it still resolves) -> the level's default file
 * -> the default alarm sound (alarm-stream levels) -> the default notification sound -> a generated tone.
 */
object SoundChoice {
    enum class Source { PICKED, LEVEL_DEFAULT, DEFAULT_ALARM, DEFAULT_NOTIFICATION, TONE }
    data class Resolved(val uri: String?, val source: Source)

    /**
     * @param resolves true when a URI can actually be opened on this device.
     * @param byName the content URI of a sound in the device library with that file name, or null.
     */
    fun resolve(
        level: SoundLevel, stored: String, resolves: (String) -> Boolean, byName: (String) -> String?,
        defaultAlarm: String?, defaultNotification: String?,
    ): Resolved {
        val s = stored.trim()
        if (s.isNotEmpty() && resolves(s)) return Resolved(s, Source.PICKED)
        for (n in level.defaults) byName(n)?.takeIf(resolves)?.let { return Resolved(it, Source.LEVEL_DEFAULT) }
        if (level.alarmStream) defaultAlarm?.takeIf(resolves)?.let { return Resolved(it, Source.DEFAULT_ALARM) }
        defaultNotification?.takeIf(resolves)?.let { return Resolved(it, Source.DEFAULT_NOTIFICATION) }
        return Resolved(null, Source.TONE)
    }
}

/**
 * Turns the engine's events into what the pilot gets: which sound (one per tick at most), whether it vibrates,
 * and what happens to the banner. Mutes ([MuteBook]) and the alert style are applied here.
 */
object OutputPlanner {
    enum class BannerAction {
        /** Heads-up re-post (escalations, zone entries, housekeeping). */
        POPUP,
        /** In-place update, no heads-up. */
        SILENT,
        /** Remove the aircraft's banner now (clear, track lost, on the ground). */
        CANCEL,
        /** Screen only (log + Last alerts). */
        NONE,
    }

    data class Output(
        val event: AlertEvent,
        val level: SoundLevel?,
        /** FULL / SHORT / NONE after mutes, style and the one-sound-per-tick rule. */
        val cue: Cue,
        val banner: BannerAction,
        /** Why a sound the engine asked for was not played ("muted", "quiet", "quiet style", "another sound this tick"). */
        val suppressed: String? = null,
    ) {
        val sounds get() = cue != Cue.NONE && level != null
        val vibrates get() = sounds
    }

    /** The sound level an event would use, or null for screen-only / silent events. */
    fun levelFor(ev: AlertEvent): SoundLevel? = when (ev.kind) {
        EventKind.TRAFFIC -> ev.tier?.let { SoundLevel.forTier(it) }
        EventKind.TFR_ENTRY, EventKind.GEOFENCE_ENTRY, EventKind.CYLINDER_ENTRY -> SoundLevel.ZONE
        EventKind.PASSING -> SoundLevel.PASSING
        EventKind.INTERNET_LOST, EventKind.INTERNET_REGAINED -> SoundLevel.INTERNET
        // Bound aircraft acquired ("Watching ...") / position lost (first time) / regained. The start-up and
        // "Waiting for this controller's aircraft" lines are screen-only (the loss was already alerted).
        EventKind.SELECTION -> if (ev.text.startsWith("Watching")) SoundLevel.BOUND else null
        EventKind.OWNSHIP_ACQUIRED, EventKind.OWNSHIP_REGAINED -> SoundLevel.BOUND
        EventKind.OWNSHIP_LOST -> if (ev.repeat) null else SoundLevel.BOUND
        EventKind.PREFLIGHT, EventKind.SOUNDS_ON -> SoundLevel.PREFLIGHT
        EventKind.TEST -> SoundLevel.WARNING
        else -> null
    }

    fun isTrafficKind(k: EventKind) = k in TRAFFIC_KINDS
    private val TRAFFIC_KINDS = setOf(EventKind.TRAFFIC, EventKind.PASSING, EventKind.NO_LONGER_FACTOR, EventKind.CLEAR,
        EventKind.TRACK_LOST, EventKind.TFR_ENTRY, EventKind.GEOFENCE_ENTRY, EventKind.CYLINDER_ENTRY)

    fun plan(events: List<AlertEvent>, mutes: MuteBook, style: AlertStyle, nowMs: Long): List<Output> {
        val out = events.map { ev -> single(ev, mutes, style, nowMs) }
        // One sound per tick: the most urgent.
        val winner = out.filter { it.sounds }.minByOrNull { SoundLevel.PRIORITY.indexOf(it.level!!) }
        return out.map { if (it.sounds && it !== winner) it.copy(cue = Cue.NONE, suppressed = "another sound this tick") else it }
    }

    private fun single(ev: AlertEvent, mutes: MuteBook, style: AlertStyle, nowMs: Long): Output {
        val level = levelFor(ev)
        if (!isTrafficKind(ev.kind)) {
            // Housekeeping: popup + sound; everything else screen-only.
            return if (level != null) Output(ev, level, Cue.FULL, BannerAction.POPUP)
            else Output(ev, null, Cue.NONE, BannerAction.NONE)
        }
        val banner = when {
            ev.kind == EventKind.CLEAR || ev.kind == EventKind.TRACK_LOST -> BannerAction.CANCEL
            ev.banner == null -> BannerAction.NONE
            ev.popup -> BannerAction.POPUP
            else -> BannerAction.SILENT
        }
        var cue = if (level == null) Cue.NONE else ev.cue
        var why: String? = null
        if (cue != Cue.NONE) {
            // Escalations, zone entries and COLLISION RISK always sound: every mute gives way to them.
            val bypass = ev.phase == Phase.ESCALATION || ev.popup || ev.tier == Tier.COLLISION
            when {
                level == SoundLevel.ADVISORY && !style.advisorySound -> { cue = Cue.NONE; why = "advisory is banner-only" }
                style == AlertStyle.QUIET && level == SoundLevel.CAUTION -> { cue = Cue.NONE; why = "quiet style" }
                bypass -> Unit
                ev.hex != null && mutes.aircraftMute(ev.hex, nowMs) != null -> { cue = Cue.NONE; why = "muted" }
                mutes.quietActive(nowMs) -> { cue = Cue.NONE; why = "quiet 5 min" }
            }
        }
        return Output(ev, level, cue, banner, why)
    }
}
