package org.mihomet.app.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.SettingsRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the live [MihomoApiClient] instance and recreates it only when the
 * controller port or secret actually changes, so unrelated settings updates
 * (e.g. theme switches) never tear down active WebSocket streams.
 *
 * Resource lifecycle (external audit P2.3): every swapped-out client is
 * [closed][MihomoApiClient.close] — the Ktor/OkHttp engine owns sockets and
 * threads, so a port/secret change must not leak it. The close is scheduled
 * after a short grace period instead of running synchronously inside the
 * swap: ViewModels capture `current()` once and may still hold the previous
 * client for a streaming collection or an in-flight request; the grace delay
 * lets those finish before the engine goes away. [dispose] cancels the
 * internal scope and closes the current client for callers owning the
 * provider's whole lifetime ([android.app.Application.onTerminate]).
 */
class MihomoApiClientProvider(
    settingsRepository: SettingsRepository
) {

    private data class ClientKey(val port: Int, val secret: String)

    /** Grace period before a swapped-out client's engine is released. */
    private companion object {
        const val CLIENT_CLOSE_GRACE_MS = 30_000L
    }

    private val currentClient = AtomicReference(
        MihomoApiClient(
            host = CoreConstants.DEFAULT_CONTROLLER_HOST,
            port = CoreConstants.DEFAULT_CONTROLLER_PORT,
            secret = ""
        )
    )
    private val lastKey = AtomicReference(ClientKey(CoreConstants.DEFAULT_CONTROLLER_PORT, ""))
    private val keyLock = Any()

    /** Handle for the settings collector + scheduled closes; cancelled by [dispose]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var disposed = false

    init {
        scope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                onSettingsChanged(settings)
            }
        }
    }

    fun current(): MihomoApiClient = currentClient.get()

    private fun onSettingsChanged(settings: AppSettings) {
        val newKey = ClientKey(settings.controllerPort, settings.controllerSecret)
        synchronized(keyLock) {
            if (disposed) return
            if (newKey != lastKey.get()) {
                lastKey.set(newKey)
                val oldClient = currentClient.getAndSet(
                    MihomoApiClient(
                        host = CoreConstants.DEFAULT_CONTROLLER_HOST,
                        port = newKey.port,
                        secret = newKey.secret
                    )
                )
                // Release the previous engine after the grace period (see class
                // KDoc): callers that captured the old reference get a window to
                // finish; the close itself is idempotent and failure-tolerant.
                scope.launch {
                    delay(CLIENT_CLOSE_GRACE_MS)
                    runCatching { oldClient.close() }
                }
            }
        }
    }

    /**
     * Stops the settings collector and pending grace closes, then closes the
     * current client. The provider must not be used afterwards. Wired from
     * [android.app.Application.onTerminate] — the last-resort lifecycle hook
     * (not called on real devices in normal operation, but the correct place
     * to release process-singleton resources).
     */
    fun dispose() {
        synchronized(keyLock) {
            disposed = true
        }
        scope.cancel()
        runCatching { currentClient.get().close() }
    }
}
