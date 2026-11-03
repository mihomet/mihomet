package org.mihomet.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.R
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.TunOptions
import org.mihomet.app.ui.theme.ExpressiveShapes

/** Ports editor (mixed / tproxy / DNS / controller) with validation + duplicate check. */
@Composable
fun PortsConfigDialog(
    currentSettings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, Int) -> Unit
) {
    var mixedPort by remember { mutableStateOf(currentSettings.mixedPort.toString()) }
    var tproxyPort by remember { mutableStateOf(currentSettings.tproxyPort.toString()) }
    var dnsPort by remember { mutableStateOf(currentSettings.dnsPort.toString()) }
    var controllerPort by remember { mutableStateOf(currentSettings.controllerPort.toString()) }

    val numberError = stringResource(R.string.settings_port_error_number)
    val rangeError = stringResource(R.string.settings_port_error_range)

    fun validate(value: String): String? {
        val port = value.toIntOrNull() ?: return numberError
        if (port !in 1024..65535) return rangeError
        return null
    }

    val mixedError = validate(mixedPort)
    val tproxyError = validate(tproxyPort)
    val dnsError = validate(dnsPort)
    val controllerError = validate(controllerPort)

    val parsedPorts = listOf(mixedPort, tproxyPort, dnsPort, controllerPort).mapNotNull { it.toIntOrNull() }
    val duplicateError =
        if (parsedPorts.size == 4 && parsedPorts.size != parsedPorts.distinct().size) {
            stringResource(R.string.settings_port_error_duplicate)
        } else {
            null
        }
    val isValid = mixedError == null && tproxyError == null && dnsError == null &&
            controllerError == null && duplicateError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ports_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = mixedPort,
                    onValueChange = { mixedPort = it },
                    label = { Text(stringResource(R.string.settings_port_mixed)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    isError = mixedError != null,
                    supportingText = { mixedError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tproxyPort,
                    onValueChange = { tproxyPort = it },
                    label = { Text(stringResource(R.string.settings_port_tproxy)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    isError = tproxyError != null,
                    supportingText = { tproxyError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dnsPort,
                    onValueChange = { dnsPort = it },
                    label = { Text(stringResource(R.string.settings_port_dns)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    isError = dnsError != null,
                    supportingText = { dnsError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = controllerPort,
                    onValueChange = { controllerPort = it },
                    label = { Text(stringResource(R.string.settings_port_controller)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    isError = controllerError != null,
                    supportingText = { controllerError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                if (duplicateError != null) {
                    Text(
                        text = duplicateError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(mixedPort.toInt(), tproxyPort.toInt(), dnsPort.toInt(), controllerPort.toInt())
                },
                enabled = isValid,
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

/** Latency-test URL / timeout editor with Google / Cloudflare shortcuts. */
@Composable
fun LatencyTestConfigDialog(
    currentUrl: String,
    currentTimeoutMs: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var timeoutSec by remember { mutableStateOf((currentTimeoutMs / 1000).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_latency_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_latency_url_label)) },
                    placeholder = { Text("https://www.gstatic.com/generate_204") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = timeoutSec,
                    onValueChange = { timeoutSec = it },
                    label = { Text(stringResource(R.string.settings_latency_timeout_label)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { url = "https://www.gstatic.com/generate_204" },
                        modifier = Modifier.weight(1f),
                        shape = ExpressiveShapes.Small
                    ) {
                        Text("Google", fontSize = 11.sp)
                    }
                    FilledTonalButton(
                        onClick = { url = "https://cp.cloudflare.com/generate_204" },
                        modifier = Modifier.weight(1f),
                        shape = ExpressiveShapes.Small
                    ) {
                        Text("Cloudflare", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val t = (timeoutSec.toIntOrNull() ?: 5) * 1000
                    onSave(url, t)
                },
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

/**
 * Free-form TUN MTU input (1280..65535, [TunOptions] clamps/persists), with
 * the common gVisor-friendly presets as one-tap shortcuts.
 */
@Composable
fun TunMtuConfigDialog(
    currentMtu: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var mtuInput by remember { mutableStateOf(currentMtu.toString()) }

    val numberError = stringResource(R.string.settings_tun_mtu_error_number)
    val rangeError = stringResource(R.string.settings_tun_mtu_error_range)
    val mtu = mtuInput.toIntOrNull()
    val error = when {
        mtu == null -> numberError
        mtu < TunOptions.MIN_MTU || mtu > TunOptions.MAX_MTU -> rangeError
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tun_mtu_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = mtuInput,
                    onValueChange = { mtuInput = it },
                    label = { Text(stringResource(R.string.settings_tun_mtu)) },
                    supportingText = {
                        Text(stringResource(R.string.settings_tun_mtu_hint, TunOptions.DEFAULT_MTU))
                    },
                    isError = error != null,
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1500, 8500, 9000).forEach { preset ->
                        FilledTonalButton(
                            onClick = { mtuInput = preset.toString() },
                            modifier = Modifier.weight(1f),
                            shape = ExpressiveShapes.Small
                        ) {
                            Text(preset.toString(), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(mtu ?: return@Button) },
                enabled = error == null,
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
