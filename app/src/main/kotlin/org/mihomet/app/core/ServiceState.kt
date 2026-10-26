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

    fun setRunning(service: RunningService) {
        android.util.Log.d("MihometVpn", "ServiceState.setRunning(service=$service)")
        _runningService.value = service
        _isRunning.value = (service != RunningService.NONE)
    }

    fun stopService(service: RunningService) {
        android.util.Log.d("MihometVpn", "ServiceState.stopService(service=$service, current=${_runningService.value})")
        if (_runningService.value == service || service == RunningService.NONE) {
            _runningService.value = RunningService.NONE
            _isRunning.value = false
        }
    }

    fun setRunning(running: Boolean) {
        android.util.Log.d("MihometVpn", "ServiceState.setRunning($running) called")
        _isRunning.value = running
        if (!running) {
            _runningService.value = RunningService.NONE
        }
    }
}
