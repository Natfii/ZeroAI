/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.discord

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.local.discord.AuthorCount
import com.zeroclaw.android.data.local.discord.DiscordMessageEntity
import com.zeroclaw.ffi.discordSearchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Page size for timeline message pagination. */
private const val PAGE_SIZE = 50

/** Debounce delay in milliseconds for search queries. */
private const val SEARCH_DEBOUNCE_MS = 500L

/** Default lookback window in days for archive search. */
private const val SEARCH_DAYS_BACK = 30L

/**
 * UI state for the channel detail timeline tab.
 *
 * @property messages Loaded messages in reverse chronological order.
 * @property isLoading Whether a page load is in progress.
 * @property hasMore Whether more messages can be loaded.
 */
data class TimelineUiState(
    val messages: List<DiscordMessageEntity> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
)

/**
 * UI state for the channel detail search tab.
 *
 * @property results Search result items matching the current query.
 * @property isSearching Whether a search is in progress.
 * @property query The current search query text.
 */
data class SearchUiState(
    val results: List<SearchResultItem> = emptyList(),
    val isSearching: Boolean = false,
    val query: String = "",
)

/**
 * A single search result from the Discord archive.
 *
 * @property author Display name of the message author.
 * @property content Message text content.
 * @property channelId Discord channel snowflake ID.
 * @property timestamp Unix timestamp in seconds.
 */
data class SearchResultItem(
    val author: String,
    val content: String,
    val channelId: String,
    val timestamp: Long,
)

/**
 * UI state for the channel detail stats tab.
 */
sealed interface StatsUiState {
    /** Stats are being computed. */
    data object Loading : StatsUiState

    /**
     * Stats have been computed.
     *
     * @property messageCount Total number of archived messages.
     * @property earliestTimestamp Unix timestamp of the oldest message, or null.
     * @property latestTimestamp Unix timestamp of the newest message, or null.
     * @property topAuthors Top 5 authors by message count.
     * @property syncStatus Human-readable sync status.
     */
    data class Content(
        val messageCount: Int,
        val earliestTimestamp: Long?,
        val latestTimestamp: Long?,
        val topAuthors: List<AuthorCount>,
        val syncStatus: String,
    ) : StatsUiState
}

/**
 * ViewModel for the Discord channel detail screen.
 *
 * Manages pagination for the timeline tab, debounced FFI search for the
 * search tab, and cached Room queries for the stats tab.
 *
 * @param application Application context for accessing the archive database.
 * @param channelId Discord channel snowflake ID to display.
 */
@Suppress("TooGenericExceptionCaught")
class DiscordChannelDetailViewModel(
    application: Application,
    private val channelId: String,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication

    private val _timeline = MutableStateFlow(TimelineUiState())

    /** Observable timeline state for the timeline tab. */
    val timeline: StateFlow<TimelineUiState> = _timeline.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())

    /** Observable search state for the search tab. */
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _stats = MutableStateFlow<StatsUiState>(StatsUiState.Loading)

    /** Observable stats state for the stats tab. */
    val stats: StateFlow<StatsUiState> = _stats.asStateFlow()

    private var currentOffset = 0
    private var searchJob: Job? = null
    private var statsLoaded = false

    init {
        loadNextPage()
    }

    /**
     * Loads the next page of messages from the Room database.
     *
     * Appends results to the existing timeline. Sets [TimelineUiState.hasMore]
     * to false when fewer than [PAGE_SIZE] messages are returned.
     */
    @Suppress("TooGenericExceptionCaught")
    fun loadNextPage() {
        if (_timeline.value.isLoading || !_timeline.value.hasMore) return

        viewModelScope.launch(Dispatchers.IO) {
            _timeline.value = _timeline.value.copy(isLoading = true)
            try {
                val db = app.openDiscordArchive()
                if (db == null) {
                    _timeline.value =
                        _timeline.value.copy(
                            isLoading = false,
                            hasMore = false,
                        )
                    return@launch
                }
                val page =
                    db.messageDao().getMessages(
                        channelId = channelId,
                        limit = PAGE_SIZE,
                        offset = currentOffset,
                    )
                currentOffset += page.size
                _timeline.value =
                    _timeline.value.copy(
                        messages = _timeline.value.messages + page,
                        isLoading = false,
                        hasMore = page.size == PAGE_SIZE,
                    )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load messages page", e)
                _timeline.value = _timeline.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Resets pagination and reloads the timeline from scratch.
     */
    fun refresh() {
        currentOffset = 0
        _timeline.value = TimelineUiState()
        loadNextPage()
    }

    /**
     * Performs a debounced search against the Discord archive.
     *
     * Cancels any in-flight search when a new query is submitted. Uses the
     * FFI `discordSearchHistory` function and maps native results into
     * immutable UI models for the search tab.
     *
     * @param query The text to search for.
     */
    @Suppress("TooGenericExceptionCaught")
    fun search(query: String) {
        _search.value = _search.value.copy(query = query)

        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchUiState(query = query)
            return
        }

        searchJob =
            viewModelScope.launch(Dispatchers.IO) {
                delay(SEARCH_DEBOUNCE_MS)
                _search.value = _search.value.copy(isSearching = true)
                try {
                    val results =
                        discordSearchHistory(
                            query = query,
                            channelId = channelId,
                            daysBack = SEARCH_DAYS_BACK,
                            limit = 10u,
                        ).map { result ->
                            SearchResultItem(
                                author = result.author,
                                content = result.content,
                                channelId = result.channelId,
                                timestamp = result.timestamp,
                            )
                        }
                    _search.value =
                        _search.value.copy(
                            results = results,
                            isSearching = false,
                        )
                } catch (e: Exception) {
                    Log.e(TAG, "Search failed", e)
                    _search.value =
                        _search.value.copy(
                            results = emptyList(),
                            isSearching = false,
                        )
                }
            }
    }

    /**
     * Loads aggregate statistics for the channel from Room.
     *
     * Results are cached in the ViewModel so they are only computed once
     * per screen visit. Subsequent calls are no-ops.
     */
    @Suppress("TooGenericExceptionCaught")
    fun loadStats() {
        if (statsLoaded) return

        viewModelScope.launch(Dispatchers.IO) {
            _stats.value = StatsUiState.Loading
            try {
                val db = app.openDiscordArchive()
                if (db == null) {
                    _stats.value =
                        StatsUiState.Content(
                            messageCount = 0,
                            earliestTimestamp = null,
                            latestTimestamp = null,
                            topAuthors = emptyList(),
                            syncStatus = "Archive database not found",
                        )
                    statsLoaded = true
                    return@launch
                }
                val dao = db.messageDao()
                val count = dao.countByChannel(channelId)
                val range = dao.dateRange(channelId)
                val authors = dao.topAuthors(channelId)

                _stats.value =
                    StatsUiState.Content(
                        messageCount = count,
                        earliestTimestamp = range?.earliest,
                        latestTimestamp = range?.latest,
                        topAuthors = authors,
                        syncStatus = if (count > 0) "Active" else "No messages archived",
                    )
                statsLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stats", e)
                _stats.value =
                    StatsUiState.Content(
                        messageCount = 0,
                        earliestTimestamp = null,
                        latestTimestamp = null,
                        topAuthors = emptyList(),
                        syncStatus = "Error loading stats",
                    )
                statsLoaded = true
            }
        }
    }

    /**
     * Factory for creating [DiscordChannelDetailViewModel] with a channel ID.
     *
     * @param application Application context for database access.
     * @param channelId Discord channel snowflake ID.
     */
    class Factory(
        private val application: Application,
        private val channelId: String,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DiscordChannelDetailViewModel(application, channelId) as T
        }
    }

    /** Constants for [DiscordChannelDetailViewModel]. */
    companion object {
        private const val TAG = "DiscordChannelDetailVM"
    }
}
