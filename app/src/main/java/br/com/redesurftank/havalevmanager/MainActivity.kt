package br.com.redesurftank.havalevmanager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import br.com.redesurftank.havalevmanager.ui.theme.HavalEvManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MainActivity"
private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/rocamoras/haval-ev-manager/releases/latest"
private const val UI_PREFS                 = "ev_manager_ui_prefs"
private const val KEY_LAST_UPDATE_CHECK    = "last_update_check_ms"
private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

// ─────────────────────────────────────────────────────────────
// HMI color tokens — matches haval-climate-control palette
// ─────────────────────────────────────────────────────────────
private val HmiBg         = Color(0xFF000000)
private val HmiSurface    = Color(0xFF141414)
private val HmiSurface2   = Color(0xFF1C1C1C)
private val HmiFg         = Color(0xFFFAFAFA)
private val HmiFgMuted    = Color(0xFFA3A3A3)
private val HmiFgDim      = Color(0xFF6B6B6B)
private val HmiAccent     = Color(0xFF22C55E)
private val HmiAccentSoft = Color(0x1F22C55E)
private val HmiAccentEdge = Color(0x6622C55E)
private val HmiBorder     = Color(0x12FFFFFF)
private val HmiBorderStr  = Color(0x1FFFFFFF)

// Value colors: 0=gray, 1=blue, 2=green
private val ValueColor0   = Color(0xFF6B6B6B)
private val ValueColor1   = Color(0xFF60A5FA)
private val ValueColor2   = HmiAccent

// Battery-level colors
private val BatteryRed    = Color(0xFFEF4444)
private val BatteryAmber  = Color(0xFFF59E0B)

// Update button colors
private val UpdateBlue    = Color(0xFF60A5FA)
private val UpdateBlueBg  = Color(0x261565C0)

private const val PROP_POWER_MODEL   = "car.ev_setting.power_model_config"
private const val PROP_CHARGE_SOC    = "car.ev_setting.charge_soc_target_config"
private const val PROP_POWER_RESERVE = "car.ev_setting.power_reserve_config"

// ─────────────────────────────────────────────────────────────
// Update state machine
// ─────────────────────────────────────────────────────────────
sealed class UpdateState {
    object Idle                                                  : UpdateState()
    object Checking                                              : UpdateState()
    data class Available(val version: String, val url: String)  : UpdateState()
    data class Downloading(val progress: Float, val version: String) : UpdateState()
    object UpToDate                                              : UpdateState()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalEvManagerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = HmiBg) {
                    MainScreen()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Main screen — 1792×660 dp landscape HMI
// ─────────────────────────────────────────────────────────────

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val state   = EvStateHolder
    val prefs   = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }

    var currentVersion by remember { mutableStateOf("--") }
    var updateState    by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var showErrDialog  by remember { mutableStateOf(false) }
    var errDialogText  by remember { mutableStateOf("") }
    var showPermDialog by remember { mutableStateOf(false) }
    var downloadJob    by remember { mutableStateOf<Job?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    // On launch: resolve version + silent background update check (once per 24 h)
    LaunchedEffect(Unit) {
        try {
            currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "--"
        } catch (_: PackageManager.NameNotFoundException) {}

        val lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        if (System.currentTimeMillis() - lastCheck >= UPDATE_CHECK_INTERVAL_MS) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    conn.connectTimeout = 10_000
                    conn.readTimeout    = 10_000
                    if (conn.responseCode == 200) {
                        val json   = JSONObject(conn.inputStream.bufferedReader().readText())
                        val tag    = json.getString("tag_name")
                        val assets = json.getJSONArray("assets")
                        var dlUrl: String? = null
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.getString("name").endsWith(".apk")) {
                                dlUrl = a.getString("browser_download_url"); break
                            }
                        }
                        withContext(Dispatchers.Main) {
                            prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                            if (dlUrl != null && compareVersions(tag, currentVersion) > 0) {
                                updateState = UpdateState.Available(tag, dlUrl)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background update check failed: ${e.message}")
                }
            }
        }
    }

    // Auto-reset UpToDate → Idle after 2 s
    LaunchedEffect(updateState) {
        if (updateState is UpdateState.UpToDate) {
            delay(2_000)
            updateState = UpdateState.Idle
        }
    }

    fun installApk(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            showPermDialog = true; return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun checkForUpdate() {
        if (updateState !is UpdateState.Idle) return
        updateState = UpdateState.Checking
        scope.launch(Dispatchers.IO) {
            try {
                val conn = URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                val next: UpdateState
                if (conn.responseCode == 200) {
                    val json   = JSONObject(conn.inputStream.bufferedReader().readText())
                    val tag    = json.getString("tag_name")
                    val assets = json.getJSONArray("assets")
                    var dlUrl: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) {
                            dlUrl = a.getString("browser_download_url"); break
                        }
                    }
                    next = if (dlUrl != null && compareVersions(tag, currentVersion) > 0)
                        UpdateState.Available(tag, dlUrl)
                    else
                        UpdateState.UpToDate
                } else {
                    next = UpdateState.Idle
                }
                withContext(Dispatchers.Main) {
                    prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
                    updateState = next
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                withContext(Dispatchers.Main) { updateState = UpdateState.Idle }
            }
        }
    }

    fun startDownload(url: String, version: String) {
        updateState = UpdateState.Downloading(0f, version)
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val file  = File(context.getExternalFilesDir(null), "update.apk")
                val conn  = URL(url).openConnection() as HttpURLConnection
                val total = conn.contentLength
                val buf   = ByteArray(8192)
                var bytes = 0; var read: Int
                FileOutputStream(file).use { out ->
                    BufferedInputStream(conn.inputStream).use { inp ->
                        while (inp.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read); bytes += read
                            if (total > 0) withContext(Dispatchers.Main) {
                                updateState = UpdateState.Downloading(bytes.toFloat() / total, version)
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    updateState = UpdateState.Idle
                    installApk(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    updateState = UpdateState.Idle
                    errDialogText = "Erro no download: ${e.message}"
                    showErrDialog = true
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HmiBg)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        HmiHeader(
            currentVersion = currentVersion,
            connected      = state.vehicleConnected,
            updateState    = updateState,
            onCheckUpdate  = { checkForUpdate() },
            onDownload     = { avail -> startDownload(avail.url, avail.version) }
        )

        Spacer(Modifier.height(10.dp))

        // Auto toggles — side by side
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AutoToggleCard(
                modifier        = Modifier.weight(1f),
                autoEnabled     = state.autoEnabled,
                connected       = state.vehicleConnected,
                chargeSocTarget = state.chargeSocTargetConfig,
                batteryLevel    = state.batteryLevel
            )
            AutoHevCard(
                modifier    = Modifier.weight(1f),
                autoHev     = state.autoHevEnabled,
                connected   = state.vehicleConnected,
                engineState = state.engineState,
                lastChange  = state.lastEngineChange,
                basicOdo    = state.basicRemainOdo,
                battery     = state.batteryLevel
            )
        }

        Spacer(Modifier.height(10.dp))

        // Cards row — fills most of the remaining height
        Row(
            modifier              = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EvCycleCard(
                modifier  = Modifier.weight(1f).fillMaxHeight(),
                label     = "power_model_config",
                propKey   = PROP_POWER_MODEL,
                value     = state.powerModelConfig,
                icon      = Icons.Filled.ElectricCar,
                connected = state.vehicleConnected,
                locked    = state.autoEnabled
            )
            EvReadOnlyCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label    = "charge_soc_target_config",
                value    = state.chargeSocTargetConfig,
                icon     = Icons.Filled.BatteryChargingFull
            )
            EvCycleCard(
                modifier  = Modifier.weight(1f).fillMaxHeight(),
                label     = "power_reserve_config",
                propKey   = PROP_POWER_RESERVE,
                value     = state.powerReserveConfig,
                icon      = Icons.Filled.EnergySavingsLeaf,
                connected = state.vehicleConnected,
                locked    = state.autoEnabled
            )
            BatteryCard(
                modifier       = Modifier.weight(1f).fillMaxHeight(),
                batteryLevel   = state.batteryLevel,
                remainOdometer = state.remainOdometer
            )
            BasicOdoCard(
                modifier  = Modifier.weight(1f).fillMaxHeight(),
                basicOdo  = state.basicRemainOdo
            )
            EngineStateCard(
                modifier      = Modifier.weight(1f).fillMaxHeight(),
                engineState   = state.engineState,
                lastChange    = state.lastEngineChange
            )
        }

        Spacer(Modifier.height(12.dp))

        ActionLog(
            modifier = Modifier.fillMaxWidth().height(85.dp),
            entries  = state.actionLog.toList()
        )
    }

    // Permission dialog
    if (showPermDialog) {
        AlertDialog(
            onDismissRequest  = { showPermDialog = false },
            title             = { Text("Permissão necessária") },
            text              = { Text("Permita a instalação de apps de fontes desconhecidas para instalar a atualização.") },
            confirmButton     = {
                TextButton(onClick = {
                    showPermDialog = false
                    permLauncher.launch(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }) { Text("Abrir Configurações") }
            },
            dismissButton     = { TextButton(onClick = { showPermDialog = false }) { Text("Cancelar") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }

    // Error dialog
    if (showErrDialog) {
        AlertDialog(
            onDismissRequest  = { showErrDialog = false },
            title             = { Text("Erro") },
            text              = { Text(errDialogText) },
            confirmButton     = { TextButton(onClick = { showErrDialog = false }) { Text("OK") } },
            containerColor    = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────

@Composable
fun HmiHeader(
    currentVersion : String,
    connected      : Boolean,
    updateState    : UpdateState,
    onCheckUpdate  : () -> Unit,
    onDownload     : (UpdateState.Available) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text          = "EV MANAGER",
                fontSize      = 18.sp,
                fontWeight    = FontWeight.Bold,
                color         = HmiFg,
                letterSpacing = 3.sp
            )
            Text(
                text     = "Controle de combustível em viagem",
                fontSize = 11.sp,
                color    = HmiFgDim
            )
        }
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (currentVersion != "--") {
                Text(
                    text     = "v$currentVersion",
                    fontSize = 11.sp,
                    color    = HmiFgDim
                )
            }
            UpdateButton(
                updateState = updateState,
                onCheck     = onCheckUpdate,
                onDownload  = onDownload
            )
            StatusDot(connected = connected)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Update button — compact widget that lives in the header
// States: Idle → Checking → Available → Downloading → UpToDate → Idle
// ─────────────────────────────────────────────────────────────

@Composable
fun UpdateButton(
    updateState : UpdateState,
    onCheck     : () -> Unit,
    onDownload  : (UpdateState.Available) -> Unit
) {
    when (updateState) {
        is UpdateState.Idle -> {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(HmiSurface2, CircleShape)
                    .clickable { onCheck() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Default.Refresh,
                    contentDescription = "Verificar atualização",
                    tint               = HmiFgDim,
                    modifier           = Modifier.size(15.dp)
                )
            }
        }

        is UpdateState.Checking -> {
            Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(18.dp),
                    color       = HmiFgMuted,
                    strokeWidth = 2.dp
                )
            }
        }

        is UpdateState.Available -> {
            Row(
                modifier = Modifier
                    .background(UpdateBlueBg, RoundedCornerShape(14.dp))
                    .border(1.dp, UpdateBlue.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onDownload(updateState) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.Refresh,
                    contentDescription = null,
                    tint               = UpdateBlue,
                    modifier           = Modifier.size(12.dp)
                )
                Text(
                    text       = updateState.version.removePrefix("v"),
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = UpdateBlue
                )
            }
        }

        is UpdateState.Downloading -> {
            Row(
                modifier = Modifier
                    .background(UpdateBlueBg, RoundedCornerShape(14.dp))
                    .border(1.dp, UpdateBlue.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                CircularProgressIndicator(
                    progress    = { updateState.progress },
                    modifier    = Modifier.size(13.dp),
                    color       = UpdateBlue,
                    strokeWidth = 2.dp
                )
                Text(
                    text     = "${(updateState.progress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color    = UpdateBlue
                )
            }
        }

        is UpdateState.UpToDate -> {
            Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector        = Icons.Default.Check,
                    contentDescription = "Atualizado",
                    tint               = HmiAccent,
                    modifier           = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Status dot
// ─────────────────────────────────────────────────────────────

@Composable
fun StatusDot(connected: Boolean) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (connected) HmiAccent else Color(0xFF555555),
                    CircleShape
                )
        )
        Text(
            text     = if (connected) "Conectado" else "Desconectado",
            fontSize = 11.sp,
            color    = if (connected) HmiAccent else Color(0xFF555555)
        )
    }
}

// ─────────────────────────────────────────────────────────────
// AutoToggleCard — full-width toggle for the EV cycle automation
// ─────────────────────────────────────────────────────────────

@Composable
fun AutoToggleCard(
    modifier        : Modifier = Modifier,
    autoEnabled     : Boolean,
    connected       : Boolean,
    chargeSocTarget : String,
    batteryLevel    : String
) {
    val bgGradient = if (autoEnabled)
        Brush.horizontalGradient(listOf(HmiAccentSoft, Color.Transparent))
    else
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    val borderColor = if (autoEnabled) HmiAccentEdge else HmiBorder

    val battery = batteryLevel.toIntOrNull()
    val phaseText = when {
        !connected               -> "Aguardando conexão com o veículo"
        !autoEnabled             -> "Toque para ativar o ciclo automático de carga"
        chargeSocTarget == "80"  -> buildString {
            append("⬆  Carregando — aguardando bateria ≥ 75%")
            if (battery != null) append("  ·  bateria: $battery%")
        }
        chargeSocTarget == "20"  -> buildString {
            append("⬇  Descarregando — aguardando bateria ≤ 20%")
            if (battery != null) append("  ·  bateria: $battery%")
        }
        else                     -> "Aguardando leitura do SOC target…"
    }

    Box(
        modifier = modifier
            .background(bgGradient, RoundedCornerShape(18.dp))
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier              = Modifier.weight(1f),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector        = Icons.Filled.Autorenew,
                    contentDescription = null,
                    tint               = if (autoEnabled) HmiAccent else HmiFgDim,
                    modifier           = Modifier.size(24.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text          = "CICLO AUTOMÁTICO",
                        fontSize      = 12.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = if (autoEnabled) HmiAccent else HmiFgMuted,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text     = phaseText,
                        fontSize = 11.sp,
                        color    = HmiFgDim
                    )
                }
            }
            Switch(
                checked         = autoEnabled,
                onCheckedChange = { if (connected) EvStateHolder.sendCommand("auto_enabled", it.toString()) },
                enabled         = connected,
                colors          = SwitchDefaults.colors(
                    checkedThumbColor           = HmiBg,
                    checkedTrackColor           = HmiAccent,
                    uncheckedThumbColor         = HmiFgDim,
                    uncheckedTrackColor         = HmiSurface2,
                    disabledCheckedTrackColor   = HmiAccent.copy(alpha = 0.38f),
                    disabledUncheckedTrackColor = HmiSurface2.copy(alpha = 0.38f)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// EvCycleCard — click to cycle 0 → 1 → 2 → 0
// ─────────────────────────────────────────────────────────────

@Composable
fun EvCycleCard(
    modifier  : Modifier = Modifier,
    label     : String,
    propKey   : String,
    value     : String,
    icon      : ImageVector,
    connected : Boolean,
    locked    : Boolean = false
) {
    val isUnknown   = value == "--"
    val currentInt  = value.toIntOrNull()
    val isClickable = connected && !isUnknown && !locked

    val valueColor = when (currentInt) {
        0    -> ValueColor0
        1    -> ValueColor1
        2    -> ValueColor2
        else -> HmiFgDim
    }

    val bgGradient = if (!locked && isClickable && currentInt != null && currentInt > 0) {
        Brush.verticalGradient(listOf(HmiAccentSoft, Color.Transparent))
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    val borderColor = when {
        locked                                              -> Color(0x1FF59E0B)
        isClickable && currentInt != null && currentInt > 0 -> HmiAccentEdge
        else                                               -> HmiBorder
    }

    Box(
        modifier = modifier
            .background(bgGradient, RoundedCornerShape(22.dp))
            .border(1.dp, borderColor, RoundedCornerShape(22.dp))
            .then(
                if (isClickable) Modifier.clickable {
                    val next = ((currentInt ?: 0) + 1) % 3
                    EvStateHolder.sendCommand(propKey, next.toString())
                } else Modifier
            )
            .padding(22.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = when {
                        locked                                              -> BatteryAmber.copy(alpha = 0.6f)
                        isClickable && currentInt != null && currentInt > 0 -> HmiAccent
                        else                                               -> HmiFgDim
                    },
                    modifier           = Modifier.size(28.dp)
                )
                Text(
                    text       = label,
                    fontSize   = 11.sp,
                    color      = HmiFgMuted,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text     = "car.ev_setting",
                    fontSize = 10.sp,
                    color    = HmiFgDim
                )
            }

            Box(
                modifier         = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = value,
                    fontSize   = if (isUnknown) 64.sp else 72.sp,
                    fontWeight = FontWeight.Bold,
                    color      = if (isUnknown) HmiFgDim else valueColor,
                    textAlign  = TextAlign.Center
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("0", "1", "2").forEach { v ->
                        val isCurrent = value == v
                        val chipColor = when {
                            isCurrent && v == "0" -> ValueColor0
                            isCurrent && v == "1" -> ValueColor1
                            isCurrent && v == "2" -> ValueColor2
                            else                  -> Color(0xFF2A2A2A)
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isCurrent) chipColor.copy(alpha = 0.15f) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .border(1.dp, if (isCurrent) chipColor else HmiBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text       = v,
                                fontSize   = 13.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color      = if (isCurrent) chipColor else HmiFgDim
                            )
                        }
                    }
                }
                val footerText = when {
                    locked      -> "Controlado pelo Auto"
                    isClickable -> "Toque para alterar"
                    !connected  -> "Aguardando conexão"
                    else        -> "--"
                }
                val footerColor = if (locked) BatteryAmber.copy(alpha = 0.6f) else HmiFgDim
                Text(text = footerText, fontSize = 10.sp, color = footerColor)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// EvReadOnlyCard — display only
// ─────────────────────────────────────────────────────────────

@Composable
fun EvReadOnlyCard(
    modifier : Modifier = Modifier,
    label    : String,
    value    : String,
    icon     : ImageVector
) {
    val isUnknown = value == "--"

    Box(
        modifier = modifier
            .background(HmiSurface, RoundedCornerShape(22.dp))
            .border(1.dp, HmiBorder, RoundedCornerShape(22.dp))
            .padding(22.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = HmiFgDim,
                    modifier           = Modifier.size(28.dp)
                )
                Text(
                    text       = label,
                    fontSize   = 11.sp,
                    color      = HmiFgMuted,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text     = "car.ev_setting",
                    fontSize = 10.sp,
                    color    = HmiFgDim
                )
            }

            Box(
                modifier         = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = value,
                    fontSize   = if (isUnknown) 64.sp else 72.sp,
                    fontWeight = FontWeight.Bold,
                    color      = if (isUnknown) HmiFgDim else Color(0xFF60A5FA),
                    textAlign  = TextAlign.Center
                )
            }

            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(HmiSurface2, RoundedCornerShape(4.dp))
                        .border(1.dp, HmiBorderStr, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(text = "somente leitura", fontSize = 10.sp, color = HmiFgDim)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// BatteryCard — read-only battery level with color indicator
// ─────────────────────────────────────────────────────────────

@Composable
fun BatteryCard(
    modifier       : Modifier = Modifier,
    batteryLevel   : String,
    remainOdometer : String = "--"
) {
    val isUnknown = batteryLevel == "--"
    val battery   = batteryLevel.toIntOrNull()

    val batteryColor = when {
        battery == null -> HmiFgDim
        battery <= 20   -> BatteryRed
        battery >= 75   -> HmiAccent
        else            -> BatteryAmber
    }

    Box(
        modifier = modifier
            .background(HmiSurface, RoundedCornerShape(22.dp))
            .border(1.dp, HmiBorder, RoundedCornerShape(22.dp))
            .padding(22.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector        = Icons.Filled.BatteryFull,
                    contentDescription = null,
                    tint               = batteryColor,
                    modifier           = Modifier.size(28.dp)
                )
                Text(
                    text       = "cur_battery_power_percentage",
                    fontSize   = 11.sp,
                    color      = HmiFgMuted,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text     = "car.ev_info",
                    fontSize = 10.sp,
                    color    = HmiFgDim
                )
            }

            Box(
                modifier         = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text       = batteryLevel,
                        fontSize   = if (isUnknown) 64.sp else 72.sp,
                        fontWeight = FontWeight.Bold,
                        color      = if (isUnknown) HmiFgDim else batteryColor,
                        textAlign  = TextAlign.Center
                    )
                    if (!isUnknown) {
                        Text(
                            text      = "%",
                            fontSize  = 18.sp,
                            color     = batteryColor.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (battery != null) {
                    LinearProgressIndicator(
                        progress   = { (battery / 100f).coerceIn(0f, 1f) },
                        modifier   = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color      = batteryColor,
                        trackColor = HmiSurface2
                    )
                }
                // Remaining range
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text     = if (remainOdometer == "--") "-- km" else "$remainOdometer km",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color    = if (remainOdometer == "--") HmiFgDim else batteryColor
                    )
                    Text(
                        text     = "restantes",
                        fontSize = 10.sp,
                        color    = HmiFgDim
                    )
                }
                Box(
                    modifier = Modifier
                        .background(HmiSurface2, RoundedCornerShape(4.dp))
                        .border(1.dp, HmiBorderStr, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(text = "somente leitura", fontSize = 10.sp, color = HmiFgDim)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// AutoHevCard — independent Auto HEV toggle
// ─────────────────────────────────────────────────────────────

@Composable
fun AutoHevCard(
    modifier    : Modifier = Modifier,
    autoHev     : Boolean,
    connected   : Boolean,
    engineState : String,
    lastChange  : String,
    basicOdo    : String,
    battery     : String
) {
    val bgGradient = if (autoHev)
        Brush.horizontalGradient(listOf(Color(0x1F3B82F6), Color.Transparent))
    else
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    val accentBlue  = Color(0xFF3B82F6)
    val borderColor = if (autoHev) accentBlue.copy(alpha = 0.4f) else HmiBorder

    val odo = basicOdo.toIntOrNull()
    val bat = battery.toIntOrNull()
    val statusText = when {
        !connected -> "Aguardando conexão com o veículo"
        !autoHev   -> "Ativar ciclo de recuperação HEV (24h)"
        lastChange == "--" -> "Aguardando primeira mudança do motor…"
        else -> buildString {
            append("Última mudança: $lastChange")
            if (odo != null) append("  ·  $odo km")
            if (bat != null) append("  ·  $bat%")
        }
    }

    Box(
        modifier = modifier
            .background(bgGradient, RoundedCornerShape(18.dp))
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier              = Modifier.weight(1f),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector        = Icons.Filled.Loop,
                    contentDescription = null,
                    tint               = if (autoHev) accentBlue else HmiFgDim,
                    modifier           = Modifier.size(24.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text          = "AUTO HEV",
                        fontSize      = 12.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = if (autoHev) accentBlue else HmiFgMuted,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text     = statusText,
                        fontSize = 11.sp,
                        color    = HmiFgDim
                    )
                }
            }
            Switch(
                checked         = autoHev,
                onCheckedChange = { if (connected) EvStateHolder.sendCommand("auto_hev_enabled", it.toString()) },
                enabled         = connected,
                colors          = SwitchDefaults.colors(
                    checkedThumbColor           = HmiBg,
                    checkedTrackColor           = accentBlue,
                    uncheckedThumbColor         = HmiFgDim,
                    uncheckedTrackColor         = HmiSurface2,
                    disabledCheckedTrackColor   = accentBlue.copy(alpha = 0.38f),
                    disabledUncheckedTrackColor = HmiSurface2.copy(alpha = 0.38f)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// EngineStateCard — car.basic.engine_state (read-only)
// ─────────────────────────────────────────────────────────────

@Composable
fun EngineStateCard(
    modifier    : Modifier = Modifier,
    engineState : String,
    lastChange  : String
) {
    val isUnknown    = engineState == "--"
    val isOn         = engineState == "13"
    val stateColor   = when {
        isUnknown -> HmiFgDim
        isOn      -> HmiAccent
        else      -> HmiFgDim
    }
    val stateLabel   = when {
        isUnknown -> "--"
        isOn      -> "Ligado"
        else      -> "Desligado"
    }

    Box(
        modifier = modifier
            .background(HmiSurface, RoundedCornerShape(22.dp))
            .border(
                1.dp,
                if (isOn) HmiAccentEdge else HmiBorder,
                RoundedCornerShape(22.dp)
            )
            .padding(22.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector        = Icons.Filled.Power,
                    contentDescription = null,
                    tint               = stateColor,
                    modifier           = Modifier.size(28.dp)
                )
                Text(
                    text       = "engine_state",
                    fontSize   = 11.sp,
                    color      = HmiFgMuted,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text     = "car.basic",
                    fontSize = 10.sp,
                    color    = HmiFgDim
                )
            }

            Box(
                modifier         = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text       = engineState,
                        fontSize   = if (isUnknown) 64.sp else 72.sp,
                        fontWeight = FontWeight.Bold,
                        color      = stateColor,
                        textAlign  = TextAlign.Center
                    )
                    if (!isUnknown) {
                        Text(
                            text      = stateLabel,
                            fontSize  = 12.sp,
                            color     = stateColor.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (lastChange != "--") {
                    Text(
                        text     = "mudança: $lastChange",
                        fontSize = 10.sp,
                        color    = HmiFgDim,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .background(HmiSurface2, RoundedCornerShape(4.dp))
                        .border(1.dp, HmiBorderStr, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(text = "somente leitura", fontSize = 10.sp, color = HmiFgDim)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// BasicOdoCard — car.basic.remain_odometer (total remaining range)
// ─────────────────────────────────────────────────────────────

@Composable
fun BasicOdoCard(
    modifier : Modifier = Modifier,
    basicOdo : String
) {
    val isUnknown = basicOdo == "--"

    Box(
        modifier = modifier
            .background(HmiSurface, RoundedCornerShape(22.dp))
            .border(1.dp, HmiBorder, RoundedCornerShape(22.dp))
            .padding(22.dp)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector        = Icons.Filled.Route,
                    contentDescription = null,
                    tint               = HmiFgDim,
                    modifier           = Modifier.size(28.dp)
                )
                Text(
                    text       = "fuel_mode_remain_odometer",
                    fontSize   = 11.sp,
                    color      = HmiFgMuted,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text     = "car.ev_info",
                    fontSize = 10.sp,
                    color    = HmiFgDim
                )
            }

            Box(
                modifier         = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text       = basicOdo,
                        fontSize   = if (isUnknown) 64.sp else 56.sp,
                        fontWeight = FontWeight.Bold,
                        color      = if (isUnknown) HmiFgDim else HmiFgMuted,
                        textAlign  = TextAlign.Center
                    )
                    if (!isUnknown) {
                        Text(
                            text      = "km",
                            fontSize  = 16.sp,
                            color     = HmiFgDim,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(HmiSurface2, RoundedCornerShape(4.dp))
                        .border(1.dp, HmiBorderStr, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(text = "somente leitura", fontSize = 10.sp, color = HmiFgDim)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Action log
// ─────────────────────────────────────────────────────────────

@Composable
fun ActionLog(modifier: Modifier = Modifier, entries: List<String>) {
    Column(modifier = modifier) {
        Text(
            text       = "Histórico de Ações",
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color      = HmiFgMuted
        )
        Spacer(Modifier.height(4.dp))
        if (entries.isEmpty()) {
            Box(
                modifier         = Modifier
                    .fillMaxSize()
                    .background(HmiSurface, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text      = "Nenhum comando enviado ainda.",
                    fontSize  = 12.sp,
                    color     = HmiFgDim,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HmiSurface, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(entries) { entry ->
                    Text(
                        text       = entry,
                        fontSize   = 11.sp,
                        color      = if (entry.contains("[AUTO]")) HmiAccent.copy(alpha = 0.8f) else HmiFgMuted,
                        fontFamily = FontFamily.Monospace
                    )
                    HorizontalDivider(color = HmiBorder, thickness = 0.5.dp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────

private fun compareVersions(v1: String, v2: String): Int {
    val p1 = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val p2 = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until minOf(p1.size, p2.size)) {
        if (p1[i] > p2[i]) return 1
        if (p1[i] < p2[i]) return -1
    }
    return p1.size.compareTo(p2.size)
}
