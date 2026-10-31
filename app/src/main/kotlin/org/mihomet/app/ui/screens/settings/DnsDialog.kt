package org.mihomet.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.R
import org.mihomet.app.core.dns.DnsCategory
import org.mihomet.app.core.dns.DnsPresets
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.PrimaryViolet

/**
 * Custom DNS picker (Settings → Advanced): free input + the built-in presets
 * grouped by reachability category, each group with a colored badge.
 */
@Composable
fun CustomDnsDialog(
    currentDns: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var dnsInput by remember { mutableStateOf(currentDns) }
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_dns_dialog_title), fontWeight = FontWeight.Black)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_dns_dialog_desc),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // F14: honest disclosure — bootstrap resolution of proxy hostnames
                // is always plain RU-reachable DNS, regardless of the chosen preset.
                Text(
                    text = stringResource(R.string.dns_bootstrap_desc),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = dnsInput,
                    onValueChange = { dnsInput = it },
                    label = { Text(stringResource(R.string.settings_dns_input_label)) },
                    placeholder = { Text("https://dns.adguard-dns.com/dns-query") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (dnsInput.isNotBlank()) {
                                IconButton(onClick = { dnsInput = "" }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.cd_clear),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { text ->
                                    dnsInput = text.trim()
                                }
                            }) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = stringResource(R.string.cd_paste),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )

                Text(
                    text = stringResource(R.string.settings_dns_presets_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Default choice: built-in smart resolver pool, no category badge.
                DnsPresetRow(
                    name = stringResource(R.string.dns_preset_default),
                    url = "",
                    isSelected = dnsInput.isBlank(),
                    onSelect = { dnsInput = "" }
                )

                // Presets grouped by reachability category, each group with a colored badge.
                DnsCategory.entries.forEach { category ->
                    val categoryPresets = DnsPresets.byCategory(category)
                    if (categoryPresets.isNotEmpty()) {
                        DnsCategoryBadge(category)
                        if (category == DnsCategory.GLOBAL) {
                            // F14: honest disclosure — ConfigGenerator always appends a
                            // plain RU-reachable resolver in parallel to GLOBAL presets
                            // (mihomo queries nameservers concurrently, so there is no
                            // strict encrypted-first failover ordering).
                            Text(
                                text = stringResource(R.string.dns_global_fallback_warning),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        categoryPresets.forEach { preset ->
                            DnsPresetRow(
                                name = DnsPresetDisplayName(preset.uri),
                                url = preset.uri,
                                isSelected = dnsInput.trim() == preset.uri,
                                onSelect = { dnsInput = preset.uri }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(dnsInput.trim()) },
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

/** One selectable DNS preset row inside [CustomDnsDialog]. */
@Composable
private fun DnsPresetRow(
    name: String,
    url: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.Small)
            .clickable { onSelect() },
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
        },
        shape = ExpressiveShapes.Small,
        border = if (isSelected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = name,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
                if (url.isNotBlank()) {
                    Text(
                        text = url,
                        fontSize = 11.sp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_dns_default_desc),
                        fontSize = 11.sp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Colored reachability badge shown above each preset group in [CustomDnsDialog]. */
@Composable
private fun DnsCategoryBadge(category: DnsCategory) {
    val color = when (category) {
        // Brand accents: teal = plain, violet = encrypted RU, amber = caution (global).
        DnsCategory.PLAINTEXT_RU -> Color(0xFF2DD4BF)
        DnsCategory.ENCRYPTED_RU -> PrimaryViolet
        DnsCategory.GLOBAL -> AccentOrange
    }
    Surface(
        shape = ExpressiveShapes.Pill,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(category.labelRes),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** Human-readable preset name for a saved DNS URI; falls back to the raw URI for custom servers. */
@Composable
fun DnsPresetDisplayName(uri: String): String {
    val preset = DnsPresets.ALL.firstOrNull { it.uri == uri.trim() } ?: return uri
    return when (preset.id) {
        "yandex_plain_1", "yandex_plain_2" -> stringResource(R.string.dns_preset_yandex_plain)
        "yandex_doh" -> stringResource(R.string.dns_preset_yandex)
        "comss_doh" -> stringResource(R.string.dns_preset_comss)
        "adguard_doh" -> stringResource(R.string.dns_preset_adguard)
        "cloudflare_doh_ip" -> stringResource(R.string.dns_preset_cloudflare_ip)
        "cloudflare_doh" -> stringResource(R.string.dns_preset_cloudflare)
        "google_doh" -> stringResource(R.string.dns_preset_google)
        "quad9_doh" -> stringResource(R.string.dns_preset_quad9)
        else -> preset.uri
    }
}
