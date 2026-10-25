package org.mihomet.app.ui.screens.proxies

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.ui.theme.AccentGreen
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AccentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxiesScreen(
    viewModel: ProxiesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Proxies", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.loadProxies() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search nodes...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.groups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        state.error ?: "No proxy groups found. Start proxy or import a profile.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(state.groups) { group ->
                        ProxyGroupCard(
                            group = group,
                            searchQuery = state.searchQuery,
                            onSelectNode = { viewModel.selectNode(group.name, it) },
                            onTestGroup = { viewModel.testGroupDelay(group.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProxyGroupCard(
    group: GroupUiModel,
    searchQuery: String,
    onSelectNode: (String) -> Unit,
    onTestGroup: () -> Unit
) {
    val filteredNodes = group.nodes.filter {
        searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Group Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = group.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "${group.type} • ${group.nodes.size} nodes",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalIconButton(
                    onClick = onTestGroup,
                    enabled = !group.isTestingGroup
                ) {
                    if (group.isTestingGroup) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Bolt, contentDescription = "Test Delay")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Nodes List
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filteredNodes.forEach { node ->
                    val isSelected = node.name == group.selectedNode

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectNode(node.name) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
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
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                Text(
                                    text = node.name,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(node.type, fontSize = 10.sp) },
                                    modifier = Modifier.height(26.dp)
                                )

                                if (node.delay > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val delayColor = when {
                                        node.delay < 200 -> AccentGreen
                                        node.delay < 500 -> AccentOrange
                                        else -> AccentRed
                                    }
                                    Text(
                                        text = "${node.delay}ms",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = delayColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
