package org.mihomet.app.ui.screens.routing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.FirewallEngineType
import org.mihomet.app.data.preferences.PerAppMode
import org.mihomet.app.data.preferences.SettingsRepository

data class AppItem(
    val label: String,
    val packageName: String,
    val uid: Int,
    val icon: Drawable? = null,
    val isSystem: Boolean = false
)

data class RoutingUiState(
    val settings: AppSettings = AppSettings(),
    val installedApps: List<AppItem> = emptyList(),
    val appSearchQuery: String = "",
    val showSystemApps: Boolean = false,
    val isLoadingApps: Boolean = false
)

class RoutingViewModel(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoutingUiState())
    val uiState: StateFlow<RoutingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingApps = true) }
            val apps = withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val rawApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    rawApps.map { appInfo ->
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        AppItem(
                            label = pm.getApplicationLabel(appInfo).toString(),
                            packageName = appInfo.packageName,
                            uid = appInfo.uid,
                            icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull(),
                            isSystem = isSystem
                        )
                    }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _uiState.update { it.copy(installedApps = apps, isLoadingApps = false) }
        }
    }

    fun updateRootMode(isRoot: Boolean) {
        viewModelScope.launch { settingsRepository.updateRootMode(isRoot) }
    }

    fun updateFirewallEngine(engine: FirewallEngineType) {
        viewModelScope.launch { settingsRepository.updateFirewallEngine(engine) }
    }

    fun updatePerAppMode(mode: PerAppMode) {
        viewModelScope.launch { settingsRepository.updatePerAppMode(mode) }
    }

    fun toggleAppUid(uid: Int) {
        viewModelScope.launch {
            val currentUids = _uiState.value.settings.selectedAppUids.toMutableSet()
            if (currentUids.contains(uid)) {
                currentUids.remove(uid)
            } else {
                currentUids.add(uid)
            }
            settingsRepository.updateSelectedAppUids(currentUids)
        }
    }

    fun updateIpv6Enabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateIpv6Enabled(enabled) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    fun toggleShowSystemApps() {
        _uiState.update { it.copy(showSystemApps = !it.showSystemApps) }
    }

    fun clearAllSelectedApps() {
        viewModelScope.launch {
            settingsRepository.updateSelectedAppUids(emptySet())
        }
    }

    fun selectAllApps() {
        viewModelScope.launch {
            val visibleApps = _uiState.value.installedApps.filter {
                _uiState.value.showSystemApps || !it.isSystem
            }
            val uids = visibleApps.map { it.uid }.toSet()
            settingsRepository.updateSelectedAppUids(uids)
        }
    }
}
