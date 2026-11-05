package org.mihomet.app.ui.screens.routing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.core.config.UserRules
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType

/**
 * Standalone "Site rules" screen: user-defined always-direct domains
 * (bare TLDs allowed) that win over subscription rules in Smart mode.
 * Lives outside the per-app exclusions screen on purpose — its own
 * menu entry, its own screen (user feedback: the card got lost at the
 * bottom of the app list).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteRulesScreen(
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
                        text = stringResource(R.string.nav_site_rules),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Description card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.Large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.routing_user_rules_desc),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Add-domain card
            var newDomain by remember { mutableStateOf("") }
            var showInvalid by remember { mutableStateOf(false) }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newDomain,
                            onValueChange = { newDomain = it; showInvalid = false },
                            placeholder = {
                                Text(
                                    stringResource(R.string.routing_user_rules_add_hint),
                                    fontSize = 13.sp
                                )
                            },
                            isError = showInvalid,
                            supportingText = if (showInvalid) {
                                {
                                    Text(
                                        stringResource(R.string.routing_user_rules_invalid),
                                        fontSize = 11.sp
                                    )
                                }
                            } else null,
                            singleLine = true,
                            shape = ExpressiveShapes.Small,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = ExpressiveShapes.Small,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(ExpressiveShapes.Small)
                                .clickable {
                                    AppHaptics.perform(context, HapticType.CLICK, view)
                                    if (newDomain.isNotBlank()) {
                                        // Local sanitize for synchronous feedback;
                                        // the repository re-validates on write.
                                        if (UserRules.sanitizeDomain(newDomain) == null) {
                                            showInvalid = true
                                        } else {
                                            viewModel.addDirectDomain(newDomain)
                                            newDomain = ""
                                        }
                                    }
                                }
                        ) {
                            Text(
                                text = stringResource(R.string.routing_user_rules_add),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }

                    if (settings.userDirectDomains.isEmpty()) {
                        Text(
                            text = stringResource(R.string.routing_user_rules_empty),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        settings.userDirectDomains.sorted().forEach { domain ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = ExpressiveShapes.Medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 8.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = domain,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            AppHaptics.perform(context, HapticType.CLICK, view)
                                            viewModel.removeDirectDomain(domain)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.cd_delete),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
