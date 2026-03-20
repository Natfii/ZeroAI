// Copyright (c) 2026 @Natfii. All rights reserved.

use zeroclaw::config::Config;

#[test]
fn empty_toml_has_empty_peers() {
    let config: Config = toml::from_str("").unwrap();
    assert!(config.tailscale_peers.entries.is_empty());
}

#[test]
fn parses_single_peer_entry() {
    let toml_str = r#"
[[tailscale_peers.entries]]
ip = "100.10.0.5"
hostname = "homeserver"
kind = "zeroclaw"
port = 42617
alias = "homeserver"
auth_required = false
enabled = true
"#;
    let config: Config = toml::from_str(toml_str).unwrap();
    assert_eq!(config.tailscale_peers.entries.len(), 1);
    let entry = &config.tailscale_peers.entries[0];
    assert_eq!(entry.ip, "100.10.0.5");
    assert_eq!(entry.kind, "zeroclaw");
    assert_eq!(entry.port, 42617);
    assert!(!entry.auth_required);
    assert!(entry.enabled);
}

#[test]
fn missing_booleans_get_defaults() {
    let toml_str = r#"
[[tailscale_peers.entries]]
ip = "100.10.0.12"
hostname = "workpc"
kind = "openclaw"
port = 18789
alias = "workpc"
"#;
    let config: Config = toml::from_str(toml_str).unwrap();
    let entry = &config.tailscale_peers.entries[0];
    assert!(!entry.auth_required);
    assert!(entry.enabled);
}

#[test]
fn parses_multiple_peer_entries() {
    let toml_str = r#"
[[tailscale_peers.entries]]
ip = "100.10.0.5"
hostname = "homeserver"
kind = "zeroclaw"
port = 42617
alias = "homeserver"

[[tailscale_peers.entries]]
ip = "100.10.0.12"
hostname = "workpc"
kind = "openclaw"
port = 18789
alias = "workpc"
"#;
    let config: Config = toml::from_str(toml_str).unwrap();
    assert_eq!(config.tailscale_peers.entries.len(), 2);
}
