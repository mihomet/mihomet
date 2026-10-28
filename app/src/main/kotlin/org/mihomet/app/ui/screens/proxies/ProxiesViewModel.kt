package org.mihomet.app.ui.screens.proxies

import android.content.Context
import androidx.annotation.StringRes
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
import org.json.JSONObject
import org.mihomet.app.R
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.ServiceState
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.core.network.ConnectionTestResult
import org.mihomet.app.core.network.NodePingTester
import org.mihomet.app.core.network.PingProtocol
import org.mihomet.app.core.network.ProxyNodeInfo
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.api.MihomoApiClientProvider
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.SettingsRepository
import org.yaml.snakeyaml.Yaml
import java.io.File

enum class ServerSortOrder(@StringRes val titleRes: Int) {
    DEFAULT(R.string.sort_default),
    BY_PING(R.string.sort_by_ping),
    BY_NAME(R.string.sort_by_name)
}

data class ServerNodeItem(
    val name: String,
    val type: String,
    val server: String = "",
    val port: Int = 0,
    val delayMs: Long = 0, // 0 = not tested, -1 = timeout, >0 = latency ms
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
    val testingNodeNames: Set<String> = emptySet(),
    val isTestingCurrentConnection: Boolean = false,
    val currentConnectionResult: ConnectionTestResult? = null,
    val pingProtocol: PingProtocol = PingProtocol.HTTP_GET,
    val sortOrder: ServerSortOrder = ServerSortOrder.DEFAULT,
    val groups: List<ProxyGroupUiModel> = emptyList(),
    val selectedGroupName: String = "",
    val selectedNodeName: String = "",
    val allServers: List<ServerNodeItem> = emptyList(),
    val rawConfigYaml: String = "",
    val searchQuery: String = "",
    val error: String? = null,
    val statusMessageRes: Int? = null,
    val statusMessageArgs: List<String> = emptyList()
)

class ProxiesViewModel(
    private val apiClientProvider: MihomoApiClientProvider,
    private val coreManager: CoreManager,
    private val settingsRepository: SettingsRepository,
    private val profileDao: ProfileDao,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProxiesUiState())
    val uiState: StateFlow<ProxiesUiState> = _uiState.asStateFlow()

    private var parsedNodes: List<ProxyNodeInfo> = emptyList()
    private var currentTestUrl = "http://www.gstatic.com/generate_204"
    private var currentTestTimeoutMs = 5000

    init {
        // Collect selected node preference and test URL
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                if (settings.selectedNodeName.isNotBlank()) {
                    _uiState.update { it.copy(selectedNodeName = settings.selectedNodeName) }
                }
                currentTestUrl = settings.testUrl
                currentTestTimeoutMs = settings.testTimeoutMs
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

    fun setPingProtocol(protocol: PingProtocol) {
        _uiState.update { it.copy(pingProtocol = protocol) }
    }

    fun setSortOrder(order: ServerSortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
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
                val nodeMap = parsedNodes.associateBy { it.name }

                // 2. Parse YAML groups
                val yaml = Yaml()
                val parsed = runCatching { yaml.load<Map<String, Any>>(rawYaml) }.getOrNull()
                val rawGroups = parsed?.get("proxy-groups") as? List<*>

                val groupsList = mutableListOf<ProxyGroupUiModel>()
                val autoGroupSet = mutableSetOf<String>()

                rawGroups?.filterIsInstance<Map<String, Any>>()?.forEach { gMap ->
                    val gName = gMap["name"]?.toString() ?: return@forEach
                    val gType = gMap["type"]?.toString()?.lowercase() ?: "select"
                    val proxies = (gMap["proxies"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    val isAuto = gType == "url-test" || gType == "fallback" || gType == "load-balance"
                    if (isAuto) {
                        autoGroupSet.add(gName)
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

                // Fallback: If no groups in YAML, create default group with all parsed nodes
                if (groupsList.isEmpty()) {
                    val allNames = parsedNodes.map { it.name }
                    groupsList.add(
                        ProxyGroupUiModel(
                            name = "PROXY",
                            type = "select",
                            selectedNode = allNames.firstOrNull() ?: "",
                            nodeNames = allNames
                        )
                    )
                }

                // 3. Create items list containing only legitimate nodes and auto-select groups
                val serverItems = mutableListOf<ServerNodeItem>()

                // Auto-groups defined by subscription or synthesized
                autoGroupSet.forEach { autoName ->
                    serverItems.add(
                        ServerNodeItem(
                            name = autoName,
                            type = "URL-TEST",
                            isAutoSelect = true
                        )
                    )
                }

                // Regular proxy nodes
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

                // Initial selected group: selector group
                val defaultGroup = groupsList.firstOrNull { it.type == "select" || it.type == "selector" }
                    ?: groupsList.first()

                _uiState.update { state ->
                    val selGroup = if (groupsList.any { it.name == state.selectedGroupName }) {
                        state.selectedGroupName
                    } else {
                        defaultGroup.name
                    }
                    val currentGroupObj = groupsList.find { it.name == selGroup }
                    val selNode = state.selectedNodeName.ifBlank {
                        currentGroupObj?.selectedNode ?: currentGroupObj?.nodeNames?.firstOrNull() ?: ""
                    }
                    state.copy(
                        isLoading = false,
                        groups = groupsList,
                        selectedGroupName = selGroup,
                        selectedNodeName = selNode,
                        allServers = serverItems,
                        rawConfigYaml = rawYaml
                    )
                }

                // 4. Sync with live core if running
                if (ServiceState.isRunning.value) {
                    syncLiveControllerStatus()
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private suspend fun syncLiveControllerStatus() {
        apiClientProvider.current().getProxies().onSuccess { response ->
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
            val targetGroup = groupName.ifBlank {
                _uiState.value.selectedGroupName.ifBlank {
                    _uiState.value.groups.firstOrNull { it.type.equals("select", ignoreCase = true) }?.name ?: ""
                }
            }

            _uiState.update { state ->
                val updatedGroups = state.groups.map { g ->
                    if (g.name == targetGroup) {
                        g.copy(selectedNode = node.name)
                    } else g
                }
                state.copy(
                    selectedGroupName = targetGroup,
                    selectedNodeName = node.name,
                    groups = updatedGroups
                )
            }
            settingsRepository.updateSelectedNodeName(node.name)

            // Send the selection only to the group the user tapped in
            if (targetGroup.isNotBlank()) {
                apiClientProvider.current().selectProxyNode(targetGroup, node.name).onFailure { e ->
                    android.util.Log.e("ProxiesVM", "Failed to select node ${node.name} in group $targetGroup", e)
                }
            }

            // Immediately close all connections so active traffic switches to new proxy without delay
            apiClientProvider.current().closeAllConnections()
        }
    }

    fun testCurrentConnection() {
        if (_uiState.value.isTestingCurrentConnection) return
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingCurrentConnection = true) }
            val result = NodePingTester.testCurrentConnection(timeoutMs = 3500)
            _uiState.update {
                it.copy(
                    isTestingCurrentConnection = false,
                    currentConnectionResult = result
                )
            }
        }
    }

    fun testSingleServer(node: ServerNodeItem) {
        if (_uiState.value.testingNodeNames.contains(node.name)) return
        val protocol = _uiState.value.pingProtocol

        viewModelScope.launch {
            _uiState.update { it.copy(testingNodeNames = it.testingNodeNames + node.name) }
            val isCoreOnline = apiClientProvider.current().getVersion().isSuccess || ServiceState.isRunning.value
            val rawTestUrl = if (protocol == PingProtocol.HTTP_HEAD) {
                "http://cp.cloudflare.com/generate_204"
            } else {
                currentTestUrl.ifBlank { "http://www.gstatic.com/generate_204" }
            }
            val testUrl = if (rawTestUrl.startsWith("https://cp.cloudflare.com")) {
                rawTestUrl.replace("https://", "http://")
            } else rawTestUrl
            val timeout = currentTestTimeoutMs

            val delay = when (protocol) {
                PingProtocol.TCP -> {
                    NodePingTester.testTcpPing(node.server, node.port, 2500)
                }
                PingProtocol.ICMP -> {
                    NodePingTester.testIcmpPing(node.server, 2500)
                }
                PingProtocol.HTTP_GET, PingProtocol.HTTP_HEAD -> {
                    if (isCoreOnline) {
                        val res = apiClientProvider.current().testNodeDelay(node.name, testUrl, timeout).getOrDefault(-1)
                        if (res > 0) res.toLong() else -1L
                    } else {
                        val probeRes = coreManager.runTemporaryCoreProbe(_uiState.value.rawConfigYaml) { probeClient ->
                            val r = probeClient.testNodeDelay(node.name, testUrl, timeout).getOrDefault(-1)
                            if (r > 0) r.toLong() else -1L
                        }.getOrNull()
                        probeRes ?: NodePingTester.testTlsPing(node.server, node.port, 2500)
                    }
                }
            }

            _uiState.update { state ->
                val updated = state.allServers.map { item ->
                    if (item.name == node.name) item.copy(delayMs = delay) else item
                }
                state.copy(
                    testingNodeNames = state.testingNodeNames - node.name,
                    allServers = updated
                )
            }
        }
    }

    fun testAllServers() {
        if (_uiState.value.isTestingAll) return
        val currentProtocol = _uiState.value.pingProtocol

        viewModelScope.launch {
            _uiState.update { it.copy(isTestingAll = true, statusMessageRes = null, statusMessageArgs = emptyList()) }

            val isCoreOnline = apiClientProvider.current().getVersion().isSuccess || ServiceState.isRunning.value

            if (currentProtocol == PingProtocol.HTTP_GET || currentProtocol == PingProtocol.HTTP_HEAD) {
                if (isCoreOnline) {
                    runHttpApiTestAll(currentProtocol, apiClientProvider.current())
                } else {
                    // Core is not running: launch lightweight background core probe
                    val probeResult = coreManager.runTemporaryCoreProbe(_uiState.value.rawConfigYaml) { probeClient ->
                        runHttpApiTestAll(currentProtocol, probeClient)
                    }
                    if (probeResult.isFailure) {
                        runDirectNetworkTestAll(
                            protocol = currentProtocol
                        )
                    }
                }
            } else {
                runDirectNetworkTestAll(currentProtocol)
            }
        }
    }

    private suspend fun runHttpApiTestAll(
        protocol: PingProtocol,
        client: MihomoApiClient = apiClientProvider.current()
    ) = withContext(Dispatchers.IO) {
        val rawTestUrl = if (protocol == PingProtocol.HTTP_HEAD) {
            "http://cp.cloudflare.com/generate_204"
        } else {
            currentTestUrl.ifBlank { "http://www.gstatic.com/generate_204" }
        }
        val testUrl = if (rawTestUrl.startsWith("https://cp.cloudflare.com")) {
            rawTestUrl.replace("https://", "http://")
        } else rawTestUrl
        val timeout = currentTestTimeoutMs

        val groupResults = mutableMapOf<String, Long>()

        // 1. Group delay test via Mihomo core (GLOBAL and current selected group)
        client.testGroupDelay("GLOBAL", testUrl, timeout).onSuccess { map ->
            map.forEach { (name, delay) ->
                if (delay > 0) groupResults[name] = delay.toLong()
            }
        }

        val activeGroup = _uiState.value.selectedGroupName
        if (activeGroup.isNotBlank() && !activeGroup.equals("GLOBAL", ignoreCase = true)) {
            client.testGroupDelay(activeGroup, testUrl, timeout).onSuccess { map ->
                map.forEach { (name, delay) ->
                    if (delay > 0) groupResults[name] = delay.toLong()
                }
            }
        }

        // 2. Individual delay test for remaining proxies not returned in group delay
        val unmeasured = _uiState.value.allServers.filter { !it.isAutoSelect && !groupResults.containsKey(it.name) }
        if (unmeasured.isNotEmpty()) {
            val semaphore = Semaphore(6)
            val deferred = unmeasured.map { node ->
                async {
                    semaphore.withPermit {
                        val apiDelay = client.testNodeDelay(node.name, testUrl, timeout).getOrDefault(-1)
                        node.name to (if (apiDelay > 0) apiDelay.toLong() else -1L)
                    }
                }
            }
            deferred.awaitAll().forEach { (name, delay) ->
                groupResults[name] = delay
            }
        }

        val bestDelay = groupResults.values.filter { it > 0 }.minOrNull() ?: -1L

        _uiState.update { state ->
            val updated = state.allServers.map { item ->
                val delay = if (item.isAutoSelect) {
                    bestDelay
                } else {
                    groupResults[item.name] ?: -1L
                }
                item.copy(delayMs = delay)
            }
            val protocolName = if (protocol == PingProtocol.HTTP_HEAD) "HTTP HEAD" else "HTTP GET"
            state.copy(
                isTestingAll = false,
                allServers = updated,
                statusMessageRes = if (bestDelay > 0) R.string.proxies_status_test_done else R.string.proxies_status_unavailable,
                statusMessageArgs = if (bestDelay > 0) listOf(protocolName, bestDelay.toString()) else listOf(protocolName)
            )
        }
    }

    private suspend fun runDirectNetworkTestAll(
        protocol: PingProtocol
    ) = withContext(Dispatchers.IO) {
        val results = NodePingTester.testAllNodes(parsedNodes, protocol = protocol, timeoutMs = 2500)
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
            state.copy(
                isTestingAll = false,
                allServers = updated,
                statusMessageRes = if (bestDelay > 0) R.string.proxies_status_ping_done_min else R.string.proxies_status_ping_done,
                statusMessageArgs = if (bestDelay > 0) listOf(protocol.shortName, bestDelay.toString()) else listOf(protocol.shortName)
            )
        }
    }

    fun getConfigAsFormattedJson(): String {
        return try {
            val yaml = Yaml()
            val loaded = yaml.load<Any>(_uiState.value.rawConfigYaml)
            if (loaded is Map<*, *>) {
                JSONObject(loaded as Map<Any, Any>).toString(2)
            } else {
                _uiState.value.rawConfigYaml
            }
        } catch (e: Exception) {
            _uiState.value.rawConfigYaml
        }
    }
}
