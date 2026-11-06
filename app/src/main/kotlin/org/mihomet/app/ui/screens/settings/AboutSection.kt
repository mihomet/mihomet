package org.mihomet.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.flow.StateFlow
import org.mihomet.app.R
import org.mihomet.app.data.update.AppUpdateManager
import org.mihomet.app.data.update.AppUpdateState
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.theme.ExpressiveShapes

/**
 * ViewModel for the About section's self-update row (audit P1.4). Thin bridge
 * to [AppUpdateManager]: all state lives in the manager's
 * [AppUpdateState] machine, all side effects in the manager.
 */
class AppUpdateViewModel(private val updater: AppUpdateManager) : ViewModel() {

    val state: StateFlow<AppUpdateState> = updater.state

    /** Installed versionName (PackageManager) shown next to the "Version" label. */
    val installedVersionName: String = updater.installedVersionName

    fun checkForUpdates() {
        viewModelScope.launch { updater.checkForUpdate() }
    }

    fun downloadUpdate() {
        viewModelScope.launch { updater.downloadVerifiedUpdate() }
    }

    fun installUpdate() {
        updater.installReadyUpdate()
    }

    /** Always-available manual fallback: the GitHub releases page. */
    fun openReleasePage() {
        updater.openReleasePage()
    }

    /** Android 8–12 "install unknown apps" redirect — user comes back on their own. */
    fun openInstallPermissionSettings() {
        updater.openInstallPermissionSettings()
    }
}

/** "About" card: app identity, edition blurb and the self-update version row. */
@Composable
internal fun AboutSection(viewModel: AppUpdateViewModel = koinViewModel()) {
    D2Panel {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = ExpressiveShapes.Small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(SettingsIconContainerSize)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column {
                    Text(stringResource(R.string.settings_about_title), fontWeight = FontWeight.Black, fontSize = 15.sp)
                    Text(stringResource(R.string.settings_about_edition), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_about_desc),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            Spacer(modifier = Modifier.height(10.dp))

            AppUpdateRow(viewModel)
        }
    }
}

/**
 * Version row + in-app update state machine (idle → checking → upToDate |
 * updateAvailable | downloading | readyToInstall | error). Inline state text
 * only — no snackbars (house rule). "Open release page" is always available.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppUpdateRow(viewModel: AppUpdateViewModel) {
    val updateState by viewModel.state.collectAsState()

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = stringResource(R.string.app_update_version_label),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Text(
            text = viewModel.installedVersionName,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val (statusText, statusColor) = when (val state = updateState) {
        AppUpdateState.Idle -> null to MaterialTheme.colorScheme.onSurfaceVariant
        AppUpdateState.Checking ->
            stringResource(R.string.app_update_checking) to MaterialTheme.colorScheme.onSurfaceVariant
        is AppUpdateState.UpToDate ->
            stringResource(R.string.app_update_up_to_date, state.currentVersion) to MaterialTheme.colorScheme.onSurfaceVariant
        is AppUpdateState.UpdateAvailable ->
            stringResource(R.string.app_update_available, state.version) to MaterialTheme.colorScheme.primary
        is AppUpdateState.UpdateAvailableManual ->
            stringResource(R.string.app_update_available_manual, state.version) to MaterialTheme.colorScheme.primary
        is AppUpdateState.Downloading ->
            stringResource(R.string.app_update_downloading, state.progressPercent) to MaterialTheme.colorScheme.primary
        is AppUpdateState.ReadyToInstall ->
            stringResource(R.string.app_update_ready, state.version) to MaterialTheme.colorScheme.primary
        is AppUpdateState.NeedInstallPermission ->
            stringResource(R.string.app_update_need_permission) to MaterialTheme.colorScheme.primary
        is AppUpdateState.InstallRequested ->
            stringResource(R.string.app_update_install_requested) to MaterialTheme.colorScheme.onSurfaceVariant
        is AppUpdateState.Error ->
            stringResource(R.string.app_update_error, state.message) to MaterialTheme.colorScheme.error
    }

    statusText?.let { text ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = statusColor
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        when (val state = updateState) {
            is AppUpdateState.UpdateAvailable -> {
                FilledTonalButton(onClick = viewModel::downloadUpdate) {
                    Text(stringResource(R.string.app_update_download), fontSize = 12.sp)
                }
            }

            is AppUpdateState.ReadyToInstall -> {
                FilledTonalButton(onClick = viewModel::installUpdate) {
                    Text(stringResource(R.string.app_update_install), fontSize = 12.sp)
                }
            }

            is AppUpdateState.NeedInstallPermission -> {
                FilledTonalButton(onClick = viewModel::openInstallPermissionSettings) {
                    Text(stringResource(R.string.app_update_grant_permission), fontSize = 12.sp)
                }
            }

            else -> {
                TextButton(
                    onClick = viewModel::checkForUpdates,
                    enabled = updateState !is AppUpdateState.Checking
                ) {
                    Text(stringResource(R.string.app_update_check), fontSize = 12.sp)
                }
            }
        }

        TextButton(onClick = viewModel::openReleasePage) {
            Text(stringResource(R.string.app_update_open_release), fontSize = 12.sp)
        }
    }
}
