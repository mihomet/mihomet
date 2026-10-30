package org.mihomet.app.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mihomet.app.core.RunningService
import org.mihomet.app.core.ServiceState
import org.mihomet.app.core.network.NodePingTester
import org.mihomet.app.core.root.RootShellManager
import org.mihomet.app.core.root.RootTProxyService
import org.mihomet.app.core.vpn.MihometVpnService
import org.mihomet.app.data.api.MihomoApiClientProvider
import org.mihomet.app.data.api.TrafficSample
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.data.preferences.SettingsRepository
import org.yaml.snakeyaml.Yaml
import java.io.File

data class DashboardUiState(
    val isRunning: Boolean = false,
    val isRootMode: Boolean = true,
    val activeProfileName: String = "",
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
    private val apiClientProvider: MihomoApiClientProvider,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var trafficJob: Job? = null
    private val historyBuffer = mutableListOf<TrafficSample>()

    private companion object {
        /** Bounded wait for the opposite-mode teardown during a mode switch. */
        const val TEARDOWN_WAIT_TIMEOUT_MS = 10_000L
    }

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
                    it.copy(activeProfileName = profile?.name ?: "")
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

        // Periodic root daemon check (if UI process restarted).
        // Skipped while ServiceState.isRunning is true: the running status is
        // already reflected there, so shell polling is only needed while the
        // service is off and we wait to adopt a background mihomo instance.
        viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.isRootMode && !ServiceState.isRunning.value) {
                    val rootRunning = RootShellManager.isDaemonRunning()
                    if (rootRunning) {
                        ServiceState.setRunning(true)
                    }
                }
                delay(5000)
            }
        }
    }

    fun startProxy() {
        val isRoot = _uiState.value.isRootMode
        android.util.Log.d("MihometVpn", "DashboardViewModel.startProxy() called, isRoot=$isRoot")
        _uiState.update { it.copy(isConnecting = true, isDisconnecting = false) }
        viewModelScope.launch {
            if (isRoot) {
                if (ServiceState.runningService.value == RunningService.VPN) {
                    // Mode switch VPN -> ROOT: stop the VPN and wait until it has
                    // fully torn down (its ACTION_STOP handler clears ServiceState)
                    // before arming root firewall rules.
                    MihometVpnService.stop(context)
                    withTimeoutOrNull(TEARDOWN_WAIT_TIMEOUT_MS) {
                        ServiceState.runningService.first { it == RunningService.NONE }
                    }
                }
                RootTProxyService.start(context)
            } else {
                // Mode switch ROOT -> VPN: stop the root session if it is tracked as
                // running, or if an untracked daemon is still up (the 5s adoption
                // loop may not have fired yet), and WAIT for the asynchronous
                // teardown (firewall rules + daemon) before claiming the TUN.
                val rootSessionUp = ServiceState.runningService.value == RunningService.ROOT ||
                        withContext(Dispatchers.IO) { RootShellManager.isDaemonRunning() }
                if (rootSessionUp) {
                    RootTProxyService.stop(context)
                    withTimeoutOrNull(TEARDOWN_WAIT_TIMEOUT_MS) {
                        RootTProxyService.teardownCompleted?.await()
                    }
                }
                MihometVpnService.start(context)
            }
        }
    }

    fun stopProxy() {
        // Stop the service that is ACTUALLY running, not the one the current
        // settings would start: the mode toggle may have been flipped while the
        // other mode was still up, so stopping by setting can address the wrong
        // service (audit P1 §5a).
        val running = ServiceState.runningService.value
        android.util.Log.d("MihometVpn", "DashboardViewModel.stopProxy() called, runningService=$running")
        _uiState.update { it.copy(isDisconnecting = true, isConnecting = false) }
        viewModelScope.launch {
            when (running) {
                RunningService.ROOT -> RootTProxyService.stop(context)
                RunningService.VPN -> MihometVpnService.stop(context)
                // Nothing tracked as running: fall back to the configured mode
                // (covers an untracked daemon before the adoption loop fires).
                RunningService.NONE -> if (_uiState.value.isRootMode) {
                    RootTProxyService.stop(context)
                } else {
                    MihometVpnService.stop(context)
                }
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
            apiClientProvider.current().updateMode(mode.apiValue)
        }
    }

    private fun startTrafficMonitor() {
        trafficJob?.cancel()
        trafficJob = viewModelScope.launch {
            apiClientProvider.current().streamTraffic().collect { sample ->
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
            apiClientProvider.current().getProxies().onSuccess { response ->
                val primaryGroup = response.proxies.values.firstOrNull {
                    (it.type.equals("Selector", ignoreCase = true) || it.type.equals("select", ignoreCase = true)) &&
                    !it.name.equals("GLOBAL", ignoreCase = true)
                } ?: response.proxies.values.firstOrNull {
                    it.type.equals("Selector", ignoreCase = true) || it.type.equals("select", ignoreCase = true)
                }
                if (primaryGroup != null) {
                    val groupName = primaryGroup.name
                    // Read the persisted selection directly from DataStore: on the very
                    // first start the settingsFlow collector may not have emitted yet,
                    // so _uiState.value.selectedNode can still be blank and the default
                    // node would be applied instead of the user's choice.
                    val currentSelected = settingsRepository.settingsFlow.first().selectedNodeName

                    // Apply preselected node only if user selected one that belongs to this group
                    val targetNode = if (currentSelected.isNotBlank() && primaryGroup.all?.contains(currentSelected) == true) {
                        if (primaryGroup.now != currentSelected) {
                            apiClientProvider.current().selectProxyNode(groupName, currentSelected)
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
