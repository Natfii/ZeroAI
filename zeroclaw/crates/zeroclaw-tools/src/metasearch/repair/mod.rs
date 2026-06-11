// Copyright (c) 2026 @Natfii. All rights reserved.

//! Self-repair pipeline for meta search engines.
//!
//! When an engine's zero-parse streak marks it layout-suspect, a background
//! repair task runs a strict ladder:
//!
//! 1. **Golden probe** — the engine may be fine (obscure queries can
//!    legitimately return nothing); a blocked or unreachable engine is not
//!    repairable and is left to normal backoff.
//! 2. **Bundled revert** — if a previously adopted overlay went stale, the
//!    factory spec is probed and restored.
//! 3. **Wrapper induction** — deterministic selector re-derivation.
//! 4. **On-device derivation** — when the app registered a local
//!    [`RepairCompleter`] (e.g. Gemini Nano), the skeletonized page is
//!    tried there first; a failure costs one local completion.
//! 5. **Model derivation** — a skeletonized page is handed to the
//!    configured provider's model.
//!
//! Every candidate passes the model-free validation gate before adoption,
//! and adoption is atomic: overlay written to disk first, then the live
//! spec swapped. Single-flight and cooldown guards keep repair attempts
//! rare and non-overlapping.

pub mod completer;
pub mod induction;
pub mod model;
pub mod validate;

pub use completer::{RepairCompleter, set_repair_completer};
pub use model::RepairModelConfig;

use crate::metasearch::executor::{self, EngineFailure};
use crate::metasearch::health;
use crate::metasearch::spec::{self, EngineSpec, HtmlResponseSpec, ResponseKind};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

/// Live engine set shared between the searcher and repair tasks.
pub type SharedSpecs = Arc<parking_lot::RwLock<Vec<EngineSpec>>>;

/// Minimum time between repair attempts for the same engine.
const REPAIR_COOLDOWN: Duration = Duration::from_secs(15 * 60);

/// Results requested from golden probes.
const PROBE_RESULTS: usize = 8;

/// Everything a repair task needs, cloned out of the searcher.
pub struct RepairContext {
    /// Live engine set to read the current spec from and swap repairs into.
    pub specs: SharedSpecs,
    /// Overlay directory for persisting adopted repairs.
    pub overlay_dir: Option<PathBuf>,
    /// Per-request timeout, matching the searcher's.
    pub timeout_secs: u64,
    /// Model rung configuration; `None` disables tier 4.
    pub model_config: Option<Arc<RepairModelConfig>>,
}

/// Terminal state of one repair attempt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RepairOutcome {
    /// Golden probe succeeded with the current spec — false alarm.
    NotBroken,
    /// A stale overlay was discarded in favor of the bundled spec.
    RevertedToBundled,
    /// Wrapper induction produced a gate-passing spec.
    RepairedByInduction,
    /// The on-device completer rung produced a gate-passing spec.
    RepairedByOnDevice,
    /// The model rung produced a gate-passing spec.
    RepairedByModel,
    /// No candidate passed, or the engine was not in a repairable state.
    Failed(String),
}

impl std::fmt::Display for RepairOutcome {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotBroken => write!(f, "not broken (golden probe passed)"),
            Self::RevertedToBundled => write!(f, "reverted to bundled spec"),
            Self::RepairedByInduction => write!(f, "repaired via wrapper induction"),
            Self::RepairedByOnDevice => write!(f, "repaired via on-device derivation"),
            Self::RepairedByModel => write!(f, "repaired via model derivation"),
            Self::Failed(detail) => write!(f, "repair failed: {detail}"),
        }
    }
}

#[derive(Default)]
struct CoordinatorState {
    in_flight: HashSet<String>,
    last_attempt: HashMap<String, Instant>,
}

fn coordinator() -> &'static parking_lot::Mutex<CoordinatorState> {
    static COORDINATOR: OnceLock<parking_lot::Mutex<CoordinatorState>> = OnceLock::new();
    COORDINATOR.get_or_init(|| parking_lot::Mutex::new(CoordinatorState::default()))
}

/// Spawns a background repair for the engine unless one is already running
/// or the engine is still in its repair cooldown.
pub(crate) fn maybe_spawn_repair(context: RepairContext, engine_id: String) {
    if !try_begin(&engine_id, Instant::now()) {
        return;
    }
    tokio::spawn(async move {
        let outcome = run_repair(&context, &engine_id).await;
        ::zeroclaw_log::record!(
            INFO,
            ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note)
                .with_attrs(::serde_json::json!({
                    "engine": engine_id,
                    "outcome": outcome.to_string(),
                })),
            "metasearch: engine repair attempt finished"
        );
        finish(&engine_id);
    });
}

fn try_begin(engine_id: &str, now: Instant) -> bool {
    let mut state = coordinator().lock();
    if state.in_flight.contains(engine_id) {
        return false;
    }
    if let Some(last) = state.last_attempt.get(engine_id)
        && now.duration_since(*last) < REPAIR_COOLDOWN
    {
        return false;
    }
    state.in_flight.insert(engine_id.to_owned());
    state.last_attempt.insert(engine_id.to_owned(), now);
    true
}

fn finish(engine_id: &str) {
    coordinator().lock().in_flight.remove(engine_id);
}

/// Runs the full repair ladder for one engine. Exposed to tests so they can
/// await completion deterministically; production goes through
/// [`maybe_spawn_repair`].
pub(crate) async fn run_repair(context: &RepairContext, engine_id: &str) -> RepairOutcome {
    let current = {
        let specs = context.specs.read();
        match specs.iter().find(|s| s.id == engine_id) {
            Some(spec) => spec.clone(),
            None => return RepairOutcome::Failed("engine id not in the live spec set".into()),
        }
    };

    // Tier 1: golden probe with the current spec. Layout-suspect engines can
    // be false alarms (streaks of legitimately empty queries).
    match executor::run_engine(
        &current,
        &current.golden.query,
        PROBE_RESULTS,
        context.timeout_secs,
    )
    .await
    {
        Ok(results) if validate::golden_hit(&results, &current.golden.expected_domain) => {
            health::global().record_ok(engine_id);
            return RepairOutcome::NotBroken;
        }
        Err(EngineFailure::Blocked(detail)) => {
            return RepairOutcome::Failed(format!("blocked during golden probe: {detail}"));
        }
        Err(EngineFailure::Network(detail)) => {
            return RepairOutcome::Failed(format!("unreachable during golden probe: {detail}"));
        }
        _ => {}
    }

    // Tier 2: a previously adopted overlay may have gone stale — probe the
    // factory spec and revert if it works again.
    if let Ok(bundled_all) = spec::bundled_specs()
        && let Some(bundled) = bundled_all.into_iter().find(|s| s.id == engine_id)
        && !specs_equal(&bundled, &current)
        && let Ok(results) = executor::run_engine(
            &bundled,
            &bundled.golden.query,
            PROBE_RESULTS,
            context.timeout_secs,
        )
        .await
        && validate::golden_hit(&results, &bundled.golden.expected_domain)
    {
        return match adopt(context, &bundled, OverlayAction::Delete) {
            Ok(()) => RepairOutcome::RevertedToBundled,
            Err(err) => RepairOutcome::Failed(format!("bundled revert failed: {err:#}")),
        };
    }

    if current.response.kind != ResponseKind::Html {
        return RepairOutcome::Failed(
            "non-HTML engine; only golden-probe and bundled-revert repair are supported".into(),
        );
    }

    let body = match executor::fetch_body(
        &current,
        &current.golden.query,
        PROBE_RESULTS,
        context.timeout_secs,
    )
    .await
    {
        Ok(body) => body,
        Err(failure) => {
            return RepairOutcome::Failed(format!("golden probe fetch failed: {failure}"));
        }
    };

    // Tier 3: deterministic wrapper induction.
    if let Some(derived) = induction::derive(&body, &current.golden.expected_domain)
        && let Some(outcome) = gate_and_adopt(
            context,
            engine_id,
            &current,
            &body,
            "induction",
            derived,
            RepairOutcome::RepairedByInduction,
        )
    {
        return outcome;
    }

    // Tier 4: on-device derivation via the app-registered completer.
    if let Some(on_device) = completer::current() {
        match model::derive_with_completer(on_device, engine_id, &body).await {
            Ok(derived) => {
                if let Some(outcome) = gate_and_adopt(
                    context,
                    engine_id,
                    &current,
                    &body,
                    "on-device",
                    derived,
                    RepairOutcome::RepairedByOnDevice,
                ) {
                    return outcome;
                }
            }
            Err(err) => log_derivation_failure(engine_id, "on-device", &err),
        }
    }

    // Tier 5: model derivation via the configured provider.
    if let Some(model_config) = &context.model_config {
        match model::derive(model_config, engine_id, &body).await {
            Ok(derived) => {
                if let Some(outcome) = gate_and_adopt(
                    context,
                    engine_id,
                    &current,
                    &body,
                    "model",
                    derived,
                    RepairOutcome::RepairedByModel,
                ) {
                    return outcome;
                }
            }
            Err(err) => log_derivation_failure(engine_id, "model", &err),
        }
    }

    RepairOutcome::Failed("no candidate passed the validation gate".into())
}

/// Gates a derived candidate and adopts it on success. Returns the terminal
/// outcome when the gate passes (adopted, or adoption failed); `None` when
/// the gate rejects, letting the ladder continue to the next rung.
#[allow(clippy::too_many_arguments)]
fn gate_and_adopt(
    context: &RepairContext,
    engine_id: &str,
    current: &EngineSpec,
    body: &str,
    source: &str,
    derived: HtmlResponseSpec,
    adopted: RepairOutcome,
) -> Option<RepairOutcome> {
    let candidate = with_html(current, derived);
    match validate::gate(&candidate, body) {
        Ok(()) => Some(match adopt(context, &candidate, OverlayAction::Write) {
            Ok(()) => adopted,
            Err(err) => RepairOutcome::Failed(format!("adoption failed: {err:#}")),
        }),
        Err(err) => {
            log_gate_rejection(engine_id, source, &err);
            None
        }
    }
}

fn log_derivation_failure(engine_id: &str, source: &str, err: &anyhow::Error) {
    ::zeroclaw_log::record!(
        WARN,
        ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
            .with_outcome(::zeroclaw_log::EventOutcome::Failure)
            .with_attrs(::serde_json::json!({
                "engine": engine_id,
                "source": source,
                "error": format!("{err:#}"),
            })),
        "metasearch: selector derivation failed"
    );
}

enum OverlayAction {
    Write,
    Delete,
}

/// Adopts a validated candidate: persist the overlay change on disk first,
/// then swap the live spec. The single-flight guard guarantees one writer
/// per engine, making the two-step file replace safe.
fn adopt(
    context: &RepairContext,
    candidate: &EngineSpec,
    action: OverlayAction,
) -> anyhow::Result<()> {
    if let Some(dir) = &context.overlay_dir {
        let final_path = dir.join(format!("{}.toml", candidate.id));
        match action {
            OverlayAction::Write => {
                std::fs::create_dir_all(dir)?;
                let tmp_path = dir.join(format!("{}.toml.tmp", candidate.id));
                std::fs::write(&tmp_path, toml::to_string(candidate)?)?;
                if final_path.exists() {
                    std::fs::remove_file(&final_path)?;
                }
                std::fs::rename(&tmp_path, &final_path)?;
            }
            OverlayAction::Delete => {
                if final_path.exists() {
                    std::fs::remove_file(&final_path)?;
                }
            }
        }
    }
    {
        let mut specs = context.specs.write();
        if let Some(slot) = specs.iter_mut().find(|s| s.id == candidate.id) {
            *slot = candidate.clone();
        }
    }
    health::global().record_repaired(&candidate.id);
    Ok(())
}

fn with_html(current: &EngineSpec, html: HtmlResponseSpec) -> EngineSpec {
    let mut candidate = current.clone();
    candidate.response.kind = ResponseKind::Html;
    candidate.response.html = Some(html);
    candidate.response.json = None;
    candidate
}

fn specs_equal(a: &EngineSpec, b: &EngineSpec) -> bool {
    toml::to_string(a).ok() == toml::to_string(b).ok()
}

fn log_gate_rejection(engine_id: &str, source: &str, err: &anyhow::Error) {
    ::zeroclaw_log::record!(
        WARN,
        ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Reject)
            .with_outcome(::zeroclaw_log::EventOutcome::Failure)
            .with_attrs(::serde_json::json!({
                "engine": engine_id,
                "source": source,
                "error": format!("{err:#}"),
            })),
        "metasearch: candidate spec rejected by validation gate"
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metasearch::spec::{GoldenSpec, RequestSpec, ResponseSpec};
    use std::collections::BTreeMap;

    fn broken_mock_spec(id: &str, server_url: &str) -> EngineSpec {
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
                    result_selector: "div.stale-result".into(),
                    title_selector: "a.stale-title".into(),
                    url_selector: "a.stale-title".into(),
                    url_attribute: "href".into(),
                    snippet_selector: None,
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

    /// A redesigned SERP that shares no class vocabulary with the stale
    /// selectors, padded past the layout-break body threshold.
    fn redesigned_serp() -> String {
        let mut items = String::new();
        let sites = [
            ("https://en.wikipedia.org/wiki/Wikipedia", "Wikipedia - The Free Encyclopedia"),
            ("https://example.com/alpha", "Alpha Example Result"),
            ("https://example.org/beta", "Beta Example Result"),
            ("https://example.net/gamma", "Gamma Example Result"),
        ];
        for (url, title) in sites {
            items.push_str(&format!(
                "<li class=\"hit\"><h3><a class=\"headline\" href=\"{url}\">{title}</a></h3>\
                 <p class=\"blurb\">A descriptive snippet about {title} long enough to qualify as one.</p></li>"
            ));
        }
        format!(
            "<html><body><nav><a href=\"/about\">About</a></nav>\
             <ul class=\"fresh-results\">{items}</ul>\
             <!-- {} --></body></html>",
            "padding ".repeat(300)
        )
    }

    #[tokio::test]
    async fn repair_ladder_recovers_via_induction_end_to_end() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(200).set_body_string(redesigned_serp()))
            .mount(&server)
            .await;

        let overlay_dir = tempfile::tempdir().unwrap();
        let spec = broken_mock_spec("repair_e2e_engine", &server.uri());
        let context = RepairContext {
            specs: Arc::new(parking_lot::RwLock::new(vec![spec.clone()])),
            overlay_dir: Some(overlay_dir.path().to_path_buf()),
            timeout_secs: 5,
            model_config: None,
        };

        let outcome = run_repair(&context, "repair_e2e_engine").await;
        assert_eq!(outcome, RepairOutcome::RepairedByInduction, "{outcome}");

        let overlay_path = overlay_dir.path().join("repair_e2e_engine.toml");
        assert!(overlay_path.exists(), "adopted overlay must be persisted");

        let repaired = context.specs.read()[0].clone();
        let html = repaired.response.html.clone().unwrap();
        assert_ne!(html.result_selector, "div.stale-result");
        let results = executor::run_engine(&repaired, "wikipedia", 8, 5).await.unwrap();
        assert!(validate::golden_hit(&results, "wikipedia.org"));
    }

    #[tokio::test]
    async fn repair_reports_not_broken_when_golden_probe_passes() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        let healthy_serp = redesigned_serp()
            .replace("hit", "stale-result")
            .replace("headline", "stale-title")
            .replace("li class", "div class")
            .replace("</li>", "</div>");
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(200).set_body_string(healthy_serp))
            .mount(&server)
            .await;

        let spec = broken_mock_spec("repair_probe_engine", &server.uri());
        let context = RepairContext {
            specs: Arc::new(parking_lot::RwLock::new(vec![spec])),
            overlay_dir: None,
            timeout_secs: 5,
            model_config: None,
        };
        let outcome = run_repair(&context, "repair_probe_engine").await;
        assert_eq!(outcome, RepairOutcome::NotBroken, "{outcome}");
    }

    #[tokio::test]
    async fn repair_gives_up_when_engine_is_blocked() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(403))
            .mount(&server)
            .await;

        let spec = broken_mock_spec("repair_blocked_engine", &server.uri());
        let context = RepairContext {
            specs: Arc::new(parking_lot::RwLock::new(vec![spec])),
            overlay_dir: None,
            timeout_secs: 5,
            model_config: None,
        };
        let outcome = run_repair(&context, "repair_blocked_engine").await;
        assert!(
            matches!(outcome, RepairOutcome::Failed(ref detail) if detail.contains("blocked")),
            "{outcome}"
        );
    }

    /// Serializes tests that touch the process-global completer slot, per
    /// the project rule for process-global state under the parallel runner.
    static COMPLETER_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    /// Clears the completer slot when a test ends, even on panic.
    struct CompleterSlotGuard;

    impl Drop for CompleterSlotGuard {
        fn drop(&mut self) {
            completer::set_repair_completer(None);
        }
    }

    struct StubCompleter {
        response: &'static str,
        calls: Arc<std::sync::atomic::AtomicUsize>,
    }

    impl completer::RepairCompleter for StubCompleter {
        fn complete(&self, _prompt: String) -> anyhow::Result<String> {
            self.calls
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(self.response.to_owned())
        }
    }

    /// A redirect-style SERP: every result link is a path-relative
    /// `/l/?uddg=<encoded>` redirect, which wrapper induction cannot seed
    /// from (no absolute golden-domain anchor) while an unwrap-aware spec
    /// parses it fine — exactly the page shape that needs the model rungs.
    fn redirect_serp() -> String {
        let mut items = String::new();
        let sites = [
            ("https://en.wikipedia.org/wiki/Wikipedia", "Wikipedia - The Free Encyclopedia"),
            ("https://example.com/alpha", "Alpha Example Result"),
            ("https://example.org/beta", "Beta Example Result"),
            ("https://example.net/gamma", "Gamma Example Result"),
        ];
        for (url, title) in sites {
            let encoded = urlencoding::encode(url);
            items.push_str(&format!(
                "<li class=\"hit\"><h3><a class=\"headline\" href=\"/l/?uddg={encoded}\">{title}</a></h3>\
                 <p class=\"blurb\">A descriptive snippet about {title} long enough to qualify.</p></li>"
            ));
        }
        format!(
            "<html><body><ul class=\"fresh-results\">{items}</ul><!-- {} --></body></html>",
            "padding ".repeat(300)
        )
    }

    #[tokio::test]
    async fn repair_ladder_recovers_via_on_device_completer() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let _lock = COMPLETER_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let _slot_guard = CompleterSlotGuard;

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(200).set_body_string(redirect_serp()))
            .mount(&server)
            .await;

        let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        completer::set_repair_completer(Some(Arc::new(StubCompleter {
            response: r#"{"result_selector": "li.hit", "title_selector": "a.headline",
                "url_selector": "a.headline", "snippet_selector": "p.blurb",
                "url_unwrap_param": "uddg"}"#,
            calls: Arc::clone(&calls),
        })));

        let spec = broken_mock_spec("repair_ondevice_engine", &server.uri());
        let context = RepairContext {
            specs: Arc::new(parking_lot::RwLock::new(vec![spec])),
            overlay_dir: None,
            timeout_secs: 5,
            model_config: None,
        };
        let outcome = run_repair(&context, "repair_ondevice_engine").await;
        assert_eq!(outcome, RepairOutcome::RepairedByOnDevice, "{outcome}");
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 1);

        let repaired = context.specs.read()[0].clone();
        let results = executor::run_engine(&repaired, "wikipedia", 8, 5)
            .await
            .unwrap();
        assert!(validate::golden_hit(&results, "wikipedia.org"));
        assert!(
            results.iter().any(|r| r.url.starts_with("https://")),
            "redirect hrefs must be unwrapped to absolute URLs"
        );
    }

    #[tokio::test]
    async fn bad_on_device_answer_is_gated_and_ladder_fails_closed() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let _lock = COMPLETER_TEST_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let _slot_guard = CompleterSlotGuard;

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(200).set_body_string(redirect_serp()))
            .mount(&server)
            .await;

        let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        completer::set_repair_completer(Some(Arc::new(StubCompleter {
            response: "I could not work out any selectors, sorry!",
            calls: Arc::clone(&calls),
        })));

        let spec = broken_mock_spec("repair_ondevice_bad_engine", &server.uri());
        let context = RepairContext {
            specs: Arc::new(parking_lot::RwLock::new(vec![spec.clone()])),
            overlay_dir: None,
            timeout_secs: 5,
            model_config: None,
        };
        let outcome = run_repair(&context, "repair_ondevice_bad_engine").await;
        assert!(
            matches!(outcome, RepairOutcome::Failed(_)),
            "a garbage on-device answer must never adopt: {outcome}"
        );
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 1);
        assert_eq!(
            toml::to_string(&context.specs.read()[0]).ok(),
            toml::to_string(&spec).ok(),
            "live spec must be untouched after a failed repair"
        );
    }

    #[test]
    fn coordinator_enforces_single_flight_and_cooldown() {
        let now = Instant::now();
        assert!(try_begin("coord_test_engine", now));
        assert!(!try_begin("coord_test_engine", now), "in-flight must dedupe");
        finish("coord_test_engine");
        assert!(
            !try_begin("coord_test_engine", now + Duration::from_secs(60)),
            "cooldown must hold after completion"
        );
        assert!(
            try_begin("coord_test_engine", now + REPAIR_COOLDOWN + Duration::from_secs(1)),
            "cooldown must expire"
        );
        finish("coord_test_engine");
    }
}
