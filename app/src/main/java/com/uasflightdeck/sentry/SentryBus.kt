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
    val voice: String = "starting",
    val voiceOk: Boolean = false,
    val replayTitle: String? = null,
    val replayClock: String? = null,
    val replayProgress: Float = 0f,
    val ringsNm: Triple<Double, Double, Double> = Triple(3.0, 1.0, 0.5),
    // ── selection (callsign / serial / controller) ──
    val selectionMode: SelectionMode? = null,
    val selectionNote: String = "",
    val pattern: String = "",
    val matchCount: Int = 0,
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

    /** One callout as shown: [clock] is sim time (PDT) in replay, device time live. */
    data class Callout(val ev: AlertEvent, val clock: String, val replay: Boolean)

    /** Last callouts, newest first. */
    private val callouts = ArrayDeque<Callout>()
    private val _calloutsFlow = MutableStateFlow<List<Callout>>(emptyList())
    val calloutsFlow: StateFlow<List<Callout>> = _calloutsFlow

    @Synchronized fun addCallout(e: AlertEvent, clock: String, replay: Boolean) {
        callouts.addFirst(Callout(e, clock, replay))
        while (callouts.size > 50) callouts.removeLast()
        _calloutsFlow.value = callouts.toList()
    }

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
