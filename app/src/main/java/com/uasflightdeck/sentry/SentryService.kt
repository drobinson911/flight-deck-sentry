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
import com.uasflightdeck.sentry.core.EventKind
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
 *  - ownship: fleet our-drones (Flight Deck Air) AND fleet DroneSense snapshot;
 *    manual pin / controller GPS as automatic fallback when both go stale.
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
    private var engine = AlertEngine()
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
        engine = AlertEngine(settings.engineConfig())
        armedAtMs = System.currentTimeMillis()
        health.setEnabled("fleet", true, armedAtMs)
        health.setEnabled("tfr", true, armedAtMs)
        loadTfrCache()
        startPollers()
        startTickLoop()
        startWatchdog()
        SentryBus.log("ARMED (live)")
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
        if (token.isBlank()) throw IllegalStateException("no fleet token (Settings)")
        val hdr = mapOf("X-Fleet-Token" to token)
        var ok = 0; var err: String? = null
        try {
            val now = System.currentTimeMillis()
            val p = Parsers.parseOurDrones(http.get(base() + "/api/live/our-drones", hdr), now)
            fdaDrones = p.drones; airsense = p.airsense; ok++
        } catch (e: Exception) { err = "our-drones: ${e.message}" }
        try {
            val now = System.currentTimeMillis()
            dsDrones = Parsers.parseDroneSense(http.get(base() + "/api/live/dronesense", hdr), now); ok++
        } catch (e: Exception) { err = (err?.let { "$it; " } ?: "") + "dronesense: ${e.message}" }
        if (ok == 0) throw java.io.IOException(err)
        val n = fdaDrones.size + dsDrones.size
        health.ok("fleet", System.currentTimeMillis(), (if (n == 0) "0 airborne" else "$n drone${if (n > 1) "s" else ""}") + (err?.let { " (1 of 2 failed)" } ?: ""))
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
        val (own, note) = selectOwnship(now)
        if (own != null) lastOwnPos = own.pos
        val center = own?.pos ?: lastOwnPos
        val merged = TrafficMerger.merge(stationTargets, cloudTargets, airsense)
        val traffic = if (center != null) TrafficMerger.within(merged, center, settings.trafficRadiusNm) else emptyList()
        val zones = tfrZones + loadFileZones() + circleZone(own)
        val res = engine.step(now, own, traffic, zones, trafficAgeSec(now))
        val hEvents = health.step(now)
        (res.events + hEvents).forEach { dispatch(it, "live") }
        publish(now, own, note, res, traffic.size, null)
    }

    private fun syncSettings() {
        engine.config = settings.engineConfig()
        voice.enabled = settings.voiceOn
        voice.volume = settings.volume.toFloat()
        if (settings.manualMode == ManualMode.DEVICE_GPS) startGps() else stopGps()
    }

    private fun trafficAgeSec(now: Long): Double {
        val ages = listOfNotNull(
            if (settings.stationEnabled) health.ageSec("station", now) else null,
            if (settings.cloudEnabled) health.ageSec("cloud", now) else null,
        )
        return ages.minOrNull() ?: ((now - armedAtMs) / 1000.0)
    }

    /** Fleet feed first (freshest matching drone); manual only when the feed has nothing fresh. */
    private fun selectOwnship(now: Long): Pair<Ownship?, String> {
        val filter = settings.droneFilter.trim().lowercase()
        val all = fdaDrones + dsDrones
        val matched = if (filter.isEmpty()) all else all.filter { it.name.lowercase().contains(filter) || it.id.lowercase().contains(filter) }
        val best = matched.maxByOrNull { it.posTimeMs }
        val distinctNames = matched.map { it.name }.distinct()
        var note = when {
            all.isEmpty() -> "no drone airborne in fleet feed"
            filter.isNotEmpty() && matched.isEmpty() -> "no drone matches \"${settings.droneFilter}\" (${all.size} airborne)"
            filter.isEmpty() && distinctNames.size > 1 -> "auto-selected freshest of ${distinctNames.size}: set a callsign in Settings"
            else -> ""
        }
        val fresh = best != null && now - best.posTimeMs <= 15_000
        if (!fresh) {
            manualOwnship(now)?.let { return it to note }
        }
        return best to note
    }

    private fun manualOwnship(now: Long): Ownship? = when (settings.manualMode) {
        ManualMode.OFF -> null
        ManualMode.PINNED -> {
            val la = settings.manualLat; val lo = settings.manualLon
            if (la.isFinite() && lo.isFinite()) Ownship("manual", "manual pin", la, lo,
                settings.manualAltMslFt.takeIf { it.isFinite() }, null, now, OwnshipSource.MANUAL_PINNED) else null
        }
        ManualMode.DEVICE_GPS -> gpsFix?.let { l ->
            // Location.altitude is WGS84 ellipsoid height (~100 ft off MSL in CA): labelled GPS/estimated.
            Ownship("gps", "controller GPS", l.latitude, l.longitude,
                if (l.hasAltitude()) l.altitude / 0.3048 + settings.gpsAglFt else null, null,
                l.time, OwnshipSource.DEVICE_GPS)
        }
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
        val eng = AlertEngine(settings.engineConfig())
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
                val own = sc.ownshipAt(t)
                val traffic = sc.trafficAt(t)
                val res = eng.step(t, own, traffic, sc.zones, 0.0)
                res.events.forEach { dispatch(it, "replay", sc) }
                publish(t, own, "", res, traffic.size, sc)
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

    private fun publish(now: Long, own: Ownship?, note: String, res: AlertEngine.StepResult, nTraffic: Int, sc: ReplayScenario?) {
        val wall = System.currentTimeMillis()
        val rows = health.all().map { SourceRow(it.spoken.replace("T F R", "TFR"), health.stateOf(it, wall), health.ageSec(it.key, wall),
            if (health.stateOf(it, wall) == HealthMonitor.State.OK) it.detail else (it.lastError ?: it.detail)) }
        val cfg = settings.engineConfig()
        val st = UiState(
            mode = mode,
            tickMs = wall,
            ownship = own,
            ownshipFresh = res.ownshipFresh,
            ownshipAgeSec = res.ownshipAgeSec,
            ownshipNote = note,
            sources = rows,
            targets = res.targets,
            targetsInRadius = nTraffic,
            watchedZones = res.watchedZones,
            trafficStale = res.trafficStale,
            voice = if (!settings.voiceOn) "muted in Settings" else voice.status,
            voiceOk = settings.voiceOn && voice.ready,
            replayTitle = sc?.title,
            replayClock = sc?.let { replayClock(now, it) },
            replayProgress = sc?.let { ((now - it.startMs).toFloat() / (it.endMs - it.startMs)).coerceIn(0f, 1f) } ?: 0f,
            ringsNm = Triple(cfg.advisoryNm, cfg.cautionNm, cfg.warningNm),
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
            st.ownship == null -> "No drone position"
            !st.ownshipFresh -> "Drone position LOST (${st.ownship.name})"
            st.ownship.source == OwnshipSource.MANUAL_PINNED || st.ownship.source == OwnshipSource.DEVICE_GPS -> "Watching ${st.ownship.name} (manual)"
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
    private val gpsListener = LocationListener { l -> gpsFix = l }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsListening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        scope.launch(Dispatchers.Main) {
            runCatching {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
                gpsListening = true
                SentryBus.log("Controller GPS listening")
            }.onFailure { SentryBus.log("GPS start failed: ${it.message}") }
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
