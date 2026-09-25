package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.RestartPolicy.Decision
import com.uasflightdeck.sentry.core.RestartPolicy.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {
    @Test fun crashOrSystemKillWhileArmedRestartsAndRearmsWithAnAlert() {
        val d = RestartPolicy.onSystemRestart(armed = true)
        assertEquals(Decision.Rearm("Sentry restarted", "Restarted after unexpected exit: re-armed"), d)
        assertTrue(RestartPolicy.onSystemRestart(armed = false) is Decision.Disarm)
    }

    @Test fun swipeAwayDisarmsAndIsNeverRestarted() {
        assertEquals(Decision.Disarm("Disarmed by user (app closed)"), RestartPolicy.onTaskRemoved(armed = true, active = true))
        assertEquals(Decision.Disarm("Disarmed by user (app closed)"), RestartPolicy.onTaskRemoved(armed = false, active = true)) // replay
        assertEquals(Decision.Nothing, RestartPolicy.onTaskRemoved(armed = false, active = false))
        // the flag is now cleared: neither a system restart nor a power cycle brings it back
        assertTrue(RestartPolicy.onSystemRestart(armed = false) is Decision.Disarm)
        assertEquals(Decision.Nothing, RestartPolicy.onBoot(armed = false, resumeAfterPowerOff = true))
    }

    @Test fun powerCycleResumesArmedOnlyIfArmedAndTheSettingIsOn() {
        assertEquals(Decision.Rearm("Sentry armed after restart", RestartPolicy.ARMED_AFTER_BOOT_LOG), RestartPolicy.onBoot(true, true))
        assertEquals(Decision.Nothing, RestartPolicy.onBoot(armed = true, resumeAfterPowerOff = false))
        assertEquals(Decision.Nothing, RestartPolicy.onBoot(armed = false, resumeAfterPowerOff = true))
    }

    @Test fun disarmClearsTheFlagSoBootDoesNothing() {
        assertTrue(RestartPolicy.onDisarm() is Decision.Disarm)
        assertEquals(Decision.Nothing, RestartPolicy.onBoot(armed = false, resumeAfterPowerOff = true))
    }

    @Test fun openingTheAppAfterAnExit() {
        assertEquals(Decision.Nothing, RestartPolicy.onAppOpenedWithoutService(false, Exit.CRASH_OR_SYSTEM))
        assertTrue(RestartPolicy.onAppOpenedWithoutService(true, Exit.CRASH_OR_SYSTEM) is Decision.Rearm)
        val forced = RestartPolicy.onAppOpenedWithoutService(true, Exit.USER_CLOSED)
        assertTrue(forced is Decision.Disarm && forced.log!!.startsWith("Disarmed by user (app closed)"))
        assertTrue(RestartPolicy.onAppOpenedWithoutService(true, Exit.UNKNOWN) is Decision.Disarm)   // Android 10: can't tell
    }
}
