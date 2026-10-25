package org.mihomet.app.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mihomet.app.core.root.RootShellManager
import org.mihomet.app.core.root.RootTProxyService
import org.mihomet.app.core.vpn.MihometVpnService
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.api.TrafficSample
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.data.preferences.SettingsRepository

data class DashboardUiState(
    val isRunning: Boolean = false,
    val isRootMode: Boolean = true,
    val activeProfileName: String = "No Profile",
    val currentTraffic: TrafficSample = TrafficSample(),
    val trafficHistory: List<TrafficSample> = emptyList(),
    val activeMode: ProxyMode = ProxyMode.RULE,
    val primaryGroup: String = "PROXY",
    val selectedNode: String = "DIRECT",
    val selectedNodeDelay: Int = 0
)

class DashboardViewModel(
    private val settingsRepository: SettingsRepository,
    private val profileDao: ProfileDao,
    private val apiClient: MihomoApiClient,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var trafficJob: Job? = null
    private val historyBuffer = mutableListOf<TrafficSample>()

    init {
        // Observe settings
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update {
                    it.copy(
                        isRootMode = settings.isRootMode,
                        activeMode = settings.proxyMode
                    )
                }
            }
        }

        // Observe active profile
        viewModelScope.launch {
            profileDao.getSelectedProfileFlow().collect { profile ->
                _uiState.update {
                    it.copy(activeProfileName = profile?.name ?: "Default (Assets)")
                }
            }
        }

        // Periodic check for daemon status
        viewModelScope.launch {
            while (isActive) {
                val running = RootShellManager.isDaemonRunning()
                if (running != _uiState.value.isRunning) {
                    _uiState.update { it.copy(isRunning = running) }
                    if (running) {
                        startTrafficMonitor()
                        loadProxyStatus()
                    } else {
                        stopTrafficMonitor()
                    }
                }
                delay(2000)
            }
        }
    }

    fun toggleProxy() {
        val currentlyRunning = _uiState.value.isRunning
        val isRoot = _uiState.value.isRootMode

        if (currentlyRunning) {
            if (isRoot) {
                RootTProxyService.stop(context)
            } else {
                MihometVpnService.stop(context)
            }
            _uiState.update { it.copy(isRunning = false) }
            stopTrafficMonitor()
        } else {
            if (isRoot) {
                RootTProxyService.start(context)
            } else {
                MihometVpnService.start(context)
            }
            _uiState.update { it.copy(isRunning = true) }
            startTrafficMonitor()
        }
    }

    fun setMode(mode: ProxyMode) {
        viewModelScope.launch {
            settingsRepository.updateProxyMode(mode)
            apiClient.updateMode(mode.apiValue)
        }
    }

    private fun startTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = viewModelScope.launch {
            apiClient.streamTraffic().collect { sample ->
                historyBuffer.add(sample)
                if (historyBuffer.size > 30) {
                    historyBuffer.removeAt(0)
                }
                _uiState.update {
                    it.copy(
                        currentTraffic = sample,
                        trafficHistory = historyBuffer.toList()
                    )
                }
            }
        }
    }

    private fun stopTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = null
        historyBuffer.clear()
        _uiState.update {
            it.copy(
                currentTraffic = TrafficSample(),
                trafficHistory = emptyList()
            )
        }
    }

    fun loadProxyStatus() {
        viewModelScope.launch {
            apiClient.getProxies().onSuccess { response ->
                val proxyGroup = response.proxies["PROXY"] ?: response.proxies.values.firstOrNull { it.type == "Selector" }
                if (proxyGroup != null) {
                    val nowNode = proxyGroup.now ?: "DIRECT"
                    val delay = proxyGroup.history.lastOrNull()?.delay ?: 0
                    _uiState.update {
                        it.copy(
                            primaryGroup = proxyGroup.name,
                            selectedNode = nowNode,
                            selectedNodeDelay = delay
                        )
                    }
                }
            }
        }
    }
}
