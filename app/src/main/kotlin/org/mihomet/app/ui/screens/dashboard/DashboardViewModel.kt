package org.mihomet.app.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mihomet.app.core.ServiceState
import org.mihomet.app.core.network.NodePingTester
import org.mihomet.app.core.root.RootShellManager
import org.mihomet.app.core.root.RootTProxyService
import org.mihomet.app.core.vpn.MihometVpnService
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.api.TrafficSample
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.data.preferences.SettingsRepository
import org.yaml.snakeyaml.Yaml
import java.io.File

data class DashboardUiState(
    val isRunning: Boolean = false,
    val isRootMode: Boolean = true,
    val activeProfileName: String = "Нет профиля",
    val currentTraffic: TrafficSample = TrafficSample(),
    val trafficHistory: List<TrafficSample> = emptyList(),
    val activeMode: ProxyMode = ProxyMode.RULE,
    val primaryGroup: String = "",
    val selectedNode: String = "",
    val selectedNodeDelay: Long = 0,
    val isConnecting: Boolean = false,
    val isDisconnecting: Boolean = false
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
        // Observe settings (root mode, proxy mode, selected node)
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update {
                    it.copy(
                        isRootMode = settings.isRootMode,
                        activeMode = settings.proxyMode,
                        selectedNode = settings.selectedNodeName
                    )
                }
            }
        }

        // Observe active profile name
        viewModelScope.launch {
            profileDao.getSelectedProfileFlow().collect { profile ->
                _uiState.update {
                    it.copy(activeProfileName = profile?.name ?: "По умолчанию")
                }
            }
        }

        // Track running status from ServiceState
        viewModelScope.launch {
            ServiceState.isRunning.collect { running ->
                android.util.Log.d("MihometVpn", "DashboardViewModel received ServiceState.isRunning = $running")
                if (!running && _uiState.value.isDisconnecting) {
                    delay(350) // Graceful completion of spin-down animation
                }
                _uiState.update { it.copy(isRunning = running, isConnecting = false, isDisconnecting = false) }
                if (running) {
                    startTrafficMonitor()
                    syncActiveProxy()
                } else {
                    stopTrafficMonitor()
                }
            }
        }

        // Periodic root daemon check (if UI process restarted)
        viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.isRootMode && !_uiState.value.isRunning) {
                    val rootRunning = RootShellManager.isDaemonRunning()
                    if (rootRunning) {
                        ServiceState.setRunning(true)
                    }
                }
                delay(3000)
            }
        }
    }

    fun startProxy() {
        val isRoot = _uiState.value.isRootMode
        android.util.Log.d("MihometVpn", "DashboardViewModel.startProxy() called, isRoot=$isRoot")
        _uiState.update { it.copy(isConnecting = true, isDisconnecting = false) }
        if (isRoot) {
            if (org.mihomet.app.core.ServiceState.runningService.value == org.mihomet.app.core.RunningService.VPN) {
                MihometVpnService.stop(context)
            }
            RootTProxyService.start(context)
        } else {
            if (org.mihomet.app.core.ServiceState.runningService.value == org.mihomet.app.core.RunningService.ROOT) {
                RootTProxyService.stop(context)
            }
            MihometVpnService.start(context)
        }
    }

    fun stopProxy() {
        val isRoot = _uiState.value.isRootMode
        android.util.Log.d("MihometVpn", "DashboardViewModel.stopProxy() called, isRoot=$isRoot")
        _uiState.update { it.copy(isDisconnecting = true, isConnecting = false) }
        viewModelScope.launch {
            if (isRoot) {
                RootTProxyService.stop(context)
            } else {
                MihometVpnService.stop(context)
            }
        }
    }

    fun toggleProxy() {
        if (_uiState.value.isRunning) {
            stopProxy()
        } else {
            startProxy()
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

    private fun syncActiveProxy() {
        viewModelScope.launch {
            delay(1000) // wait for external controller to initialize
            apiClient.getProxies().onSuccess { response ->
                val primaryGroup = response.proxies.values.firstOrNull {
                    (it.type.equals("Selector", ignoreCase = true) || it.type.equals("select", ignoreCase = true)) &&
                    !it.name.equals("GLOBAL", ignoreCase = true)
                } ?: response.proxies.values.firstOrNull {
                    it.type.equals("Selector", ignoreCase = true) || it.type.equals("select", ignoreCase = true)
                }
                if (primaryGroup != null) {
                    val groupName = primaryGroup.name
                    val currentSelected = _uiState.value.selectedNode

                    // Apply preselected node only if user selected one that belongs to this group
                    val targetNode = if (currentSelected.isNotBlank() && primaryGroup.all?.contains(currentSelected) == true) {
                        if (primaryGroup.now != currentSelected) {
                            apiClient.selectProxyNode(groupName, currentSelected)
                        }
                        currentSelected
                    } else {
                        primaryGroup.now ?: primaryGroup.all?.firstOrNull() ?: ""
                    }

                    val targetProxy = response.proxies[targetNode]
                    val activeNodeName = if (targetProxy?.type.equals("URLTest", ignoreCase = true)) {
                        targetProxy?.now ?: targetNode
                    } else {
                        targetNode
                    }

                    val delay = response.proxies[activeNodeName]?.history?.lastOrNull()?.delay?.toLong()
                        ?: targetProxy?.history?.lastOrNull()?.delay?.toLong()
                        ?: primaryGroup.history.lastOrNull()?.delay?.toLong()
                        ?: 0L

                    _uiState.update {
                        it.copy(
                            primaryGroup = groupName,
                            selectedNode = targetNode,
                            selectedNodeDelay = delay
                        )
                    }
                }
            }
        }
    }
}
