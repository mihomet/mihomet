package org.mihomet.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import org.mihomet.app.ui.components.accentContentColor
import org.mihomet.app.ui.theme.AccentGreen
import org.mihomet.app.ui.theme.AccentGreenDeep
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType

/** "Notifications" card: the optional live-speed notification toggle. */
@Composable
internal fun NotificationsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val view = LocalView.current

    SettingsSectionTitle(stringResource(R.string.settings_notifications))
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ExpressiveShapes.Medium)
                    .clickable {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        viewModel.updateShowSpeedInNotification(!settings.showSpeedInNotification)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = AccentGreen.copy(alpha = 0.16f),
                        modifier = Modifier.size(SettingsIconContainerSize)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = accentContentColor(AccentGreen, AccentGreenDeep),
                                modifier = Modifier.size(SettingsIconSize)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = stringResource(R.string.settings_speed_notification),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.settings_speed_notification_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = settings.showSpeedInNotification,
                    onCheckedChange = {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        viewModel.updateShowSpeedInNotification(it)
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_notification_note),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
