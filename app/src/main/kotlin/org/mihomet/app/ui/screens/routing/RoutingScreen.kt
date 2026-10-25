package org.mihomet.app.ui.screens.routing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.data.preferences.PerAppMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: RoutingViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val settings = state.settings

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Исключения приложений", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            // Mode Selector Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Режим туннелирования",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.ALL,
                                onClick = { viewModel.updatePerAppMode(PerAppMode.ALL) },
                                shape = SegmentedButtonDefaults.itemShape(0, 3)
                            ) {
                                Text("Все", fontSize = 13.sp)
                            }
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.WHITELIST,
                                onClick = { viewModel.updatePerAppMode(PerAppMode.WHITELIST) },
                                shape = SegmentedButtonDefaults.itemShape(1, 3)
                            ) {
                                Text("Белый список", fontSize = 13.sp)
                            }
                            SegmentedButton(
                                selected = settings.perAppMode == PerAppMode.BLACKLIST,
                                onClick = { viewModel.updatePerAppMode(PerAppMode.BLACKLIST) },
                                shape = SegmentedButtonDefaults.itemShape(2, 3)
                            ) {
                                Text("Черный список", fontSize = 13.sp)
                            }
                        }

                        val modeDescription = when (settings.perAppMode) {
                            PerAppMode.ALL -> "Трафик всех приложений устройства направляется через прокси."
                            PerAppMode.WHITELIST -> "Только отмеченные приложения направляются через прокси. Остальные приложения работают напрямую."
                            PerAppMode.BLACKLIST -> "Все приложения работают через прокси, кроме отмеченных (например, российские банки, Госуслуги)."
                        }

                        Text(
                            text = modeDescription,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Controls & App list (only when Whitelist or Blacklist is selected)
            if (settings.perAppMode != PerAppMode.ALL) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Search bar
                        OutlinedTextField(
                            value = state.appSearchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text("Поиск по названию или пакету...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.appSearchQuery.isNotBlank()) {
                                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                        )

                        // Quick action bar (Selected count & buttons)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Выбрано: ${settings.selectedAppUids.size} из ${state.installedApps.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { viewModel.selectAllApps() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Все", fontSize = 12.sp)
                                }
                                TextButton(
                                    onClick = { viewModel.clearAllSelectedApps() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Сбросить", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                val filteredApps = state.installedApps.filter {
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
                            CircularProgressIndicator()
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
                                text = "Приложения не найдены",
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
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { viewModel.toggleAppUid(app.uid) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isChecked) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
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
                                    if (app.icon != null) {
                                        Image(
                                            bitmap = app.icon.toBitmap().asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = app.label,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = app.packageName,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { viewModel.toggleAppUid(app.uid) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
