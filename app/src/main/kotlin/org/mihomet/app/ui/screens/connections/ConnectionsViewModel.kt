package org.mihomet.app.ui.screens.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.mihomet.app.data.api.ConnectionItem
import org.mihomet.app.data.api.MihomoApiClientProvider

data class ConnectionsUiState(
    val downloadTotal: Long = 0L,
    val uploadTotal: Long = 0L,
    val connections: List<ConnectionItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false
)

class ConnectionsViewModel(
    private val apiClientProvider: MihomoApiClientProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    /**
     * Starts the connections refresh loop. Called by ConnectionsScreen when it
     * enters composition (DisposableEffect) so the controller API is not polled
     * while the screen is not visible. Safe to call repeatedly.
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                // Resolve current client on every iteration so port/secret changes are picked up
                apiClientProvider.current().getConnections().onSuccess { response ->
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

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun closeConnection(id: String) {
        viewModelScope.launch {
            apiClientProvider.current().closeConnection(id)
            _uiState.update { state ->
                state.copy(connections = state.connections.filter { it.id != id })
            }
        }
    }

    fun closeAllConnections() {
        viewModelScope.launch {
            apiClientProvider.current().closeAllConnections()
            _uiState.update { it.copy(connections = emptyList()) }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }
}
