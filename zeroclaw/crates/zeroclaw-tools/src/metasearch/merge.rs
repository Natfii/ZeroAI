// Copyright (c) 2026 @Natfii. All rights reserved.

//! Cross-engine result fusion: URL-normalized deduplication plus weighted
//! reciprocal-rank scoring, so results confirmed by several engines rise to
//! the top without any engine-specific logic.

use super::executor::EngineResult;
use std::collections::HashMap;

/// Results from one engine, tagged for attribution and scoring.
#[derive(Debug, Clone)]
pub struct EngineBatch {
    /// Spec id of the contributing engine.
    pub engine_id: String,
    /// Human-readable engine name used in result attribution.
    pub display_name: String,
    /// Fusion weight from the engine spec.
    pub weight: f64,
    /// Results in the engine's own ranking order.
    pub results: Vec<EngineResult>,
}

/// One fused result with cross-engine attribution.
#[derive(Debug, Clone)]
pub struct RankedResult {
    /// Title from the first engine that surfaced this URL.
    pub title: String,
    /// Original (non-normalized) URL from the first contributing engine.
    pub url: String,
    /// Longest snippet seen across contributing engines.
    pub snippet: String,
    /// Display names of every engine that surfaced this URL.
    pub engines: Vec<String>,
    /// Weighted reciprocal-rank fusion score.
    pub score: f64,
}

/// Fuses per-engine batches into a single ranking.
///
/// Scoring is weighted reciprocal rank: each engine contributes
/// `weight / (rank + 1)` for a URL, so a result ranked highly by multiple
/// engines beats a result any single engine ranked first. Ties keep
/// first-seen order, which follows the bundled spec order.
pub fn merge_engine_results(batches: &[EngineBatch], max_results: usize) -> Vec<RankedResult> {
    let mut by_url: HashMap<String, usize> = HashMap::new();
    let mut fused: Vec<RankedResult> = Vec::new();

    for batch in batches {
        for (rank, result) in batch.results.iter().enumerate() {
            let key = normalize_url_for_dedup(&result.url);
            let contribution = batch.weight / (rank as f64 + 1.0);
            match by_url.get(&key) {
                Some(&index) => {
                    let entry = &mut fused[index];
                    entry.score += contribution;
                    if !entry.engines.contains(&batch.display_name) {
                        entry.engines.push(batch.display_name.clone());
                    }
                    if result.snippet.len() > entry.snippet.len() {
                        entry.snippet = result.snippet.clone();
                    }
                }
                None => {
                    by_url.insert(key, fused.len());
                    fused.push(RankedResult {
                        title: result.title.clone(),
                        url: result.url.clone(),
                        snippet: result.snippet.clone(),
                        engines: vec![batch.display_name.clone()],
                        score: contribution,
                    });
                }
            }
        }
    }

    fused.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
    fused.truncate(max_results);
    fused
}

/// Tracking query parameters stripped before URL comparison.
const TRACKING_PARAMS: &[&str] = &["fbclid", "gclid", "msclkid", "ref", "ref_src"];

/// Canonicalizes a URL into a deduplication key: scheme-insensitive,
/// lowercase host, no fragment, no tracking parameters, no trailing slash.
fn normalize_url_for_dedup(raw: &str) -> String {
    let Ok(url) = reqwest::Url::parse(raw) else {
        return raw.trim().to_ascii_lowercase();
    };
    let host = url.host_str().unwrap_or_default().to_ascii_lowercase();
    let host = host.strip_prefix("www.").unwrap_or(&host);
    let path = url.path().trim_end_matches('/');
    let mut kept_params: Vec<(String, String)> = url
        .query_pairs()
        .filter(|(name, _)| {
            !TRACKING_PARAMS.contains(&name.as_ref()) && !name.starts_with("utm_")
        })
        .map(|(name, value)| (name.into_owned(), value.into_owned()))
        .collect();
    kept_params.sort();
    let mut key = format!("{host}{path}");
    if !kept_params.is_empty() {
        key.push('?');
        for (index, (name, value)) in kept_params.iter().enumerate() {
            if index > 0 {
                key.push('&');
            }
            key.push_str(name);
            key.push('=');
            key.push_str(value);
        }
    }
    key
}

#[cfg(test)]
mod tests {
    use super::*;

    fn result(title: &str, url: &str, snippet: &str) -> EngineResult {
        EngineResult {
            title: title.into(),
            url: url.into(),
            snippet: snippet.into(),
        }
    }

    fn batch(id: &str, weight: f64, results: Vec<EngineResult>) -> EngineBatch {
        EngineBatch {
            engine_id: id.into(),
            display_name: id.to_uppercase(),
            weight,
            results,
        }
    }

    #[test]
    fn duplicate_urls_merge_across_engines() {
        let merged = merge_engine_results(
            &[
                batch("a", 1.0, vec![result("Rust", "https://www.rust-lang.org/", "short")]),
                batch(
                    "b",
                    1.0,
                    vec![result(
                        "Rust Lang",
                        "https://rust-lang.org?utm_source=x",
                        "a much longer snippet",
                    )],
                ),
            ],
            10,
        );
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].engines, vec!["A", "B"]);
        assert_eq!(merged[0].snippet, "a much longer snippet");
        assert_eq!(merged[0].title, "Rust");
    }

    #[test]
    fn multi_engine_confirmation_outranks_single_first_place() {
        let merged = merge_engine_results(
            &[
                batch(
                    "a",
                    1.0,
                    vec![
                        result("Only A", "https://only-a.example", ""),
                        result("Shared", "https://shared.example", ""),
                    ],
                ),
                batch("b", 1.0, vec![result("Shared", "https://shared.example/", "")]),
            ],
            10,
        );
        assert_eq!(merged[0].url, "https://shared.example");
        assert!(merged[0].score > merged[1].score);
    }

    #[test]
    fn weight_scales_contribution() {
        let merged = merge_engine_results(
            &[
                batch("light", 0.5, vec![result("L", "https://l.example", "")]),
                batch("heavy", 2.0, vec![result("H", "https://h.example", "")]),
            ],
            10,
        );
        assert_eq!(merged[0].url, "https://h.example");
    }

    #[test]
    fn truncates_to_max_results() {
        let results: Vec<EngineResult> = (0..8)
            .map(|i| result(&format!("R{i}"), &format!("https://r{i}.example"), ""))
            .collect();
        let merged = merge_engine_results(&[batch("a", 1.0, results)], 3);
        assert_eq!(merged.len(), 3);
    }

    #[test]
    fn normalize_strips_tracking_and_trailing_slash() {
        assert_eq!(
            normalize_url_for_dedup("https://Example.com/page/?utm_campaign=x&gclid=1"),
            "example.com/page"
        );
        assert_eq!(
            normalize_url_for_dedup("http://www.example.com/page"),
            "example.com/page"
        );
    }

    #[test]
    fn normalize_keeps_meaningful_query_params() {
        assert_eq!(
            normalize_url_for_dedup("https://example.com/search?b=2&a=1"),
            "example.com/search?a=1&b=2"
        );
    }

    #[test]
    fn empty_batches_merge_to_empty() {
        assert!(merge_engine_results(&[], 5).is_empty());
    }
}
