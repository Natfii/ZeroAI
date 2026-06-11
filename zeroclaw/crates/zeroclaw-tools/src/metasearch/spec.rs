// Copyright (c) 2026 @Natfii. All rights reserved.

//! Declarative engine specifications for the on-device meta search backend.
//!
//! Each remote engine is described entirely by data: how to build the request,
//! where results live in the response, and a golden query used to validate the
//! spec against the live engine. Keeping engines as data rather than code is
//! what allows the self-repair pipeline to regenerate selectors at runtime
//! without an app update. The bundled specs compiled into the binary are the
//! permanent fallback floor: overlays loaded from disk may refine an engine,
//! but can never retarget it at another host, change its golden contract, or
//! add or remove engines.

use std::collections::BTreeMap;
use std::path::Path;

/// Upper bound on engine-spec overlay files read from disk. Anything larger
/// is treated as corrupt output from a failed repair and ignored.
const MAX_SPEC_FILE_BYTES: u64 = 64 * 1024;

/// Raw TOML for the engine specs compiled into the binary.
const BUNDLED_SPECS: &[&str] = &[
    include_str!("engines/ddg_html.toml"),
    include_str!("engines/mojeek.toml"),
    include_str!("engines/wikipedia.toml"),
    include_str!("engines/marginalia.toml"),
];

/// A complete declarative description of one search engine.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EngineSpec {
    /// Stable snake_case identifier; also the overlay file stem on disk.
    pub id: String,
    /// Human-readable engine name shown in result attribution and the UI.
    pub display_name: String,
    /// Relative ranking weight applied during result fusion (`0.0..=10.0`).
    #[serde(default = "default_engine_weight")]
    pub weight: f64,
    /// Case-insensitive body substrings that identify an anti-bot block page.
    #[serde(default)]
    pub block_markers: Vec<String>,
    /// How to build the search request.
    pub request: RequestSpec,
    /// How to extract results from the response.
    pub response: ResponseSpec,
    /// Known-answer probe used by the repair pipeline's validation gate.
    pub golden: GoldenSpec,
}

/// Request construction: URL template, headers, and optional token pre-fetch.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RequestSpec {
    /// HTTPS URL template. Supports `{query}` (required, percent-encoded),
    /// `{max_results}`, and `{token}` (requires a `token` section).
    pub url: String,
    /// Override for the default browser User-Agent.
    #[serde(default)]
    pub user_agent: Option<String>,
    /// Additional request headers.
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
    /// Optional anti-bot token pre-fetch (e.g. DuckDuckGo's `vqd` dance).
    #[serde(default)]
    pub token: Option<TokenSpec>,
}

/// Pre-fetch a page and regex-extract a token to substitute into the
/// request URL as `{token}`.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TokenSpec {
    /// URL to fetch the token from. Supports `{query}`.
    pub url: String,
    /// Regex with exactly one capture group that extracts the token value.
    pub extract_pattern: String,
}

/// Response parsing strategy selector plus the matching per-kind section.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ResponseSpec {
    /// Which parser to apply to the response body.
    pub kind: ResponseKind,
    /// CSS-selector extraction rules; required when `kind = "html"`.
    #[serde(default)]
    pub html: Option<HtmlResponseSpec>,
    /// JSON-pointer extraction rules; required when `kind = "json"`.
    #[serde(default)]
    pub json: Option<JsonResponseSpec>,
}

/// Supported response body formats.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ResponseKind {
    /// Scrape a SERP with CSS selectors.
    Html,
    /// Extract from a JSON API response with JSON pointers.
    Json,
}

/// CSS-selector extraction rules for HTML result pages.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HtmlResponseSpec {
    /// Selector matching one element per search result.
    pub result_selector: String,
    /// Selector (scoped to a result element) for the title text.
    pub title_selector: String,
    /// Selector (scoped to a result element) for the link element.
    pub url_selector: String,
    /// Attribute on the link element holding the URL.
    #[serde(default = "default_url_attribute")]
    pub url_attribute: String,
    /// Selector (scoped to a result element) for the snippet text.
    #[serde(default)]
    pub snippet_selector: Option<String>,
    /// Query parameter to unwrap redirect-style hrefs
    /// (e.g. `uddg` for DuckDuckGo's `/l/?uddg=<real-url>` links).
    #[serde(default)]
    pub url_unwrap_param: Option<String>,
}

/// JSON-pointer extraction rules for JSON API responses. Pointers for the
/// per-result fields are relative to each element of the results array.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JsonResponseSpec {
    /// JSON pointer to the array of result objects.
    pub results_pointer: String,
    /// JSON pointer to the result title.
    pub title_pointer: String,
    /// JSON pointer to the result URL. Mutually exclusive with `url_template`.
    #[serde(default)]
    pub url_pointer: Option<String>,
    /// URL template with a `{value}` placeholder, for APIs that return a page
    /// key instead of a full URL. Requires `url_template_value_pointer`.
    #[serde(default)]
    pub url_template: Option<String>,
    /// JSON pointer to the value substituted into `url_template`.
    #[serde(default)]
    pub url_template_value_pointer: Option<String>,
    /// JSON pointer to the result snippet.
    #[serde(default)]
    pub snippet_pointer: Option<String>,
}

/// Known-answer probe: searching `query` on a healthy engine must surface
/// `expected_domain` among the results. The repair pipeline refuses to adopt
/// any candidate spec that cannot reproduce this.
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GoldenSpec {
    /// Probe query with a stable, predictable answer.
    pub query: String,
    /// Domain that must appear in the probe's results.
    pub expected_domain: String,
}

fn default_engine_weight() -> f64 {
    1.0
}

fn default_url_attribute() -> String {
    "href".into()
}

impl EngineSpec {
    /// Validates internal consistency: placeholder presence, HTTPS-only URLs,
    /// compilable selectors and regexes, and kind/section agreement.
    ///
    /// Runs at load time (bundled and overlay) and against repair candidates,
    /// so executor code can assume a structurally sound spec.
    pub fn validate(&self) -> anyhow::Result<()> {
        anyhow::ensure!(
            !self.id.is_empty()
                && self
                    .id
                    .chars()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_'),
            "engine id must be non-empty ascii snake_case, got '{}'",
            self.id
        );
        anyhow::ensure!(
            !self.display_name.trim().is_empty(),
            "engine '{}': display_name must not be blank",
            self.id
        );
        anyhow::ensure!(
            (0.0..=10.0).contains(&self.weight),
            "engine '{}': weight must be within 0.0..=10.0, got {}",
            self.id,
            self.weight
        );
        anyhow::ensure!(
            self.request.url.starts_with("https://"),
            "engine '{}': request url must use https",
            self.id
        );
        anyhow::ensure!(
            self.request.url.contains("{query}"),
            "engine '{}': request url must contain a {{query}} placeholder",
            self.id
        );
        anyhow::ensure!(
            self.request_host().is_some(),
            "engine '{}': request url host could not be parsed",
            self.id
        );
        if let Some(token) = &self.request.token {
            anyhow::ensure!(
                self.request.url.contains("{token}"),
                "engine '{}': token section present but url lacks {{token}}",
                self.id
            );
            anyhow::ensure!(
                token.url.starts_with("https://"),
                "engine '{}': token url must use https",
                self.id
            );
            let pattern = regex::Regex::new(&token.extract_pattern).map_err(|e| {
                anyhow::Error::msg(format!(
                    "engine '{}': token extract_pattern invalid: {e}",
                    self.id
                ))
            })?;
            anyhow::ensure!(
                pattern.captures_len() >= 2,
                "engine '{}': token extract_pattern needs one capture group",
                self.id
            );
        }
        match self.response.kind {
            ResponseKind::Html => {
                let html = self.response.html.as_ref().ok_or_else(|| {
                    anyhow::Error::msg(format!(
                        "engine '{}': kind is html but [response.html] missing",
                        self.id
                    ))
                })?;
                for (name, selector) in [
                    ("result_selector", Some(&html.result_selector)),
                    ("title_selector", Some(&html.title_selector)),
                    ("url_selector", Some(&html.url_selector)),
                    ("snippet_selector", html.snippet_selector.as_ref()),
                ] {
                    if let Some(selector) = selector {
                        scraper::Selector::parse(selector).map_err(|e| {
                            anyhow::Error::msg(format!(
                                "engine '{}': {name} invalid: {e}",
                                self.id
                            ))
                        })?;
                    }
                }
            }
            ResponseKind::Json => {
                let json = self.response.json.as_ref().ok_or_else(|| {
                    anyhow::Error::msg(format!(
                        "engine '{}': kind is json but [response.json] missing",
                        self.id
                    ))
                })?;
                anyhow::ensure!(
                    json.url_pointer.is_some() != json.url_template.is_some(),
                    "engine '{}': exactly one of url_pointer or url_template required",
                    self.id
                );
                anyhow::ensure!(
                    json.url_template.is_none() || json.url_template_value_pointer.is_some(),
                    "engine '{}': url_template requires url_template_value_pointer",
                    self.id
                );
                let pointers = [
                    Some(&json.results_pointer),
                    Some(&json.title_pointer),
                    json.url_pointer.as_ref(),
                    json.url_template_value_pointer.as_ref(),
                    json.snippet_pointer.as_ref(),
                ];
                for pointer in pointers.into_iter().flatten() {
                    anyhow::ensure!(
                        pointer.starts_with('/'),
                        "engine '{}': JSON pointer '{pointer}' must start with '/'",
                        self.id
                    );
                }
            }
        }
        anyhow::ensure!(
            !self.golden.query.trim().is_empty() && !self.golden.expected_domain.trim().is_empty(),
            "engine '{}': golden query and expected_domain must be set",
            self.id
        );
        Ok(())
    }

    /// Host of the request URL template, with placeholders substituted by
    /// dummy values so the template parses as a real URL.
    pub fn request_host(&self) -> Option<String> {
        let concrete = self
            .request
            .url
            .replace("{query}", "probe")
            .replace("{max_results}", "5")
            .replace("{token}", "probe");
        let url = reqwest::Url::parse(&concrete).ok()?;
        url.host_str().map(str::to_owned)
    }
}

/// Parses and validates the engine specs compiled into the binary.
///
/// A failure here is a programmer error (the bundled TOML is covered by unit
/// tests); callers degrade to an empty engine set rather than panicking so
/// nothing can unwind across the FFI boundary.
pub fn bundled_specs() -> anyhow::Result<Vec<EngineSpec>> {
    BUNDLED_SPECS
        .iter()
        .map(|raw| {
            let spec: EngineSpec = toml::from_str(raw).map_err(|e| {
                anyhow::Error::msg(format!("bundled engine spec failed to parse: {e}"))
            })?;
            spec.validate()?;
            Ok(spec)
        })
        .collect()
}

/// Resolves the effective engine set: bundled specs, each replaced by its
/// on-disk overlay (`<dir>/<id>.toml`) when a valid one exists.
///
/// Overlays come from the self-repair pipeline. A rejected overlay (parse
/// error, failed validation, host or golden-contract change) is logged and
/// skipped, leaving the bundled spec in force — the factory floor.
pub fn resolve_specs(overlay_dir: Option<&Path>) -> anyhow::Result<Vec<EngineSpec>> {
    let mut specs = bundled_specs()?;
    let Some(dir) = overlay_dir else {
        return Ok(specs);
    };
    for spec in &mut specs {
        let path = dir.join(format!("{}.toml", spec.id));
        match load_overlay(&path, spec) {
            Ok(Some(overlay)) => *spec = overlay,
            Ok(None) => {}
            Err(err) => {
                ::zeroclaw_log::record!(
                    WARN,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Reject)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(::serde_json::json!({
                            "engine": spec.id,
                            "path": path.display().to_string(),
                            "error": format!("{err:#}"),
                        })),
                    "metasearch: rejected engine spec overlay; keeping bundled spec"
                );
            }
        }
    }
    Ok(specs)
}

/// Loads one overlay file, returning `Ok(None)` when absent and an error
/// when present but unusable or attempting to escape its bundled contract.
fn load_overlay(path: &Path, bundled: &EngineSpec) -> anyhow::Result<Option<EngineSpec>> {
    let metadata = match std::fs::metadata(path) {
        Ok(metadata) => metadata,
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(err) => return Err(err.into()),
    };
    anyhow::ensure!(
        metadata.len() <= MAX_SPEC_FILE_BYTES,
        "overlay file is {} bytes, over the {MAX_SPEC_FILE_BYTES} byte limit",
        metadata.len()
    );
    let raw = std::fs::read_to_string(path)?;
    let overlay: EngineSpec = toml::from_str(&raw)?;
    overlay.validate()?;
    anyhow::ensure!(
        overlay.id == bundled.id,
        "overlay id '{}' does not match bundled id '{}'",
        overlay.id,
        bundled.id
    );
    anyhow::ensure!(
        overlay.request_host() == bundled.request_host(),
        "overlay must keep the bundled request host {:?}",
        bundled.request_host()
    );
    anyhow::ensure!(
        overlay.golden.expected_domain == bundled.golden.expected_domain,
        "overlay must keep the bundled golden expected_domain '{}'",
        bundled.golden.expected_domain
    );
    Ok(Some(overlay))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ddg_spec() -> EngineSpec {
        bundled_specs()
            .unwrap()
            .into_iter()
            .find(|s| s.id == "ddg_html")
            .unwrap()
    }

    #[test]
    fn bundled_specs_parse_and_validate() {
        let specs = bundled_specs().unwrap();
        let ids: Vec<&str> = specs.iter().map(|s| s.id.as_str()).collect();
        assert_eq!(ids, ["ddg_html", "mojeek", "wikipedia", "marginalia"]);
    }

    #[test]
    fn bundled_specs_cover_both_response_kinds() {
        let specs = bundled_specs().unwrap();
        assert!(specs.iter().any(|s| s.response.kind == ResponseKind::Html));
        assert!(specs.iter().any(|s| s.response.kind == ResponseKind::Json));
    }

    #[test]
    fn validate_rejects_http_url() {
        let mut spec = ddg_spec();
        spec.request.url = "http://html.duckduckgo.com/html/?q={query}".into();
        assert!(spec.validate().unwrap_err().to_string().contains("https"));
    }

    #[test]
    fn validate_rejects_missing_query_placeholder() {
        let mut spec = ddg_spec();
        spec.request.url = "https://html.duckduckgo.com/html/?q=fixed".into();
        assert!(spec.validate().unwrap_err().to_string().contains("{query}"));
    }

    #[test]
    fn validate_rejects_bad_selector() {
        let mut spec = ddg_spec();
        if let Some(html) = spec.response.html.as_mut() {
            html.result_selector = ":::not-a-selector".into();
        }
        assert!(
            spec.validate()
                .unwrap_err()
                .to_string()
                .contains("result_selector")
        );
    }

    #[test]
    fn validate_rejects_token_without_placeholder() {
        let mut spec = ddg_spec();
        spec.request.token = Some(TokenSpec {
            url: "https://duckduckgo.com/?q={query}".into(),
            extract_pattern: r#"vqd=([\d-]+)"#.into(),
        });
        assert!(spec.validate().unwrap_err().to_string().contains("{token}"));
    }

    #[test]
    fn spec_rejects_unknown_fields() {
        let raw = r#"
            id = "ddg_html"
            display_name = "DuckDuckGo"
            totally_unknown_field = true
        "#;
        assert!(toml::from_str::<EngineSpec>(raw).is_err());
    }

    #[test]
    fn resolve_specs_without_dir_returns_bundled() {
        let specs = resolve_specs(None).unwrap();
        assert_eq!(specs.len(), 4);
    }

    #[test]
    fn overlay_with_changed_selector_is_adopted() {
        let dir = tempfile::tempdir().unwrap();
        let mut overlay = ddg_spec();
        if let Some(html) = overlay.response.html.as_mut() {
            html.result_selector = "div.fresh-result".into();
        }
        std::fs::write(dir.path().join("ddg_html.toml"), spec_to_toml(&overlay)).unwrap();

        let specs = resolve_specs(Some(dir.path())).unwrap();
        let ddg = specs.iter().find(|s| s.id == "ddg_html").unwrap();
        assert_eq!(
            ddg.response.html.as_ref().unwrap().result_selector,
            "div.fresh-result"
        );
    }

    #[test]
    fn overlay_changing_host_is_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let mut overlay = ddg_spec();
        overlay.request.url = "https://evil.example.com/html/?q={query}".into();
        std::fs::write(dir.path().join("ddg_html.toml"), spec_to_toml(&overlay)).unwrap();

        let specs = resolve_specs(Some(dir.path())).unwrap();
        let ddg = specs.iter().find(|s| s.id == "ddg_html").unwrap();
        assert!(ddg.request.url.contains("html.duckduckgo.com"));
    }

    #[test]
    fn overlay_changing_golden_domain_is_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let mut overlay = ddg_spec();
        overlay.golden.expected_domain = "evil.example.com".into();
        std::fs::write(dir.path().join("ddg_html.toml"), spec_to_toml(&overlay)).unwrap();

        let specs = resolve_specs(Some(dir.path())).unwrap();
        let ddg = specs.iter().find(|s| s.id == "ddg_html").unwrap();
        assert_eq!(ddg.golden.expected_domain, "wikipedia.org");
    }

    #[test]
    fn oversized_overlay_is_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let huge = "# padding\n".repeat(8 * 1024);
        std::fs::write(dir.path().join("ddg_html.toml"), huge).unwrap();

        let specs = resolve_specs(Some(dir.path())).unwrap();
        let ddg = specs.iter().find(|s| s.id == "ddg_html").unwrap();
        assert!(ddg.request.url.contains("html.duckduckgo.com"));
    }

    /// Serializes a spec back to TOML for overlay tests. Specs are
    /// deserialize-only in production, so tests hand-roll the document.
    fn spec_to_toml(spec: &EngineSpec) -> String {
        let mut out = String::new();
        out.push_str(&format!("id = \"{}\"\n", spec.id));
        out.push_str(&format!("display_name = \"{}\"\n", spec.display_name));
        out.push_str(&format!("weight = {:.1}\n", spec.weight));
        if !spec.block_markers.is_empty() {
            let markers: Vec<String> = spec
                .block_markers
                .iter()
                .map(|m| format!("\"{}\"", m.replace('\\', "\\\\")))
                .collect();
            out.push_str(&format!("block_markers = [{}]\n", markers.join(", ")));
        }
        out.push_str("\n[request]\n");
        out.push_str(&format!("url = \"{}\"\n", spec.request.url));
        if let Some(ua) = &spec.request.user_agent {
            out.push_str(&format!("user_agent = \"{ua}\"\n"));
        }
        out.push_str("\n[response]\n");
        match spec.response.kind {
            ResponseKind::Html => out.push_str("kind = \"html\"\n"),
            ResponseKind::Json => out.push_str("kind = \"json\"\n"),
        }
        if let Some(html) = &spec.response.html {
            out.push_str("\n[response.html]\n");
            out.push_str(&format!("result_selector = \"{}\"\n", html.result_selector));
            out.push_str(&format!("title_selector = \"{}\"\n", html.title_selector));
            out.push_str(&format!("url_selector = \"{}\"\n", html.url_selector));
            if let Some(snippet) = &html.snippet_selector {
                out.push_str(&format!("snippet_selector = \"{snippet}\"\n"));
            }
            if let Some(param) = &html.url_unwrap_param {
                out.push_str(&format!("url_unwrap_param = \"{param}\"\n"));
            }
        }
        if let Some(json) = &spec.response.json {
            out.push_str("\n[response.json]\n");
            out.push_str(&format!("results_pointer = \"{}\"\n", json.results_pointer));
            out.push_str(&format!("title_pointer = \"{}\"\n", json.title_pointer));
            if let Some(p) = &json.url_pointer {
                out.push_str(&format!("url_pointer = \"{p}\"\n"));
            }
            if let Some(t) = &json.url_template {
                out.push_str(&format!("url_template = \"{t}\"\n"));
            }
            if let Some(p) = &json.url_template_value_pointer {
                out.push_str(&format!("url_template_value_pointer = \"{p}\"\n"));
            }
            if let Some(p) = &json.snippet_pointer {
                out.push_str(&format!("snippet_pointer = \"{p}\"\n"));
            }
        }
        out.push_str("\n[golden]\n");
        out.push_str(&format!("query = \"{}\"\n", spec.golden.query));
        out.push_str(&format!(
            "expected_domain = \"{}\"\n",
            spec.golden.expected_domain
        ));
        out
    }
}
