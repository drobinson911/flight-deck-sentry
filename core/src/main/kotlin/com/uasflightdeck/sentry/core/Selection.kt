package com.uasflightdeck.sentry.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.uasflightdeck.sentry.core.Parsers.arr
import com.uasflightdeck.sentry.core.Parsers.num
import com.uasflightdeck.sentry.core.Parsers.obj
import com.uasflightdeck.sentry.core.Parsers.str
import kotlin.math.roundToLong

/** This controller's own GPS fix. [elevMslFt] null = unknown (limits fail wide). */
data class ControllerFix(
    val lat: Double,
    val lon: Double,
    val elevMslFt: Double?,
    val accuracyM: Double?,
    val timeMs: Long,
    val label: String = "controller GPS",
) {
    val pos get() = LatLon(lat, lon)
}

enum class CylinderAltRef(val label: String) {
    /** Feet above the controller's own elevation (the default). */
    AGL_CONTROLLER("ft above controller"),
    MSL("ft MSL"),
}

/**
 * A protection cylinder centred on the controller. [radiusNm] is stored in nm
 * (the editor accepts nm or ft). Floor/ceiling are in feet, read per [ref].
 */
data class Cylinder(
    val id: String,
    val name: String,
    val radiusNm: Double,
    val floorFt: Double,
    val ceilingFt: Double,
    val ref: CylinderAltRef = CylinderAltRef.AGL_CONTROLLER,
    val enabled: Boolean = true,
) {
    fun valid(): Boolean = radiusNm > 0 && radiusNm.isFinite() && ceilingFt > floorFt && name.isNotBlank()

    /** Build the engine zone around [center]. AGL limits are resolved by the engine against the controller elevation. */
    fun toZone(center: LatLon): Zone {
        val altRef = if (ref == CylinderAltRef.MSL) AltRef.MSL else AltRef.AGL
        val r = Units.nmToM(radiusNm)
        val ring = (0 until 48).map { i ->
            val a = Math.toRadians(i * 7.5)
            Geo.fromEN(center, EN(r * kotlin.math.sin(a), r * kotlin.math.cos(a)))
        }
        return Zone(
            id = "cyl:$id", name = name, kind = ZoneKind.CYLINDER, polygons = listOf(Polygon(ring)),
            floor = AltLimit(floorFt, altRef), ceiling = AltLimit(ceilingFt, altRef),
            circle = Circle(center, radiusNm),
        )
    }

    fun describe(): String {
        val us = java.util.Locale.US
        val rad = if (radiusNm < 1.0) String.format(us, "%,d ft", (radiusNm * Units.FT_PER_NM).roundToLong())
            else String.format(us, "%.1f nm", radiusNm)
        val floor = if (floorFt <= 0 && ref == CylinderAltRef.AGL_CONTROLLER) "SFC" else String.format(us, "%,d", floorFt.toLong())
        return "$name · $rad · $floor–${String.format(us, "%,d", ceilingFt.toLong())} ${ref.label}"
    }

    companion object {
        /** The owner's example: 1 nm / 1,500 ft ops area + 3 nm / 3,000 ft advisory. */
        val DEFAULTS = listOf(
            Cylinder("ops", "ops area", 1.0, 0.0, 1500.0),
            Cylinder("adv", "advisory area", 3.0, 0.0, 3000.0),
        )

        fun toJson(list: List<Cylinder>): String = JsonArray(list.map { c ->
            JsonObject(mapOf(
                "id" to JsonPrimitive(c.id), "name" to JsonPrimitive(c.name),
                "radiusNm" to JsonPrimitive(c.radiusNm), "floorFt" to JsonPrimitive(c.floorFt),
                "ceilingFt" to JsonPrimitive(c.ceilingFt), "ref" to JsonPrimitive(c.ref.name),
                "enabled" to JsonPrimitive(c.enabled),
            ))
        }).toString()

        fun fromJson(text: String): List<Cylinder> = runCatching {
            Parsers.parse(text).arr()?.mapNotNull { el ->
                val o = el.obj() ?: return@mapNotNull null
                Cylinder(
                    id = o.str("id") ?: return@mapNotNull null,
                    name = o.str("name") ?: return@mapNotNull null,
                    radiusNm = o.num("radiusNm") ?: return@mapNotNull null,
                    floorFt = o.num("floorFt") ?: 0.0,
                    ceilingFt = o.num("ceilingFt") ?: return@mapNotNull null,
                    ref = runCatching { CylinderAltRef.valueOf(o.str("ref") ?: "") }.getOrDefault(CylinderAltRef.AGL_CONTROLLER),
                    enabled = o.str("enabled")?.toBooleanStrictOrNull() ?: true,
                )
            }
        }.getOrNull() ?: emptyList()
    }
}

enum class SelectionMode(val label: String) {
    /** Watching this controller's aircraft: the airframe whose serial is pinned. */
    PINNED("pinned"),
    /** Protecting the controller: nothing is pinned, or the pinned airframe is not in the feed. */
    CONTROLLER("controller"),
}

/**
 * What Sentry protects, re-evaluated on EVERY tick from the live drone list.
 *
 * v0.3.3, owner: "just serial number or controller as a fallback", "needs to be a constant, per controller".
 * There are exactly two outcomes:
 *  - the PINNED airframe (airborne, else on the pad), matched by serial trimmed and case-insensitively;
 *  - otherwise the CONTROLLER: cylinders centred on this controller's GPS.
 * No other drone is ever a candidate, so the pilot can never be protecting someone else's aircraft.
 *
 * Speech: "Watching <callsign>, this controller's aircraft." when it is acquired. When it is pinned but
 * missing: "Waiting for this controller's aircraft.", said once on arm (after a short start-up grace, so the
 * first fleet poll can land) and once each time it drops out. It drops out after [SelectorConfig.fallbackSec]:
 * after [SelectorConfig.staleSec] the engine already says "Drone position lost" (the stale position is still
 * passed so the UI shows its age). With nothing pinned: "No aircraft pinned. Protecting this controller."
 */
class DroneSelector(var config: SelectorConfig = SelectorConfig()) {

    companion object {
        /** Trimmed, upper-case; null when blank. Serials are compared this way everywhere. */
        fun normaliseSerial(s: String?): String? = s?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    }

    data class SelectorConfig(
        /** This controller's aircraft (airframe serial), blank = none pinned. */
        val pinnedSerial: String = "",
        val staleSec: Double = 15.0,
        val fallbackSec: Double = 30.0,
        /** A stationary controller's fix is used for this long (its true age is shown). */
        val controllerFixMaxAgeSec: Double = 60.0,
        /** Hold the start-up line this long after arming, so it isn't said 2 s before the first fleet poll lands. */
        val startupGraceSec: Double = 5.0,
    )

    data class Result(
        val mode: SelectionMode,
        /** What the engine should protect: the pinned drone (possibly stale), the controller, or null. */
        val ownship: Ownship?,
        /** The watched (pinned) drone, if in PINNED mode. */
        val drone: Ownship?,
        val events: List<AlertEvent>,
        /** Human note for the UI. */
        val note: String,
        val controllerFix: ControllerFix?,
        val controllerFixAgeSec: Double?,
        val controllerUsable: Boolean,
        /** The pinned serial as typed (trimmed), or null when nothing is pinned. */
        val boundSerial: String? = null,
        /** Pinned, and the pinned airframe is not being watched right now. */
        val waitingForBound: Boolean = false,
    )

    private var mode: SelectionMode? = null
    private var watched: Ownship? = null
    private var firstMs: Long? = null
    private var pendingControllerSpeech: String? = null
    private var controllerGpsAnnouncedLost = false
    private var controllerModeSinceMs = 0L

    val currentMode: SelectionMode? get() = mode

    fun reset() {
        mode = null; watched = null; firstMs = null
        pendingControllerSpeech = null; controllerGpsAnnouncedLost = false
    }

    fun step(nowMs: Long, drones: List<Ownship>, controller: ControllerFix?): Result {
        val cfg = config
        if (firstMs == null) firstMs = nowMs
        val events = ArrayList<AlertEvent>()
        fun age(o: Ownship) = (nowMs - o.posTimeMs) / 1000.0

        val pinned = normaliseSerial(cfg.pinnedSerial)
        val boundSerial = cfg.pinnedSerial.trim().takeIf { pinned != null }
        // Only the pinned airframe is ever looked at. Freshest report wins (two feeds may carry it).
        val report = if (pinned == null) null else drones.filter { normaliseSerial(it.serial) == pinned }.maxByOrNull { it.posTimeMs }
        // Keep the last report when the feed drops it, so the engine can say "position lost" and then we fall back.
        if (report != null && (watched == null || report.posTimeMs >= watched!!.posTimeMs)) watched = report
        val cur = watched?.takeIf { pinned != null && normaliseSerial(it.serial) == pinned }
        val prevMode = mode
        // Acquire only on a fresh report; once watched, hold it through the lost window, then fall back.
        val keep = cur != null && age(cur) <= (if (prevMode == SelectionMode.PINNED) cfg.fallbackSec else cfg.staleSec)

        val newMode = if (keep) SelectionMode.PINNED else SelectionMode.CONTROLLER
        var note = when {
            pinned == null -> "No aircraft pinned: protecting this controller only (Settings)"
            !keep -> "$boundSerial not in the feed; waiting for it (no other drone is ever watched)"
            age(cur!!) > cfg.staleSec -> "$boundSerial: no position for ${age(cur).toInt()} s"
            else -> ""
        }

        if (newMode == SelectionMode.PINNED) {
            val d = cur!!
            val name = d.callsign ?: d.name
            if (prevMode != SelectionMode.PINNED)
                events += AlertEvent(nowMs, EventKind.SELECTION, Severity.INFO,
                    "Watching $name, this controller's aircraft.")
            pendingControllerSpeech = null
            controllerGpsAnnouncedLost = false
        } else {
            if (prevMode != SelectionMode.CONTROLLER) {
                controllerModeSinceMs = nowMs
                val droppedOut = prevMode == SelectionMode.PINNED
                val text = if (pinned != null) "Waiting for this controller's aircraft." else "No aircraft pinned. Protecting this controller."
                if (prevMode == null) pendingControllerSpeech = text                 // start-up: wait for the first fleet poll
                else events += AlertEvent(nowMs, EventKind.SELECTION, if (droppedOut) Severity.CAUTION else Severity.INFO, text)
            }
            pendingControllerSpeech?.let { t ->
                if (nowMs - firstMs!! >= cfg.startupGraceSec * 1000) {
                    events += AlertEvent(nowMs, EventKind.SELECTION, Severity.INFO, t)
                    pendingControllerSpeech = null
                }
            }
            watched = null
        }
        mode = newMode

        // ── controller fix ───────────────────────────────────────────────
        val fixAge = controller?.let { (nowMs - it.timeMs) / 1000.0 }
        val usable = controller != null && fixAge!! <= cfg.controllerFixMaxAgeSec
        if (newMode == SelectionMode.CONTROLLER) {
            if (!usable && !controllerGpsAnnouncedLost && nowMs - controllerModeSinceMs >= cfg.staleSec * 1000) {
                events += AlertEvent(nowMs, EventKind.CONTROLLER_GPS_LOST, Severity.CAUTION,
                    "Controller GPS unavailable. Nothing protected.")
                controllerGpsAnnouncedLost = true
            } else if (usable && controllerGpsAnnouncedLost) {
                events += AlertEvent(nowMs, EventKind.CONTROLLER_GPS_REGAINED, Severity.INFO, "Controller GPS regained.")
                controllerGpsAnnouncedLost = false
            }
            if (!usable) note = listOf(note, "Controller GPS: no usable fix").filter { it.isNotEmpty() }.joinToString(" · ")
        }

        val own: Ownship? = when {
            newMode == SelectionMode.PINNED -> cur
            usable -> Ownship(
                id = "controller", name = "this controller",
                lat = controller!!.lat, lon = controller.lon,
                altMslFt = controller.elevMslFt, altAglFt = controller.elevMslFt?.let { 0.0 },
                // A stationary controller: its last fix stays valid; the true fix age is shown separately.
                posTimeMs = nowMs, source = OwnshipSource.CONTROLLER,
            )
            else -> null
        }
        return Result(newMode, own, if (newMode == SelectionMode.PINNED) cur else null, events, note,
            controller, fixAge, usable, boundSerial = boundSerial, waitingForBound = pinned != null && newMode == SelectionMode.CONTROLLER)
    }
}

/**
 * Every callsign / serial Sentry has seen, with last-seen time, for the
 * Settings autocomplete and the "pick a drone" list. Pure data; the app
 * persists [toJson].
 */
class KnownDrones(private val max: Int = 200) {
    data class Entry(val callsign: String, val serial: String?, val lastSeenMs: Long)

    private val byCallsign = LinkedHashMap<String, Entry>()

    /** Returns true when something new was learned (a new callsign/serial), so the app knows to persist. */
    fun observe(drones: List<Ownship>, nowMs: Long): Boolean {
        var changed = false
        for (d in drones) {
            val cs = (d.callsign ?: d.name).trim()
            if (cs.isEmpty()) continue
            val key = cs.lowercase()
            val prev = byCallsign[key]
            val serial = d.serial?.trim()?.takeIf { it.isNotEmpty() } ?: prev?.serial
            if (prev == null || prev.serial != serial) changed = true
            byCallsign[key] = Entry(cs, serial, maxOf(nowMs, prev?.lastSeenMs ?: 0))
        }
        if (byCallsign.size > max) {
            byCallsign.values.sortedBy { it.lastSeenMs }.take(byCallsign.size - max).forEach { byCallsign.remove(it.callsign.lowercase()) }
            changed = true
        }
        return changed
    }

    fun merge(other: KnownDrones) {
        for (e in other.entries()) {
            val k = e.callsign.lowercase(); val p = byCallsign[k]
            if (p == null || e.lastSeenMs > p.lastSeenMs) byCallsign[k] = e.copy(serial = e.serial ?: p?.serial)
        }
    }

    /** Newest first. */
    fun entries(): List<Entry> = byCallsign.values.sortedByDescending { it.lastSeenMs }
    fun callsigns(): List<String> = entries().map { it.callsign }
    fun serials(): List<String> = entries().mapNotNull { it.serial }.distinct()

    /** The most recently seen callsign flown by airframe [serial] (case-insensitive), or null. */
    fun callsignForSerial(serial: String?): String? {
        val k = DroneSelector.normaliseSerial(serial) ?: return null
        return entries().firstOrNull { DroneSelector.normaliseSerial(it.serial) == k }?.callsign
    }

    fun toJson(): String = JsonArray(entries().map { e ->
        JsonObject(buildMap {
            put("callsign", JsonPrimitive(e.callsign))
            e.serial?.let { put("serial", JsonPrimitive(it)) }
            put("lastSeenMs", JsonPrimitive(e.lastSeenMs))
        })
    }).toString()

    companion object {
        fun fromJson(text: String?): KnownDrones {
            val k = KnownDrones()
            if (text.isNullOrBlank()) return k
            runCatching {
                Parsers.parse(text).arr()?.forEach { el ->
                    val o = el.obj() ?: return@forEach
                    val cs = o.str("callsign") ?: return@forEach
                    k.byCallsign[cs.lowercase()] = Entry(cs, o.str("serial"), o.num("lastSeenMs")?.toLong() ?: 0L)
                }
            }
            return k
        }
    }
}
