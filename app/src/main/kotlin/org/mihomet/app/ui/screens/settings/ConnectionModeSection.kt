package org.mihomet.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.mihomet.app.R
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.ui.components.accentContentColor
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AccentOrangeDeep
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.HapticType

/** "Connection mode" card: VPN vs Root selector and the boot autostart toggle. */
@Composable
internal fun ConnectionModeSection(
    settings: AppSettings,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val view = LocalView.current

    SettingsSectionTitle(stringResource(R.string.settings_connection_mode))
    Spacer(modifier = Modifier.height(6.dp))
    D2Panel {
        Column(
            verticalArrangement = Arrangement.spacedBy(SettingsRowSpacing)
        ) {
            ExpressiveModeSelectRow(
                icon = Icons.Default.VpnKey,
                iconBg = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                title = stringResource(R.string.settings_mode_vpn_title),
                description = stringResource(R.string.settings_mode_vpn_desc),
                selected = !settings.isRootMode,
                onSelect = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.updateRootMode(false)
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            ExpressiveModeSelectRow(
                icon = Icons.Default.AdminPanelSettings,
                iconBg = AccentOrange.copy(alpha = 0.16f),
                iconTint = accentContentColor(AccentOrange, AccentOrangeDeep),
                title = stringResource(R.string.settings_mode_root_title),
                description = stringResource(R.string.settings_mode_root_desc),
                selected = settings.isRootMode,
                onSelect = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.updateRootMode(true)
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // Autostart on boot (BootReceiver + last successfully started mode)
            ExpressiveSwitchRow(
                icon = Icons.Default.RestartAlt,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                title = stringResource(R.string.settings_autostart),
                subtitle = stringResource(R.string.settings_autostart_desc),
                checked = settings.autoStartOnBoot,
                onCheckedChange = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.updateAutoStartOnBoot(it)
                }
            )
        }
    }
}
