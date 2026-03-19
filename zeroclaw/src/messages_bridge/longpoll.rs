// Copyright (c) 2026 @Natfii. All rights reserved.

//! Long-poll listener for the Google Messages Bugle protocol.
//!
//! Opens an HTTP long-poll connection to the `ReceiveMessages` endpoint and
//! streams incoming RPC messages to the session manager via an [`mpsc`] channel.
//! Handles exponential-backoff reconnection, keepalive pinging, and phone
//! responsiveness tracking.
//!
//! Ported from mautrix-gmessages:
//! <https://github.com/mautrix/gmessages/blob/main/pkg/libgm/longpoll.go>

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Duration;

use prost::Message;
use tokio::sync::{mpsc, oneshot};
use tokio::time::sleep;
use tracing::{debug, info, warn};

use super::client::{endpoints, BugleHttpClient, BugleHttpError};
use super::crypto::BugleCryptoKeys;
use super::events::{self, BridgeEvent};
use super::pblite;
use super::proto::{authentication, client, rpc};

/// Interval between keepalive pings sent to the server (15 minutes).
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(900);

/// Maximum delay between reconnection attempts (≈64 minutes).
const MAX_RECONNECT_DELAY: Duration = Duration::from_secs(3840);

/// Base delay for the first reconnection attempt.
const BASE_RECONNECT_DELAY: Duration = Duration::from_secs(1);

/// Number of consecutive keepalive failures before emitting
/// [`BridgeEvent::PhoneNotResponding`].
const PHONE_NOT_RESPONDING_THRESHOLD: u32 = 3;

/// Browser + mobile device identity pair established during QR pairing.
///
/// Both fields are serialised [`authentication::Device`] bytes that are
/// embedded in outgoing RPC requests to identify the session.
#[derive(Debug, Clone)]
pub struct DevicePair {
    /// Serialised browser [`authentication::Device`] protobuf bytes.
    pub browser: Vec<u8>,
    /// Serialised mobile [`authentication::Device`] protobuf bytes.
    pub mobile: Vec<u8>,
}

/// Long-poll listener that maintains a persistent streaming connection to the
/// Google Messages `ReceiveMessages` endpoint.
///
/// Created after a successful QR-code pairing.  Call [`LongPollListener::run`]
/// to start the event loop, which will emit [`BridgeEvent`]s on the provided
/// channel until shutdown is requested or an unrecoverable error occurs.
pub struct LongPollListener {
    /// Pre-configured HTTP client with a 30-minute timeout.
    http: BugleHttpClient,
    /// AES-256 + HMAC-SHA256 keys for decrypting incoming message payloads.
    crypto_keys: BugleCryptoKeys,
    /// Tachyon auth token for authenticating with the Bugle API.
    tachyon_auth_token: Vec<u8>,
    /// Browser/mobile device pair from the pairing session.
    device_pair: DevicePair,
}

impl LongPollListener {
    /// Creates a new long-poll listener with the given session credentials.
    ///
    /// The `http` client should be created with [`BugleHttpClient::new_long_poll`]
    /// for the extended 30-minute timeout.
    pub fn new(
        http: BugleHttpClient,
        crypto_keys: BugleCryptoKeys,
        tachyon_auth_token: Vec<u8>,
        device_pair: DevicePair,
    ) -> Self {
        Self {
            http,
            crypto_keys,
            tachyon_auth_token,
            device_pair,
        }
    }

    /// Runs the long-poll event loop until shutdown is requested.
    ///
    /// Connects to the `ReceiveMessages` endpoint, reads the PBLite response
    /// stream, decrypts and decodes each payload, and sends the resulting
    /// [`BridgeEvent`]s to `event_tx`.
    ///
    /// On connection failure the listener performs exponential-backoff
    /// reconnection (1 s → 2 s → 4 s → … → 64 min cap).  A keepalive task
    /// tracks phone responsiveness and emits
    /// [`BridgeEvent::PhoneNotResponding`] / [`BridgeEvent::PhoneRespondingAgain`]
    /// when consecutive failures cross the threshold.
    ///
    /// The loop exits cleanly when `shutdown_rx` fires or the `event_tx`
    /// channel is closed.
    pub async fn run(
        self,
        event_tx: mpsc::Sender<BridgeEvent>,
        mut shutdown_rx: oneshot::Receiver<()>,
    ) {
        let mut seen_hashes: VecDeque<[u8; 32]> = VecDeque::new();
        let mut reconnect_delay = BASE_RECONNECT_DELAY;
        let consecutive_failures = Arc::new(AtomicU32::new(0));
        let mut phone_was_unresponsive = false;

        info!("long-poll listener starting");

        loop {
            // Check for shutdown before each connection attempt.
            if shutdown_rx.try_recv().is_ok() {
                info!("long-poll listener received shutdown signal");
                break;
            }

            // Spawn keepalive pinger for this connection cycle.
            let (keepalive_cancel_tx, keepalive_cancel_rx) = oneshot::channel::<()>();
            let failures_clone = Arc::clone(&consecutive_failures);
            let event_tx_keepalive = event_tx.clone();
            let keepalive_handle = tokio::spawn(Self::keepalive_pinger(
                failures_clone,
                event_tx_keepalive,
                keepalive_cancel_rx,
            ));

            match self.do_long_poll(&event_tx, &mut seen_hashes).await {
                Ok(()) => {
                    // Successful connection resets the backoff.
                    reconnect_delay = BASE_RECONNECT_DELAY;
                    consecutive_failures.store(0, Ordering::Relaxed);

                    // Check if phone was previously unresponsive.
                    if phone_was_unresponsive {
                        phone_was_unresponsive = false;
                        let _ = event_tx.send(BridgeEvent::PhoneRespondingAgain).await;
                    }
                }
                Err(e) => {
                    let failure_count =
                        consecutive_failures.fetch_add(1, Ordering::Relaxed) + 1;

                    if failure_count >= PHONE_NOT_RESPONDING_THRESHOLD
                        && !phone_was_unresponsive
                    {
                        phone_was_unresponsive = true;
                        let _ = event_tx.send(BridgeEvent::PhoneNotResponding).await;
                    }

                    warn!(
                        error = %e,
                        attempt = failure_count,
                        delay_secs = reconnect_delay.as_secs(),
                        "long-poll connection failed, reconnecting"
                    );

                    // Exponential backoff with cap.
                    tokio::select! {
                        () = sleep(reconnect_delay) => {}
                        _ = &mut shutdown_rx => {
                            info!("long-poll listener received shutdown during backoff");
                            let _ = keepalive_cancel_tx.send(());
                            keepalive_handle.abort();
                            break;
                        }
                    }

                    reconnect_delay = std::cmp::min(
                        reconnect_delay.saturating_mul(2),
                        MAX_RECONNECT_DELAY,
                    );
                }
            }

            // Cancel the keepalive pinger for this cycle.
            let _ = keepalive_cancel_tx.send(());
            keepalive_handle.abort();
        }

        let _ = event_tx
            .send(BridgeEvent::Disconnected {
                reason: "listener shut down".to_owned(),
            })
            .await;

        info!("long-poll listener stopped");
    }

    /// Executes a single long-poll HTTP request cycle.
    ///
    /// Builds the `ReceiveMessagesRequest` proto, encodes it as PBLite
    /// (`application/json+protobuf`), POSTs it to the `ReceiveMessages`
    /// endpoint, and streams the response — processing each payload
    /// incrementally as it arrives from the server.
    async fn do_long_poll(
        &self,
        event_tx: &mpsc::Sender<BridgeEvent>,
        seen_hashes: &mut VecDeque<[u8; 32]>,
    ) -> Result<(), BugleHttpError> {
        let request = self.build_receive_messages_request();
        let pblite_body = pblite::encode_receive_messages_request(&request);
        let url = endpoints::receive_messages();

        debug!("opening long-poll connection (PBLite, streaming)");

        let response = self.http.start_long_poll_pblite(&url, &pblite_body).await?;

        let crypto = &self.crypto_keys;
        let result = pblite::read_pblite_stream(response, |payload_val| {
            if let Ok(lp_payload) = pblite::decode::<rpc::LongPollingPayload>(&payload_val) {
                Self::process_payload_sync(
                    &lp_payload,
                    crypto,
                    seen_hashes,
                    event_tx,
                );
            } else {
                trace_payload_decode_failure(&payload_val);
            }
            true // keep reading
        })
        .await;

        match result {
            Ok(_) => {
                debug!("long-poll stream ended normally");
                Ok(())
            }
            Err(e) => Err(BugleHttpError::ServerError {
                status: 0,
                body: e,
            }),
        }
    }

    /// Constructs a [`client::ReceiveMessagesRequest`] proto for the current session.
    fn build_receive_messages_request(&self) -> client::ReceiveMessagesRequest {
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
            tachyon_auth_token: self.tachyon_auth_token.clone(),
            config_version: Some(config_version),
        };

        client::ReceiveMessagesRequest {
            auth: Some(auth),
            unknown: Some(client::receive_messages_request::UnknownEmptyObject2 {
                unknown: Some(
                    client::receive_messages_request::UnknownEmptyObject1 {},
                ),
            }),
        }
    }

    /// Processes a single decoded [`rpc::LongPollingPayload`] synchronously.
    ///
    /// Uses [`mpsc::Sender::try_send`] instead of async `send` so it can be
    /// called from the synchronous [`pblite::read_pblite_stream`] callback.
    /// The event channel has capacity 64, which is more than sufficient for
    /// the throughput of a single long-poll response.
    fn process_payload_sync(
        payload: &rpc::LongPollingPayload,
        crypto_keys: &BugleCryptoKeys,
        seen_hashes: &mut VecDeque<[u8; 32]>,
        event_tx: &mpsc::Sender<BridgeEvent>,
    ) {
        if payload.heartbeat.is_some() {
            debug!("received heartbeat");
            return;
        }
        if payload.ack.is_some() {
            debug!("received start ack");
            return;
        }
        if payload.start_read.is_some() {
            debug!("received start-read signal");
            return;
        }

        let Some(rpc_msg) = &payload.data else {
            return;
        };

        let data = &rpc_msg.message_data;
        if data.is_empty() {
            debug!(
                response_id = %rpc_msg.response_id,
                "RPC message has empty messageData"
            );
            return;
        }

        let route = rpc_msg.bugle_route;
        match rpc::BugleRoute::try_from(route) {
            Ok(rpc::BugleRoute::DataEvent) => {
                if let Ok(rpc_data) = rpc::RpcMessageData::decode(data.as_slice()) {
                    let action = rpc_data.action;
                    let encrypted = if rpc_data.encrypted_data.is_empty() {
                        &rpc_data.unencrypted_data
                    } else {
                        &rpc_data.encrypted_data
                    };
                    if !encrypted.is_empty() {
                        if let Some(event) =
                            events::handle_rpc_message(
                                encrypted,
                                action,
                                crypto_keys,
                                seen_hashes,
                            )
                        {
                            if event_tx.try_send(event).is_err() {
                                warn!("event channel full or closed");
                            }
                        }
                    }
                } else {
                    debug!("failed to decode RpcMessageData from messageData");
                }
            }
            Ok(rpc::BugleRoute::PairEvent) => {
                // Distinguish pair confirmation from revocation.
                // After initial pairing, Google replays the PairEvent which
                // contains PairedData — this is NOT an unpair.
                if let Ok(pair_data) =
                    super::proto::events::RpcPairData::decode(data.as_slice())
                {
                    match pair_data.event {
                        Some(super::proto::events::rpc_pair_data::Event::Revoked(
                            _,
                        )) => {
                            warn!("pair revoked by phone or Google");
                            let _ = event_tx.try_send(BridgeEvent::Unpaired);
                        }
                        Some(
                            super::proto::events::rpc_pair_data::Event::Paired(pd),
                        ) => {
                            debug!("pair confirmation received (already paired)");
                            let mobile_device = pd
                                .mobile
                                .map(|d| d.encode_to_vec())
                                .unwrap_or_default();
                            let token = pd
                                .token_data
                                .map(|td| td.tachyon_auth_token)
                                .unwrap_or_default();
                            let _ = event_tx.try_send(BridgeEvent::PairSuccess {
                                mobile_device,
                                tachyon_auth_token: token,
                            });
                        }
                        None => {
                            debug!("pair event with no data, ignoring");
                        }
                    }
                } else {
                    debug!("could not decode RPCPairData, ignoring pair event");
                }
            }
            Ok(other) => {
                debug!(?other, "ignoring non-data bugle route");
            }
            Err(_) => {
                debug!(route, "unknown bugle route");
            }
        }
    }

    /// Background task that periodically checks connection liveness.
    ///
    /// Increments the failure counter on each tick.  The main loop resets the
    /// counter to zero whenever a successful response arrives, so the counter
    /// effectively tracks consecutive missed keepalive windows.
    ///
    /// This task does **not** send actual HTTP pings — it relies on the main
    /// long-poll cycle completing within [`KEEPALIVE_INTERVAL`].  If the phone
    /// stops sending data the failure count will cross
    /// [`PHONE_NOT_RESPONDING_THRESHOLD`] and the main loop will emit the
    /// appropriate event.
    async fn keepalive_pinger(
        consecutive_failures: Arc<AtomicU32>,
        event_tx: mpsc::Sender<BridgeEvent>,
        mut cancel_rx: oneshot::Receiver<()>,
    ) {
        loop {
            tokio::select! {
                () = sleep(KEEPALIVE_INTERVAL) => {
                    let failures = consecutive_failures.fetch_add(1, Ordering::Relaxed) + 1;
                    if failures == PHONE_NOT_RESPONDING_THRESHOLD {
                        debug!(
                            failures,
                            "keepalive threshold reached, phone may be unresponsive"
                        );
                        let _ = event_tx.send(BridgeEvent::PhoneNotResponding).await;
                    }
                }
                _ = &mut cancel_rx => {
                    debug!("keepalive pinger cancelled");
                    return;
                }
            }
        }
    }
}

/// Logs a PBLite payload that could not be decoded as a [`rpc::LongPollingPayload`].
fn trace_payload_decode_failure(payload: &serde_json::Value) {
    let preview: String = payload.to_string().chars().take(120).collect();
    debug!(preview = %preview, "failed to decode PBLite payload as LongPollingPayload");
}
