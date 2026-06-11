// Copyright (c) 2026 @Natfii. All rights reserved.

//! Model-free validation gate for repair candidates.
//!
//! Every candidate spec — whether derived by wrapper induction or by a
//! model — must reproduce the golden contract against a real probe body
//! before it can be adopted. A bad model can therefore waste a repair
//! attempt, but can never degrade the engine set.

use crate::metasearch::executor::{self, EngineResult};
use crate::metasearch::spec::EngineSpec;
use std::collections::HashSet;

/// Minimum results a candidate must extract from the golden probe body.
pub const MIN_GOLDEN_RESULTS: usize = 3;

/// Validates a candidate spec against the golden probe body it must parse.
///
/// Checks structural validity, a minimum result count, URL diversity, and —
/// the known-answer core — presence of the golden expected domain.
pub fn gate(candidate: &EngineSpec, golden_body: &str) -> anyhow::Result<()> {
    candidate.validate()?;
    let results = executor::parse_response(candidate, golden_body, 10)
        .map_err(|failure| {
            anyhow::Error::msg(format!("candidate failed to parse golden body: {failure}"))
        })?;
    anyhow::ensure!(
        results.len() >= MIN_GOLDEN_RESULTS,
        "candidate extracted only {} results (need {MIN_GOLDEN_RESULTS})",
        results.len()
    );
    let distinct_urls: HashSet<&str> = results.iter().map(|r| r.url.as_str()).collect();
    anyhow::ensure!(
        distinct_urls.len() >= MIN_GOLDEN_RESULTS,
        "candidate extracted only {} distinct URLs",
        distinct_urls.len()
    );
    anyhow::ensure!(
        golden_hit(&results, &candidate.golden.expected_domain),
        "golden domain '{}' missing from candidate results",
        candidate.golden.expected_domain
    );
    Ok(())
}

/// Whether any result URL lands on the expected golden domain.
pub fn golden_hit(results: &[EngineResult], expected_domain: &str) -> bool {
    results.iter().any(|r| r.url.contains(expected_domain))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metasearch::spec::bundled_specs;

    const DDG_FIXTURE: &str = include_str!("../fixtures/ddg_html.html");

    fn ddg_spec() -> EngineSpec {
        bundled_specs()
            .unwrap()
            .into_iter()
            .find(|s| s.id == "ddg_html")
            .unwrap()
    }

    #[test]
    fn bundled_spec_passes_gate_on_real_fixture() {
        // The fixture was captured for "rust programming language" and
        // includes an en.wikipedia.org result, satisfying the bundled
        // golden contract (expected_domain = wikipedia.org).
        gate(&ddg_spec(), DDG_FIXTURE).unwrap();
    }

    #[test]
    fn gate_rejects_candidate_matching_nothing() {
        let mut candidate = ddg_spec();
        if let Some(html) = candidate.response.html.as_mut() {
            html.result_selector = "div.does-not-exist".into();
        }
        let err = gate(&candidate, DDG_FIXTURE).unwrap_err();
        assert!(err.to_string().contains("results"), "got: {err}");
    }

    #[test]
    fn gate_rejects_candidate_missing_golden_domain() {
        let mut candidate = ddg_spec();
        candidate.golden.expected_domain = "definitely-not-present.example".into();
        let err = gate(&candidate, DDG_FIXTURE).unwrap_err();
        assert!(err.to_string().contains("golden domain"), "got: {err}");
    }

    #[test]
    fn gate_rejects_structurally_invalid_candidate() {
        let mut candidate = ddg_spec();
        if let Some(html) = candidate.response.html.as_mut() {
            html.title_selector = ":::broken".into();
        }
        assert!(gate(&candidate, DDG_FIXTURE).is_err());
    }

    #[test]
    fn golden_hit_matches_domain_substring() {
        let results = vec![EngineResult {
            title: "Earth".into(),
            url: "https://en.wikipedia.org/wiki/Earth".into(),
            snippet: String::new(),
        }];
        assert!(golden_hit(&results, "wikipedia.org"));
        assert!(!golden_hit(&results, "mojeek.com"));
    }
}
