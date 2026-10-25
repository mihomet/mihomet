package org.mihomet.app.ui.screens.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mihomet.app.data.api.ConnectionItem
import org.mihomet.app.data.api.MihomoApiClient

data class ConnectionsUiState(
    val downloadTotal: Long = 0L,
    val uploadTotal: Long = 0L,
    val connections: List<ConnectionItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false
)

class ConnectionsViewModel(
    private val apiClient: MihomoApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()

    init {
        startPollingConnections()
    }

    private fun startPollingConnections() {
        viewModelScope.launch {
            while (isActive) {
                apiClient.getConnections().onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            downloadTotal = response.downloadTotal,
                            uploadTotal = response.uploadTotal,
                            connections = response.connections
                        )
                    }
                }
                delay(1500)
            }
        }
    }

    fun closeConnection(id: String) {
        viewModelScope.launch {
            apiClient.closeConnection(id)
            _uiState.update { state ->
                state.copy(connections = state.connections.filter { it.id != id })
            }
        }
    }

    fun closeAllConnections() {
        viewModelScope.launch {
            apiClient.closeAllConnections()
            _uiState.update { it.copy(connections = emptyList()) }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }
}
