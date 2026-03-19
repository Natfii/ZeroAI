// Copyright (c) 2026 @Natfii. All rights reserved.

use crate::config::RoutingConfig;
use regex::Regex;
use std::sync::LazyLock;

/// Fenced code blocks: ``` ... ```
static CODE_FENCE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?s)```.*?```").unwrap());

/// Inline code: `something`
static INLINE_CODE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"`[^`]+`").unwrap());

/// JSON-like structure: { "key": ... }
static JSON_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"(?s)\{.*?"[^"]+"\s*:"#).unwrap());

/// XML/HTML tags.
static XML_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"<[a-zA-Z][a-zA-Z0-9]*[\s>]").unwrap());

/// SQL keywords at word boundaries.
static SQL_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| {
        Regex::new(r"(?i)\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP)\b").unwrap()
    });

/// LaTeX math notation.
static MATH_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\$[^$]+\$|\\(frac|sqrt|sum|int|begin)\{").unwrap());

/// Tool-use action verbs.
static TOOL_USE_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| {
        Regex::new(
            r"(?i)\b(search for|look\s+up|calculate|fetch|find all|run|execute|check the)\b",
        )
        .unwrap()
    });

/// Creative task verbs.
static CREATIVE_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| {
        Regex::new(
            r"(?i)\b(write a|write me|compose|brainstorm|imagine|invent|draft a|come up with|create a story|make up)\b",
        )
        .unwrap()
    });

/// Simple factual question patterns.
static SIMPLE_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| {
        Regex::new(
            r"(?i)\b(what is|what's|define|who is|who's|when did|when was|how many|how much|translate|name the|list the|list all)\b",
        )
        .unwrap()
    });

/// Greetings (must match entire trimmed message).
static GREETING_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| {
        Regex::new(
            r"(?i)^(hi|hello|hey|howdy|good morning|good afternoon|good evening|sup|yo|what's up|thanks|thank you|ok|okay)\s*[!?.]*$",
        )
        .unwrap()
    });

/// Complex reasoning verbs.
static COMPLEX_VERB_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| {
        Regex::new(
            r"(?i)\b(explain|compare|contrast|analyze|evaluate|synthesize|design|implement|debug|refactor|optimize|prove|derive|why does|how does)\b",
        )
        .unwrap()
    });

/// Multi-part question indicators.
static MULTI_PART_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| {
        Regex::new(
            r"(?im)(^\s*\d+[.)]\s)|(\b(additionally|furthermore|also|moreover|first[\s,].*second|and also)\b)",
        )
        .unwrap()
    });

/// Constraint-heavy language.
static CONSTRAINT_PATTERN: LazyLock<Regex> =
    LazyLock::new(|| {
        Regex::new(
            r"(?i)\b(do not|don't|without|except|only if|make sure|must not|avoid|never)\b",
        )
        .unwrap()
    });

/// Messages shorter than this word count are likely simple.
const SHORT_MESSAGE_WORDS: usize = 12;

/// Messages longer than this word count are likely complex.
const LONG_MESSAGE_WORDS: usize = 200;

/// Ambiguity score threshold: at or above this → Complex.
const AMBIGUITY_THRESHOLD: i32 = 2;

/// Classifies a user message into a [`RouteHint`] complexity tier.
///
/// This is the Rust port of the Kotlin `HeuristicClassifier`. It runs in
/// microseconds using pre-compiled regexes. Classification priority
/// (first match wins):
///
/// 1. Code / structured data → [`RouteHint::Complex`]
/// 2. Tool-use verbs → [`RouteHint::ToolUse`]
/// 3. Creative verbs → [`RouteHint::Creative`]
/// 4. Greetings → [`RouteHint::Simple`]
/// 5. Short + factual pattern → [`RouteHint::Simple`]
/// 6. Complex verbs / multi-part / long → [`RouteHint::Complex`]
/// 7. Weighted scoring for ambiguous middle ground
/// 8. Default: short → Simple, else → Complex
///
/// The asymmetric default-to-complex strategy is intentional: sending a
/// simple query to a strong model costs a few extra cents, but sending a
/// complex query to a weak model produces a bad answer.
pub fn classify(message: &str) -> RouteHint {
    let trimmed = message.trim();

    let hint = if trimmed.is_empty() {
        RouteHint::Simple
    } else if has_code_or_structured_data(trimmed) {
        RouteHint::Complex
    } else if TOOL_USE_PATTERN.is_match(trimmed) {
        RouteHint::ToolUse
    } else if CREATIVE_PATTERN.is_match(trimmed) {
        RouteHint::Creative
    } else if GREETING_PATTERN.is_match(trimmed) {
        RouteHint::Simple
    } else {
        let word_count = trimmed.split_whitespace().count();

        if word_count <= SHORT_MESSAGE_WORDS && SIMPLE_PATTERN.is_match(trimmed) {
            RouteHint::Simple
        } else if COMPLEX_VERB_PATTERN.is_match(trimmed) {
            RouteHint::Complex
        } else if MULTI_PART_PATTERN.is_match(trimmed) {
            RouteHint::Complex
        } else if word_count > LONG_MESSAGE_WORDS {
            RouteHint::Complex
        } else {
            let mut score: i32 = 0;
            if CONSTRAINT_PATTERN.is_match(trimmed) {
                score += 2;
            }
            if word_count > 50 {
                score += 1;
            }
            if trimmed.matches('?').count() >= 2 {
                score += 2;
            }
            if score >= AMBIGUITY_THRESHOLD {
                RouteHint::Complex
            } else if word_count <= SHORT_MESSAGE_WORDS {
                RouteHint::Simple
            } else {
                RouteHint::Complex
            }
        }
    };

    tracing::debug!(message_len = trimmed.len(), ?hint, "Message classified");
    hint
}

/// Checks for code blocks, structured data, or formal notation.
fn has_code_or_structured_data(text: &str) -> bool {
    CODE_FENCE.is_match(text)
        || INLINE_CODE.is_match(text)
        || JSON_PATTERN.is_match(text)
        || XML_PATTERN.is_match(text)
        || SQL_PATTERN.is_match(text)
        || MATH_PATTERN.is_match(text)
}

/// Message complexity classification for provider routing.
///
/// Passed from the Kotlin triage layer to influence which provider
/// handles the request. The Rust cascade uses this to select the
/// preferred provider order for each tier.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RouteHint {
    /// Simple factual lookups, greetings, short answers.
    /// Prefer cheapest/fastest provider.
    Simple,
    /// Multi-step reasoning, code generation, analysis.
    /// Prefer most capable provider.
    Complex,
    /// Creative writing, brainstorming, open-ended generation.
    /// Prefer user's default provider.
    Creative,
    /// Requires function/tool calling capability.
    /// Prefer providers with native tool support.
    ToolUse,
}

impl RouteHint {
    /// Parses a route hint from the string passed across FFI.
    ///
    /// Returns `None` for unrecognized values, letting the caller
    /// fall back to default routing.
    pub fn from_ffi(s: &str) -> Option<Self> {
        match s {
            "simple" => Some(Self::Simple),
            "complex" => Some(Self::Complex),
            "creative" => Some(Self::Creative),
            "tool_use" => Some(Self::ToolUse),
            _ => None,
        }
    }

    /// Returns the preferred provider order for this hint tier.
    ///
    /// If the routing config has no entries for this tier, returns an empty
    /// slice, signaling the caller to use the default provider.
    pub fn preferred_providers<'a>(&self, routing: &'a RoutingConfig) -> &'a [String] {
        match self {
            Self::Simple => &routing.simple,
            Self::Complex => &routing.complex,
            Self::Creative => &routing.creative,
            Self::ToolUse => &routing.tool_use,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_valid_hints() {
        assert_eq!(RouteHint::from_ffi("simple"), Some(RouteHint::Simple));
        assert_eq!(RouteHint::from_ffi("complex"), Some(RouteHint::Complex));
        assert_eq!(RouteHint::from_ffi("creative"), Some(RouteHint::Creative));
        assert_eq!(RouteHint::from_ffi("tool_use"), Some(RouteHint::ToolUse));
    }

    #[test]
    fn parse_unknown_returns_none() {
        assert_eq!(RouteHint::from_ffi(""), None);
        assert_eq!(RouteHint::from_ffi("unknown"), None);
        assert_eq!(RouteHint::from_ffi("SIMPLE"), None);
    }

    #[test]
    fn preferred_providers_returns_configured_list() {
        let routing = RoutingConfig {
            simple: vec!["gemini".into(), "ollama".into()],
            complex: vec!["anthropic".into()],
            creative: vec![],
            tool_use: vec!["openai".into()],
        };
        assert_eq!(
            RouteHint::Simple.preferred_providers(&routing),
            &["gemini", "ollama"],
        );
        assert_eq!(
            RouteHint::Complex.preferred_providers(&routing),
            &["anthropic"],
        );
        assert!(RouteHint::Creative.preferred_providers(&routing).is_empty());
        assert_eq!(
            RouteHint::ToolUse.preferred_providers(&routing),
            &["openai"],
        );
    }

    // --- classify() tests ---

    #[test]
    fn classify_empty_is_simple() {
        assert_eq!(classify(""), RouteHint::Simple);
        assert_eq!(classify("   "), RouteHint::Simple);
    }

    #[test]
    fn classify_fenced_code_is_complex() {
        let msg = "Can you fix this?\n```kotlin\nfun main() {}\n```";
        assert_eq!(classify(msg), RouteHint::Complex);
    }

    #[test]
    fn classify_inline_code_is_complex() {
        assert_eq!(classify("What does `listOf()` return?"), RouteHint::Complex);
    }

    #[test]
    fn classify_json_is_complex() {
        let msg = r#"Parse this: {"name": "test", "value": 42}"#;
        assert_eq!(classify(msg), RouteHint::Complex);
    }

    #[test]
    fn classify_sql_is_complex() {
        assert_eq!(
            classify("SELECT * FROM users WHERE active = true"),
            RouteHint::Complex,
        );
    }

    #[test]
    fn classify_search_is_tool_use() {
        assert_eq!(classify("Search for restaurants near me"), RouteHint::ToolUse);
    }

    #[test]
    fn classify_calculate_is_tool_use() {
        assert_eq!(classify("Calculate 15% tip on $47.50"), RouteHint::ToolUse);
    }

    #[test]
    fn classify_look_up_is_tool_use() {
        assert_eq!(classify("Look up the weather in Tokyo"), RouteHint::ToolUse);
    }

    #[test]
    fn classify_write_story_is_creative() {
        assert_eq!(
            classify("Write a story about a robot dog"),
            RouteHint::Creative,
        );
    }

    #[test]
    fn classify_brainstorm_is_creative() {
        assert_eq!(
            classify("Brainstorm names for my startup"),
            RouteHint::Creative,
        );
    }

    #[test]
    fn classify_greeting_is_simple() {
        assert_eq!(classify("Hello"), RouteHint::Simple);
        assert_eq!(classify("hey!"), RouteHint::Simple);
        assert_eq!(classify("thanks"), RouteHint::Simple);
    }

    #[test]
    fn classify_short_factual_is_simple() {
        assert_eq!(
            classify("What is the capital of France?"),
            RouteHint::Simple,
        );
    }

    #[test]
    fn classify_define_is_simple() {
        assert_eq!(classify("Define photosynthesis"), RouteHint::Simple);
    }

    #[test]
    fn classify_who_is_simple() {
        assert_eq!(classify("Who is Alan Turing?"), RouteHint::Simple);
    }

    #[test]
    fn classify_explain_is_complex() {
        assert_eq!(
            classify("Explain how transformers work in machine learning"),
            RouteHint::Complex,
        );
    }

    #[test]
    fn classify_compare_is_complex() {
        assert_eq!(
            classify("Compare React and Vue for a large enterprise app"),
            RouteHint::Complex,
        );
    }

    #[test]
    fn classify_numbered_list_is_complex() {
        let msg = "1) What is Rust? 2) How does it compare to C++? 3) Should I use it?";
        assert_eq!(classify(msg), RouteHint::Complex);
    }

    #[test]
    fn classify_long_message_is_complex() {
        let msg = "word ".repeat(201);
        assert_eq!(classify(&msg), RouteHint::Complex);
    }

    #[test]
    fn classify_ambiguous_medium_defaults_complex() {
        let msg =
            "I need help with my project, there are several issues and I'm not sure where to start";
        assert_eq!(classify(msg), RouteHint::Complex);
    }
}
