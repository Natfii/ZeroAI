// Copyright (c) 2026 @Natfii. All rights reserved.

//! Tailnet service discovery FFI types and inner implementations.
//!
//! Provides typed records for UniFFI binding generation and the inner
//! (non-`#[uniffi::export]`) functions called by the public FFI exports
//! in `lib.rs`. Uses the Tailscale local API (`100.100.100.100`) to
//! discover peers, then probes each for known services (Ollama, zeroclaw).
//!
//! Reference:
//! - Tailscale local API: <https://tailscale.com/api#tag/localapi>
//! - Ollama API: <https://github.com/ollama/ollama/blob/main/docs/api.md>

use crate::error::FfiError;
use crate::runtime::get_or_create_runtime;
use secrecy::{ExposeSecret, SecretString};
use std::net::IpAddr;
use std::time::Duration;

/// TCP connect timeout for service probes.
const PROBE_CONNECT_TIMEOUT: Duration = Duration::from_secs(2);

/// Overall HTTP request timeout for service probes.
const PROBE_REQUEST_TIMEOUT: Duration = Duration::from_secs(3);

/// Tailscale local API status endpoint (the daemon-local HTTP API).
const TAILSCALE_LOCAL_API: &str = "http://100.100.100.100/localapi/v0/status";

/// Standard AI server ports to probe, ordered by popularity.
const AI_PORTS: &[(u16, &str)] = &[
    (11434, "ollama"),
    (1234, "lmstudio"),
    (8000, "vllm"),
    (8080, "localai"),
];

/// Default zeroclaw gateway HTTP port (matches ZeroAI's `AppSettings.DEFAULT_PORT`).
const ZEROCLAW_PORT: u16 = 42617;

/// Default OpenClaw gateway HTTP port.
const OPENCLAW_PORT: u16 = 18789;

// ── FFI record types ─────────────────────────────────────────────────

/// Result of querying the Tailscale local API for tailnet membership.
///
/// Contains the tailnet name, the device's own IP, and a list of
/// online peers discovered on the same tailnet.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TailnetAutoDiscoverResult {
    /// Human-readable tailnet name (e.g. `"mynet.ts.net"`).
    pub tailnet_name: String,
    /// This device's primary Tailscale IP address.
    pub self_ip: String,
    /// Online peers discovered on the tailnet.
    pub peers: Vec<TailnetDiscoveredPeer>,
}

/// A single peer node discovered on the tailnet.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TailnetDiscoveredPeer {
    /// Peer hostname (e.g. `"workstation"`).
    pub hostname: String,
    /// Fully-qualified MagicDNS name (e.g. `"workstation.mynet.ts.net."`).
    pub dns_name: String,
    /// Primary Tailscale IP address of the peer.
    pub ip: String,
    /// Operating system reported by the peer (e.g. `"linux"`, `"windows"`).
    pub os: String,
}

/// A peer IP address and the services discovered running on it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TailnetPeer {
    /// Tailscale IP address of the peer.
    pub ip: String,
    /// Services successfully probed on this peer.
    pub services: Vec<TailnetService>,
}

/// A single service discovered on a tailnet peer.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TailnetService {
    /// The kind of service detected.
    pub kind: TailnetServiceKind,
    /// TCP port the service is listening on.
    pub port: u16,
    /// Version string reported by the service, if available.
    pub version: Option<String>,
    /// Whether the service responded with a healthy status.
    pub healthy: bool,
    /// Whether the peer requires a bearer token for API access.
    ///
    /// Defaults to `true` (safe assumption — require auth unless proven otherwise).
    pub auth_required: bool,
}

/// Channel kinds that support peer message relay.
///
/// Used by [`peer_send_channel_response_inner`] to validate and route
/// responses back through the originating channel.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum PeerChannelKind {
    /// Telegram bot channel.
    Telegram,
    /// Discord bot channel.
    Discord,
    /// In-app CLI/Terminal.
    Cli,
}

/// Known service types that can be discovered on tailnet peers.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum TailnetServiceKind {
    /// Ollama LLM inference server (port 11434).
    Ollama,
    /// LM Studio inference server (port 1234).
    LmStudio,
    /// vLLM inference server (port 8000).
    Vllm,
    /// LocalAI inference server (port 8080).
    LocalAi,
    /// ZeroClaw gateway HTTP server (port 42617).
    Zeroclaw,
    /// OpenClaw gateway (port 18789).
    OpenClaw,
}

// ── Inner implementations ────────────────────────────────────────────

/// Queries the Tailscale local API for tailnet membership and online peers.
///
/// Hits `GET http://100.100.100.100/localapi/v0/status`, parses the JSON
/// response for the current tailnet name, this device's IP, and all
/// online peer nodes.
pub(crate) fn tailnet_auto_discover_inner() -> Result<TailnetAutoDiscoverResult, FfiError> {
    let handle = get_or_create_runtime()?;
    handle.block_on(async {
        let client = reqwest::Client::builder()
            .connect_timeout(PROBE_CONNECT_TIMEOUT)
            .timeout(PROBE_REQUEST_TIMEOUT)
            .build()
            .map_err(|e| FfiError::NetworkError {
                detail: format!("failed to build HTTP client: {e}"),
            })?;

        let response =
            client
                .get(TAILSCALE_LOCAL_API)
                .send()
                .await
                .map_err(|e| FfiError::NetworkError {
                    detail: format!("failed to reach Tailscale local API: {e}"),
                })?;

        let status = response.status();
        if !status.is_success() {
            let body = response
                .text()
                .await
                .unwrap_or_else(|_| "<unreadable>".into());
            return Err(FfiError::NetworkError {
                detail: format!("Tailscale local API returned {status}: {body}"),
            });
        }

        let json: serde_json::Value =
            response.json().await.map_err(|e| FfiError::NetworkError {
                detail: format!("failed to parse Tailscale status JSON: {e}"),
            })?;

        let tailnet_name = json["CurrentTailnet"]["Name"]
            .as_str()
            .unwrap_or_default()
            .to_string();

        let self_ip = json["Self"]["TailscaleIPs"]
            .as_array()
            .and_then(|ips| ips.first())
            .and_then(|ip| ip.as_str())
            .unwrap_or_default()
            .to_string();

        let mut peers = Vec::new();

        if let Some(peer_map) = json["Peer"].as_object() {
            for (_key, peer) in peer_map {
                let online = peer["Online"].as_bool().unwrap_or(false);
                if !online {
                    continue;
                }

                let hostname = peer["HostName"].as_str().unwrap_or_default().to_string();
                let dns_name = peer["DNSName"].as_str().unwrap_or_default().to_string();
                let os = peer["OS"].as_str().unwrap_or_default().to_string();

                let ip = peer["TailscaleIPs"]
                    .as_array()
                    .and_then(|ips| ips.first())
                    .and_then(|ip| ip.as_str())
                    .unwrap_or_default()
                    .to_string();

                if ip.is_empty() {
                    continue;
                }

                peers.push(TailnetDiscoveredPeer {
                    hostname,
                    dns_name,
                    ip,
                    os,
                });
            }
        }

        Ok(TailnetAutoDiscoverResult {
            tailnet_name,
            self_ip,
            peers,
        })
    })
}

/// Probes a list of peer addresses for AI servers and zeroclaw gateways.
///
/// Each entry in `peer_addresses` can be a bare IP/hostname (probes all
/// standard AI ports plus zeroclaw) or `host:port` (probes only that
/// port for any known API).
///
/// Standard ports probed: Ollama (11434), LM Studio (1234), vLLM (8000),
/// LocalAI (8080), zeroclaw (42617).
///
/// Individual probe failures are silently ignored. Only runtime-level
/// failures surface as [`FfiError`].
pub(crate) fn tailnet_probe_services_inner(
    peer_addresses: Vec<String>,
) -> Result<Vec<TailnetPeer>, FfiError> {
    let handle = get_or_create_runtime()?;
    handle.block_on(async {
        let client = reqwest::Client::builder()
            .connect_timeout(PROBE_CONNECT_TIMEOUT)
            .timeout(PROBE_REQUEST_TIMEOUT)
            .build()
            .map_err(|e| FfiError::NetworkError {
                detail: format!("failed to build HTTP client: {e}"),
            })?;

        let mut results = Vec::with_capacity(peer_addresses.len());

        for addr in &peer_addresses {
            let (host, explicit_port) = parse_host_port(addr);
            let mut services = Vec::new();

            if let Some(port) = explicit_port {
                // Explicit port: try all API types on that one port.
                if let Some(svc) = probe_ai_server(&client, host, port).await {
                    services.push(svc);
                }
                if let Some(svc) = probe_zeroclaw(&client, host, port).await {
                    services.push(svc);
                }
                if let Some(svc) = probe_openclaw(&client, host, port).await {
                    services.push(svc);
                }
            } else {
                // No explicit port: probe all standard ports concurrently.
                let mut handles = Vec::new();
                for &(port, _hint) in AI_PORTS {
                    let c = client.clone();
                    let h = host.to_string();
                    handles.push(tokio::spawn(
                        async move { probe_ai_server(&c, &h, port).await },
                    ));
                }
                let zc = client.clone();
                let zh = host.to_string();
                handles.push(tokio::spawn(async move {
                    probe_zeroclaw(&zc, &zh, ZEROCLAW_PORT).await
                }));
                let oc = client.clone();
                let oh = host.to_string();
                handles.push(tokio::spawn(async move {
                    probe_openclaw(&oc, &oh, OPENCLAW_PORT).await
                }));

                for handle in handles {
                    if let Ok(Some(svc)) = handle.await {
                        services.push(svc);
                    }
                }
            }

            results.push(TailnetPeer {
                ip: host.to_string(),
                services,
            });
        }

        Ok(results)
    })
}

/// Splits an address into `(host, optional_port)`.
///
/// Accepts `"host"` or `"host:port"`. Returns `None` for the port when
/// no explicit port is given (caller should probe all standard ports).
fn parse_host_port(addr: &str) -> (&str, Option<u16>) {
    if let Some((host, port_str)) = addr.rsplit_once(':')
        && let Ok(port) = port_str.parse::<u16>()
    {
        return (host, Some(port));
    }
    (addr, None)
}

// ── Private async helpers ────────────────────────────────────────────

/// Probes for an AI inference server on the given host and port.
///
/// Tries Ollama's `/api/tags` first, then falls back to the
/// OpenAI-compatible `/v1/models` endpoint. Returns `None` if neither
/// responds successfully.
async fn probe_ai_server(
    client: &reqwest::Client,
    host: &str,
    port: u16,
) -> Option<TailnetService> {
    let ollama_url = format!("http://{host}:{port}/api/tags");
    if let Ok(resp) = client.get(&ollama_url).send().await
        && resp.status().is_success()
        && let Ok(json) = resp.json::<serde_json::Value>().await
        && json.get("models").and_then(|v| v.as_array()).is_some()
    {
        let model_count = json["models"].as_array().map_or(0, Vec::len);
        let kind = port_to_kind(port)?;
        return Some(TailnetService {
            kind,
            port,
            version: Some(format!("{model_count} model(s)")),
            healthy: true,
            auth_required: false,
        });
    }

    let openai_url = format!("http://{host}:{port}/v1/models");
    if let Ok(resp) = client.get(&openai_url).send().await
        && resp.status().is_success()
        && let Ok(json) = resp.json::<serde_json::Value>().await
        && let Some(data) = json.get("data").and_then(|v| v.as_array())
    {
        let kind = port_to_kind(port)?;
        return Some(TailnetService {
            kind,
            port,
            version: Some(format!("{} model(s)", data.len())),
            healthy: true,
            auth_required: false,
        });
    }

    None
}

/// Probes a zeroclaw gateway's `/health` endpoint.
///
/// Returns `None` if the probe fails for any reason. Extracts
/// `require_pairing` to determine `auth_required` (defaults to
/// `true` when the field is absent — safe assumption).
async fn probe_zeroclaw(client: &reqwest::Client, host: &str, port: u16) -> Option<TailnetService> {
    let url = format!("http://{host}:{port}/health");
    let resp = client.get(&url).send().await.ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let json: serde_json::Value = resp.json().await.ok()?;
    let version = json["version"].as_str().map(cap_version_string);
    let auth_required = json["require_pairing"].as_bool().unwrap_or(true);
    Some(TailnetService {
        kind: TailnetServiceKind::Zeroclaw,
        port,
        version,
        healthy: true,
        auth_required,
    })
}

/// Caps a string at 64 characters for safe storage/display.
fn cap_version_string(s: &str) -> String {
    match s.char_indices().nth(64) {
        Some((idx, _)) => s[..idx].to_string(),
        None => s.to_string(),
    }
}

/// Probes for an OpenClaw gateway on the given host and port.
///
/// Detection uses a tiered approach:
/// 1. Check for `x-openclaw-version` response header (definitive)
/// 2. Parse response as JSON and check `name`/`title` fields (fallback)
///
/// Returns `None` if neither signal is present.
async fn probe_openclaw(client: &reqwest::Client, host: &str, port: u16) -> Option<TailnetService> {
    let url = format!("http://{host}:{port}/");
    let resp = client.get(&url).send().await.ok()?;
    if !resp.status().is_success() {
        return None;
    }

    // Tier 1: definitive header
    let header_version = resp
        .headers()
        .get("x-openclaw-version")
        .and_then(|v| v.to_str().ok())
        .map(cap_version_string);

    if let Some(ref ver) = header_version {
        return Some(TailnetService {
            kind: TailnetServiceKind::OpenClaw,
            port,
            version: Some(ver.clone()),
            healthy: true,
            auth_required: true,
        });
    }

    // Tier 2: structured JSON body (cap at 4KB)
    let body = resp.text().await.ok()?;
    let truncated = &body[..body.len().min(4096)];
    if let Ok(json) = serde_json::from_str::<serde_json::Value>(truncated) {
        let name_match = json
            .get("name")
            .and_then(|v| v.as_str())
            .is_some_and(|s| s.to_lowercase().contains("openclaw"));
        let title_match = json
            .get("title")
            .and_then(|v| v.as_str())
            .is_some_and(|s| s.to_lowercase().contains("openclaw"));

        if name_match || title_match {
            let version = json
                .get("version")
                .and_then(|v| v.as_str())
                .map(cap_version_string);
            return Some(TailnetService {
                kind: TailnetServiceKind::OpenClaw,
                port,
                version,
                healthy: true,
                auth_required: true,
            });
        }
    }

    None
}

/// Maps a port number to the expected [`TailnetServiceKind`].
///
/// Returns `None` for unrecognized ports. Never guesses — unknown
/// ports must be classified by their probe response, not their number.
fn port_to_kind(port: u16) -> Option<TailnetServiceKind> {
    match port {
        11434 => Some(TailnetServiceKind::Ollama),
        1234 => Some(TailnetServiceKind::LmStudio),
        8000 => Some(TailnetServiceKind::Vllm),
        8080 => Some(TailnetServiceKind::LocalAi),
        42617 => Some(TailnetServiceKind::Zeroclaw),
        18789 => Some(TailnetServiceKind::OpenClaw),
        _ => None,
    }
}

// ── Peer messaging ───────────────────────────────────────────────────

/// Maximum response body size (1 MB) to prevent OOM from a malicious peer.
const MAX_RESPONSE_BYTES: u64 = 1_048_576;

/// Response timeout for peer messages (60s — waiting for LLM generation).
const PEER_RESPONSE_TIMEOUT: Duration = Duration::from_secs(60);

/// Validates that an IP string is a valid IP address.
fn validate_peer_ip(ip: &str) -> Result<IpAddr, FfiError> {
    ip.parse::<IpAddr>().map_err(|_| FfiError::InvalidArgument {
        detail: format!("Invalid IP address: {ip}"),
    })
}

/// Sends a message to a peer agent and returns the response text.
///
/// # Blocking
///
/// This function performs a synchronous HTTP request and may block for
/// up to 63 seconds (3s connect + 60s response timeout). Callers
/// **must** invoke from a background dispatcher (`Dispatchers.IO`).
/// Never call from the main thread.
///
/// # Errors
///
/// - [`FfiError::InvalidArgument`] — malformed IP address or unsupported peer kind
/// - [`FfiError::NetworkError`] — connection failure, timeout, or malformed response
/// - [`FfiError::InternalPanic`] — unexpected internal panic (caught)
pub(crate) fn peer_send_message_inner(
    ip: String,
    port: u16,
    kind: TailnetServiceKind,
    token: Option<String>,
    message: String,
) -> Result<String, FfiError> {
    let addr = validate_peer_ip(&ip)?;

    let secret_token: Option<SecretString> = token.map(SecretString::from);

    let handle = get_or_create_runtime()?;
    handle.block_on(async {
        let client = reqwest::Client::builder()
            .connect_timeout(PROBE_CONNECT_TIMEOUT)
            .timeout(PEER_RESPONSE_TIMEOUT)
            .build()
            .map_err(|e| FfiError::NetworkError {
                detail: format!("failed to build HTTP client: {e}"),
            })?;

        let auth_header = secret_token
            .as_ref()
            .map(|t: &SecretString| format!("Bearer {}", t.expose_secret()));

        match kind {
            TailnetServiceKind::Zeroclaw => {
                send_to_zeroclaw(&client, &addr, port, auth_header.as_deref(), &message).await
            }
            TailnetServiceKind::OpenClaw => {
                send_to_openclaw(&client, &addr, port, auth_header.as_deref(), &message).await
            }
            _ => Err(FfiError::InvalidArgument {
                detail: format!("Unsupported peer kind for messaging: {kind:?}"),
            }),
        }
    })
}

/// Sends a message to a ZeroClaw peer via POST /webhook.
async fn send_to_zeroclaw(
    client: &reqwest::Client,
    addr: &IpAddr,
    port: u16,
    auth: Option<&str>,
    message: &str,
) -> Result<String, FfiError> {
    let url = format!("http://{addr}:{port}/webhook");
    let body = serde_json::json!({"message": message});

    let mut req = client.post(&url).json(&body);
    if let Some(token) = auth {
        req = req.header("Authorization", token);
    }

    let resp = req.send().await.map_err(|e| FfiError::NetworkError {
        detail: format!("Failed to reach ZeroClaw peer at {addr}:{port}: {e}"),
    })?;

    if !resp.status().is_success() {
        let status = resp.status();
        return Err(FfiError::NetworkError {
            detail: format!("ZeroClaw peer returned HTTP {status}"),
        });
    }

    let body = read_capped_body(resp).await?;
    let json: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| FfiError::NetworkError {
            detail: format!("Invalid JSON from ZeroClaw peer: {e}"),
        })?;

    json.get("response")
        .and_then(|v| v.as_str())
        .map(String::from)
        .ok_or_else(|| FfiError::NetworkError {
            detail: "ZeroClaw response missing 'response' field".into(),
        })
}

/// Sends a message to an OpenClaw peer via POST /v1/chat/completions.
async fn send_to_openclaw(
    client: &reqwest::Client,
    addr: &IpAddr,
    port: u16,
    auth: Option<&str>,
    message: &str,
) -> Result<String, FfiError> {
    let url = format!("http://{addr}:{port}/v1/chat/completions");
    let body = serde_json::json!({
        "model": "openclaw",
        "messages": [{"role": "user", "content": message}]
    });

    let mut req = client.post(&url).json(&body);
    if let Some(token) = auth {
        req = req.header("Authorization", token);
    }

    let resp = req.send().await.map_err(|e| FfiError::NetworkError {
        detail: format!("Failed to reach OpenClaw peer at {addr}:{port}: {e}"),
    })?;

    if !resp.status().is_success() {
        let status = resp.status();
        return Err(FfiError::NetworkError {
            detail: format!("OpenClaw peer returned HTTP {status}"),
        });
    }

    let body = read_capped_body(resp).await?;
    let json: serde_json::Value =
        serde_json::from_str(&body).map_err(|e| FfiError::NetworkError {
            detail: format!("Invalid JSON from OpenClaw peer: {e}"),
        })?;

    json.get("choices")
        .and_then(|c| c.as_array())
        .and_then(|arr| arr.first())
        .and_then(|choice| choice.get("message"))
        .and_then(|msg| msg.get("content"))
        .and_then(|c| c.as_str())
        .map(String::from)
        .ok_or_else(|| FfiError::NetworkError {
            detail: "OpenClaw response missing choices[0].message.content".into(),
        })
}

/// Reads a response body with a 1 MB cap to prevent OOM.
async fn read_capped_body(resp: reqwest::Response) -> Result<String, FfiError> {
    use futures_util::StreamExt;

    let mut stream = resp.bytes_stream();
    let mut total: u64 = 0;
    let mut body = Vec::new();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| FfiError::NetworkError {
            detail: format!("Error reading response body: {e}"),
        })?;
        total += chunk.len() as u64;
        if total > MAX_RESPONSE_BYTES {
            return Err(FfiError::NetworkError {
                detail: format!("Response exceeded {MAX_RESPONSE_BYTES} byte limit"),
            });
        }
        body.extend_from_slice(&chunk);
    }

    String::from_utf8(body).map_err(|e| FfiError::NetworkError {
        detail: format!("Response body is not valid UTF-8: {e}"),
    })
}

/// Sends a formatted response back through the daemon's gateway.
///
/// Relays the message through the gateway webhook endpoint, which
/// dispatches to all active channels. The `channel` and `recipient`
/// parameters are encoded in the message for routing context.
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn peer_send_channel_response_inner(
    channel: PeerChannelKind,
    recipient: String,
    message: String,
) -> Result<(), FfiError> {
    let channel_name = match channel {
        PeerChannelKind::Telegram => "telegram",
        PeerChannelKind::Discord => "discord",
        PeerChannelKind::Cli => "cli",
    };

    let body = serde_json::json!({
        "message": message,
        "channel": channel_name,
        "recipient": recipient,
    });

    crate::gateway_client::gateway_post("/webhook", &body)?;
    Ok(())
}
