package org.mihomet.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.R
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.subs.SubscriptionRepository
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType

/** "Subscriptions" card: global identity (UA / x-hwid) used for every download. */
@Composable
internal fun SubscriptionsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onShowUaDialog: () -> Unit,
    onShowHwidDialog: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    SettingsSectionTitle(stringResource(R.string.settings_subs_section))
    Spacer(modifier = Modifier.height(6.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Subscription User-Agent (blank = built-in default chain)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ExpressiveShapes.Small)
                    .clickable {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        onShowUaDialog()
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_subs_ua),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        stringResource(
                            R.string.settings_subs_ua_summary,
                            settings.subscriptionUserAgent.ifBlank {
                                stringResource(R.string.settings_subs_ua_desc)
                            }
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(18.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // x-hwid device header toggle
            ExpressiveSwitchRow(
                icon = Icons.Default.Fingerprint,
                iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                title = stringResource(R.string.settings_subs_hwid),
                subtitle = stringResource(R.string.settings_subs_hwid_desc),
                checked = settings.sendHwid,
                onCheckedChange = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.updateSendHwid(it)
                }
            )

            // Custom x-hwid override (only meaningful while the header is sent)
            if (settings.sendHwid) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ExpressiveShapes.Small)
                        .clickable {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            onShowHwidDialog()
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            stringResource(R.string.settings_subs_hwid_custom),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            stringResource(R.string.settings_subs_hwid_custom_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.Tag, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Global subscription User-Agent (Settings → Subscriptions). Blank input
 * restores the built-in honest default chain — the hint spells it out.
 */
@Composable
fun SubscriptionUserAgentDialog(
    currentUa: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var uaInput by remember { mutableStateOf(currentUa) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_subs_ua_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = uaInput,
                    onValueChange = { uaInput = it },
                    label = { Text(stringResource(R.string.settings_subs_ua)) },
                    placeholder = { Text(SubscriptionRepository.APP_USER_AGENT) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    trailingIcon = {
                        if (uaInput.isNotEmpty()) {
                            IconButton(onClick = { uaInput = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.settings_subs_ua_hint,
                        SubscriptionRepository.APP_USER_AGENT
                    ),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(uaInput.trim()) },
                shape = ExpressiveShapes.Small
            ) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Custom `x-hwid` override (Settings → Subscriptions); blank = device-derived id. */
@Composable
fun SubscriptionHwidDialog(
    currentHwid: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var hwidInput by remember { mutableStateOf(currentHwid) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_subs_hwid_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = hwidInput,
                    onValueChange = { hwidInput = it },
                    label = { Text(stringResource(R.string.settings_subs_hwid_custom)) },
                    placeholder = { Text("0123456789abcdef0123456789abcdef") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    trailingIcon = {
                        if (hwidInput.isNotEmpty()) {
                            IconButton(onClick = { hwidInput = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.settings_subs_hwid_hint),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(hwidInput) },
                shape = ExpressiveShapes.Small
            ) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
