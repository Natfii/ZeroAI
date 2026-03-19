/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.discord

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.local.discord.AuthorCount
import com.zeroclaw.android.data.local.discord.DiscordMessageEntity
import com.zeroclaw.android.ui.component.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimum touch target size in dp. */
private const val MIN_TOUCH_TARGET_DP = 48

/** Standard horizontal padding in dp. */
private const val EDGE_PADDING_DP = 16

/** Standard vertical spacing between items in dp. */
private const val ITEM_SPACING_DP = 8

/** Section heading spacing in dp. */
private const val SECTION_SPACING_DP = 16

/** Inner card padding in dp. */
private const val CARD_PADDING_DP = 16

/** Tab index for the Timeline tab. */
private const val TAB_TIMELINE = 0

/** Tab index for the Search tab. */
private const val TAB_SEARCH = 1

/** Tab index for the Stats tab. */
private const val TAB_STATS = 2

/** Milliseconds-per-second conversion factor. */
private const val MILLIS_PER_SECOND = 1000L

/**
 * Discord channel detail screen with Timeline, Search, and Stats tabs.
 *
 * Displays archived messages, full-text search, and aggregate statistics
 * for a single Discord channel. Data is loaded from the Rust-managed
 * `discord_archive.db` via Room one-shot queries.
 *
 * @param channelId Discord channel snowflake ID to display.
 * @param onBack Callback when the back button is pressed.
 * @param modifier Modifier applied to the root layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordChannelDetailScreen(
    channelId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeroAIApplication
    val viewModel: DiscordChannelDetailViewModel =
        viewModel(
            factory = DiscordChannelDetailViewModel.Factory(app, channelId),
        )

    val timelineState by viewModel.timeline.collectAsStateWithLifecycle()
    val searchState by viewModel.search.collectAsStateWithLifecycle()
    val statsState by viewModel.stats.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_TIMELINE) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == TAB_STATS) {
            viewModel.loadStats()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Channel Detail",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Navigate back"
                            },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    if (selectedTab == TAB_TIMELINE) {
                        IconButton(
                            onClick = { viewModel.refresh() },
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "Refresh timeline"
                                },
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == TAB_TIMELINE,
                    onClick = { selectedTab = TAB_TIMELINE },
                    text = { Text("Timeline") },
                    icon = {
                        Icon(Icons.Filled.Forum, contentDescription = null)
                    },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Timeline tab"
                        },
                )
                Tab(
                    selected = selectedTab == TAB_SEARCH,
                    onClick = { selectedTab = TAB_SEARCH },
                    text = { Text("Search") },
                    icon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Search tab"
                        },
                )
                Tab(
                    selected = selectedTab == TAB_STATS,
                    onClick = { selectedTab = TAB_STATS },
                    text = { Text("Stats") },
                    icon = {
                        Icon(Icons.Filled.Analytics, contentDescription = null)
                    },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Stats tab"
                        },
                )
            }

            when (selectedTab) {
                TAB_TIMELINE ->
                    TimelineTab(
                        state = timelineState,
                        onLoadMore = { viewModel.loadNextPage() },
                    )
                TAB_SEARCH ->
                    SearchTab(
                        state = searchState,
                        onQueryChange = { viewModel.search(it) },
                    )
                TAB_STATS -> StatsTab(state = statsState)
            }
        }
    }
}

/**
 * Timeline tab displaying paginated messages grouped by date.
 *
 * Uses a manual "Load more" button instead of infinite scroll to
 * conserve battery on always-on foreground service devices.
 *
 * @param state Current timeline UI state.
 * @param onLoadMore Callback to load the next page of messages.
 */
@Composable
private fun TimelineTab(
    state: TimelineUiState,
    onLoadMore: () -> Unit,
) {
    if (state.messages.isEmpty() && !state.isLoading) {
        EmptyState(
            icon = Icons.Filled.Forum,
            message = "No messages archived yet",
        )
        return
    }

    val groupedMessages = groupMessagesByDay(state.messages)

    LazyColumn(
        contentPadding =
            PaddingValues(
                start = EDGE_PADDING_DP.dp,
                end = EDGE_PADDING_DP.dp,
                top = ITEM_SPACING_DP.dp,
                bottom = SECTION_SPACING_DP.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
    ) {
        groupedMessages.forEach { (dateLabel, messages) ->
            item(key = "header_$dateLabel", contentType = "date_header") {
                DateHeader(label = dateLabel)
            }
            items(
                items = messages,
                key = { it.id },
                contentType = { "message" },
            ) { message ->
                MessageCard(message = message)
            }
        }

        if (state.isLoading) {
            item(key = "loading", contentType = "loading") {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = SECTION_SPACING_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.hasMore) {
            item(key = "load_more", contentType = "load_more") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(
                        onClick = onLoadMore,
                        modifier =
                            Modifier
                                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                                .semantics {
                                    contentDescription = "Load more messages"
                                },
                    ) {
                        Text("Load more")
                    }
                }
            }
        }
    }
}

/**
 * Search tab with a debounced text field and result list.
 *
 * @param state Current search UI state.
 * @param onQueryChange Callback when the search query text changes.
 */
@Composable
private fun SearchTab(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = EDGE_PADDING_DP.dp),
    ) {
        Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Search messages") },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search messages input" },
        )

        Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))

        if (state.isSearching) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(ITEM_SPACING_DP.dp))
        }

        if (state.query.isNotBlank() && state.results.isEmpty() && !state.isSearching) {
            EmptyState(
                icon = Icons.Filled.Search,
                message = "No results found for \"${state.query}\"",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = SECTION_SPACING_DP.dp),
                verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
            ) {
                items(
                    items = state.results,
                    key = { "${it.channelId}_${it.timestamp}" },
                    contentType = { "search_result" },
                ) { result ->
                    SearchResultCard(result = result)
                }
            }
        }
    }
}

/**
 * Stats tab displaying aggregate channel statistics.
 *
 * Values are computed once when the tab is first selected and cached
 * in the ViewModel.
 *
 * @param state Current stats UI state.
 */
@Composable
private fun StatsTab(state: StatsUiState) {
    when (state) {
        is StatsUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is StatsUiState.Content -> {
            LazyColumn(
                contentPadding =
                    PaddingValues(
                        start = EDGE_PADDING_DP.dp,
                        end = EDGE_PADDING_DP.dp,
                        top = SECTION_SPACING_DP.dp,
                        bottom = SECTION_SPACING_DP.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
            ) {
                item(key = "message_count", contentType = "stat_card") {
                    StatCard(
                        title = "Total Messages",
                        value = state.messageCount.toString(),
                    )
                }
                item(key = "date_range", contentType = "stat_card") {
                    StatCard(
                        title = "Date Range",
                        value =
                            formatDateRange(
                                state.earliestTimestamp,
                                state.latestTimestamp,
                            ),
                    )
                }
                item(key = "top_authors_header", contentType = "section_header") {
                    Text(
                        text = "Top Authors",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = ITEM_SPACING_DP.dp),
                    )
                }
                if (state.topAuthors.isEmpty()) {
                    item(key = "no_authors", contentType = "stat_card") {
                        StatCard(
                            title = "Authors",
                            value = "No data",
                        )
                    }
                } else {
                    items(
                        items = state.topAuthors,
                        key = { it.authorName },
                        contentType = { "author_row" },
                    ) { author ->
                        AuthorRow(author = author)
                    }
                }
                item(key = "sync_status", contentType = "stat_card") {
                    StatCard(
                        title = "Sync Status",
                        value = state.syncStatus,
                    )
                }
            }
        }
    }
}

/**
 * Date header displayed between message groups in the timeline.
 *
 * @param label Formatted date string.
 */
@Composable
private fun DateHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(
                top = ITEM_SPACING_DP.dp,
                bottom = 4.dp,
            ),
    )
}

/**
 * Card displaying a single archived Discord message.
 *
 * @param message The message entity to display.
 */
@Composable
private fun MessageCard(message: DiscordMessageEntity) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(CARD_PADDING_DP.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.authorName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(ITEM_SPACING_DP.dp))
                Text(
                    text = formatRelativeTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Card displaying a single search result.
 *
 * @param result The search result to display.
 */
@Composable
private fun SearchResultCard(result: SearchResultItem) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(CARD_PADDING_DP.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = result.author,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(ITEM_SPACING_DP.dp))
                Text(
                    text = formatRelativeTimestamp(result.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = result.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Card showing a single statistic with a title and value.
 *
 * @param title Label for the statistic.
 * @param value Formatted value string.
 */
@Composable
private fun StatCard(
    title: String,
    value: String,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
    ) {
        Column(modifier = Modifier.padding(CARD_PADDING_DP.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * Row displaying an author name and their message count.
 *
 * @param author The author count data.
 */
@Composable
private fun AuthorRow(author: AuthorCount) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CARD_PADDING_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = author.authorName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(ITEM_SPACING_DP.dp))
            Text(
                text = "${author.cnt} messages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Groups messages by calendar day for timeline date headers.
 *
 * @param messages Flat list of messages sorted by timestamp descending.
 * @return Ordered list of pairs: (date label, messages for that day).
 */
private fun groupMessagesByDay(
    messages: List<DiscordMessageEntity>,
): List<Pair<String, List<DiscordMessageEntity>>> {
    if (messages.isEmpty()) return emptyList()

    val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    return messages
        .groupBy { dateFormat.format(Date(it.timestamp * MILLIS_PER_SECOND)) }
        .toList()
}

/**
 * Formats a Unix timestamp as a relative time string (e.g., "3 hours ago").
 *
 * @param timestampSeconds Unix timestamp in seconds.
 * @return Human-readable relative time string.
 */
private fun formatRelativeTimestamp(timestampSeconds: Long): String {
    val millis = timestampSeconds * MILLIS_PER_SECOND
    return DateUtils
        .getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
}

/**
 * Formats earliest and latest timestamps into a date range string.
 *
 * @param earliest Unix timestamp of the oldest message, or null.
 * @param latest Unix timestamp of the newest message, or null.
 * @return Formatted date range string, or "No data" if both are null.
 */
private fun formatDateRange(
    earliest: Long?,
    latest: Long?,
): String {
    if (earliest == null || latest == null) return "No data"
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val start = dateFormat.format(Date(earliest * MILLIS_PER_SECOND))
    val end = dateFormat.format(Date(latest * MILLIS_PER_SECOND))
    return "$start \u2014 $end"
}
