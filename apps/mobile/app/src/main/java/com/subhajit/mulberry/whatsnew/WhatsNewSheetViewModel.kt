package com.subhajit.mulberry.whatsnew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subhajit.mulberry.core.config.AppConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class WhatsNewSheetViewModel @Inject constructor(
    private val prompter: WhatsNewPrompter,
    private val appConfig: AppConfig
) : ViewModel() {
    val activePrompt: StateFlow<ActiveWhatsNewPrompt?> = prompter.activePrompt
    val apiBaseUrl: String = appConfig.apiBaseUrl

    fun dismiss() {
        viewModelScope.launch { prompter.onPromptDismissed() }
    }

    fun loadNextHistoryPage() {
        viewModelScope.launch { prompter.loadNextHistoryPage() }
    }
}
