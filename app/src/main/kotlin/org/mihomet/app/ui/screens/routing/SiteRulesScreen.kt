package org.mihomet.app.ui.screens.routing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import org.mihomet.app.ui.components.D2BackHeader
import org.mihomet.app.ui.design2.D2
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.HapticType

/**
 * Standalone "Site rules" screen: user-defined always-direct domains
 * (bare TLDs allowed) that win over subscription rules in Smart mode.
 * Lives outside the per-app exclusions screen on purpose — its own
 * menu entry, its own screen (user feedback: the card got lost at the
 * bottom of the app list).
 *
 * Edition 2 language: big back-title header, filled tonal panels, no outlines.
 */
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .padding(horizontal = D2.ScreenH)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(D2.SectionGap)
        ) {
            // ── Header: big display title + round tonal back action ──────────
            D2BackHeader(
                title = stringResource(R.string.nav_site_rules),
                backContentDescription = stringResource(R.string.cd_back),
                onBack = onNavigateBack,
                modifier = Modifier.padding(top = 12.dp)
            )

            // ── Description panel ────────────────────────────────────────────
            D2Panel(contentPadding = PaddingValues(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = D2.Inner,
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

            // ── Add-domain panel ─────────────────────────────────────────────
            var newDomain by remember { mutableStateOf("") }
            var showInvalid by remember { mutableStateOf(false) }
            D2Panel(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            shape = D2.Inner,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = D2.Container,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(D2.Container)
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
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
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
                                shape = D2.Inner,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 4.dp
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
