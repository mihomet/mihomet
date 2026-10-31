package org.mihomet.app.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mihomet.app.R
import org.mihomet.app.core.OemHints
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AccentOrangeContainer
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType

/**
 * Manufacturer diagnostics card (known OEM ROMs only — the caller gates the
 * item on [OemHints.OemBrand.STOCK]): battery-killer warning + a launch
 * shortcut into the vendor's battery settings.
 */
@Composable
internal fun OemDiagnosticsSection(
    oemBrand: OemHints.OemBrand,
    oemLaunch: Intent?,
    oemLabel: String
) {
    val context = LocalContext.current
    val view = LocalView.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = ExpressiveShapes.Small,
                    color = AccentOrangeContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column {
                    Text(
                        stringResource(R.string.oem_diag_title),
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                    Text(
                        stringResource(R.string.oem_diag_desc, oemLabel),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (oemLaunch != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        runCatching { context.startActivity(oemLaunch) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.Medium
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.oem_diag_open), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
