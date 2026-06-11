/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * Health snapshot for one on-device meta search engine, mirroring the Rust
 * `FfiSearchEngineHealth` type.
 *
 * One row exists per bundled engine spec; engines that have not been queried
 * this session report a "healthy" condition with no timestamps. The
 * underlying Rust timestamp fields are `u64` Unix seconds, mapped to [Long]
 * for JVM compatibility.
 *
 * @property engineId Engine spec id (e.g. "ddg_html").
 * @property displayName Human-readable engine name (e.g. "DuckDuckGo").
 * @property condition Condition string: "healthy", "backoff", or "layout_suspect".
 * @property lastError Most recent failure description, or `null` if none.
 * @property lastOkUnix Unix seconds of the last successful search, or `null` if never.
 * @property repairedAtUnix Unix seconds of the last adopted self-repair, or `null` if never.
 * @property backoffRemainingSecs Seconds of backoff remaining while the engine backs off,
 *   or `null` when it is not backing off.
 */
data class SearchEngineHealth(
    val engineId: String,
    val displayName: String,
    val condition: String,
    val lastError: String?,
    val lastOkUnix: Long?,
    val repairedAtUnix: Long?,
    val backoffRemainingSecs: Long?,
)
