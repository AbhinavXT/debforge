package com.abhinavxt.debforge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.search.SearchRepository
import com.abhinavxt.debforge.data.search.SearchSource
import com.abhinavxt.debforge.data.search.SearchSourceStore
import com.abhinavxt.debforge.ui.components.RowDivider
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SearchSourcesViewModel @Inject constructor(
    private val store: SearchSourceStore,
    private val repository: SearchRepository
) : ViewModel() {

    val sources: StateFlow<List<SearchSource>> = store.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    sealed interface Check {
        data object Idle : Check
        data object Checking : Check
        data object Saved : Check
        data class Problem(val message: String) : Check
    }

    private val _check = MutableStateFlow<Check>(Check.Idle)
    val check: StateFlow<Check> = _check.asStateFlow()

    fun resetCheck() { _check.value = Check.Idle }

    /** Tests the feed first; [force] saves without (the server is off right now). */
    fun add(name: String, url: String, apiKey: String, force: Boolean = false) {
        val host = url.trim().toHttpUrlOrNull()?.host
        val source = SearchSource(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { host ?: url.trim() },
            url = url.trim(),
            apiKey = apiKey.trim()
        )
        _check.value = Check.Checking
        viewModelScope.launch {
            val problem = if (force) null else repository.test(source)
            if (problem == null) {
                store.save(source)
                _check.value = Check.Saved
            } else {
                _check.value = Check.Problem(problem)
            }
        }
    }

    fun remove(source: SearchSource) { viewModelScope.launch { store.remove(source.id) } }
}

/** Settings → Search sources: the Jackett / Prowlarr feeds the Search tab uses. */
@Composable
fun SearchSourcesSection(viewModel: SearchSourcesViewModel = hiltViewModel()) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.sources_title)) {
        SettingRow(
            icon = Icons.Rounded.TravelExplore,
            title = stringResource(R.string.sources_title),
            subtitle = stringResource(R.string.sources_detail)
        )
        sources.forEach { source ->
            RowDivider()
            SettingRow(
                icon = Icons.Rounded.TravelExplore,
                title = source.name,
                subtitle = source.url.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: source.url,
                trailing = {
                    IconButton(onClick = { viewModel.remove(source) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.sources_remove, source.name))
                    }
                }
            )
        }
        FilledTonalButton(
            onClick = { viewModel.resetCheck(); adding = true },
            modifier = Modifier.padding(start = 68.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        ) {
            Text(stringResource(R.string.sources_add))
        }
    }

    if (adding) AddSourceDialog(viewModel, onDismiss = { adding = false })
}

@Composable
private fun AddSourceDialog(viewModel: SearchSourcesViewModel, onDismiss: () -> Unit) {
    val check by viewModel.check.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    if (check is SearchSourcesViewModel.Check.Saved) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val checking = check is SearchSourcesViewModel.Check.Checking
    val problem = (check as? SearchSourcesViewModel.Check.Problem)?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sources_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sources_add_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = url, onValueChange = { url = it.trim(); viewModel.resetCheck() },
                    label = { Text(stringResource(R.string.sources_url)) },
                    placeholder = { Text("http://192.168.1.5:9117/api/v2.0/indexers/all/results/torznab/") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim(); viewModel.resetCheck() },
                    label = { Text(stringResource(R.string.sources_api_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it; viewModel.resetCheck() },
                    label = { Text(stringResource(R.string.sources_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            // Saving anyway only makes sense for a real link (the server may just be off).
            if (problem != null && url.toHttpUrlOrNull() != null) {
                TextButton(onClick = { viewModel.add(name, url, key, force = true) }) {
                    Text(stringResource(R.string.os_save_anyway))
                }
            } else {
                TextButton(onClick = { viewModel.add(name, url, key) }, enabled = url.isNotBlank() && !checking && problem == null) {
                    if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
