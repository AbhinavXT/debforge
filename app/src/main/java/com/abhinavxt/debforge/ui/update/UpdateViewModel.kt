package com.abhinavxt.debforge.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abhinavxt.debforge.data.prefs.SettingsStore
import com.abhinavxt.debforge.data.update.UpdateChecker
import com.abhinavxt.debforge.data.update.UpdateInfo
import com.abhinavxt.debforge.data.update.UpdateStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs both the Library update banner and the Settings "About" section. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val settings: SettingsStore
) : ViewModel() {

    val status: StateFlow<UpdateStatus> = checker.status

    val autoCheck: StateFlow<Boolean> = settings.autoUpdateCheckFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Update to advertise in the Library, unless the user dismissed this version. */
    val banner: StateFlow<UpdateInfo?> = combine(checker.status, settings.dismissedUpdateFlow) { s, dismissed ->
        (s as? UpdateStatus.Available)?.info?.takeIf { it.tag != dismissed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val currentVersion: String get() = checker.currentVersion
    val repoUrl: String get() = "https://github.com/${checker.repo}"

    init {
        viewModelScope.launch { checker.checkIfDue() }
    }

    fun checkNow() = viewModelScope.launch { checker.check() }
    fun dismiss(info: UpdateInfo) = viewModelScope.launch { settings.dismissUpdate(info.tag) }
    fun setAutoCheck(v: Boolean) = viewModelScope.launch {
        settings.setAutoUpdateCheck(v)
        if (v) checker.checkIfDue()
    }
}
