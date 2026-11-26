package com.abhinavxt.debforge.ui.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.domain.ProviderId
import com.abhinavxt.debforge.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One signed-in service in the switcher menu. */
data class ServiceChoice(val id: ProviderId, val name: String, val active: Boolean)

/** The signed-in services in display order; [all] is every provider as (id, display name). */
fun serviceChoices(all: List<Pair<ProviderId, String>>, signedIn: Set<ProviderId>, active: ProviderId?): List<ServiceChoice> =
    all.filter { it.first in signedIn }.map { ServiceChoice(it.first, it.second, it.first == active) }

@HiltViewModel
class ServiceSwitcherViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    val choices: StateFlow<List<ServiceChoice>> = combine(auth.signedInProvider, auth.signedInProviders) { active, signedIn ->
        serviceChoices(auth.providers.map { it.id to it.displayName }, signedIn, active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same call Settings' service list makes. */
    fun select(id: ProviderId) = viewModelScope.launch { auth.setActiveProvider(id) }
}

/** The service switcher: signed-in services (the active one ticked), then "Add a service". Shared by the title and the tab. */
@Composable
fun ServiceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    viewModel: ServiceSwitcherViewModel = hiltViewModel()
) {
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        choices.forEach { c ->
            DropdownMenuItem(
                text = { Text(c.name) },
                trailingIcon = { if (c.active) Icon(Icons.Rounded.Check, contentDescription = null) },
                onClick = { onDismiss(); if (!c.active) viewModel.select(c.id) }
            )
        }
        if (choices.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.service_add)) },
            onClick = { onDismiss(); onConnect() }
        )
    }
}
