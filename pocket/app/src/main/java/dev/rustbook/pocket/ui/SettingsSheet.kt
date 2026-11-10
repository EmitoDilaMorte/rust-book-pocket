package dev.rustbook.pocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rustbook.pocket.BuildConfig
import dev.rustbook.pocket.R
import dev.rustbook.pocket.data.ReaderSettings
import dev.rustbook.pocket.runner.ExecutionMode
import dev.rustbook.pocket.update.UpdateState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: ReaderSettings,
    updateState: UpdateState,
    onDismiss: () -> Unit,
    onExecutionModeChange: (ExecutionMode) -> Unit,
    onPlaygroundUrlChange: (String) -> Unit,
    onTextZoomChange: (Int) -> Unit,
    onClearProgress: () -> Unit,
    onCheckForUpdates: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current

    var confirmClear by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            SectionTitle(stringResource(R.string.settings_section_execution))

            ExecutionModeRow(
                label = stringResource(R.string.settings_mode_auto),
                summary = stringResource(R.string.settings_mode_auto_summary),
                selected = settings.executionMode == ExecutionMode.AUTO,
                enabled = true,
                onSelect = { onExecutionModeChange(ExecutionMode.AUTO) },
            )
            ExecutionModeRow(
                label = stringResource(R.string.settings_mode_remote),
                summary = stringResource(R.string.settings_mode_remote_summary),
                selected = settings.executionMode == ExecutionMode.REMOTE,
                enabled = true,
                onSelect = { onExecutionModeChange(ExecutionMode.REMOTE) },
            )
            ExecutionModeRow(
                label = stringResource(R.string.settings_mode_local),
                summary = stringResource(R.string.settings_mode_local_summary),
                selected = settings.executionMode == ExecutionMode.LOCAL,
                enabled = false,
                onSelect = { onExecutionModeChange(ExecutionMode.LOCAL) },
            )

            PlaygroundUrlField(
                url = settings.playgroundUrl,
                onUrlChange = onPlaygroundUrlChange,
            )

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_section_reading))

            Text(
                text = stringResource(R.string.settings_text_size) + ": " +
                    stringResource(R.string.settings_text_size_value, settings.textZoom),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.textZoom.toFloat(),
                onValueChange = { onTextZoomChange(it.toInt()) },
                valueRange = ReaderSettings.MIN_TEXT_ZOOM.toFloat()..ReaderSettings.MAX_TEXT_ZOOM.toFloat(),
                steps = 6,
            )

            Text(
                text = stringResource(R.string.settings_clear_progress_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { confirmClear = true }) {
                Text(stringResource(R.string.settings_clear_progress))
            }

            HorizontalDivider()

            SectionTitle(stringResource(R.string.settings_section_about))

            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onCheckForUpdates,
                    enabled = updateState != UpdateState.Checking,
                ) {
                    Text(stringResource(R.string.settings_check_updates))
                }
            }

            UpdateStatus(updateState) { url -> uriHandler.openUri(url) }

            TextButton(onClick = { showLicenses = true }) {
                Text(stringResource(R.string.settings_licenses))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_progress)) },
            text = { Text(stringResource(R.string.settings_clear_progress_summary)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearProgress()
                    },
                ) {
                    Text(stringResource(R.string.settings_clear_progress))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.settings_close))
                }
            },
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(stringResource(R.string.licenses_title)) },
            text = {
                Text(
                    text = stringResource(R.string.licenses_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) {
                    Text(stringResource(R.string.licenses_close))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ExecutionModeRow(
    label: String,
    summary: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = if (enabled) onSelect else null, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaygroundUrlField(url: String, onUrlChange: (String) -> Unit) {
    var text by remember(url) { mutableStateOf(url) }

    LaunchedEffect(text) {
        if (text != url) {
            delay(600)
            onUrlChange(text)
        }
    }

    Column {
        Text(
            text = stringResource(R.string.settings_playground_url),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text(stringResource(R.string.settings_playground_url_hint)) },
        )
        if (text.startsWith("http://")) {
            Text(
                text = stringResource(R.string.settings_playground_url_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun UpdateStatus(state: UpdateState, onOpen: (String) -> Unit) {
    when (state) {
        UpdateState.Idle -> Unit

        UpdateState.Checking -> Text(
            text = stringResource(R.string.settings_check_updates) + "…",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
        )

        UpdateState.UpToDate -> Text(
            text = stringResource(R.string.settings_update_current),
            style = MaterialTheme.typography.bodySmall,
        )

        UpdateState.Unavailable -> Text(
            text = stringResource(R.string.settings_updates_unavailable),
            style = MaterialTheme.typography.bodySmall,
        )

        UpdateState.Error -> Text(
            text = stringResource(R.string.settings_update_error),
            style = MaterialTheme.typography.bodySmall,
        )

        is UpdateState.Available -> Column {
            Text(
                text = stringResource(R.string.settings_update_available, state.version),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.url.isNotBlank()) {
                Button(onClick = { onOpen(state.url) }) {
                    Text(stringResource(R.string.settings_update_open))
                }
            }
        }
    }
}
