package org.mihomet.app.ui.screens.profiles

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
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.ui.components.ExpressiveSnackbarHost
import org.mihomet.app.ui.theme.AccentOrange
import org.mihomet.app.ui.theme.AccentRed
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType
import org.mihomet.app.ui.theme.PrimaryViolet
import org.mihomet.app.ui.theme.SecondaryTeal
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onEditProfileYaml: (Long) -> Unit,
    viewModel: ProfilesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    // Profile whose override patch is being edited (P7)
    var patchDialogProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    // Subscription QR sharing: profile name + decrypted URL resolved on demand
    var qrDialogData by remember { mutableStateOf<ProfileQrData?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it, withDismissAction = true)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccess()
        }
    }

    // One-tap import deep links (happ://add, clash://install-config, v2rayng://, sing-box://,
    // incy://) and "Share → Mihomet" payloads arrive here through DeepLinkBus
    LaunchedEffect(Unit) {
        org.mihomet.app.core.DeepLinkBus.flow.collect { text ->
            if (!text.isNullOrBlank()) {
                org.mihomet.app.core.DeepLinkBus.consume()
                viewModel.importFromClipboard(
                    text = text,
                    onSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    onError = { err -> scope.launch { snackbarHostState.showSnackbar(err, withDismissAction = true) } }
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { ExpressiveSnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.nav_profiles),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                        if (state.profiles.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = "${state.profiles.size}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(CircleShape)
                            .clickable {
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
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.profiles_import),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    showAddDialog = true
                },
                shape = ExpressiveShapes.Medium,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_profiles_add), modifier = Modifier.size(24.dp))
            }
        }
    ) { innerPadding ->
        if (state.profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = ExpressiveShapes.Large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(28.dp)
                    ) {
                        Surface(
                            shape = ExpressiveShapes.Medium,
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
                            onClick = {
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
                            },
                            shape = ExpressiveShapes.Medium
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
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 100.dp)
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

        qrDialogData?.let { qr ->
            SubscriptionQrDialog(
                title = qr.name,
                url = qr.url,
                onDismiss = { qrDialogData = null }
            )
        }
    }
}

/** Payload of the subscription QR dialog: profile display name + decrypted URL. */
private data class ProfileQrData(val name: String, val url: String)

@Composable
fun ExpressiveProfileCard(
    profile: ProfileEntity,
    onSelect: () -> Unit,
    onEditYaml: () -> Unit,
    onEditPatch: () -> Unit,
    onShowQr: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val expireDateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.Large)
            .clickable(onClick = onSelect),
        shape = ExpressiveShapes.Large,
        color = if (profile.isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (profile.isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Type Squircle Icon
                    Surface(
                        shape = ExpressiveShapes.Small,
                        color = if (profile.isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (profile.type == ProfileType.REMOTE) Icons.Default.CloudDownload else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (profile.isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = if (profile.type == ProfileType.REMOTE) SecondaryTeal.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = if (profile.type == ProfileType.REMOTE) stringResource(R.string.profile_type_remote) else stringResource(R.string.profile_type_local),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (profile.type == ProfileType.REMOTE) SecondaryTeal else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Text(
                                text = dateFormat.format(Date(profile.lastUpdated)),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // Selection Radio Pill & Menu
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile.isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(22.dp)
                        ) {}
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_menu), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (profile.type == ProfileType.REMOTE) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profiles_update_now)) },
                                    onClick = {
                                        showMenu = false
                                        onUpdate()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) }
                                )
                                // QR sharing only makes sense for a subscription URL
                                // (remote profile with a non-blank stored url).
                                if (profile.url.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.profile_show_qr)) },
                                        onClick = {
                                            showMenu = false
                                            onShowQr()
                                        },
                                        leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null) }
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_edit_yaml)) },
                                onClick = {
                                    showMenu = false
                                    onEditYaml()
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profile_patch_menu)) },
                                onClick = {
                                    showMenu = false
                                    onEditPatch()
                                },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                }
            }

            // Subscription quota & expiration (Subscription-Userinfo header).
            // The panel may omit the total (trafficTotal == 0) while still sending
            // upload/download usage and the expire date — show whatever is known.
            val usedTraffic = profile.uploadUsed + profile.downloadUsed
            val hasQuotaTotal = profile.trafficTotal > 0
            val hasUsage = usedTraffic > 0
            val hasExpireDate = profile.expireAt > 0
            if (hasQuotaTotal || hasUsage || hasExpireDate) {
                val now = System.currentTimeMillis()
                val usedFraction = if (hasQuotaTotal) {
                    (usedTraffic.toDouble() / profile.trafficTotal).coerceIn(0.0, 1.0).toFloat()
                } else 0f
                val usedPercent = if (hasQuotaTotal) (usedTraffic * 100 / profile.trafficTotal).toInt() else 0
                val expireBadgeColor = when {
                    profile.expireAt <= 0L -> null
                    profile.expireAt <= now -> AccentRed
                    profile.expireAt - now <= TimeUnit.DAYS.toMillis(14) -> AccentOrange
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasQuotaTotal) {
                        Text(
                            text = stringResource(
                                R.string.profile_quota_used,
                                formatTrafficBytes(usedTraffic),
                                formatTrafficBytes(profile.trafficTotal),
                                usedPercent
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    } else if (hasUsage) {
                        // No total from the panel — show bare usage without a progress bar
                        Text(
                            text = stringResource(
                                R.string.profile_quota_used_only,
                                formatTrafficBytes(usedTraffic)
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (expireBadgeColor != null) {
                        if (hasQuotaTotal || hasUsage) {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Surface(
                            shape = CircleShape,
                            color = expireBadgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.profile_expires_at,
                                    expireDateFormat.format(Date(profile.expireAt))
                                ),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = expireBadgeColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (hasQuotaTotal) {
                    Spacer(modifier = Modifier.height(6.dp))

                    // Thin branded-violet usage bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(usedFraction)
                                .clip(CircleShape)
                                .background(PrimaryViolet)
                        )
                    }
                }
            }
        }
    }
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

@Composable
fun AddProfileDialog(
    onDismiss: () -> Unit,
    onAddRemote: (String, String, String) -> Unit,
    onAddBlank: (String) -> Unit
) {
    var isRemote by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var customHeaders by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_add_title), fontWeight = FontWeight.Black) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
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
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        if (isRemote && url.isNotBlank()) {
                            val headers = customHeaders.ifBlank { "{}" }
                            onAddRemote(name, url, headers)
                        } else if (!isRemote) {
                            onAddBlank(name)
                        }
                    }
                },
                shape = ExpressiveShapes.Small
            ) {
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
