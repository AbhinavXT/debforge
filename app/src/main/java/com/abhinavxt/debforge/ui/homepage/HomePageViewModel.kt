package com.abhinavxt.debforge.ui.homepage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.prefs.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Only the row switches live here: the rows' data comes from the view-models that already own it
 * (Library, play history, downloads), so Home adds no loading of its own.
 */
@HiltViewModel
class HomePageViewModel @Inject constructor(settings: SettingsStore) : ViewModel() {
    val hiddenRows: StateFlow<Set<HomeRow>> = settings.homeRowsHiddenFlow.map(HomeRow::parseHidden)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}
