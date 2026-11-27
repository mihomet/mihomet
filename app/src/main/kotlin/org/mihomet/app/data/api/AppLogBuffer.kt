package org.mihomet.app.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.Locale

/** Counts cover everything received since Clear; only recent entries occupy memory. */
data class LogBufferSnapshot(
    val entries: List<LogEntry> = emptyList(),
    val totalCount: Long = 0,
    val levelCounts: Map<String, Long> = emptyMap()
) {
    fun count(level: String): Long = if (level == "all") totalCount else levelCounts[level] ?: 0
}

object AppLogBuffer {
    internal const val CAPACITY = 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var continuousJob: Job? = null
    private val buffer = ArrayDeque<LogEntry>(CAPACITY)
    private val _snapshot = MutableStateFlow(LogBufferSnapshot())
    val snapshot = _snapshot.asStateFlow()

    @Synchronized
    fun add(entry: LogEntry) {
        if (buffer.size == CAPACITY) buffer.removeFirst()
        buffer.addLast(entry)
        val previous = _snapshot.value
        val level = entry.type.lowercase(Locale.ROOT)
        _snapshot.value = LogBufferSnapshot(
            entries = buffer.toList(),
            totalCount = previous.totalCount + 1,
            levelCounts = previous.levelCounts + (level to ((previous.levelCounts[level] ?: 0) + 1))
        )
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _snapshot.value = LogBufferSnapshot()
    }

    @Synchronized
    fun startContinuousLogging(clientProvider: MihomoApiClientProvider) {
        if (continuousJob?.isActive == true) return
        continuousJob = scope.launch {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            clientProvider.clientFlow.flatMapLatest { client -> client.streamLogs() }.collect(::add)
        }
    }

    @Synchronized
    fun stopContinuousLogging() {
        continuousJob?.cancel()
        continuousJob = null
    }
}
