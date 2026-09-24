package com.uasflightdeck.sentry

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.appcompat.app.AlertDialog

/**
 * The pilot tapped "update". Two gates before [Updater.downloadAndInstall]:
 *  1. Android's per-app "Install unknown apps" permission: explain, open the
 *     system page for Sentry, and let the pilot tap update again on return.
 *  2. Sentry is armed: installing replaces the running app, so say what that
 *     means (a short gap in protection) and never do it without a second tap.
 */
fun startUpdate(a: Activity, s: Settings) {
    if (!Updater.available(s)) { Updater.check(a, manual = true); return }
    if (!Updater.canInstall(a)) {
        AlertDialog.Builder(a, R.style.Sentry_Dialog)
            .setTitle("Allow Sentry to install its update")
            .setMessage("Android asks once: on the next screen, turn on \"Allow from this source\" for Flight Deck Sentry, then come back and tap the update again.")
            .setPositiveButton("Open settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= 26) runCatching {
                    a.startActivity(Intent(SysSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                }.onFailure { runCatching { a.startActivity(Intent(SysSettings.ACTION_SECURITY_SETTINGS)) } }
            }
            .setNegativeButton("Cancel", null)
            .show()
        return
    }
    val armed = SentryBus.state.value.mode != Mode.OFF
    val v = Updater.latest(s)?.version
    if (armed) {
        AlertDialog.Builder(a, R.style.Sentry_Dialog)
            .setTitle("Update while armed?")
            .setMessage("Installing Sentry $v replaces the running app: traffic callouts stop until you tap Open after the install " +
                "(or until it re-arms itself if \"Re-arm automatically\" is on). Don't update in flight.")
            .setPositiveButton("Download and install") { _, _ -> Updater.downloadAndInstall(a) }
            .setNegativeButton("Later", null)
            .show()
    } else {
        Updater.downloadAndInstall(a)
    }
}
