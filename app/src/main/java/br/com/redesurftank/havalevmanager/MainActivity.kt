package br.com.redesurftank.havalevmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.redesurftank.havalevmanager.ui.theme.HavalEvManagerTheme
import java.text.SimpleDateFormat

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

private const val PROP_POWER_MODEL      = "car.ev_setting.power_model_config"
private const val PROP_CHARGE_SOC       = "car.ev_setting.charge_soc_target_config"
private const val PROP_POWER_RESERVE    = "car.ev_setting.power_reserve_config"

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
    val state = EvStateHolder

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HmiBg)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Header
        HmiHeader(connected = state.vehicleConnected)

        Spacer(Modifier.height(14.dp))

        // Cards row — fills most of the height
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EvCycleCard(
                modifier    = Modifier.weight(1f).fillMaxHeight(),
                label       = "power_model_config",
                propKey     = PROP_POWER_MODEL,
                value       = state.powerModelConfig,
                icon        = Icons.Filled.ElectricCar,
                connected   = state.vehicleConnected
            )
            EvReadOnlyCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label    = "charge_soc_target_config",
                value    = state.chargeSocTargetConfig,
                icon     = Icons.Filled.BatteryChargingFull
            )
            EvCycleCard(
                modifier    = Modifier.weight(1f).fillMaxHeight(),
                label       = "power_reserve_config",
                propKey     = PROP_POWER_RESERVE,
                value       = state.powerReserveConfig,
                icon        = Icons.Filled.EnergySavingsLeaf,
                connected   = state.vehicleConnected
            )
        }

        Spacer(Modifier.height(12.dp))

        // Action log
        ActionLog(
            modifier = Modifier.fillMaxWidth().height(110.dp),
            entries  = state.actionLog.toList()
        )
    }
}

// ─────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────

@Composable
fun HmiHeader(connected: Boolean) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text       = "EV MANAGER",
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                color      = HmiFg,
                letterSpacing = 3.sp
            )
            Text(
                text     = "Controle de combustível em viagem",
                fontSize = 11.sp,
                color    = HmiFgDim
            )
        }
        StatusDot(connected = connected)
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
    val timeFmt       = remember { SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    val isUnknown     = value == "--"
    val currentInt    = value.toIntOrNull()
    val isClickable   = connected && !isUnknown

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
            // Top: icon + label
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

            // Center: current value large
            Box(
                modifier        = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (isUnknown) {
                    Text(
                        text       = "--",
                        fontSize   = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color      = HmiFgDim,
                        textAlign  = TextAlign.Center
                    )
                } else {
                    Text(
                        text       = value,
                        fontSize   = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color      = valueColor,
                        textAlign  = TextAlign.Center
                    )
                }
            }

            // Bottom: value indicator chips + hint
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 3 chips: 0, 1, 2
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("0", "1", "2").forEach { v ->
                        val isCurrent = value == v
                        val chipColor = when {
                            isCurrent && v == "0" -> ValueColor0
                            isCurrent && v == "1" -> ValueColor1
                            isCurrent && v == "2" -> ValueColor2
                            else                  -> Color(0xFF2A2A2A)
                        }
                        val chipBorder = if (isCurrent) chipColor else HmiBorder
                        Box(
                            modifier         = Modifier
                                .background(
                                    if (isCurrent) chipColor.copy(alpha = 0.15f) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .border(1.dp, chipBorder, RoundedCornerShape(6.dp))
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
            // Top: icon + label
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

            // Center: current value large
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

            // Bottom: read-only badge
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
                    Text(
                        text     = "somente leitura",
                        fontSize = 10.sp,
                        color    = HmiFgDim
                    )
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
