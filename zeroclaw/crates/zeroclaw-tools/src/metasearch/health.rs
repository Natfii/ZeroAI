// Copyright (c) 2026 @Natfii. All rights reserved.

//! Per-engine health bookkeeping: backoff for blocked or unreachable engines
//! and layout-break detection that arms the self-repair pipeline.
//!
//! A process-global registry holds live state so the FFI health surface can
//! report per-engine status without holding a reference to the tool instance.

use super::executor::EngineFailure;
use std::collections::HashMap;
use std::sync::OnceLock;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// Consecutive zero-result parses before an engine is considered a repair
/// candidate. The threshold absorbs legitimately empty result pages for
/// obscure queries; the repair pipeline's golden probe disambiguates.
pub const LAYOUT_SUSPECT_THRESHOLD: u32 = 3;

const BLOCK_BACKOFF_BASE: Duration = Duration::from_secs(60);
const BLOCK_BACKOFF_CAP: Duration = Duration::from_secs(1800);
const NETWORK_BACKOFF: Duration = Duration::from_secs(30);

/// Coarse engine condition for health surfaces and the plugin detail UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EngineCondition {
    /// Serving results normally.
    Healthy,
    /// Temporarily out of rotation after blocks or network failures.
    Backoff,
    /// Repeated zero-result parses; a repair candidate.
    LayoutSuspect,
}

impl EngineCondition {
    /// Stable lowercase identifier for FFI and logs.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Healthy => "healthy",
            Self::Backoff => "backoff",
            Self::LayoutSuspect => "layout_suspect",
        }
    }
}

/// Live health state for one engine.
#[derive(Debug, Clone)]
struct EngineHealth {
    condition: EngineCondition,
    backoff_until: Option<Instant>,
    consecutive_zero_parses: u32,
    consecutive_blocks: u32,
    last_error: Option<String>,
    last_ok_unix: Option<u64>,
    repaired_at_unix: Option<u64>,
}

impl Default for EngineHealth {
    fn default() -> Self {
        Self {
            condition: EngineCondition::Healthy,
            backoff_until: None,
            consecutive_zero_parses: 0,
            consecutive_blocks: 0,
            last_error: None,
            last_ok_unix: None,
            repaired_at_unix: None,
        }
    }
}

/// Point-in-time, serialization-friendly view of one engine's health.
#[derive(Debug, Clone)]
pub struct EngineHealthSnapshot {
    /// Spec id of the engine.
    pub engine_id: String,
    /// Stable condition identifier (`healthy` / `backoff` / `layout_suspect`).
    pub condition: &'static str,
    /// Most recent failure description, if any.
    pub last_error: Option<String>,
    /// Unix seconds of the last successful search, if any.
    pub last_ok_unix: Option<u64>,
    /// Unix seconds of the last adopted self-repair, if any.
    pub repaired_at_unix: Option<u64>,
    /// Seconds of backoff remaining, when the engine is backing off.
    pub backoff_remaining_secs: Option<u64>,
}

/// Thread-safe registry of per-engine health, keyed by spec id.
#[derive(Default)]
pub struct HealthRegistry {
    inner: parking_lot::RwLock<HashMap<String, EngineHealth>>,
}

impl HealthRegistry {
    /// Whether the engine should be queried right now (not in backoff).
    /// Layout-suspect engines stay available: their requests are cheap, they
    /// may recover on their own, and continued failures feed repair evidence.
    pub fn is_available(&self, engine_id: &str) -> bool {
        self.is_available_at(engine_id, Instant::now())
    }

    fn is_available_at(&self, engine_id: &str, now: Instant) -> bool {
        let inner = self.inner.read();
        match inner.get(engine_id).and_then(|h| h.backoff_until) {
            Some(until) => now >= until,
            None => true,
        }
    }

    /// Records a successful search, clearing failure streaks and backoff.
    pub fn record_ok(&self, engine_id: &str) {
        let mut inner = self.inner.write();
        let health = inner.entry(engine_id.to_owned()).or_default();
        health.condition = EngineCondition::Healthy;
        health.backoff_until = None;
        health.consecutive_zero_parses = 0;
        health.consecutive_blocks = 0;
        health.last_error = None;
        health.last_ok_unix = Some(unix_now());
    }

    /// Records a classified failure and applies the matching policy:
    /// exponential backoff for blocks, a short flat backoff for network
    /// errors, and a zero-parse streak counter for layout-break evidence.
    pub fn record_failure(&self, engine_id: &str, failure: &EngineFailure) {
        self.record_failure_at(engine_id, failure, Instant::now());
    }

    fn record_failure_at(&self, engine_id: &str, failure: &EngineFailure, now: Instant) {
        let mut inner = self.inner.write();
        let health = inner.entry(engine_id.to_owned()).or_default();
        health.last_error = Some(failure.to_string());
        match failure {
            EngineFailure::Blocked(_) => {
                health.consecutive_blocks = health.consecutive_blocks.saturating_add(1);
                let exponent = health.consecutive_blocks.saturating_sub(1).min(8);
                let backoff = BLOCK_BACKOFF_BASE
                    .saturating_mul(2_u32.saturating_pow(exponent))
                    .min(BLOCK_BACKOFF_CAP);
                health.backoff_until = Some(now + backoff);
                health.condition = EngineCondition::Backoff;
            }
            EngineFailure::Network(_) => {
                health.backoff_until = Some(now + NETWORK_BACKOFF);
                health.condition = EngineCondition::Backoff;
            }
            EngineFailure::ZeroParse { .. } => {
                health.consecutive_zero_parses =
                    health.consecutive_zero_parses.saturating_add(1);
                if health.consecutive_zero_parses >= LAYOUT_SUSPECT_THRESHOLD {
                    health.condition = EngineCondition::LayoutSuspect;
                }
            }
            EngineFailure::BadSpec(_) => {
                health.condition = EngineCondition::LayoutSuspect;
            }
        }
    }

    /// Records an adopted self-repair for surfacing in health detail.
    pub fn record_repaired(&self, engine_id: &str) {
        let mut inner = self.inner.write();
        let health = inner.entry(engine_id.to_owned()).or_default();
        health.condition = EngineCondition::Healthy;
        health.consecutive_zero_parses = 0;
        health.last_error = None;
        health.repaired_at_unix = Some(unix_now());
    }

    /// Engines whose zero-parse streak crossed [`LAYOUT_SUSPECT_THRESHOLD`].
    pub fn layout_suspects(&self) -> Vec<String> {
        let inner = self.inner.read();
        inner
            .iter()
            .filter(|(_, h)| h.condition == EngineCondition::LayoutSuspect)
            .map(|(id, _)| id.clone())
            .collect()
    }

    /// Point-in-time snapshot of every tracked engine, sorted by id.
    pub fn snapshot(&self) -> Vec<EngineHealthSnapshot> {
        let now = Instant::now();
        let inner = self.inner.read();
        let mut snapshots: Vec<EngineHealthSnapshot> = inner
            .iter()
            .map(|(id, health)| EngineHealthSnapshot {
                engine_id: id.clone(),
                condition: health.condition.as_str(),
                last_error: health.last_error.clone(),
                last_ok_unix: health.last_ok_unix,
                repaired_at_unix: health.repaired_at_unix,
                backoff_remaining_secs: health
                    .backoff_until
                    .and_then(|until| until.checked_duration_since(now))
                    .map(|remaining| remaining.as_secs()),
            })
            .collect();
        snapshots.sort_by(|a, b| a.engine_id.cmp(&b.engine_id));
        snapshots
    }
}

/// Process-global registry shared by the tool instance and the FFI health
/// surface.
pub fn global() -> &'static HealthRegistry {
    static REGISTRY: OnceLock<HealthRegistry> = OnceLock::new();
    REGISTRY.get_or_init(HealthRegistry::default)
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fresh_engine_is_available_and_healthy() {
        let registry = HealthRegistry::default();
        assert!(registry.is_available("ddg_html"));
        assert!(registry.snapshot().is_empty());
    }

    #[test]
    fn block_applies_exponential_backoff() {
        let registry = HealthRegistry::default();
        let now = Instant::now();
        let failure = EngineFailure::Blocked("HTTP 403".into());

        registry.record_failure_at("e", &failure, now);
        assert!(!registry.is_available_at("e", now + Duration::from_secs(30)));
        assert!(registry.is_available_at("e", now + Duration::from_secs(61)));

        registry.record_failure_at("e", &failure, now);
        assert!(!registry.is_available_at("e", now + Duration::from_secs(100)));
        assert!(registry.is_available_at("e", now + Duration::from_secs(121)));
    }

    #[test]
    fn block_backoff_is_capped() {
        let registry = HealthRegistry::default();
        let now = Instant::now();
        let failure = EngineFailure::Blocked("HTTP 429".into());
        for _ in 0..20 {
            registry.record_failure_at("e", &failure, now);
        }
        assert!(registry.is_available_at("e", now + Duration::from_secs(1801)));
    }

    #[test]
    fn network_failure_applies_short_backoff() {
        let registry = HealthRegistry::default();
        let now = Instant::now();
        registry.record_failure_at("e", &EngineFailure::Network("timeout".into()), now);
        assert!(!registry.is_available_at("e", now + Duration::from_secs(10)));
        assert!(registry.is_available_at("e", now + Duration::from_secs(31)));
    }

    #[test]
    fn zero_parse_streak_marks_layout_suspect_without_backoff() {
        let registry = HealthRegistry::default();
        let now = Instant::now();
        let failure = EngineFailure::ZeroParse { body_bytes: 9000 };
        for _ in 0..LAYOUT_SUSPECT_THRESHOLD {
            registry.record_failure_at("e", &failure, now);
        }
        assert!(registry.is_available_at("e", now));
        assert_eq!(registry.layout_suspects(), vec!["e".to_string()]);
    }

    #[test]
    fn ok_resets_streaks_and_backoff() {
        let registry = HealthRegistry::default();
        let now = Instant::now();
        let failure = EngineFailure::ZeroParse { body_bytes: 9000 };
        for _ in 0..LAYOUT_SUSPECT_THRESHOLD {
            registry.record_failure_at("e", &failure, now);
        }
        registry.record_ok("e");
        assert!(registry.layout_suspects().is_empty());
        let snapshot = registry.snapshot();
        assert_eq!(snapshot.len(), 1);
        assert_eq!(snapshot[0].condition, "healthy");
        assert!(snapshot[0].last_ok_unix.is_some());
    }

    #[test]
    fn repaired_clears_suspect_state_and_stamps_time() {
        let registry = HealthRegistry::default();
        let now = Instant::now();
        let failure = EngineFailure::ZeroParse { body_bytes: 9000 };
        for _ in 0..LAYOUT_SUSPECT_THRESHOLD {
            registry.record_failure_at("e", &failure, now);
        }
        registry.record_repaired("e");
        assert!(registry.layout_suspects().is_empty());
        assert!(registry.snapshot()[0].repaired_at_unix.is_some());
    }

    #[test]
    fn global_registry_is_a_singleton() {
        let a = global() as *const HealthRegistry;
        let b = global() as *const HealthRegistry;
        assert_eq!(a, b);
    }
}
