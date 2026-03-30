//! REST API handlers for the web dashboard.
//!
//! All `/api/*` routes require an authenticated dashboard session or bearer token.

use super::{require_pairing_auth, AppState};
use axum::{
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Json},
};
use serde::Deserialize;

#[derive(Deserialize)]
pub struct MemoryQuery {
    pub query: Option<String>,
    pub category: Option<String>,
}

#[derive(Deserialize)]
pub struct MemoryStoreBody {
    pub key: String,
    pub content: String,
    pub category: Option<String>,
}

/// Query parameters for the memory graph endpoint.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GraphQuery {
    /// Maximum number of links to return per source node (default 5).
    pub max_links_per_node: Option<usize>,
}

/// GET /api/session — current dashboard authentication state.
pub async fn handle_api_session(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let authenticated = require_pairing_auth(&state, &headers).is_ok();
    Json(serde_json::json!({
        "authenticated": authenticated || !state.pairing.require_pairing(),
        "paired": state.pairing.is_paired(),
        "require_pairing": state.pairing.require_pairing(),
    }))
}

/// GET /api/status — system status overview
pub async fn handle_api_status(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if let Err(e) = require_pairing_auth(&state, &headers) {
        return e.into_response();
    }

    let config = state.config.lock().clone();
    let health = crate::health::snapshot();

    let mut channels = serde_json::Map::new();

    for (channel, present) in config.channels_config.channels() {
        channels.insert(channel.name().to_string(), serde_json::Value::Bool(present));
    }

    let body = serde_json::json!({
        "provider": config.default_provider,
        "model": state.model,
        "temperature": state.temperature,
        "uptime_seconds": health.uptime_seconds,
        "gateway_port": config.gateway.port,
        "locale": "en",
        "memory_backend": state.mem.name(),
        "paired": state.pairing.is_paired(),
        "channels": channels,
        "health": health,
    });

    Json(body).into_response()
}

/// GET /api/memory — list or search memory entries
pub async fn handle_api_memory_list(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(params): Query<MemoryQuery>,
) -> impl IntoResponse {
    if let Err(e) = require_pairing_auth(&state, &headers) {
        return e.into_response();
    }

    if let Some(ref query) = params.query {
        match state.mem.recall(query, 50, None).await {
            Ok(entries) => Json(serde_json::json!({"entries": entries})).into_response(),
            Err(e) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": format!("Memory recall failed: {e}")})),
            )
                .into_response(),
        }
    } else {
        let category = params.category.as_deref().map(|cat| match cat {
            "core" => crate::memory::MemoryCategory::Core,
            "daily" => crate::memory::MemoryCategory::Daily,
            "conversation" => crate::memory::MemoryCategory::Conversation,
            other => crate::memory::MemoryCategory::Custom(other.to_string()),
        });

        match state.mem.list(category.as_ref(), None).await {
            Ok(entries) => Json(serde_json::json!({"entries": entries})).into_response(),
            Err(e) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"error": format!("Memory list failed: {e}")})),
            )
                .into_response(),
        }
    }
}

/// POST /api/memory — store a memory entry
pub async fn handle_api_memory_store(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<MemoryStoreBody>,
) -> impl IntoResponse {
    if let Err(e) = require_pairing_auth(&state, &headers) {
        return e.into_response();
    }

    let category = body
        .category
        .as_deref()
        .map(|cat| match cat {
            "core" => crate::memory::MemoryCategory::Core,
            "daily" => crate::memory::MemoryCategory::Daily,
            "conversation" => crate::memory::MemoryCategory::Conversation,
            other => crate::memory::MemoryCategory::Custom(other.to_string()),
        })
        .unwrap_or(crate::memory::MemoryCategory::Core);

    match state
        .mem
        .store(&body.key, &body.content, category, None)
        .await
    {
        Ok(()) => Json(serde_json::json!({"status": "ok"})).into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Memory store failed: {e}")})),
        )
            .into_response(),
    }
}

/// DELETE /api/memory/:key — delete a memory entry
pub async fn handle_api_memory_delete(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(key): Path<String>,
) -> impl IntoResponse {
    if let Err(e) = require_pairing_auth(&state, &headers) {
        return e.into_response();
    }

    match state.mem.forget(&key).await {
        Ok(deleted) => {
            Json(serde_json::json!({"status": "ok", "deleted": deleted})).into_response()
        }
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Memory forget failed: {e}")})),
        )
            .into_response(),
    }
}

/// GET /api/memory/graph — memory graph for the Brain Visualizer
///
/// Returns all nodes (without content) and pre-computed Jaccard links,
/// capped at `maxLinksPerNode` (default 5) per source. Scores are
/// normalized to \[0.0, 1.0\] relative to the result-set maximum.
pub async fn handle_api_memory_graph(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(params): Query<GraphQuery>,
) -> impl IntoResponse {
    if let Err(e) = require_pairing_auth(&state, &headers) {
        return e.into_response();
    }

    let max_links = params.max_links_per_node.unwrap_or(5);

    let sqlite = match state
        .mem
        .as_any()
        .downcast_ref::<crate::memory::SqliteMemory>()
    {
        Some(s) => s,
        None => {
            return (
                StatusCode::NOT_IMPLEMENTED,
                Json(serde_json::json!({"error": "Graph requires SQLite memory backend"})),
            )
                .into_response();
        }
    };

    let conn = sqlite.conn.clone();
    let result = tokio::task::spawn_blocking(move || -> anyhow::Result<serde_json::Value> {
        let conn = conn.lock();

        // Fetch nodes (without content)
        let mut stmt = conn.prepare(
            "SELECT id, key, category, source, tags, confidence, access_count, last_accessed_at
             FROM memories ORDER BY updated_at DESC",
        )?;

        let mut nodes = Vec::new();
        let mut max_score: f64 = 0.0;
        let raw_rows: Vec<(String, String, String, String, String, f64, u32, Option<String>)> =
            stmt.query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, String>(4)?,
                    row.get::<_, f64>(5)?,
                    row.get::<_, u32>(6)?,
                    row.get::<_, Option<String>>(7)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;

        for row in &raw_rows {
            if row.5 > max_score {
                max_score = row.5;
            }
        }
        if max_score <= 0.0 {
            max_score = 1.0;
        }

        for (id, key, category, source, tags, confidence, access_count, last_accessed_at) in
            &raw_rows
        {
            let normalized = confidence / max_score;
            let tag_list: Vec<&str> = tags.split(',').map(str::trim).filter(|t| !t.is_empty()).collect();
            nodes.push(serde_json::json!({
                "id": id,
                "key": key,
                "category": category,
                "source": source,
                "tags": tag_list,
                "score": confidence,
                "display_score": (normalized * 100.0).round() / 100.0,
                "access_count": access_count,
                "last_accessed_at": last_accessed_at,
            }));
        }

        // Fetch links, capped per source node
        let mut link_stmt = conn.prepare(
            "SELECT source_id, target_id, similarity FROM memory_links ORDER BY source_id, similarity DESC",
        )?;
        let all_links: Vec<(String, String, f64)> = link_stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, f64>(2)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;

        let mut links = Vec::new();
        let mut counts: std::collections::HashMap<&str, usize> = std::collections::HashMap::new();
        for (source_id, target_id, similarity) in &all_links {
            let count = counts.entry(source_id.as_str()).or_insert(0);
            if *count >= max_links {
                continue;
            }
            *count += 1;
            links.push(serde_json::json!({
                "source_id": source_id,
                "target_id": target_id,
                "similarity": similarity,
            }));
        }

        Ok(serde_json::json!({
            "nodes": nodes,
            "links": links,
        }))
    })
    .await;

    match result {
        Ok(Ok(body)) => Json(body).into_response(),
        Ok(Err(e)) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Graph query failed: {e}")})),
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Graph task panicked: {e}")})),
        )
            .into_response(),
    }
}

/// GET /api/memory/leaderboard — cached leaderboard JSON from consolidation
///
/// Returns the cached leaderboard payload. Returns 503 if the cache is
/// empty (daemon has not yet run consolidation).
pub async fn handle_api_memory_leaderboard(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if let Err(e) = require_pairing_auth(&state, &headers) {
        return e.into_response();
    }

    let sqlite = match state
        .mem
        .as_any()
        .downcast_ref::<crate::memory::SqliteMemory>()
    {
        Some(s) => s,
        None => {
            return (
                StatusCode::NOT_IMPLEMENTED,
                Json(serde_json::json!({"error": "Leaderboard requires SQLite memory backend"})),
            )
                .into_response();
        }
    };

    // Build leaderboard from live data: top 50 memories ordered by access_count
    let conn = sqlite.conn.clone();
    let result = tokio::task::spawn_blocking(move || -> anyhow::Result<serde_json::Value> {
        let conn = conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, key, category, source, tags, confidence, access_count, last_accessed_at
             FROM memories ORDER BY access_count DESC, confidence DESC LIMIT 50",
        )?;

        let entries: Vec<serde_json::Value> = stmt
            .query_map([], |row| {
                Ok(serde_json::json!({
                    "id": row.get::<_, String>(0)?,
                    "key": row.get::<_, String>(1)?,
                    "category": row.get::<_, String>(2)?,
                    "source": row.get::<_, String>(3)?,
                    "tags": row.get::<_, String>(4)?,
                    "confidence": row.get::<_, f64>(5)?,
                    "access_count": row.get::<_, u32>(6)?,
                    "last_accessed_at": row.get::<_, Option<String>>(7)?,
                }))
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(serde_json::json!(entries))
    })
    .await;

    match result {
        Ok(Ok(body)) => {
            if body.as_array().is_some_and(|a| a.is_empty()) {
                return (
                    StatusCode::SERVICE_UNAVAILABLE,
                    Json(serde_json::json!({"error": "Leaderboard is empty — no memories yet"})),
                )
                    .into_response();
            }
            Json(body).into_response()
        }
        Ok(Err(e)) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Leaderboard query failed: {e}")})),
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Leaderboard task panicked: {e}")})),
        )
            .into_response(),
    }
}

/// GET /api/memory/stats — aggregate memory statistics
///
/// Returns fact counts by category, total count, and consolidation
/// backlog size for the Brain Visualizer dashboard widget.
pub async fn handle_api_memory_stats(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    if let Err(e) = require_pairing_auth(&state, &headers) {
        return e.into_response();
    }

    let sqlite = match state
        .mem
        .as_any()
        .downcast_ref::<crate::memory::SqliteMemory>()
    {
        Some(s) => s,
        None => {
            // Fallback for non-SQLite backends: use trait methods only
            let total = state.mem.count().await.unwrap_or(0);
            return Json(serde_json::json!({
                "total_facts": total,
                "categories": { "core": 0, "daily": 0, "conversation": 0 },
                "last_consolidation": null,
                "backlog_count": 0,
            }))
            .into_response();
        }
    };

    let conn = sqlite.conn.clone();
    let result = tokio::task::spawn_blocking(move || -> anyhow::Result<serde_json::Value> {
        let conn = conn.lock();

        let total: u32 =
            conn.query_row("SELECT COUNT(*) FROM memories", [], |r| r.get(0))?;

        let core: u32 = conn.query_row(
            "SELECT COUNT(*) FROM memories WHERE category = 'core'",
            [],
            |r| r.get(0),
        )?;
        let daily: u32 = conn.query_row(
            "SELECT COUNT(*) FROM memories WHERE category = 'daily'",
            [],
            |r| r.get(0),
        )?;
        let conversation: u32 = conn.query_row(
            "SELECT COUNT(*) FROM memories WHERE category = 'conversation'",
            [],
            |r| r.get(0),
        )?;

        let backlog_count: u32 =
            conn.query_row("SELECT COUNT(*) FROM consolidation_backlog", [], |r| {
                r.get(0)
            })?;

        Ok(serde_json::json!({
            "total_facts": total,
            "categories": {
                "core": core,
                "daily": daily,
                "conversation": conversation,
            },
            "last_consolidation": null,
            "backlog_count": backlog_count,
        }))
    })
    .await;

    match result {
        Ok(Ok(body)) => Json(body).into_response(),
        Ok(Err(e)) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Stats query failed: {e}")})),
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Stats task panicked: {e}")})),
        )
            .into_response(),
    }
}

/// GET /api/memory/detail/:id — full content lookup by memory ID
///
/// Returns the complete memory entry including content. Returns 404 if
/// no memory with the given ID exists.
pub async fn handle_api_memory_detail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> impl IntoResponse {
    if let Err(e) = require_pairing_auth(&state, &headers) {
        return e.into_response();
    }

    let sqlite = match state
        .mem
        .as_any()
        .downcast_ref::<crate::memory::SqliteMemory>()
    {
        Some(s) => s,
        None => {
            // Fallback: try trait get() treating the id as a key
            return match state.mem.get(&id).await {
                Ok(Some(entry)) => Json(serde_json::json!(entry)).into_response(),
                Ok(None) => (
                    StatusCode::NOT_FOUND,
                    Json(serde_json::json!({"error": "Memory not found"})),
                )
                    .into_response(),
                Err(e) => (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(serde_json::json!({"error": format!("Memory lookup failed: {e}")})),
                )
                    .into_response(),
            };
        }
    };

    let conn = sqlite.conn.clone();
    let id_clone = id.clone();
    let result = tokio::task::spawn_blocking(move || -> anyhow::Result<Option<serde_json::Value>> {
        let conn = conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, key, content, category, created_at, session_id, \
                    confidence, source, tags, access_count, last_accessed_at, decay_half_life_days \
             FROM memories WHERE id = ?1",
        )?;

        let mut rows = stmt.query_map(rusqlite::params![id_clone], |row| {
            Ok(serde_json::json!({
                "id": row.get::<_, String>(0)?,
                "key": row.get::<_, String>(1)?,
                "content": row.get::<_, String>(2)?,
                "category": row.get::<_, String>(3)?,
                "timestamp": row.get::<_, String>(4)?,
                "session_id": row.get::<_, Option<String>>(5)?,
                "confidence": row.get::<_, f64>(6)?,
                "source": row.get::<_, String>(7)?,
                "tags": row.get::<_, String>(8)?,
                "access_count": row.get::<_, u32>(9)?,
                "last_accessed_at": row.get::<_, Option<String>>(10)?,
                "decay_half_life_days": row.get::<_, i64>(11)?,
            }))
        })?;

        match rows.next() {
            Some(Ok(entry)) => Ok(Some(entry)),
            _ => Ok(None),
        }
    })
    .await;

    match result {
        Ok(Ok(Some(entry))) => Json(entry).into_response(),
        Ok(Ok(None)) => (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"error": "Memory not found"})),
        )
            .into_response(),
        Ok(Err(e)) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Memory detail failed: {e}")})),
        )
            .into_response(),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"error": format!("Memory detail task panicked: {e}")})),
        )
            .into_response(),
    }
}

