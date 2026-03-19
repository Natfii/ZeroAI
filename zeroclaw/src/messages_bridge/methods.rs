// Copyright (c) 2026 @Natfii. All rights reserved.

//! High-level API methods for the Google Messages Bugle protocol.
//!
//! Each function builds the appropriate protobuf request, wraps it in an
//! [`OutgoingRpcMessage`], encrypts the payload, PBLite-encodes the envelope,
//! and POSTs it to the Bugle `SendMessage` endpoint. The response arrives
//! asynchronously on the long-poll stream.
//!
//! Ported from mautrix-gmessages:
//! <https://github.com/mautrix/gmessages/blob/main/pkg/libgm/methods.go>

use std::time::{SystemTime, UNIX_EPOCH};

use p256::ecdsa::{signature::Signer, SigningKey};
use prost::Message;

use super::client::{endpoints, BugleHttpClient, BugleHttpError};
use super::crypto::{self, BugleCryptoKeys};
use super::longpoll::DevicePair;
use super::pblite;
use super::proto::{authentication, client, rpc, util};

/// Sends a `GET_UPDATES` action to mark the session as active.
///
/// This tells the phone that we're actively listening for data. Without
/// this call, the phone won't push conversations or messages on the
/// long-poll stream. Matches upstream `SetActiveSession()`.
///
/// # Errors
///
/// Returns [`BugleHttpError`] on transport failure or non-2xx server
/// response.
/// Returns the generated session ID on success.
pub async fn set_active_session(
    http: &BugleHttpClient,
    crypto_keys: &BugleCryptoKeys,
    auth_token: &[u8],
    device_pair: &DevicePair,
) -> Result<String, BugleHttpError> {
    let session_id = uuid::Uuid::new_v4().to_string();

    // GET_UPDATES with requestID = sessionID (upstream pattern).
    send_rpc_action_with_session(
        http,
        crypto_keys,
        auth_token,
        0, // OmitTTL
        device_pair,
        rpc::ActionType::GetUpdates,
        rpc::MessageType::BugleMessage,
        &util::EmptyArr {}, // no data
        &session_id,
        Some(&session_id), // requestID = sessionID
    )
    .await?;

    Ok(session_id)
}

/// Sends a `ListConversationsRequest` to the paired phone.
///
/// Uses [`rpc::MessageType::BugleAnnotation`] for the initial fetch
/// (matching upstream mautrix-gmessages behaviour) and includes the
/// Tachyon TTL and browser device registration ID.
///
/// The response arrives asynchronously on the long-poll stream as a
/// `DataEvent` with [`rpc::ActionType::ListConversations`].
///
/// # Errors
///
/// Returns [`BugleHttpError`] on transport failure or non-2xx server
/// response.
pub async fn list_conversations(
    http: &BugleHttpClient,
    crypto_keys: &BugleCryptoKeys,
    auth_token: &[u8],
    ttl: i64,
    device_pair: &DevicePair,
    session_id: &str,
) -> Result<(), BugleHttpError> {
    let inner_request = client::ListConversationsRequest {
        count: 100,
        folder: client::list_conversations_request::Folder::Inbox.into(),
        cursor: None,
    };

    send_rpc_action_with_session(
        http,
        crypto_keys,
        auth_token,
        ttl,
        device_pair,
        rpc::ActionType::ListConversations,
        rpc::MessageType::BugleAnnotation,
        &inner_request,
        session_id,
        None,
    )
    .await
}

/// Refreshes the Tachyon auth token via the `RegisterRefresh` endpoint.
///
/// After QR pairing, the relay token only authorises `ReceiveMessages`.
/// This function sends a signed `RegisterRefreshRequest` to obtain a fresh
/// token that is valid for `SendMessage` RPCs (e.g. `ListConversations`).
///
/// The request is binary protobuf (`application/x-protobuf`), matching the
/// upstream mautrix-gmessages `refreshAuthToken()` behaviour.
///
/// # Arguments
///
/// * `http` — The cookie-enabled HTTP client from the pairing session.
/// * `auth_token` — The current Tachyon auth token from pairing.
/// * `browser_device_bytes` — The serialised browser [`authentication::Device`].
/// * `signing_key_bytes` — Raw ECDSA P-256 signing key bytes (32 bytes).
///
/// # Returns
///
/// A tuple of `(new_tachyon_auth_token, ttl)` on success.
///
/// # Errors
///
/// Returns [`BugleHttpError`] on transport failure, non-2xx response, or
/// if the response cannot be decoded.
pub async fn refresh_auth_token(
    http: &BugleHttpClient,
    auth_token: &[u8],
    browser_device_bytes: &[u8],
    signing_key_bytes: &[u8],
) -> Result<(Vec<u8>, i64), BugleHttpError> {
    let request_id = uuid::Uuid::new_v4().to_string();

    // Timestamp in microseconds (Unix millis * 1000).
    let now_micros = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_micros() as i64;

    // Reconstruct the ECDSA signing key from raw bytes.
    let signing_key = SigningKey::from_slice(signing_key_bytes).map_err(|e| {
        BugleHttpError::ServerError {
            status: 0,
            body: format!("invalid signing key: {e}"),
        }
    })?;

    // Sign "{requestID}:{timestamp}" with ECDSA-SHA256.
    let sign_payload = format!("{request_id}:{now_micros}");
    let signature: p256::ecdsa::Signature = signing_key.sign(sign_payload.as_bytes());
    let signature_bytes = signature.to_der().as_bytes().to_vec();

    // Decode the browser Device from serialised bytes.
    let browser_device =
        authentication::Device::decode(browser_device_bytes).unwrap_or_default();

    let config_version = authentication::ConfigVersion {
        year: 2025,
        month: 11,
        day: 6,
        v1: 4,
        v2: 6,
    };

    let auth_message = authentication::AuthMessage {
        request_id: request_id.clone(),
        tachyon_auth_token: auth_token.to_vec(),
        network: "Bugle".to_owned(),
        config_version: Some(config_version),
    };

    let request = authentication::RegisterRefreshRequest {
        message_auth: Some(auth_message),
        curr_browser_device: Some(browser_device),
        unix_timestamp: now_micros,
        signature: signature_bytes,
        parameters: None,
        message_type: 2,
    };

    // POST PBLite to RegisterRefresh (upstream uses ContentTypePBLite).
    let url = endpoints::register_refresh();
    let pblite_body = pblite::encode_register_refresh_request(&request);
    let response_bytes = http.post_pblite(&url, &pblite_body).await?;

    // Decode the binary protobuf response.
    let response =
        authentication::RegisterRefreshResponse::decode(response_bytes.as_slice())
            .map_err(|e| BugleHttpError::ServerError {
                status: 0,
                body: format!("failed to decode RegisterRefreshResponse: {e}"),
            })?;

    let token_data =
        response
            .token_data
            .ok_or_else(|| BugleHttpError::ServerError {
                status: 0,
                body: "RegisterRefreshResponse missing tokenData".to_owned(),
            })?;

    Ok((token_data.tachyon_auth_token, token_data.ttl))
}

/// Builds and sends an outgoing RPC action to the paired phone.
///
/// Encrypts `inner_request` with the session crypto keys, wraps it in an
/// [`rpc::OutgoingRpcData`] → [`rpc::OutgoingRpcMessage`] envelope,
/// PBLite-encodes the envelope, and POSTs it to the `SendMessage` endpoint
/// using `application/json+protobuf` content type.
async fn send_rpc_action_with_session(
    http: &BugleHttpClient,
    crypto_keys: &BugleCryptoKeys,
    auth_token: &[u8],
    ttl: i64,
    device_pair: &DevicePair,
    action: rpc::ActionType,
    message_type: rpc::MessageType,
    inner_request: &impl Message,
    session_id: &str,
    override_request_id: Option<&str>,
) -> Result<(), BugleHttpError> {
    let request_id = override_request_id
        .map(String::from)
        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());

    // 1. Encode and optionally encrypt the inner request.
    //    When the inner proto serialises to empty bytes (e.g. GET_UPDATES
    //    with no data), skip encryption — the upstream sends both
    //    unencryptedProtoData and encryptedProtoData as empty in that case.
    let inner_bytes = inner_request.encode_to_vec();
    let encrypted = if inner_bytes.is_empty() {
        Vec::new()
    } else {
        crypto::encrypt(crypto_keys, &inner_bytes)
    };

    // 2. Build OutgoingRPCData with the encrypted payload.
    let rpc_data = rpc::OutgoingRpcData {
        request_id: request_id.clone(),
        action: action.into(),
        unencrypted_proto_data: Vec::new(),
        encrypted_proto_data: encrypted,
        session_id: session_id.to_owned(),
    };
    let rpc_data_bytes = rpc_data.encode_to_vec();

    // 3. Decode mobile device from serialised bytes.
    let mobile = authentication::Device::decode(device_pair.mobile.as_slice())
        .unwrap_or_default();

    // 4. DestRegistrationIDs left empty for relay-paired sessions.
    //    The upstream populates this with AuthData.DestRegID (a UUID),
    //    but for QR-paired sessions without Google login the field is
    //    not required and sending the browser sourceID causes a 400
    //    (server expects TYPE_BYTES, not a raw string).

    // 5. Build the outer OutgoingRPCMessage envelope.
    let config_version = authentication::ConfigVersion {
        year: 2025,
        month: 11,
        day: 6,
        v1: 4,
        v2: 6,
    };

    let message = rpc::OutgoingRpcMessage {
        mobile: Some(mobile),
        data: Some(rpc::outgoing_rpc_message::Data {
            request_id: request_id.clone(),
            bugle_route: rpc::BugleRoute::DataEvent.into(),
            message_data: rpc_data_bytes,
            message_type_data: Some(rpc::outgoing_rpc_message::data::Type {
                empty_arr: Some(util::EmptyArr {}),
                message_type: message_type.into(),
            }),
        }),
        auth: Some(rpc::outgoing_rpc_message::Auth {
            request_id,
            tachyon_auth_token: auth_token.to_vec(),
            config_version: Some(config_version),
        }),
        ttl,
        dest_registration_i_ds: Vec::new(),
    };

    // 6. PBLite-encode and POST to the SendMessage endpoint.
    let pblite_body = pblite::encode_outgoing_rpc_message(&message);

    tracing::warn!(
        target: "messages_bridge::methods",
        body_len = pblite_body.len(),
        body_preview = %pblite_body.chars().take(500).collect::<String>(),
        "SendMessage PBLite request"
    );

    let url = endpoints::send_message();
    let _ = http.post_pblite(&url, &pblite_body).await?;

    Ok(())
}
