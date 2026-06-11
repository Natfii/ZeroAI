// Copyright (c) 2026 @Natfii. All rights reserved.

//! On-device multi-engine meta search — the `meta` web-search backend.
//!
//! Queries several keyless engines concurrently (DuckDuckGo HTML, Mojeek,
//! Wikipedia, Marginalia), fuses the results, and degrades gracefully when
//! individual engines block, fail, or change their layout. No API keys, no
//! remote search service, no configuration: the phone's own network identity
//! is the only requirement.
//!
//! Engines are pure data ([`spec::EngineSpec`]); per-engine health drives
//! backoff and arms the self-repair pipeline when an engine's layout shifts.

pub mod executor;
pub mod health;
pub mod merge;
pub mod rate_limit;
pub mod repair;
pub mod spec;

pub use repair::RepairModelConfig;

use executor::EngineFailure;
use merge::EngineBatch;
use rate_limit::SearchRateLimiter;
use repair::SharedSpecs;
use spec::EngineSpec;
use std::fmt::Write as _;
use std::path::PathBuf;
use std::sync::Arc;

/// Number of results requested from each engine before fusion. Fetching a
/// few more than the final cut improves cross-engine overlap detection.
const PER_ENGINE_RESULTS: usize = 8;

/// Bundled spec id of the DuckDuckGo HTML engine. The explicit `duckduckgo`
/// web-search provider routes through this engine via
/// [`MetaSearcher::search_engine`] so it shares the spec-driven parser,
/// health backoff, and self-repair pipeline with the `meta` backend.
pub const DDG_ENGINE_ID: &str = "ddg_html";

/// Orchestrates the engine set for the `meta` web-search backend.
pub struct MetaSearcher {
    specs: SharedSpecs,
    overlay_dir: Option<PathBuf>,
    repair_model: Option<Arc<RepairModelConfig>>,
    limiter: SearchRateLimiter,
    timeout_secs: u64,
}

impl MetaSearcher {
    /// Builds a searcher from the bundled engine specs, overlaid with any
    /// valid self-repair specs found in `overlay_dir`. `repair_model`
    /// enables the model rung of the repair ladder when present.
    ///
    /// A bundled-spec failure is downgraded to an empty engine set (searches
    /// then error cleanly) so construction can never panic across the FFI
    /// boundary.
    pub fn new(
        overlay_dir: Option<PathBuf>,
        max_requests_per_minute: u32,
        timeout_secs: u64,
        repair_model: Option<RepairModelConfig>,
    ) -> Self {
        let specs = spec::resolve_specs(overlay_dir.as_deref()).unwrap_or_else(|err| {
            ::zeroclaw_log::record!(
                ERROR,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                    .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                    .with_attrs(::serde_json::json!({"error": format!("{err:#}")})),
                "metasearch: bundled engine specs failed to load"
            );
            Vec::new()
        });
        Self {
            specs: Arc::new(parking_lot::RwLock::new(specs)),
            overlay_dir,
            repair_model: repair_model.map(Arc::new),
            limiter: SearchRateLimiter::new(max_requests_per_minute),
            timeout_secs: timeout_secs.max(1),
        }
    }

    #[cfg(test)]
    pub(crate) fn with_specs(
        specs: Vec<EngineSpec>,
        max_requests_per_minute: u32,
        timeout_secs: u64,
    ) -> Self {
        Self {
            specs: Arc::new(parking_lot::RwLock::new(specs)),
            overlay_dir: None,
            repair_model: None,
            limiter: SearchRateLimiter::new(max_requests_per_minute),
            timeout_secs,
        }
    }

    /// Runs one meta search: rate-limit check, concurrent engine fan-out,
    /// health bookkeeping, fusion, and text formatting for the agent.
    pub async fn search(&self, query: &str, max_results: usize) -> anyhow::Result<String> {
        if let Err(retry_secs) = self.limiter.try_acquire() {
            anyhow::bail!(
                "Meta search rate limit reached; try again in about {retry_secs} seconds."
            );
        }
        let registry = health::global();
        let active: Vec<EngineSpec> = self
            .specs
            .read()
            .iter()
            .filter(|spec| registry.is_available(&spec.id))
            .cloned()
            .collect();
        anyhow::ensure!(
            !active.is_empty(),
            "No search engines are currently available (all are backing off); try again shortly."
        );

        let (batches, failures) = self.run_engines(active, query).await;
        match fuse_and_format(query, &batches, max_results) {
            Some(output) => Ok(output),
            None => {
                if batches.is_empty() && !failures.is_empty() {
                    let summary: Vec<String> = failures
                        .iter()
                        .map(|(name, failure)| format!("{name}: {failure}"))
                        .collect();
                    anyhow::bail!("All search engines failed — {}", summary.join("; "));
                }
                Ok(format!("No results found for: {query}"))
            }
        }
    }

    /// Runs a meta search restricted to one engine — the spec-driven path
    /// for an explicitly selected provider (e.g. `duckduckgo`). The engine
    /// keeps the full meta treatment: block detection, health backoff, and
    /// self-repair arming.
    pub async fn search_engine(
        &self,
        engine_id: &str,
        query: &str,
        max_results: usize,
    ) -> anyhow::Result<String> {
        if let Err(retry_secs) = self.limiter.try_acquire() {
            anyhow::bail!(
                "Meta search rate limit reached; try again in about {retry_secs} seconds."
            );
        }
        let spec = self
            .specs
            .read()
            .iter()
            .find(|spec| spec.id == engine_id)
            .cloned()
            .ok_or_else(|| {
                ::zeroclaw_log::record!(
                    WARN,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Reject)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(::serde_json::json!({"engine": engine_id})),
                    "metasearch: unknown engine id requested"
                );
                anyhow::Error::msg(format!("Search engine '{engine_id}' is not available."))
            })?;
        if !health::global().is_available(engine_id) {
            anyhow::bail!(
                "{} is temporarily backing off after repeated failures; try again shortly, \
                 or use the default meta search provider.",
                spec.display_name
            );
        }

        let (batches, failures) = self.run_engines(vec![spec], query).await;
        if batches.is_empty()
            && let Some((display_name, failure)) = failures.first()
        {
            if matches!(failure, EngineFailure::Blocked(_)) {
                anyhow::bail!(
                    "{display_name} blocked the automated search request. Try the default \
                     meta search provider, or configure SearXNG, Brave, or Tavily as the \
                     web search provider."
                );
            }
            anyhow::bail!("{display_name} search failed — {failure}");
        }
        Ok(fuse_and_format(query, &batches, max_results)
            .unwrap_or_else(|| format!("No results found for: {query}")))
    }

    /// Fans the query out to `engines` concurrently and performs the shared
    /// per-engine bookkeeping: health recording, failure logging, and
    /// self-repair arming on layout-suspect streaks.
    async fn run_engines(
        &self,
        engines: Vec<EngineSpec>,
        query: &str,
    ) -> (Vec<EngineBatch>, Vec<(String, EngineFailure)>) {
        let registry = health::global();
        let timeout_secs = self.timeout_secs;
        let handles: Vec<_> = engines
            .into_iter()
            .map(|spec| {
                let query = query.to_owned();
                tokio::spawn(async move {
                    let outcome =
                        executor::run_engine(&spec, &query, PER_ENGINE_RESULTS, timeout_secs)
                            .await;
                    (spec, outcome)
                })
            })
            .collect();

        let mut batches: Vec<EngineBatch> = Vec::new();
        let mut failures: Vec<(String, EngineFailure)> = Vec::new();
        for handle in handles {
            let Ok((spec, outcome)) = handle.await else {
                continue;
            };
            match outcome {
                Ok(results) => {
                    registry.record_ok(&spec.id);
                    repair::note_engine_ok(&spec.id);
                    if !results.is_empty() {
                        batches.push(EngineBatch {
                            engine_id: spec.id.clone(),
                            display_name: spec.display_name.clone(),
                            weight: spec.weight,
                            results,
                        });
                    }
                }
                Err(failure) => {
                    registry.record_failure(&spec.id, &failure);
                    log_engine_failure(&spec.id, &failure);
                    if matches!(failure, EngineFailure::ZeroParse { .. })
                        && registry.is_layout_suspect(&spec.id)
                    {
                        repair::maybe_spawn_repair(
                            repair::RepairContext {
                                specs: Arc::clone(&self.specs),
                                overlay_dir: self.overlay_dir.clone(),
                                timeout_secs: self.timeout_secs,
                                model_config: self.repair_model.clone(),
                            },
                            spec.id.clone(),
                        );
                    }
                    failures.push((spec.display_name.clone(), failure));
                }
            }
        }
        (batches, failures)
    }
}

/// Fuses engine batches and formats the agent-facing output, or `None` when
/// fusion produced no results.
fn fuse_and_format(query: &str, batches: &[EngineBatch], max_results: usize) -> Option<String> {
    let merged = merge::merge_engine_results(batches, max_results.clamp(1, 10));
    if merged.is_empty() {
        return None;
    }
    Some(format_results(query, batches, &merged))
}

fn log_engine_failure(engine_id: &str, failure: &EngineFailure) {
    ::zeroclaw_log::record!(
        WARN,
        ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
            .with_outcome(::zeroclaw_log::EventOutcome::Failure)
            .with_attrs(::serde_json::json!({
                "engine": engine_id,
                "failure": failure.to_string(),
            })),
        "metasearch: engine failed"
    );
}

fn format_results(query: &str, batches: &[EngineBatch], merged: &[merge::RankedResult]) -> String {
    let engine_names: Vec<&str> = batches
        .iter()
        .map(|batch| batch.display_name.as_str())
        .collect();
    let mut out = format!(
        "Search results for: {query} (on-device meta search via {})",
        engine_names.join(", ")
    );
    for (index, result) in merged.iter().enumerate() {
        let _ = write!(out, "\n{}. {}", index + 1, result.title);
        let _ = write!(out, "\n   {}", result.url);
        if !result.snippet.is_empty() {
            let _ = write!(out, "\n   {}", result.snippet);
        }
        if result.engines.len() > 1 {
            let _ = write!(out, "\n   [confirmed by {}]", result.engines.join(", "));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use spec::{GoldenSpec, HtmlResponseSpec, RequestSpec, ResponseKind, ResponseSpec};
    use std::collections::BTreeMap;

    fn mock_spec(id: &str, server_url: &str) -> EngineSpec {
        EngineSpec {
            id: id.into(),
            display_name: "MockEngine".into(),
            weight: 1.0,
            block_markers: vec![],
            request: RequestSpec {
                url: format!("{server_url}/search?q={{query}}"),
                user_agent: None,
                headers: BTreeMap::new(),
                token: None,
            },
            response: ResponseSpec {
                kind: ResponseKind::Html,
                html: Some(HtmlResponseSpec {
                    result_selector: "div.result".into(),
                    title_selector: "a.title".into(),
                    url_selector: "a.title".into(),
                    url_attribute: "href".into(),
                    snippet_selector: Some("p.snippet".into()),
                    url_unwrap_param: None,
                }),
                json: None,
            },
            golden: GoldenSpec {
                query: "wikipedia".into(),
                expected_domain: "wikipedia.org".into(),
            },
        }
    }

    const MOCK_SERP: &str = r#"<html><body>
        <div class="result">
            <a class="title" href="https://example.com/one">First Result</a>
            <p class="snippet">First snippet</p>
        </div>
        <div class="result">
            <a class="title" href="https://example.com/two">Second Result</a>
            <p class="snippet">Second snippet</p>
        </div>
    </body></html>"#;

    #[tokio::test]
    async fn search_end_to_end_formats_merged_results() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(200).set_body_string(MOCK_SERP))
            .mount(&server)
            .await;

        let searcher = MetaSearcher::with_specs(
            vec![mock_spec("mod_test_e2e_engine", &server.uri())],
            0,
            5,
        );
        let output = searcher.search("test query", 5).await.unwrap();
        assert!(output.contains("on-device meta search via MockEngine"));
        assert!(output.contains("1. First Result"));
        assert!(output.contains("https://example.com/one"));
        assert!(output.contains("First snippet"));
    }

    #[tokio::test]
    async fn search_reports_all_engines_failed() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(500))
            .mount(&server)
            .await;

        let searcher = MetaSearcher::with_specs(
            vec![mock_spec("mod_test_failure_engine", &server.uri())],
            0,
            5,
        );
        let err = searcher.search("test query", 5).await.unwrap_err();
        assert!(err.to_string().contains("All search engines failed"));
    }

    #[tokio::test]
    async fn search_enforces_rate_limit() {
        let searcher = MetaSearcher::with_specs(
            vec![mock_spec("mod_test_rate_engine", "https://unused.example")],
            1,
            5,
        );
        // Consume the single slot without caring about the network outcome.
        let _ = searcher.search("first", 5).await;
        let err = searcher.search("second", 5).await.unwrap_err();
        assert!(err.to_string().contains("rate limit"), "got: {err}");
    }

    #[test]
    fn new_loads_bundled_specs() {
        let searcher = MetaSearcher::new(None, 10, 15, None);
        assert_eq!(searcher.specs.read().len(), 4);
    }

    #[test]
    fn bundled_specs_include_the_ddg_engine() {
        let searcher = MetaSearcher::new(None, 10, 15, None);
        assert!(
            searcher
                .specs
                .read()
                .iter()
                .any(|spec| spec.id == DDG_ENGINE_ID),
            "the duckduckgo provider route depends on the bundled '{DDG_ENGINE_ID}' spec"
        );
    }

    #[tokio::test]
    async fn search_engine_queries_only_the_selected_engine() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(200).set_body_string(MOCK_SERP))
            .mount(&server)
            .await;

        let mut other = mock_spec("mod_test_single_other", &server.uri());
        other.display_name = "OtherEngine".into();
        let searcher = MetaSearcher::with_specs(
            vec![mock_spec("mod_test_single_selected", &server.uri()), other],
            0,
            5,
        );
        let output = searcher
            .search_engine("mod_test_single_selected", "test query", 5)
            .await
            .unwrap();
        assert!(output.contains("via MockEngine"));
        assert!(!output.contains("OtherEngine"));

        let recorded = server.received_requests().await.unwrap();
        assert_eq!(recorded.len(), 1, "only the selected engine may be queried");
    }

    #[tokio::test]
    async fn search_engine_rejects_unknown_engine_id() {
        let searcher = MetaSearcher::with_specs(vec![], 0, 5);
        let err = searcher
            .search_engine("mod_test_single_missing", "test", 5)
            .await
            .unwrap_err();
        assert!(err.to_string().contains("not available"), "got: {err}");
    }

    #[tokio::test]
    async fn search_engine_reports_block_with_provider_guidance() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(403))
            .mount(&server)
            .await;

        let searcher = MetaSearcher::with_specs(
            vec![mock_spec("mod_test_single_blocked", &server.uri())],
            0,
            5,
        );
        let err = searcher
            .search_engine("mod_test_single_blocked", "test", 5)
            .await
            .unwrap_err();
        assert!(
            err.to_string()
                .contains("MockEngine blocked the automated search request"),
            "got: {err}"
        );
        assert!(err.to_string().contains("SearXNG"), "got: {err}");
    }

    #[tokio::test]
    async fn search_engine_reports_failure_detail() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(500))
            .mount(&server)
            .await;

        let searcher = MetaSearcher::with_specs(
            vec![mock_spec("mod_test_single_failed", &server.uri())],
            0,
            5,
        );
        let err = searcher
            .search_engine("mod_test_single_failed", "test", 5)
            .await
            .unwrap_err();
        assert!(
            err.to_string().contains("MockEngine search failed"),
            "got: {err}"
        );
        assert!(err.to_string().contains("network error"), "got: {err}");
    }

    #[tokio::test]
    async fn search_engine_respects_backoff() {
        let searcher = MetaSearcher::with_specs(
            vec![mock_spec(
                "mod_test_single_backoff",
                "https://unused.example",
            )],
            0,
            5,
        );
        health::global().record_failure(
            "mod_test_single_backoff",
            &EngineFailure::Blocked("HTTP 403".into()),
        );
        let err = searcher
            .search_engine("mod_test_single_backoff", "test", 5)
            .await
            .unwrap_err();
        assert!(err.to_string().contains("backing off"), "got: {err}");
    }

    #[test]
    fn format_results_attributes_multi_engine_confirmation() {
        let merged = vec![merge::RankedResult {
            title: "Shared".into(),
            url: "https://shared.example".into(),
            snippet: "snippet".into(),
            engines: vec!["DuckDuckGo".into(), "Mojeek".into()],
            score: 2.0,
        }];
        let batches = vec![EngineBatch {
            engine_id: "ddg_html".into(),
            display_name: "DuckDuckGo".into(),
            weight: 1.0,
            results: vec![executor::EngineResult {
                title: "Shared".into(),
                url: "https://shared.example".into(),
                snippet: "snippet".into(),
            }],
        }];
        let output = format_results("q", &batches, &merged);
        assert!(output.contains("[confirmed by DuckDuckGo, Mojeek]"));
    }
}
