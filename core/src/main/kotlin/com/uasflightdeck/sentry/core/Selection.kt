package com.uasflightdeck.sentry.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.uasflightdeck.sentry.core.Parsers.arr
import com.uasflightdeck.sentry.core.Parsers.num
import com.uasflightdeck.sentry.core.Parsers.obj
import com.uasflightdeck.sentry.core.Parsers.str
import kotlin.math.roundToLong

/**
 * "My callsign pattern". Syntax:
 *  - literal text, case-insensitive; spaces and hyphens are optional
 *    separators (both sides are normalised by removing them), so
 *    `DEMO-# Pilot` matches "DEMO-1 Pilot", "DEMO-1 Pilot", "demo1pilot".
 *  - `#` = exactly one digit, `*` = any run of characters (including none).
 *  - `re:<regex>` = a plain regular expression, case-insensitive, matched
 *    anywhere in the raw callsign (anchor it with ^ and $ for a full match).
 * The whole callsign must match a non-regex pattern.
 */
class CallsignPattern private constructor(val source: String, private val regex: Regex?, private val isRe: Boolean) {

    /** True when the pattern could not be compiled (bad `re:`). A blank pattern is valid and matches nothing. */
    val invalid: Boolean get() = regex == null && source.isNotBlank()
    val isBlank: Boolean get() = source.isBlank()

    fun matches(callsign: String?): Boolean {
        val r = regex ?: return false
        callsign ?: return false
        return if (isRe) r.containsMatchIn(callsign) else r.matches(normalise(callsign))
    }

    companion object {
        /** Lower-case, and remove whitespace and hyphens. */
        fun normalise(s: String): String = s.lowercase().filterNot { it.isWhitespace() || it == '-' }

        fun compile(pattern: String): CallsignPattern {
            val p = pattern.trim()
            if (p.isEmpty()) return CallsignPattern(p, null, false)
            if (p.startsWith("re:", ignoreCase = true)) {
                val r = runCatching { Regex(p.substring(3).trim(), RegexOption.IGNORE_CASE) }.getOrNull()
                return CallsignPattern(p, r, true)
            }
            val sb = StringBuilder()
            for (c in normalise(p)) when (c) {
                '#' -> sb.append("\\d")
                '*' -> sb.append(".*")
                else -> sb.append(Regex.escape(c.toString()))
            }
            return CallsignPattern(p, Regex(sb.toString()), false)
        }
    }
}

/** Serial allowlist: comma / newline / whitespace separated, compared case-insensitively. */
object SerialList {
    fun parse(raw: String): Set<String> =
        raw.split(',', '\n', ';', ' ', '\t').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    fun contains(list: Set<String>, serial: String?): Boolean =
        serial != null && serial.trim().uppercase() in list
}

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
    CALLSIGN("callsign"),
    SERIAL("serial"),
    CONTROLLER("controller"),
}

/**
 * Chooses what Sentry protects, re-evaluated on EVERY tick from the live
 * drone list (never "last event wins"):
 *
 *  1. an AIRBORNE drone whose callsign matches the pattern,
 *  2. an AIRBORNE drone whose serial is on the allowlist,
 *  3. a matching drone that is fresh but still on the pad (pattern, then serial),
 *  4. otherwise the controller: cylinders centred on this controller's GPS.
 *
 * Within a tier the freshest position wins; once watching, Sentry stays on
 * that drone while it is still in the best available tier (no flapping
 * between two drones reporting a second apart).
 *
 * The watched drone going stale: after [SelectorConfig.staleSec] the engine
 * says "Drone position lost" (the stale ownship is still passed so the UI
 * shows its age); after [SelectorConfig.fallbackSec] Sentry falls back to the
 * controller cylinders and says so; when a matching drone is fresh again it
 * is re-acquired.
 */
class DroneSelector(var config: SelectorConfig = SelectorConfig()) {

    data class SelectorConfig(
        val pattern: String = "",
        val serials: Set<String> = emptySet(),
        val forceController: Boolean = false,
        val staleSec: Double = 15.0,
        val fallbackSec: Double = 30.0,
        /** A stationary controller's fix is used for this long (its true age is shown). */
        val controllerFixMaxAgeSec: Double = 60.0,
        /** Hold "No drone selected" speech this long after start, so arming doesn't announce controller mode 2 s before the first fleet poll lands. */
        val startupGraceSec: Double = 5.0,
    )

    data class Result(
        val mode: SelectionMode,
        /** What the engine should protect: the watched drone (possibly stale), the controller, or null. */
        val ownship: Ownship?,
        /** The watched drone, if in drone mode. */
        val drone: Ownship?,
        val events: List<AlertEvent>,
        /** Human note for the UI ("2 drones match; watching the freshest", "pattern invalid" …). */
        val note: String,
        val matchCount: Int,
        val controllerFix: ControllerFix?,
        val controllerFixAgeSec: Double?,
        val controllerUsable: Boolean,
    )

    private var mode: SelectionMode? = null
    private var watchedId: String? = null
    private var watched: Ownship? = null
    private var firstMs: Long? = null
    private var pendingControllerSpeech: String? = null
    private var controllerGpsAnnouncedLost = false
    private var controllerModeSinceMs = 0L

    val currentMode: SelectionMode? get() = mode

    fun reset() {
        mode = null; watchedId = null; watched = null; firstMs = null
        pendingControllerSpeech = null; controllerGpsAnnouncedLost = false
    }

    fun step(nowMs: Long, drones: List<Ownship>, controller: ControllerFix?): Result {
        val cfg = config
        if (firstMs == null) firstMs = nowMs
        val events = ArrayList<AlertEvent>()
        val pat = CallsignPattern.compile(cfg.pattern)
        fun age(o: Ownship) = (nowMs - o.posTimeMs) / 1000.0
        fun isFresh(o: Ownship) = age(o) <= cfg.staleSec

        // Freshest report per drone id (two feeds may carry the same drone).
        val byId = drones.groupBy { it.id }.mapValues { (_, v) -> v.maxBy { it.posTimeMs } }
        watchedId?.let { id -> byId[id]?.let { d -> if (watched == null || d.posTimeMs >= watched!!.posTimeMs) watched = d } }

        fun patMatch(o: Ownship) = pat.matches(o.callsign ?: o.name)
        fun serMatch(o: Ownship) = SerialList.contains(cfg.serials, o.serial)
        fun tier(o: Ownship): Int? = when {
            patMatch(o) && o.isAirborne -> 0
            serMatch(o) && o.isAirborne -> 1
            patMatch(o) -> 2
            serMatch(o) -> 3
            else -> null
        }
        fun modeOf(o: Ownship) = if (patMatch(o)) SelectionMode.CALLSIGN else SelectionMode.SERIAL

        // The watched drone stays a candidate while its last position is fresh, even
        // if the feed dropped it from this poll (it ages out after staleSec like any other).
        val held = watched?.takeIf { w -> w.id !in byId }
        val fresh = (byId.values + listOfNotNull(held)).filter { isFresh(it) }
        val ranked = fresh.mapNotNull { d -> tier(d)?.let { d to it } }
        val bestTier = ranked.minOfOrNull { it.second }
        val inBest = ranked.filter { it.second == bestTier }.map { it.first }
        val matchCount = fresh.count { patMatch(it) || serMatch(it) }

        var note = when {
            pat.invalid -> "Callsign pattern is not a valid regex"
            pat.isBlank && cfg.serials.isEmpty() -> "No callsign pattern or serial set (Settings)"
            else -> ""
        }

        val prevMode = mode
        val prevId = watchedId
        val cur = watched
        val curTier = cur?.let { if (isFresh(it)) tier(it) else null }

        var newMode: SelectionMode
        var newDrone: Ownship? = null
        var fallbackReason: String? = null

        if (cfg.forceController) {
            newMode = SelectionMode.CONTROLLER
            fallbackReason = "chosen"
        } else if (cur != null && curTier != null && curTier == bestTier) {
            newMode = modeOf(cur); newDrone = cur                            // keep watching
        } else if (inBest.isNotEmpty()) {
            newDrone = inBest.maxBy { it.posTimeMs }; newMode = modeOf(newDrone)
        } else if (cur != null && !isFresh(cur) && tier(cur) != null && age(cur) <= cfg.fallbackSec) {
            // stale but inside the grace window: keep it (engine says "Drone position lost")
            newMode = prevMode ?: modeOf(cur); newDrone = cur
        } else {
            newMode = SelectionMode.CONTROLLER
            fallbackReason = if (cur != null && prevMode != SelectionMode.CONTROLLER && !isFresh(cur)) "lost" else "none"
        }

        // ── transitions + speech ─────────────────────────────────────────
        if (newMode != SelectionMode.CONTROLLER) {
            val d = newDrone!!
            val name = d.callsign ?: d.name
            val bySerial = if (newMode == SelectionMode.SERIAL) " by serial" else ""
            if (prevId != d.id) {
                val multi = inBest.size > 1 && inBest.contains(d)
                val lead = when {
                    multi -> "Multiple matches, watching"
                    prevMode == null || prevMode == SelectionMode.CONTROLLER -> "Watching"
                    else -> "Now watching"
                }
                events += AlertEvent(nowMs, EventKind.SELECTION, Severity.INFO,
                    "$lead $name$bySerial.", "$lead ${Phrasing.spelledId(name)}$bySerial.")
            }
            pendingControllerSpeech = null
            controllerGpsAnnouncedLost = false
            watchedId = d.id; watched = d
            if (inBest.size > 1) note = listOf(note, "${inBest.size} drones match; watching ${name}").filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            if (prevMode != SelectionMode.CONTROLLER) {
                controllerModeSinceMs = nowMs
                val text = when (fallbackReason) {
                    "chosen" -> "Protecting this controller."
                    "lost" -> "No drone position for ${cfg.fallbackSec.toInt()} seconds. Protecting this controller."
                    else -> "No drone selected. Protecting this controller."
                }
                // At start-up, hold the "no drone" line for a few seconds: the first fleet poll may still be in flight.
                if (prevMode == null && fallbackReason == "none") pendingControllerSpeech = text
                else events += AlertEvent(nowMs, EventKind.SELECTION, Severity.CAUTION.takeIf { fallbackReason == "lost" } ?: Severity.INFO, text)
            }
            pendingControllerSpeech?.let { t ->
                if (nowMs - firstMs!! >= cfg.startupGraceSec * 1000) {
                    events += AlertEvent(nowMs, EventKind.SELECTION, Severity.INFO, t)
                    pendingControllerSpeech = null
                }
            }
            watchedId = null; watched = null
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
            newMode != SelectionMode.CONTROLLER -> newDrone
            usable -> Ownship(
                id = "controller", name = "this controller",
                lat = controller!!.lat, lon = controller.lon,
                altMslFt = controller.elevMslFt, altAglFt = controller.elevMslFt?.let { 0.0 },
                // A stationary controller: its last fix stays valid; the true fix age is shown separately.
                posTimeMs = nowMs, source = OwnshipSource.CONTROLLER,
            )
            else -> null
        }
        return Result(newMode, own, if (newMode != SelectionMode.CONTROLLER) newDrone else null, events, note,
            matchCount, controller, fixAge, usable)
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
