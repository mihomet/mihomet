package org.mihomet.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.GitHubRelease
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.preferences.AppSettings
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
    private val apiClient: MihomoApiClient
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
            apiClient.getVersion().onSuccess { version ->
                _uiState.update { it.copy(coreVersion = version.version) }
            }
        }
    }

    fun checkForCoreUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true, updateMessage = null) }
            coreManager.checkLatestRelease()
                .onSuccess { release ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            latestRelease = release,
                            updateMessage = "Found release ${release.tag_name}"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateMessage = "Check failed: ${error.message}"
                        )
                    }
                }
        }
    }

    fun installCoreUpdate() {
        val release = _uiState.value.latestRelease ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloadingUpdate = true, updateMessage = "Downloading and installing...") }
            coreManager.downloadAndUpdateCore(release)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            coreVersion = release.tag_name,
                            updateMessage = "Core successfully updated to ${release.tag_name}!"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDownloadingUpdate = false,
                            updateMessage = "Update error: ${error.message}"
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
}
