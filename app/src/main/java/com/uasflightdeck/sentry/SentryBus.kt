package com.uasflightdeck.sentry

import android.util.Log
import com.uasflightdeck.sentry.core.AlertEngine
import com.uasflightdeck.sentry.core.AlertEvent
import com.uasflightdeck.sentry.core.ControllerFix
import com.uasflightdeck.sentry.core.Cylinder
import com.uasflightdeck.sentry.core.SelectionMode
import com.uasflightdeck.sentry.core.HealthMonitor
import com.uasflightdeck.sentry.core.Ownship
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Mode { OFF, LIVE, REPLAY }

data class SourceRow(val name: String, val state: HealthMonitor.State, val ageSec: Double?, val detail: String)

/**
 * Everything the screen shows, rebuilt by the service on EVERY tick from live
 * data. The activity also checks [tickMs]: if the engine stops ticking, the UI
 * says so instead of showing a frozen "all clear".
 */
data class UiState(
    val mode: Mode = Mode.OFF,
    val tickMs: Long = 0,
    val ownship: Ownship? = null,
    val ownshipFresh: Boolean = false,
    val ownshipAgeSec: Double? = null,
    val ownshipNote: String = "",
    val sources: List<SourceRow> = emptyList(),
    val targets: List<AlertEngine.TargetView> = emptyList(),
    val targetsInRadius: Int = 0,
    val watchedZones: List<String> = emptyList(),
    val trafficStale: Boolean = false,
    /** "ON (Standard)", "QUIET 4:12 left", "OFF in Settings"... */
    val sounds: String = "",
    val soundsOk: Boolean = true,
    val vibration: String = "",
    /** Internet: "ONLINE" / "OFFLINE" / "…", with how long. */
    val internet: String = "",
    val internetOk: Boolean = true,
    /** The poll rates in use ("airborne · fleet 2 s · cloud 2 s · station 1 s"). */
    val pollRates: String = "",
    /** Muted aircraft: hex -> "muted 47 s" / "ignored". */
    val muted: Map<String, String> = emptyMap(),
    val replayTitle: String? = null,
    val replayClock: String? = null,
    val replayProgress: Float = 0f,
    val ringsNm: Triple<Double, Double, Double> = Triple(3.0, 1.0, 0.5),
    // ── selection (callsign / serial / controller) ──
    val selectionMode: SelectionMode? = null,
    val selectionNote: String = "",
    /** The serial this controller is bound to (as typed), or null when none is pinned. */
    val boundSerial: String? = null,
    /** Bound, but the aircraft isn't being watched right now: "WAITING FOR THIS CONTROLLER'S AIRCRAFT". */
    val waitingForBound: Boolean = false,
    /** Targets shown: radius (around the aircraft or the controller) and ceiling, for the Targets label. */
    val displayRadiusNm: Double = 0.0,
    val displayAroundAircraft: Boolean = false,
    val displayCeilingFt: Double = 0.0,
    /** Aircraft the engine evaluated that the display filter hides (outside the radius or above the ceiling). */
    val hiddenTargets: Int = 0,
    val controllerFix: ControllerFix? = null,
    /** Fix age at [tickMs] (sim time in a replay). */
    val controllerFixAgeSec: Double? = null,
    val controllerUsable: Boolean = false,
    /** Enabled cylinders, drawn on the radar and listed when protecting the controller. */
    val cylinders: List<Cylinder> = emptyList(),
)

object SentryBus {
    const val TAG = "Sentry"
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    fun publish(s: UiState) { _state.value = s }

    /** One alert (or pilot action) as shown: [clock] is sim time (PDT) in replay, device time live. [note] = what was done. */
    data class Callout(val ev: AlertEvent, val clock: String, val replay: Boolean, val note: String = "")

    /** Last callouts, newest first. */
    private val callouts = ArrayDeque<Callout>()
    private val _calloutsFlow = MutableStateFlow<List<Callout>>(emptyList())
    val calloutsFlow: StateFlow<List<Callout>> = _calloutsFlow

    @Synchronized fun addCallout(e: AlertEvent, clock: String, replay: Boolean, note: String = "") {
        callouts.addFirst(Callout(e, clock, replay, note))
        while (callouts.size > 50) callouts.removeLast()
        _calloutsFlow.value = callouts.toList()
    }

    /** The last pre-flight check (null = none run yet), and whether one is running. */
    val preflight = MutableStateFlow<Pair<Long, List<com.uasflightdeck.sentry.core.Preflight.Item>>?>(null)
    val preflightRunning = MutableStateFlow(false)

    /** Ring buffer log shown in the UI; everything also goes to Logcat tag "Sentry". */
    private val log = ArrayDeque<String>()
    private val _logFlow = MutableStateFlow<List<String>>(emptyList())
    val logFlow: StateFlow<List<String>> = _logFlow
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun log(msg: String, level: Int = Log.INFO) {
        Log.println(level, TAG, msg)
        log.addFirst("${fmt.format(Date())}  $msg")
        while (log.size > 300) log.removeLast()
        _logFlow.value = log.toList()
    }
}
