package com.subhajit.mulberry.whatsnew

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ActiveWhatsNewPrompt(
    val entry: WhatsNewEntry,
    val markSeenVersionName: String?,
    val source: WhatsNewPromptSource,
    val historyEntries: List<WhatsNewEntry> = emptyList(),
    val historyNextCursor: String? = null,
    val historyLoadingMore: Boolean = false,
    val historyError: String? = null
)

enum class WhatsNewPromptSource {
    Auto,
    Manual,
    DeveloperPreview,
    SettingsHistory
}

@Singleton
class WhatsNewPrompter @Inject constructor(
    private val store: WhatsNewPromptStateStore,
    private val repository: WhatsNewRepository
) {
    private val mutex = Mutex()

    private val _activePrompt = MutableStateFlow<ActiveWhatsNewPrompt?>(null)
    val activePrompt: StateFlow<ActiveWhatsNewPrompt?> = _activePrompt.asStateFlow()

    suspend fun maybeAutoPrompt(
        nowMs: Long,
        currentVersionName: String,
        isEligibleToShow: Boolean
    ) {
        if (!isEligibleToShow) return
        mutex.withLock {
            if (_activePrompt.value != null) return

            val state = store.get()
            if (state.lastSeenVersionName == currentVersionName) return

            val normalized = if (state.pendingVersionName != currentVersionName) {
                store.updateAndGet { it.copy(pendingVersionName = currentVersionName, nextRetryAtMs = null, retryAttempt = 0) }
            } else {
                state
            }
            val nextRetryAt = normalized.nextRetryAtMs
            if (nextRetryAt != null && nowMs < nextRetryAt) return

            when (val result = repository.fetchByVersion(currentVersionName)) {
                is WhatsNewFetchResult.Success -> {
                    val entry = WhatsNewParser.parse(result.rawMarkdown)
                    _activePrompt.value = ActiveWhatsNewPrompt(
                        entry = entry,
                        markSeenVersionName = currentVersionName,
                        source = WhatsNewPromptSource.Auto
                    )
                }

                WhatsNewFetchResult.NotFound -> {
                    store.updateAndGet {
                        it.copy(
                            lastSeenVersionName = currentVersionName,
                            pendingVersionName = null,
                            nextRetryAtMs = null,
                            retryAttempt = 0
                        )
                    }
                }

                is WhatsNewFetchResult.Error -> {
                    if (!result.retryable) {
                        store.updateAndGet { it.copy(pendingVersionName = null, nextRetryAtMs = null, retryAttempt = 0) }
                        return
                    }
                    val nextAttempt = (normalized.retryAttempt + 1).coerceAtMost(MAX_RETRY_ATTEMPTS)
                    val delayMs = computeBackoffMs(nextAttempt)
                    store.updateAndGet {
                        it.copy(
                            pendingVersionName = currentVersionName,
                            nextRetryAtMs = nowMs + delayMs,
                            retryAttempt = nextAttempt
                        )
                    }
                }
            }
        }
    }

    suspend fun previewLatest(nowMs: Long): String? {
        mutex.withLock {
            if (_activePrompt.value != null) return null
            return when (val result = repository.fetchLatest()) {
                is WhatsNewFetchResult.Success -> {
                    val entry = WhatsNewParser.parse(result.rawMarkdown)
                    _activePrompt.value = ActiveWhatsNewPrompt(
                        entry = entry,
                        markSeenVersionName = null,
                        source = WhatsNewPromptSource.DeveloperPreview
                    )
                    null
                }

                WhatsNewFetchResult.NotFound -> "No what's new entries are configured on the backend."
                is WhatsNewFetchResult.Error -> "Unable to fetch what's new (${result.message})."
            }
        }
    }

    suspend fun openForVersion(
        nowMs: Long,
        versionName: String
    ): String? {
        mutex.withLock {
            if (_activePrompt.value != null) return null
            return when (val result = repository.fetchByVersion(versionName)) {
                is WhatsNewFetchResult.Success -> {
                    val entry = WhatsNewParser.parse(result.rawMarkdown)
                    _activePrompt.value = ActiveWhatsNewPrompt(
                        entry = entry,
                        markSeenVersionName = versionName,
                        source = WhatsNewPromptSource.Manual
                    )
                    null
                }

                WhatsNewFetchResult.NotFound -> {
                    store.updateAndGet {
                        it.copy(
                            lastSeenVersionName = versionName,
                            pendingVersionName = null,
                            nextRetryAtMs = null,
                            retryAttempt = 0
                        )
                    }
                    "No what’s new entry is configured for version $versionName."
                }

                is WhatsNewFetchResult.Error -> "Unable to fetch what’s new for version $versionName (${result.message})."
            }
        }
    }

    suspend fun openHistory(
        currentVersionName: String
    ): String? {
        mutex.withLock {
            if (_activePrompt.value != null) return null
            return when (val result = repository.fetchPage(cursor = null)) {
                is WhatsNewListFetchResult.Success -> {
                    _activePrompt.value = ActiveWhatsNewPrompt(
                        entry = result.entries.firstOrNull() ?: WhatsNewEntry(),
                        markSeenVersionName = result.entries
                            .firstOrNull { it.versionName == currentVersionName }
                            ?.versionName,
                        source = WhatsNewPromptSource.SettingsHistory,
                        historyEntries = result.entries,
                        historyNextCursor = result.nextCursor
                    )
                    null
                }

                is WhatsNewListFetchResult.Error -> "Unable to fetch what’s new (${result.message})."
            }
        }
    }

    suspend fun loadNextHistoryPage(): String? {
        mutex.withLock {
            val prompt = _activePrompt.value ?: return null
            if (prompt.source != WhatsNewPromptSource.SettingsHistory) return null
            val cursor = prompt.historyNextCursor ?: return null
            if (prompt.historyLoadingMore) return null

            _activePrompt.value = prompt.copy(historyLoadingMore = true, historyError = null)
            return when (val result = repository.fetchPage(cursor = cursor)) {
                is WhatsNewListFetchResult.Success -> {
                    val current = _activePrompt.value ?: return null
                    val existingVersions = current.historyEntries.mapNotNull { it.versionName }.toSet()
                    val newEntries = result.entries.filter { entry ->
                        val version = entry.versionName
                        version == null || version !in existingVersions
                    }
                    _activePrompt.value = current.copy(
                        entry = current.entry.takeIf { current.historyEntries.isNotEmpty() }
                            ?: newEntries.firstOrNull()
                            ?: WhatsNewEntry(),
                        historyEntries = current.historyEntries + newEntries,
                        historyNextCursor = result.nextCursor,
                        historyLoadingMore = false,
                        historyError = null
                    )
                    null
                }

                is WhatsNewListFetchResult.Error -> {
                    val current = _activePrompt.value
                    if (current != null) {
                        _activePrompt.value = current.copy(
                            historyLoadingMore = false,
                            historyError = "Unable to load more (${result.message})."
                        )
                    }
                    "Unable to load more what’s new entries (${result.message})."
                }
            }
        }
    }

    suspend fun onPromptDismissed() {
        mutex.withLock {
            val prompt = _activePrompt.value ?: return
            _activePrompt.value = null
            val markSeen = prompt.markSeenVersionName ?: return
            store.updateAndGet {
                it.copy(
                    lastSeenVersionName = markSeen,
                    pendingVersionName = null,
                    nextRetryAtMs = null,
                    retryAttempt = 0
                )
            }
        }
    }

    private fun computeBackoffMs(attempt: Int): Long {
        if (attempt <= 0) return BASE_RETRY_MS
        val multiplier = 1L shl (attempt - 1).coerceAtMost(10)
        return (BASE_RETRY_MS * multiplier).coerceAtMost(MAX_RETRY_MS)
    }

    private companion object {
        private const val BASE_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 6L * 60L * 60L * 1_000L
        private const val MAX_RETRY_ATTEMPTS = 10
    }
}
