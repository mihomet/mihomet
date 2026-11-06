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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.R
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.mihomet.app.core.ServiceState
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.ui.components.ExpressiveLatencyBadge
import org.mihomet.app.ui.components.PulsingStatusDot
import org.mihomet.app.ui.components.rememberPressScale
import org.mihomet.app.ui.theme.*

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
                            text = stringResource(R.string.app_name),
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
                                    text = stringResource(
                                        if (state.isRootMode) R.string.dashboard_mode_root_tproxy else R.string.dashboard_mode_vpn
                                    ),
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
                                contentDescription = stringResource(R.string.nav_connections),
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
                                contentDescription = stringResource(R.string.nav_logs),
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
                isDisconnecting = state.isDisconnecting,
                onClick = {
                    AppHaptics.perform(context, HapticType.HEAVY, view)
                    if (state.isRunning || state.isDisconnecting) {
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
                profileName = state.activeProfileName.ifBlank { stringResource(R.string.dashboard_default_profile_name) },
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
                    title = stringResource(R.string.dashboard_download),
                    speedText = formatSpeed(state.currentTraffic.down),
                    icon = Icons.Default.ArrowDownward,
                    accentColor = AccentGreen,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f)
                )

                ExpressiveSpeedCard(
                    title = stringResource(R.string.dashboard_upload),
                    speedText = formatSpeed(state.currentTraffic.up),
                    icon = Icons.Default.ArrowUpward,
                    accentColor = AccentBlue,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f)
                )
            }

            // 3b. Compact core status row (connections + core memory) — only while running
            if (state.isRunning) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.dash_connections, state.connectionsCount) +
                                "  ·  " +
                                stringResource(R.string.dash_memory, formatMemoryMb(state.memoryInuse)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            // 4. Routing Mode Segmented Pill Card
            ExpressiveRoutingModeCard(
                activeMode = state.activeMode,
                onModeSelected = { mode ->
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.setMode(mode)
                }
            )

            // 5. Traffic Activity Graph Card (always visible; TrafficGraph shows a placeholder when there is no data yet)
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
                            text = stringResource(R.string.dashboard_traffic_activity),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Material 3 Expressive Hero Connect Widget with tactile morphing squircle, dynamic containers, and breathing aura.
 *
 * Ring motion is differentiated per state (M3E kinematics):
 * - connecting: soft emanating pulse (fast grow + fade);
 * - connected: calm breathing halo (slow gentle swell);
 * - disconnecting: contracting spin-down (shrink back to rest).
 * All glow stays within the 165.dp ring bounds — no full-screen glow (OLED efficiency).
 */
@Composable
fun ExpressiveHeroConnect(
    isRunning: Boolean,
    isConnecting: Boolean,
    isDisconnecting: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseHalo")

    // Pulse tempo: energetic while connecting, slowest while protected.
    val ringDuration = when {
        isConnecting -> 900
        isDisconnecting -> 600
        else -> 2400
    }
    // Emanating / contracting rings restart from their origin; breathing swells back and forth.
    val ringRepeat = if (isRunning) RepeatMode.Reverse else RepeatMode.Restart

    val outerRingScale by infiniteTransition.animateFloat(
        initialValue = if (isDisconnecting) 1.08f else 1f,
        targetValue = when {
            isConnecting -> 1.12f
            isDisconnecting -> 1f
            isRunning -> 1.04f
            else -> 1f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(ringDuration, easing = FastOutSlowInEasing),
            repeatMode = ringRepeat
        ),
        label = "outerRingScale"
    )

    val innerRingScale by infiniteTransition.animateFloat(
        initialValue = if (isDisconnecting) 1.05f else 1f,
        targetValue = when {
            isConnecting -> 1.07f
            isDisconnecting -> 1f
            isRunning -> 1.02f
            else -> 1f
        },
        animationSpec = infiniteRepeatable(
            animation = tween((ringDuration * 0.85f).toInt().coerceAtLeast(200), easing = FastOutSlowInEasing),
            repeatMode = ringRepeat
        ),
        label = "innerRingScale"
    )

    // Outer ring breathes its opacity too: prominent while emanating (connecting),
    // steady and calm while protected.
    val outerRingAlpha by infiniteTransition.animateFloat(
        initialValue = if (isConnecting) 0.85f else 0.6f,
        targetValue = when {
            isConnecting -> 0.25f
            isDisconnecting -> 0.2f
            else -> 1f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(ringDuration, easing = FastOutSlowInEasing),
            repeatMode = ringRepeat
        ),
        label = "outerRingAlpha"
    )

    val targetButtonColor = when {
        isRunning -> AccentGreen
        isConnecting -> AccentOrange
        isDisconnecting -> Color(0xFFEA580C)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val buttonColor by animateColorAsState(
        targetValue = targetButtonColor,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "heroButtonColor"
    )

    val iconTint by animateColorAsState(
        targetValue = when {
            isRunning || isConnecting || isDisconnecting -> Color.White
            else -> MaterialTheme.colorScheme.primary
        },
        label = "heroIconTint"
    )

    val targetGlowColor = when {
        isRunning -> AccentGreen.copy(alpha = 0.28f)
        isConnecting -> AccentOrange.copy(alpha = 0.28f)
        isDisconnecting -> Color(0xFFEA580C).copy(alpha = 0.22f)
        else -> Color.Transparent
    }

    val buttonGlowColor by animateColorAsState(
        targetValue = targetGlowColor,
        label = "heroGlowColor"
    )

    // M3E signature "cookie morph": rounded square at rest, 12-lobed cookie while
    // the VPN protects the device. Drawn through graphics-shapes Morph paths —
    // Surface shapes cannot morph, and a draw-phase re-read keeps it frame-exact.
    val heroMorph = remember { ExpressiveMorph.morphFor(ExpressiveMorph.SQUARE, ExpressiveMorph.COOKIE_12) }
    val heroMorphProgress by ExpressiveMorph.progress(if (isRunning) 1f else 0f)
    val heroMorphPath = remember { Path() }

    // Tactile press feedback: ripple is disabled (indication = null) on the big
    // squircle, so the expressive squeeze carries the pressed state instead.
    val heroInteractionSource = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(
        interactionSource = heroInteractionSource,
        pressedScale = 0.93f,
        stiffness = Spring.StiffnessMedium
    )

    var uptimeText by remember { mutableStateOf("") }
    val connectionStartTime by ServiceState.connectionStartTime.collectAsState()
    LaunchedEffect(isRunning, connectionStartTime) {
        if (!isRunning || connectionStartTime == null) {
            uptimeText = ""
            return@LaunchedEffect
        }
        while (isActive) {
            val start = connectionStartTime ?: break
            uptimeText = ServiceState.formatDuration(System.currentTimeMillis() - start)
            delay(1000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(175.dp)
        ) {
            // Outer Ambient Pulse Ring (alpha breathes with the state tempo)
            if (isRunning || isConnecting || isDisconnecting) {
                Box(
                    modifier = Modifier
                        .size(165.dp)
                        .scale(outerRingScale)
                        .clip(ExpressiveShapes.Hero)
                        .background(buttonGlowColor.copy(alpha = 0.14f * outerRingAlpha))
                )

                // Inner Ambient Pulse Ring
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .scale(innerRingScale)
                        .clip(ExpressiveShapes.Hero)
                        .background(buttonGlowColor)
                )
            }

            // Main Tactile Morph Button — a plain Box because Surface cannot render
            // a morphing shape: the outline comes from graphicsLayer (fresh morph
            // wrapper per frame over a cached Morph), the fill from background()
            // (clipped by the same layer), and the rest-state border is stroked
            // over the drawn content.
            // NO shadowElevation here: HWUI spot-shadow tessellation on a concave
            // 12-lobed morph path (stitchConcaveRings) takes effectively forever on
            // real devices — the RenderThread wedges and the app ANRs (realme
            // repro). Depth comes from the pulse rings + glow instead.
            val morphOutlineColor =
                if (!isRunning && !isConnecting && !isDisconnecting) {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                } else {
                    Color.Transparent
                }
            Box(
                modifier = Modifier
                    .size(138.dp)
                    .scale(pressScale)
                    .graphicsLayer {
                        shape = ExpressiveMorph.shape(
                            ExpressiveMorph.SQUARE,
                            ExpressiveMorph.COOKIE_12
                        ) { heroMorphProgress }
                        clip = true
                    }
                    .background(buttonColor)
                    .clickable(
                        interactionSource = heroInteractionSource,
                        indication = null,
                        onClick = onClick
                    )
                    .drawWithContent {
                        drawContent()
                        if (morphOutlineColor != Color.Transparent) {
                            drawPath(
                                ExpressiveMorph.path(
                                    heroMorph, heroMorphProgress, size, heroMorphPath
                                ),
                                morphOutlineColor,
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isConnecting || isDisconnecting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(52.dp),
                            strokeWidth = 3.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Shield else Icons.Default.PowerSettingsNew,
                            contentDescription = stringResource(R.string.cd_dashboard_connect),
                            tint = iconTint,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

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
                        isConnecting || isDisconnecting -> AccentOrange
                        else -> MaterialTheme.colorScheme.outline
                    },
                    size = 7.dp,
                    pulsing = isRunning || isConnecting || isDisconnecting
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = when {
                        isConnecting -> stringResource(R.string.dashboard_status_connecting)
                        isDisconnecting -> stringResource(R.string.dashboard_status_disconnecting)
                        isRunning -> {
                            val base = stringResource(R.string.dashboard_status_protected)
                            if (uptimeText.isNotBlank()) "$base  ·  $uptimeText" else base
                        }
                        else -> stringResource(R.string.dashboard_status_disconnected)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.8.sp,
                    color = when {
                        isRunning -> AccentGreen
                        isConnecting || isDisconnecting -> AccentOrange
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/**
 * Expressive Current Server Card with tactile squircle, press squeeze and ping badge.
 */
@Composable
fun CurrentServerHeroCard(
    serverName: String,
    profileName: String,
    delayMs: Long,
    onClick: () -> Unit
) {
    val cardInteractionSource = remember { MutableInteractionSource() }
    val cardPressScale = rememberPressScale(cardInteractionSource, pressedScale = 0.98f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardPressScale)
            .clip(ExpressiveShapes.Large)
            .clickable(
                interactionSource = cardInteractionSource,
                onClick = onClick
            ),
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
                        text = stringResource(R.string.dashboard_active_server),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = serverName.ifBlank { stringResource(R.string.dashboard_select_server) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = stringResource(R.string.dashboard_subscription_label, profileName),
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
                    contentDescription = stringResource(R.string.cd_dashboard_change_server),
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
                text = stringResource(R.string.dashboard_routing_mode),
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
                        Triple(ProxyMode.RULE, stringResource(R.string.mode_rule), Icons.AutoMirrored.Filled.AltRoute),
                        Triple(ProxyMode.GLOBAL, stringResource(R.string.mode_global), Icons.Default.Public),
                        Triple(ProxyMode.DIRECT, stringResource(R.string.mode_direct), Icons.Default.ArrowOutward)
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
                    ProxyMode.RULE -> stringResource(R.string.dashboard_mode_rule_desc)
                    ProxyMode.GLOBAL -> stringResource(R.string.dashboard_mode_global_desc)
                    ProxyMode.DIRECT -> stringResource(R.string.dashboard_mode_direct_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 -> stringResource(R.string.speed_mb_per_s, bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024 -> stringResource(R.string.speed_kb_per_s, bytesPerSec / 1024.0)
        bytesPerSec > 0 -> stringResource(R.string.speed_b_per_s, bytesPerSec)
        else -> stringResource(R.string.speed_b_per_s, 0L)
    }
}

/** Compact core-memory formatter: bytes → "%.0f MB" (locale-aware). */
@Composable
private fun formatMemoryMb(bytes: Long): String {
    return stringResource(R.string.dash_memory_value, bytes / (1024.0 * 1024.0))
}
