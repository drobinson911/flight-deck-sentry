package com.uasflightdeck.sentry

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.uasflightdeck.sentry.core.AlertEngine
import com.uasflightdeck.sentry.core.AlertEvent
import com.uasflightdeck.sentry.core.ControllerFix
import com.uasflightdeck.sentry.core.DroneSelector
import com.uasflightdeck.sentry.core.SelectionMode
import com.uasflightdeck.sentry.core.EventKind
import com.uasflightdeck.sentry.core.FleetStatus
import com.uasflightdeck.sentry.core.DemoReplayFixture
import com.uasflightdeck.sentry.core.Geo
import com.uasflightdeck.sentry.core.HealthMonitor
import com.uasflightdeck.sentry.core.LatLon
import com.uasflightdeck.sentry.core.Ownship
import com.uasflightdeck.sentry.core.OwnshipSource
import com.uasflightdeck.sentry.core.Parsers
import com.uasflightdeck.sentry.core.ReplayScenario
import com.uasflightdeck.sentry.core.Severity
import com.uasflightdeck.sentry.core.Target
import com.uasflightdeck.sentry.core.TargetDisplay
import com.uasflightdeck.sentry.core.TrafficMerger
import com.uasflightdeck.sentry.core.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * The always-on part of Sentry. Runs as a foreground service with a partial
 * wake lock so it keeps watching with the screen off and DroneSense in front.
 *
 * Redundancy, stacked (never either/or):
 *  - ownship: fleet our-drones (Flight Deck Air) AND fleet DroneSense snapshot,
 *    chosen by callsign pattern, then serial allowlist (DroneSelector); with no
 *    drone selected, cylinders around THIS controller's GPS are protected.
 *  - traffic: truck station (Overwatch, LAN) AND cloud ADS-B AND the drone's
 *    AirSense contacts (when Flight Deck Air relays them), merged per hex.
 *  - station address: typed URL AND Overwatch UDP beacon discovery.
 *  - every poller has its own backoff; a watchdog restarts any poller (or the
 *    tick loop) that stops making progress; START_STICKY + opt-in boot start.
 */
class SentryService : Service() {

    companion object {
        const val ACTION_ARM = "com.uasflightdeck.sentry.ARM"
        const val ACTION_DISARM = "com.uasflightdeck.sentry.DISARM"
        const val ACTION_REPLAY = "com.uasflightdeck.sentry.REPLAY"
        const val ACTION_REPLAY_STOP = "com.uasflightdeck.sentry.REPLAY_STOP"
        const val ACTION_TEST = "com.uasflightdeck.sentry.TEST"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_CLOUD_VIEW = "cloudView"

        fun send(ctx: Context, action: String, extras: (Intent) -> Unit = {}) {
            val i = Intent(ctx, SentryService::class.java).setAction(action)
            extras(i)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: Settings
    private val http = Http()
    private lateinit var voice: AlertVoice
    private val health = HealthMonitor()
    private var engine = AlertEngine(externalSelection = true)
    private var selector = DroneSelector()
    @Volatile private var mode = Mode.OFF
    private var wakeLock: PowerManager.WakeLock? = null

    // ── live data (immutable snapshots swapped atomically) ─────────────────
    @Volatile private var fdaDrones: List<Ownship> = emptyList()
    @Volatile private var dsDrones: List<Ownship> = emptyList()
    @Volatile private var airsense: List<Target> = emptyList()
    @Volatile private var stationTargets: List<Target> = emptyList()
    @Volatile private var cloudTargets: List<Target> = emptyList()
    @Volatile private var tfrZones: List<Zone> = emptyList()
    @Volatile private var fileZones: List<Zone> = emptyList()
    private var fileZonesStamp = ""
    @Volatile private var discoveredStation: String? = null
    @Volatile private var stationBaseInUse: String? = null
    @Volatile private var gpsFix: Location? = null
    @Volatile private var lastOwnPos: LatLon? = null
    private var armedAtMs = 0L

    // ── supervised pollers ──────────────────────────────────────────────────
    private class Poller(val name: String, val intervalMs: Long, val maxBackoffMs: Long, val block: suspend () -> Unit) {
        @Volatile var heartbeat = 0L
        var job: Job? = null
    }
    private val pollers = ConcurrentHashMap<String, Poller>()
    private var tickJob: Job? = null
    @Volatile private var tickHeartbeat = 0L
    private var watchdogJob: Job? = null
    private var beaconJob: Job? = null
    private var replayJob: Job? = null
    private var lastNotifText = ""
    private var gpsListening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        voice = AlertVoice(this, scope).also { it.start() }
        health.register("fleet", "Drone feed", 10.0)
        health.register("station", "Station link", 10.0)
        health.register("cloud", "Cloud traffic", 20.0)
        health.register("tfr", "T F R data", 45 * 60.0)
        SentryBus.log("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_ARM -> { settings.armed = true; startLive() }
            ACTION_DISARM -> { settings.armed = false; disarm() }
            ACTION_REPLAY -> startReplay(intent.getDoubleExtra(EXTRA_SPEED, settings.replaySpeed),
                intent.getBooleanExtra(EXTRA_CLOUD_VIEW, settings.replayCloudView))
            ACTION_REPLAY_STOP -> stopReplay("stopped")
            ACTION_TEST -> {
                dispatch(AlertEvent(System.currentTimeMillis(), EventKind.TEST, Severity.WARNING,
                    text = "Test callout. Traffic, N388KM, southwest, 1,500 feet, 300 below, converging.",
                    speech = "Test callout. Traffic, N 3 8 8 K M, southwest, 1,500 feet, 300 below, converging."), "test")
                if (mode == Mode.OFF) scope.launch { delay(15_000); if (mode == Mode.OFF) stopSelfCleanly() }
            }
            null -> {   // sticky restart after process death
                SentryBus.log("Service restarted by system (sticky); armed=${settings.armed}")
                if (settings.armed) startLive() else stopSelfCleanly()
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val n = Notifier.status(this, "Flight Deck Sentry", statusLine())
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or (if (hasLoc) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        ServiceCompat.startForeground(this, Notifier.ID_STATUS, n, type)
    }

    // ── live mode ───────────────────────────────────────────────────────────
    private fun startLive() {
        acquireWakeLock()
        if (mode == Mode.LIVE) return
        if (mode == Mode.REPLAY) { settings.armed = true; return }  // replay returns to live when it ends
        mode = Mode.LIVE
        engine = AlertEngine(settings.engineConfig(), externalSelection = true)
        selector = DroneSelector(settings.selectorConfig())
        armedAtMs = System.currentTimeMillis()
        health.setEnabled("fleet", true, armedAtMs)
        health.setEnabled("tfr", true, armedAtMs)
        loadTfrCache()
        startPollers()
        startTickLoop()
        startWatchdog()
        SentryBus.log("ARMED (live)")
        Updater.check(this)   // daily at most; quiet when offline or rate-limited
        dispatch(AlertEvent(armedAtMs, EventKind.SYSTEM, Severity.INFO, "Sentry armed"), "live")
    }

    private fun disarm() {
        SentryBus.log("DISARMED")
        replayJob?.cancel(); replayJob = null
        stopLiveJobs()
        mode = Mode.OFF
        voice.say(AlertEvent(System.currentTimeMillis(), EventKind.SYSTEM, Severity.INFO, "Sentry disarmed"))
        publishOff()
        scope.launch { delay(4000); if (mode == Mode.OFF) stopSelfCleanly() }
    }

    private fun stopLiveJobs() {
        tickJob?.cancel(); tickJob = null
        watchdogJob?.cancel(); watchdogJob = null
        beaconJob?.cancel(); beaconJob = null
        pollers.values.forEach { it.job?.cancel() }
        pollers.clear()
        stopGps()
    }

    private fun startPollers() {
        addPoller(Poller("fleet", 2000, 15_000) { pollFleet() })
        addPoller(Poller("station", 1000, 10_000) { pollStation() })
        addPoller(Poller("cloud", 5000, 30_000) { pollCloud() })
        addPoller(Poller("tfr", 10 * 60_000L, 10 * 60_000L) { pollTfrs() })
    }

    private fun addPoller(p: Poller) {
        pollers[p.name]?.job?.cancel()
        pollers[p.name] = p
        launchPoller(p)
    }

    private fun launchPoller(p: Poller) {
        p.job?.cancel()
        p.heartbeat = System.currentTimeMillis()
        p.job = scope.launch {
            var failures = 0
            while (isActive) {
                p.heartbeat = System.currentTimeMillis()
                val wait = try {
                    p.block(); failures = 0; p.intervalMs
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    health.fail(p.name, e.message ?: e.javaClass.simpleName)
                    if (failures == 1 || failures % 10 == 0) SentryBus.log("${p.name}: ${e.message ?: e.javaClass.simpleName} (x$failures)")
                    // exponential backoff, capped
                    minOf(p.maxBackoffMs, p.intervalMs * (1L shl minOf(failures, 5)))
                } catch (e: StackOverflowError) {
                    // Belt and braces behind Parsers.MAX_DEPTH: a pathological payload must not take the process down.
                    failures++
                    health.fail(p.name, "payload too deeply nested")
                    if (failures == 1 || failures % 10 == 0) SentryBus.log("${p.name}: ${e.message ?: e.javaClass.simpleName} (x$failures)")
                    // exponential backoff, capped
                    minOf(p.maxBackoffMs, p.intervalMs * (1L shl minOf(failures, 5)))
                }
                delay(wait)
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(5000)
                val now = System.currentTimeMillis()
                for (p in pollers.values) {
                    val limit = p.intervalMs + p.maxBackoffMs + 20_000
                    if (p.job?.isActive != true || now - p.heartbeat > limit) {
                        SentryBus.log("Watchdog: restarting poller ${p.name}")
                        launchPoller(p)
                    }
                }
                if (mode == Mode.LIVE && (tickJob?.isActive != true || now - tickHeartbeat > 10_000)) {
                    SentryBus.log("Watchdog: restarting tick loop")
                    startTickLoop()
                }
                if (settings.stationEnabled && settings.stationAutoDiscover) {
                    if (beaconJob?.isActive != true) startBeacon()
                } else { beaconJob?.cancel(); beaconJob = null }
                acquireWakeLock()
                Updater.check(this@SentryService)   // no-op unless 24 h since the last good check (1 h after a failure)
            }
        }
    }

    private fun startBeacon() {
        beaconJob = scope.launch {
            try {
                BeaconListener(this@SentryService) { url ->
                    if (discoveredStation != url) SentryBus.log("Station beacon heard: $url")
                    discoveredStation = url
                }.run()
            } catch (e: Exception) {
                SentryBus.log("Beacon listener: ${e.message}")
                delay(30_000)   // watchdog relaunches
            }
        }
    }

    // ── pollers ─────────────────────────────────────────────────────────────
    private fun base() = settings.workerBase.trimEnd('/')

    private suspend fun pollFleet() {
        val token = settings.fleetToken
        if (token.isBlank()) {
            DroneHistory.feedStatus = FleetStatus.NO_TOKEN
            throw IllegalStateException(FleetStatus.NO_TOKEN)
        }
        val hdr = mapOf("X-Fleet-Token" to token)
        var fda = FleetStatus.Fetch(); var ds = FleetStatus.Fetch()
        try {
            val now = System.currentTimeMillis()
            val p = Parsers.parseOurDrones(http.get(base() + "/api/live/our-drones", hdr), now)
            fdaDrones = p.drones; airsense = p.airsense; fda = FleetStatus.Fetch(count = p.drones.size)
            DroneHistory.observeLive(this, p.drones + dsDrones, now)
        } catch (e: Exception) { fda = FleetStatus.Fetch(error = e.message ?: e.javaClass.simpleName) }
        try {
            val now = System.currentTimeMillis()
            dsDrones = Parsers.parseDroneSense(http.get(base() + "/api/live/dronesense", hdr), now); ds = FleetStatus.Fetch(count = dsDrones.size)
            DroneHistory.observeLive(this, fdaDrones + dsDrones, now)
        } catch (e: Exception) { ds = FleetStatus.Fetch(error = e.message ?: e.javaClass.simpleName) }
        // The row says WHY there are no drones: no token / token rejected / HTTP or network error / none in feed.
        val st = FleetStatus.of(false, fda, ds)
        DroneHistory.feedStatus = st.detail
        if (!st.ok) throw java.io.IOException(st.detail)
        health.ok("fleet", System.currentTimeMillis(), st.detail)
    }

    private suspend fun pollStation() {
        val enabled = settings.stationEnabled
        health.setEnabled("station", enabled, System.currentTimeMillis())
        if (!enabled) { stationTargets = emptyList(); return }
        val cands = listOfNotNull(Settings.normalizeStationBase(settings.stationUrl), discoveredStation).distinct()
        if (cands.isEmpty()) throw IllegalStateException("no station address (type one or wait for beacon)")
        var last: Exception? = null
        for (b in cands) {
            try {
                val t0 = System.currentTimeMillis()
                val text = http.get("$b/data/aircraft.json")
                val now = System.currentTimeMillis()
                stationTargets = Parsers.parseReadsb(text, now, "station")
                health.ok("station", now, "${stationTargets.size} ac · ${now - t0} ms")
                if (stationBaseInUse != b) { stationBaseInUse = b; SentryBus.log("Station: using $b") }
                return
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("station unreachable")
    }

    private suspend fun pollCloud() {
        val enabled = settings.cloudEnabled
        health.setEnabled("cloud", enabled, System.currentTimeMillis())
        if (!enabled) { cloudTargets = emptyList(); return }
        val text = http.get(base() + "/api/live/adsb")
        val now = System.currentTimeMillis()
        val all = Parsers.parseReadsb(text, now, "cloud")
        val c = lastOwnPos
        cloudTargets = if (c != null) TrafficMerger.within(all, c, settings.trafficRadiusNm) else emptyList()
        health.ok("cloud", now, "${cloudTargets.size} near / ${all.size} US")
    }

    private suspend fun pollTfrs() {
        val text = http.get(base() + "/api/tfrs")
        val zones = Parsers.parseTfrs(text)
        if (zones.isEmpty()) throw IllegalStateException("TFR feed parsed empty")
        tfrZones = zones
        runCatching { File(filesDir, "tfrs.json").writeText(text) }
        health.ok("tfr", System.currentTimeMillis(), "${zones.size} TFRs")
    }

    private fun loadTfrCache() {
        val f = File(filesDir, "tfrs.json")
        if (tfrZones.isNotEmpty() || !f.exists()) return
        runCatching {
            tfrZones = Parsers.parseTfrs(f.readText())
            // honest age: the cache is as old as the file, not "now"
            health.ok("tfr", f.lastModified(), "${tfrZones.size} (disk cache)")
            SentryBus.log("TFRs: loaded ${tfrZones.size} from disk cache")
        }
    }

    // ── tick ────────────────────────────────────────────────────────────────
    private fun startTickLoop() {
        tickJob?.cancel()
        tickHeartbeat = System.currentTimeMillis()
        tickJob = scope.launch {
            while (isActive) {
                val t0 = System.currentTimeMillis()
                tickHeartbeat = t0
                if (mode == Mode.LIVE) {
                    try { liveTick(t0) } catch (e: Exception) { SentryBus.log("tick error: $e") }
                }
                delay(maxOf(50L, 1000L - (System.currentTimeMillis() - t0)))
            }
        }
    }

    private fun liveTick(now: Long) {
        syncSettings()
        selector.config = settings.selectorConfig()
        val sel = selector.step(now, fdaDrones + dsDrones, controllerFix())
        val own = sel.ownship
        if (own != null) lastOwnPos = own.pos
        val center = own?.pos ?: lastOwnPos
        val merged = TrafficMerger.merge(stationTargets, cloudTargets, airsense)
        val traffic = if (center != null) TrafficMerger.within(merged, center, settings.trafficRadiusNm) else emptyList()
        val zones = tfrZones + loadFileZones() + circleZone(own) + cylinderZones(sel)
        val res = engine.step(now, own, traffic, zones, trafficAgeSec(now))
        val hEvents = health.step(now)
        (sel.events + res.events + hEvents).forEach { dispatch(it, "live") }
        publish(now, own, sel, res, traffic.size, null)
    }

    /** Controller cylinders, only while protecting the controller (a watched drone uses its own rings). */
    private fun cylinderZones(sel: DroneSelector.Result): List<Zone> {
        if (sel.mode != SelectionMode.CONTROLLER) return emptyList()
        val c = sel.ownship?.pos ?: return emptyList()
        return settings.cylinders.filter { it.enabled && it.valid() }.map { it.toZone(c) }
    }

    /**
     * This controller's position. Elevation: the Settings override if set;
     * else Android's MSL altitude (API 34+ when the platform provides it);
     * else the raw GPS altitude, which is WGS-84 ELLIPSOID height (about
     * 100 ft below MSL in California) and is labelled as such.
     */
    private fun controllerFix(): ControllerFix? {
        val l = gpsFix ?: return null
        val override = settings.controllerElevFt.takeIf { it.isFinite() }
        val (elev, label) = when {
            override != null -> override to "controller GPS · elev set"
            Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude() -> l.mslAltitudeMeters / 0.3048 to "controller GPS · MSL"
            l.hasAltitude() -> l.altitude / 0.3048 to "controller GPS · elev ≈ellipsoid"
            else -> null to "controller GPS · elev unknown"
        }
        return ControllerFix(l.latitude, l.longitude, elev, if (l.hasAccuracy()) l.accuracy.toDouble() else null, l.time, label)
    }

    private fun syncSettings() {
        engine.config = settings.engineConfig()
        voice.enabled = settings.voiceOn
        voice.volume = settings.volume.toFloat()
        startGps()   // always: the controller is the fallback protected position
    }

    private fun trafficAgeSec(now: Long): Double {
        val ages = listOfNotNull(
            if (settings.stationEnabled) health.ageSec("station", now) else null,
            if (settings.cloudEnabled) health.ageSec("cloud", now) else null,
        )
        return ages.minOrNull() ?: ((now - armedAtMs) / 1000.0)
    }

    private fun loadFileZones(): List<Zone> {
        val f = File(filesDir, "geofences.geojson")
        val stamp = if (f.exists()) "${f.lastModified()}:${f.length()}:${settings.geofenceFileName}" else ""
        if (stamp != fileZonesStamp) {
            fileZonesStamp = stamp
            fileZones = if (f.exists()) runCatching { Parsers.parseGeofences(f.readText(), settings.geofenceFileName.ifBlank { "imported" }) }
                .getOrElse { SentryBus.log("Geofence file unreadable: ${it.message}"); emptyList() } else emptyList()
            SentryBus.log("Geofences: ${fileZones.size} polygon zone(s) from file")
        }
        return fileZones
    }

    private fun circleZone(own: Ownship?): List<Zone> {
        if (!settings.circleEnabled) return emptyList()
        val r = settings.circleRadiusNm.takeIf { it > 0 } ?: return emptyList()
        val c = if (settings.circleOnDrone) own?.pos else
            LatLon(settings.circleLat, settings.circleLon).takeIf { it.lat.isFinite() && it.lon.isFinite() }
        c ?: return emptyList()
        val name = if (settings.circleOnDrone) "drone circle" else "circle"
        return listOf(Parsers.circleZone("circle", name, c, r))
    }

    // ── replay ──────────────────────────────────────────────────────────────
    private fun startReplay(speed: Double, cloudView: Boolean) {
        acquireWakeLock()
        replayJob?.cancel()
        val sc = try {
            fun a(n: String) = assets.open("replay/$n").bufferedReader().use { it.readText() }
            DemoReplayFixture.load(a("demo_drone.json"), a("n388km_merged.json"), a("tfr_demo.json"), cloudView)
        } catch (e: Exception) { SentryBus.log("Replay load failed: $e"); return }
        mode = Mode.REPLAY
        val sp = speed.coerceIn(0.25, 16.0)
        val eng = AlertEngine(settings.engineConfig(), externalSelection = true)
        val sel = DroneSelector(settings.selectorConfig())
        SentryBus.log("REPLAY start: ${sc.title} at ${sp}x")
        voice.say(AlertEvent(System.currentTimeMillis(), EventKind.SYSTEM, Severity.INFO, "Replay starting"))
        if (tickJob == null) startTickLoop()
        replayJob = scope.launch {
            var t = sc.startMs
            while (isActive && t <= sc.endMs) {
                val t0 = System.currentTimeMillis()
                tickHeartbeat = t0
                syncSettings()
                eng.config = settings.engineConfig()
                sel.config = settings.selectorConfig()
                // The replay's "fleet feed" is DEMO-1 (callsign "DEMO-1 Pilot"); the
                // simulated controller sits at DEMO-1's launch point.
                val drones = sc.dronesAt(t)
                DroneHistory.observeSession(drones, System.currentTimeMillis())
                val s = sel.step(t, drones, DemoReplayFixture.launchController(t))
                val own = s.ownship
                val traffic = sc.trafficAt(t)
                val res = eng.step(t, own, traffic, sc.zones + cylinderZones(s), 0.0)
                (s.events + res.events).forEach { dispatch(it, "replay", sc) }
                publish(t, own, s, res, traffic.size, sc)
                t += 1000
                delay(maxOf(10L, (1000.0 / sp).toLong() - (System.currentTimeMillis() - t0)))
            }
            if (isActive) stopReplay("finished")
        }
    }

    private fun stopReplay(why: String) {
        replayJob?.cancel(); replayJob = null
        SentryBus.log("REPLAY $why")
        if (settings.armed) { mode = Mode.OFF; startLive() } else {
            mode = Mode.OFF; publishOff()
            scope.launch { delay(8000); if (mode == Mode.OFF) stopSelfCleanly() }
        }
    }

    // ── output ──────────────────────────────────────────────────────────────
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun dispatch(ev: AlertEvent, origin: String, sc: ReplayScenario? = null) {
        val clock = if (sc != null) replayClock(ev.timeMs, sc) else hms.format(Date(ev.timeMs))
        SentryBus.addCallout(ev, clock, sc != null)
        SentryBus.log("CALLOUT[$origin] $clock ${ev.severity.label.uppercase()} ${ev.kind}: ${ev.text}")
        voice.say(ev)
        Notifier.alert(this, ev)
    }

    private fun replayClock(t: Long, sc: ReplayScenario): String {
        val f = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return f.format(Date(t + sc.clockOffsetMs)) + " " + sc.clockZoneLabel
    }

    private fun publish(now: Long, own: Ownship?, sel: DroneSelector.Result, res: AlertEngine.StepResult, nTraffic: Int, sc: ReplayScenario?) {
        val wall = System.currentTimeMillis()
        val rows = health.all().map { SourceRow(it.spoken.replace("T F R", "TFR"), health.stateOf(it, wall), health.ageSec(it.key, wall),
            if (health.stateOf(it, wall) == HealthMonitor.State.OK) it.detail else (it.lastError ?: it.detail)) }
        val cfg = settings.engineConfig()
        val dcfg = settings.targetDisplay()
        val aroundAircraft = sel.mode == SelectionMode.PINNED
        val shown = TargetDisplay.filter(res.targets, aroundAircraft, dcfg)
        val st = UiState(
            mode = mode,
            tickMs = wall,
            ownship = own,
            ownshipFresh = res.ownshipFresh,
            ownshipAgeSec = res.ownshipAgeSec,
            ownshipNote = sel.note,
            sources = rows,
            targets = shown,
            hiddenTargets = res.targets.size - shown.size,
            displayRadiusNm = TargetDisplay.radiusNm(aroundAircraft, dcfg),
            displayAroundAircraft = aroundAircraft,
            displayCeilingFt = dcfg.ceilingFt,
            targetsInRadius = nTraffic,
            watchedZones = res.watchedZones,
            trafficStale = res.trafficStale,
            voice = if (!settings.voiceOn) "muted in Settings" else voice.status,
            voiceOk = settings.voiceOn && voice.ready,
            replayTitle = sc?.title,
            replayClock = sc?.let { replayClock(now, it) },
            replayProgress = sc?.let { ((now - it.startMs).toFloat() / (it.endMs - it.startMs)).coerceIn(0f, 1f) } ?: 0f,
            ringsNm = Triple(cfg.advisoryNm, cfg.cautionNm, cfg.warningNm),
            selectionMode = sel.mode,
            selectionNote = sel.note,
            boundSerial = sel.boundSerial,
            waitingForBound = sel.waitingForBound,
            controllerFix = sel.controllerFix,
            controllerFixAgeSec = sel.controllerFixAgeSec,
            controllerUsable = sel.controllerUsable,
            cylinders = settings.cylinders.filter { it.enabled && it.valid() },
        )
        SentryBus.publish(st)
        updateNotification(st)
    }

    private fun publishOff() {
        SentryBus.publish(UiState(mode = Mode.OFF, tickMs = System.currentTimeMillis(), voice = voice.status, voiceOk = voice.ready))
    }

    private fun statusLine(st: UiState? = null): String {
        st ?: return "Starting…"
        val who = when {
            st.mode == Mode.REPLAY -> "REPLAY ${st.replayClock ?: ""}"
            st.waitingForBound -> "Waiting for this controller's aircraft ${st.boundSerial}"
            st.selectionMode == SelectionMode.CONTROLLER && st.ownship == null -> "No drone · controller GPS unavailable"
            st.selectionMode == SelectionMode.CONTROLLER -> "No aircraft pinned · protecting this controller"
            st.ownship == null -> "No drone position"
            !st.ownshipFresh -> "Drone position LOST (${st.ownship.name})"
            else -> "Watching ${st.ownship.name}"
        }
        val srcs = st.sources.filter { it.state == HealthMonitor.State.OK && (it.name.startsWith("Station") || it.name.startsWith("Cloud")) }
            .joinToString("+") { if (it.name.startsWith("Station")) "station" else "cloud" }.ifEmpty { "NO TRAFFIC SOURCE" }
        return "$who · ${st.targets.size} targets · ${if (st.mode == Mode.REPLAY) "replay" else srcs}"
    }

    private fun updateNotification(st: UiState) {
        val line = statusLine(st)
        if (line == lastNotifText) return
        lastNotifText = line
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(Notifier.ID_STATUS, Notifier.status(this, "Flight Deck Sentry", line))
    }

    // ── GPS fallback ────────────────────────────────────────────────────────
    /** Keep the freshest fix across providers (a coarse network fix never replaces a newer GPS one). */
    private val gpsListener = LocationListener { l -> val cur = gpsFix; if (cur == null || l.time >= cur.time) gpsFix = l }
    @Volatile private var gpsStarting = false

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsListening || gpsStarting) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        gpsStarting = true
        scope.launch(Dispatchers.Main) {
            runCatching {
                // Stacked: GPS AND network provider; the freshest fix wins. Seed with the last known fix
                // (its true age is shown and it is only used while <= 60 s old).
                for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (lm.allProviders.contains(prov)) {
                        lm.getLastKnownLocation(prov)?.let { gpsListener.onLocationChanged(it) }
                        lm.requestLocationUpdates(prov, 1000L, 0f, gpsListener, Looper.getMainLooper())
                    }
                }
                gpsListening = true
                SentryBus.log("Controller GPS listening")
            }.onFailure { SentryBus.log("GPS start failed: ${it.message}") }
            gpsStarting = false
        }
    }

    private fun stopGps() {
        if (!gpsListening) return
        runCatching { getSystemService(LocationManager::class.java)?.removeUpdates(gpsListener) }
        gpsListening = false
    }

    // ── lifecycle ───────────────────────────────────────────────────────────
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val wl = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlightDeckSentry:watch").also { it.setReferenceCounted(false); wakeLock = it }
        if (!wl.isHeld) wl.acquire(12 * 60 * 60 * 1000L)
    }

    private fun stopSelfCleanly() {
        stopLiveJobs()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        SentryBus.log("Service destroyed (mode=$mode)")
        stopLiveJobs()
        replayJob?.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        voice.shutdown()
        scope.cancel()
        publishOff()
        super.onDestroy()
    }
}
