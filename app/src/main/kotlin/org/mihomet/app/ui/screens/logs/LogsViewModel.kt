package org.mihomet.app.ui.screens.logs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mihomet.app.data.api.AppLogBuffer
import org.mihomet.app.data.api.LogEntry
import org.mihomet.app.data.api.MihomoApiClientProvider
import org.mihomet.app.data.preferences.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogsUiState(
    val logs: List<LogEntry> = emptyList(),
    val selectedLevel: String = "all",
    val totalCount: Long = 0,
    val levelCounts: Map<String, Long> = emptyMap()
)

/**
 * Builds the machine-readable log export text:
 * a header (app name/version, export timestamp, entry count) followed by one
 * `[LEVEL] payload` line per buffered entry. Pure and locale-stable (Locale.US),
 * so it can be unit-tested without Android dependencies.
 */
fun buildLogExportText(
    logs: List<LogEntry>,
    now: Date,
    appVersion: String
): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
    return buildString {
        append("Mihomet v").append(appVersion).append(" — log export\n")
        append(timestamp).append('\n')
        append("Entries: ").append(logs.size).append('\n')
        logs.forEach { entry ->
            append('[').append(entry.type.uppercase(Locale.US)).append("] ")
                .append(entry.payload).append('\n')
        }
    }
}

class LogsViewModel(
    private val apiClientProvider: MihomoApiClientProvider,
    private val appContext: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            AppLogBuffer.snapshot.collect { snapshot ->
                _uiState.update {
                    it.copy(logs = snapshot.entries, totalCount = snapshot.totalCount, levelCounts = snapshot.levelCounts)
                }
            }
        }
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            settingsRepository.settingsFlow.map { it.persistentLogging }.distinctUntilChanged().flatMapLatest { persistent ->
                if (persistent) emptyFlow()
                else apiClientProvider.clientFlow.flatMapLatest { it.streamLogs() }
            }.collect(AppLogBuffer::add)
        }
    }

    fun setLevel(level: String) {
        _uiState.update { it.copy(selectedLevel = level) }
    }

    fun clearLogs() {
        AppLogBuffer.clear()
    }

    /** Header (date + app version) plus the buffered log lines. */
    fun buildLogExport(): String = buildLogExportText(
        logs = _uiState.value.logs,
        now = Date(),
        appVersion = appVersion()
    )

    /**
     * Writes the export into `<externalCacheDir>/logs/export-<timestamp>.log`
     * and returns the file, or null when the log is empty / writing failed.
     */
    suspend fun exportLogs(context: Context): File? {
        if (_uiState.value.logs.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.externalCacheDir, "logs").apply { mkdirs() }
                val file = File(dir, "export-${System.currentTimeMillis()}.log")
                file.writeText(buildLogExport())
                file
            }.getOrNull()
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: ""
}
