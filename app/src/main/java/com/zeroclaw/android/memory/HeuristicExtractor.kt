/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.memory

import com.zeroclaw.ffi.FfiExtractedFact

/**
 * Thin FFI wrapper for Rust-side heuristic fact extraction.
 *
 * Delegates to Rust `extract_facts` via UniFFI so Telegram/Discord
 * channel messages are extracted without an FFI round-trip from Kotlin.
 * The REPL/Terminal path calls this wrapper for consistency.
 */
object HeuristicExtractor {
    /**
     * Extracts facts from a user message using 8 regex rules.
     *
     * Runs in ~50us with zero network cost. Safe to call on any thread.
     *
     * @param userMessage Raw user message text.
     * @return List of extracted [FfiExtractedFact] entries, empty if no patterns match.
     * @throws com.zeroclaw.ffi.FfiException if the native layer reports an error.
     */
    fun extract(userMessage: String): List<FfiExtractedFact> =
        // FFI name resolved by UniFFI from Rust `extract_facts`
        com.zeroclaw.ffi.extractFacts(userMessage)
}
