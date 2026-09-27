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
import com.uasflightdeck.sentry.core.BannerLook
import com.uasflightdeck.sentry.core.BannerRefresh
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
 *    0.4.4: fully custom RemoteViews on our own opaque dark card with explicit colours ([BannerLook]); the heads-up
 *    is two rows + our action row, the expanded form the full four lines + actions.
 *  - "housekeeping" (HIGH): internet lost / regained, bound aircraft acquired / lost, pre-flight, sounds on.
 *    Tap = dismiss.
 *  - "status" (LOW): the foreground-service notification with Disarm and Open Sentry (the only way into the app).
 *  - "updates" (LOW): "Sentry x.y.z available".
 *    0.4.5: housekeeping, status and update use [infoCard]: the same opaque dark card, explicit colours (grey-blue
 *    info, amber lost, green regained); NO notification here uses the system template any more.
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
            val title = "Sentry $version available"
            val text = "Tap to open Sentry, then tap the update banner to install."
            val n = infoCard(NotificationCompat.Builder(ctx, CH_UPDATES), ctx, BannerLook.Tone.INFO, title, text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_UPDATE, n) }
        }

        /** A button on the expanded non-traffic card. */
        class CardAction(val label: String, val intent: PendingIntent)

        /**
         * 0.4.5: the non-traffic card (housekeeping pop-ups, status, update notice) on the same opaque dark card as the
         * traffic banners, every colour explicit: [banner_info] for the heads-up and the collapsed form (title + one
         * line), [banner_info_big] pulled down (full body + our own buttons). Title / text / actions are still set on
         * the builder for accessibility and the lock screen; no system template is drawn.
         */
        fun infoCard(nb: NotificationCompat.Builder, ctx: Context, tone: BannerLook.Tone, title: String, text: String,
                     actions: List<CardAction> = emptyList()): NotificationCompat.Builder {
            fun rv(layout: Int, body: String): android.widget.RemoteViews = android.widget.RemoteViews(ctx.packageName, layout).apply {
                setInt(R.id.bStripe, "setBackgroundColor", tone.stripe)
                setTextViewText(R.id.bTitle, title)
                setTextColor(R.id.bTitle, tone.argb)
                setTextViewText(R.id.bLine2, body)
                setTextColor(R.id.bLine2, BannerLook.INK)
                setViewVisibility(R.id.bLine2, if (body.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            }
            // collapsed / heads-up: the first line only (the pre-flight list is several lines)
            val oneLine = text.lineSequence().firstOrNull().orEmpty()
            val big = rv(R.layout.banner_info_big, text)
            if (actions.isNotEmpty()) {
                big.setViewVisibility(R.id.bActions, android.view.View.VISIBLE)
                val ids = intArrayOf(R.id.bAct1, R.id.bAct2)
                ids.forEachIndexed { i, id ->
                    val a = actions.getOrNull(i)
                    big.setViewVisibility(id, if (a == null) android.view.View.GONE else android.view.View.VISIBLE)
                    if (a != null) {
                        big.setTextViewText(id, a.label); big.setTextColor(id, BannerLook.INK)
                        big.setOnClickPendingIntent(id, a.intent)
                    }
                }
            }
            nb.setSmallIcon(R.drawable.ic_stat_sentry)
                .setColor(tone.stripe)
                .setContentTitle(title)
                .setContentText(oneLine)
                .setCustomContentView(rv(R.layout.banner_info, oneLine))
                .setCustomBigContentView(big)
                .setCustomHeadsUpContentView(rv(R.layout.banner_info, oneLine))
                .setShowWhen(false)
            actions.forEach { nb.addAction(0, it.label, it.intent) }
            return nb
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
            // No body tap: "Open Sentry" is the only way into the app from here.
            infoCard(NotificationCompat.Builder(ctx, CH_STATUS), ctx, BannerLook.Tone.INFO, title, text, listOf(
                CardAction("Disarm", serviceAction(ctx, SentryService.ACTION_DISARM, 9001)),
                CardAction("Open Sentry", openApp(ctx))))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Banners currently shown: key -> (cancel time, last text). [holdUntilMs]: the event that posted it keeps its
     * title until then (0.4.3, [BannerRefresh]); the per-second refresh never touches it.
     */
    private class Live(var untilMs: Long, var text: BannerText, var tier: Tier?, val cancel: Runnable, val holdUntilMs: Long)
    private val live = HashMap<String, Live>()

    @Volatile var bannerMs: Long = 5_000
    @Volatile var ignoreEnabled = true
    @Volatile var quietMin = 5

    fun trafficId(hex: String) = 1000 + (hex.hashCode() and 0x7FFF)
    private fun houseId(key: String) = 40_000 + (key.hashCode() and 0x3FFF)

    /** 0.4.4 level colours (explicit, >= 7:1 on the card): collision red, warning amber, caution yellow, track /
     *  advisory grey-blue, passing / clear grey. */
    private fun level(tier: Tier?, title: String) = BannerLook.level(tier, title)

    /** The action buttons' intents; null = no action row (COLLISION RISK, PASSING, CLEAR). */
    private class Actions(val gotIt: PendingIntent, val ignore: PendingIntent?, val quiet: PendingIntent, val quietLabel: String)

    /**
     * One of our three banner layouts, every colour explicit (0.4.2/0.4.3 used the platform notification text
     * appearance, which is black unless the system is in night mode: unreadable on the RC Plus's dark heads-up).
     */
    private fun views(layout: Int, b: BannerText, tier: Tier?, actions: Actions?): android.widget.RemoteViews {
        val rv = android.widget.RemoteViews(ctx.packageName, layout)
        val lv = level(tier, b.title)
        rv.setInt(R.id.bStripe, "setBackgroundColor", lv.stripe)
        rv.setTextViewText(R.id.bTitle, b.title)
        rv.setTextColor(R.id.bTitle, lv.argb)
        rv.setTextColor(R.id.bLine2, BannerLook.INK)
        if (layout == R.layout.banner_2line) {
            rv.setTextViewText(R.id.bLine2, b.line2)
            return rv
        }
        val lines = if (layout == R.layout.banner_headsup) BannerLook.headsUpRows(b).let { h -> Triple(h.where, h.what, h.hint) }
                    else Triple(b.line2, b.line3, b.line4)
        rv.setTextViewText(R.id.bLine2, lines.first)
        rv.setTextViewText(R.id.bLine3, lines.second)
        rv.setTextColor(R.id.bLine3, BannerLook.INK)
        rv.setViewVisibility(R.id.bLine3, if (lines.second.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        rv.setTextViewText(R.id.bLine4, lines.third ?: "")
        rv.setTextColor(R.id.bLine4, BannerLook.INK)
        rv.setViewVisibility(R.id.bLine4, if (lines.third.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        if (actions == null) {
            rv.setViewVisibility(R.id.bActions, android.view.View.GONE)
        } else {
            rv.setViewVisibility(R.id.bActions, android.view.View.VISIBLE)
            for (id in intArrayOf(R.id.bGotIt, R.id.bIgnore, R.id.bQuiet)) rv.setTextColor(id, BannerLook.INK)
            rv.setOnClickPendingIntent(R.id.bGotIt, actions.gotIt)
            if (actions.ignore != null) rv.setOnClickPendingIntent(R.id.bIgnore, actions.ignore)
            rv.setViewVisibility(R.id.bIgnore, if (actions.ignore != null) android.view.View.VISIBLE else android.view.View.GONE)
            rv.setTextViewText(R.id.bQuiet, actions.quietLabel)
            rv.setOnClickPendingIntent(R.id.bQuiet, actions.quiet)
        }
        return rv
    }

    private fun trafficNotification(hex: String, id: String, b: BannerText, tier: Tier?, alert: Boolean, silent: Boolean): Notification {
        val active = tier != null && tier >= Tier.ADVISORY && !b.title.startsWith("●") && !b.title.startsWith("○")
        val actions = if (active && tier != Tier.COLLISION) {
            val code = trafficId(hex) * 4
            Actions(
                gotIt = serviceAction(ctx, SentryService.ACTION_GOT_IT, code) { it.putExtra("hex", hex).putExtra("id", id) },
                ignore = if (ignoreEnabled) serviceAction(ctx, SentryService.ACTION_IGNORE, code + 1) { it.putExtra("hex", hex).putExtra("id", id) } else null,
                quiet = serviceAction(ctx, SentryService.ACTION_QUIET, code + 2),
                quietLabel = "Quiet $quietMin min")
        } else null
        val nb = NotificationCompat.Builder(ctx, CH_TRAFFIC)
            .setSmallIcon(R.drawable.ic_stat_sentry)
            .setColor(level(tier, b.title).stripe)
            .setContentTitle(b.title)
            .setContentText(b.line2)
            // 0.4.4: NO DecoratedCustomViewStyle. Our three layouts are the whole banner (own dark card, own colours,
            // own buttons), so the system's notification theme and action row never decide what the pilot can read.
            // Body tap = expand only (no content intent).
            .setCustomContentView(views(R.layout.banner_2line, b, tier, null))
            .setCustomBigContentView(views(R.layout.banner_4line, b, tier, actions))
            .setCustomHeadsUpContentView(views(R.layout.banner_headsup, b, tier, actions))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (tier != null && tier >= Tier.WARNING) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
            .setOnlyAlertOnce(!alert)
            .setSilent(silent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // The same actions as notification actions too: not drawn with fully custom views, but they stay available to
        // accessibility services and any surface that shows the standard template.
        actions?.let { a ->
            nb.addAction(0, "Got it", a.gotIt)
            a.ignore?.let { nb.addAction(0, "Ignore", it) }
            nb.addAction(0, a.quietLabel, a.quiet)
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
        val now = System.currentTimeMillis()
        if (BannerRefresh.decide(l.text, l.tier, l.holdUntilMs, b, tier, now) == BannerRefresh.Action.KEEP) return
        if (l.text.title != b.title) SentryBus.log("BANNER retitle $id: ${l.text.title} -> ${b.title} (event banner shown ${now - (l.holdUntilMs - bannerMs)} ms)")
        l.text = b; l.tier = tier
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
        val now = System.currentTimeMillis()
        live[key] = Live(now + bannerMs, b, tier, r, holdUntilMs = now + bannerMs)
        main.postDelayed(r, bannerMs)
    }

    /** Housekeeping heads-up: internet, bound aircraft, pre-flight, sounds on. Tap = dismiss. 0.4.5: our dark card, [tone]. */
    @Synchronized
    fun housekeeping(kind: String, title: String, text: String, tone: BannerLook.Tone = BannerLook.Tone.INFO) {
        val key = "house:$kind"
        val id = houseId(key)
        val n = infoCard(NotificationCompat.Builder(ctx, CH_HOUSE), ctx, tone, title, text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
