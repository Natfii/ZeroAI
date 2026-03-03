/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! URL validation helpers for FFI tool implementations.
//!
//! Replicates the private URL-validation logic from upstream's
//! `web_fetch` and `http_request` tools into a shared `pub(crate)`
//! module so that [`crate`]-level tool wrappers (`FfiWebFetchTool`,
//! `FfiHttpRequestTool`) can reuse the same validation pipeline
//! without duplicating it.
//!
//! All functions return `Result<T, String>` (not `anyhow`) to keep
//! the module dependency-light.  DNS-resolution checks
//! (`validate_resolved_host_is_public`) are intentionally omitted
//! because they require async networking; the static checks here are
//! sufficient for FFI-side pre-validation.

// Functions are pub(crate) but have no callers until Tasks 21/22 add
// FfiWebFetchTool and FfiHttpRequestTool.
#![allow(dead_code)]

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// Normalises a list of domain strings for allowlist / blocklist matching.
///
/// Each domain is lowercased, stripped of scheme prefixes (`http://`,
/// `https://`), path suffixes, leading/trailing dots, and port numbers.
/// Empty or whitespace-only entries are discarded, and the result is
/// sorted and de-duplicated.
pub(crate) fn normalize_allowed_domains(domains: Vec<String>) -> Vec<String> {
    let mut normalized = domains
        .into_iter()
        .filter_map(|d| normalize_domain(&d))
        .collect::<Vec<_>>();
    normalized.sort_unstable();
    normalized.dedup();
    normalized
}

/// Normalises a single raw domain string.
///
/// Returns `None` when the input is empty, whitespace-only, or
/// otherwise unparseable after stripping.
fn normalize_domain(raw: &str) -> Option<String> {
    let mut d = raw.trim().to_lowercase();
    if d.is_empty() {
        return None;
    }

    if let Some(stripped) = d.strip_prefix("https://") {
        d = stripped.to_string();
    } else if let Some(stripped) = d.strip_prefix("http://") {
        d = stripped.to_string();
    }

    if let Some((host, _)) = d.split_once('/') {
        d = host.to_string();
    }

    d = d.trim_start_matches('.').trim_end_matches('.').to_string();

    if let Some((host, _)) = d.split_once(':') {
        d = host.to_string();
    }

    if d.is_empty() || d.chars().any(char::is_whitespace) {
        return None;
    }

    Some(d)
}

/// Extracts the host component from an HTTP(S) URL.
///
/// Rejects URLs that contain userinfo (`@`), IPv6 bracket notation,
/// or an empty host component.
pub(crate) fn extract_host(url: &str) -> Result<String, String> {
    let rest = url
        .strip_prefix("http://")
        .or_else(|| url.strip_prefix("https://"))
        .ok_or_else(|| "Only http:// and https:// URLs are allowed".to_string())?;

    let authority = rest
        .split(['/', '?', '#'])
        .next()
        .ok_or_else(|| "Invalid URL".to_string())?;

    if authority.is_empty() {
        return Err("URL must include a host".to_string());
    }

    if authority.contains('@') {
        return Err("URL userinfo is not allowed".to_string());
    }

    if authority.starts_with('[') {
        return Err("IPv6 hosts are not supported".to_string());
    }

    let host = authority
        .split(':')
        .next()
        .unwrap_or_default()
        .trim()
        .trim_end_matches('.')
        .to_lowercase();

    if host.is_empty() {
        return Err("URL must include a valid host".to_string());
    }

    Ok(host)
}

/// Returns `true` when `host` resolves to a private, loopback,
/// link-local, multicast, documentation, shared-address-space, or
/// otherwise non-globally-routable address.
///
/// Covers:
/// - `localhost` and `*.localhost`
/// - `.local` mDNS TLD
/// - IPv4 loopback (`127.0.0.0/8`), private (RFC 1918), link-local
///   (`169.254.0.0/16`), shared address space (`100.64.0.0/10`),
///   documentation (`192.0.2.0/24`, `198.51.100.0/24`,
///   `203.0.113.0/24`), benchmarking (`198.18.0.0/15`), broadcast,
///   multicast, and reserved (`240.0.0.0/4`)
/// - IPv6 loopback, unspecified, multicast, ULA (`fc00::/7`),
///   link-local (`fe80::/10`), documentation (`2001:db8::/32`), and
///   IPv4-mapped addresses that map to non-global IPv4
pub(crate) fn is_private_or_local_host(host: &str) -> bool {
    let bare = host
        .strip_prefix('[')
        .and_then(|h| h.strip_suffix(']'))
        .unwrap_or(host);

    let has_local_tld = bare
        .rsplit('.')
        .next()
        .is_some_and(|label| label == "local");

    if bare == "localhost" || bare.ends_with(".localhost") || has_local_tld {
        return true;
    }

    if let Ok(ip) = bare.parse::<IpAddr>() {
        return match ip {
            IpAddr::V4(v4) => is_non_global_v4(v4),
            IpAddr::V6(v6) => is_non_global_v6(v6),
        };
    }

    false
}

/// Returns `true` when `host` matches any entry in the allowlist.
///
/// A wildcard entry (`"*"`) matches everything.  Otherwise, the host
/// must either equal the domain exactly or be a proper subdomain
/// (i.e. `sub.example.com` matches `example.com` because the prefix
/// before the domain ends with `.`).
pub(crate) fn host_matches_allowlist(host: &str, allowed_domains: &[String]) -> bool {
    if allowed_domains.iter().any(|domain| domain == "*") {
        return true;
    }

    allowed_domains.iter().any(|domain| {
        host == domain
            || host
                .strip_suffix(domain)
                .is_some_and(|prefix| prefix.ends_with('.'))
    })
}

/// Orchestrates full URL validation for an FFI tool.
///
/// Checks, in order:
/// 1. URL is non-empty and trimmed
/// 2. URL contains no internal whitespace
/// 3. Scheme is `http://` or `https://`
/// 4. Allowed-domains list is non-empty (misconfiguration guard)
/// 5. Host is extractable (no userinfo, no IPv6, non-empty)
/// 6. Host is not private / local
/// 7. Host is not in the blocked-domains list
/// 8. Host is in the allowed-domains list
///
/// Returns the trimmed URL on success.
pub(crate) fn validate_target_url(
    raw_url: &str,
    allowed_domains: &[String],
    blocked_domains: &[String],
    tool_name: &str,
) -> Result<String, String> {
    let url = raw_url.trim();

    if url.is_empty() {
        return Err("URL cannot be empty".to_string());
    }

    if url.chars().any(char::is_whitespace) {
        return Err("URL cannot contain whitespace".to_string());
    }

    if !url.starts_with("http://") && !url.starts_with("https://") {
        return Err("Only http:// and https:// URLs are allowed".to_string());
    }

    if allowed_domains.is_empty() {
        return Err(format!(
            "{tool_name} tool is enabled but no allowed_domains are configured. \
             Add [{tool_name}].allowed_domains in config.toml"
        ));
    }

    let host = extract_host(url)?;

    if is_private_or_local_host(&host) {
        return Err(format!("Blocked local/private host: {host}"));
    }

    if host_matches_allowlist(&host, blocked_domains) {
        return Err(format!("Host '{host}' is in {tool_name}.blocked_domains"));
    }

    if !host_matches_allowlist(&host, allowed_domains) {
        return Err(format!(
            "Host '{host}' is not in {tool_name}.allowed_domains"
        ));
    }

    Ok(url.to_string())
}

/// Returns `true` for IPv4 addresses that are not globally routable.
fn is_non_global_v4(v4: Ipv4Addr) -> bool {
    let [a, b, c, _] = v4.octets();
    v4.is_loopback()
        || v4.is_private()
        || v4.is_link_local()
        || v4.is_unspecified()
        || v4.is_broadcast()
        || v4.is_multicast()
        // Shared address space (RFC 6598) — 100.64.0.0/10
        || (a == 100 && (64..=127).contains(&b))
        // Reserved for future use — 240.0.0.0/4
        || a >= 240
        // IETF protocol assignments — 192.0.0.0/24
        // Documentation (TEST-NET-1) — 192.0.2.0/24
        || (a == 192 && b == 0 && (c == 0 || c == 2))
        // Documentation (TEST-NET-2) — 198.51.100.0/24
        || (a == 198 && b == 51)
        // Documentation (TEST-NET-3) — 203.0.113.0/24
        || (a == 203 && b == 0)
        // Benchmarking — 198.18.0.0/15
        || (a == 198 && (18..=19).contains(&b))
}

/// Returns `true` for IPv6 addresses that are not globally routable.
fn is_non_global_v6(v6: Ipv6Addr) -> bool {
    let segs = v6.segments();
    v6.is_loopback()
        || v6.is_unspecified()
        || v6.is_multicast()
        // Unique-local (ULA) — fc00::/7
        || (segs[0] & 0xfe00) == 0xfc00
        // Link-local — fe80::/10
        || (segs[0] & 0xffc0) == 0xfe80
        // Documentation — 2001:db8::/32
        || (segs[0] == 0x2001 && segs[1] == 0x0db8)
        // IPv4-mapped addresses — delegate to IPv4 check
        || v6.to_ipv4_mapped().is_some_and(is_non_global_v4)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    // ── normalize_domain ────────────────────────────────────────

    #[test]
    fn normalize_strips_https_and_lowercases() {
        let got = normalize_domain("  HTTPS://Docs.Example.com/path ").unwrap();
        assert_eq!(got, "docs.example.com");
    }

    #[test]
    fn normalize_strips_http() {
        let got = normalize_domain("http://API.Example.COM:8080/v1").unwrap();
        assert_eq!(got, "api.example.com");
    }

    #[test]
    fn normalize_strips_leading_and_trailing_dots() {
        let got = normalize_domain("..example.com..").unwrap();
        assert_eq!(got, "example.com");
    }

    #[test]
    fn normalize_strips_port() {
        let got = normalize_domain("example.com:443").unwrap();
        assert_eq!(got, "example.com");
    }

    #[test]
    fn normalize_returns_none_for_empty() {
        assert!(normalize_domain("").is_none());
        assert!(normalize_domain("   ").is_none());
    }

    #[test]
    fn normalize_returns_none_for_whitespace_only_after_strip() {
        assert!(normalize_domain("https://").is_none());
        assert!(normalize_domain("http:///").is_none());
    }

    #[test]
    fn normalize_returns_none_for_internal_whitespace() {
        assert!(normalize_domain("exa mple.com").is_none());
    }

    #[test]
    fn normalize_preserves_wildcard() {
        let got = normalize_domain("*").unwrap();
        assert_eq!(got, "*");
    }

    // ── normalize_allowed_domains ───────────────────────────────

    #[test]
    fn normalize_deduplicates_and_sorts() {
        let got = normalize_allowed_domains(vec![
            "example.com".into(),
            "EXAMPLE.COM".into(),
            "https://example.com/".into(),
        ]);
        assert_eq!(got, vec!["example.com"]);
    }

    #[test]
    fn normalize_filters_empty_entries() {
        let got = normalize_allowed_domains(vec![String::new(), "  ".into(), "good.com".into()]);
        assert_eq!(got, vec!["good.com"]);
    }

    #[test]
    fn normalize_sorts_alphabetically() {
        let got = normalize_allowed_domains(vec![
            "zebra.com".into(),
            "alpha.com".into(),
            "middle.com".into(),
        ]);
        assert_eq!(got, vec!["alpha.com", "middle.com", "zebra.com"]);
    }

    // ── extract_host ────────────────────────────────────────────

    #[test]
    fn extract_host_https() {
        let host = extract_host("https://example.com/page?q=1").unwrap();
        assert_eq!(host, "example.com");
    }

    #[test]
    fn extract_host_http() {
        let host = extract_host("http://example.com:8080/api").unwrap();
        assert_eq!(host, "example.com");
    }

    #[test]
    fn extract_host_strips_trailing_dot() {
        let host = extract_host("https://example.com./path").unwrap();
        assert_eq!(host, "example.com");
    }

    #[test]
    fn extract_host_lowercases() {
        let host = extract_host("https://EXAMPLE.COM").unwrap();
        assert_eq!(host, "example.com");
    }

    #[test]
    fn extract_host_with_fragment() {
        let host = extract_host("https://example.com#section").unwrap();
        assert_eq!(host, "example.com");
    }

    #[test]
    fn extract_host_with_query() {
        let host = extract_host("https://example.com?key=val").unwrap();
        assert_eq!(host, "example.com");
    }

    #[test]
    fn extract_host_rejects_non_http() {
        let err = extract_host("ftp://example.com").unwrap_err();
        assert!(err.contains("http://") || err.contains("https://"));
    }

    #[test]
    fn extract_host_rejects_empty_host() {
        let err = extract_host("https:///path").unwrap_err();
        assert!(err.contains("host"));
    }

    #[test]
    fn extract_host_rejects_userinfo() {
        let err = extract_host("https://user:pass@example.com").unwrap_err();
        assert!(err.contains("userinfo"));
    }

    #[test]
    fn extract_host_rejects_ipv6() {
        let err = extract_host("https://[::1]:8080/api").unwrap_err();
        assert!(err.contains("IPv6"));
    }

    // ── is_private_or_local_host ────────────────────────────────

    #[test]
    fn private_localhost() {
        assert!(is_private_or_local_host("localhost"));
    }

    #[test]
    fn private_subdomain_localhost() {
        assert!(is_private_or_local_host("foo.localhost"));
    }

    #[test]
    fn private_local_tld() {
        assert!(is_private_or_local_host("mydevice.local"));
    }

    #[test]
    fn private_loopback_ipv4() {
        assert!(is_private_or_local_host("127.0.0.1"));
        assert!(is_private_or_local_host("127.0.0.2"));
        assert!(is_private_or_local_host("127.255.255.255"));
    }

    #[test]
    fn private_rfc1918_10() {
        assert!(is_private_or_local_host("10.0.0.1"));
        assert!(is_private_or_local_host("10.255.255.255"));
    }

    #[test]
    fn private_rfc1918_172() {
        assert!(is_private_or_local_host("172.16.0.1"));
        assert!(is_private_or_local_host("172.31.255.255"));
    }

    #[test]
    fn private_rfc1918_192() {
        assert!(is_private_or_local_host("192.168.0.1"));
        assert!(is_private_or_local_host("192.168.255.255"));
    }

    #[test]
    fn private_link_local() {
        assert!(is_private_or_local_host("169.254.0.1"));
        assert!(is_private_or_local_host("169.254.255.255"));
    }

    #[test]
    fn private_shared_address_space() {
        assert!(is_private_or_local_host("100.64.0.1"));
        assert!(is_private_or_local_host("100.127.255.255"));
    }

    #[test]
    fn private_unspecified() {
        assert!(is_private_or_local_host("0.0.0.0"));
    }

    #[test]
    fn private_broadcast() {
        assert!(is_private_or_local_host("255.255.255.255"));
    }

    #[test]
    fn private_multicast() {
        assert!(is_private_or_local_host("224.0.0.1"));
        assert!(is_private_or_local_host("239.255.255.255"));
    }

    #[test]
    fn private_reserved_future() {
        assert!(is_private_or_local_host("240.0.0.1"));
        assert!(is_private_or_local_host("250.0.0.1"));
    }

    #[test]
    fn private_documentation_ranges() {
        assert!(is_private_or_local_host("192.0.2.1"));
        assert!(is_private_or_local_host("198.51.100.1"));
        assert!(is_private_or_local_host("203.0.113.1"));
    }

    #[test]
    fn private_ietf_protocol_assignments() {
        assert!(is_private_or_local_host("192.0.0.1"));
    }

    #[test]
    fn private_benchmarking() {
        assert!(is_private_or_local_host("198.18.0.1"));
        assert!(is_private_or_local_host("198.19.255.255"));
    }

    #[test]
    fn public_ipv4_allowed() {
        assert!(!is_private_or_local_host("93.184.216.34"));
        assert!(!is_private_or_local_host("1.1.1.1"));
        assert!(!is_private_or_local_host("8.8.8.8"));
    }

    #[test]
    fn public_domain_allowed() {
        assert!(!is_private_or_local_host("example.com"));
        assert!(!is_private_or_local_host("docs.example.com"));
    }

    #[test]
    fn private_ipv6_loopback() {
        assert!(is_private_or_local_host("::1"));
    }

    #[test]
    fn private_ipv6_unspecified() {
        assert!(is_private_or_local_host("::"));
    }

    #[test]
    fn private_ipv6_multicast() {
        assert!(is_private_or_local_host("ff02::1"));
    }

    #[test]
    fn private_ipv6_ula() {
        assert!(is_private_or_local_host("fc00::1"));
        assert!(is_private_or_local_host("fd00::1"));
    }

    #[test]
    fn private_ipv6_link_local() {
        assert!(is_private_or_local_host("fe80::1"));
    }

    #[test]
    fn private_ipv6_documentation() {
        assert!(is_private_or_local_host("2001:db8::1"));
    }

    #[test]
    fn private_ipv6_mapped_private_v4() {
        assert!(is_private_or_local_host("::ffff:127.0.0.1"));
        assert!(is_private_or_local_host("::ffff:10.0.0.1"));
    }

    #[test]
    fn private_bracketed_ipv6() {
        assert!(is_private_or_local_host("[::1]"));
    }

    #[test]
    fn public_ipv6_allowed() {
        assert!(!is_private_or_local_host("2607:f8b0:4004:800::200e"));
    }

    // ── host_matches_allowlist ──────────────────────────────────

    #[test]
    fn allowlist_exact_match() {
        let allowed = vec!["example.com".into()];
        assert!(host_matches_allowlist("example.com", &allowed));
    }

    #[test]
    fn allowlist_subdomain_match() {
        let allowed = vec!["example.com".into()];
        assert!(host_matches_allowlist("docs.example.com", &allowed));
        assert!(host_matches_allowlist("api.docs.example.com", &allowed));
    }

    #[test]
    fn allowlist_rejects_superdomain() {
        let allowed = vec!["docs.example.com".into()];
        assert!(!host_matches_allowlist("example.com", &allowed));
    }

    #[test]
    fn allowlist_rejects_partial_suffix() {
        let allowed = vec!["example.com".into()];
        assert!(!host_matches_allowlist("notexample.com", &allowed));
    }

    #[test]
    fn allowlist_wildcard_matches_everything() {
        let allowed = vec!["*".into()];
        assert!(host_matches_allowlist("anything.example.com", &allowed));
    }

    #[test]
    fn allowlist_no_match() {
        let allowed = vec!["example.com".into()];
        assert!(!host_matches_allowlist("google.com", &allowed));
    }

    #[test]
    fn allowlist_empty_matches_nothing() {
        let allowed: Vec<String> = vec![];
        assert!(!host_matches_allowlist("example.com", &allowed));
    }

    #[test]
    fn allowlist_multiple_domains() {
        let allowed = vec!["example.com".into(), "github.com".into()];
        assert!(host_matches_allowlist("example.com", &allowed));
        assert!(host_matches_allowlist("github.com", &allowed));
        assert!(host_matches_allowlist("api.github.com", &allowed));
        assert!(!host_matches_allowlist("google.com", &allowed));
    }

    // ── validate_target_url ─────────────────────────────────────

    #[test]
    fn validate_accepts_allowed_url() {
        let allowed = vec!["example.com".into()];
        let got =
            validate_target_url("https://example.com/page", &allowed, &[], "web_fetch").unwrap();
        assert_eq!(got, "https://example.com/page");
    }

    #[test]
    fn validate_trims_whitespace() {
        let allowed = vec!["example.com".into()];
        let got =
            validate_target_url("  https://example.com  ", &allowed, &[], "web_fetch").unwrap();
        assert_eq!(got, "https://example.com");
    }

    #[test]
    fn validate_accepts_subdomain() {
        let allowed = vec!["example.com".into()];
        assert!(
            validate_target_url("https://docs.example.com/guide", &allowed, &[], "web_fetch")
                .is_ok()
        );
    }

    #[test]
    fn validate_accepts_http() {
        let allowed = vec!["example.com".into()];
        assert!(validate_target_url("http://example.com/page", &allowed, &[], "web_fetch").is_ok());
    }

    #[test]
    fn validate_rejects_empty_url() {
        let allowed = vec!["example.com".into()];
        let err = validate_target_url("", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("empty"));
    }

    #[test]
    fn validate_rejects_whitespace_only() {
        let allowed = vec!["example.com".into()];
        let err = validate_target_url("   ", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("empty"));
    }

    #[test]
    fn validate_rejects_internal_whitespace() {
        let allowed = vec!["example.com".into()];
        let err =
            validate_target_url("https://example .com", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("whitespace"));
    }

    #[test]
    fn validate_rejects_ftp_scheme() {
        let allowed = vec!["example.com".into()];
        let err = validate_target_url("ftp://example.com", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("http://") || err.contains("https://"));
    }

    #[test]
    fn validate_rejects_no_scheme() {
        let allowed = vec!["example.com".into()];
        let err = validate_target_url("example.com", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("http://") || err.contains("https://"));
    }

    #[test]
    fn validate_rejects_empty_allowlist() {
        let err = validate_target_url("https://example.com", &[], &[], "web_fetch").unwrap_err();
        assert!(err.contains("allowed_domains"));
        assert!(err.contains("web_fetch"));
    }

    #[test]
    fn validate_rejects_localhost() {
        let allowed = vec!["localhost".into()];
        let err =
            validate_target_url("https://localhost:8080", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("local/private"));
    }

    #[test]
    fn validate_rejects_private_ip() {
        let allowed = vec!["*".into()];
        let err =
            validate_target_url("https://192.168.1.1", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("local/private"));
    }

    #[test]
    fn validate_rejects_loopback() {
        let allowed = vec!["*".into()];
        let err =
            validate_target_url("https://127.0.0.1/admin", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("local/private"));
    }

    #[test]
    fn validate_wildcard_still_blocks_private() {
        let allowed = vec!["*".into()];
        let err = validate_target_url("https://10.0.0.1", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("local/private"));
    }

    #[test]
    fn validate_rejects_blocked_domain() {
        let allowed = vec!["*".into()];
        let blocked = vec!["evil.com".into()];
        let err = validate_target_url("https://evil.com/phish", &allowed, &blocked, "web_fetch")
            .unwrap_err();
        assert!(err.contains("blocked_domains"));
    }

    #[test]
    fn validate_rejects_blocked_subdomain() {
        let allowed = vec!["*".into()];
        let blocked = vec!["evil.com".into()];
        let err = validate_target_url("https://api.evil.com/v1", &allowed, &blocked, "web_fetch")
            .unwrap_err();
        assert!(err.contains("blocked_domains"));
    }

    #[test]
    fn validate_blocklist_wins_over_allowlist() {
        let allowed = vec!["evil.com".into()];
        let blocked = vec!["evil.com".into()];
        let err =
            validate_target_url("https://evil.com", &allowed, &blocked, "web_fetch").unwrap_err();
        assert!(err.contains("blocked_domains"));
    }

    #[test]
    fn validate_blocklist_allows_non_blocked() {
        let allowed = vec!["*".into()];
        let blocked = vec!["evil.com".into()];
        assert!(
            validate_target_url("https://example.com", &allowed, &blocked, "web_fetch").is_ok()
        );
    }

    #[test]
    fn validate_rejects_unallowed_domain() {
        let allowed = vec!["example.com".into()];
        let err =
            validate_target_url("https://google.com", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("allowed_domains"));
    }

    #[test]
    fn validate_tool_name_in_error_messages() {
        let err = validate_target_url("https://example.com", &[], &[], "http_request").unwrap_err();
        assert!(err.contains("http_request"));
    }

    #[test]
    fn validate_rejects_userinfo() {
        let allowed = vec!["example.com".into()];
        let err = validate_target_url("https://user:pass@example.com", &allowed, &[], "web_fetch")
            .unwrap_err();
        assert!(err.contains("userinfo"));
    }

    #[test]
    fn validate_rejects_ipv6() {
        let allowed = vec!["*".into()];
        let err =
            validate_target_url("https://[::1]:8080/api", &allowed, &[], "web_fetch").unwrap_err();
        assert!(err.contains("IPv6"));
    }
}
