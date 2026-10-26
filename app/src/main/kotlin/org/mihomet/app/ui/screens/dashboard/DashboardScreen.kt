package org.mihomet.app.ui.screens.dashboard

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.ui.components.ExpressiveLatencyBadge
import org.mihomet.app.ui.components.PulsingStatusDot
import org.mihomet.app.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToConnections: () -> Unit,
    onNavigateToLogs: () -> Unit,
    viewModel: DashboardViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startProxy()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Mihomet",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp
                        )

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = if (state.isRootMode) Icons.Default.AdminPanelSettings else Icons.Default.VpnKey,
                                    contentDescription = null,
                                    tint = if (state.isRootMode) AccentOrange else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (state.isRootMode) "Root TProxy" else "VPN Режим",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                actions = {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        IconButton(
                            onClick = onNavigateToConnections,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.Hub,
                                contentDescription = "Соединения",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        IconButton(
                            onClick = onNavigateToLogs,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = "Журнал",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. Hero Connect Button with Expressive Morphing Squircle and Dual Pulse Rings
            ExpressiveHeroConnect(
                isRunning = state.isRunning,
                isConnecting = state.isConnecting,
                onClick = {
                    AppHaptics.perform(context, HapticType.HEAVY, view)
                    if (state.isRunning) {
                        viewModel.stopProxy()
                    } else {
                        if (state.isRootMode) {
                            viewModel.startProxy()
                        } else {
                            val prepareIntent = VpnService.prepare(context)
                            if (prepareIntent != null) {
                                vpnPrepareLauncher.launch(prepareIntent)
                            } else {
                                viewModel.startProxy()
                            }
                        }
                    }
                }
            )

            // 2. Current Server Hero Card
            CurrentServerHeroCard(
                serverName = state.selectedNode,
                profileName = state.activeProfileName,
                delayMs = state.selectedNodeDelay,
                onClick = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    onNavigateToServers()
                }
            )

            // 3. Real-time Traffic Cards (Download & Upload)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveSpeedCard(
                    title = "Входящий",
                    speedText = formatSpeed(state.currentTraffic.down),
                    icon = Icons.Default.ArrowDownward,
                    accentColor = AccentGreen,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f)
                )

                ExpressiveSpeedCard(
                    title = "Исходящий",
                    speedText = formatSpeed(state.currentTraffic.up),
                    icon = Icons.Default.ArrowUpward,
                    accentColor = PrimaryDark,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f)
                )
            }

            // 4. Routing Mode Segmented Pill Card
            ExpressiveRoutingModeCard(
                activeMode = state.activeMode,
                onModeSelected = { mode ->
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.setMode(mode)
                }
            )

            // 5. Traffic Activity Graph Card
            if (state.isRunning && state.trafficHistory.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Активность трафика",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    PulsingStatusDot(color = AccentGreen, size = 6.dp)
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "LIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        color = AccentGreen
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        TrafficGraph(
                            history = state.trafficHistory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

/**
 * Material 3 Expressive Hero Connect Widget with tactile morphing squircle and breathing aura.
 */
@Composable
fun ExpressiveHeroConnect(
    isRunning: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseHalo")

    val outerRingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning || isConnecting) 1.18f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outerRingScale"
    )

    val innerRingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning || isConnecting) 1.09f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "innerRingScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isRunning -> AccentGreen
            isConnecting -> AccentOrange
            else -> PrimaryDark
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "heroButtonColor"
    )

    val buttonGlowColor by animateColorAsState(
        targetValue = when {
            isRunning -> AccentGreen.copy(alpha = 0.28f)
            isConnecting -> AccentOrange.copy(alpha = 0.28f)
            else -> PrimaryDark.copy(alpha = 0.15f)
        },
        label = "heroGlowColor"
    )

    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "pressScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(190.dp)
        ) {
            // Outer Ambient Pulse Ring
            if (isRunning || isConnecting) {
                Box(
                    modifier = Modifier
                        .size(185.dp)
                        .scale(outerRingScale)
                        .clip(ExpressiveShapes.Hero)
                        .background(buttonGlowColor.copy(alpha = 0.14f))
                )

                // Inner Ambient Pulse Ring
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(innerRingScale)
                        .clip(ExpressiveShapes.Hero)
                        .background(buttonGlowColor)
                )
            }

            // Main Tactile Squircle Button
            Surface(
                shape = ExpressiveShapes.Hero,
                color = buttonColor,
                shadowElevation = if (isRunning) 14.dp else 6.dp,
                modifier = Modifier
                    .size(138.dp)
                    .scale(pressScale)
                    .clip(ExpressiveShapes.Hero)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(52.dp),
                            strokeWidth = 3.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Shield else Icons.Default.PowerSettingsNew,
                            contentDescription = "Подключение",
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Status Capsule Pill
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                1.dp,
                if (isRunning) AccentGreen.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                PulsingStatusDot(
                    color = when {
                        isRunning -> AccentGreen
                        isConnecting -> AccentOrange
                        else -> MaterialTheme.colorScheme.outline
                    },
                    size = 7.dp,
                    pulsing = isRunning || isConnecting
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = when {
                        isConnecting -> "ПОДКЛЮЧЕНИЕ..."
                        isRunning -> "ЗАЩИЩЕНО"
                        else -> "НЕ ПОДКЛЮЧЕНО"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = when {
                        isRunning -> AccentGreen
                        isConnecting -> AccentOrange
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/**
 * Expressive Current Server Card with tactile squircle and ping badge.
 */
@Composable
fun CurrentServerHeroCard(
    serverName: String,
    profileName: String,
    delayMs: Long,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.Large)
            .clickable(onClick = onClick),
        shape = ExpressiveShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = ExpressiveShapes.Medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (serverName.startsWith("⚡") || serverName.contains("Auto", ignoreCase = true)) Icons.Default.Bolt else Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Активный сервер",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = serverName.ifBlank { "Выбрать сервер" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "Подписка: $profileName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (delayMs > 0 || delayMs == -1L) {
                    ExpressiveLatencyBadge(delayMs = delayMs)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Сменить сервер",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Expressive Download / Upload Speed Card with bold numeric typography and colored squircle icon.
 */
@Composable
fun ExpressiveSpeedCard(
    title: String,
    speedText: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = ExpressiveShapes.Large,
        color = containerColor,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = ExpressiveShapes.Small,
                    color = accentColor.copy(alpha = 0.16f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = speedText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Expressive Routing Mode Card with fluid segmented pill control.
 */
@Composable
fun ExpressiveRoutingModeCard(
    activeMode: ProxyMode,
    onModeSelected: (ProxyMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Режим маршрутизации",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Segmented Pill Container
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val modes = listOf(
                        Triple(ProxyMode.RULE, "Умный", Icons.AutoMirrored.Filled.AltRoute),
                        Triple(ProxyMode.GLOBAL, "Все сайты", Icons.Default.Public),
                        Triple(ProxyMode.DIRECT, "Напрямую", Icons.Default.ArrowOutward)
                    )

                    modes.forEach { (mode, label, icon) ->
                        val isSelected = activeMode == mode

                        val tabBg by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                            label = "tabBg"
                        )
                        val contentColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            label = "tabContentColor"
                        )

                        Surface(
                            shape = CircleShape,
                            color = tabBg,
                            modifier = Modifier
                                .weight(1f)
                                .clip(CircleShape)
                                .clickable { onModeSelected(mode) }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    color = contentColor
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = when (activeMode) {
                    ProxyMode.RULE -> "Обход блокировок: заблокированные сервисы через прокси, российские сайты напрямую."
                    ProxyMode.GLOBAL -> "Полное проксирование: весь сетевой трафик смартфона направляется через выбранный сервер."
                    ProxyMode.DIRECT -> "Прямое подключение: весь интернет-трафик идет напрямую в обход ядра прокси."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f МБ/с", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f КБ/с", bytesPerSec / 1024.0)
        bytesPerSec > 0 -> "$bytesPerSec Б/с"
        else -> "0 Б/с"
    }
}
