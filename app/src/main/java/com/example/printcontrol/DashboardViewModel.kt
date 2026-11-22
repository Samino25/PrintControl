package com.example.printcontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DashboardViewModel : ViewModel() {

    private val repository = SmartPrintRepository()
    private val _uiState = MutableStateFlow(DashboardUiState(isLoading = true))

    // Samostatná úloha pre kameru – beží iba vtedy, keď je kamera rozbalená
    private var cameraJob: Job? = null

    val uiState: StateFlow<DashboardUiState> = _uiState
        .onStart { startSmartPolling() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DashboardUiState(isLoading = true)
        )

    private fun startSmartPolling() {
        viewModelScope.launch {
            while (isActive) {
                val freshData = repository.fetchLatestData()
                // Zachováme stav otvorenej kamery a jej poslednú snímku
                _uiState.value = freshData.copy(
                    isCameraVisible = _uiState.value.isCameraVisible,
                    isCameraLoading = _uiState.value.isCameraLoading,
                    cameraBitmap = _uiState.value.cameraBitmap
                )
                delay(4000)
            }
        }
    }

    /**
     * Zapne alebo vypne živý náhľad kamery.
     * Ak ho vypneš, prenos obrazu sa okamžite zastaví a šetrí batériu.
     */
    fun onToggleCameraPreview() {
        val willBeVisible = !_uiState.value.isCameraVisible
        _uiState.value = _uiState.value.copy(isCameraVisible = willBeVisible)

        if (willBeVisible) {
            startCameraStream()
        } else {
            stopCameraStream()
        }
    }

    private fun startCameraStream() {
        cameraJob?.cancel()
        cameraJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCameraLoading = true)
            while (isActive && _uiState.value.isCameraVisible) {
                val bitmap = repository.fetchCameraFrame()
                _uiState.value = _uiState.value.copy(
                    cameraBitmap = bitmap,
                    isCameraLoading = false
                )
                // Obnovujeme snímku každé 2 sekundy (ideálny pomer prehľadu a úspory energie)
                delay(2000)
            }
        }
    }

    private fun stopCameraStream() {
        cameraJob?.cancel()
        cameraJob = null
        // Uvoľníme pamäť obrázka, keď je kamera zatvorená
        _uiState.value = _uiState.value.copy(
            cameraBitmap = null,
            isCameraLoading = false
        )
    }

    fun onToggleTapo(deviceId: String) {
        viewModelScope.launch {
            val updated = repository.toggleTapoDevice(deviceId)
            _uiState.value = updated.copy(
                isCameraVisible = _uiState.value.isCameraVisible,
                cameraBitmap = _uiState.value.cameraBitmap
            )
        }
    }

    fun onTogglePausePrint() {
        viewModelScope.launch {
            val updated = repository.togglePausePrint()
            _uiState.value = updated.copy(
                isCameraVisible = _uiState.value.isCameraVisible,
                cameraBitmap = _uiState.value.cameraBitmap
            )
        }
    }

    fun onToggleChamberLight() {
        viewModelScope.launch {
            val updated = repository.toggleChamberLight()
            _uiState.value = updated.copy(
                isCameraVisible = _uiState.value.isCameraVisible,
                cameraBitmap = _uiState.value.cameraBitmap
            )
        }
    }

    fun onToggleAutoPowerOff() {
        viewModelScope.launch {
            val updated = repository.toggleAutoPowerOff()
            _uiState.value = updated.copy(
                isCameraVisible = _uiState.value.isCameraVisible,
                cameraBitmap = _uiState.value.cameraBitmap
            )
        }
    }

    fun dismissError() {
        repository.clearError()
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}