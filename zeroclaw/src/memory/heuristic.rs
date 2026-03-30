// Copyright (c) 2026 @Natfii. All rights reserved.

//! Heuristic fact extraction from user messages.
//!
//! Runs 8 regex rules in ~50μs per message. Zero network cost.
//! Lives in Rust so Telegram/Discord channel messages are extracted
//! without an FFI round-trip.
//!
//! Inspired by Mem0's passive extraction pipeline
//! (<https://github.com/mem0ai/mem0>) adapted to regex-only.

use regex::Regex;
use std::sync::LazyLock;

/// A fact extracted by heuristic pattern matching.
#[derive(Debug, Clone, PartialEq)]
pub struct ExtractedFact {
    /// Key identifying the fact (e.g. `user_name`, `preference_a1b2c3d4`).
    pub key: String,
    /// The extracted content value.
    pub content: String,
    /// Category grouping — always `"core"` for heuristic rules.
    pub category: String,
    /// Comma-separated tags describing the fact.
    pub tags: String,
    /// Confidence score in `[0.0, 1.0]`.
    pub confidence: f64,
}

/// Internal compiled rule used at runtime.
struct CompiledRule {
    pattern: Regex,
    key_template: &'static str,
    category: &'static str,
    tags: &'static str,
    confidence: f64,
}

/// Rule definitions compiled once on first access.
static RULES: LazyLock<Vec<CompiledRule>> = LazyLock::new(|| {
    let defs: &[(&str, &str, &str, &str, f64)] = &[
        // 1. Name
        (
            r"(?i)(?:my name is|i'm|i am)\s+([A-Z][a-zA-Z]*(?:\s+[A-Z][a-zA-Z]*)*)",
            "user_name",
            "core",
            "identity,name",
            0.9,
        ),
        // 2. Location
        (
            r"(?i)(?:i live in|i'm from|i am based in)\s+(.+?)(?:\.|,|$)",
            "user_location",
            "core",
            "identity,location",
            0.9,
        ),
        // 3. Preference
        (
            r"(?i)(?:i prefer\s+(.+?)(?:\.|,|$)|my fav(?:ou?rite)\s+\S+\s+is\s+(.+?)(?:\.|,|$))",
            "preference_{hash}",
            "core",
            "preference",
            0.9,
        ),
        // 4. Tool
        (
            r"(?i)(?:i use|i work with|i develop in)\s+(.+?)(?:\.|,|$)",
            "tool_{hash}",
            "core",
            "preference,tool",
            0.9,
        ),
        // 5. Explicit note
        (
            r"(?i)(?:remember that|don't forget|keep in mind|note that)\s+(.+?)(?:\.|$)",
            "user_note_{hash}",
            "core",
            "explicit_note",
            1.0,
        ),
        // 6. Timezone
        (
            r"(?i)(?:my timezone is|i'm in\s+(.+?)\s+timezone)\s*(.+?)(?:\.|,|$)",
            "user_timezone",
            "core",
            "identity,timezone",
            0.9,
        ),
        // 7. Language
        (
            r"(?i)(?:i speak|my language is)\s+(.+?)(?:\.|,|$)",
            "user_language",
            "core",
            "identity,language",
            0.9,
        ),
        // 8. Preferred name
        (
            r"(?i)(?:call me|address me as)\s+(.+?)(?:\.|,|$)",
            "user_preferred_name",
            "core",
            "identity,name",
            1.0,
        ),
    ];

    defs.iter()
        .map(|(pat, key, cat, tags, conf)| CompiledRule {
            pattern: Regex::new(pat).expect("heuristic regex must compile"),
            key_template: key,
            category: cat,
            tags,
            confidence: *conf,
        })
        .collect()
});

/// Compute a simple hash of content for use in dynamic keys.
fn content_hash(content: &str) -> String {
    let h = content
        .as_bytes()
        .iter()
        .fold(0u32, |h, &b| h.wrapping_mul(31).wrapping_add(b as u32));
    format!("{h:08x}")
}

/// Extracts facts from a user message using 8 regex rules.
///
/// Returns empty vec if no patterns match. Runs in ~50μs.
/// Inspired by Mem0's passive extraction, adapted to regex-only
/// for zero network cost.
pub fn extract_facts(user_message: &str) -> Vec<ExtractedFact> {
    let mut facts = Vec::new();

    for rule in RULES.iter() {
        if let Some(caps) = rule.pattern.captures(user_message) {
            // Find the first non-empty capture group
            let content = (1..caps.len())
                .filter_map(|i| caps.get(i))
                .map(|m| m.as_str().trim())
                .find(|s| !s.is_empty());

            let content = match content {
                Some(c) => c.to_string(),
                None => continue,
            };

            let key = if rule.key_template.contains("{hash}") {
                rule.key_template.replace("{hash}", &content_hash(&content))
            } else {
                rule.key_template.to_string()
            };

            facts.push(ExtractedFact {
                key,
                content,
                category: rule.category.to_string(),
                tags: rule.tags.to_string(),
                confidence: rule.confidence,
            });
        }
    }

    facts
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_name_simple() {
        let facts = extract_facts("My name is Natali");
        assert_eq!(facts.len(), 1);
        assert_eq!(facts[0].key, "user_name");
        assert_eq!(facts[0].content, "Natali");
    }

    #[test]
    fn extract_name_full() {
        let facts = extract_facts("I'm Natali Caggiano");
        assert_eq!(facts.len(), 1);
        assert_eq!(facts[0].key, "user_name");
        assert_eq!(facts[0].content, "Natali Caggiano");
    }

    #[test]
    fn extract_location() {
        let facts = extract_facts("I live in Seattle");
        assert_eq!(facts.len(), 1);
        assert_eq!(facts[0].key, "user_location");
        assert_eq!(facts[0].content, "Seattle");
    }

    #[test]
    fn extract_preference() {
        let facts = extract_facts("I prefer dark mode");
        assert_eq!(facts.len(), 1);
        assert!(facts[0].key.starts_with("preference_"));
        assert!(facts[0].content.contains("dark mode"));
    }

    #[test]
    fn extract_tool() {
        let facts = extract_facts("I use Rust and Kotlin");
        assert_eq!(facts.len(), 1);
        assert!(facts[0].key.starts_with("tool_"));
        assert!(facts[0].content.contains("Rust and Kotlin"));
    }

    #[test]
    fn extract_remember() {
        let facts = extract_facts("Remember that the deploy key is rotated monthly");
        assert_eq!(facts.len(), 1);
        assert!(facts[0].key.starts_with("user_note_"));
        assert_eq!(facts[0].confidence, 1.0);
    }

    #[test]
    fn extract_timezone() {
        let facts = extract_facts("My timezone is America/Los_Angeles");
        assert_eq!(facts.len(), 1);
        assert_eq!(facts[0].key, "user_timezone");
    }

    #[test]
    fn extract_language() {
        let facts = extract_facts("I speak Portuguese");
        assert_eq!(facts.len(), 1);
        assert_eq!(facts[0].key, "user_language");
        assert_eq!(facts[0].content, "Portuguese");
    }

    #[test]
    fn extract_preferred_name() {
        let facts = extract_facts("Call me Nat");
        assert_eq!(facts.len(), 1);
        assert_eq!(facts[0].key, "user_preferred_name");
        assert_eq!(facts[0].confidence, 1.0);
    }

    #[test]
    fn extract_nothing() {
        let facts = extract_facts("How do I fix this bug?");
        assert!(facts.is_empty());
    }

    #[test]
    fn extract_multiple() {
        let facts = extract_facts("I'm Natali, I live in Seattle, I use Kotlin");
        assert_eq!(facts.len(), 3);
    }

    #[test]
    fn extract_case_insensitive() {
        let facts = extract_facts("MY NAME IS NATALI");
        assert!(!facts.is_empty());
        assert_eq!(facts[0].key, "user_name");
    }
}
