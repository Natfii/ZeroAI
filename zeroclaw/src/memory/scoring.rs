// Copyright (c) 2026 @Natfii. All rights reserved.

//! Memory scoring functions for retrieval ranking and forgetting.
//!
//! Implements the three-factor scoring formula adapted from
//! Generative Agents (Park et al., UIST 2023) with Ebbinghaus
//! decay from MemoryBank (Zhong et al., AAAI 2024) and
//! category-aware half-lives from Kore (Auriti Labs).

use crate::memory::traits::MemoryCategory;
use chrono::{DateTime, Utc};

/// Ebbinghaus forgetting curve: `exp(-0.693 * days_since / half_life)`.
///
/// Returns a score in `[0.0, 1.0]` where 1.0 means just accessed.
///
/// * `last_accessed` - RFC 3339 timestamp, or `None` (returns 0.5).
/// * `half_life_days` - decay half-life in days, clamped to min 1.
pub fn recency_score(last_accessed: Option<&str>, half_life_days: i64) -> f64 {
    let half_life = half_life_days.max(1) as f64;

    let ts = match last_accessed {
        Some(s) => match s.parse::<DateTime<Utc>>() {
            Ok(dt) => dt,
            Err(_) => return 0.5,
        },
        None => return 0.5,
    };

    let now = Utc::now();
    let elapsed_days = (now - ts).num_seconds().max(0) as f64 / 86_400.0;

    (-0.693 * elapsed_days / half_life).exp().clamp(0.0, 1.0)
}

/// Capped linear frequency score: `min(1.0, access_count / 20.0)`.
///
/// Returns 0.0 for zero accesses, 1.0 for 20 or more.
pub fn frequency_score(access_count: u32) -> f64 {
    (access_count as f64 / 20.0).min(1.0)
}

/// Three-factor weighted score: `0.6 * hybrid + 0.25 * recency + 0.15 * frequency`.
///
/// The result is clamped to `[0.0, 1.0]`.
pub fn combined_score(hybrid: f64, recency: f64, frequency: f64) -> f64 {
    (0.6 * hybrid + 0.25 * recency + 0.15 * frequency).clamp(0.0, 1.0)
}

/// Returns the default Ebbinghaus half-life (in days) for a memory category.
///
/// * [`MemoryCategory::Core`] - 365
/// * [`MemoryCategory::Daily`] - 7
/// * [`MemoryCategory::Conversation`] - 1
/// * [`MemoryCategory::Custom`] - 90
pub fn category_half_life(category: &MemoryCategory) -> i64 {
    match category {
        MemoryCategory::Core => 365,
        MemoryCategory::Daily => 7,
        MemoryCategory::Conversation => 1,
        MemoryCategory::Custom(_) => 90,
    }
}

/// Returns `true` when a memory should be pruned (recency below threshold
/// and rarely accessed).
///
/// Prune when `recency < 0.05` **and** `access_count < 3`.
pub fn should_prune(recency: f64, access_count: u32) -> bool {
    recency < 0.05 && access_count < 3
}

/// Applies category-aware ranking boosts to a base score.
///
/// Boosts stack multiplicatively. Returns the raw boosted score without
/// normalization — callers use it for ranking only. For display, normalize
/// against the actual max score in the result set.
///
/// Boosts applied:
/// * [`MemoryCategory::Core`] — 1.5×
/// * Accessed within the last 24 hours — 1.2×
/// * `access_count > 5` — 1.1×
/// * Maximum combined multiplier: 1.98× (all three stacked)
///
/// # Parameters
///
/// * `base_score` — Pre-boost score, typically the output of [`combined_score`].
/// * `category` — Memory category; only [`MemoryCategory::Core`] receives a boost.
/// * `last_accessed_at` — RFC 3339 timestamp of last access, or `None` to skip
///   the recency boost.
/// * `access_count` — Total number of times this memory has been accessed.
pub fn apply_boosts(
    base_score: f64,
    category: &MemoryCategory,
    last_accessed_at: Option<&str>,
    access_count: u32,
) -> f64 {
    let mut score = base_score;

    // Core facts: 1.5×
    if matches!(category, MemoryCategory::Core) {
        score *= 1.5;
    }

    // Accessed in last 24h: 1.2×
    if let Some(ts) = last_accessed_at {
        if let Ok(parsed) = chrono::DateTime::parse_from_rfc3339(ts) {
            let hours = (chrono::Local::now() - parsed.with_timezone(&chrono::Local)).num_hours();
            if hours < 24 {
                score *= 1.2;
            }
        }
    }

    // Frequently accessed (>5): 1.1×
    if access_count > 5 {
        score *= 1.1;
    }

    score
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;

    fn ts_ago(days: i64) -> String {
        (Utc::now() - Duration::days(days))
            .to_rfc3339()
    }

    #[test]
    fn recency_score_just_accessed() {
        let now = Utc::now().to_rfc3339();
        let score = recency_score(Some(&now), 365);
        assert!(
            (score - 1.0).abs() < 0.01,
            "expected ~1.0, got {score}"
        );
    }

    #[test]
    fn recency_score_one_half_life() {
        let ts = ts_ago(365);
        let score = recency_score(Some(&ts), 365);
        assert!(
            (score - 0.5).abs() < 0.05,
            "expected ~0.5, got {score}"
        );
    }

    #[test]
    fn recency_score_two_half_lives() {
        let ts = ts_ago(730);
        let score = recency_score(Some(&ts), 365);
        assert!(
            (score - 0.25).abs() < 0.05,
            "expected ~0.25, got {score}"
        );
    }

    #[test]
    fn recency_score_never_accessed() {
        let score = recency_score(None, 365);
        assert!(
            (score - 0.5).abs() < f64::EPSILON,
            "expected exactly 0.5, got {score}"
        );
    }

    #[test]
    fn recency_score_daily_category() {
        let ts = ts_ago(7);
        let score = recency_score(Some(&ts), 7);
        assert!(
            (score - 0.5).abs() < 0.05,
            "expected ~0.5, got {score}"
        );
    }

    #[test]
    fn recency_score_conversation_expired() {
        let ts = ts_ago(5);
        let score = recency_score(Some(&ts), 1);
        assert!(
            score < 0.05,
            "expected < 0.05, got {score}"
        );
    }

    #[test]
    fn frequency_score_zero() {
        assert!((frequency_score(0) - 0.0).abs() < f64::EPSILON);
    }

    #[test]
    fn frequency_score_capped() {
        assert!((frequency_score(50) - 1.0).abs() < f64::EPSILON);
    }

    #[test]
    fn frequency_score_mid() {
        assert!((frequency_score(10) - 0.5).abs() < f64::EPSILON);
    }

    #[test]
    fn combined_score_weights() {
        let score = combined_score(1.0, 1.0, 1.0);
        assert!(
            (score - 1.0).abs() < f64::EPSILON,
            "expected 1.0 (clamped), got {score}"
        );
    }
}
