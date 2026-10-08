package io.termaterial.app.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.termaterial.app.R
import io.termaterial.app.update.DownloadProgress
import io.termaterial.app.update.UpdateUiState

/**
 * Update available: the changes of every version since the installed one, then - once started -
 * the download progress and the hand-off to the system installer.
 */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    currentVersionName: String,
    onUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val offer = state.offer ?: return
    val busy = state.download != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !busy),
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(id = R.string.update_available_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(id = R.string.update_versions, offer.latest.versionName, currentVersionName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    offer.releases.forEachIndexed { index, release ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(text = release.versionName, style = MaterialTheme.typography.titleSmall)
                        if (release.changelog.isEmpty()) {
                            Text(
                                text = stringResource(id = R.string.update_no_changelog),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        for (change in release.changelog) {
                            Row(modifier = Modifier.padding(top = 2.dp)) {
                                Text(text = "•", modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
                                Text(text = change, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                val download = state.download
                when {
                    download != null -> DownloadProgressBar(download)
                    state.installing -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                        Text(
                            text = stringResource(id = R.string.update_installing),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    else -> {
                        state.updateError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                        Text(
                            text = stringResource(id = R.string.update_sessions_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                busy -> TextButton(onClick = onCancelDownload) { Text(stringResource(id = R.string.cancel)) }
                state.installing -> TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.close)) }
                else -> Button(onClick = onUpdate) {
                    Text(
                        stringResource(
                            id = if (state.updateError != null) R.string.update_retry else R.string.update_install,
                        ),
                    )
                }
            }
        },
        dismissButton = if (busy || state.installing) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.update_later)) } }
        },
    )
}

@Composable
private fun DownloadProgressBar(download: DownloadProgress) {
    val context = LocalContext.current
    val fraction = download.fraction
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
    val read = Formatter.formatShortFileSize(context, download.bytesRead)
    Text(
        text = if (fraction != null) {
            stringResource(
                id = R.string.update_download_progress,
                read,
                Formatter.formatShortFileSize(context, download.totalBytes),
                (fraction * 100).toInt(),
            )
        } else {
            stringResource(id = R.string.update_download_progress_unknown, read)
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}
