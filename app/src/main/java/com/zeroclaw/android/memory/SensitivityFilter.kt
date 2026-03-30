/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.memory

/**
 * Blocks storage of sensitive content.
 *
 * Scans for API keys, tokens, passwords, credit card numbers,
 * and SSNs. Inspired by Hermes Agent's injection scanning
 * (https://github.com/NousResearch/hermes-agent).
 */
object SensitivityFilter {
    private val PATTERNS =
        listOf(
            // OpenAI keys
            Regex("""sk-[A-Za-z0-9]{20,}"""),
            // AWS access key IDs
            Regex("""AKIA[A-Z0-9]{16}"""),
            // GitHub tokens (classic + fine-grained)
            Regex("""(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{36,}"""),
            // Google API keys
            Regex("""AIzaSy[A-Za-z0-9_-]{33}"""),
            // Slack tokens
            Regex("""xox[bporas]-[A-Za-z0-9-]+"""),
            // PEM private key blocks
            Regex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"""),
            // Credit card numbers (with or without delimiters)
            Regex("""\b(?:\d[ -]?){13,16}\b"""),
            // SSN (with hyphens)
            Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
            // SSN (without hyphens, 9 digits)
            Regex("""\b\d{9}\b"""),
            // Generic high-entropy tokens (40+ alphanumeric chars)
            Regex("""[A-Za-z0-9/+]{40,}"""),
            // Password patterns
            Regex("""(?i)\b(?:my )?password\s+is\s+\S+"""),
            // Bearer tokens
            Regex("""Bearer\s+eyJ[A-Za-z0-9_-]+"""),
        )

    /**
     * Returns true if the content contains sensitive data
     * that should NOT be stored in memory.
     *
     * @param content Text to scan for sensitive patterns.
     * @return True if any sensitive pattern matches.
     */
    fun containsSensitive(content: String): Boolean = PATTERNS.any { it.containsMatchIn(content) }
}
