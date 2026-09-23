package com.uasflightdeck.sentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opt-in: re-arm after reboot / app update, only if Sentry was armed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val s = Settings(ctx)
        if (s.autoStartOnBoot && s.armed) {
            SentryBus.log("Boot/update: re-arming (${intent.action})")
            SentryService.send(ctx, SentryService.ACTION_ARM)
        }
    }
}
