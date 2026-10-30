package org.mihomet.app.ui.screens.routing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.mihomet.app.ui.components.SearchPill
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType

@OptIn(ExperimentalMaterial3Api::class)
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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_app_exclusions),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    )
                },
                navigationIcon = {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp)
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
        ) {
            // Mode Selector Card
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.routing_tunnel_mode),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.ALL,
                                onClick = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updatePerAppMode(PerAppMode.ALL)
                                },
                                shape = SegmentedButtonDefaults.itemShape(0, 3),
                                icon = {}
                            ) {
                                Text(stringResource(R.string.routing_mode_all), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.WHITELIST,
                                onClick = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updatePerAppMode(PerAppMode.WHITELIST)
                                },
                                shape = SegmentedButtonDefaults.itemShape(1, 3),
                                icon = {}
                            ) {
                                Text(stringResource(R.string.routing_mode_whitelist), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.BLACKLIST,
                                onClick = {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.updatePerAppMode(PerAppMode.BLACKLIST)
                                },
                                shape = SegmentedButtonDefaults.itemShape(2, 3),
                                icon = {}
                            ) {
                                Text(stringResource(R.string.routing_mode_blacklist), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }

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
                        // Expressive Capsule Search Bar
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
                // is empty, so tell the user the mode currently behaves as "All".
                if (settings.perAppMode == PerAppMode.WHITELIST && settings.selectedAppUids.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = ExpressiveShapes.Small,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = stringResource(R.string.perapp_empty_whitelist),
                                modifier = Modifier.padding(14.dp),
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
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(ExpressiveShapes.Medium)
                                .clickable {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    viewModel.toggleAppUid(app.uid)
                                },
                            shape = ExpressiveShapes.Medium,
                            color = if (isChecked) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
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
                                    if (app.icon != null) {
                                        Image(
                                            bitmap = app.icon.toBitmap().asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(ExpressiveShapes.Small)
                                        )
                                    } else {
                                        Surface(
                                            shape = ExpressiveShapes.Small,
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            modifier = Modifier.size(40.dp)
                                        ) {}
                                    }

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
                                    onCheckedChange = {
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
    }
}
