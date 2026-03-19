// Copyright (c) 2026 @Natfii. All rights reserved.

//! Session lifecycle manager for the Google Messages bridge.
//!
//! Manages a single global bridge session using a [`parking_lot::Mutex`]-guarded
//! singleton. Only one bridge can be active at a time. The session owns the
//! [`MessagesBridgeStore`], tracks pairing state, and spawns the
//! [`LongPollListener`] background task after pairing completes.
//!
//! Modelled after the ClawBoy session pattern
//! (`zeroclaw-ffi/src/clawboy/session.rs`).

use std::path::{Path, PathBuf};
use std::sync::Arc;

use parking_lot::Mutex;
use prost::Message;
use tokio::sync::{mpsc, oneshot};
use tracing::{debug, info, warn};

use super::client::{endpoints, BugleHttpClient};
use super::crypto::BugleCryptoKeys;
use super::events::BridgeEvent;
use super::longpoll::{DevicePair, LongPollListener};
use super::methods;
use super::pairing;
use super::pblite;
use super::proto::{authentication, client, rpc};
use super::store::MessagesBridgeStore;
use super::types::{BridgeStatus, PairedDevice};

// ── Global singleton ─────────────────────────────────────────────────────────

/// Active bridge session state, guarded by a [`parking_lot::Mutex`].
///
/// Only one Google Messages bridge session can exist at a time.
/// [`parking_lot::Mutex`] does not poison on panic, so no recovery
/// wrapper is needed (unlike `std::sync::Mutex`).
static BRIDGE_SESSION: Mutex<Option<BridgeSession>> = Mutex::new(None);

/// Internal session state for the Google Messages bridge.
struct BridgeSession {
    /// Current connection status exposed to callers.
    status: BridgeStatus,
    /// Root data directory for the bridge store.
    data_dir: PathBuf,
    /// SQLite message store shared across tasks.
    store: Arc<MessagesBridgeStore>,
    /// Device identity established during pairing.
    paired_device: Option<PairedDevice>,
    /// HTTP client with cookies from the pairing flow.
    /// Retained so that cookies set by `RegisterPhoneRelay` are available
    /// for subsequent authenticated requests (e.g. `SendMessage`).
    pairing_http: Option<BugleHttpClient>,
    /// Shutdown signal sender for the long-poll listener task.
    shutdown_tx: Option<oneshot::Sender<()>>,
    /// Handle for the long-poll listener background task.
    longpoll_handle: Option<tokio::task::JoinHandle<()>>,
    /// Handle for the pairing watcher task (waits for QR scan completion).
    pairing_watcher_handle: Option<tokio::task::JoinHandle<()>>,
    /// RPC credentials retained after pairing for on-demand requests.
    rpc_http: Option<BugleHttpClient>,
    /// Crypto keys for encrypting RPC payloads.
    rpc_crypto: Option<BugleCryptoKeys>,
    /// Tachyon auth token for authenticated RPCs.
    rpc_auth_token: Option<Vec<u8>>,
    /// Tachyon TTL for RPCs.
    rpc_ttl: i64,
    /// Device pair for RPC routing.
    rpc_device_pair: Option<DevicePair>,
    /// Session ID for RPC requests.
    rpc_session_id: Option<String>,
    /// Pending history response sender (set by [`fetch_conversation_history`]).
    history_response_tx: Option<oneshot::Sender<Vec<super::types::BridgedMessage>>>,
}

// ── Public API ───────────────────────────────────────────────────────────────

/// Starts a new bridge session by opening the message store.
///
/// Creates the [`MessagesBridgeStore`] at the given `data_dir` and sets the
/// initial status to [`BridgeStatus::Unpaired`]. If a session is already
/// active, it is replaced (the previous session is not shut down — call
/// [`disconnect`] first if needed).
///
/// # Errors
///
/// Returns an error if the SQLite store cannot be opened or created.
pub fn start_session(data_dir: &Path) -> Result<(), anyhow::Error> {
    let store = MessagesBridgeStore::open(data_dir)?;

    let mut guard = BRIDGE_SESSION.lock();
    *guard = Some(BridgeSession {
        status: BridgeStatus::Unpaired,
        data_dir: data_dir.to_path_buf(),
        store: Arc::new(store),
        paired_device: None,
        pairing_http: None,
        shutdown_tx: None,
        longpoll_handle: None,
        pairing_watcher_handle: None,
        rpc_http: None,
        rpc_crypto: None,
        rpc_auth_token: None,
        rpc_ttl: 0,
        rpc_device_pair: None,
        rpc_session_id: None,
        history_response_tx: None,
    });

    info!(
        target: "messages_bridge::session",
        data_dir = %data_dir.display(),
        "bridge session started (unpaired)"
    );

    Ok(())
}

/// Returns the current bridge connection status.
///
/// Returns [`BridgeStatus::Unpaired`] if no session is active.
pub fn get_status() -> BridgeStatus {
    let guard = BRIDGE_SESSION.lock();
    guard
        .as_ref()
        .map(|s| s.status.clone())
        .unwrap_or(BridgeStatus::Unpaired)
}

/// Returns a shared reference to the message store, if a session is active.
pub fn get_store() -> Option<Arc<MessagesBridgeStore>> {
    let guard = BRIDGE_SESSION.lock();
    guard.as_ref().map(|s| Arc::clone(&s.store))
}

/// Updates the bridge connection status.
///
/// No-op if no session is active.
pub fn set_status(status: BridgeStatus) {
    let mut guard = BRIDGE_SESSION.lock();
    if let Some(session) = guard.as_mut() {
        debug!(
            target: "messages_bridge::session",
            ?status,
            "status updated"
        );
        session.status = status;
    }
}

/// Initiates QR-code pairing with Google Messages.
///
/// Creates a [`BugleHttpClient`], calls [`pairing::start_pairing`], sets the
/// session status to [`BridgeStatus::AwaitingPairing`], spawns a background
/// task to watch for the pairing completion event, and returns the QR URL
/// that the user should scan with their phone.
///
/// # Errors
///
/// Returns an error if:
/// - No session is active (call [`start_session`] first)
/// - The HTTP client cannot be created
/// - The pairing RPC call fails
pub async fn begin_pairing(data_dir: &Path) -> Result<String, anyhow::Error> {
    // Ensure a session exists; create one if not.
    {
        let guard = BRIDGE_SESSION.lock();
        if guard.is_none() {
            drop(guard);
            start_session(data_dir)?;
        }
    }

    // Abort any previous watcher before starting a new pairing attempt.
    {
        let mut guard = BRIDGE_SESSION.lock();
        if let Some(session) = guard.as_mut() {
            if let Some(handle) = session.pairing_watcher_handle.take() {
                handle.abort();
            }
        }
    }

    // 1. Register the phone relay and get crypto material.
    let http = BugleHttpClient::new()?;
    let pairing_session = pairing::start_pairing(&http).await?;
    let qr_url = pairing_session.qr_url.clone();
    let relay_tachyon_token = pairing_session.tachyon_auth_token.clone();
    let relay_tachyon_ttl = pairing_session.tachyon_ttl;
    let relay_browser_device = pairing_session.browser_device.clone();
    let crypto_keys = pairing_session.crypto_keys.clone();
    let signing_key_bytes = pairing_session.signing_key.to_bytes().to_vec();

    // 2. Start the long-poll watcher BEFORE returning the QR URL.
    //    This matches the upstream mautrix-gmessages pattern where
    //    doLongPoll() is started before GenerateQRCodeData().
    //    The watcher must be listening when the phone scans the QR,
    //    otherwise the PairEvent is missed.
    let watcher_handle = tokio::spawn(watch_for_pairing(
        relay_tachyon_token,
        relay_tachyon_ttl,
        relay_browser_device,
        crypto_keys,
        signing_key_bytes,
    ));

    // 3. Update session status with the QR URL, store the watcher handle,
    //    and stash the HTTP client so its cookies survive for later requests.
    {
        let mut guard = BRIDGE_SESSION.lock();
        if let Some(session) = guard.as_mut() {
            session.status = BridgeStatus::AwaitingPairing {
                qr_url: qr_url.clone(),
            };
            session.pairing_watcher_handle = Some(watcher_handle);
            session.pairing_http = Some(http);
        }
    }

    // Give the watcher a moment to open its first long-poll connection
    // before the QR is displayed. This reduces the race window to near zero.
    tokio::time::sleep(std::time::Duration::from_millis(500)).await;

    info!(
        target: "messages_bridge::session",
        "pairing initiated, watcher listening, QR URL ready"
    );

    Ok(qr_url)
}

/// Completes pairing by storing device credentials and starting the long-poll listener.
///
/// Call this after the phone has scanned the QR code and the pair event has been
/// received. Spawns the [`LongPollListener`] background task on the tokio
/// runtime, which will emit [`BridgeEvent`]s. Events are routed to the
/// [`MessagesBridgeStore`] for persistence.
///
/// # Panics
///
/// Panics if the HTTP long-poll client cannot be constructed (TLS backend
/// failure), which should be impossible on a system that already completed
/// pairing.
pub fn complete_pairing(paired_device: PairedDevice) {
    let mut guard = BRIDGE_SESSION.lock();
    let session = match guard.as_mut() {
        Some(s) => s,
        None => {
            warn!(
                target: "messages_bridge::session",
                "complete_pairing called with no active session"
            );
            return;
        }
    };

    // Build long-poll credentials from the paired device.
    let crypto_keys = BugleCryptoKeys {
        aes_key: paired_device.aes_key,
        hmac_key: paired_device.hmac_key,
    };
    let device_pair = DevicePair {
        browser: paired_device.browser_id.clone(),
        mobile: paired_device.mobile_id.clone(),
    };
    let tachyon_auth_token = paired_device.tachyon_auth_token.clone();
    let tachyon_ttl = paired_device.tachyon_ttl;
    let signing_key_for_fetch = paired_device.signing_key.clone();
    let browser_id_for_fetch = paired_device.browser_id.clone();

    // Clone the store Arc so the event consumer task can share it.
    let store = Arc::clone(&session.store);

    // Store the paired device.
    session.paired_device = Some(paired_device);

    // Create shutdown channel.
    let (shutdown_tx, shutdown_rx) = oneshot::channel();
    session.shutdown_tx = Some(shutdown_tx);

    // Create event channel.
    let (event_tx, event_rx) = mpsc::channel::<BridgeEvent>(64);

    // Build the long-poll listener.
    let http = BugleHttpClient::new_long_poll()
        .expect("TLS backend should be available after successful pairing");

    // Clone credentials for the initial conversation fetch task before
    // they are moved into the long-poll listener.
    let fetch_crypto = BugleCryptoKeys {
        aes_key: crypto_keys.aes_key,
        hmac_key: crypto_keys.hmac_key,
    };
    let fetch_token = tachyon_auth_token.clone();
    let fetch_ttl = tachyon_ttl;
    let fetch_devices = device_pair.clone();
    let _fetch_signing_key = signing_key_for_fetch;
    let _fetch_browser_id = browser_id_for_fetch;

    // Take the HTTP client from the pairing flow so its cookies
    // (set by RegisterPhoneRelay) are available for SendMessage.
    let fetch_http = session.pairing_http.take();

    let listener = LongPollListener::new(
        http,
        crypto_keys,
        tachyon_auth_token,
        device_pair,
    );

    // Spawn the long-poll listener task with a 2-second delay.
    // The delay lets the phone save pair data. If we reconnect too quickly,
    // the phone won't recognise the session and Google will send another
    // PairEvent that the listener would misinterpret as an unpair.
    // (Matches upstream mautrix-gmessages `completePairing` behaviour.)
    let longpoll_handle = tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
        listener.run(event_tx, shutdown_rx).await;
    });

    // Spawn the event consumer task, passing the shared store.
    tokio::spawn(consume_bridge_events(event_rx, store));

    // Refresh the auth token and request the initial conversation list
    // after the long-poll listener has connected. The relay token from
    // pairing only authorises ReceiveMessages — RegisterRefresh activates
    // it for SendMessage RPCs (e.g. ListConversations).
    // Reuse the pairing HTTP client so cookies from RegisterPhoneRelay
    // are included in the requests.
    tokio::spawn(async move {
        // Wait for the long-poll listener to establish its connection.
        tokio::time::sleep(std::time::Duration::from_secs(4)).await;

        let http = match fetch_http {
            Some(h) => h,
            None => match BugleHttpClient::new() {
                Ok(h) => h,
                Err(e) => {
                    warn!(
                        target: "messages_bridge::session",
                        "failed to create HTTP client for conversation fetch: {e}"
                    );
                    return;
                }
            },
        };

        // Skip RegisterRefresh for freshly paired sessions — the upstream
        // also skips it when the token has >1 hour remaining (which is
        // always the case right after pairing). The relay token is valid
        // for SendMessage RPCs directly.
        let (active_token, active_ttl) = {
            (fetch_token.clone(), fetch_ttl)
        };

        // Mark the session as active so the phone starts pushing data.
        let session_id = match methods::set_active_session(
            &http,
            &fetch_crypto,
            &active_token,
            &fetch_devices,
        )
        .await
        {
            Ok(sid) => {
                info!(
                    target: "messages_bridge::session",
                    "SetActiveSession (GET_UPDATES) sent"
                );
                sid
            }
            Err(e) => {
                warn!(
                    target: "messages_bridge::session",
                    "SetActiveSession failed: {e}"
                );
                uuid::Uuid::new_v4().to_string()
            }
        };

        // Request the initial conversation list.
        match methods::list_conversations(
            &http,
            &fetch_crypto,
            &active_token,
            active_ttl,
            &fetch_devices,
            &session_id,
        )
        .await
        {
            Ok(()) => {
                info!(
                    target: "messages_bridge::session",
                    "initial ListConversations request sent"
                );
            }
            Err(e) => {
                warn!(
                    target: "messages_bridge::session",
                    "failed to request conversation list: {e}"
                );
            }
        }

        // Store RPC credentials for on-demand requests (e.g. fetch_conversation_history).
        {
            let mut guard = BRIDGE_SESSION.lock();
            if let Some(s) = guard.as_mut() {
                s.rpc_crypto = Some(fetch_crypto);
                s.rpc_auth_token = Some(active_token);
                s.rpc_ttl = active_ttl;
                s.rpc_device_pair = Some(fetch_devices);
                s.rpc_session_id = Some(session_id);
                // Create a fresh HTTP client for ad-hoc RPCs.
                s.rpc_http = BugleHttpClient::new().ok();
            }
        }
    });

    session.longpoll_handle = Some(longpoll_handle);
    session.status = BridgeStatus::Connected;

    info!(
        target: "messages_bridge::session",
        "pairing complete, long-poll listener started"
    );
}

/// Fetches message history for a conversation via the `ListMessages` RPC.
///
/// Sends the RPC request and waits for the response to arrive on the
/// long-poll stream (routed via [`BridgeEvent::MessageHistory`]). Returns
/// the messages on success, or an error on timeout or missing credentials.
///
/// # Errors
///
/// Returns an error if the bridge is not paired, RPC credentials are
/// unavailable, the request fails, or the 10-second timeout expires.
pub async fn fetch_conversation_history(
    conversation_id: &str,
    count: i64,
) -> Result<Vec<super::types::BridgedMessage>, anyhow::Error> {
    // Extract RPC credentials and set up the response channel.
    let (rx, http, crypto, token, ttl, devices, session_id) = {
        let mut guard = BRIDGE_SESSION.lock();
        let session = guard
            .as_mut()
            .ok_or_else(|| anyhow::anyhow!("no active bridge session"))?;

        let http = session
            .rpc_http
            .take()
            .or_else(|| BugleHttpClient::new().ok())
            .ok_or_else(|| anyhow::anyhow!("failed to create HTTP client"))?;

        let crypto = session
            .rpc_crypto
            .clone()
            .ok_or_else(|| anyhow::anyhow!("RPC credentials not available (bridge not fully paired)"))?;
        let token = session
            .rpc_auth_token
            .clone()
            .ok_or_else(|| anyhow::anyhow!("auth token not available"))?;
        let ttl = session.rpc_ttl;
        let devices = session
            .rpc_device_pair
            .clone()
            .ok_or_else(|| anyhow::anyhow!("device pair not available"))?;
        let session_id = session
            .rpc_session_id
            .clone()
            .ok_or_else(|| anyhow::anyhow!("session ID not available"))?;

        let (tx, rx) = oneshot::channel();
        session.history_response_tx = Some(tx);

        (rx, http, crypto, token, ttl, devices, session_id)
    };
    // Lock dropped here — safe to await.

    // Send the ListMessages RPC.
    methods::list_messages(
        &http,
        &crypto,
        &token,
        ttl,
        &devices,
        &session_id,
        conversation_id,
        count,
    )
    .await
    .map_err(|e| anyhow::anyhow!("ListMessages RPC failed: {e}"))?;

    info!(
        target: "messages_bridge::session",
        conversation_id,
        count,
        "ListMessages request sent, awaiting response"
    );

    // Return the HTTP client to the session for future use.
    {
        let mut guard = BRIDGE_SESSION.lock();
        if let Some(s) = guard.as_mut() {
            s.rpc_http = Some(http);
        }
    }

    // Wait for the response with a 10-second timeout.
    match tokio::time::timeout(std::time::Duration::from_secs(10), rx).await {
        Ok(Ok(messages)) => Ok(messages),
        Ok(Err(_)) => Err(anyhow::anyhow!("history response channel closed")),
        Err(_) => Err(anyhow::anyhow!(
            "timed out waiting for message history (10s)"
        )),
    }
}

/// Disconnects the bridge, stopping the long-poll listener.
///
/// Sends the shutdown signal to the listener task and sets the status back to
/// [`BridgeStatus::Unpaired`]. The store data is preserved so the user can
/// re-pair without losing conversation history.
pub fn disconnect() {
    let mut guard = BRIDGE_SESSION.lock();
    let session = match guard.as_mut() {
        Some(s) => s,
        None => return,
    };

    // Abort pairing watcher if still running.
    if let Some(handle) = session.pairing_watcher_handle.take() {
        handle.abort();
    }

    // Send shutdown signal.
    if let Some(tx) = session.shutdown_tx.take() {
        let _ = tx.send(());
    }

    // Abort the long-poll handle if still running.
    if let Some(handle) = session.longpoll_handle.take() {
        handle.abort();
    }

    session.paired_device = None;
    session.status = BridgeStatus::Unpaired;

    info!(
        target: "messages_bridge::session",
        "bridge disconnected (store preserved)"
    );
}

/// Disconnects the bridge and wipes all stored data.
///
/// Calls [`disconnect`] to stop the listener, then wipes the SQLite store
/// (all conversations, messages, and FTS data).
pub fn disconnect_and_clear() {
    disconnect();

    let guard = BRIDGE_SESSION.lock();
    if let Some(session) = guard.as_ref() {
        if let Err(e) = session.store.wipe() {
            warn!(
                target: "messages_bridge::session",
                "failed to wipe store: {e}"
            );
        } else {
            info!(
                target: "messages_bridge::session",
                "bridge disconnected and store wiped"
            );
        }
    }
}

/// Returns whether the bridge is currently paired with a phone.
pub fn is_paired() -> bool {
    let guard = BRIDGE_SESSION.lock();
    guard
        .as_ref()
        .map(|s| s.paired_device.is_some())
        .unwrap_or(false)
}

// ── Pairing watcher ──────────────────────────────────────────────────────────

/// Maximum number of long-poll cycles the pairing watcher will attempt
/// before giving up (~10 minutes with typical relay timeouts).
const PAIRING_WATCHER_MAX_CYCLES: u32 = 30;

/// Background task that repeatedly long-polls `ReceiveMessages` waiting for
/// the phone to complete the QR pairing handshake.
///
/// Each long-poll cycle may return heartbeats, acks, or the actual pair
/// event. The watcher loops until it finds a `PairEvent` containing
/// [`authentication::PairedData`] with the mobile device identity and a
/// fresh tachyon auth token, then calls [`complete_pairing`] to transition
/// the session to `Connected`.
async fn watch_for_pairing(
    tachyon_auth_token: Vec<u8>,
    tachyon_ttl: i64,
    browser_device: Vec<u8>,
    crypto_keys: BugleCryptoKeys,
    signing_key_bytes: Vec<u8>,
) {
    info!(
        target: "messages_bridge::session",
        "pairing watcher started — waiting for QR scan"
    );

    let http = match BugleHttpClient::new_long_poll() {
        Ok(h) => h,
        Err(e) => {
            warn!(
                target: "messages_bridge::session",
                "failed to create HTTP client for pairing watcher: {e}"
            );
            return;
        }
    };

    let url = endpoints::receive_messages();

    for cycle in 1..=PAIRING_WATCHER_MAX_CYCLES {
        // Check if we've been disconnected or pairing was cancelled.
        {
            let guard = BRIDGE_SESSION.lock();
            match guard.as_ref().map(|s| &s.status) {
                Some(BridgeStatus::AwaitingPairing { .. }) => {}
                _ => {
                    info!(
                        target: "messages_bridge::session",
                        "pairing watcher: session no longer awaiting pairing, exiting"
                    );
                    return;
                }
            }
        }

        // Build a fresh request each cycle (new request ID).
        let config_version = authentication::ConfigVersion {
            year: 2025,
            month: 11,
            day: 6,
            v1: 4,
            v2: 6,
        };

        let auth = authentication::AuthMessage {
            request_id: uuid::Uuid::new_v4().to_string(),
            network: "Bugle".to_owned(),
            tachyon_auth_token: tachyon_auth_token.clone(),
            config_version: Some(config_version),
        };

        let request = client::ReceiveMessagesRequest {
            auth: Some(auth),
            unknown: Some(client::receive_messages_request::UnknownEmptyObject2 {
                unknown: Some(
                    client::receive_messages_request::UnknownEmptyObject1 {},
                ),
            }),
        };

        // Encode as PBLite (application/json+protobuf) — the ReceiveMessages
        // endpoint requires PBLite encoding, not binary protobuf.
        let pblite_body = pblite::encode_receive_messages_request(&request);

        debug!(
            target: "messages_bridge::session",
            cycle,
            "pairing watcher: opening long-poll (PBLite)"
        );

        warn!(
            target: "messages_bridge::session",
            cycle,
            "pairing watcher: sending long-poll request"
        );

        let response = match http.start_long_poll_pblite(&url, &pblite_body).await {
            Ok(r) => {
                warn!(
                    target: "messages_bridge::session",
                    cycle,
                    status = r.status().as_u16(),
                    "pairing watcher: long-poll HTTP OK"
                );
                r
            }
            Err(e) => {
                warn!(
                    target: "messages_bridge::session",
                    cycle,
                    error = %e,
                    "pairing watcher long-poll failed, retrying"
                );
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                continue;
            }
        };

        // Read the PBLite stream incrementally so we process the PairEvent
        // as soon as Google sends it, rather than waiting for the entire
        // long-poll response to close (which can take 30+ minutes).
        let tachyon_clone = tachyon_auth_token.clone();
        let keys_clone = crypto_keys.clone();
        let signing_key_clone = signing_key_bytes.clone();
        let mut found_pair = false;

        let stream_result = pblite::read_pblite_stream(response, |payload_val| {
            let preview: String = payload_val.to_string().chars().take(200).collect();
            let lp_payload = match pblite::decode::<rpc::LongPollingPayload>(&payload_val) {
                Ok(p) => p,
                Err(e) => {
                    warn!(
                        target: "messages_bridge::session",
                        error = %e,
                        %preview,
                        "watcher: failed to decode LongPollingPayload"
                    );
                    return true;
                }
            };

            // Heartbeats, acks, and start-reads are expected — keep reading.
            if lp_payload.heartbeat.is_some()
                || lp_payload.ack.is_some()
                || lp_payload.start_read.is_some()
            {
                return true;
            }

            let rpc_msg = match &lp_payload.data {
                Some(msg) => msg,
                None => {
                    warn!(
                        target: "messages_bridge::session",
                        %preview,
                        "watcher: payload has no data/heartbeat/ack/startRead"
                    );
                    return true;
                }
            };

            let route = rpc_msg.bugle_route;
            warn!(
                target: "messages_bridge::session",
                route,
                data_len = rpc_msg.message_data.len(),
                "watcher: received RPC message"
            );

            // Check for PairEvent route.
            if rpc::BugleRoute::try_from(route) != Ok(rpc::BugleRoute::PairEvent) {
                warn!(
                    target: "messages_bridge::session",
                    route,
                    "watcher: not a PairEvent, ignoring"
                );
                return true;
            }

            warn!(
                target: "messages_bridge::session",
                "watcher: GOT PairEvent!"
            );

            // Decode RPCPairData wrapper, then extract PairedData.
            // The messageData is an RPCPairData oneof — PairedData is at
            // field 4, not field 1, so decoding directly as PairedData
            // produces empty fields.
            if !rpc_msg.message_data.is_empty() {
                if let Ok(pair_data) =
                    super::proto::events::RpcPairData::decode(
                        rpc_msg.message_data.as_slice(),
                    )
                {
                    if let Some(
                        super::proto::events::rpc_pair_data::Event::Paired(
                            paired_data,
                        ),
                    ) = pair_data.event
                    {
                        let (new_token, new_ttl) = paired_data
                            .token_data
                            .map(|td| (td.tachyon_auth_token, td.ttl))
                            .unwrap_or((tachyon_clone.clone(), tachyon_ttl));

                        let mobile_id = paired_data
                            .mobile
                            .map(|d| d.encode_to_vec())
                            .unwrap_or_default();
                        let browser_id = paired_data
                            .browser
                            .map(|d| d.encode_to_vec())
                            .unwrap_or(browser_device.clone());

                        warn!(
                            target: "messages_bridge::session",
                            mobile_empty = mobile_id.is_empty(),
                            browser_empty = browser_id.is_empty(),
                            has_token = !new_token.is_empty(),
                            "watcher: extracted PairedData from RPCPairData"
                        );

                        complete_pairing(PairedDevice {
                            browser_id,
                            mobile_id,
                            tachyon_auth_token: new_token,
                            tachyon_ttl: new_ttl,
                            aes_key: keys_clone.aes_key,
                            hmac_key: keys_clone.hmac_key,
                            signing_key: signing_key_clone.clone(),
                        });
                        found_pair = true;
                        return false; // stop reading
                    }
                }
            }

            // Fallback: extract device info from IncomingRPCMessage fields.
            let mobile_id = rpc_msg
                .mobile
                .as_ref()
                .map(|d| d.encode_to_vec())
                .unwrap_or_default();
            let browser_id = rpc_msg
                .browser
                .as_ref()
                .map(|d| d.encode_to_vec())
                .unwrap_or(browser_device.clone());

            complete_pairing(PairedDevice {
                browser_id,
                mobile_id,
                tachyon_auth_token: tachyon_clone.clone(),
                tachyon_ttl,
                aes_key: keys_clone.aes_key,
                hmac_key: keys_clone.hmac_key,
                signing_key: signing_key_clone.clone(),
            });
            found_pair = true;
            false // stop reading
        })
        .await;

        if found_pair {
            return;
        }

        if let Err(e) = stream_result {
            warn!(
                target: "messages_bridge::session",
                cycle,
                error = %e,
                "pairing watcher stream read failed, retrying"
            );
        }
    }

    warn!(
        target: "messages_bridge::session",
        "pairing watcher exhausted {} cycles without finding PairEvent — resetting to Unpaired",
        PAIRING_WATCHER_MAX_CYCLES,
    );
    set_status(BridgeStatus::Unpaired);
}

// ── Event consumer ───────────────────────────────────────────────────────────

/// Background task that drains the [`BridgeEvent`] channel and routes events
/// to the [`MessagesBridgeStore`] and session status.
///
/// Handles conversation syncs, new messages, connection status changes, and
/// pairing lifecycle events. Store errors are logged but never fatal — the
/// consumer continues processing subsequent events.
async fn consume_bridge_events(
    mut event_rx: mpsc::Receiver<BridgeEvent>,
    store: Arc<MessagesBridgeStore>,
) {
    info!(
        target: "messages_bridge::session",
        "event consumer started"
    );

    while let Some(event) = event_rx.recv().await {
        match event {
            BridgeEvent::ConversationListSync { conversations } => {
                let count = conversations.len();
                for conv in &conversations {
                    if let Err(e) = store.upsert_conversation(conv) {
                        tracing::error!(
                            target: "messages_bridge::session",
                            conversation_id = %conv.id,
                            "failed to upsert conversation: {e}"
                        );
                    }
                }
                info!(
                    target: "messages_bridge::session",
                    count,
                    "synced conversations"
                );
            }
            BridgeEvent::NewMessage(msg) => {
                debug!(
                    target: "messages_bridge::session",
                    conversation_id = %msg.conversation_id,
                    sender = %msg.sender_name,
                    "received new message"
                );
                if let Err(e) = store.store_messages(std::slice::from_ref(&msg)) {
                    tracing::error!(
                        target: "messages_bridge::session",
                        "failed to store message: {e}"
                    );
                }
                // Update the conversation's last-message preview and timestamp.
                match store.list_conversations() {
                    Ok(convs) => {
                        if let Some(conv) = convs.iter().find(|c| c.id == msg.conversation_id) {
                            let mut updated = conv.clone();
                            updated.last_message_preview =
                                msg.body.chars().take(100).collect();
                            updated.last_message_timestamp = msg.timestamp;
                            if let Err(e) = store.upsert_conversation(&updated) {
                                tracing::error!(
                                    target: "messages_bridge::session",
                                    conversation_id = %msg.conversation_id,
                                    "failed to update conversation preview: {e}"
                                );
                            }
                        }
                    }
                    Err(e) => {
                        tracing::error!(
                            target: "messages_bridge::session",
                            "failed to list conversations for preview update: {e}"
                        );
                    }
                }
            }
            BridgeEvent::PairSuccess { .. } => {
                info!(
                    target: "messages_bridge::session",
                    "pairing confirmed by server; credentials held in memory"
                );
                set_status(BridgeStatus::Connected);
            }
            BridgeEvent::PhoneNotResponding => {
                warn!(
                    target: "messages_bridge::session",
                    "phone not responding"
                );
                set_status(BridgeStatus::PhoneNotResponding);
            }
            BridgeEvent::PhoneRespondingAgain => {
                info!(
                    target: "messages_bridge::session",
                    "phone responding again"
                );
                set_status(BridgeStatus::Connected);
            }
            BridgeEvent::Disconnected { reason } => {
                warn!(
                    target: "messages_bridge::session",
                    %reason,
                    "bridge disconnected, will attempt reconnect"
                );
                set_status(BridgeStatus::Reconnecting { attempt: 1 });
            }
            BridgeEvent::Unpaired => {
                warn!(
                    target: "messages_bridge::session",
                    "bridge unpaired by Google or phone"
                );
                set_status(BridgeStatus::Unpaired);
            }
            BridgeEvent::BrowserPresenceCheck => {
                debug!(
                    target: "messages_bridge::session",
                    "browser presence check received"
                );
            }
            BridgeEvent::UserAlert { alert_type } => {
                info!(
                    target: "messages_bridge::session",
                    alert_type,
                    "user alert received"
                );
            }
            BridgeEvent::MessageHistory {
                conversation_id,
                messages,
            } => {
                info!(
                    target: "messages_bridge::session",
                    conversation_id = %conversation_id,
                    count = messages.len(),
                    "received message history"
                );
                // Store messages in the database.
                if let Err(e) = store.store_messages(&messages) {
                    tracing::error!(
                        target: "messages_bridge::session",
                        "failed to store message history: {e}"
                    );
                }
                // Send to the waiting fetch_conversation_history caller.
                let tx = {
                    let mut guard = BRIDGE_SESSION.lock();
                    guard
                        .as_mut()
                        .and_then(|s| s.history_response_tx.take())
                };
                if let Some(tx) = tx {
                    let _ = tx.send(messages);
                }
            }
        }
    }

    info!(
        target: "messages_bridge::session",
        "event consumer stopped (channel closed)"
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn get_status_returns_unpaired_when_no_session() {
        // Ensure no session is active.
        {
            let mut guard = BRIDGE_SESSION.lock();
            *guard = None;
        }
        assert_eq!(get_status(), BridgeStatus::Unpaired);
    }

    #[test]
    fn start_session_sets_unpaired_status() {
        let tmp = tempfile::tempdir().unwrap();
        start_session(tmp.path()).unwrap();

        assert_eq!(get_status(), BridgeStatus::Unpaired);
        assert!(get_store().is_some());
        assert!(!is_paired());

        // Clean up.
        let mut guard = BRIDGE_SESSION.lock();
        *guard = None;
    }

    #[test]
    fn set_status_updates_correctly() {
        let tmp = tempfile::tempdir().unwrap();
        start_session(tmp.path()).unwrap();

        set_status(BridgeStatus::Connected);
        assert_eq!(get_status(), BridgeStatus::Connected);

        set_status(BridgeStatus::PhoneNotResponding);
        assert_eq!(get_status(), BridgeStatus::PhoneNotResponding);

        // Clean up.
        let mut guard = BRIDGE_SESSION.lock();
        *guard = None;
    }

    #[test]
    fn disconnect_resets_to_unpaired() {
        let tmp = tempfile::tempdir().unwrap();
        start_session(tmp.path()).unwrap();

        set_status(BridgeStatus::Connected);
        disconnect();

        assert_eq!(get_status(), BridgeStatus::Unpaired);

        // Clean up.
        let mut guard = BRIDGE_SESSION.lock();
        *guard = None;
    }
}
