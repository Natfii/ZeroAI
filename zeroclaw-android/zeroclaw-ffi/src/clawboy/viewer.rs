// Copyright (c) 2026 @Natfii. All rights reserved.

//! WebSocket server for streaming Game Boy frames to a browser viewer.
//!
//! Binds to a random available port on all interfaces. Plain HTTP GET
//! requests receive the embedded HTML viewer page; WebSocket upgrade
//! requests are promoted to a binary frame stream at ~15 fps.

use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{oneshot, watch};
use tokio_tungstenite::tungstenite::Message;

/// Streams Game Boy frames to a browser-based canvas viewer.
///
/// Binds to a random available port on all interfaces. HTTP GET
/// requests receive the embedded HTML viewer page. WebSocket
/// connections receive binary RGB565 frame data at ~15 fps.
///
/// Only one viewer connection streams at a time; additional
/// WebSocket clients are accepted but each runs its own
/// independent stream loop off the shared watch channel.
pub struct ViewerServer {
    /// Port the server is bound to.
    port: u16,
    /// Sends new frame data to the broadcast loop.
    #[allow(dead_code)]
    frame_tx: watch::Sender<Arc<Vec<u8>>>,
    /// Signals the accept loop to shut down.
    shutdown_tx: Option<oneshot::Sender<()>>,
    /// Handle for the accept loop task.
    handle: Option<tokio::task::JoinHandle<()>>,
}

/// Maximum bytes to peek from an incoming TCP connection when
/// deciding whether to serve HTML or upgrade to WebSocket.
const PEEK_BUF_SIZE: usize = 4096;

/// Timeout for awaiting the accept loop to finish during shutdown.
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(3);

impl ViewerServer {
    /// Starts the viewer server on a random available port.
    ///
    /// Binds a TCP listener to `0.0.0.0:0`, which lets the OS assign
    /// an available port. Spawns a background tokio task that accepts
    /// connections and routes them to either the HTML page or the
    /// WebSocket frame stream.
    ///
    /// # Errors
    ///
    /// Returns an error string if the TCP listener cannot be bound.
    #[allow(dead_code)]
    pub async fn start() -> Result<Self, String> {
        let listener = TcpListener::bind("0.0.0.0:0")
            .await
            .map_err(|e| format!("failed to bind viewer listener: {e}"))?;

        let port = listener
            .local_addr()
            .map_err(|e| format!("failed to get local address: {e}"))?
            .port();

        let (frame_tx, frame_rx) = watch::channel(Arc::new(Vec::new()));
        let (shutdown_tx, shutdown_rx) = oneshot::channel();

        let handle = tokio::spawn(accept_loop(listener, frame_rx, shutdown_rx));

        tracing::info!(target: "clawboy::ws", port, "viewer server started");

        Ok(Self {
            port,
            frame_tx,
            shutdown_tx: Some(shutdown_tx),
            handle: Some(handle),
        })
    }

    /// Starts the viewer server with an externally-owned frame channel.
    ///
    /// Like [`start`](Self::start), but the caller provides the
    /// [`watch::Receiver`] side of the frame channel. This allows the
    /// session lifecycle manager to own the [`watch::Sender`] and feed
    /// frames from the tick loop without borrowing the server.
    ///
    /// # Errors
    ///
    /// Returns an error string if the TCP listener cannot be bound.
    pub async fn start_with_frame_channel(
        frame_rx: watch::Receiver<Arc<Vec<u8>>>,
    ) -> Result<Self, String> {
        let listener = TcpListener::bind("0.0.0.0:0")
            .await
            .map_err(|e| format!("failed to bind viewer listener: {e}"))?;

        let port = listener
            .local_addr()
            .map_err(|e| format!("failed to get local address: {e}"))?
            .port();

        // Create a dummy sender that is never used for sending — the
        // caller owns the real sender. We still need a `Sender` in the
        // struct so `send_frame` compiles, but it won't be called when
        // this constructor is used.
        let (frame_tx, _) = watch::channel(Arc::new(Vec::new()));
        let (shutdown_tx, shutdown_rx) = oneshot::channel();

        let handle = tokio::spawn(accept_loop(listener, frame_rx, shutdown_rx));

        tracing::info!(target: "clawboy::ws", port, "viewer server started (external channel)");

        Ok(Self {
            port,
            frame_tx,
            shutdown_tx: Some(shutdown_tx),
            handle: Some(handle),
        })
    }

    /// Returns the port the server is bound to.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Publishes a new frame to all connected viewers.
    ///
    /// Updates the watch channel with the latest RGB565 frame data.
    /// Connected WebSocket clients will pick up the new frame on
    /// their next receive cycle.
    #[allow(dead_code)]
    pub fn send_frame(&self, frame_data: Vec<u8>) {
        if let Err(e) = self.frame_tx.send(Arc::new(frame_data)) {
            tracing::warn!(
                target: "clawboy::ws",
                "failed to broadcast frame: {e}"
            );
        }
    }

    /// Shuts down the viewer server and waits for cleanup.
    ///
    /// Sends the shutdown signal to the accept loop and awaits
    /// its completion with a timeout. Any connected WebSocket
    /// clients will be disconnected when the loop exits.
    pub async fn shutdown(mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }

        if let Some(handle) = self.handle.take() {
            match tokio::time::timeout(SHUTDOWN_TIMEOUT, handle).await {
                Ok(Ok(())) => {
                    tracing::info!(target: "clawboy::ws", "viewer server stopped");
                }
                Ok(Err(e)) => {
                    tracing::warn!(
                        target: "clawboy::ws",
                        "viewer accept loop panicked: {e}"
                    );
                }
                Err(_) => {
                    tracing::warn!(
                        target: "clawboy::ws",
                        "viewer shutdown timed out after {}s",
                        SHUTDOWN_TIMEOUT.as_secs()
                    );
                }
            }
        }
    }
}

/// Accept loop that runs in a spawned tokio task.
///
/// Listens for incoming TCP connections and spawns a handler for
/// each one. Exits when the shutdown signal is received.
async fn accept_loop(
    listener: TcpListener,
    frame_rx: watch::Receiver<Arc<Vec<u8>>>,
    mut shutdown: oneshot::Receiver<()>,
) {
    loop {
        tokio::select! {
            result = listener.accept() => {
                match result {
                    Ok((stream, addr)) => {
                        tracing::info!(
                            target: "clawboy::ws",
                            %addr,
                            "viewer client connected"
                        );
                        let rx = frame_rx.clone();
                        tokio::spawn(handle_connection(stream, rx));
                    }
                    Err(e) => {
                        tracing::warn!(
                            target: "clawboy::ws",
                            "failed to accept connection: {e}"
                        );
                    }
                }
            }
            _ = &mut shutdown => {
                tracing::info!(
                    target: "clawboy::ws",
                    "viewer server shutting down"
                );
                break;
            }
        }
    }
}

/// Handles a single incoming TCP connection.
///
/// Peeks at the initial bytes to determine whether this is a plain
/// HTTP request (serve the HTML viewer page) or a WebSocket upgrade
/// request (stream frames). Uses [`TcpStream::peek`] so the bytes
/// remain in the kernel buffer for `accept_async` to consume during
/// the WebSocket handshake.
async fn handle_connection(stream: TcpStream, frame_rx: watch::Receiver<Arc<Vec<u8>>>) {
    let mut peek_buf = [0u8; PEEK_BUF_SIZE];

    let n = match stream.peek(&mut peek_buf).await {
        Ok(n) => n,
        Err(e) => {
            tracing::debug!(
                target: "clawboy::ws",
                "peek failed: {e}"
            );
            return;
        }
    };

    let header_str = String::from_utf8_lossy(&peek_buf[..n]);
    let is_websocket = header_str
        .to_ascii_lowercase()
        .contains("upgrade: websocket");

    if is_websocket {
        handle_websocket(stream, frame_rx).await;
    } else {
        handle_http(stream).await;
    }
}

/// Serves the embedded HTML viewer page over plain HTTP.
///
/// Reads and discards the HTTP request from the stream (the same
/// bytes we already peeked at), then writes back an HTTP 200
/// response containing the viewer HTML.
async fn handle_http(mut stream: TcpStream) {
    use tokio::io::AsyncReadExt as _;

    // Consume the request bytes that were previously peeked.
    // A simple browser GET request fits in a single read.
    let mut discard = [0u8; PEEK_BUF_SIZE];
    match stream.read(&mut discard).await {
        Ok(0) | Err(_) => return,
        Ok(_) => {}
    }

    let html = super::viewer_page::VIEWER_HTML;
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
            target: "clawboy::ws",
            "failed to write HTTP response: {e}"
        );
    }
}

/// Upgrades a TCP connection to WebSocket and streams frames.
///
/// Performs the WebSocket handshake via `tokio_tungstenite::accept_async`,
/// then enters a loop that watches for new frames on the watch channel
/// and sends each one as a binary WebSocket message. Exits when the
/// frame sender is dropped or the client disconnects.
async fn handle_websocket(stream: TcpStream, mut frame_rx: watch::Receiver<Arc<Vec<u8>>>) {
    let ws_stream = match tokio_tungstenite::accept_async(stream).await {
        Ok(ws) => ws,
        Err(e) => {
            tracing::warn!(
                target: "clawboy::ws",
                "WebSocket handshake failed: {e}"
            );
            return;
        }
    };

    let (mut write, mut read) = ws_stream.split();

    loop {
        tokio::select! {
            // Watch for new frames to send.
            result = frame_rx.changed() => {
                if result.is_err() {
                    // Sender dropped — server is shutting down.
                    break;
                }

                let frame = frame_rx.borrow_and_update().clone();
                if frame.is_empty() {
                    continue;
                }

                let msg = Message::Binary(frame.to_vec().into());
                if let Err(e) = write.send(msg).await {
                    tracing::info!(
                        target: "clawboy::ws",
                        "viewer disconnected: {e}"
                    );
                    break;
                }
            }
            // Drain incoming messages so the connection doesn't stall.
            msg = read.next() => {
                match msg {
                    Some(Ok(Message::Close(_))) | None => {
                        tracing::info!(
                            target: "clawboy::ws",
                            "viewer closed connection"
                        );
                        break;
                    }
                    Some(Err(e)) => {
                        tracing::info!(
                            target: "clawboy::ws",
                            "viewer read error: {e}"
                        );
                        break;
                    }
                    // Ignore pings/pongs/text — tungstenite auto-responds to pings.
                    Some(Ok(_)) => {}
                }
            }
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn server_starts_and_reports_port() {
        let server = ViewerServer::start().await.unwrap();
        assert!(server.port() > 0, "port should be non-zero");
        server.shutdown().await;
    }

    #[tokio::test]
    async fn send_frame_does_not_panic_on_empty() {
        let server = ViewerServer::start().await.unwrap();
        server.send_frame(Vec::new());
        server.send_frame(vec![0u8; 46_080]);
        server.shutdown().await;
    }
}
