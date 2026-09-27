package com.abhinavxt.debforge.ui.posters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.metadata.MetadataRepository.KeyCheck
import com.abhinavxt.debforge.data.metadata.TmdbApi

/**
 * Guided TMDB key setup, opened from the Library banner and from Settings.
 * Three short steps, a button straight to TMDB's key page, one-tap paste,
 * and a real check with TMDB before the key is saved. [onSaved] runs once
 * the key is stored (e.g. to show a "Posters are on" snackbar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosterSetupSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PosterSetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Fresh form every time the sheet opens (the view-model outlives it).
    LaunchedEffect(Unit) { viewModel.reset() }
    LaunchedEffect(state.done) {
        if (state.done) {
            viewModel.reset()
            onSaved()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.poster_setup_title), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.poster_setup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Step(1, stringResource(R.string.poster_setup_step1))
            Step(2, stringResource(R.string.poster_setup_step2))
            Step(3, stringResource(R.string.poster_setup_step3))
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { uriHandler.openUri(TmdbApi.KEY_PAGE_URL) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.poster_setup_open))
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::onDraft,
                label = { Text(stringResource(R.string.set_tmdb_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = state.problem != null && state.problem != KeyCheck.UNREACHABLE,
                supportingText = state.problem?.let { problem ->
                    {
                        Text(
                            stringResource(
                                when (problem) {
                                    KeyCheck.MALFORMED -> R.string.poster_setup_bad_format
                                    KeyCheck.REJECTED -> R.string.poster_setup_rejected
                                    KeyCheck.UNREACHABLE, KeyCheck.VALID -> R.string.poster_setup_unreachable
                                }
                            )
                        )
                    }
                },
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let(viewModel::onDraft) }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.poster_setup_paste))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.checkAndSave() }),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = viewModel::checkAndSave,
                    enabled = state.draft.isNotBlank() && !state.checking
                ) {
                    if (state.checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.poster_setup_check))
                }
                if (state.problem == KeyCheck.UNREACHABLE) {
                    TextButton(onClick = viewModel::saveAnyway) {
                        Text(stringResource(R.string.poster_setup_save_anyway))
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(n.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}
