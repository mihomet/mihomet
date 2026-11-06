package org.mihomet.app.ui.screens.routing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.data.preferences.PerAppMode
import org.mihomet.app.ui.components.D2BackHeader
import org.mihomet.app.ui.components.SearchPill
import org.mihomet.app.ui.components.rememberPressScale
import org.mihomet.app.ui.design2.D2
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.design2.D2SectionLabel
import org.mihomet.app.ui.design2.D2SegmentOption
import org.mihomet.app.ui.design2.D2Segmented
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.HapticType

/**
 * Per-app exclusions on the Edition 2 language: big back-title header, the
 * tunneling mode as a springy D2Segmented control inside a filled panel, and
 * filled tonal app rows — checked apps take the primaryContainer tint (the
 * only state allowed a border).
 */
@Composable
fun RoutingScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: RoutingViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val settings = state.settings
    val context = LocalContext.current
    val view = LocalView.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .padding(horizontal = D2.ScreenH),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            // ── Header: big display title + round tonal back action ──────────
            item {
                D2BackHeader(
                    title = stringResource(R.string.settings_app_exclusions),
                    backContentDescription = stringResource(R.string.cd_back),
                    onBack = onNavigateBack
                )
            }

            // ── Tunneling mode: segmented control in a filled panel ──────────
            item {
                D2Panel {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        D2SectionLabel(text = stringResource(R.string.routing_tunnel_mode))

                        D2Segmented(
                            options = listOf(
                                D2SegmentOption(PerAppMode.ALL, stringResource(R.string.routing_mode_all)),
                                D2SegmentOption(PerAppMode.WHITELIST, stringResource(R.string.routing_mode_whitelist)),
                                D2SegmentOption(PerAppMode.BLACKLIST, stringResource(R.string.routing_mode_blacklist))
                            ),
                            selected = settings.perAppMode,
                            onOptionSelected = { mode ->
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.updatePerAppMode(mode)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        val modeDescription = when (settings.perAppMode) {
                            PerAppMode.ALL -> stringResource(R.string.routing_mode_all_desc)
                            PerAppMode.WHITELIST -> stringResource(R.string.routing_mode_whitelist_desc)
                            PerAppMode.BLACKLIST -> stringResource(R.string.routing_mode_blacklist_desc)
                        }

                        Text(
                            text = modeDescription,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Controls & App list (only when Whitelist or Blacklist is selected)
            if (settings.perAppMode != PerAppMode.ALL) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Filled capsule search bar
                        SearchPill(
                            value = state.appSearchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholderRes = R.string.routing_search_hint,
                            modifier = Modifier.fillMaxWidth()
                        )

                        val visibleApps = state.installedApps.filter {
                            state.showSystemApps || !it.isSystem || it.packageName == "com.android.chrome"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.routing_selected_count, settings.selectedAppUids.size, visibleApps.size),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (state.showSystemApps) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { viewModel.toggleShowSystemApps() }
                                ) {
                                    Text(
                                        text = stringResource(R.string.routing_system_apps),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.showSystemApps) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { viewModel.selectAllApps() }
                                ) {
                                    Text(
                                        text = stringResource(R.string.routing_mode_all),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { viewModel.clearAllSelectedApps() }
                                ) {
                                    Text(
                                        text = stringResource(R.string.routing_reset),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Empty-whitelist hint: Android routes every app when the allow list
                // is empty (both the VPN engine and, since the firewall engines treat
                // an empty whitelist as ALL, root mode), so tell the user the mode
                // currently behaves as "All".
                if (settings.perAppMode == PerAppMode.WHITELIST && settings.selectedAppUids.isEmpty()) {
                    item {
                        D2Panel(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentPadding = PaddingValues(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.perapp_empty_whitelist),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Only-self warning: the VPN builder never allows our own package
                // (its traffic would loop into the TUN it feeds), so with Mihomet as
                // the sole selection no addAllowedApplication() call happens and
                // Android routes every app — warn the user about this degenerate state.
                if (settings.perAppMode == PerAppMode.WHITELIST &&
                    settings.selectedAppUids == setOf(android.os.Process.myUid())
                ) {
                    item {
                        D2Panel(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentPadding = PaddingValues(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.perapp_only_self),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val visibleApps = state.installedApps.filter {
                    state.showSystemApps || !it.isSystem || it.packageName == "com.android.chrome"
                }
                val filteredApps = visibleApps.filter {
                    state.appSearchQuery.isBlank() ||
                            it.label.contains(state.appSearchQuery, ignoreCase = true) ||
                            it.packageName.contains(state.appSearchQuery, ignoreCase = true)
                }

                if (state.isLoadingApps) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (filteredApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.routing_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val isChecked = settings.selectedAppUids.contains(app.uid)
                        AppExclusionRow(
                            app = app,
                            isChecked = isChecked,
                            onToggle = {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                viewModel.toggleAppUid(app.uid)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One app row on the Edition 2 language: filled tonal panel; the checked
 * state takes the primaryContainer tint and — as the sole allowed border —
 * a primary edge, plus a springy press squeeze.
 */
@Composable
private fun AppExclusionRow(
    app: AppItem,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val rowPressScale = rememberPressScale(rowInteraction, pressedScale = 0.98f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(rowPressScale)
            .clip(D2.Inner)
            .clickable(interactionSource = rowInteraction, onClick = onToggle),
        shape = D2.Inner,
        color = if (isChecked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (isChecked) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                AppIcon(packageName = app.packageName)

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (app.isSystem) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = stringResource(R.string.routing_system_badge),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = app.packageName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

/** Launcher icon of the app, clipped to the inner squircle; blank box on failure. */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }
    if (icon != null) {
        Image(
            bitmap = remember(icon) { icon.toBitmap().asImageBitmap() },
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(D2.Inner)
        )
    } else {
        Surface(
            shape = D2.Inner,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(40.dp)
        ) {}
    }
}
