package com.uasflightdeck.sentry

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.uasflightdeck.sentry.core.ReleaseInfo
import com.uasflightdeck.sentry.core.Releases
import com.uasflightdeck.sentry.core.SemVer
import com.uasflightdeck.sentry.core.UpdatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Self-update from the PUBLIC GitHub releases of drobinson911/flight-deck-sentry.
 *
 *  - check(): GET releases/latest (unauthenticated, 60/h per IP). Automatic
 *    checks are rate-limited by [UpdatePolicy] (24 h after a success, 1 h after
 *    a failure) and fail quietly: offline or rate-limited only updates the
 *    status line. The result is persisted so the main-screen banner survives
 *    restarts.
 *  - downloadAndInstall(): ONLY from a tap. Streams the APK to the cache dir
 *    with progress, verifies length + that it is this package at a newer
 *    version, then commits a PackageInstaller session. Android shows its own
 *    confirm screen and enforces the signature match; a mismatch (the 0.2.0
 *    debug-signed build) is reported with the uninstall-once fix.
 *
 * Same approach as flightdeck-air's UpdateController (session install, verify
 * before installing), minus its token-gated worker endpoints.
 */
object Updater {
    data class Ui(
        val checking: Boolean = false,
        val downloading: Boolean = false,
        val progressPct: Int? = null,
        /** Last human-readable status ("Up to date", "Offline: …", "Downloading 40%", install errors). */
        val message: String = "",
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private const val ACTION_INSTALL_RESULT = "com.uasflightdeck.sentry.INSTALL_RESULT"

    /** SHA-256 of the release signing certificate (public; printed by `apksigner verify --print-certs`). */
    const val RELEASE_CERT_SHA256 = "07d612bf02fcdfcc3a617d091909bb83d3e44cfae7e58f34bd146b6a75cc51dc"

    val current: String get() = BuildConfig.VERSION_NAME

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    /** The newest release seen by the last successful check, or null. */
    fun latest(s: Settings): ReleaseInfo? {
        val v = SemVer.parse(s.updateLatestTag) ?: return null
        return ReleaseInfo(s.updateLatestTag, v, null, s.updateNotes, s.updateApkUrl.ifBlank { Releases.STABLE_APK_URL },
            s.updateApkSize.takeIf { it > 0 }, null, null)
    }

    fun available(s: Settings): Boolean = SemVer.isNewer(s.updateLatestTag, current)

    /** Automatic (rate-limited, quiet) or [manual] check. Safe to call from anywhere, any thread. */
    fun check(ctx: Context, manual: Boolean = false) {
        val app = ctx.applicationContext
        val s = Settings(app)
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.shouldCheck(now, s.updateLastSuccessMs, s.updateLastAttemptMs, manual)) return
        if (checkJob?.isActive == true) return
        s.updateLastAttemptMs = now
        _state.value = _state.value.copy(checking = true, message = if (manual) "Checking GitHub…" else _state.value.message)
        checkJob = scope.launch {
            val msg = try {
                val req = Request.Builder().url(Releases.LATEST_API)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "FlightDeckSentry/$current (+https://github.com/${Releases.REPO})")
                    .build()
                http.newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        r.code == 403 || r.code == 429 -> "GitHub rate limit reached; will try again later"
                        r.code == 404 -> "No release published yet"
                        !r.isSuccessful -> "GitHub answered HTTP ${r.code}; will try again later"
                        else -> {
                            val rel = Releases.parseLatest(body) ?: throw IOException("unreadable release JSON")
                            s.updateLatestTag = rel.tag; s.updateNotes = rel.notes
                            s.updateApkUrl = rel.apkUrl; s.updateApkSize = rel.apkSize ?: 0L
                            s.updateLastSuccessMs = System.currentTimeMillis()
                            if (UpdatePolicy.shouldNotify(rel.tag, current, s.updateNotifiedVersion)) {
                                Notifier.updateAvailable(app, rel.version.toString())
                                s.updateNotifiedVersion = rel.version.toString()
                            }
                            when {
                                SemVer.isNewer(rel.tag, current) -> "Sentry ${rel.version} is available"
                                SemVer.isNewer(current, rel.tag) -> "This build ($current) is newer than the latest release (${rel.version})"
                                else -> "Up to date"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                "Offline: couldn't reach GitHub (${e.javaClass.simpleName}); will try again later"
            }
            s.updateLastMessage = msg
            SentryBus.log("Update check${if (manual) " (manual)" else ""}: $msg")
            _state.value = _state.value.copy(checking = false, message = msg)
        }
    }

    /** Can this app launch the installer, or must the pilot first allow "install unknown apps" for Sentry? */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Download the latest APK and hand it to the system installer. Call ONLY from a user tap. */
    fun downloadAndInstall(ctx: Context) {
        val app = ctx.applicationContext
        val s = Settings(app)
        val rel = latest(s)
        if (rel == null || !SemVer.isNewer(rel.tag, current)) { _state.value = _state.value.copy(message = "No newer version to install"); return }
        if (downloadJob?.isActive == true) return
        _state.value = _state.value.copy(downloading = true, progressPct = 0, message = "Downloading Sentry ${rel.version}…")
        downloadJob = scope.launch {
            val err = try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val apk = File(dir, Releases.APK_NAME)
                var received = 0L; var expected = -1L
                val req = Request.Builder().url(rel.apkUrl).header("User-Agent", "FlightDeckSentry/$current").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("download failed: HTTP ${r.code}")
                    val body = r.body ?: throw IOException("empty download")
                    expected = body.contentLength().takeIf { it > 0 } ?: rel.apkSize ?: -1L
                    body.byteStream().use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024); var lastPct = -1
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); received += n
                                if (expected > 0) {
                                    val pct = (received * 100 / expected).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) { lastPct = pct; _state.value = _state.value.copy(progressPct = pct, message = "Downloading Sentry ${rel.version}… $pct%") }
                                }
                            }
                        }
                    }
                }
                // Verify BEFORE the installer: a truncated file makes the installer fail with no UI.
                if (expected > 0 && received != expected) throw IOException("download cut short (${received / 1024} of ${expected / 1024} KB); tap again to retry")
                @Suppress("DEPRECATION")
                val info = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                    ?: throw IOException("downloaded file is not a valid APK; tap again to retry")
                if (info.packageName != app.packageName) throw IOException("downloaded APK is ${info.packageName}, not Sentry")
                if (!SemVer.isNewer(info.versionName, current)) throw IOException("downloaded APK is ${info.versionName}, not newer than $current")
                SentryBus.log("Update: APK ${info.versionName} verified ($received bytes); opening installer")
                _state.value = _state.value.copy(progressPct = 100, message = "Opening the installer for Sentry ${info.versionName}…")
                installViaSession(app, apk)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            _state.value = _state.value.copy(downloading = false, progressPct = null,
                message = err ?: "Installer opened: tap Install (then Open to re-arm)")
            if (err != null) SentryBus.log("Update: $err")
        }
    }

    private fun installViaSession(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { c.startActivity(confirm) }.onFailure { SentryBus.log("Update: installer UI failed: ${it.message}") }
                        // keep listening for the final status (on success this process is replaced)
                    }
                    PackageInstaller.STATUS_SUCCESS -> { runCatching { c.unregisterReceiver(this) }; SentryBus.log("Update: installed") }
                    else -> {
                        runCatching { c.unregisterReceiver(this) }
                        val detail = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no detail"
                        val msg = when (status) {
                            PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
                            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                                "This installed Sentry is signed with a different key (0.2.0 and older, or a CI debug build). " +
                                    "Uninstall Sentry once, then install flight-deck-sentry.apk from GitHub; updates install in place after that."
                            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage for the update"
                            else -> "Install failed (status $status): $detail"
                        }
                        SentryBus.log("Update: install status=$status $detail")
                        _state.value = _state.value.copy(downloading = false, progressPct = null, message = msg)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_INSTALL_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
        installer.openSession(sessionId).use { session ->
            session.openWrite(Releases.APK_NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(app, sessionId, Intent(ACTION_INSTALL_RESULT).setPackage(app.packageName), flags)
            session.commit(pi.intentSender)
        }
    }

    /** SHA-256 (hex, lower-case) of the certificate this installed copy is signed with. */
    fun ownCertSha256(ctx: Context): String? = runCatching {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val sig = sigs?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun signedWithReleaseKey(ctx: Context) = ownCertSha256(ctx) == RELEASE_CERT_SHA256
}
