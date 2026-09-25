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
import com.uasflightdeck.sentry.core.AlertLatency
import com.uasflightdeck.sentry.core.Banner
import com.uasflightdeck.sentry.core.Cue
import com.uasflightdeck.sentry.core.InternetMonitor
import com.uasflightdeck.sentry.core.MuteBook
import com.uasflightdeck.sentry.core.OutputPlanner
import com.uasflightdeck.sentry.core.Phase
import com.uasflightdeck.sentry.core.PollRates
import com.uasflightdeck.sentry.core.Preflight
import com.uasflightdeck.sentry.core.SoundLevel
import com.uasflightdeck.sentry.core.SystemText
import com.uasflightdeck.sentry.core.Tier
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
        const val ACTION_PREFLIGHT = "com.uasflightdeck.sentry.PREFLIGHT"
        /** Banner actions (never open the app). */
        const val ACTION_GOT_IT = "com.uasflightdeck.sentry.GOT_IT"
        const val ACTION_IGNORE = "com.uasflightdeck.sentry.IGNORE"
        const val ACTION_QUIET = "com.uasflightdeck.sentry.QUIET"
        /** Housekeeping banner tap: dismiss it. */
        const val ACTION_DISMISS = "com.uasflightdeck.sentry.DISMISS"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_CLOUD_VIEW = "cloudView"
        const val EXTRA_CROSSING = "crossing"

        fun send(ctx: Context, action: String, extras: (Intent) -> Unit = {}) {
            val i = Intent(ctx, SentryService::class.java).setAction(action)
            extras(i)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: Settings
    private val http = Http(onResponse = { lastReachOkMs = System.currentTimeMillis() }, onNetFail = { lastReachFailMs = System.currentTimeMillis() })
    private lateinit var sound: SoundPlayer
    private lateinit var notifier: Notifier
    private val mutes = MuteBook()
    private val internet = InternetMonitor()
    @Volatile private var networkUp = true
    @Volatile private var lastReachOkMs: Long? = null
    @Volatile private var lastReachFailMs: Long? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    /** Poll rates in use (low power while the bound aircraft is on the pad or absent). */
    @Volatile private var rates = PollRates.LOW_POWER
    /** The engine's clock at the last tick (sim time in a replay): mutes run on it. */
    @Volatile private var clockNowMs = System.currentTimeMillis()
    @Volatile private var lastViews: List<AlertEngine.TargetView> = emptyList()
    @Volatile private var replaySpeed = 1.0
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
    private class Poller(val name: String, val interval: () -> Long, val maxBackoffMs: Long, val block: suspend () -> Unit) {
        val intervalMs get() = interval()
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
        settings.migrate().forEach { SentryBus.log("Settings: $it") }
        sound = SoundPlayer(this)
        notifier = Notifier(this)
        val lostAfter = mapOf("fleet" to 15.0, "station" to 10.0, "cloud" to 20.0, "tfr" to 45 * 60.0)
        SystemText.HEALTH_SOURCES.forEach { (k, name) -> health.register(k, name, lostAfter.getValue(k)) }
        watchNetwork()
        SentryBus.log("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_ARM -> { settings.armed = true; startLive() }
            ACTION_DISARM -> { settings.armed = false; disarm() }
            ACTION_REPLAY -> startReplay(intent.getDoubleExtra(EXTRA_SPEED, settings.replaySpeed),
                intent.getBooleanExtra(EXTRA_CLOUD_VIEW, settings.replayCloudView), intent.getBooleanExtra(EXTRA_CROSSING, settings.replayCrossing))
            ACTION_REPLAY_STOP -> stopReplay("stopped")
            ACTION_PREFLIGHT -> { runPreflight(); stopLaterIfIdle(20_000) }
            ACTION_GOT_IT, ACTION_IGNORE -> {
                val hex = intent.getStringExtra("hex"); val id = intent.getStringExtra("id") ?: hex
                if (hex != null && mode != Mode.OFF) {
                    val miss = lastViews.firstOrNull { it.hex == hex }?.missNm
                    pilotAction(if (intent.action == ACTION_GOT_IT) mutes.gotIt(hex, id!!, clockNowMs, miss) else mutes.ignore(hex, id!!, clockNowMs, miss))
                }
                stopLaterIfIdle(3_000)
            }
            ACTION_QUIET -> { if (mode != Mode.OFF) pilotAction(mutes.quiet(clockNowMs)); stopLaterIfIdle(3_000) }
            ACTION_DISMISS -> {
                val nid = intent.getIntExtra("nid", 0)
                if (nid != 0) runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(nid) }
                stopLaterIfIdle(3_000)
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
        selector = DroneSelector(settings.selectorConfig()); loggedBound = ""
        internet.reset()
        armedAtMs = System.currentTimeMillis()
        health.setEnabled("fleet", true, armedAtMs)
        health.setEnabled("tfr", true, armedAtMs)
        loadTfrCache()
        startPollers()
        startTickLoop()
        startWatchdog()
        SentryBus.log("ARMED (live)")
        Updater.check(this)   // daily at most; quiet when offline or rate-limited
        deliver(OutputPlanner.plan(listOf(AlertEvent(armedAtMs, EventKind.SYSTEM, Severity.INFO, SystemText.ARMED)), mutes, settings.alertStyle, armedAtMs), "live", null, armedAtMs)
    }

    private fun disarm() {
        SentryBus.log("DISARMED")
        replayJob?.cancel(); replayJob = null
        stopLiveJobs()
        mode = Mode.OFF
        sound.stop()
        notifier.cancelAll()
        SentryBus.addCallout(AlertEvent(System.currentTimeMillis(), EventKind.SYSTEM, Severity.INFO, SystemText.DISARMED), hms.format(Date()), false)
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
        // Low power (0.4.0): fleet + cloud every 5 s while the bound aircraft is on the pad or absent, 2 s airborne.
        addPoller(Poller("fleet", { rates.fleetMs }, 15_000) { pollFleet() })
        addPoller(Poller("station", { rates.stationMs }, 10_000) { pollStation() })
        addPoller(Poller("cloud", { rates.cloudMs }, 30_000) { pollCloud() })
        addPoller(Poller("tfr", { 10 * 60_000L }, 10 * 60_000L) { pollTfrs() })
        // Internet reachability: any HTTP response from the worker in the last 15 s counts; else probe it.
        addPoller(Poller("net", { 15_000L }, 15_000) { probeInternet() })
    }

    private suspend fun probeInternet() {
        val ok = lastReachOkMs
        if (ok != null && System.currentTimeMillis() - ok < 15_000) return
        runCatching { http.get(base() + "/") }      // a 404 still proves the internet works (Http.onResponse)
    }

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        fun update() {
            networkUp = runCatching {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }.getOrDefault(true)
        }
        update()
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: android.net.Network) = update()
            override fun onLost(n: android.net.Network) = update()
            override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) = update()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
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

    /** The binding last logged by [liveTick] ("" = not logged yet). */
    private var loggedBound: String? = ""

    private fun liveTick(now: Long) {
        syncSettings()
        selector.config = settings.selectorConfig()
        // Settings auto-saves while the pilot types (0.3.4); log every change of the binding so it can be verified.
        val bound = DroneSelector.normaliseSerial(selector.config.pinnedSerial)
        if (bound != loggedBound) { loggedBound = bound; SentryBus.log("Selection: bound to ${bound ?: "nothing (controller only)"}") }
        val sel = selector.step(now, fdaDrones + dsDrones, controllerFix())
        val own = sel.ownship
        if (own != null) lastOwnPos = own.pos
        val center = own?.pos ?: lastOwnPos
        val merged = TrafficMerger.merge(stationTargets, cloudTargets, airsense)
        val traffic = if (center != null) TrafficMerger.within(merged, center, settings.trafficRadiusNm) else emptyList()
        val zones = tfrZones + loadFileZones() + circleZone(own) + cylinderZones(sel)
        val res = engine.step(now, own, traffic, zones, trafficAgeSec(now))
        val hEvents = health.step(now)
        rates = PollRates.of(if (sel.mode == SelectionMode.PINNED) sel.drone?.isAirborne else null)
        val net = internet.step(now, networkUp, lastReachOkMs, lastReachFailMs)
        output(now, sel.events + res.events + hEvents + listOfNotNull(net), res.targets, "live", null)
        publish(now, own, sel, res, traffic.size, null)
    }

    /** Mutes, the output plan, then sounds / vibration / banners / log, and the per-second banner refresh. */
    private fun output(now: Long, events: List<AlertEvent>, views: List<AlertEngine.TargetView>, origin: String, sc: ReplayScenario?) {
        clockNowMs = now; lastViews = views
        val tickWall = System.currentTimeMillis()
        val (log, soundsOn) = mutes.step(now, views, events)
        log.forEach { pilotAction(it, logOnly = true) }
        deliver(OutputPlanner.plan(events + listOfNotNull(soundsOn), mutes, settings.alertStyle, now), origin, sc, tickWall)
        // The live countdown on banners that are on screen (in place; never re-posts a cancelled one).
        for (hex in notifier.activeHexes()) {
            val v = views.firstOrNull { it.hex == hex } ?: continue
            if (v.tier >= Tier.ADVISORY) notifier.refresh(hex, v.displayId, Banner.traffic(v), v.tier)
        }
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
        mutes.gotItSec = settings.gotItSec; mutes.quietSec = settings.quietMin * 60
        notifier.bannerMs = (settings.bannerSec * 1000).toLong().coerceIn(2_000, 30_000)
        notifier.ignoreEnabled = settings.ignoreEnabled; notifier.quietMin = settings.quietMin.toInt()
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
    private fun startReplay(speed: Double, cloudView: Boolean, crossing: Boolean) {
        acquireWakeLock()
        replayJob?.cancel()
        val sc = try {
            fun a(n: String) = assets.open("replay/$n").bufferedReader().use { it.readText() }
            DemoReplayFixture.load(a("demo_drone.json"), a("n388km_merged.json"), a("tfr_demo.json"), cloudView, crossing)
        } catch (e: Exception) { SentryBus.log("Replay load failed: $e"); return }
        mode = Mode.REPLAY
        val sp = speed.coerceIn(0.25, 16.0)
        replaySpeed = sp
        val eng = AlertEngine(settings.engineConfig(), externalSelection = true)
        val sel = DroneSelector(settings.selectorConfig())
        SentryBus.log("REPLAY start: ${sc.title} at ${sp}x")
        SentryBus.addCallout(AlertEvent(System.currentTimeMillis(), EventKind.SYSTEM, Severity.INFO, SystemText.REPLAY_STARTING + ": " + sc.title), hms.format(Date()), true)
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
                rates = PollRates.of(if (s.mode == SelectionMode.PINNED) s.drone?.isAirborne else null)
                output(t, s.events + res.events, res.targets, "replay", sc)
                publish(t, own, s, res, traffic.size, sc)
                t += 1000
                delay(maxOf(10L, (1000.0 / sp).toLong() - (System.currentTimeMillis() - t0)))
            }
            if (isActive) stopReplay("finished")
        }
    }

    private fun stopReplay(why: String) {
        replayJob?.cancel(); replayJob = null
        replaySpeed = 1.0
        notifier.cancelAll()
        SentryBus.log("REPLAY $why")
        if (settings.armed) { mode = Mode.OFF; startLive() } else {
            mode = Mode.OFF; publishOff()
            scope.launch { delay(8000); if (mode == Mode.OFF) stopSelfCleanly() }
        }
    }

    // ── output ──────────────────────────────────────────────────────────────
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun deliver(outputs: List<OutputPlanner.Output>, origin: String, sc: ReplayScenario?, tickWallMs: Long) {
        for (o in outputs) {
            val ev = o.event
            val clock = if (sc != null) replayClock(ev.timeMs, sc) else hms.format(Date(ev.timeMs))
            val snd = when {
                o.sounds -> "${o.level!!.key}/${o.cue.name.lowercase()}"
                o.suppressed != null -> "silent (${o.suppressed})"
                else -> "-"
            }
            val sScore = ev.closenessS?.let { String.format(Locale.US, " S=%.2f", it) } ?: ""
            SentryBus.addCallout(ev, clock, sc != null, if (o.sounds) snd else o.suppressed?.let { "silent: $it" } ?: "")
            SentryBus.log("ALERT[$origin] $clock ${(ev.tier?.label ?: ev.severity.label).uppercase()} ${ev.kind}/${ev.phase} " +
                "sound=$snd banner=${o.banner}: ${ev.banner?.oneLine ?: ev.text}$sScore")
            if (o.sounds) {
                val level = o.level!!
                val speed = if (sc != null) replaySpeed else 1.0
                sound.play(level, o.cue, settings.soundUri(level), settings.soundVolume(level)) { startWall, what ->
                    val lat = if (ev.phase == Phase.ESCALATION) AlertLatency.ms(ev, tickWallMs, startWall, speed) else null
                    SentryBus.log("SOUND $what" + (lat?.let { " · ${AlertLatency.label(it)} (${ev.tier?.label?.uppercase()} ${ev.hex ?: ""})" } ?: ""))
                }
                if (settings.vibrationOn) sound.vibrate(level)
            }
            if (OutputPlanner.isTrafficKind(ev.kind)) {
                val hex = ev.hex ?: continue
                val id = lastViews.firstOrNull { it.hex == hex }?.displayId ?: ev.text.substringBefore(' ')
                notifier.traffic(hex, id, ev.banner, ev.tier, o.banner)
            } else if (o.banner == OutputPlanner.BannerAction.POPUP) {
                val (title, text) = housekeepingText(ev)
                notifier.housekeeping(ev.kind.name, title, text)
            }
        }
    }

    private fun housekeepingText(ev: AlertEvent): Pair<String, String> = when (ev.kind) {
        EventKind.INTERNET_LOST -> "Internet offline" to "Cloud traffic, the drone feed and TFR updates need it. The station link (if any) keeps working."
        EventKind.INTERNET_REGAINED -> "Internet back" to "Cloud traffic and the drone feed resume."
        EventKind.SELECTION -> "Bound aircraft acquired" to ev.text
        EventKind.OWNSHIP_LOST -> "Bound aircraft lost" to "${ev.text}. Sentry falls back to the controller cylinders if it doesn't return."
        EventKind.OWNSHIP_REGAINED, EventKind.OWNSHIP_ACQUIRED -> "Bound aircraft back" to ev.text
        EventKind.SOUNDS_ON -> "Sentry sounds on" to "Quiet is over: traffic sounds are back."
        EventKind.PREFLIGHT -> ev.text to (SentryBus.preflight.value?.second?.filter { !it.ok }?.joinToString("\n") { "✗ ${it.name}: ${it.detail}" }
            ?.ifEmpty { "Everything is ready." } ?: "")
        else -> "Sentry" to ev.text
    }

    /** A pilot action (banner button) or a mute ending: log + "Last alerts", and the status line picks it up. */
    private fun pilotAction(line: String, logOnly: Boolean = false) {
        SentryBus.log("ACTION $line")
        val now = System.currentTimeMillis()
        SentryBus.addCallout(AlertEvent(now, EventKind.SYSTEM, Severity.INFO, line), hms.format(Date(now)), mode == Mode.REPLAY, if (logOnly) "" else "pilot")
    }

    private fun stopLaterIfIdle(ms: Long) {
        if (mode == Mode.OFF) scope.launch { delay(ms); if (mode == Mode.OFF && !SentryBus.preflightRunning.value) stopSelfCleanly() }
    }

    // ── pre-flight check ───────────────────────────────────────────────────
    /**
     * Internet, fleet token, drone feed, bound aircraft, controller GPS, traffic feed, TFR data, notifications +
     * heads-up, battery optimisation, sound (plays the warning sound), vibration. One-shot fetches, so it works
     * armed or not. Result: SentryBus.preflight (the screen's list) + a housekeeping alert.
     */
    private fun runPreflight() {
        if (SentryBus.preflightRunning.value) return
        SentryBus.preflightRunning.value = true
        SentryBus.log("Pre-flight: running")
        scope.launch {
            try {
                val now0 = System.currentTimeMillis()
                val token = settings.fleetToken
                val hdr = mapOf("X-Fleet-Token" to token)
                fun err(e: Throwable) = e.message ?: e.javaClass.simpleName
                val ds = if (token.isBlank()) null else runCatching { Parsers.parseDroneSense(http.get(base() + "/api/live/dronesense", hdr), now0) }
                val fda = if (token.isBlank()) null else runCatching { Parsers.parseOurDrones(http.get(base() + "/api/live/our-drones", hdr), now0).drones }
                val errs = listOfNotNull(ds?.exceptionOrNull(), fda?.exceptionOrNull()).map { err(it) }
                val rejected = errs.firstOrNull { Regex("HTTP (401|403)").containsMatchIn(it) }
                val anyOk = ds?.isSuccess == true || fda?.isSuccess == true
                val tokenAccepted: Boolean? = when { token.isBlank() -> null; anyOk -> true; rejected != null -> false; else -> false }
                val feed = FleetStatus.of(token.isBlank(),
                    FleetStatus.Fetch(fda?.getOrNull()?.size, fda?.exceptionOrNull()?.let { err(it) }),
                    FleetStatus.Fetch(ds?.getOrNull()?.size, ds?.exceptionOrNull()?.let { err(it) }))
                val drones = ds?.getOrNull().orEmpty() + fda?.getOrNull().orEmpty()
                val pinned = DroneSelector.normaliseSerial(settings.pinnedSerial)
                val bound = drones.filter { DroneSelector.normaliseSerial(it.serial) == pinned }.maxByOrNull { it.posTimeMs }
                val adsb = runCatching { Parsers.parseReadsb(http.get(base() + "/api/live/adsb"), System.currentTimeMillis(), "cloud") }
                val center = bound?.pos ?: controllerFix()?.pos ?: lastOwnPos
                val near = adsb.getOrNull()?.let { all -> center?.let { TrafficMerger.within(all, it, settings.trafficRadiusNm).size } }
                val stationOk = settings.stationEnabled && health.get("station")?.let { health.stateOf(it, System.currentTimeMillis()) == HealthMonitor.State.OK } == true
                val tfr = if (tfrZones.isNotEmpty()) Result.success(tfrZones.size) else runCatching { Parsers.parseTfrs(http.get(base() + "/api/tfrs")).also { tfrZones = it }.size }
                val fix = controllerFix()
                val fixAge = fix?.let { (System.currentTimeMillis() - it.timeMs) / 1000.0 }
                val nmc = androidx.core.app.NotificationManagerCompat.from(this@SentryService)
                val ch = getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(Notifier.CH_TRAFFIC)
                val (audible, soundDetail) = sound.audible()
                // Sound: play the WARNING sound now, so the pilot hears exactly what a warning sounds like.
                sound.play(SoundLevel.WARNING, Cue.FULL, settings.soundUri(SoundLevel.WARNING), settings.soundVolume(SoundLevel.WARNING))
                if (settings.vibrationOn) sound.vibrate(SoundLevel.PREFLIGHT)
                val facts = Preflight.Facts(
                    internet = internet.state == InternetMonitor.State.ONLINE || lastReachOkMs?.let { System.currentTimeMillis() - it < 20_000 } == true,
                    tokenAccepted = tokenAccepted, tokenDetail = rejected?.let { Regex("HTTP \\d+").find(it)?.value } ?: errs.firstOrNull() ?: "",
                    droneFeedOk = feed.ok, droneFeedDetail = feed.detail,
                    pinnedSerial = pinned, boundAirborne = bound?.isAirborne, boundCallsign = bound?.callsign,
                    controllerGps = fix != null && fixAge!! <= 60, controllerGpsDetail = when {
                        fix == null -> "no fix"
                        fixAge!! > 60 -> "last fix ${fixAge.toInt()} s old"
                        else -> "fix" + (fix.accuracyM?.let { " ±${it.toInt()} m" } ?: "")
                    },
                    trafficOk = adsb.isSuccess || stationOk, trafficDetail = when {
                        adsb.isSuccess -> "cloud ADS-B OK" + (near?.let { " · $it within ${settings.trafficRadiusNm.toInt()} nm" } ?: "") + if (stationOk) " · station OK" else ""
                        stationOk -> "station OK · cloud: ${err(adsb.exceptionOrNull()!!)}"
                        else -> "cloud: ${err(adsb.exceptionOrNull()!!)}"
                    },
                    tfrOk = tfr.isSuccess, tfrDetail = tfr.fold({ "$it TFRs" }, { "TFR feed: ${err(it)}" }),
                    notificationsEnabled = nmc.areNotificationsEnabled(),
                    headsUpAllowed = ch == null || ch.importance >= android.app.NotificationManager.IMPORTANCE_HIGH,
                    batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
                    soundAudible = audible, soundDetail = if (audible) "played the warning sound · $soundDetail" else "muted: $soundDetail",
                    vibrator = sound.hasVibrator,
                )
                val items = Preflight.items(facts)
                items.forEach { SentryBus.log("Pre-flight ${if (it.ok) "OK  " else "FAIL"} ${it.name}: ${it.detail}${if (!it.ok && it.fix.isNotEmpty()) " -> ${it.fix}" else ""}") }
                SentryBus.preflight.value = System.currentTimeMillis() to items
                delay(2_600)                                              // let the warning sound finish
                val summary = Preflight.summary(items)
                val t = System.currentTimeMillis()
                deliver(OutputPlanner.plan(listOf(AlertEvent(t, EventKind.PREFLIGHT, if (Preflight.passed(items)) Severity.INFO else Severity.CAUTION, summary)),
                    mutes, settings.alertStyle, t), "preflight", null, t)
            } catch (e: Exception) {
                SentryBus.log("Pre-flight failed: $e")
            } finally {
                SentryBus.preflightRunning.value = false
            }
        }
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
            sounds = soundsLabel(now),
            soundsOk = !mutes.quietActive(now),
            vibration = if (!sound.hasVibrator) "not available on this controller" else if (settings.vibrationOn) "on" else "off in Settings",
            internet = when (internet.state) {
                InternetMonitor.State.ONLINE -> "ONLINE"
                InternetMonitor.State.OFFLINE -> "OFFLINE"
                InternetMonitor.State.UNKNOWN -> "CHECKING"
            } + if (internet.state != InternetMonitor.State.UNKNOWN && internet.sinceMs > 0) " " + ago(wall - internet.sinceMs) else "",
            internetOk = internet.state != InternetMonitor.State.OFFLINE,
            pollRates = "${rates.label} · fleet ${rates.fleetMs / 1000} s · cloud ${rates.cloudMs / 1000} s · station ${rates.stationMs / 1000} s",
            muted = res.targets.mapNotNull { v -> mutes.label(v.hex, now)?.let { v.hex to it } }.toMap(),
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
        SentryBus.publish(UiState(mode = Mode.OFF, tickMs = System.currentTimeMillis(), sounds = "starts when armed",
            vibration = if (sound.hasVibrator) "available" else "not available on this controller"))
    }

    private fun soundsLabel(now: Long): String {
        val q = mutes.quietLeftSec(now)
        return if (q != null) "QUIET ${Banner.clock(q)} left" else "ON (${settings.alertStyle.label})"
    }

    private fun ago(ms: Long): String { val s = ms / 1000; return if (s < 120) "${s}s" else if (s < 7200) "${s / 60}m" else "${s / 3600}h" }

    /**
     * The status notification: "Bound to <serial> · 12 targets · sounds on", then any mute countdown
     * ("N388KM muted 47 s", "Quiet 4:12 left") and "Internet offline".
     */
    private fun statusLine(st: UiState? = null): String {
        st ?: return "Starting…"
        val who = when {
            st.mode == Mode.REPLAY -> "REPLAY ${st.replayClock ?: ""}"
            st.waitingForBound -> "Waiting for ${st.boundSerial}"
            st.selectionMode == SelectionMode.CONTROLLER && st.ownship == null -> "No drone · controller GPS unavailable"
            st.selectionMode == SelectionMode.CONTROLLER -> "No aircraft pinned · protecting this controller"
            st.ownship == null -> "No drone position"
            !st.ownshipFresh -> "Drone position LOST (${st.ownship.name})"
            else -> "Bound to ${st.boundSerial ?: st.ownship.serial ?: ""} · ${st.ownship.callsign ?: st.ownship.name}"
        }
        val parts = ArrayList<String>()
        if (!st.internetOk) parts += "Internet offline"
        parts += who
        parts += "${st.targets.size} targets"
        parts += if (st.soundsOk) "sounds on" else st.sounds.lowercase().replaceFirstChar { it.uppercase() }
        st.muted.entries.sortedBy { it.value }.forEach { (hex, lbl) ->
            parts += "${st.targets.firstOrNull { it.hex == hex }?.displayId ?: hex} $lbl"
        }
        return parts.joinToString(" · ")
    }

    private fun updateNotification(st: UiState) {
        val line = statusLine(st)
        if (line == lastNotifText) return
        lastNotifText = line
        val nm = getSystemService(android.app.NotificationManager::class.java)
        runCatching { nm.notify(Notifier.ID_STATUS, Notifier.status(this, "Flight Deck Sentry", line)) }
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
        runCatching { netCallback?.let { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        notifier.cancelAll()
        sound.shutdown()
        scope.cancel()
        publishOff()
        super.onDestroy()
    }
}
