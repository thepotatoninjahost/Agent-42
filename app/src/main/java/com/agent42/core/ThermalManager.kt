package com.agent42.core

import android.content.Context
import android.os.PowerManager

enum class ThermalStatus { NORMAL, WARM, HOT, CRITICAL }

class ThermalManager(private val context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var currentStatus = ThermalStatus.NORMAL

    fun check(): ThermalStatus {
        val status = getCurrentThermalStatus()
        currentStatus = status
        return status
    }

    private fun getCurrentThermalStatus(): ThermalStatus {
        val status = powerManager.currentThermalStatus
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.NORMAL
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.WARM
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.HOT
            PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.CRITICAL
            else -> ThermalStatus.NORMAL
        }
    }

    fun getRecommendedMaxTokens(): Int = when (currentStatus) {
        ThermalStatus.NORMAL -> 4096; ThermalStatus.WARM -> 2048
        ThermalStatus.HOT -> 1024; ThermalStatus.CRITICAL -> 0
    }
    fun shouldPauseInference(): Boolean = currentStatus == ThermalStatus.CRITICAL
    fun getCoolingMessage(): String = when (currentStatus) {
        ThermalStatus.WARM -> "Device warming up — responses may be shorter"
        ThermalStatus.HOT -> "Device is hot — using lightweight reasoning"
        ThermalStatus.CRITICAL -> "Device too hot — pausing to cool down"
        ThermalStatus.NORMAL -> ""
    }
}
