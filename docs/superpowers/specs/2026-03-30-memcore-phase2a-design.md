# MemCore Phase 2a: Intelligence — Brain Visualizer, LLM Consolidation, Leaderboard

**Status**: Approved Design
**Date**: 2026-03-30
**Prereq**: MemCore Phase 1 (merged to main at `9b6455ca`)
**Parent spec**: `docs/design/2026-03-29-memcore-spec.md` (§3.1 Gate 3, §3.2, §3.4, §4.3)

Copyright (c) 2026 Natali Caggiano (@Natfii). All rights reserved.

---

## 1. Scope

Phase 2a delivers five components:

| # | Component | Layer | Summary |
|---|-----------|-------|---------|
| 1 | Memory Brain Visualizer | Web (TS + Canvas) | Gut existing React SPA, rebuild as neural graph |
| 2 | Startup LLM Consolidation | Rust + Kotlin | Batch LLM extraction on app open for messages heuristics missed |
| 3 | Provider Leaderboard | Rust API + Web overlay | SQL aggregation over interaction_outcomes, bottom-sheet panel |
| 4 | Category-Aware Ranking Boosts | Rust (scoring.rs) | Core 1.5x, recent-24h 1.2x, frequent 1.1x multipliers |
| 5 | consolidation.rs | Rust | Backlog queue, prompt building, JSON parsing, Jaccard similarity, fact merging |

**Deferred to Phase 2b**: "Suggest Improvement" flow (PromptVariantEntity, diff UI, variant A/B tracking).

**Deferred to Phase 3**: Procedural memory (tool chain storage and replay).

---

## 2. Memory Brain Visualizer

### 2.1 Tech Stack

- **force-graph** (vasturiano) — Canvas 2D rendering + d3-force physics engine
- **Tailwind CSS 4** — UI overlay panels (leaderboard, node detail, filters)
- **@chenglou/pretext** — efficient text measurement for Canvas node labels without DOM reflow
- **Vite** — bundler (lightweight, keeps the existing build approach)
- **TypeScript** — no React, no framework. Vanilla TS compiled to a single bundle.

### 2.2 Visual Design — "Neural Cortex"

**Background**: Deep navy/black (#0a0e1a) with faint grid pattern evoking a neural scan.

**Nodes = Neurons**:
- Circular, glowing, sized by access count (more accessed = bigger)
- Color by source:
  - Heuristic: cyan (#06b6d4)
  - LLM: purple (#a855f7)
  - User: green (#22c55e)
  - Agent: amber (#f59e0b)
- Pulse animation on nodes accessed in the last 24 hours
- Core category nodes have a brighter outer glow ring
- Tap a node to open the detail panel

**Connections = Synapses**:
- Curved lines between nodes sharing tags or with Jaccard similarity > 0.3
- Line thickness proportional to similarity strength
- Subtle particle flow along connections (force-graph built-in `linkDirectionalParticles`)
- Dimmer when neither endpoint is hovered/selected

**Touch Interaction**:
- Drag neurons — they spring back with physics, connected nodes pull along
- Pinch to zoom, pan to navigate
- Tap node to expand detail panel
- Double-tap background to reset view

### 2.3 Overlay Panels

All panels are plain HTML + Tailwind, absolutely positioned over the Canvas element. Dark translucent backgrounds matching the Neural Cortex aesthetic.

**Node Detail Panel** (slides in from right on node tap):
- Key (title)
- Full content text
- Confidence bar (0-100%)
- Source badge (colored pill matching node color)
- Tags as small pills
- Category label
- Created/updated/last accessed timestamps
- Access count
- Close button or tap-away to dismiss

**Leaderboard Panel** (slides up from bottom, swipe gesture or button):
- Table: provider name, model, total interactions, success rate %, avg latency ms
- Success rate colored: green >80%, amber 50-80%, red <50%
- Sortable columns on tap
- Auto-refreshes when panel opens
- See §4 for data source

**Filter Bar** (fixed at top):
- Category filter dropdown (All, Core, Daily, Conversation, Custom)
- Source filter (All, Heuristic, LLM, User, Agent)
- Text search input
- Toggle for connection visibility

### 2.4 Data Flow

```
App open → WebView loads /_app/
  → GET /api/session (auth check)
  → GET /api/memory/graph
    Returns: { nodes: [...], links: [...] }
  → force-graph renders the neural cortex
  → SSE subscription for real-time memory events
    → New fact extracted → add node with entrance animation
    → Memory accessed → pulse the node
    → Fact updated → update node metadata
```

### 2.5 What Gets Deleted

Everything in `zeroclaw/web/src/` — all React components, pages, App.tsx, router config. The `package.json` gets rewritten with the new dependency set. `web/dist/` gets rebuilt from the new source.

Deleted pages: Dashboard, AgentChat, Tools, Cron, Integrations, Memory (old), Config, Cost, Logs, Doctor.

### 2.6 What Stays Untouched

- Gateway infrastructure: Axum server, pairing token auth, rate limiting, localhost binding
- `static_files.rs`: rust-embed serving from `web/dist/`, SPA fallback
- `WebDashboardScreen.kt` + `WebDashboardViewModel.kt`: WebView shell, token generation
- `sse.rs`: SSE infrastructure (repurposed for memory events)
- Webhook endpoints and auth

---

## 3. Startup LLM Consolidation

### 3.1 Trigger (Kotlin)

In `ZeroAIDaemonService.onCreate()`:

1. Check power state — if Critical (<20% or power save mode), skip entirely
2. Call `consolidation_backlog_count()` via FFI
3. If count >= 20 OR hours since last consolidation >= 4:
   - Fire `run_startup_consolidation()` on `Dispatchers.IO` (fire-and-forget)
   - Log the `FfiConsolidationReport` result

### 3.2 Rust Orchestrator

`run_consolidation(config, memory)` in `consolidation.rs`:

1. `should_consolidate()` — check backlog count + time threshold
2. Load backlog messages from `consolidation_backlog` table
3. `build_consolidation_prompts()` — chunk into batches of 30 messages
4. For each batch:
   - Call `Config::effective_provider()` with `RouteHint::Simple` (cheapest model)
   - `max_tokens = 4000` to accommodate reasoning models
   - `parse_consolidation_response()` on the result
   - Store extracted facts via `store_with_metadata()` (source = "llm", confidence = 0.8)
5. Clear processed messages from `consolidation_backlog`
6. 60-second total timeout — if exceeded, defer remainder to next startup

### 3.3 Consolidation Prompt Template

```
Extract facts about the user from these conversation excerpts.
Also summarize any conversations with 20+ messages.

Rules:
- Only extract facts the user stated or clearly implied
- Skip greetings, small talk, and things the AI said about itself
- Merge duplicate facts (keep the most recent version)
- Each fact needs: a short key, the content, and comma-separated tags
- Each summary needs: the session ID and a 1-2 sentence summary

Return ONLY this JSON (no markdown, no explanation):
{
  "facts": [
    {"key": "user_name", "content": "Natali", "tags": "identity,name"}
  ],
  "summaries": [
    {"session_id": "abc123", "summary": "Discussed SSH terminal setup."}
  ]
}

---CONVERSATIONS---
[Session: {id}, {date}]
User: ...
Assistant: ...
```

### 3.4 `needs_llm_extraction` — Backlog Table

New table (not a flag on existing tables):

```sql
CREATE TABLE IF NOT EXISTS consolidation_backlog (
    id TEXT PRIMARY KEY,
    session_id TEXT,
    message_text TEXT NOT NULL,
    created_at TEXT NOT NULL
)
```

- Created in `migrate_memcore_schema()` (idempotent, same pattern as Phase 1)
- Rows inserted by extraction pipeline: message is interesting (`InterestingnessFilter` = true) but heuristic extraction yields 0 facts
- Cleared after successful consolidation batch processing

### 3.5 Offline Behavior

Skip consolidation entirely. Heuristic facts from Phase 1 remain available. Retry next startup.

### 3.6 Backlog Overflow

If backlog exceeds 200 messages, cap at 200 (oldest first). This keeps consolidation under 7 LLM calls (~15-20 seconds). If total time exceeds 60 seconds, defer remainder.

---

## 4. Provider Leaderboard

### 4.1 Rust API Endpoint

`GET /api/memory/leaderboard` in `gateway/api.rs`:

```sql
SELECT provider, model,
       COUNT(*) as total,
       SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) * 100.0 / COUNT(*) as success_rate,
       AVG(latency_ms) as avg_latency
FROM interaction_outcomes
WHERE created_at > :thirty_days_ago
GROUP BY provider, model
ORDER BY success_rate DESC
```

Returns JSON: `[{ "provider": "anthropic", "model": "claude-sonnet-4-20250514", "total": 142, "success_rate": 87.3, "avg_latency": 1250 }]`

### 4.2 Data Source

No new tables or entities. Reads from `interaction_outcomes` which Phase 1 already populates via `EventBridge.recordInteractionOutcome()` on every `"agent_end"` event. The `InteractionOutcomeEntity` has all required fields: provider, model, outcome, latency_ms, created_at.

### 4.3 Room DB Access

`interaction_outcomes` lives in Room (`ZeroAIDatabase`), not in `brain.db`. The gateway needs read access to the Room database for the leaderboard query.

**Approach**: Pass the Room DB path to the gateway at startup. The gateway opens a read-only SQLite connection to the Room DB alongside its existing `brain.db` connection. The `AppState` struct gains a `room_db: Option<Connection>` field. The gateway already receives config at startup; the Room DB path is added as a parameter to `start_gateway()`.

No new Kotlin Room queries or DAOs needed — the gateway reads directly via raw SQL on the read-only connection.

---

## 5. Category-Aware Ranking Boosts

### 5.1 Changes to `scoring.rs`

New function:

```rust
pub fn apply_boosts(
    base_score: f64,
    category: &MemoryCategory,
    last_accessed_at: Option<&str>,
    access_count: u32,
) -> f64
```

| Condition | Multiplier |
|-----------|-----------|
| Category = Core | 1.5x |
| Accessed in last 24h | 1.2x |
| Access count > 5 | 1.1x |

- Boosts stack multiplicatively (max: 1.5 × 1.2 × 1.1 = 1.98x)
- Applied in `SqliteMemory::recall_scored()` after base `combined_score()` calculation
- Final score clamped to [0.0, 1.0] after boosting (normalize against max theoretical boost of 1.98)

### 5.2 Impact on Brain Visualizer

Boosted scores determine node size in the Neural Cortex — core identity facts that are frequently accessed appear as the largest, brightest neurons.

### 5.3 No New Files or FFI

Extends existing `scoring.rs` and wires into existing `recall_scored()` in `sqlite.rs`.

---

## 6. consolidation.rs Module

### 6.1 New File: `zeroclaw/src/memory/consolidation.rs` (~200 lines)

**Structs:**

```rust
pub struct ConsolidationResult {
    pub facts: Vec<ExtractedFact>,
    pub summaries: Vec<SessionSummary>,
}

pub struct SessionSummary {
    pub session_id: String,
    pub summary: String,
}
```

`ExtractedFact` is reused from `heuristic.rs` (fields: key, content, category, tags, confidence). The LLM consolidation path sets category to `Core` and confidence to `0.8` for all extracted facts.

**Functions:**

| Function | Purpose |
|----------|---------|
| `should_consolidate(flagged_count, last_consolidation, threshold) → bool` | Pure check: count >= threshold OR 4+ hours elapsed |
| `build_consolidation_prompts(messages, batch_size) → Vec<String>` | Chunk messages into batches, render prompt template |
| `parse_consolidation_response(response) → Option<ConsolidationResult>` | Lenient JSON parse with per-fact validation |
| `jaccard_similarity(a, b) → f64` | Word-level Jaccard over whitespace-split tokens |
| `merge_facts(older, newer) → MemoryEntry` | Keep newer content, preserve older's access_count/created_at, max confidence |

### 6.2 Jaccard Dual Use

`jaccard_similarity` is used in two places:
1. **Brain Visualizer links**: `GET /api/memory/graph` pre-computes links between all fact pairs with Jaccard > 0.3
2. **Duplicate detection**: Before storing LLM-extracted facts, check Jaccard against existing facts with the same tags. If > 0.7, merge instead of creating a new entry.

---

## 7. Gateway API Changes

### 7.1 Endpoints to Keep

| Endpoint | Purpose |
|----------|---------|
| `GET /api/session` | Auth check on WebView load |
| `GET /api/status` | System health for visualizer header |
| `POST /webhook` | External webhook (separate concern) |
| SSE endpoint | Repurposed for real-time memory events |

### 7.2 Endpoints to Add

| Endpoint | Returns |
|----------|---------|
| `GET /api/memory/graph` | `{ nodes: [FactNode], links: [SynapseLink] }` — all facts with metadata + pre-computed Jaccard links |
| `GET /api/memory/leaderboard` | `[{ provider, model, total, success_rate, avg_latency }]` |
| `GET /api/memory/stats` | `{ total_facts, categories: {}, last_consolidation, backlog_count }` |

### 7.3 Endpoints to Remove

| Endpoint | Reason |
|----------|--------|
| `GET/PUT /api/config` | TOML editor page removed |
| `GET /api/tools` | Tools listing page removed |
| `GET/POST/PUT/DELETE /api/cron/*` | Cron management page removed |
| `GET /api/cost/*` | Cost tracking page removed |
| `GET /api/health/*` | Detailed health page removed (basic status stays) |
| `POST /api/chat` | Agent chat page removed |
| `GET /api/logs` | Log streaming page removed |
| `GET /api/integrations` | Integrations page removed |

### 7.4 SSE Repurpose

Existing SSE infrastructure in `sse.rs` repurposed for memory events:
- `memory:fact_created` — new fact extracted (heuristic or LLM)
- `memory:fact_accessed` — memory recalled (triggers node pulse animation)
- `memory:consolidation_complete` — startup consolidation finished

---

## 8. FFI Surface

### 8.1 New FFI Functions (3)

| Function | Signature | Purpose |
|----------|-----------|---------|
| `add_to_consolidation_backlog` | `(session_id: String, message_text: String) → ()` | Queue message for LLM extraction |
| `consolidation_backlog_count` | `() → u32` | Count pending messages |
| `run_startup_consolidation` | `() → FfiConsolidationReport` | Execute batch LLM extraction |

### 8.2 New FFI Records (1)

```
FfiConsolidationReport {
    facts_extracted: u32,
    sessions_summarized: u32,
    errors: Vec<String>,
}
```

### 8.3 Existing FFI (No Changes)

All Phase 1 FFI functions remain unchanged. The gateway reads memory data through its existing `AppState` which holds a reference to `SqliteMemory`.

---

## 9. File Manifest

### 9.1 Delete

| Path | Reason |
|------|--------|
| `zeroclaw/web/src/**/*` | Entire React SPA — all pages, components, router, App.tsx |
| `zeroclaw/web/package.json` | Rewritten with new deps |
| `zeroclaw/web/package-lock.json` | Regenerated |
| `zeroclaw/web/tsconfig.json` | Rewritten for vanilla TS |
| `zeroclaw/web/tailwind.config.*` | Rewritten for Tailwind 4 |
| `zeroclaw/web/vite.config.*` | Rewritten for non-React build |
| `zeroclaw/web/index.html` | Rewritten |
| `zeroclaw/web/dist/*` | Rebuilt from new source |

### 9.2 Create (Rust)

| Path | ~LOC | Purpose |
|------|------|---------|
| `zeroclaw/src/memory/consolidation.rs` | ~200 | Consolidation logic, Jaccard, prompt building, JSON parsing |
| `zeroclaw/tests/consolidation_integration.rs` | ~150 | Integration tests for consolidation pipeline |

### 9.3 Create (Web — `zeroclaw/web/src/`)

| Path | ~LOC | Purpose |
|------|------|---------|
| `main.ts` | ~50 | Entry point, auth check, init graph |
| `graph.ts` | ~200 | force-graph setup, node/link styling, animations, touch handlers |
| `api.ts` | ~80 | Fetch wrappers for /api/memory/* endpoints + SSE subscription |
| `panels/detail.ts` | ~120 | Node detail slide-in panel |
| `panels/leaderboard.ts` | ~100 | Leaderboard bottom-sheet panel |
| `panels/filters.ts` | ~80 | Filter bar (category, source, search) |
| `types.ts` | ~40 | TypeScript interfaces for API responses |
| `style.css` | ~100 | Tailwind imports + Neural Cortex custom styles (glows, grid bg) |

### 9.4 Modify (Rust)

| Path | Change |
|------|--------|
| `zeroclaw/src/memory/mod.rs` | Add `pub mod consolidation` |
| `zeroclaw/src/memory/scoring.rs` | Add `apply_boosts()` function |
| `zeroclaw/src/memory/sqlite.rs` | Wire boosts into `recall_scored()`, add `consolidation_backlog` table migration |
| `zeroclaw/src/gateway/api.rs` | Remove 8 old endpoints, add 3 new memory endpoints |
| `zeroclaw/src/gateway/mod.rs` | Update route table |
| `zeroclaw/src/gateway/sse.rs` | Add memory event types |
| `zeroclaw-android/zeroclaw-ffi/src/lib.rs` | Add 3 consolidation FFI functions |
| `zeroclaw-android/zeroclaw-ffi/src/memory_browse.rs` | Add `FfiConsolidationReport` record |

### 9.5 Modify (Kotlin)

| Path | Change |
|------|--------|
| `app/.../memory/MemoryExtractionPipeline.kt` | Call `addToConsolidationBacklog()` when interesting but no heuristic match |
| `app/.../service/ZeroAIDaemonService.kt` | Add consolidation trigger in `onCreate()` |

### 9.6 No Changes

| Path | Why |
|------|-----|
| `app/.../ui/screen/settings/WebDashboardScreen.kt` | WebView shell unchanged |
| `app/.../ui/screen/settings/WebDashboardViewModel.kt` | Token generation unchanged |
| `zeroclaw/src/gateway/static_files.rs` | Still embeds `web/dist/`, serves `/_app/*` |
| `zeroclaw/Cargo.toml` (rust-embed, mime_guess) | Still needed for static file embedding |

---

## 10. Estimated Effort

| Component | Est. |
|-----------|------|
| Brain Visualizer (web rebuild) | 3d |
| consolidation.rs + backlog table | 1.5d |
| Startup consolidation trigger (Kotlin) | 0.5d |
| Gateway API changes (remove old, add new) | 1d |
| Ranking boosts (scoring.rs) | 0.5d |
| SSE memory events | 0.5d |
| Integration tests | 1.5d |
| **Total** | **~8.5d** |
