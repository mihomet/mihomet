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
    val icon: Drawable? = null
)

data class RoutingUiState(
    val settings: AppSettings = AppSettings(),
    val installedApps: List<AppItem> = emptyList(),
    val appSearchQuery: String = "",
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
                val pm = context.packageManager
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                packages
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName == "com.android.chrome" }
                    .map { appInfo ->
                        AppItem(
                            label = pm.getApplicationLabel(appInfo).toString(),
                            packageName = appInfo.packageName,
                            uid = appInfo.uid,
                            icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                        )
                    }
                    .sortedBy { it.label.lowercase() }
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
}
