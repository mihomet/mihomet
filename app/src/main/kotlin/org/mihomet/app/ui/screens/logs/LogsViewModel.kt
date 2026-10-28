package org.mihomet.app.ui.screens.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.mihomet.app.data.api.LogEntry
import org.mihomet.app.data.api.MihomoApiClientProvider

data class LogsUiState(
    val logs: List<LogEntry> = emptyList(),
    val selectedLevel: String = "all"
)

class LogsViewModel(
    private val apiClientProvider: MihomoApiClientProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    private val logBuffer = mutableListOf<LogEntry>()

    init {
        viewModelScope.launch {
            apiClientProvider.current().streamLogs().collect { entry ->
                logBuffer.add(entry)
                if (logBuffer.size > 500) {
                    logBuffer.removeAt(0)
                }
                _uiState.update { it.copy(logs = logBuffer.toList()) }
            }
        }
    }

    fun setLevel(level: String) {
        _uiState.update { it.copy(selectedLevel = level) }
    }

    fun clearLogs() {
        logBuffer.clear()
        _uiState.update { it.copy(logs = emptyList()) }
    }
}
