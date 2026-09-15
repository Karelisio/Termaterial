package io.termaterial.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.termaterial.app.R
import androidx.compose.ui.res.stringResource

/**
 * Settings entry point, wired up as a Material 3 [ModalBottomSheet] (per the Step 3 requirement).
 * Actual content (monospace font choice, text size, terminal color theme, dynamic color toggle)
 * is added in Step 4; for now this only proves the component is in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = stringResource(id = R.string.settings),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            text = "À venir : Étape 4 (police, taille de texte, thème de couleurs, dynamic color).",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}
