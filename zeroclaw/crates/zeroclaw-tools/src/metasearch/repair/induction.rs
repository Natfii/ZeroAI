// Copyright (c) 2026 @Natfii. All rights reserved.

//! Deterministic selector re-derivation via wrapper induction.
//!
//! Given a golden-query SERP body and the domain that must appear in it,
//! this module rediscovers result selectors without any model involvement:
//! find the anchor pointing at the known domain, walk up to the repeating
//! sibling structure that holds one result each, and generalize selectors
//! from what it finds. Handles the common breakage class (renamed CSS
//! classes, reshuffled wrappers) for free; the model rung only sees pages
//! this can't solve.

use crate::metasearch::spec::HtmlResponseSpec;
use scraper::ElementRef;

/// Minimum sibling results required to accept a repeated-container candidate.
const MIN_REPEATED_SIBLINGS: usize = 3;

/// Minimum collapsed text length for a snippet candidate.
const MIN_SNIPPET_CHARS: usize = 30;

/// Attempts to derive a fresh [`HtmlResponseSpec`] from a golden-probe SERP
/// body. Returns `None` when no repeated result structure can be found; the
/// caller then escalates to the model rung.
pub fn derive(body: &str, expected_domain: &str) -> Option<HtmlResponseSpec> {
    let document = scraper::Html::parse_document(body);
    let anchor_selector = parse_static_selector("a[href]");
    let golden_anchors: Vec<ElementRef> = document
        .select(&anchor_selector)
        .filter(|anchor| {
            anchor.value().attr("href").is_some_and(|href| {
                is_external_href(href) && href_contains_domain(href, expected_domain)
            })
        })
        .collect();

    golden_anchors
        .iter()
        .find_map(|anchor| derive_from_anchor(*anchor, expected_domain))
}

/// Builds a selector from a pattern known to be valid at compile time.
fn parse_static_selector(pattern: &str) -> scraper::Selector {
    scraper::Selector::parse(pattern).expect("literal selector pattern must compile")
}

fn derive_from_anchor(anchor: ElementRef, expected_domain: &str) -> Option<HtmlResponseSpec> {
    let (container, siblings) = find_repeated_container(anchor)?;
    let result_selector = container_selector(container, &siblings)?;
    let title_anchor = best_title_anchor(container)?;
    let title_selector = element_selector_within(title_anchor, container)?;
    let url_unwrap_param = title_anchor
        .value()
        .attr("href")
        .and_then(|href| detect_unwrap_param(href, expected_domain));
    let snippet_selector = best_snippet_element(container, title_anchor)
        .and_then(|element| element_selector_within(element, container));

    let candidate = HtmlResponseSpec {
        result_selector,
        title_selector: title_selector.clone(),
        url_selector: title_selector,
        url_attribute: "href".into(),
        snippet_selector,
        url_unwrap_param,
    };
    Some(candidate)
}

/// Walks up from a golden anchor to the deepest ancestor that repeats as a
/// same-tag sibling group where most siblings contain an external link —
/// the signature of a one-element-per-result container.
fn find_repeated_container<'a>(
    anchor: ElementRef<'a>,
) -> Option<(ElementRef<'a>, Vec<ElementRef<'a>>)> {
    for ancestor in anchor.ancestors().filter_map(ElementRef::wrap) {
        let tag = ancestor.value().name();
        if tag == "body" || tag == "html" {
            return None;
        }
        let Some(parent) = ancestor.parent().and_then(ElementRef::wrap) else {
            continue;
        };
        let same_tag_siblings: Vec<ElementRef<'a>> = parent
            .children()
            .filter_map(ElementRef::wrap)
            .filter(|sibling| sibling.value().name() == tag)
            .collect();
        let with_external_anchor = same_tag_siblings
            .iter()
            .filter(|sibling| contains_external_anchor(**sibling))
            .count();
        if same_tag_siblings.len() >= MIN_REPEATED_SIBLINGS
            && with_external_anchor >= MIN_REPEATED_SIBLINGS
        {
            return Some((ancestor, same_tag_siblings));
        }
    }
    None
}

/// Selector for the repeated container: container classes shared by enough
/// siblings to be the result-row signature (an odd sibling such as an
/// instant-answer box must not erase them), otherwise qualified by the
/// parent element.
fn container_selector(container: ElementRef, siblings: &[ElementRef]) -> Option<String> {
    let tag = container.value().name().to_owned();
    let sibling_classes: Vec<Vec<String>> =
        siblings.iter().map(|s| css_safe_classes(*s)).collect();
    let common: Vec<String> = css_safe_classes(container)
        .into_iter()
        .filter(|class| {
            sibling_classes
                .iter()
                .filter(|classes| classes.contains(class))
                .count()
                >= MIN_REPEATED_SIBLINGS
        })
        .collect();
    let selector = if common.is_empty() {
        let parent = container.parent().and_then(ElementRef::wrap)?;
        let parent_classes = css_safe_classes(parent);
        let parent_part = if parent_classes.is_empty() {
            parent.value().name().to_owned()
        } else {
            format!("{}.{}", parent.value().name(), parent_classes.join("."))
        };
        format!("{parent_part} {tag}")
    } else {
        format!("{tag}.{}", common.join("."))
    };
    validate_derived_selector(selector)
}

/// First external-href anchor in document order whose text reads like a
/// title rather than a displayed URL (filters out URL-line anchors such as
/// Mojeek's `a.ob`).
fn best_title_anchor(container: ElementRef<'_>) -> Option<ElementRef<'_>> {
    let anchor_selector = parse_static_selector("a[href]");
    container.select(&anchor_selector).find(|anchor| {
        let href_ok = anchor
            .value()
            .attr("href")
            .is_some_and(is_external_href);
        let text = collapse(&anchor.text().collect::<String>());
        href_ok && !text.is_empty() && !looks_like_url(&text)
    })
}

/// Longest text-bearing element inside the container that does not contain
/// the title anchor (which would make it a wrapper, not a snippet).
fn best_snippet_element<'a>(
    container: ElementRef<'a>,
    title_anchor: ElementRef<'a>,
) -> Option<ElementRef<'a>> {
    let candidate_selector = parse_static_selector("p, span, td, div, a");
    let title_id = title_anchor.id();
    let title_text = collapse(&title_anchor.text().collect::<String>());
    container
        .select(&candidate_selector)
        .filter(|element| {
            element.id() != title_id
                && !element
                    .descendants()
                    .any(|descendant| descendant.id() == title_id)
        })
        .map(|element| {
            let text = collapse(&element.text().collect::<String>());
            (element, text)
        })
        .filter(|(_, text)| text.len() >= MIN_SNIPPET_CHARS && *text != title_text)
        .max_by_key(|(_, text)| text.len())
        .map(|(element, _)| element)
}

/// Relative selector for an element inside the container: class-based when
/// classes exist, otherwise qualified by the parent tag.
fn element_selector_within(element: ElementRef, container: ElementRef) -> Option<String> {
    let tag = element.value().name().to_owned();
    let classes = css_safe_classes(element);
    let selector = if classes.is_empty() {
        match element.parent().and_then(ElementRef::wrap) {
            Some(parent) if parent.id() != container.id() => {
                format!("{} {tag}", parent.value().name())
            }
            _ => tag,
        }
    } else {
        format!("{tag}.{}", classes.join("."))
    };
    validate_derived_selector(selector)
}

/// Finds a query parameter whose percent-decoded value is a URL on the
/// expected domain — the redirect-unwrap signature (e.g. DuckDuckGo `uddg`).
fn detect_unwrap_param(href: &str, expected_domain: &str) -> Option<String> {
    let authority = href
        .trim_start_matches("https:")
        .trim_start_matches("http:")
        .trim_start_matches("//");
    let host = authority.split(['/', '?']).next().unwrap_or_default();
    if host.contains(expected_domain) {
        return None;
    }
    let query = href.split_once('?')?.1;
    for pair in query.split('&') {
        let Some((name, value)) = pair.split_once('=') else {
            continue;
        };
        if let Ok(decoded) = urlencoding::decode(value)
            && decoded.starts_with("http")
            && decoded.contains(expected_domain)
        {
            return Some(name.to_owned());
        }
    }
    None
}

fn contains_external_anchor(element: ElementRef) -> bool {
    let anchor_selector = parse_static_selector("a[href]");
    element
        .select(&anchor_selector)
        .any(|anchor| anchor.value().attr("href").is_some_and(is_external_href))
}

fn css_safe_classes(element: ElementRef) -> Vec<String> {
    element
        .value()
        .classes()
        .filter(|class| {
            !class.is_empty()
                && !class.starts_with(|c: char| c.is_ascii_digit() || c == '-')
                && class
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        })
        .map(str::to_owned)
        .collect()
}

fn validate_derived_selector(selector: String) -> Option<String> {
    scraper::Selector::parse(&selector).ok()?;
    Some(selector)
}

fn is_external_href(href: &str) -> bool {
    href.starts_with("https://") || href.starts_with("http://") || href.starts_with("//")
}

/// Matches the expected domain against the raw href and its percent-decoded
/// form — redirect-style links encode the target URL (DuckDuckGo encodes
/// even dashes, so `rust-lang.org` appears as `rust%2Dlang.org`).
fn href_contains_domain(href: &str, domain: &str) -> bool {
    if href.contains(domain) {
        return true;
    }
    urlencoding::decode(href)
        .map(|decoded| decoded.contains(domain))
        .unwrap_or(false)
}

fn looks_like_url(text: &str) -> bool {
    let lowered = text.to_ascii_lowercase();
    lowered.starts_with("http") || lowered.starts_with("www.") || lowered.contains("://")
}

fn collapse(raw: &str) -> String {
    raw.split_whitespace().collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metasearch::executor::parse_response;
    use crate::metasearch::spec::{EngineSpec, ResponseKind, bundled_specs};

    const DDG_FIXTURE: &str = include_str!("../fixtures/ddg_html.html");
    const MOJEEK_FIXTURE: &str = include_str!("../fixtures/mojeek.html");
    const DDG_BLOCK_FIXTURE: &str = include_str!("../fixtures/ddg_anomaly_block.html");

    fn spec_with_html(base_id: &str, html: HtmlResponseSpec) -> EngineSpec {
        let mut spec = bundled_specs()
            .unwrap()
            .into_iter()
            .find(|s| s.id == base_id)
            .unwrap();
        assert_eq!(spec.response.kind, ResponseKind::Html);
        spec.response.html = Some(html);
        spec
    }

    #[test]
    fn rediscovers_working_selectors_from_real_ddg_serp() {
        let derived = derive(DDG_FIXTURE, "rust-lang.org").expect("induction should succeed");
        let spec = spec_with_html("ddg_html", derived.clone());
        let results = parse_response(&spec, DDG_FIXTURE, 10).unwrap();
        assert!(results.len() >= 3, "got {} results: {derived:?}", results.len());
        assert!(
            results.iter().any(|r| r.url.contains("rust-lang.org")),
            "expected rust-lang.org in {results:?}"
        );
        assert_eq!(
            derived.url_unwrap_param.as_deref(),
            Some("uddg"),
            "DDG redirect unwrap must be rediscovered"
        );
    }

    #[test]
    fn rediscovers_working_selectors_from_real_mojeek_serp() {
        let derived = derive(MOJEEK_FIXTURE, "rust-lang.org").expect("induction should succeed");
        let spec = spec_with_html("mojeek", derived.clone());
        let results = parse_response(&spec, MOJEEK_FIXTURE, 10).unwrap();
        assert!(results.len() >= 3, "got {} results: {derived:?}", results.len());
        assert!(results.iter().any(|r| r.url.contains("rust-lang.org")));
        assert!(
            results
                .iter()
                .all(|r| !looks_like_url(&r.title)),
            "titles must be real titles, not URL lines: {results:?}"
        );
    }

    #[test]
    fn derives_from_synthetic_redesigned_serp() {
        let body = synthetic_serp("fresh-results", "hit", "headline", "blurb");
        let derived = derive(&body, "wikipedia.org").expect("induction should succeed");
        assert!(derived.result_selector.contains("hit"));
        let spec = spec_with_html("ddg_html", derived);
        let results = parse_response(&spec, &body, 10).unwrap();
        assert_eq!(results.len(), 4);
        assert!(results.iter().any(|r| r.url.contains("wikipedia.org")));
    }

    #[test]
    fn returns_none_for_block_page_without_result_structure() {
        assert!(derive(DDG_BLOCK_FIXTURE, "wikipedia.org").is_none());
    }

    #[test]
    fn detect_unwrap_param_ignores_direct_links() {
        assert_eq!(
            detect_unwrap_param("https://en.wikipedia.org/wiki/Earth", "wikipedia.org"),
            None
        );
        assert_eq!(
            detect_unwrap_param(
                "//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FEarth&rut=x",
                "wikipedia.org"
            ),
            Some("uddg".to_owned())
        );
    }

    /// Builds a small but realistic redesigned SERP whose class vocabulary
    /// shares nothing with the bundled specs.
    fn synthetic_serp(list_class: &str, item_class: &str, title_class: &str, blurb_class: &str) -> String {
        let mut items = String::new();
        let sites = [
            ("https://en.wikipedia.org/wiki/Earth", "Earth - Wikipedia"),
            ("https://example.com/one", "First Example Page"),
            ("https://example.org/two", "Second Example Page"),
            ("https://example.net/three", "Third Example Page"),
        ];
        for (url, title) in sites {
            items.push_str(&format!(
                "<li class=\"{item_class}\"><h3><a class=\"{title_class}\" href=\"{url}\">{title}</a></h3>\
                 <p class=\"{blurb_class}\">A descriptive snippet for {title} that is long enough to qualify.</p></li>"
            ));
        }
        format!(
            "<html><body><div id=\"chrome\"><a href=\"/settings\">Settings</a></div>\
             <ul class=\"{list_class}\">{items}</ul></body></html>"
        )
    }
}
