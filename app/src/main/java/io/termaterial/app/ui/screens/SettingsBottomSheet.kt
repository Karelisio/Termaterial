package io.termaterial.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.termaterial.app.R
import io.termaterial.app.settings.AppSettings
import io.termaterial.app.settings.MonospaceFont
import io.termaterial.app.update.UpdateUiState
import io.termaterial.app.ui.theme.TerminalPalette

/**
 * Settings screen (Step 4): monospace font, text size, terminal color theme, dynamic color
 * on/off - as a Material 3 [ModalBottomSheet], per the Step 3 UI requirement - plus a way to
 * reinstall the Termux environment if it ends up broken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    settings: AppSettings,
    onMonospaceFontChange: (MonospaceFont) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onTerminalPaletteChange: (TerminalPalette) -> Unit,
    onUseDynamicColorChange: (Boolean) -> Unit,
    prootAvailable: Boolean,
    onUseProotChange: (Boolean) -> Unit,
    onReinstallEnvironment: () -> Unit,
    currentVersionName: String,
    updateState: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onShowUpdate: () -> Unit,
    onAutoCheckUpdatesChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmReinstall by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(id = R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            SettingsSectionTitle(stringResource(id = R.string.settings_font))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (font in MonospaceFont.entries) {
                    FilterChip(
                        selected = settings.monospaceFont == font,
                        onClick = { onMonospaceFontChange(font) },
                        label = { Text(font.label) },
                    )
                }
            }

            SettingsSectionTitle(stringResource(id = R.string.settings_font_size, settings.fontSizeSp))
            Slider(
                value = settings.fontSizeSp.toFloat(),
                onValueChange = { onFontSizeChange(it.toInt()) },
                valueRange = AppSettings.MIN_FONT_SIZE_SP.toFloat()..AppSettings.MAX_FONT_SIZE_SP.toFloat(),
                steps = AppSettings.MAX_FONT_SIZE_SP - AppSettings.MIN_FONT_SIZE_SP - 1,
            )

            SettingsSectionTitle(stringResource(id = R.string.settings_terminal_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (palette in TerminalPalette.entries) {
                    FilterChip(
                        selected = settings.terminalPalette == palette,
                        onClick = { onTerminalPaletteChange(palette) },
                        label = { Text(palette.label) },
                    )
                }
            }

            SettingsSectionTitle(stringResource(id = R.string.settings_dynamic_color))
            val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (dynamicColorAvailable) {
                        stringResource(id = R.string.settings_dynamic_color_description)
                    } else {
                        stringResource(id = R.string.settings_dynamic_color_unavailable)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Switch(
                    checked = settings.useDynamicColor && dynamicColorAvailable,
                    enabled = dynamicColorAvailable,
                    onCheckedChange = onUseDynamicColorChange,
                )
            }

            SettingsSectionTitle(stringResource(id = R.string.settings_environment))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(id = R.string.settings_proot),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (prootAvailable) {
                            stringResource(id = R.string.settings_proot_description)
                        } else {
                            stringResource(id = R.string.settings_proot_unavailable)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.useProot && prootAvailable,
                    enabled = prootAvailable,
                    onCheckedChange = onUseProotChange,
                )
            }
            OutlinedButton(
                onClick = { confirmReinstall = true },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(id = R.string.settings_reinstall))
            }

            SettingsSectionTitle(stringResource(id = R.string.settings_updates))
            UpdatesSection(
                currentVersionName = currentVersionName,
                state = updateState,
                autoCheck = settings.autoCheckUpdates,
                onCheck = onCheckForUpdates,
                onShowUpdate = onShowUpdate,
                onAutoCheckChange = onAutoCheckUpdatesChange,
            )
        }
    }

    if (confirmReinstall) {
        AlertDialog(
            onDismissRequest = { confirmReinstall = false },
            title = { Text(stringResource(id = R.string.settings_reinstall_confirm_title)) },
            text = { Text(stringResource(id = R.string.settings_reinstall_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReinstall = false
                    onReinstallEnvironment()
                }) {
                    Text(stringResource(id = R.string.settings_reinstall_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReinstall = false }) {
                    Text(stringResource(id = R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun UpdatesSection(
    currentVersionName: String,
    state: UpdateUiState,
    autoCheck: Boolean,
    onCheck: () -> Unit,
    onShowUpdate: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
) {
    Text(
        text = stringResource(id = R.string.settings_updates_installed, currentVersionName),
        style = MaterialTheme.typography.bodyLarge,
    )
    val offer = state.offer
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.checking -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(id = R.string.settings_updates_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            offer != null -> {
                Text(
                    text = stringResource(id = R.string.settings_updates_available, offer.latest.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onShowUpdate) { Text(stringResource(id = R.string.settings_updates_show)) }
            }
            state.checkError != null -> Text(
                text = state.checkError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            state.checked -> Text(
                text = stringResource(id = R.string.settings_updates_up_to_date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    OutlinedButton(
        onClick = onCheck,
        enabled = !state.checking && state.download == null,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(stringResource(id = R.string.settings_updates_check))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = R.string.settings_updates_auto),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Switch(checked = autoCheck, onCheckedChange = onAutoCheckChange)
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
