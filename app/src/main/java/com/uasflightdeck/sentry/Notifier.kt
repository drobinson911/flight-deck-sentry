package com.uasflightdeck.sentry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.uasflightdeck.sentry.core.BannerText
import com.uasflightdeck.sentry.core.OutputPlanner.BannerAction
import com.uasflightdeck.sentry.core.Tier

/**
 * Notifications (v0.4.0). ALL channels are silent and never vibrate: Sentry plays its own sound and vibrates
 * itself ([SoundPlayer]), so nothing is ever played twice.
 *
 *  - "traffic" (HIGH): one heads-up banner per aircraft, updated in place, never stacked. It pops up only on an
 *    escalation or a zone entry; cadence repeats update it silently; the countdown is refreshed every second in
 *    place. Actions: Got it · Ignore · Quiet 5 min (none on COLLISION RISK). Body tap = expand only.
 *  - "housekeeping" (HIGH): internet lost / regained, bound aircraft acquired / lost, pre-flight, sounds on.
 *    Tap = dismiss.
 *  - "status" (LOW): the foreground-service notification with Disarm and Open Sentry (the only way into the app).
 *  - every banner cancels after the banner duration (traffic: restarted by each cadence refresh), and a traffic
 *    banner is cancelled at once when the aircraft clears.
 */
class Notifier(private val ctx: Context) {
    companion object {
        const val CH_STATUS = "status"
        const val CH_TRAFFIC = "traffic"
        const val CH_HOUSE = "housekeeping"
        const val CH_UPDATES = "updates"
        const val ID_STATUS = 1
        const val ID_UPDATE = 2

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // 0.3.x "alerts" channel vibrated on its own: gone, so a banner never buzzes on top of Sentry's own vibration.
            runCatching { nm.deleteNotificationChannel("alerts") }
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Sentry status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing: what Sentry is watching, Disarm, Open Sentry"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_TRAFFIC, "Traffic alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heads-up traffic banners (Sentry plays the sound itself)"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_HOUSE, "Sentry alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Internet lost / regained, bound aircraft, pre-flight result"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A newer Sentry is on GitHub (never installs by itself)"
                setShowBadge(false)
            })
        }

        /** Low priority, silent: "Sentry 0.4.1 available". Tapping opens Sentry, where the pilot taps to install. */
        fun updateAvailable(ctx: Context, version: String) {
            val n = NotificationCompat.Builder(ctx, CH_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_sentry)
                .setContentTitle("Sentry $version available")
                .setContentText("Tap to open Sentry, then tap the update banner to install.")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_UPDATE, n) }
        }

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun serviceAction(ctx: Context, action: String, req: Int, extras: (Intent) -> Unit = {}): PendingIntent {
            val i = Intent(ctx, SentryService::class.java).setAction(action).also(extras)
            val f = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(ctx, req, i, f) else PendingIntent.getService(ctx, req, i, f)
        }

        fun status(ctx: Context, title: String, text: String): Notification =
            NotificationCompat.Builder(ctx, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat_sentry)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // No body tap: "Open Sentry" is the only way into the app from here.
                .addAction(0, "Disarm", serviceAction(ctx, SentryService.ACTION_DISARM, 9001))
                .addAction(0, "Open Sentry", openApp(ctx))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** Banners currently shown: key -> (cancel time, last text). */
    private class Live(var untilMs: Long, var text: BannerText, var tier: Tier?, val cancel: Runnable)
    private val live = HashMap<String, Live>()

    @Volatile var bannerMs: Long = 5_000
    @Volatile var ignoreEnabled = true
    @Volatile var quietMin = 5

    fun trafficId(hex: String) = 1000 + (hex.hashCode() and 0x7FFF)
    private fun houseId(key: String) = 40_000 + (key.hashCode() and 0x3FFF)

    private fun colorFor(tier: Tier?, title: String) = ContextCompat.getColor(ctx, when {
        title.startsWith("●") || title.startsWith("○") -> R.color.dim
        tier == Tier.COLLISION || tier == Tier.WARNING -> R.color.warning
        tier == Tier.TRACK || tier == Tier.CAUTION -> R.color.caution
        tier == Tier.ADVISORY -> R.color.advisory
        else -> R.color.caution
    })

    private fun trafficNotification(hex: String, id: String, b: BannerText, tier: Tier?, alert: Boolean, silent: Boolean): Notification {
        val nb = NotificationCompat.Builder(ctx, CH_TRAFFIC)
            .setSmallIcon(R.drawable.ic_stat_sentry)
            .setColor(colorFor(tier, b.title))
            .setContentTitle(b.title)
            .setContentText(b.line2)
            .setStyle(NotificationCompat.BigTextStyle().bigText(b.body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (tier != null && tier >= Tier.WARNING) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
            .setOnlyAlertOnce(!alert)
            .setSilent(silent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val active = tier != null && tier >= Tier.ADVISORY && !b.title.startsWith("●") && !b.title.startsWith("○")
        if (active && tier != Tier.COLLISION) {
            val code = trafficId(hex) * 4
            nb.addAction(0, "Got it", serviceAction(ctx, SentryService.ACTION_GOT_IT, code) { it.putExtra("hex", hex).putExtra("id", id) })
            if (ignoreEnabled) nb.addAction(0, "Ignore", serviceAction(ctx, SentryService.ACTION_IGNORE, code + 1) { it.putExtra("hex", hex).putExtra("id", id) })
            nb.addAction(0, "Quiet $quietMin min", serviceAction(ctx, SentryService.ACTION_QUIET, code + 2))
        }
        return nb.build()
    }

    /** Apply one planned banner action for aircraft [hex]. */
    @Synchronized
    fun traffic(hex: String, id: String, b: BannerText?, tier: Tier?, action: BannerAction) {
        when (action) {
            BannerAction.NONE -> return
            BannerAction.CANCEL -> { cancelTraffic(hex); return }
            else -> Unit
        }
        b ?: return
        val l = live[hex]
        val popup = action == BannerAction.POPUP
        // Showing: update in place (a popup re-alerts). Gone: a silent re-post that doesn't pop up.
        val n = trafficNotification(hex, id, b, tier, alert = popup, silent = !popup && l == null)
        notify(trafficId(hex), n)
        restartTimer(hex, b, tier, l)
    }

    /** Every second: the live countdown, in place, only for banners on screen (never re-posts a cancelled one). */
    @Synchronized
    fun refresh(hex: String, id: String, b: BannerText, tier: Tier) {
        val l = live[hex] ?: return
        if (l.text == b && l.tier == tier) return
        if (l.text.title != b.title && tier < (l.tier ?: Tier.NONE)) return     // a step down waits for its own event
        l.text = b
        notify(trafficId(hex), trafficNotification(hex, id, b, tier, alert = false, silent = false))
    }

    @Synchronized
    fun activeHexes(): Set<String> = live.keys.filter { !it.startsWith("house:") }.toSet()

    @Synchronized
    fun cancelTraffic(hex: String) {
        live.remove(hex)?.let { main.removeCallbacks(it.cancel) }
        runCatching { nm.cancel(trafficId(hex)) }
    }

    @Synchronized
    private fun restartTimer(key: String, b: BannerText, tier: Tier?, prev: Live?) {
        prev?.let { main.removeCallbacks(it.cancel) }
        val r = Runnable { synchronized(this) { live.remove(key) }; runCatching { nm.cancel(if (key.startsWith("house:")) houseId(key) else trafficId(key)) } }
        live[key] = Live(System.currentTimeMillis() + bannerMs, b, tier, r)
        main.postDelayed(r, bannerMs)
    }

    /** Housekeeping heads-up: internet, bound aircraft, pre-flight, sounds on. Tap = dismiss. */
    @Synchronized
    fun housekeeping(kind: String, title: String, text: String) {
        val key = "house:$kind"
        val id = houseId(key)
        val n = NotificationCompat.Builder(ctx, CH_HOUSE)
            .setSmallIcon(R.drawable.ic_stat_sentry)
            .setColor(ContextCompat.getColor(ctx, R.color.advisory))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setContentIntent(serviceAction(ctx, SentryService.ACTION_DISMISS, id) { it.putExtra("nid", id) })
            .build()
        notify(id, n)
        restartTimer(key, BannerText(title, text, ""), null, live[key])
    }

    @Synchronized
    fun cancelAll() {
        live.values.forEach { main.removeCallbacks(it.cancel) }
        live.keys.toList().forEach { k -> runCatching { nm.cancel(if (k.startsWith("house:")) houseId(k) else trafficId(k)) } }
        live.clear()
    }

    private fun notify(id: Int, n: Notification) {
        runCatching { nm.notify(id, n) }.onFailure { SentryBus.log("Notify failed: ${it.message}") }
    }
}
