package com.uasflightdeck.sentry.core

/**
 * 0.4.2 owner rules: Sentry stays ARMED until the pilot DISARMs it or swipes it away from the app switcher.
 *  - DISARM, swipe-away (onTaskRemoved) or a force-stop: disarmed, stopped, never restarted by Sentry.
 *  - A crash or a system kill (the system re-delivers the sticky service with no intent): restart and re-arm, log
 *    "restarted after unexpected exit" and post one housekeeping alert "Sentry restarted".
 *  - Power-off / power-on while armed: BOOT_COMPLETED re-arms (setting "Resume armed after power-off", default on)
 *    with one housekeeping alert "Sentry armed after restart".
 * The persisted armed flag is the memory: cleared on DISARM and swipe-away, kept otherwise.
 */
object RestartPolicy {
    enum class Exit { USER_CLOSED, CRASH_OR_SYSTEM, UNKNOWN }

    sealed class Decision {
        /** Arm (again), with the housekeeping alert title and the log line. */
        data class Rearm(val alertTitle: String, val log: String) : Decision()
        /** Clear the armed flag and stop (with this log line, if any). */
        data class Disarm(val log: String?) : Decision()
        object Nothing : Decision()
    }

    const val RESTARTED = "Sentry restarted"
    const val RESTARTED_LOG = "Restarted after unexpected exit: re-armed"
    const val ARMED_AFTER_BOOT = "Sentry armed after restart"
    const val ARMED_AFTER_BOOT_LOG = "Power-on: re-armed (Sentry was armed at power-off)"
    const val APP_CLOSED = "Disarmed by user (app closed)"

    /** The pilot's DISARM: the flag is cleared. */
    fun onDisarm(): Decision = Decision.Disarm(null)

    /** Swiped away from Recents: disarm + stop, never restarted. */
    fun onTaskRemoved(armed: Boolean, active: Boolean): Decision =
        if (armed || active) Decision.Disarm(APP_CLOSED) else Decision.Nothing

    /** The system restarted the sticky service with no intent (crash / low-memory kill). */
    fun onSystemRestart(armed: Boolean): Decision =
        if (armed) Decision.Rearm(RESTARTED, RESTARTED_LOG) else Decision.Disarm("System restart while disarmed: staying off")

    /** BOOT_COMPLETED (or this app updated while it was armed). */
    fun onBoot(armed: Boolean, resumeAfterPowerOff: Boolean): Decision =
        if (armed && resumeAfterPowerOff) Decision.Rearm(ARMED_AFTER_BOOT, ARMED_AFTER_BOOT_LOG) else Decision.Nothing

    /**
     * The app was opened and finds the flag armed but no service running. A crash / system kill re-arms (the system's
     * own restart may simply not have happened yet); a user force-stop, or an exit Android can't explain (Android 10
     * has no exit-reason API), stays disarmed: "if they kill it, maybe it's because it's doing something wrong".
     */
    fun onAppOpenedWithoutService(armed: Boolean, lastExit: Exit): Decision = when {
        !armed -> Decision.Nothing
        lastExit == Exit.CRASH_OR_SYSTEM -> Decision.Rearm(RESTARTED, RESTARTED_LOG)
        lastExit == Exit.USER_CLOSED -> Decision.Disarm("$APP_CLOSED: force-stopped")
        else -> Decision.Disarm("$APP_CLOSED: found armed with no service running (force-stopped?); tap ARM to watch again")
    }
}
