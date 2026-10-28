package org.mihomet.app.ui.screens.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.mihomet.app.R
import org.mihomet.app.ui.components.ExpressiveSnackbarHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YamlEditorScreen(
    profileId: Long,
    onNavigateBack: () -> Unit,
    viewModel: ProfilesViewModel = koinViewModel()
) {
    var yamlText by remember { mutableStateOf("") }
    var savedText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var showExitDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Dirty state: content differs from the last saved/loaded version
    val isDirty = yamlText != savedText

    LaunchedEffect(profileId) {
        val content = viewModel.getProfileContent(profileId)
        yamlText = content
        savedText = content
        isLoading = false
    }

    fun requestExit() {
        if (isDirty) showExitDialog = true else onNavigateBack()
    }

    fun saveAndExit() {
        showExitDialog = false
        scope.launch {
            viewModel.saveProfileContent(profileId, yamlText)
                .onSuccess {
                    onNavigateBack()
                }
                .onFailure {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.yaml_syntax_error, it.message ?: "")
                    )
                }
        }
    }

    // Intercept the system back while there are unsaved changes
    BackHandler(enabled = isDirty) {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.yaml_unsaved_changes_title)) },
            text = { Text(stringResource(R.string.yaml_unsaved_changes_message)) },
            confirmButton = {
                TextButton(onClick = { saveAndExit() }) {
                    Text(stringResource(R.string.yaml_save_and_exit))
                }
            },
            dismissButton = {
                TextButton(onClick = onNavigateBack) {
                    Text(stringResource(R.string.yaml_exit_without_saving))
                }
                TextButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(R.string.yaml_cancel))
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.yaml_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = { requestExit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                viewModel.saveProfileContent(profileId, yamlText)
                                    .onSuccess {
                                        savedText = yamlText
                                        snackbarHostState.showSnackbar(context.getString(R.string.yaml_saved))
                                    }
                                    .onFailure {
                                        snackbarHostState.showSnackbar(context.getString(R.string.yaml_syntax_error, it.message ?: ""))
                                    }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.cd_save))
                    }
                }
            )
        },
        snackbarHost = { ExpressiveSnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val linesCount = yamlText.lines().size.coerceAtLeast(1)
            val lineNumbers = (1..linesCount).joinToString("\n")

            // A single scroll state drives BOTH the gutter and the text area,
            // which keeps them pixel-perfectly synchronized.
            val editorScrollState = rememberScrollState()

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                // Line numbers gutter (scrolls with the shared state)
                Text(
                    text = lineNumbers,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        lineHeight = 20.sp
                    ),
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .verticalScroll(editorScrollState)
                )

                // Editable YAML text area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(editorScrollState)
                ) {
                    BasicTextField(
                        value = yamlText,
                        onValueChange = { yamlText = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
