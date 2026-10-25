package org.mihomet.app.ui.screens.proxies

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.mihomet.app.core.ServiceState
import org.mihomet.app.core.network.NodePingTester
import org.mihomet.app.core.network.ProxyNodeInfo
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.SettingsRepository
import org.yaml.snakeyaml.Yaml
import java.io.File

enum class PingMode(val label: String, val shortLabel: String) {
    HTTP("HTTP (204)", "HTTP"),
    TCP("TCP (Сокет)", "TCP")
}

data class ServerNodeItem(
    val name: String,
    val type: String,
    val server: String = "",
    val port: Int = 0,
    val delayMs: Long = 0, // 0 = not tested, -1 = timeout, >0 = latency ms
    val isTesting: Boolean = false,
    val isAutoSelect: Boolean = false
)

data class ProxyGroupUiModel(
    val name: String,
    val type: String,
    val selectedNode: String = "",
    val nodeNames: List<String> = emptyList()
)

data class ProxiesUiState(
    val isLoading: Boolean = false,
    val isTestingAll: Boolean = false,
    val pingMode: PingMode = PingMode.HTTP,
    val groups: List<ProxyGroupUiModel> = emptyList(),
    val selectedGroupName: String = "",
    val selectedNodeName: String = "",
    val allServers: List<ServerNodeItem> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null,
    val statusMessage: String? = null
)

class ProxiesViewModel(
    private val apiClient: MihomoApiClient,
    private val settingsRepository: SettingsRepository,
    private val profileDao: ProfileDao,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProxiesUiState())
    val uiState: StateFlow<ProxiesUiState> = _uiState.asStateFlow()

    private var parsedNodes: List<ProxyNodeInfo> = emptyList()

    init {
        // Collect selected node preference
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                if (settings.selectedNodeName.isNotBlank()) {
                    _uiState.update { it.copy(selectedNodeName = settings.selectedNodeName) }
                }
            }
        }

        // Observe profile changes
        viewModelScope.launch {
            profileDao.getSelectedProfileFlow().collect {
                loadServers()
            }
        }

        // Observe core running state to sync live status when core starts
        viewModelScope.launch {
            ServiceState.isRunning.collect { running ->
                if (running) {
                    syncLiveControllerStatus()
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setPingMode(mode: PingMode) {
        _uiState.update { it.copy(pingMode = mode) }
    }

    fun selectGroup(groupName: String) {
        _uiState.update { state ->
            val group = state.groups.find { it.name == groupName }
            state.copy(
                selectedGroupName = groupName,
                selectedNodeName = group?.selectedNode?.ifBlank { state.selectedNodeName } ?: state.selectedNodeName
            )
        }
    }

    fun loadServers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val profile = profileDao.getSelectedProfile()
                val rawYaml = if (profile != null && File(profile.filePath).exists()) {
                    File(profile.filePath).readText()
                } else {
                    context.assets.open("default_config.yaml").bufferedReader().use { it.readText() }
                }

                // 1. Parse offline nodes
                parsedNodes = NodePingTester.parseNodesFromYaml(rawYaml)

                // 2. Parse YAML groups
                val yaml = Yaml()
                val parsed = runCatching { yaml.load<Map<String, Any>>(rawYaml) }.getOrNull()
                val rawGroups = parsed?.get("proxy-groups") as? List<*>

                val groupsList = mutableListOf<ProxyGroupUiModel>()
                val autoGroupNames = mutableSetOf<String>()

                rawGroups?.filterIsInstance<Map<String, Any>>()?.forEach { gMap ->
                    val gName = gMap["name"]?.toString() ?: return@forEach
                    val gType = gMap["type"]?.toString()?.lowercase() ?: "select"
                    val proxies = (gMap["proxies"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    val isAuto = gType == "url-test" || gType == "fallback" || gType == "load-balance"
                    if (isAuto) {
                        autoGroupNames.add(gName)
                    }
                    groupsList.add(
                        ProxyGroupUiModel(
                            name = gName,
                            type = gType,
                            selectedNode = proxies.firstOrNull() ?: "",
                            nodeNames = proxies
                        )
                    )
                }

                // Fallback: If no groups in YAML, create GLOBAL group with all nodes
                if (groupsList.isEmpty()) {
                    val allNames = parsedNodes.map { it.name }
                    groupsList.add(
                        ProxyGroupUiModel(
                            name = "GLOBAL",
                            type = "select",
                            selectedNode = allNames.firstOrNull() ?: "",
                            nodeNames = allNames
                        )
                    )
                }

                // Create list of all server items (including auto-select groups from subscription)
                val serverItems = mutableListOf<ServerNodeItem>()

                // Add auto groups as ServerNodeItem
                autoGroupNames.forEach { autoName ->
                    serverItems.add(
                        ServerNodeItem(
                            name = autoName,
                            type = "URL-TEST",
                            isAutoSelect = true
                        )
                    )
                }

                // Add proxy nodes
                parsedNodes.forEach { node ->
                    serverItems.add(
                        ServerNodeItem(
                            name = node.name,
                            type = node.type.uppercase(),
                            server = node.server,
                            port = node.port
                        )
                    )
                }

                // Add DIRECT or REJECT if referenced by any group
                val allReferenced = groupsList.flatMap { it.nodeNames }.toSet()
                if (allReferenced.contains("DIRECT") && serverItems.none { it.name == "DIRECT" }) {
                    serverItems.add(ServerNodeItem(name = "DIRECT", type = "DIRECT"))
                }
                if (allReferenced.contains("REJECT") && serverItems.none { it.name == "REJECT" }) {
                    serverItems.add(ServerNodeItem(name = "REJECT", type = "REJECT"))
                }

                // Initial selected group: first selector group or first group
                val defaultGroup = groupsList.firstOrNull { it.type == "select" || it.type == "selector" }
                    ?: groupsList.first()

                _uiState.update { state ->
                    val selGroup = if (groupsList.any { it.name == state.selectedGroupName }) {
                        state.selectedGroupName
                    } else {
                        defaultGroup.name
                    }
                    val selNode = state.selectedNodeName.ifBlank {
                        groupsList.find { it.name == selGroup }?.selectedNode ?: ""
                    }
                    state.copy(
                        isLoading = false,
                        groups = groupsList,
                        selectedGroupName = selGroup,
                        selectedNodeName = selNode,
                        allServers = serverItems
                    )
                }

                // 3. If core running, sync live controller status & delays
                syncLiveControllerStatus()

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private suspend fun syncLiveControllerStatus() {
        apiClient.getProxies().onSuccess { response ->
            val allProxies = response.proxies

            _uiState.update { state ->
                val updatedGroups = state.groups.map { group ->
                    val apiData = allProxies[group.name]
                    if (apiData != null && apiData.now != null) {
                        group.copy(selectedNode = apiData.now)
                    } else group
                }

                val currentGroup = updatedGroups.find { it.name == state.selectedGroupName }
                val currentNow = currentGroup?.selectedNode?.ifBlank { state.selectedNodeName }
                    ?: state.selectedNodeName

                val updatedServers = state.allServers.map { item ->
                    val apiData = allProxies[item.name]
                    val apiDelay = apiData?.history?.lastOrNull()?.delay?.toLong() ?: item.delayMs
                    item.copy(delayMs = if (apiDelay > 0) apiDelay else item.delayMs)
                }

                state.copy(
                    groups = updatedGroups,
                    selectedNodeName = currentNow,
                    allServers = updatedServers
                )
            }
        }
    }

    fun selectServer(groupName: String, node: ServerNodeItem) {
        viewModelScope.launch {
            _uiState.update { state ->
                val updatedGroups = state.groups.map { g ->
                    if (g.name == groupName) g.copy(selectedNode = node.name) else g
                }
                state.copy(
                    selectedNodeName = node.name,
                    groups = updatedGroups
                )
            }
            settingsRepository.updateSelectedNodeName(node.name)

            // Send selection to Mihomo core
            val actualGroupName = groupName.ifBlank { _uiState.value.selectedGroupName }
            apiClient.selectProxyNode(actualGroupName, node.name).onFailure { e ->
                android.util.Log.e("ProxiesVM", "Failed to select node ${node.name} in group $actualGroupName", e)
            }
        }
    }

    fun testAllServers() {
        if (_uiState.value.isTestingAll) return
        val currentPingMode = _uiState.value.pingMode
        val isCoreRunning = ServiceState.isRunning.value

        viewModelScope.launch {
            _uiState.update { it.copy(isTestingAll = true, statusMessage = null) }

            if (currentPingMode == PingMode.HTTP && !isCoreRunning) {
                _uiState.update {
                    it.copy(statusMessage = "Прокси выключен: выполнен прямой TCP-тест сокетов")
                }
                runTcpTestAll()
            } else if (currentPingMode == PingMode.HTTP && isCoreRunning) {
                runHttpTestAll()
            } else {
                runTcpTestAll()
            }
        }
    }

    private suspend fun runHttpTestAll() = withContext(Dispatchers.IO) {
        val serversToTest = _uiState.value.allServers
        val semaphore = Semaphore(6)
        val deferred = serversToTest.map { node ->
            async {
                semaphore.withPermit {
                    val delay = apiClient.testNodeDelay(node.name, timeoutMs = 3000).getOrDefault(-1)
                    node.name to delay.toLong()
                }
            }
        }
        val results = deferred.awaitAll().toMap()

        _uiState.update { state ->
            val updated = state.allServers.map { item ->
                val delay = results[item.name] ?: item.delayMs
                item.copy(delayMs = delay)
            }
            state.copy(isTestingAll = false, allServers = updated)
        }
    }

    private suspend fun runTcpTestAll() = withContext(Dispatchers.IO) {
        val results = NodePingTester.testAllNodes(parsedNodes, timeoutMs = 2500)
        val bestDelay = results.values.filter { it > 0 }.minOrNull() ?: -1L

        _uiState.update { state ->
            val updated = state.allServers.map { item ->
                if (item.isAutoSelect) {
                    item.copy(delayMs = bestDelay)
                } else {
                    val latency = results[item.name] ?: item.delayMs
                    item.copy(delayMs = if (latency != 0L) latency else item.delayMs)
                }
            }
            state.copy(isTestingAll = false, allServers = updated)
        }
    }

    fun testSingleServer(node: ServerNodeItem) {
        val currentPingMode = _uiState.value.pingMode
        val isCoreRunning = ServiceState.isRunning.value

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    statusMessage = if (currentPingMode == PingMode.HTTP && !isCoreRunning) {
                        "Прокси не запущен: для проверки HTTP включите прокси или переключитесь на TCP"
                    } else null,
                    allServers = state.allServers.map {
                        if (it.name == node.name) it.copy(isTesting = true) else it
                    }
                )
            }

            val latency: Long = if (currentPingMode == PingMode.HTTP && isCoreRunning) {
                apiClient.testNodeDelay(node.name, timeoutMs = 3500).getOrDefault(-1).toLong()
            } else {
                if (node.isAutoSelect || node.server.isBlank()) {
                    val results = NodePingTester.testAllNodes(parsedNodes, timeoutMs = 2000)
                    results.values.filter { it > 0 }.minOrNull() ?: -1L
                } else {
                    NodePingTester.testTcpPing(node.server, node.port, timeoutMs = 2000)
                }
            }

            _uiState.update { state ->
                state.copy(allServers = state.allServers.map {
                    if (it.name == node.name) it.copy(delayMs = latency, isTesting = false) else it
                })
            }
        }
    }
}
