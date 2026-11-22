package com.example.printcontrol

import android.graphics.Bitmap

data class BambuPrinterState(
    val name: String = "Bambu Lab P1S",
    val isOnline: Boolean = false,
    val statusText: String = "Odpojené",
    val currentFile: String = "Žiadna úloha",
    val progressPercent: Int = 0,
    val remainingMinutes: Int = 0,
    val nozzleTemp: Int = 25,
    val nozzleTargetTemp: Int = 0,
    val bedTemp: Int = 25,
    val chamberLightOn: Boolean = false,
    val isPaused: Boolean = false
)

data class TapoDevice(
    val id: String,
    val name: String,
    val type: String,
    val isOn: Boolean,
    val currentPowerWatts: Double = 0.0,
    val todayEnergyWh: Int = 0
)

data class WatchdogState(
    val aiSpaghettiCheckOk: Boolean = true,
    val autoPowerOffEnabled: Boolean = true,
    val safeNozzleTempThreshold: Int = 50,
    val lastCheckTime: String = "Čakám na dáta"
)

data class DashboardUiState(
    val printer: BambuPrinterState = BambuPrinterState(),
    val tapoDevices: List<TapoDevice> = emptyList(),
    val watchdog: WatchdogState = WatchdogState(),
    // Nové položky pre voliteľný náhľad kamery:
    val isCameraVisible: Boolean = false,
    val isCameraLoading: Boolean = false,
    val cameraBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)