package org.mihomet.app.ui.screens.proxies

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mihomet.app.core.network.NodePingTester
import org.mihomet.app.core.network.ProxyNodeInfo
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.data.preferences.SettingsRepository
import org.yaml.snakeyaml.Yaml
import java.io.File

data class ServerNodeItem(
    val name: String,
    val type: String,
    val server: String = "",
    val port: Int = 0,
    val delayMs: Long = 0, // 0 = not tested, -1 = timeout, >0 = latency ms
    val isTesting: Boolean = false,
    val isAutoSelect: Boolean = false
)

data class ProxiesUiState(
    val isLoading: Boolean = false,
    val isTestingAll: Boolean = false,
    val selectedNodeName: String = "⚡ Автовыбор",
    val primaryGroupName: String = "GLOBAL",
    val servers: List<ServerNodeItem> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null
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
                _uiState.update { it.copy(selectedNodeName = settings.selectedNodeName) }
            }
        }

        // Observe profile changes
        viewModelScope.launch {
            profileDao.getSelectedProfileFlow().collect {
                loadServers()
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
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

                // Detect primary selector group name from YAML if present
                val yaml = Yaml()
                val parsed = runCatching { yaml.load<Map<String, Any>>(rawYaml) }.getOrNull()
                val rawGroups = parsed?.get("proxy-groups") as? List<*>
                val detectedGroupName = rawGroups?.filterIsInstance<Map<String, Any>>()
                    ?.firstOrNull { it["type"] == "select" }
                    ?.get("name")?.toString() ?: "GLOBAL"

                val serverItems = mutableListOf<ServerNodeItem>()
                // Always prepend Auto-Select
                serverItems.add(
                    ServerNodeItem(
                        name = "⚡ Автовыбор",
                        type = "url-test",
                        isAutoSelect = true
                    )
                )

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

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        primaryGroupName = detectedGroupName,
                        servers = serverItems
                    )
                }

                // 2. If daemon is running, sync live controller status & delays
                apiClient.getProxies().onSuccess { response ->
                    val allProxies = response.proxies
                    val group = allProxies[detectedGroupName] ?: allProxies.values.firstOrNull { it.type == "Selector" }
                    val currentNow = group?.now

                    _uiState.update { state ->
                        val updatedServers = state.servers.map { item ->
                            val apiData = allProxies[item.name]
                            val apiDelay = apiData?.history?.lastOrNull()?.delay?.toLong() ?: item.delayMs
                            item.copy(delayMs = if (apiDelay > 0) apiDelay else item.delayMs)
                        }
                        state.copy(
                            servers = updatedServers,
                            selectedNodeName = currentNow ?: state.selectedNodeName,
                            primaryGroupName = group?.name ?: state.primaryGroupName
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectServer(node: ServerNodeItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedNodeName = node.name) }
            settingsRepository.updateSelectedNodeName(node.name)

            val groupName = _uiState.value.primaryGroupName
            apiClient.selectProxyNode(groupName, node.name)
        }
    }

    fun testAllServers() {
        if (_uiState.value.isTestingAll) return
        viewModelScope.launch {
            android.util.Log.i("Mihomet", "testAllServers called with ${parsedNodes.size} nodes")
            _uiState.update { it.copy(isTestingAll = true) }
            val results = NodePingTester.testAllNodes(parsedNodes, timeoutMs = 2500)
            android.util.Log.i("Mihomet", "testAllServers results: $results")

            _uiState.update { state ->
                val bestDelay = results.values.filter { it > 0 }.minOrNull() ?: 0L

                val updatedServers = state.servers.map { item ->
                    if (item.isAutoSelect) {
                        item.copy(delayMs = bestDelay)
                    } else {
                        val latency = results[item.name] ?: item.delayMs
                        item.copy(delayMs = if (latency != 0L) latency else item.delayMs)
                    }
                }
                state.copy(isTestingAll = false, servers = updatedServers)
            }
        }
    }

    fun testSingleServer(node: ServerNodeItem) {
        if (node.isAutoSelect || node.server.isBlank()) {
            testAllServers()
            return
        }
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(servers = state.servers.map {
                    if (it.name == node.name) it.copy(isTesting = true) else it
                })
            }

            val latency = NodePingTester.testTcpPing(node.server, node.port, timeoutMs = 2000)

            _uiState.update { state ->
                state.copy(servers = state.servers.map {
                    if (it.name == node.name) it.copy(delayMs = latency, isTesting = false) else it
                })
            }
        }
    }
}
