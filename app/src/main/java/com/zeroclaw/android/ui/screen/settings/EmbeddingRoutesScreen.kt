/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ui.component.SectionHeader
import org.json.JSONArray
import org.json.JSONObject

/** Default embedding dimensions. */
private const val DEFAULT_DIMENSIONS = 1536

/**
 * Embedding routing rules screen for hint-based embedding provider selection.
 *
 * Maps to the upstream `[[embedding_routes]]` TOML array. Each route maps
 * a hint keyword to a specific embedding provider, model, and dimension count.
 * Routes are persisted as a JSON array string in [AppSettings.embeddingRoutesJson].
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param settingsViewModel The shared [SettingsViewModel].
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun EmbeddingRoutesScreen(
    edgeMargin: Dp,
    settingsViewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val routes =
        remember(settings.embeddingRoutesJson) {
            parseEmbeddingRoutes(settings.embeddingRoutesJson)
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = edgeMargin),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        SectionHeader(title = "Embedding Routes")

        Text(
            text = "Route embedding requests to specific providers based on hint keywords.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = routes,
                key = { index, _ -> index },
            ) { index, route ->
                EmbeddingRouteCard(
                    route = route,
                    onUpdate = { updated ->
                        val newRoutes = routes.toMutableList()
                        newRoutes[index] = updated
                        settingsViewModel.updateEmbeddingRoutesJson(
                            serializeEmbeddingRoutes(newRoutes),
                        )
                    },
                    onDelete = {
                        val newRoutes = routes.toMutableList()
                        newRoutes.removeAt(index)
                        settingsViewModel.updateEmbeddingRoutesJson(
                            serializeEmbeddingRoutes(newRoutes),
                        )
                    },
                )
            }
        }

        FilledTonalButton(
            onClick = {
                val newRoutes = routes + EmbeddingRoute()
                settingsViewModel.updateEmbeddingRoutesJson(
                    serializeEmbeddingRoutes(newRoutes),
                )
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .semantics { contentDescription = "Add embedding route" },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add Route", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * Card displaying a single embedding route with editable fields.
 *
 * @param route The route data to display.
 * @param onUpdate Callback when any field changes.
 * @param onDelete Callback when the delete button is tapped.
 */
@Composable
private fun EmbeddingRouteCard(
    route: EmbeddingRoute,
    onUpdate: (EmbeddingRoute) -> Unit,
    onDelete: () -> Unit,
) {
    var hint by remember(route) { mutableStateOf(route.hint) }
    var provider by remember(route) { mutableStateOf(route.provider) }
    var model by remember(route) { mutableStateOf(route.model) }
    var dimensions by remember(route) { mutableStateOf(route.dimensions.toString()) }
    var apiKey by remember(route) { mutableStateOf(route.apiKey) }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Embedding Route", style = MaterialTheme.typography.titleSmall)
                IconButton(
                    onClick = onDelete,
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Delete embedding route"
                        },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                }
            }
            OutlinedTextField(
                value = hint,
                onValueChange = {
                    hint = it
                    onUpdate(route.copy(hint = it))
                },
                label = { Text("Hint") },
                supportingText = { Text("e.g. default, code, multilingual") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = provider,
                onValueChange = {
                    provider = it
                    onUpdate(route.copy(provider = it))
                },
                label = { Text("Provider") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    onUpdate(route.copy(model = it))
                },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = dimensions,
                onValueChange = {
                    dimensions = it
                    it.toIntOrNull()?.let { d -> onUpdate(route.copy(dimensions = d)) }
                },
                label = { Text("Dimensions") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    onUpdate(route.copy(apiKey = it))
                },
                label = { Text("API Key") },
                supportingText = { Text("Optional per-route key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * In-memory representation of a single embedding route.
 *
 * @property hint Keyword that triggers this route.
 * @property provider Provider ID for embedding.
 * @property model Model name for embedding.
 * @property dimensions Vector dimension count.
 * @property apiKey Optional API key for this route.
 */
private data class EmbeddingRoute(
    val hint: String = "",
    val provider: String = "",
    val model: String = "",
    val dimensions: Int = DEFAULT_DIMENSIONS,
    val apiKey: String = "",
)

@Suppress("TooGenericExceptionCaught")
private fun parseEmbeddingRoutes(json: String): List<EmbeddingRoute> =
    try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            EmbeddingRoute(
                hint = obj.optString("hint", ""),
                provider = obj.optString("provider", ""),
                model = obj.optString("model", ""),
                dimensions = obj.optInt("dimensions", DEFAULT_DIMENSIONS),
                apiKey = obj.optString("api_key", ""),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

private fun serializeEmbeddingRoutes(routes: List<EmbeddingRoute>): String {
    val arr = JSONArray()
    for (route in routes) {
        arr.put(
            JSONObject().apply {
                put("hint", route.hint)
                put("provider", route.provider)
                put("model", route.model)
                put("dimensions", route.dimensions)
                if (route.apiKey.isNotBlank()) put("api_key", route.apiKey)
            },
        )
    }
    return arr.toString()
}
