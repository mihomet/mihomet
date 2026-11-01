package org.mihomet.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunningService {
    NONE,
    VPN,
    ROOT
}

object ServiceState {
    private val _runningService = MutableStateFlow(RunningService.NONE)
    val runningService = _runningService.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _connectionStartTime = MutableStateFlow<Long?>(null)
    val connectionStartTime = _connectionStartTime.asStateFlow()

    fun setRunning(service: RunningService) {
        android.util.Log.d("MihometVpn", "ServiceState.setRunning(service=$service)")
        _runningService.value = service
        val running = (service != RunningService.NONE)
        _isRunning.value = running
        if (running && _connectionStartTime.value == null) {
            _connectionStartTime.value = System.currentTimeMillis()
        } else if (!running) {
            _connectionStartTime.value = null
        }
    }

    fun stopService(service: RunningService) {
        android.util.Log.d("MihometVpn", "ServiceState.stopService(service=$service, current=${_runningService.value})")
        if (_runningService.value == service || service == RunningService.NONE) {
            _runningService.value = RunningService.NONE
            _isRunning.value = false
            _connectionStartTime.value = null
        }
    }

    fun setRunning(running: Boolean) {
        android.util.Log.d("MihometVpn", "ServiceState.setRunning($running) called")
        _isRunning.value = running
        if (running && _connectionStartTime.value == null) {
            _connectionStartTime.value = System.currentTimeMillis()
        } else if (!running) {
            _runningService.value = RunningService.NONE
            _connectionStartTime.value = null
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return if (h > 0) {
            String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.ROOT, "%02d:%02d", m, s)
        }
    }
}
