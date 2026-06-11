// Copyright (c) 2026 @Natfii. All rights reserved.

//! Model-assisted selector derivation — the escalation rung used when
//! wrapper induction cannot solve a redesigned SERP.
//!
//! The model never sees raw HTML: the body is first skeletonized into a
//! compact structural outline (tags, classes, truncated hrefs and text)
//! that fits comfortably in a small on-device model's context. The model's
//! answer is parsed strictly and still has to pass the model-free
//! validation gate before adoption, so a bad model cannot break anything.

use crate::metasearch::spec::HtmlResponseSpec;

/// Maximum skeleton size handed to the model (bytes, pre-prompt).
const MAX_SKELETON_BYTES: usize = 6 * 1024;

const MAX_TEXT_CHARS: usize = 60;
const MAX_HREF_CHARS: usize = 80;

/// Provider settings for the repair model rung, captured at tool
/// registration from the active agent's model configuration.
#[derive(Clone)]
pub struct RepairModelConfig {
    /// ModelProvider type (e.g. "openrouter", "custom-openai").
    pub provider: String,
    /// Model identifier passed to the provider.
    pub model: String,
    /// API key, when the provider requires one.
    pub api_key: Option<String>,
    /// Runtime options inherited from root config (base URL, proxy, etc.).
    pub runtime_options: zeroclaw_providers::ModelProviderRuntimeOptions,
}

impl RepairModelConfig {
    /// Derives the repair-model settings from the first configured model
    /// provider — the same provider the active agent chats through.
    ///
    /// Returns `None` (disabling the model rung) when no provider or no
    /// model is configured; the deterministic repair tiers still run.
    pub fn from_config(config: &zeroclaw_config::schema::Config) -> Option<Self> {
        let provider = config.first_model_provider_type()?.to_owned();
        let model = config
            .first_model_provider()
            .and_then(|entry| entry.model.clone())
            .filter(|model| !model.trim().is_empty())?;
        Some(Self {
            provider,
            model,
            api_key: config
                .first_model_provider()
                .and_then(|entry| entry.api_key.clone()),
            runtime_options: zeroclaw_providers::provider_runtime_options_from_config(config),
        })
    }
}

/// Asks the configured model to derive fresh selectors from a skeletonized
/// SERP body. The result must still pass the validation gate.
pub async fn derive(
    config: &RepairModelConfig,
    engine_id: &str,
    body: &str,
) -> anyhow::Result<HtmlResponseSpec> {
    let skeleton = skeletonize(body, MAX_SKELETON_BYTES);
    let prompt = build_repair_prompt(engine_id, &skeleton);
    let provider = zeroclaw_providers::create_model_provider_with_options(
        &config.provider,
        config.api_key.as_deref(),
        &config.runtime_options,
    )?;
    let response = provider
        .simple_chat(&prompt, &config.model, Some(0.0))
        .await?;
    parse_model_selectors(&response)
}

/// Collapses an HTML document into an indented structural outline: tag,
/// classes, id, truncated href, and truncated text — everything a model
/// needs to write CSS selectors, at a fraction of the size.
pub(crate) fn skeletonize(body: &str, max_bytes: usize) -> String {
    let document = scraper::Html::parse_document(body);
    let mut out = String::new();
    emit_children(document.tree.root(), &mut out, 0, max_bytes);
    if out.len() > max_bytes {
        out.truncate(max_bytes);
        out.push_str("\n…(truncated)");
    }
    out
}

fn emit_children(
    node: ego_tree::NodeRef<'_, scraper::Node>,
    out: &mut String,
    depth: usize,
    max_bytes: usize,
) {
    for child in node.children() {
        if out.len() >= max_bytes {
            return;
        }
        match child.value() {
            scraper::Node::Element(element) => {
                let tag = element.name();
                if matches!(
                    tag,
                    "script" | "style" | "svg" | "noscript" | "head" | "link" | "meta" | "iframe"
                ) {
                    continue;
                }
                out.push_str(&"  ".repeat(depth));
                out.push_str(tag);
                for class in element.classes() {
                    out.push('.');
                    out.push_str(class);
                }
                if let Some(id) = element.id() {
                    out.push('#');
                    out.push_str(id);
                }
                if let Some(href) = element.attr("href") {
                    out.push_str(" [href=");
                    out.push_str(&truncate_chars(href, MAX_HREF_CHARS));
                    out.push(']');
                }
                out.push('\n');
                emit_children(child, out, depth + 1, max_bytes);
            }
            scraper::Node::Text(text) => {
                let collapsed = text.split_whitespace().collect::<Vec<_>>().join(" ");
                if !collapsed.is_empty() {
                    out.push_str(&"  ".repeat(depth));
                    out.push('"');
                    out.push_str(&truncate_chars(&collapsed, MAX_TEXT_CHARS));
                    out.push_str("\"\n");
                }
            }
            _ => {}
        }
    }
}

fn truncate_chars(raw: &str, max_chars: usize) -> String {
    if raw.chars().count() <= max_chars {
        raw.to_owned()
    } else {
        let truncated: String = raw.chars().take(max_chars).collect();
        format!("{truncated}…")
    }
}

fn build_repair_prompt(engine_id: &str, skeleton: &str) -> String {
    format!(
        "You are repairing a broken search-result parser for the '{engine_id}' search engine.\n\
         Below is a structural outline of a live search results page (one line per HTML\n\
         element: tag.classes [href=...], with quoted text contents indented beneath).\n\
         \n\
         Identify the repeated per-result container and answer with CSS selectors:\n\
         - result_selector: matches one element per organic search result\n\
         - title_selector: within a result, the element whose text is the result title\n\
         - url_selector: within a result, the <a> whose href is the result link\n\
         - snippet_selector: within a result, the description text element (omit if none)\n\
         - url_unwrap_param: if hrefs are redirect links carrying the real URL in a query\n\
           parameter, the parameter name (omit if hrefs are direct)\n\
         \n\
         Respond ONLY with a JSON object containing those keys, no explanation:\n\
         {{\"result_selector\": \"...\", \"title_selector\": \"...\", \"url_selector\": \"...\"}}\n\
         \n\
         Page outline:\n\
         {skeleton}"
    )
}

#[derive(serde::Deserialize)]
struct ModelSelectors {
    result_selector: String,
    title_selector: String,
    #[serde(default)]
    url_selector: Option<String>,
    #[serde(default)]
    snippet_selector: Option<String>,
    #[serde(default)]
    url_unwrap_param: Option<String>,
}

/// Parses the model's JSON answer (tolerating markdown fences and prose
/// around the JSON object) into an [`HtmlResponseSpec`].
pub(crate) fn parse_model_selectors(response: &str) -> anyhow::Result<HtmlResponseSpec> {
    let start = response
        .find('{')
        .ok_or_else(|| anyhow::Error::msg("model response contains no JSON object"))?;
    let end = response
        .rfind('}')
        .ok_or_else(|| anyhow::Error::msg("model response contains no closing brace"))?;
    anyhow::ensure!(start < end, "model response JSON braces are inverted");
    let parsed: ModelSelectors = serde_json::from_str(&response[start..=end])
        .map_err(|e| anyhow::Error::msg(format!("model response is not valid JSON: {e}")))?;

    let title_selector = non_empty(parsed.title_selector)
        .ok_or_else(|| anyhow::Error::msg("model returned an empty title_selector"))?;
    let result_selector = non_empty(parsed.result_selector)
        .ok_or_else(|| anyhow::Error::msg("model returned an empty result_selector"))?;
    let url_selector = parsed
        .url_selector
        .and_then(non_empty)
        .unwrap_or_else(|| title_selector.clone());
    Ok(HtmlResponseSpec {
        result_selector,
        title_selector,
        url_selector,
        url_attribute: "href".into(),
        snippet_selector: parsed.snippet_selector.and_then(non_empty),
        url_unwrap_param: parsed.url_unwrap_param.and_then(non_empty),
    })
}

fn non_empty(raw: String) -> Option<String> {
    let trimmed = raw.trim().to_owned();
    if trimmed.is_empty() { None } else { Some(trimmed) }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DDG_FIXTURE: &str = include_str!("../fixtures/ddg_html.html");

    #[test]
    fn skeleton_is_compact_and_keeps_class_vocabulary() {
        let skeleton = skeletonize(DDG_FIXTURE, MAX_SKELETON_BYTES);
        assert!(skeleton.len() <= MAX_SKELETON_BYTES + 32);
        assert!(skeleton.contains("result__a"), "class names must survive");
        assert!(!skeleton.contains("<script"), "scripts must be dropped");
    }

    #[test]
    fn parse_accepts_plain_json() {
        let spec = parse_model_selectors(
            r#"{"result_selector": "li.hit", "title_selector": "a.headline", "url_selector": "a.headline", "snippet_selector": "p.blurb"}"#,
        )
        .unwrap();
        assert_eq!(spec.result_selector, "li.hit");
        assert_eq!(spec.snippet_selector.as_deref(), Some("p.blurb"));
        assert_eq!(spec.url_attribute, "href");
    }

    #[test]
    fn parse_accepts_fenced_json_with_prose() {
        let spec = parse_model_selectors(
            "Here is the repair:\n```json\n{\"result_selector\": \"div.r\", \"title_selector\": \"h3 a\"}\n```\nGood luck!",
        )
        .unwrap();
        assert_eq!(spec.result_selector, "div.r");
        assert_eq!(spec.url_selector, "h3 a", "url defaults to title selector");
    }

    #[test]
    fn parse_rejects_garbage_and_empty_selectors() {
        assert!(parse_model_selectors("I could not figure it out, sorry!").is_err());
        assert!(
            parse_model_selectors(r#"{"result_selector": "", "title_selector": "a"}"#).is_err()
        );
    }

    #[test]
    fn prompt_includes_contract_and_skeleton() {
        let prompt = build_repair_prompt("ddg_html", "ul.fresh\n  li.hit");
        assert!(prompt.contains("result_selector"));
        assert!(prompt.contains("ul.fresh"));
        assert!(prompt.contains("ONLY with a JSON object"));
    }
}
