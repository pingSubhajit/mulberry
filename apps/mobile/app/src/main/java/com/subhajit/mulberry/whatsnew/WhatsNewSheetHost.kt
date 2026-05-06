package com.subhajit.mulberry.whatsnew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun WhatsNewSheetHost(
    viewModel: WhatsNewSheetViewModel = hiltViewModel()
) {
    val prompt by viewModel.activePrompt.collectAsStateWithLifecycle()
    val active = prompt ?: return
    WhatsNewBottomSheet(
        entry = active.entry,
        apiBaseUrl = viewModel.apiBaseUrl,
        onDismiss = viewModel::dismiss
    )
}

