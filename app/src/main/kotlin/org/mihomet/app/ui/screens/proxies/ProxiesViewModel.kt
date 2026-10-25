package org.mihomet.app.ui.screens.proxies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.api.ProxyGroupOrNode

data class NodeUiModel(
    val name: String,
    val type: String,
    val delay: Int = 0,
    val isTesting: Boolean = false
)

data class GroupUiModel(
    val name: String,
    val type: String,
    val selectedNode: String,
    val nodes: List<NodeUiModel> = emptyList(),
    val isTestingGroup: Boolean = false
)

data class ProxiesUiState(
    val isLoading: Boolean = false,
    val groups: List<GroupUiModel> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null
)

class ProxiesViewModel(
    private val apiClient: MihomoApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProxiesUiState())
    val uiState: StateFlow<ProxiesUiState> = _uiState.asStateFlow()

    init {
        loadProxies()
    }

    fun loadProxies() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            apiClient.getProxies().onSuccess { response ->
                val allProxies = response.proxies
                val groupList = mutableListOf<GroupUiModel>()

                // Filter proxy groups (Selector, URLTest, Fallback, etc.)
                allProxies.values.filter { it.all != null && it.all.isNotEmpty() }.forEach { group ->
                    val nodeModels = group.all?.map { nodeName ->
                        val nodeData = allProxies[nodeName]
                        NodeUiModel(
                            name = nodeName,
                            type = nodeData?.type ?: "Unknown",
                            delay = nodeData?.history?.lastOrNull()?.delay ?: 0
                        )
                    } ?: emptyList()

                    groupList.add(
                        GroupUiModel(
                            name = group.name,
                            type = group.type,
                            selectedNode = group.now ?: "",
                            nodes = nodeModels
                        )
                    )
                }

                _uiState.update { it.copy(isLoading = false, groups = groupList) }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "Failed to load proxies") }
            }
        }
    }

    fun selectNode(groupName: String, nodeName: String) {
        viewModelScope.launch {
            apiClient.selectProxyNode(groupName, nodeName).onSuccess {
                _uiState.update { state ->
                    state.copy(
                        groups = state.groups.map { group ->
                            if (group.name == groupName) {
                                group.copy(selectedNode = nodeName)
                            } else group
                        }
                    )
                }
            }
        }
    }

    fun testGroupDelay(groupName: String) {
        val group = _uiState.value.groups.find { it.name == groupName } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { state ->
                state.copy(
                    groups = state.groups.map {
                        if (it.name == groupName) it.copy(isTestingGroup = true) else it
                    }
                )
            }

            val deferredResults = group.nodes.map { node ->
                async {
                    val delay = apiClient.testNodeDelay(node.name).getOrDefault(0)
                    node.name to delay
                }
            }

            val results = deferredResults.awaitAll().toMap()

            _uiState.update { state ->
                state.copy(
                    groups = state.groups.map { g ->
                        if (g.name == groupName) {
                            g.copy(
                                isTestingGroup = false,
                                nodes = g.nodes.map { n ->
                                    val newDelay = results[n.name] ?: n.delay
                                    n.copy(delay = newDelay)
                                }
                            )
                        } else g
                    }
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }
}
