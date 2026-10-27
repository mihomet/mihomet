package org.mihomet.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.GitHubRelease
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
    val updateMessage: String? = null
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
            _uiState.update { it.copy(isCheckingUpdate = true, updateMessage = null) }
            coreManager.checkLatestRelease()
                .onSuccess { release ->
                    val cleanLatest = release.tag_name.trim().removePrefix("v")
                    val cleanCurrent = _uiState.value.coreVersion.trim().removePrefix("v")
                    val isNewer = cleanLatest.isNotBlank() && cleanLatest != cleanCurrent
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            latestRelease = release,
                            updateMessage = if (isNewer) {
                                "Доступна новая версия: ${release.tag_name}"
                            } else {
                                "У вас установлена актуальная версия ядра (${release.tag_name})"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateMessage = "Ошибка проверки: ${error.message}"
                        )
                    }
                }
        }
    }

    fun installCoreUpdate() {
        val release = _uiState.value.latestRelease ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloadingUpdate = true, updateMessage = "Загрузка и установка...") }
            coreManager.downloadAndUpdateCore(release)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            coreVersion = release.tag_name,
                            updateMessage = "Ядро успешно обновлено до ${release.tag_name}!"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            updateMessage = "Ошибка обновления: ${error.message}"
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
}
