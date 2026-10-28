package org.mihomet.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.mihomet.app.R
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.GitHubRelease
import org.mihomet.app.core.HappCrypto
import org.mihomet.app.data.api.MihomoApiClientProvider
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.FirewallEngineType
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.data.preferences.SettingsRepository

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val coreVersion: String = "v1.19.33 (Bundled)",
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val latestRelease: GitHubRelease? = null,
    val updateMessageRes: Int? = null,
    val updateMessageArgs: List<String> = emptyList(),
    // Happ crypto keyring (key id → AES key), see core/HappCrypto.kt
    val cryptoKeys: Map<String, String> = emptyMap()
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val coreManager: CoreManager,
    private val apiClientProvider: MihomoApiClientProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }

        viewModelScope.launch {
            settingsRepository.cryptoKeysFlow.collect { keys ->
                _uiState.update { it.copy(cryptoKeys = keys) }
            }
        }

        viewModelScope.launch {
            apiClientProvider.current().getVersion().onSuccess { version ->
                _uiState.update { it.copy(coreVersion = version.version) }
            }
        }
    }

    fun updateRootMode(isRoot: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateRootMode(isRoot)
        }
    }

    fun updateProxyMode(mode: ProxyMode) {
        viewModelScope.launch {
            settingsRepository.updateProxyMode(mode)
            apiClientProvider.current().updateMode(mode.apiValue)
        }
    }

    fun updateShowSpeedInNotification(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateShowSpeedInNotification(show)
        }
    }

    fun updateFirewallEngine(engine: FirewallEngineType) {
        viewModelScope.launch {
            settingsRepository.updateFirewallEngine(engine)
        }
    }

    fun updateIpv6Enabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateIpv6Enabled(enabled)
        }
    }

    fun checkForCoreUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true, updateMessageRes = null, updateMessageArgs = emptyList()) }
            coreManager.checkLatestRelease()
                .onSuccess { release ->
                    // Compare semantic versions strictly: "isNewer" only when the release
                    // is actually GREATER than the installed core. Plain string inequality
                    // misfires because the current version can carry decorations like
                    // "1.19.33 (Bundled)" before the real version is fetched from the core.
                    val latest = parseSemanticVersion(release.tag_name)
                    val current = parseSemanticVersion(_uiState.value.coreVersion)
                    val isNewer = latest != null && current != null && isStrictlyNewer(latest, current)
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            latestRelease = release,
                            updateMessageRes = if (isNewer) R.string.settings_update_available else R.string.settings_update_up_to_date,
                            updateMessageArgs = listOf(release.tag_name)
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateMessageRes = R.string.settings_check_error,
                            updateMessageArgs = listOf(error.message ?: "")
                        )
                    }
                }
        }
    }

    fun installCoreUpdate() {
        val release = _uiState.value.latestRelease ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingUpdate = true,
                    updateMessageRes = R.string.settings_downloading,
                    updateMessageArgs = emptyList()
                )
            }
            coreManager.downloadAndUpdateCore(release)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            updateMessageRes = R.string.settings_update_success,
                            updateMessageArgs = listOf(release.tag_name)
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            updateMessageRes = R.string.settings_update_error,
                            updateMessageArgs = listOf(error.message ?: "")
                        )
                    }
                }
        }
    }

    fun updatePorts(mixed: Int, tproxy: Int, dns: Int, controller: Int) {
        viewModelScope.launch {
            settingsRepository.updatePorts(mixed, tproxy, dns, controller)
        }
    }

    fun updateTheme(dynamic: Boolean, dark: Boolean, amoled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateTheme(dynamic, dark, amoled)
        }
    }

    fun updateTestUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.updateTestUrl(url)
        }
    }

    fun updateTestTimeout(timeoutMs: Int) {
        viewModelScope.launch {
            settingsRepository.updateTestTimeout(timeoutMs)
        }
    }

    fun updateCustomDns(dns: String) {
        viewModelScope.launch {
            settingsRepository.updateCustomDns(dns)
        }
    }

    fun updateOverrideUnifiedDelay(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOverrideUnifiedDelay(enabled)
        }
    }

    fun updateOverrideTcpConcurrent(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOverrideTcpConcurrent(enabled)
        }
    }

    fun updateOverrideLogLevel(level: String) {
        viewModelScope.launch {
            settingsRepository.updateOverrideLogLevel(level)
        }
    }

    fun updateOverrideSnifferEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOverrideSnifferEnabled(enabled)
        }
    }

    /**
     * Adds (or replaces) a Happ crypto key after validating the AES-128 length:
     * the key must be exactly [HappCrypto.KEY_BYTES] bytes (UTF-8) and the id
     * must not be blank. Invalid input is silently ignored — the keyring is only
     * touched with a valid entry.
     */
    fun addCryptoKey(id: String, key: String) {
        val keyId = id.trim()
        val keyValue = key.trim()
        if (keyId.isEmpty()) return
        if (keyValue.toByteArray(Charsets.UTF_8).size != HappCrypto.KEY_BYTES) return
        viewModelScope.launch {
            settingsRepository.setCryptoKey(keyId, keyValue)
        }
    }

    /** Removes the Happ crypto key stored under [id] (no-op when absent). */
    fun removeCryptoKey(id: String) {
        viewModelScope.launch {
            settingsRepository.removeCryptoKey(id)
        }
    }

    /**
     * Extracts the first `major.minor.patch` triple from a version string,
     * tolerating decorations like "v1.19.33", "1.19.33 (Bundled)" or
     * "v1.19.33-alpha". Returns null when no semantic version is present.
     */
    private fun parseSemanticVersion(raw: String): Triple<Int, Int, Int>? {
        val match = Regex("\\d+\\.\\d+\\.\\d+").find(raw) ?: return null
        val parts = match.value.split(".").map { it.toInt() }
        return Triple(parts[0], parts[1], parts[2])
    }

    /** Lexicographic major/minor/patch check: true only when [candidate] is strictly greater. */
    private fun isStrictlyNewer(
        candidate: Triple<Int, Int, Int>,
        current: Triple<Int, Int, Int>
    ): Boolean = compareValuesBy(candidate, current, { it.first }, { it.second }, { it.third }) > 0
}
