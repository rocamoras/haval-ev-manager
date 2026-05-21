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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat

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

private const val PROP_POWER_MODEL   = "car.ev_setting.power_model_config"
private const val PROP_CHARGE_SOC    = "car.ev_setting.charge_soc_target_config"
private const val PROP_POWER_RESERVE = "car.ev_setting.power_reserve_config"

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

    var currentVersion   by remember { mutableStateOf("--") }
    var isDownloading    by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var updateAvailable  by remember { mutableStateOf(false) }
    var latestVersion    by remember { mutableStateOf("") }
    var downloadUrl      by remember { mutableStateOf("") }
    var showErrDialog    by remember { mutableStateOf(false) }
    var errDialogText    by remember { mutableStateOf("") }
    var showPermDialog   by remember { mutableStateOf(false) }
    var downloadJob      by remember { mutableStateOf<Job?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

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
                                latestVersion   = tag
                                downloadUrl     = dlUrl
                                updateAvailable = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background update check failed: ${e.message}")
                }
            }
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

    fun startDownload() {
        isDownloading = true; downloadProgress = 0f
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val file  = File(context.getExternalFilesDir(null), "update.apk")
                val conn  = URL(downloadUrl).openConnection() as HttpURLConnection
                val total = conn.contentLength
                val buf   = ByteArray(4096)
                var bytes = 0; var read: Int
                FileOutputStream(file).use { out ->
                    BufferedInputStream(conn.inputStream).use { inp ->
                        while (inp.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read); bytes += read
                            if (total > 0) downloadProgress = bytes.toFloat() / total
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    isDownloading = false; updateAvailable = false; installApk(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    isDownloading = false
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
        HmiHeader(currentVersion = currentVersion, connected = state.vehicleConnected)

        Spacer(Modifier.height(10.dp))

        // Update banner — shown only when a new release is available
        if (updateAvailable) {
            Button(
                onClick        = { startDownload() },
                enabled        = !isDownloading,
                modifier       = Modifier.fillMaxWidth(),
                colors         = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                shape          = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(14.dp)
            ) {
                if (isDownloading) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Baixando $latestVersion… ${(downloadProgress * 100).toInt()}%", fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color    = Color.White
                        )
                    }
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Atualizar para $latestVersion", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Cards row — fills most of the height
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
                connected = state.vehicleConnected
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
                connected = state.vehicleConnected
            )
        }

        Spacer(Modifier.height(12.dp))

        ActionLog(
            modifier = Modifier.fillMaxWidth().height(110.dp),
            entries  = state.actionLog.toList()
        )
    }

    // Permission dialog
    if (showPermDialog) {
        AlertDialog(
            onDismissRequest = { showPermDialog = false },
            title            = { Text("Permissão necessária") },
            text             = { Text("Permita a instalação de apps de fontes desconhecidas para instalar a atualização.") },
            confirmButton    = {
                TextButton(onClick = {
                    showPermDialog = false
                    permLauncher.launch(
                        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }) { Text("Abrir Configurações") }
            },
            dismissButton    = { TextButton(onClick = { showPermDialog = false }) { Text("Cancelar") } },
            containerColor   = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }

    // Error dialog
    if (showErrDialog) {
        AlertDialog(
            onDismissRequest = { showErrDialog = false },
            title            = { Text("Erro") },
            text             = { Text(errDialogText) },
            confirmButton    = { TextButton(onClick = { showErrDialog = false }) { Text("OK") } },
            containerColor   = Color(0xFF1E1E1E),
            titleContentColor = HmiFg,
            textContentColor  = HmiFgMuted
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────

@Composable
fun HmiHeader(currentVersion: String, connected: Boolean) {
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
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (currentVersion != "--") {
                Text(
                    text     = "v$currentVersion",
                    fontSize = 11.sp,
                    color    = HmiFgDim
                )
            }
            StatusDot(connected = connected)
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
// EvCycleCard — click to cycle 0 → 1 → 2 → 0
// ─────────────────────────────────────────────────────────────

@Composable
fun EvCycleCard(
    modifier  : Modifier = Modifier,
    label     : String,
    propKey   : String,
    value     : String,
    icon      : ImageVector,
    connected : Boolean
) {
    val isUnknown   = value == "--"
    val currentInt  = value.toIntOrNull()
    val isClickable = connected && !isUnknown

    val valueColor = when (currentInt) {
        0    -> ValueColor0
        1    -> ValueColor1
        2    -> ValueColor2
        else -> HmiFgDim
    }

    val bgGradient = if (isClickable && currentInt != null && currentInt > 0) {
        Brush.verticalGradient(listOf(HmiAccentSoft, Color.Transparent))
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    val borderColor = if (isClickable && currentInt != null && currentInt > 0)
        HmiAccentEdge else HmiBorder

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
                    tint               = if (isClickable && currentInt != null && currentInt > 0) HmiAccent else HmiFgDim,
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
                Text(
                    text     = if (isClickable) "Toque para alterar" else if (!connected) "Aguardando conexão" else "--",
                    fontSize = 10.sp,
                    color    = HmiFgDim
                )
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
                        color      = HmiFgMuted,
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
