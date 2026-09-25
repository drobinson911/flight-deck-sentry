package com.uasflightdeck.sentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.uasflightdeck.sentry.core.RestartPolicy

/**
 * 0.4.2: armed at power-off = armed after power-on ("Resume armed after power-off", default on). A foreground service
 * started from BOOT_COMPLETED is allowed on Android 10; the channels exist already (SentryApp.onCreate runs first)
 * and the service loads Settings (pinned serial, token) in onCreate, before any poller starts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val s = Settings(ctx)
        when (val d = RestartPolicy.onBoot(s.armed, s.resumeAfterPowerOff)) {
            is RestartPolicy.Decision.Rearm -> {
                SentryBus.log("${d.log} (${intent.action})")
                SentryService.send(ctx, SentryService.ACTION_ARM) { it.putExtra(SentryService.EXTRA_RESTART, SentryService.RESTART_BOOT) }
            }
            else -> SentryBus.log("Boot/update: armed=${s.armed}, resume after power-off=${s.resumeAfterPowerOff}: not arming")
        }
    }
}
