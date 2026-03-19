// Copyright (c) 2026 @Natfii. All rights reserved.

//! Google Messages bridge FFI types and inner implementations.
//!
//! Provides typed records and enums for UniFFI binding generation and the
//! inner (non-`#[uniffi::export]`) functions called by the public FFI
//! exports in `lib.rs`. Each inner function is wrapped in `catch_unwind`
//! at the call site to prevent panics from crossing the JNI boundary.
//!
//! The [`PairingPageServer`] serves a local HTTP page that displays the
//! QR code during the Google Messages pairing flow. The user opens the
//! local URL on a computer, scans the QR with Google Messages on the
//! phone, and ZeroAI retains the session credentials.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;

use crate::error::FfiError;
use crate::messages_bridge_page::PAIRING_HTML;
use crate::runtime::get_or_create_runtime;

// ── Active pairing server singleton ─────────────────────────────────

/// State held while the pairing page server is running.
struct ActivePairingServer {
    /// The local URL served to the user (e.g. `http://192.168.1.42:54321`).
    page_url: String,
    /// Shared flag flipped to `true` when the bridge reaches [`Connected`] status.
    paired_flag: Arc<AtomicBool>,
    /// Server instance — dropped to trigger shutdown.
    _server: PairingPageServer,
}

/// Global singleton for the active pairing page server.
///
/// Set by [`start_pairing_inner`], cleared by [`shutdown_pairing_server`].
/// `std::sync::Mutex` is used because this is not performance-critical.
static PAIRING_SERVER: Mutex<Option<ActivePairingServer>> = Mutex::new(None);

/// Shuts down the active pairing server, if any.
///
/// Dropping the [`PairingPageServer`] sends the shutdown signal to the
/// accept loop, which causes the spawned task to exit.
fn shutdown_pairing_server() {
    let mut guard = PAIRING_SERVER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(state) = guard.take() {
        tracing::info!(
            target: "messages_bridge::http",
            "dropping pairing server (page_url={})",
            state.page_url,
        );
        drop(state);
    }
}

// ── FFI record types ─────────────────────────────────────────────────

/// A conversation synced from Google Messages, exposed to Kotlin.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiBridgedConversation {
    /// Google internal conversation ID.
    pub id: String,
    /// Contact or group name.
    pub display_name: String,
    /// Whether this is a group conversation.
    pub is_group: bool,
    /// Last message text preview for display in allowlist.
    pub last_message_preview: String,
    /// Epoch millis of last message.
    pub last_message_timestamp: i64,
    /// Whether the AI agent is allowed to read this conversation.
    pub agent_allowed: bool,
    /// Optional epoch millis cutoff. Null means all history.
    pub window_start: Option<i64>,
}

// ── FFI enum types ───────────────────────────────────────────────────

/// Bridge connection status exposed to Kotlin via UniFFI.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum FfiBridgeStatus {
    /// Not paired with any device.
    Unpaired,
    /// QR code displayed, waiting for user to scan.
    AwaitingPairing {
        /// URL for the local QR pairing page.
        qr_page_url: String,
    },
    /// Paired and actively receiving messages.
    Connected,
    /// Connection lost, attempting to reconnect.
    Reconnecting {
        /// Current reconnection attempt number.
        attempt: u32,
    },
    /// Paired but phone is not responding to pings.
    PhoneNotResponding,
}

// ── Conversion helpers ───────────────────────────────────────────────

/// Converts the core [`BridgeStatus`] to the FFI-safe [`FfiBridgeStatus`].
///
/// For [`AwaitingPairing`], substitutes the raw QR URL with the local
/// page server URL so the Kotlin layer shows the LAN address.
fn status_to_ffi(status: &zeroclaw::messages_bridge::types::BridgeStatus) -> FfiBridgeStatus {
    use zeroclaw::messages_bridge::types::BridgeStatus;

    match status {
        BridgeStatus::Unpaired => FfiBridgeStatus::Unpaired,
        BridgeStatus::AwaitingPairing { .. } => {
            let guard = PAIRING_SERVER
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            let page_url = guard
                .as_ref()
                .map(|s| s.page_url.clone())
                .unwrap_or_default();
            FfiBridgeStatus::AwaitingPairing {
                qr_page_url: page_url,
            }
        }
        BridgeStatus::Connected => FfiBridgeStatus::Connected,
        BridgeStatus::Reconnecting { attempt } => {
            FfiBridgeStatus::Reconnecting { attempt: *attempt }
        }
        BridgeStatus::PhoneNotResponding => FfiBridgeStatus::PhoneNotResponding,
    }
}

/// Converts a core [`BridgedConversation`] to the FFI-safe [`FfiBridgedConversation`].
fn conv_to_ffi(
    conv: &zeroclaw::messages_bridge::types::BridgedConversation,
) -> FfiBridgedConversation {
    FfiBridgedConversation {
        id: conv.id.clone(),
        display_name: conv.display_name.clone(),
        is_group: conv.is_group,
        last_message_preview: conv.last_message_preview.clone(),
        last_message_timestamp: conv.last_message_timestamp,
        agent_allowed: conv.agent_allowed,
        window_start: conv.window_start,
    }
}

// ── Inner implementations ────────────────────────────────────────────

/// Returns the current bridge connection status.
///
/// Also synchronises the pairing page server: when the bridge reaches
/// [`Connected`], the server's `paired_flag` is flipped so the browser
/// polling `/status` sees `{"paired":true}`.
///
/// Returns [`FfiBridgeStatus::Unpaired`] if no session is active.
pub(crate) fn get_status_inner() -> FfiBridgeStatus {
    let status = zeroclaw::messages_bridge::session::get_status();

    if matches!(
        status,
        zeroclaw::messages_bridge::types::BridgeStatus::Connected
    ) {
        let guard = PAIRING_SERVER
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(state) = guard.as_ref() {
            state.paired_flag.store(true, Ordering::Release);
        }
    }

    status_to_ffi(&status)
}

/// Lists all bridged conversations from the message store.
///
/// Conversations are ordered by last message timestamp descending.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the bridge session is not active,
/// or [`FfiError::SpawnError`] if the store query fails.
pub(crate) fn list_conversations_inner() -> Result<Vec<FfiBridgedConversation>, FfiError> {
    let store =
        zeroclaw::messages_bridge::session::get_store().ok_or_else(|| FfiError::StateError {
            detail: "Messages bridge not active".into(),
        })?;
    let convs = store
        .list_conversations()
        .map_err(|e| FfiError::SpawnError {
            detail: e.to_string(),
        })?;
    Ok(convs.iter().map(conv_to_ffi).collect())
}

/// Sets whether the AI agent is allowed to read a specific conversation.
///
/// When `allowed` is `true`, the optional `window_start_ms` sets the
/// earliest timestamp the agent may see. Pass `None` to allow all history.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the bridge session is not active,
/// or [`FfiError::SpawnError`] if the store update fails.
pub(crate) fn set_allowed_inner(
    conversation_id: String,
    allowed: bool,
    window_start_ms: Option<i64>,
) -> Result<(), FfiError> {
    let store =
        zeroclaw::messages_bridge::session::get_store().ok_or_else(|| FfiError::StateError {
            detail: "Messages bridge not active".into(),
        })?;
    store
        .set_allowed(&conversation_id, allowed, window_start_ms)
        .map_err(|e| FfiError::SpawnError {
            detail: e.to_string(),
        })
}

/// Disconnects the bridge and shuts down the pairing page server.
///
/// The store data is preserved so the user can re-pair without losing
/// conversation history.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn disconnect_inner() -> Result<(), FfiError> {
    shutdown_pairing_server();
    zeroclaw::messages_bridge::session::disconnect();
    Ok(())
}

/// Disconnects the bridge, shuts down the pairing server, and wipes all stored data.
///
/// Calls [`disconnect`] to stop the listener, then wipes the SQLite
/// store (all conversations, messages, and FTS data).
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn disconnect_and_clear_inner() -> Result<(), FfiError> {
    shutdown_pairing_server();
    zeroclaw::messages_bridge::session::disconnect_and_clear();
    Ok(())
}

/// Initiates QR-code pairing with Google Messages.
///
/// Creates or resumes a bridge session at `data_dir`, generates a pairing
/// QR code via the Bugle API, starts a local HTTP server that renders the
/// QR code as an SVG, and returns the LAN URL (e.g. `http://192.168.1.42:54321`)
/// for the user to open on a computer.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] if the runtime cannot be created,
/// the pairing RPC call fails, or the HTTP server cannot bind.
pub(crate) fn start_pairing_inner(data_dir: String) -> Result<String, FfiError> {
    let handle = get_or_create_runtime()?;
    let path = std::path::PathBuf::from(&data_dir);

    handle.block_on(async {
        // 1. Run the Bugle pairing flow to get the raw QR URL.
        let qr_url = zeroclaw::messages_bridge::session::begin_pairing(&path)
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("pairing failed: {e}"),
            })?;

        // 2. Shut down any previous pairing server.
        shutdown_pairing_server();

        // 3. Start the local HTTP server with the QR code.
        let paired_flag = Arc::new(AtomicBool::new(false));
        let server = PairingPageServer::start(qr_url, Arc::clone(&paired_flag))
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("pairing server failed: {e}"),
            })?;

        let page_url = server.page_url();

        tracing::info!(
            target: "messages_bridge::http",
            %page_url,
            "pairing page available on LAN"
        );

        // 4. Store the server so it stays alive and can be cleaned up later.
        {
            let mut guard = PAIRING_SERVER
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            *guard = Some(ActivePairingServer {
                page_url: page_url.clone(),
                paired_flag,
                _server: server,
            });
        }

        Ok(page_url)
    })
}

// ── Pairing page HTTP server ──────────────────────────────────────────

/// Maximum bytes to peek when detecting the HTTP request path.
const PEEK_BUF_SIZE: usize = 4096;

/// Duration to wait for the user to complete QR pairing before
/// automatically shutting down the HTTP server.
const PAIRING_TIMEOUT: Duration = Duration::from_secs(600);

/// Timeout for awaiting the accept loop to finish during shutdown.
#[cfg(test)]
#[allow(dead_code)]
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(3);

/// Local HTTP server that serves the QR code pairing page.
///
/// Binds to a random available port on all interfaces. Plain HTTP GET
/// requests to `/` receive the embedded pairing HTML page with the QR code
/// SVG injected. GET requests to `/status` return `{"paired":true/false}`.
///
/// The server automatically shuts down after [`PAIRING_TIMEOUT`] (10 minutes)
/// whether or not the user completes pairing, to avoid leaving a dangling
/// listener open. Dropping the server also triggers shutdown via the
/// [`oneshot::Sender`].
struct PairingPageServer {
    /// Port the server is listening on.
    port: u16,
    /// Signals the accept loop to shut down early.
    shutdown_tx: Option<oneshot::Sender<()>>,
    /// Handle for the spawned accept loop task.
    handle: Option<tokio::task::JoinHandle<()>>,
}

impl PairingPageServer {
    /// Starts the pairing page server on a random available port.
    ///
    /// Generates a QR code SVG from `pairing_url`, then binds a TCP listener
    /// to `0.0.0.0:0` and spawns a background task to serve requests. The
    /// `paired_flag` is polled by `/status` requests; callers set it to `true`
    /// once Google Messages completes the handshake.
    ///
    /// # Errors
    ///
    /// Returns an error string if the TCP listener cannot be bound or the
    /// local address cannot be retrieved.
    async fn start(pairing_url: String, paired_flag: Arc<AtomicBool>) -> Result<Self, String> {
        let qr_svg = generate_qr_svg(&pairing_url);

        let listener = TcpListener::bind("0.0.0.0:0")
            .await
            .map_err(|e| format!("failed to bind pairing listener: {e}"))?;

        let port = listener
            .local_addr()
            .map_err(|e| format!("failed to get local address: {e}"))?
            .port();

        let (shutdown_tx, shutdown_rx) = oneshot::channel();

        let handle = tokio::spawn(pairing_accept_loop(
            listener,
            qr_svg,
            paired_flag,
            shutdown_rx,
        ));

        tracing::info!(target: "messages_bridge::http", port, "pairing page server started");

        Ok(Self {
            port,
            shutdown_tx: Some(shutdown_tx),
            handle: Some(handle),
        })
    }

    /// Returns the local IP address and port as a URL for the pairing page.
    ///
    /// Uses a UDP trick to discover the device's outbound IP address. Falls
    /// back to `127.0.0.1` if detection fails.
    fn page_url(&self) -> String {
        format!("http://{}:{}", local_ip(), self.port)
    }

    /// Returns the port the server is bound to.
    #[cfg(test)]
    fn port(&self) -> u16 {
        self.port
    }
}

impl Drop for PairingPageServer {
    fn drop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }
        if let Some(handle) = self.handle.take() {
            handle.abort();
        }
    }
}

/// Accept loop that runs in a spawned tokio task.
///
/// Listens for incoming TCP connections and spawns a handler for each one.
/// Exits when the shutdown signal is received or after [`PAIRING_TIMEOUT`]
/// elapses.
async fn pairing_accept_loop(
    listener: TcpListener,
    qr_svg: String,
    paired_flag: Arc<AtomicBool>,
    mut shutdown: oneshot::Receiver<()>,
) {
    let qr_svg = Arc::new(qr_svg);
    let deadline = tokio::time::sleep(PAIRING_TIMEOUT);
    tokio::pin!(deadline);

    loop {
        tokio::select! {
            result = listener.accept() => {
                match result {
                    Ok((stream, addr)) => {
                        tracing::debug!(
                            target: "messages_bridge::http",
                            %addr,
                            "pairing page client connected"
                        );
                        let svg = Arc::clone(&qr_svg);
                        let flag = Arc::clone(&paired_flag);
                        tokio::spawn(handle_pairing_connection(stream, svg, flag));
                    }
                    Err(e) => {
                        tracing::warn!(
                            target: "messages_bridge::http",
                            "failed to accept connection: {e}"
                        );
                    }
                }
            }
            () = &mut deadline => {
                tracing::info!(
                    target: "messages_bridge::http",
                    "pairing timeout elapsed — shutting down HTTP server"
                );
                break;
            }
            _ = &mut shutdown => {
                tracing::info!(
                    target: "messages_bridge::http",
                    "pairing server received shutdown signal"
                );
                break;
            }
        }
    }
}

/// Handles a single incoming TCP connection from the pairing page browser.
///
/// Peeks at the request line to route between:
/// - `GET /` — serves the HTML pairing page with QR SVG injected
/// - `GET /status` — returns `{"paired":true}` or `{"paired":false}`
/// - Everything else — serves the HTML page as a fallback
async fn handle_pairing_connection(
    mut stream: TcpStream,
    qr_svg: Arc<String>,
    paired_flag: Arc<AtomicBool>,
) {
    let mut peek_buf = [0u8; PEEK_BUF_SIZE];

    let n = match stream.peek(&mut peek_buf).await {
        Ok(n) => n,
        Err(e) => {
            tracing::debug!(target: "messages_bridge::http", "peek failed: {e}");
            return;
        }
    };

    let request_line = String::from_utf8_lossy(&peek_buf[..n]);
    let is_status = request_line.contains("GET /status");

    // Consume the request bytes that were previously peeked.
    let mut discard = [0u8; PEEK_BUF_SIZE];
    match stream.read(&mut discard).await {
        Ok(0) | Err(_) => return,
        Ok(_) => {}
    }

    if is_status {
        serve_status(&mut stream, &paired_flag).await;
    } else {
        serve_pairing_page(&mut stream, &qr_svg).await;
    }
}

/// Serves the embedded HTML pairing page with the QR SVG injected.
///
/// Replaces the `QR_SVG_PLACEHOLDER` token in [`PAIRING_HTML`] with the
/// actual SVG string, then writes an HTTP 200 response.
async fn serve_pairing_page(stream: &mut TcpStream, qr_svg: &str) {
    let html = PAIRING_HTML.replace("QR_SVG_PLACEHOLDER", qr_svg);
    let response = format!(
        "HTTP/1.1 200 OK\r\n\
         Content-Type: text/html; charset=utf-8\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\
         \r\n\
         {}",
        html.len(),
        html,
    );

    if let Err(e) = stream.write_all(response.as_bytes()).await {
        tracing::debug!(
            target: "messages_bridge::http",
            "failed to write pairing page response: {e}"
        );
    }
}

/// Serves the `/status` JSON endpoint.
///
/// Returns `{"paired":true}` if the `paired_flag` is set, otherwise
/// `{"paired":false}`. Also includes CORS headers so the browser page
/// can poll from the same origin.
async fn serve_status(stream: &mut TcpStream, paired_flag: &AtomicBool) {
    let paired = paired_flag.load(Ordering::Acquire);
    let body = if paired {
        r#"{"paired":true}"#
    } else {
        r#"{"paired":false}"#
    };

    let response = format!(
        "HTTP/1.1 200 OK\r\n\
         Content-Type: application/json\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\
         \r\n\
         {}",
        body.len(),
        body,
    );

    if let Err(e) = stream.write_all(response.as_bytes()).await {
        tracing::debug!(
            target: "messages_bridge::http",
            "failed to write status response: {e}"
        );
    }
}

/// Generates a QR code SVG string from a URL.
///
/// Encodes `url` as a QR code and renders it as an inline SVG with
/// minimum dimensions of 200 x 200 pixels.
///
/// # Panics
///
/// Panics if the QR code library fails to encode the URL, which should
/// not happen for well-formed ASCII URLs.
fn generate_qr_svg(url: &str) -> String {
    use qrcode::QrCode;
    use qrcode::render::svg;

    #[allow(clippy::unwrap_used)]
    let code = QrCode::new(url.as_bytes()).unwrap();
    code.render::<svg::Color>().min_dimensions(200, 200).build()
}

/// Returns the device's outbound LAN IP address as a string.
///
/// Opens a UDP socket and connects to a public address so the kernel
/// selects the correct source interface, then reads back the local address.
/// Falls back to `127.0.0.1` if any step fails.
fn local_ip() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .ok()
        .and_then(|s| {
            s.connect("8.8.8.8:80").ok()?;
            s.local_addr().ok()
        })
        .map_or_else(|| "127.0.0.1".to_owned(), |a| a.ip().to_string())
}

// ── Tests ─────────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn generate_qr_svg_produces_svg_markup() {
        let svg = generate_qr_svg("https://example.com/pair?token=abc123");
        assert!(svg.contains("<svg"), "output should contain an SVG element");
    }

    #[test]
    fn pairing_html_contains_placeholder() {
        assert!(
            PAIRING_HTML.contains("QR_SVG_PLACEHOLDER"),
            "PAIRING_HTML must contain the QR_SVG_PLACEHOLDER token"
        );
    }

    #[test]
    fn pairing_html_placeholder_replaced() {
        let html = PAIRING_HTML.replace("QR_SVG_PLACEHOLDER", "<svg/>");
        assert!(html.contains("<svg/>"), "placeholder should be replaced");
        assert!(
            !html.contains("QR_SVG_PLACEHOLDER"),
            "placeholder should be gone"
        );
    }

    #[tokio::test]
    async fn server_starts_and_reports_port() {
        let paired = Arc::new(AtomicBool::new(false));
        let server = PairingPageServer::start("https://example.com/pair".to_owned(), paired)
            .await
            .unwrap();
        assert!(server.port() > 0, "port should be non-zero");
        assert!(
            server.page_url().starts_with("http://"),
            "page URL should be an HTTP URL"
        );
        drop(server);
    }
}
