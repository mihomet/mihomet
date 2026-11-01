package org.mihomet.app.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

object AppLogBuffer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var continuousJob: Job? = null
    val buffer = CopyOnWriteArrayList<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun add(entry: LogEntry) {
        buffer.add(entry)
        if (buffer.size > 1000) {
            buffer.removeAt(0)
        }
        _entries.value = buffer.toList()
    }

    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    @Synchronized
    fun startContinuousLogging(clientProvider: MihomoApiClientProvider) {
        if (continuousJob != null) return
        continuousJob = scope.launch {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            clientProvider.clientFlow.flatMapLatest { client ->
                client.streamLogs()
            }.collect { entry ->
                add(entry)
            }
        }
    }

    @Synchronized
    fun stopContinuousLogging() {
        continuousJob?.cancel()
        continuousJob = null
    }
}
