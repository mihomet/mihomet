package org.mihomet.app.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.SettingsRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the live [MihomoApiClient] instance and recreates it only when the
 * controller port or secret actually changes, so unrelated settings updates
 * (e.g. theme switches) never tear down active WebSocket streams.
 */
class MihomoApiClientProvider(
    settingsRepository: SettingsRepository
) {

    private data class ClientKey(val port: Int, val secret: String)

    private val currentClient = AtomicReference(
        MihomoApiClient(
            host = CoreConstants.DEFAULT_CONTROLLER_HOST,
            port = CoreConstants.DEFAULT_CONTROLLER_PORT,
            secret = ""
        )
    )
    private val lastKey = AtomicReference(ClientKey(CoreConstants.DEFAULT_CONTROLLER_PORT, ""))
    private val keyLock = Any()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settingsRepository.settingsFlow.collect { settings ->
                onSettingsChanged(settings)
            }
        }
    }

    fun current(): MihomoApiClient = currentClient.get()

    private fun onSettingsChanged(settings: AppSettings) {
        val newKey = ClientKey(settings.controllerPort, settings.controllerSecret)
        synchronized(keyLock) {
            if (newKey != lastKey.get()) {
                lastKey.set(newKey)
                currentClient.set(
                    MihomoApiClient(
                        host = CoreConstants.DEFAULT_CONTROLLER_HOST,
                        port = newKey.port,
                        secret = newKey.secret
                    )
                )
            }
        }
    }
}
