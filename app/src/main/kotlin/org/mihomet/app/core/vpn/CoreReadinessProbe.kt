package org.mihomet.app.core.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.mihomet.app.data.api.MihomoApiClientProvider

/**
 * Readiness gate for the spawned core process: the controller port may already
 * be bound while the core is still booting, and a spawned process alone proves
 * nothing — only a `/version` answer WITH `tun` enabled means the core (TUN fd
 * handed over, API up) is actually usable. Until then the UI must NOT show
 * "connected".
 *
 * Extracted verbatim from [MihometVpnService] — pure move, no behavior change.
 */
class CoreReadinessProbe(private val apiClientProvider: MihomoApiClientProvider) {

    companion object {
        /** Total budget for the core REST API readiness gate after the process spawns. */
        const val TIMEOUT_MS = 15_000L

        /** Per-probe cap so a hanging /version call cannot stretch the readiness budget. */
        const val PROBE_TIMEOUT_MS = 2_000L

        /** Interval between core REST API readiness probes. */
        const val INTERVAL_MS = 500L
    }

    /**
     * Polls the core REST API (`GET /version` + TUN flag) until it answers, the
     * caller reports an abort via [isAborted], the core process goes away, or
     * [TIMEOUT_MS] elapses. Called with the start mutex held, so the watchdog
     * restart path cannot race a readiness probe.
     */
    suspend fun await(proc: Process, isAborted: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!isAborted() && proc.isAlive) {
            val answered = runCatching {
                withTimeout(PROBE_TIMEOUT_MS) {
                    val api = apiClientProvider.current()
                    // /version alone proves the process — a live core with a dead TUN
                    // (audit §5/F04) must not report connected: verify the tunnel too
                    // VPN path: the core must have actually attached the TUN fd —
                    // a live API with a dead TUN must not report connected (audit F04)
                    val configs = api.getConfigs().getOrNull()
                    configs != null && configs.tunEnabled
                }
            }.getOrDefault(false)
            if (answered) return true
            if (System.currentTimeMillis() >= deadline) break
            delay(INTERVAL_MS)
        }
        return false
    }
}
