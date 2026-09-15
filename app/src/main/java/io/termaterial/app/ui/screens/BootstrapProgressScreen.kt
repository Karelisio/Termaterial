package io.termaterial.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.termaterial.app.R
import io.termaterial.shell.BootstrapProgress

/** First-launch screen shown while [io.termaterial.shell.BootstrapInstaller] downloads/extracts. */
@Composable
fun BootstrapProgressScreen(
    progress: BootstrapProgress,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(id = R.string.bootstrap_installing_title),
            style = MaterialTheme.typography.titleLarge,
        )

        Column(
            modifier = Modifier.padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (progress) {
                is BootstrapProgress.CheckingExistingInstallation -> {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(id = R.string.bootstrap_checking),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                is BootstrapProgress.Downloading -> {
                    val fraction = if (progress.totalBytes > 0) {
                        (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.width(240.dp))
                    Text(
                        text = stringResource(id = R.string.bootstrap_downloading),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = "${progress.bytesRead / 1024 / 1024} / " +
                            if (progress.totalBytes > 0) "${progress.totalBytes / 1024 / 1024} MB" else "? MB",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is BootstrapProgress.Extracting -> {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(id = R.string.bootstrap_extracting),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = "${progress.entriesDone}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is BootstrapProgress.Installed -> {
                    CircularProgressIndicator()
                }

                is BootstrapProgress.Failed -> {
                    Text(
                        text = stringResource(id = R.string.bootstrap_failed_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Selectable + monospace: this can be a full stack trace (see MainActivity's
                    // session-creation error handling), and the user needs to be able to copy it
                    // out to report it, not just read it.
                    SelectionContainer {
                        Text(
                            text = progress.message,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(id = R.string.bootstrap_retry))
                    }
                }
            }
        }
    }
}
