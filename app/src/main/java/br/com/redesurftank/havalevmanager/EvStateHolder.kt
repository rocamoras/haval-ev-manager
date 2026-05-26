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
    var remainOdometer         by mutableStateOf("--")
    var basicRemainOdo         by mutableStateOf("--")
    var engineState            by mutableStateOf("--")
    var lastEngineChange       by mutableStateOf("--")

    /** Persisted via SharedPreferences; service reads prefs directly, UI reads this for display. */
    var autoEnabled            by mutableStateOf(false)
    var autoHevEnabled         by mutableStateOf(false)

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
        battery: String?,
        remainOdo: String?,
        basicOdo: String?
    ) {
        vehicleConnected      = connected
        powerModelConfig      = powerModel   ?: "--"
        chargeSocTargetConfig = socTarget    ?: "--"
        powerReserveConfig    = powerReserve ?: "--"
        batteryLevel          = battery      ?: "--"
        remainOdometer        = remainOdo    ?: "--"
        basicRemainOdo        = basicOdo     ?: "--"
    }

    /** Called from Java service to update engine state + last change timestamp. */
    fun setEngineStateData(value: String?, lastChangeFmt: String) {
        engineState      = value        ?: "--"
        lastEngineChange = lastChangeFmt
    }

    fun addLog(entry: String) {
        actionLog.add(0, entry)
        if (actionLog.size > 50) actionLog.removeAt(actionLog.lastIndex)
    }
}
