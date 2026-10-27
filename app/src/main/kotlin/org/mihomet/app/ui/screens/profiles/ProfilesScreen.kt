package org.mihomet.app.ui.screens.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType
import org.mihomet.app.ui.theme.SecondaryTeal
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onEditProfileYaml: (Long) -> Unit,
    viewModel: ProfilesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Подписки",
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
                                    scope.launch { snackbarHostState.showSnackbar("Буфер обмена пуст") }
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
                                text = "Импорт",
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
                Icon(Icons.Default.Add, contentDescription = "Добавить подписку", modifier = Modifier.size(24.dp))
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
                            text = "Нет добавленных подписок",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Импортируйте ссылку из буфера обмена или добавьте конфигурацию вручную.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )

                        Button(
                            onClick = {
                                val clip = clipboardManager.getText()?.text ?: ""
                                if (clip.isBlank()) {
                                    scope.launch { snackbarHostState.showSnackbar("Буфер обмена пуст") }
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
                            Text("Импорт из буфера", fontWeight = FontWeight.Bold)
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
    }
}

@Composable
fun ExpressiveProfileCard(
    profile: ProfileEntity,
    onSelect: () -> Unit,
    onEditYaml: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

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
                                    text = if (profile.type == ProfileType.REMOTE) "Онлайн" else "Локальный",
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
                            Icon(Icons.Default.MoreVert, contentDescription = "Меню", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (profile.type == ProfileType.REMOTE) {
                                DropdownMenuItem(
                                    text = { Text("Обновить сейчас") },
                                    onClick = {
                                        showMenu = false
                                        onUpdate()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Редактировать YAML") },
                                onClick = {
                                    showMenu = false
                                    onEditYaml()
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
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
        }
    }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить подписку", fontWeight = FontWeight.Black) },
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
                        Text("Ссылка (URL)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = !isRemote,
                        onClick = { isRemote = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) {
                        Text("Локальный файл", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название подписки") },
                    placeholder = { Text("Мой VPN") },
                    shape = ExpressiveShapes.Small,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isRemote) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Ссылка на подписку (URL)") },
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
                                            name = "Подписка $host"
                                        }
                                    }
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Вставить из буфера")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customHeaders,
                        onValueChange = { customHeaders = it },
                        label = { Text("Заголовки (JSON или Ключ: Значение)") },
                        placeholder = { Text("User-Agent: Happ/3.6.0\nx-hwid: 12345\n(или JSON)") },
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
                                Icon(Icons.Default.ContentPaste, contentDescription = "Вставить заголовки")
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
                Text("Добавить", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
