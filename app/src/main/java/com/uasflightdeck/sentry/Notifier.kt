package com.uasflightdeck.sentry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.uasflightdeck.sentry.core.AlertEvent
import com.uasflightdeck.sentry.core.Severity

/**
 * Two channels: a quiet ongoing "status" one for the foreground service, and a
 * HIGH-importance "alerts" one whose heads-up banners overlay DroneSense. The
 * alerts channel is silent (Sentry plays its own tone + speech through the
 * ducking audio path); it vibrates.
 */
object Notifier {
    const val CH_STATUS = "status"
    const val CH_ALERTS = "alerts"
    const val ID_STATUS = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Sentry status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing: what Sentry is watching"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Traffic alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Heads-up traffic, TFR and link-health callouts"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250)
        })
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun status(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_sentry)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun alert(ctx: Context, ev: AlertEvent) {
        if (ev.severity < Severity.ADVISORY && ev.hex != null) return  // "clear" etc. don't need a banner
        val color = ContextCompat.getColor(ctx, when (ev.severity) {
            Severity.WARNING -> R.color.warning
            Severity.CAUTION -> R.color.caution
            Severity.ADVISORY -> R.color.advisory
            else -> R.color.ok
        })
        val title = when (ev.severity) {
            Severity.WARNING -> "WARNING — TRAFFIC"
            Severity.CAUTION -> if (ev.hex != null) "CAUTION — TRAFFIC" else "CAUTION"
            Severity.ADVISORY -> "TRAFFIC ADVISORY"
            else -> "Sentry"
        }
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_sentry)
            .setColor(color)
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(ev.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ev.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .setContentIntent(openApp(ctx))
            .build()
        // one banner per aircraft (updates replace), one per health kind
        val id = 1000 + ((ev.hex ?: ev.kind.name).hashCode() and 0xFFFF)
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
            .onFailure { SentryBus.log("Notify failed: ${it.message}") }
    }
}
