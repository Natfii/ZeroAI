// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for [`crate::url_helpers`].

#![allow(clippy::unwrap_used)]

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
fn validate_empty_allowlist_allows_public_hosts() {
    let url = validate_target_url("https://example.com", &[], &[], "web_fetch").unwrap();
    assert_eq!(url, "https://example.com");
}

#[test]
fn validate_empty_allowlist_still_blocks_private() {
    let err = validate_target_url("https://localhost:8080", &[], &[], "web_fetch").unwrap_err();
    assert!(err.contains("local/private"));
}

#[test]
fn validate_empty_allowlist_still_checks_blocked() {
    let blocked = vec!["evil.com".into()];
    let err = validate_target_url("https://evil.com", &[], &blocked, "web_fetch").unwrap_err();
    assert!(err.contains("blocked_domains"));
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
    let blocked = vec!["example.com".into()];
    let err =
        validate_target_url("https://example.com", &[], &blocked, "http_request").unwrap_err();
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
