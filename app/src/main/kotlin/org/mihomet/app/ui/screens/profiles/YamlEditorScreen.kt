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
                        visualTransformation = run {
                            val keyColor = MaterialTheme.colorScheme.primary
                            val commentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            val literalColor = MaterialTheme.colorScheme.secondary
                            val urlColor = MaterialTheme.colorScheme.tertiary
                            androidx.compose.ui.text.input.VisualTransformation { field ->
                                androidx.compose.ui.text.input.TransformedText(
                                    highlightYaml(field.text, keyColor, commentColor, literalColor, urlColor),
                                    androidx.compose.ui.text.input.OffsetMapping.Identity
                                )
                            }
                        },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private val YAML_KEY_REGEX = Regex("^([ \\t-]*)([A-Za-z0-9_.-]+)(:)(\\s|$)")
private val YAML_COMMENT_REGEX = Regex("#\\s?.*$")
private val YAML_LITERAL_REGEX = Regex("(?<=^|[ \\t:\\-])(true|false|null|~|-?\\d+(\\.\\d+)?)(?=[ \\t]|$)")
private val YAML_URL_REGEX = Regex("https?://\\S+")

/**
 * Lightweight YAML coloring (keys / comments / booleans & numbers / URLs) applied as a
 * VisualTransformation with an identity offset mapping — cursor and selection positions
 * are preserved. Very large documents fall back to plain text to keep typing snappy.
 */
private fun highlightYaml(
    text: String,
    keyColor: androidx.compose.ui.graphics.Color,
    commentColor: androidx.compose.ui.graphics.Color,
    literalColor: androidx.compose.ui.graphics.Color,
    urlColor: androidx.compose.ui.graphics.Color
): androidx.compose.ui.text.AnnotatedString {
    if (text.length > 256_000) return androidx.compose.ui.text.AnnotatedString(text)

    val builder = androidx.compose.ui.text.AnnotatedString.Builder(text.length)
    var offset = 0
    text.lineSequence().forEach { line ->
        val lineEnd = offset + line.length
        val commentStart = YAML_COMMENT_REGEX.find(line)?.range?.first
        val codeEnd = commentStart ?: line.length

        YAML_KEY_REGEX.find(line)?.let { match ->
            val keyRange = match.groups[2]!!.range
            if (keyRange.first < codeEnd) {
                builder.addStyle(
                    androidx.compose.ui.text.SpanStyle(color = keyColor, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    offset + keyRange.first,
                    offset + keyRange.last + 1
                )
            }
        }
        YAML_LITERAL_REGEX.findAll(line.substring(0, codeEnd)).forEach { match ->
            builder.addStyle(
                androidx.compose.ui.text.SpanStyle(color = literalColor, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                offset + match.range.first,
                offset + match.range.last + 1
            )
        }
        YAML_URL_REGEX.findAll(line.substring(0, codeEnd)).forEach { match ->
            builder.addStyle(
                androidx.compose.ui.text.SpanStyle(color = urlColor),
                offset + match.range.first,
                offset + match.range.last + 1
            )
        }
        if (commentStart != null) {
            builder.addStyle(
                androidx.compose.ui.text.SpanStyle(color = commentColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                offset + commentStart,
                lineEnd
            )
        }
        builder.append("\n")
        offset = lineEnd + 1
    }
    return builder.toAnnotatedString()
}
