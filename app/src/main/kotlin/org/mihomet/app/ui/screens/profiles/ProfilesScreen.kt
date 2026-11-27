package org.mihomet.app.ui.screens.profiles

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mihomet.app.data.subs.SubscriptionRepository
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.core.config.ConversionReport
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.ui.components.CountBadge
import org.mihomet.app.ui.components.ExpressiveSnackbarHost
import org.mihomet.app.ui.design2.D2
import org.mihomet.app.ui.design2.D2IconAction
import org.mihomet.app.ui.design2.D2Panel
import org.mihomet.app.ui.design2.D2ScreenTitle
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType
import java.util.*

/** Hard cap for text read from a picked local profile file (matches subscription byte cap spirit). */
private const val MAX_LOCAL_IMPORT_BYTES = SubscriptionRepository.MAX_SUBSCRIPTION_BYTES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onEditProfileYaml: (Long) -> Unit,
    viewModel: ProfilesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    // Profile whose override patch is being edited (P7)
    var patchDialogProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    // Subscription headers editor: profile id + decrypted "Name: Value" lines
    // (resolved on demand — the stored headers are encrypted at rest)
    var headersDialog by remember { mutableStateOf<HeadersDialogData?>(null) }
    var autoUpdateProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    // Subscription QR sharing: profile name + decrypted URL resolved on demand
    var qrDialogData by remember { mutableStateOf<ProfileQrData?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            viewModel.clearError()
            snackbarHostState.showSnackbar(msg, withDismissAction = true)
        }
    }
    LaunchedEffect(state.successMessage) {
        state.successMessage?.let { msg ->
            viewModel.clearSuccess()
            snackbarHostState.showSnackbar(msg)
        }
    }

    // External deep-link imports (happ://add, clash://install-config, v2rayng://,
    // sing-box://, incy://, share targets) are collected by the ViewModel and
    // parked in state.pendingImport — the dialog below gates the actual import
    // behind an explicit user confirmation (audit P0 §1.1).

    // Clipboard import shared by the header action and the empty state.
    val importFromClipboard: () -> Unit = {
        val clip = clipboardManager.getText()?.text ?: ""
        if (clip.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.clipboard_empty)) }
        } else {
            AppHaptics.perform(context, HapticType.CLICK, view)
            viewModel.importFromClipboard(
                clip,
                onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                onError = { err -> scope.launch { snackbarHostState.showSnackbar(err) } }
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { ExpressiveSnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!state.importInProgress) {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        showAddDialog = true
                    }
                },
                shape = D2.Container,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profiles_add_title))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Header: big display title + round tonal import action ────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .padding(horizontal = D2.ScreenH)
            ) {
                D2ScreenTitle(
                    text = stringResource(R.string.nav_profiles),
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.profiles.isNotEmpty()) {
                        CountBadge(count = state.profiles.size)
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    D2IconAction(
                        icon = Icons.Default.ContentPaste,
                        contentDescription = stringResource(R.string.profiles_import),
                        onClick = importFromClipboard
                    )
                }
            }

            if (state.importInProgress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                Text(
                    stringResource(R.string.profiles_importing),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = D2.ScreenH, vertical = 8.dp)
                )
            }

            if (state.profiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    D2Panel(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            Surface(
                                shape = D2.Inner,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Bookmarks,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            Text(
                                text = stringResource(R.string.profiles_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = stringResource(R.string.profiles_empty_subtitle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )

                            Button(
                                onClick = importFromClipboard,
                                shape = D2.Container
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.profiles_import_clipboard), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(
                        start = D2.ScreenH,
                        end = D2.ScreenH,
                        top = 14.dp,
                        bottom = 16.dp
                    )
                ) {
                    items(state.profiles, key = { it.id }) { profile ->
                    ExpressiveProfileCard(
                        profile = profile,
                        onSelect = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.selectProfile(profile.id)
                        },
                        onEditYaml = { onEditProfileYaml(profile.id) },
                        onEditPatch = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            patchDialogProfile = profile
                        },
                        onEditHeaders = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            scope.launch {
                                headersDialog = HeadersDialogData(
                                    profileId = profile.id,
                                    initialText = viewModel.getHeadersText(profile.id)
                                )
                            }
                        },
                        onEditInterval = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            autoUpdateProfile = profile
                        },
                        onShowQr = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            scope.launch {
                                val url = viewModel.getProfileUrl(profile.id)
                                if (url != null) {
                                    qrDialogData = ProfileQrData(name = profile.name, url = url)
                                }
                                // null on a decrypt failure — the explanation arrives
                                // through state.errorMessage as a snackbar.
                            }
                        },
                        onReport = { viewModel.showReport(ConversionReport.fromJson(profile.conversionReport)) },
                        onUpdate = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.updateProfile(profile)
                        },
                        onDelete = {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            viewModel.deleteProfile(profile)
                        }
                    )
                }
            }
            }
        }

        if (showAddDialog) {
            AddProfileDialog(
                onDismiss = { showAddDialog = false },
                onAddRemote = { name, url, headers ->
                    viewModel.addRemoteProfile(name, url, headers)
                    showAddDialog = false
                },
                onAddBlank = { name ->
                    viewModel.addBlankProfile(name)
                    showAddDialog = false
                },
                onAddLocal = { name, content ->
                    viewModel.addLocalProfile(name, content)
                    showAddDialog = false
                }
            )
        }

        autoUpdateProfile?.let { targetProfile ->
            val intervals = listOf(
                0 to stringResource(R.string.profile_auto_update_disabled),
                1 to stringResource(R.string.profile_auto_update_1h),
                6 to stringResource(R.string.profile_auto_update_6h),
                12 to stringResource(R.string.profile_auto_update_12h),
                24 to stringResource(R.string.profile_auto_update_24h)
            )
            AlertDialog(
                onDismissRequest = { autoUpdateProfile = null },
                title = { Text(stringResource(R.string.profile_auto_update_title), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.profile_auto_update_note), style = MaterialTheme.typography.bodySmall)
                        intervals.forEach { (hours, label) ->
                            val isSelected = targetProfile.updateIntervalHours == hours
                            Surface(
                                shape = ExpressiveShapes.Small,
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateProfileInterval(targetProfile, hours)
                                        autoUpdateProfile = null
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            viewModel.updateProfileInterval(targetProfile, hours)
                                            autoUpdateProfile = null
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { autoUpdateProfile = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        patchDialogProfile?.let { profile ->
            OverridePatchDialog(
                initialPatch = profile.overridePatch,
                onDismiss = { patchDialogProfile = null },
                onSave = { patch ->
                    // Validation failures keep the dialog open (input preserved);
                    // the error snackbar comes through state.errorMessage.
                    val result = viewModel.saveOverridePatch(profile.id, patch)
                    if (result.isSuccess) {
                        patchDialogProfile = null
                    }
                }
            )
        }

        headersDialog?.let { data ->
            SubscriptionHeadersDialog(
                initialHeaders = data.initialText,
                onDismiss = { headersDialog = null },
                onSave = { headers ->
                    // Validation failures keep the dialog open (input preserved);
                    // the error snackbar comes through state.errorMessage.
                    val result = viewModel.saveHeaders(data.profileId, headers)
                    if (result.isSuccess) {
                        headersDialog = null
                    }
                }
            )
        }

        qrDialogData?.let { qr ->
            SubscriptionQrDialog(
                title = qr.name,
                url = qr.url,
                onDismiss = { qrDialogData = null }
            )
        }

        // Audit P0 §1.1: every external import waits for explicit confirmation.
        state.conversionDiagnostic?.takeIf { state.behaviorConsent == null }?.let { diagnostic ->
            ConversionReportSheet(diagnostic, viewModel::dismissDiagnostic)
        }
        state.pendingImport?.let { pending ->
            DeepLinkImportDialog(
                pending = pending,
                onConfirm = { viewModel.confirmPendingImport() },
                onDismiss = { viewModel.cancelPendingImport() }
            )
        }

        // Audit P0 §1.1: panel behavior headers apply only after explicit consent.
        state.behaviorConsent?.let { consent ->
            BehaviorConsentDialog(
                consent = consent,
                onApply = { viewModel.applyBehaviorConsent() },
                onDecline = { viewModel.declineBehaviorConsent() }
            )
        }
    }
}

/** Payload of the subscription QR dialog: profile display name + decrypted URL. */
private data class ProfileQrData(val name: String, val url: String)

/** Payload of the per-profile subscription headers dialog. */
private data class HeadersDialogData(val profileId: Long, val initialText: String)

/**
 * Confirmation gate for external imports (audit P0 §1.1): shows the payload, the
 * best-effort source (referrer / originating package) and the profile name the
 * import would create. Nothing has been fetched or stored yet — the download and
 * conversion happen only after the user confirms.
 */
@Composable
fun DeepLinkImportDialog(
    pending: PendingImport,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.deeplink_import_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.deeplink_import_payload),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = pending.text.take(2048),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ExpressiveShapes.Small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(8.dp)
                )
                Text(
                    text = if (pending.source.isNullOrBlank()) {
                        stringResource(R.string.deeplink_import_source_unknown)
                    } else {
                        stringResource(R.string.deeplink_import_source, pending.source)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.deeplink_import_name, pending.previewName),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = ExpressiveShapes.Small) {
                Text(stringResource(R.string.action_add), fontWeight = FontWeight.Bold)
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
 * One-time consent dialog for panel behavior headers (audit P0 §1.1): lists
 * EXACTLY what the panel wants to change (active profile, per-app routing,
 * root/VPN mode). Nothing — especially the root-mode switch — is applied unless
 * the user presses Apply.
 */
@Composable
fun BehaviorConsentDialog(
    consent: BehaviorConsent,
    onApply: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.consent_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.consent_description),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (consent.autoConnect) {
                    ConsentBullet(
                        text = stringResource(R.string.consent_autoconnect, consent.profileName)
                    )
                }
                when (consent.perAppWhitelist) {
                    true -> ConsentBullet(
                        text = stringResource(R.string.consent_perapp_whitelist, consent.perAppUids.size)
                    )
                    false -> ConsentBullet(
                        text = stringResource(R.string.consent_perapp_blacklist, consent.perAppUids.size)
                    )
                    null -> {}
                }
                when (consent.tunModeHint) {
                    true -> ConsentBullet(text = stringResource(R.string.consent_tun_vpn))
                    false -> ConsentBullet(text = stringResource(R.string.consent_tun_root))
                    null -> {}
                }
            }
        },
        confirmButton = {
            Button(onClick = onApply, shape = ExpressiveShapes.Small) {
                Text(stringResource(R.string.consent_apply), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ConsentBullet(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ExpressiveProfileCard(
    profile: ProfileEntity, onSelect: () -> Unit, onEditYaml: () -> Unit, onEditPatch: () -> Unit,
    onEditHeaders: () -> Unit, onEditInterval: () -> Unit = {}, onShowQr: () -> Unit,
    onUpdate: () -> Unit, onDelete: () -> Unit, onReport: () -> Unit = {}
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dates = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    val expiry = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    val report = remember(profile.conversionReport) { ConversionReport.fromJson(profile.conversionReport) }
    Surface(onClick = onSelect, shape = ExpressiveShapes.Large,
        color = if (profile.isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (profile.isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = profile.isSelected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (profile.type == ProfileType.REMOTE) R.string.profile_type_remote else R.string.profile_type_local),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.cd_menu)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (profile.type == ProfileType.REMOTE) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.profiles_update_now)) }, onClick = { menu = false; onUpdate() }, leadingIcon = { Icon(Icons.Default.Sync, null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.profile_headers_menu)) }, onClick = { menu = false; onEditHeaders() }, leadingIcon = { Icon(Icons.Default.Badge, null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.profile_auto_update_menu)) }, onClick = { menu = false; onEditInterval() }, leadingIcon = { Icon(Icons.Default.Schedule, null) })
                            if (profile.url.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.profile_show_qr)) }, onClick = { menu = false; onShowQr() }, leadingIcon = { Icon(Icons.Default.QrCode2, null) })
                        }
                        if (report != null) DropdownMenuItem(text = { Text(stringResource(R.string.conversion_report_title)) }, onClick = { menu = false; onReport() }, leadingIcon = { Icon(Icons.Default.Info, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.profiles_edit_yaml)) }, onClick = { menu = false; onEditYaml() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.profile_patch_menu)) }, onClick = { menu = false; onEditPatch() }, leadingIcon = { Icon(Icons.Default.Tune, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                    }
                }
            }
            Text(dates.format(Date(profile.lastUpdated)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (report != null && report.issues.isNotEmpty()) {
                AssistChip(onClick = onReport, label = { Text(stringResource(R.string.conversion_report_warning_count, report.issues.size)) },
                    leadingIcon = { Icon(Icons.Default.Info, null, Modifier.size(18.dp)) })
            }
            val used = (profile.uploadUsed.toDouble() + profile.downloadUsed.toDouble()).coerceAtLeast(0.0)
            if (profile.trafficTotal > 0) {
                val fraction = (used / profile.trafficTotal).coerceIn(0.0, 1.0).toFloat()
                Text(stringResource(R.string.profile_quota_used, formatTrafficBytes(used.toLong()), formatTrafficBytes(profile.trafficTotal), (fraction * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else if (used > 0) Text(stringResource(R.string.profile_quota_used_only, formatTrafficBytes(used.toLong())), style = MaterialTheme.typography.bodyMedium)
            if (profile.expireAt > 0) Text(stringResource(R.string.profile_expires_at, expiry.format(Date(profile.expireAt))),
                style = MaterialTheme.typography.bodyMedium,
                color = if (profile.expireAt < System.currentTimeMillis()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.profile_delete_title)) },
        text = { Text(stringResource(R.string.profile_delete_message, profile.name)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
fun SubscriptionHeadersDialog(
    initialHeaders: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var headers by remember { mutableStateOf(initialHeaders) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_headers_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.profile_headers_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = headers,
                    onValueChange = { headers = it },
                    placeholder = { Text(stringResource(R.string.profiles_headers_placeholder)) },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    minLines = 3,
                    maxLines = 8,
                    shape = ExpressiveShapes.Small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(headers) },
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

@Composable
fun OverridePatchDialog(
    initialPatch: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var patch by remember { mutableStateOf(initialPatch) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_patch_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.profile_patch_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = patch,
                    onValueChange = { patch = it },
                    placeholder = { Text("keep-alive-interval: 15") },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    minLines = 4,
                    maxLines = 10,
                    shape = ExpressiveShapes.Small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(patch) },
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
 * Share-by-QR dialog for a remote profile: renders the decrypted subscription
 * URL as a scannable QR code (white background, dark modules) and shows the
 * profile name plus the URL itself underneath.
 */
@Composable
fun SubscriptionQrDialog(
    title: String,
    url: String,
    onDismiss: () -> Unit
) {
    // Generated once per URL; a payload that does not fit into a QR symbol
    // falls back to the error text (the URL is shown below regardless).
    val bitmap = remember(url) {
        runCatching {
            SubscriptionQr.toBitmap(SubscriptionQr.encodeBitMatrix(url))
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_dialog_title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(ExpressiveShapes.Small)
                            .background(Color.White)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.profiles_unknown_error),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = url,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileDialog(
    onDismiss: () -> Unit,
    onAddRemote: (String, String, String) -> Unit,
    onAddBlank: (String) -> Unit,
    onAddLocal: (String, String) -> Unit = { _, _ -> }
) {
    var isRemote by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var customHeaders by remember { mutableStateOf("") }
    // Picked-file state for the local branch (WireGuard .conf, share links, JSON, YAML)
    var localContent by remember { mutableStateOf<String?>(null) }
    var localFileName by remember { mutableStateOf("") }
    var pickFailed by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current

    val fileScope = rememberCoroutineScope()
    var readingFile by remember { mutableStateOf(false) }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) fileScope.launch {
            readingFile = true
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_LOCAL_IMPORT_BYTES) { "Local profile exceeds the import size limit" }
                            bytes.write(buffer, 0, read)
                        }
                        bytes.toString("UTF-8")
                    }.orEmpty()
                    val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }.orEmpty()
                    text to fileName
                }.getOrNull()
            }
            readingFile = false
            pickFailed = picked == null || picked.first.isBlank()
            if (!pickFailed && picked != null) {
                localContent = picked.first
                localFileName = picked.second
                if (name.isBlank()) name = picked.second.substringBeforeLast('.')
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = D2.Hero,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
                Text(
                    text = stringResource(R.string.profiles_add_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = isRemote,
                        onClick = { isRemote = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) {
                        Text(stringResource(R.string.profiles_type_url), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = !isRemote,
                        onClick = { isRemote = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) {
                        Text(stringResource(R.string.profiles_type_local_file), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profiles_name_label)) },
                    placeholder = { Text(stringResource(R.string.profiles_name_placeholder)) },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isRemote) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.profiles_url_label)) },
                        placeholder = { Text("https://...") },
                        shape = ExpressiveShapes.Small,
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text?.trim() ?: ""
                                if (clip.isNotBlank()) {
                                    url = clip
                                    if (name.isBlank()) {
                                        val host = runCatching { java.net.URI(clip).host }.getOrNull()?.removePrefix("www.")
                                        if (!host.isNullOrBlank()) {
                                            name = context.getString(R.string.profile_auto_name, host)
                                        }
                                    }
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.cd_paste_clipboard))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customHeaders,
                        onValueChange = { customHeaders = it },
                        label = { Text(stringResource(R.string.profiles_headers_label)) },
                        placeholder = { Text(stringResource(R.string.profiles_headers_placeholder)) },
                        shape = ExpressiveShapes.Small,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5,
                        trailingIcon = {
                            IconButton(onClick = {
                                val clip = clipboardManager.getText()?.text?.trim() ?: ""
                                if (clip.isNotBlank()) {
                                    customHeaders = clip
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.cd_paste_headers))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Pick a file from disk: .conf (WireGuard/AmneziaWG), share-link
                    // list, Xray/sing-box JSON or Clash YAML — content is converted
                    // on import (addLocalProfile), not stored raw.
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Small)
                            .clickable {
                                AppHaptics.perform(context, HapticType.CLICK, view)
                                pickFailed = false
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                        shape = ExpressiveShapes.Small,
                        color = if (localContent != null) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        border = BorderStroke(
                            1.dp,
                            if (localContent != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = localFileName.ifBlank {
                                        stringResource(R.string.profiles_pick_file)
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (localContent != null) {
                                    val loaded = localContent.orEmpty()
                                    Text(
                                        text = stringResource(
                                            R.string.profiles_file_loaded,
                                            loaded.length
                                        ),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.profiles_pick_file_desc),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (pickFailed) {
                        Text(
                            text = stringResource(R.string.profiles_pick_failed),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        if (isRemote) onAddRemote(name, url, customHeaders.ifBlank { "{}" })
                        else localContent?.let { onAddLocal(name, it) } ?: onAddBlank(name)
                    },
                    enabled = !readingFile && if (isRemote) url.isNotBlank() else localContent != null || name.isNotBlank(),
                    shape = ExpressiveShapes.Small
                ) {
                    Text(stringResource(R.string.action_add), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
