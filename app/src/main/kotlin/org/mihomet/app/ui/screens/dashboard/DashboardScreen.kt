package org.mihomet.app.ui.screens.dashboard

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.core.ServiceState
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.ui.components.ExpressiveLatencyBadge
import org.mihomet.app.ui.components.rememberPressScale
import org.mihomet.app.ui.design2.D2
import org.mihomet.app.ui.design2.D2Caption
import org.mihomet.app.ui.design2.D2Gap
import org.mihomet.app.ui.design2.D2IconAction
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.design2.D2ScreenTitle
import org.mihomet.app.ui.design2.D2SectionLabel
import org.mihomet.app.ui.design2.D2SegmentOption
import org.mihomet.app.ui.design2.D2Segmented
import org.mihomet.app.ui.design2.D2StatBlock
import org.mihomet.app.ui.theme.AccentBlue
import org.mihomet.app.ui.theme.AccentGreen
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveMorph
import org.mihomet.app.ui.theme.HapticType

/**
 * Dashboard on the "Edition 2" language: a full-width hero zone on the plain
 * background (morph connect button + big status text), a tonal server strip,
 * two big-numeral traffic blocks, a segmented routing control in a filled
 * panel, and the traffic chart last. No outlined cards anywhere — separation
 * by tone steps and spacing.
 */
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = D2.ScreenH),
            verticalArrangement = Arrangement.spacedBy(D2.SectionGap)
        ) {
            // ── Header: big brand title + round tonal quick actions ──────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                D2ScreenTitle(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.weight(1f)
                ) {
                    D2IconAction(
                        icon = Icons.Default.Hub,
                        contentDescription = stringResource(R.string.nav_connections),
                        onClick = onNavigateToConnections
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    D2IconAction(
                        icon = Icons.Default.Terminal,
                        contentDescription = stringResource(R.string.nav_logs),
                        onClick = onNavigateToLogs
                    )
                }
            }

            // ── Hero zone: connect morph + big status text, on background ────
            HeroZone(
                isRunning = state.isRunning,
                isConnecting = state.isConnecting,
                isDisconnecting = state.isDisconnecting,
                modeLabel = stringResource(
                    if (state.isRootMode) R.string.dashboard_mode_root_tproxy else R.string.dashboard_mode_vpn
                ),
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

            // ── Server strip ─────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(D2.LabelGap)) {
                D2SectionLabel(text = stringResource(R.string.dashboard_active_server))
                D2Panel(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    D2ServerRow(
                        serverName = state.selectedNode,
                        profileName = state.activeProfileName
                            .ifBlank { stringResource(R.string.dashboard_default_profile_name) },
                        delayMs = state.selectedNodeDelay,
                        onClick = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            onNavigateToServers()
                        }
                    )
                }
            }

            // ── Traffic: two big-numeral stat blocks + live core caption ─────
            Column(verticalArrangement = Arrangement.spacedBy(D2.LabelGap)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(D2.BlockGap),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    D2StatBlock(
                        label = stringResource(R.string.dashboard_download),
                        value = formatSpeed(state.currentTraffic.down),
                        accent = AccentGreen,
                        modifier = Modifier.weight(1f)
                    )
                    D2StatBlock(
                        label = stringResource(R.string.dashboard_upload),
                        value = formatSpeed(state.currentTraffic.up),
                        accent = AccentBlue,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (state.isRunning) {
                    D2Caption(
                        text = stringResource(R.string.dash_connections, state.connectionsCount) +
                            "  ·  " +
                            stringResource(R.string.dash_memory, formatMemoryMb(state.memoryInuse)),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }

            // ── Routing mode: compact segmented control in a filled panel ────
            Column(verticalArrangement = Arrangement.spacedBy(D2.LabelGap)) {
                D2SectionLabel(text = stringResource(R.string.dashboard_routing_mode))
                D2Panel {
                    val modeOptions = listOf(
                        D2SegmentOption(ProxyMode.RULE, stringResource(R.string.mode_rule)),
                        D2SegmentOption(ProxyMode.GLOBAL, stringResource(R.string.mode_global)),
                        D2SegmentOption(ProxyMode.DIRECT, stringResource(R.string.mode_direct))
                    )
                    D2Segmented(
                        options = modeOptions,
                        selected = state.activeMode,
                        onOptionSelected = { mode ->
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.setMode(mode)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    D2Gap()

                    Text(
                        text = when (state.activeMode) {
                            ProxyMode.RULE -> stringResource(R.string.dashboard_mode_rule_desc)
                            ProxyMode.GLOBAL -> stringResource(R.string.dashboard_mode_global_desc)
                            ProxyMode.DIRECT -> stringResource(R.string.dashboard_mode_direct_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }

            // ── Traffic activity chart, last ─────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(D2.LabelGap)) {
                D2SectionLabel(text = stringResource(R.string.dashboard_traffic_activity))
                D2Panel {
                    TrafficGraph(
                        history = state.trafficHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(D2.SectionGap))
        }
    }
}

/** Server switcher strip content: squircle icon, loud node name, latency, chevron. */
@Composable
private fun D2ServerRow(
    serverName: String,
    profileName: String,
    delayMs: Long,
    onClick: () -> Unit
) {
    val isAuto = serverName.startsWith("⚡") || serverName.contains("Auto", ignoreCase = true)
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction, pressedScale = 0.97f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .clickable(interactionSource = interaction) { onClick() }
            .padding(vertical = 6.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(D2.Inner)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(
                imageVector = if (isAuto) Icons.Default.Bolt else Icons.Default.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(23.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = serverName.ifBlank { stringResource(R.string.dashboard_select_server) },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
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

/**
 * Hero zone: the tactile morph button with its breathing aura, then the
 * status as a big display line — the visual anchor of the screen sits
 * directly on the background, not in a card.
 *
 * Ring motion is differentiated per state (M3E kinematics): emanating pulse
 * while connecting, calm breathing while protected, contracting spin-down
 * while disconnecting. All glow stays within the 165.dp ring bounds.
 * NO shadowElevation on the morph path (concave spot-shadow tessellation
 * wedges HWUI on real devices — see ExpressiveMorph notes).
 */
@Composable
private fun HeroZone(
    isRunning: Boolean,
    isConnecting: Boolean,
    isDisconnecting: Boolean,
    modeLabel: String,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "d2HeroHalo")

    val ringDuration = when {
        isConnecting -> 900
        isDisconnecting -> 600
        else -> 2400
    }
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
        label = "d2OuterRingScale"
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
        label = "d2InnerRingScale"
    )

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
        label = "d2OuterRingAlpha"
    )

    val isBusy = isRunning || isConnecting || isDisconnecting

    val targetButtonColor = when {
        isRunning -> AccentGreen
        isConnecting -> AccentOrange
        isDisconnecting -> Color(0xFFEA580C)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val buttonColor by animateColorAsState(
        targetValue = targetButtonColor,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "d2HeroButtonColor"
    )

    val iconTint by animateColorAsState(
        targetValue = if (isBusy) Color.White else MaterialTheme.colorScheme.primary,
        label = "d2HeroIconTint"
    )

    val targetGlowColor = when {
        isRunning -> AccentGreen.copy(alpha = 0.28f)
        isConnecting -> AccentOrange.copy(alpha = 0.28f)
        isDisconnecting -> Color(0xFFEA580C).copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    val glowColor by animateColorAsState(targetValue = targetGlowColor, label = "d2HeroGlowColor")

    val statusColor by animateColorAsState(
        targetValue = when {
            isRunning -> AccentGreen
            isConnecting || isDisconnecting -> AccentOrange
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "d2HeroStatusColor"
    )

    // M3E signature "cookie morph": rounded square at rest, 12-lobed cookie
    // while the VPN protects the device. graphics-shapes Morph, cached, with
    // a draw-phase re-read so the outline is frame-exact.
    val heroMorph = remember { ExpressiveMorph.morphFor(ExpressiveMorph.SQUARE, ExpressiveMorph.COOKIE_12) }
    val heroMorphProgress by ExpressiveMorph.progress(if (isRunning) 1f else 0f)
    val heroMorphPath = remember { Path() }

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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(178.dp)
        ) {
            if (isBusy) {
                Box(
                    modifier = Modifier
                        .size(168.dp)
                        .scale(outerRingScale)
                        .clip(D2.Hero)
                        .background(glowColor.copy(alpha = 0.14f * outerRingAlpha))
                )
                Box(
                    modifier = Modifier
                        .size(152.dp)
                        .scale(innerRingScale)
                        .clip(D2.Hero)
                        .background(glowColor)
                )
            }

            val morphOutlineColor =
                if (!isBusy) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f) else Color.Transparent
            Box(
                modifier = Modifier
                    .size(140.dp)
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
                                ExpressiveMorph.path(heroMorph, heroMorphProgress, size, heroMorphPath),
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

        Spacer(modifier = Modifier.height(22.dp))

        // Big status line — the quiet pill from the old design became the voice.
        Text(
            text = when {
                isConnecting -> stringResource(R.string.dashboard_status_connecting)
                isDisconnecting -> stringResource(R.string.dashboard_status_disconnecting)
                isRunning -> stringResource(R.string.dashboard_status_protected)
                else -> stringResource(R.string.dashboard_status_disconnected)
            },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp,
            color = statusColor
        )

        // Caption: execution mode + live uptime.
        D2Caption(
            text = if (isRunning && uptimeText.isNotBlank()) "$modeLabel  ·  $uptimeText" else modeLabel,
            modifier = Modifier.padding(top = 4.dp)
        )
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
