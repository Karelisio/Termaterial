package io.termaterial.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
        modifier = modifier.fillMaxSize().padding(PaddingValues(24.dp)),
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
                    Text(
                        text = progress.message,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(id = R.string.bootstrap_retry))
                    }
                }
            }
        }
    }
}
