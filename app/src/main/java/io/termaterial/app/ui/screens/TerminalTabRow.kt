package io.termaterial.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.termaterial.app.R
import io.termaterial.app.terminal.TerminalTab

/** Multi-session tab strip (Step 4: "Onglets multi-sessions"), one chip per open [TerminalTab]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalTabRow(
    tabs: List<TerminalTab>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tab in tabs) {
            val title by tab.title
            FilterChip(
                selected = tab.id == activeTabId,
                onClick = { onSelectTab(tab.id) },
                label = { Text(text = title?.takeIf { it.isNotBlank() } ?: stringResource(id = R.string.app_name)) },
                trailingIcon = if (tabs.size > 1) {
                    {
                        IconButton(onClick = { onCloseTab(tab.id) }, modifier = Modifier.size(18.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(id = R.string.tab_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }
        IconButton(onClick = onNewTab) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(id = R.string.tab_new))
        }
    }
}
