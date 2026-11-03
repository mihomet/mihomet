package org.mihomet.app.ui.screens.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import org.mihomet.app.ui.components.accentContentColor
import org.mihomet.app.ui.theme.AccentTealDeep
import org.mihomet.app.ui.theme.AppHaptics
import org.mihomet.app.ui.theme.ExpressiveShapes
import org.mihomet.app.ui.theme.HapticType
import org.mihomet.app.ui.theme.SecondaryTeal

/** "Appearance" card: dynamic colors, dark theme, AMOLED, per-app language. */
@Composable
internal fun AppearanceSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onShowLanguageDialog: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    SettingsSectionTitle(stringResource(R.string.settings_appearance))
    Spacer(modifier = Modifier.height(6.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ExpressiveShapes.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExpressiveSwitchRow(
                icon = Icons.Default.Palette,
                iconBg = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                title = stringResource(R.string.settings_dynamic_colors),
                subtitle = stringResource(R.string.settings_dynamic_colors_desc),
                checked = settings.dynamicColor,
                onCheckedChange = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.updateTheme(it, settings.darkTheme, settings.amoledBlack)
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            ExpressiveSwitchRow(
                icon = Icons.Default.DarkMode,
                iconBg = SecondaryTeal.copy(alpha = 0.16f),
                iconTint = accentContentColor(SecondaryTeal, AccentTealDeep),
                title = stringResource(R.string.settings_dark_theme),
                subtitle = stringResource(R.string.settings_dark_theme_desc),
                checked = settings.darkTheme,
                onCheckedChange = {
                    AppHaptics.perform(context, HapticType.CLICK, view)
                    viewModel.updateTheme(settings.dynamicColor, it, settings.amoledBlack)
                }
            )

            if (settings.darkTheme) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                ExpressiveSwitchRow(
                    icon = Icons.Default.Brightness2,
                    iconBg = MaterialTheme.colorScheme.surfaceContainerHighest,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = stringResource(R.string.settings_amoled),
                    subtitle = stringResource(R.string.settings_amoled_desc),
                    checked = settings.amoledBlack,
                    onCheckedChange = {
                        AppHaptics.perform(context, HapticType.CLICK, view)
                        viewModel.updateTheme(settings.dynamicColor, settings.darkTheme, it)
                    }
                )
            }

            // Per-app language (platform API, Android 13+; hidden below)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ExpressiveShapes.Small)
                        .clickable {
                            AppHaptics.perform(context, HapticType.CLICK, view)
                            onShowLanguageDialog()
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = ExpressiveShapes.Small,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                stringResource(R.string.settings_language),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                stringResource(R.string.settings_language_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** One selectable row inside [LanguageDialog]. */
@Composable
private fun LanguageOptionRow(
    label: String,
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
            Text(
                text = label,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
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

/**
 * Per-app language picker (Android 13+ platform API; the entry row is hidden
 * below API 33). Applies en/ru/system through [LocaleManager] — the system
 * recreates activities automatically.
 */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val currentTags = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(LocaleManager::class.java)
                    .applicationLocales.toLanguageTags()
            }.getOrDefault("")
        } else ""
    }
    val selected = when {
        currentTags.isBlank() -> "system"
        currentTags.startsWith("ru") -> "ru"
        else -> "en"
    }

    fun apply(tags: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = if (tags.isBlank()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tags)
            }
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_dialog_title), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguageOptionRow(
                    label = stringResource(R.string.language_system),
                    isSelected = selected == "system",
                    onSelect = { apply("") }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_english),
                    isSelected = selected == "en",
                    onSelect = { apply("en") }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_russian),
                    isSelected = selected == "ru",
                    onSelect = { apply("ru") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
