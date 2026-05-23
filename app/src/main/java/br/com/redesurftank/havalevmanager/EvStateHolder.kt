package br.com.redesurftank.havalevmanager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object EvStateHolder {

    var vehicleConnected       by mutableStateOf(false)
    var powerModelConfig       by mutableStateOf("--")
    var chargeSocTargetConfig  by mutableStateOf("--")
    var powerReserveConfig     by mutableStateOf("--")
    var batteryLevel           by mutableStateOf("--")

    /** Persisted via SharedPreferences; service reads prefs directly, UI reads this for display. */
    var autoEnabled            by mutableStateOf(false)

    val actionLog              = mutableStateListOf<String>()

    fun interface CommandCallback {
        fun onCommand(key: String, value: String)
    }

    @JvmField @Volatile var commandCallback: CommandCallback? = null

    fun sendCommand(key: String, value: String) {
        commandCallback?.onCommand(key, value)
    }

    fun updateEvData(
        connected: Boolean,
        powerModel: String?,
        socTarget: String?,
        powerReserve: String?,
        battery: String?
    ) {
        vehicleConnected      = connected
        powerModelConfig      = powerModel   ?: "--"
        chargeSocTargetConfig = socTarget    ?: "--"
        powerReserveConfig    = powerReserve ?: "--"
        batteryLevel          = battery      ?: "--"
    }

    fun addLog(entry: String) {
        actionLog.add(0, entry)
        if (actionLog.size > 50) actionLog.removeAt(actionLog.lastIndex)
    }
}
