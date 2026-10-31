package org.mihomet.app.core.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Core-process exit watchdog: watches the spawned mihomo process and reacts to
 * abnormal exits with automatic restarts (linear backoff, CMFA/FlClash pattern),
 * tears the foreground service down on clean exits, and resets the restart
 * budget only after the core has proven stable (so a crash-looping core cannot
 * renew its budget on every spawn).
 *
 * Ownership is re-checked under the start mutex before every state change, so a
 * stale generation (user stop or a newer start happened meanwhile) never resets
 * the state of a newer one (audit F08/N05).
 *
 * Extracted verbatim from [MihometVpnService] — pure move, no behavior change.
 * Service-owned state is reached through the [Hooks] interface.
 */
class CoreWatchdog(
    private val scope: CoroutineScope,
    private val startMutex: Mutex,
    private val hooks: Hooks
) {

    /** The service-owned state the watchdog needs to observe and mutate. */
    interface Hooks {
        /** The currently owned core process (identity comparison target), or null. */
        fun currentProcess(): Process?

        /** True while a user-initiated stop is in progress. */
        fun isStopping(): Boolean

        /** Releases TUN fd, wake lock, callbacks, process — the service cleanup. */
        fun cleanupVpnResources()

        /** Text for the "restarting after crash" notification. */
        fun restartNotificationText(): String

        /** Text for the final "restarts exhausted" notification. */
        fun giveUpNotificationText(exitCode: Int): String

        /** Re-runs the core start ([MihometVpnService.startVpnInternal]) with the mutex held. */
        suspend fun restartCore()

        /** stopForeground(REMOVE) + stopSelf(). */
        fun teardownForegroundService()

        fun updateNotification(text: String, ongoing: Boolean = true)
    }

    companion object {
        /** Max automatic core restarts after abnormal exit before giving up (CMFA/FlClash watchdog). */
        const val MAX_CORE_RESTARTS = 3

        /** Backoff base for core restarts: delay = CORE_RESTART_BACKOFF_MS * attempt. */
        const val CORE_RESTART_BACKOFF_MS = 2000L

        /** Uptime (with reachable API) the core must reach before the restart counter resets. */
        const val CORE_STABILITY_DELAY_MS = 30_000L
    }

    /**
     * Consecutive automatic core restart attempts. Reset to 0 only after the
     * core has stayed up with a reachable API for [CORE_STABILITY_DELAY_MS], so
     * a crash-looping core cannot renew its restart budget on every spawn.
     */
    @Volatile
    private var coreRestartAttempts = 0

    /** Starts watching [process]; call exactly once per spawned generation. */
    fun watch(process: Process, logFile: File) {
        scope.launch {
            try {
                val exitCode = process.waitFor()
                android.util.Log.d("MihometVpn", "Mihomo process exited with code $exitCode")
                // Ownership check: if the user stopped (or a newer start replaced the process),
                // this watchdog no longer owns the service state and must stay silent.
                // Every terminal branch below re-verifies ownership under the mutex
                // before touching state or resources (audit F08: a stale generation
                // must never reset the state of a newer one, and must never leave
                // the TUN fd / wake lock / foreground state behind).
                val watchdogOwnsProcess = hooks.currentProcess() === process && !hooks.isStopping()
                when {
                    !watchdogOwnsProcess -> {
                        // User stop or a newer start owns the state — nothing to do
                    }
                    exitCode == 0 -> {
                        // A clean core exit is terminal too: the tunnel is dead, so
                        // the foreground service must not keep showing "connected"
                        // with the TUN, wake lock and callbacks still held.
                        // cleanupVpnResources() also flips ServiceState and widgets.
                        android.util.Log.i("MihometVpn", "Core exited cleanly — tearing down")
                        startMutex.withLock {
                            // Re-check under the mutex: a user START may have
                            // acquired it meanwhile and spawned a new generation.
                            if (hooks.currentProcess() === process && !hooks.isStopping()) {
                                hooks.cleanupVpnResources()
                                hooks.teardownForegroundService()
                            }
                        }
                    }
                    coreRestartAttempts < MAX_CORE_RESTARTS -> {
                        // Automatic restart with linear backoff (CMFA/FlClash watchdog pattern)
                        coreRestartAttempts++
                        val attempt = coreRestartAttempts
                        val tailLogs = runCatching { logFile.readLines().takeLast(10).joinToString("\n") }.getOrNull()
                        android.util.Log.w("MihometVpn", "Core exited with $exitCode, restarting (attempt $attempt/$MAX_CORE_RESTARTS): $tailLogs")
                        hooks.updateNotification(hooks.restartNotificationText())
                        // The dead generation's resources (TUN fd, wake lock,
                        // callbacks) are deliberately NOT cleaned up here: the
                        // mutex is free during the backoff so a user STOP/START
                        // can win immediately, and cleanup without the mutex
                        // could kill a NEW generation's resources (audit N05).
                        delay(CORE_RESTART_BACKOFF_MS * attempt)
                        // User may have pressed Stop during the backoff delay, or started fresh.
                        // Serialize with user START/STOP so their action always wins.
                        startMutex.withLock {
                            // Ownership re-check under the mutex (audit N05):
                            // clean up and restart only while this watchdog's
                            // process is still the current generation — a user
                            // START that acquired the mutex meanwhile spawned a
                            // new generation whose resources must stay intact.
                            if (!hooks.isStopping() && hooks.currentProcess() === process) {
                                hooks.cleanupVpnResources()
                                hooks.restartCore()
                            }
                        }
                    }
                    else -> {
                        // Restart attempts exhausted — final failure: release the
                        // TUN fd, wake lock, callbacks and foreground state before
                        // reporting, instead of leaving a dead tunnel's resources held.
                        val tailLogs = runCatching { logFile.readLines().takeLast(10).joinToString("\n") }.getOrNull()
                        android.util.Log.e("MihometVpn", "Core exited with $exitCode, restarts exhausted: $tailLogs")
                        startMutex.withLock {
                            if (hooks.currentProcess() === process && !hooks.isStopping()) {
                                hooks.cleanupVpnResources()
                                hooks.teardownForegroundService()
                                // Posted after stopForeground(REMOVE) so the system does
                                // not tear the notification down together with the
                                // foreground state, and non-ongoing so the user can
                                // dismiss it (same pattern as the readiness-fail path).
                                hooks.updateNotification(hooks.giveUpNotificationText(exitCode), ongoing = false)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.d("MihometVpn", "waitFor() canceled: ${e.message}")
            }
        }
    }

    /**
     * Stability gate: the watchdog restart counter is wiped only after this
     * exact process has been up with a reachable API for a while. Resetting
     * at spawn (old behavior) let a crash-looping core renew its budget on
     * every cycle, so [MAX_CORE_RESTARTS] never exhausted.
     */
    fun scheduleStabilityReset(process: Process) {
        scope.launch {
            delay(CORE_STABILITY_DELAY_MS)
            if (!hooks.isStopping() && hooks.currentProcess() === process && process.isAlive) {
                android.util.Log.d(
                    "MihometVpn",
                    "Core stable for ${CORE_STABILITY_DELAY_MS / 1000}s — coreRestartAttempts reset"
                )
                coreRestartAttempts = 0
            }
        }
    }
}
