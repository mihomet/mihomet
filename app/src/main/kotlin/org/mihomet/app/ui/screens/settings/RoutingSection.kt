package org.mihomet.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.R
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.ProxyMode
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType

/** "Routing & app rules" card: proxy mode selector and the per-app exclusions entry. */
@Composable
internal fun RoutingSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onNavigateToRouting: () -> Unit,
    onNavigateToSiteRules: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current

    SettingsSectionTitle(stringResource(R.string.settings_routing_section))
    Spacer(modifier = Modifier.height(6.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsRowSpacing)
        ) {
            Text(
                text = stringResource(R.string.settings_proxy_mode),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = settings.proxyMode == ProxyMode.RULE,
                    onClick = {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        viewModel.updateProxyMode(ProxyMode.RULE)
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 3)
                ) {
                    Text(stringResource(R.string.mode_rule), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                SegmentedButton(
                    selected = settings.proxyMode == ProxyMode.GLOBAL,
                    onClick = {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        viewModel.updateProxyMode(ProxyMode.GLOBAL)
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 3)
                ) {
                    Text(stringResource(R.string.mode_global), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                SegmentedButton(
                    selected = settings.proxyMode == ProxyMode.DIRECT,
                    onClick = {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        viewModel.updateProxyMode(ProxyMode.DIRECT)
                    },
                    shape = SegmentedButtonDefaults.itemShape(2, 3)
                ) {
                    Text(stringResource(R.string.mode_direct_short), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // App Exclusions Clickable Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ExpressiveShapes.Medium)
                    .clickable {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        onNavigateToRouting()
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(SettingsIconContainerSize)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(SettingsIconSize)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = stringResource(R.string.settings_app_exclusions),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = stringResource(R.string.settings_app_exclusions_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // Site rules (always-direct domains) — dedicated screen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ExpressiveShapes.Medium)
                    .clickable {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        onNavigateToSiteRules()
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(SettingsIconContainerSize)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(SettingsIconSize)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = stringResource(R.string.nav_site_rules),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = stringResource(R.string.settings_site_rules_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
