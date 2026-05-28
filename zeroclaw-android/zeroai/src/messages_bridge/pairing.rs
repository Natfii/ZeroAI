// Copyright (c) 2026 @Natfii. All rights reserved.

//! QR code pairing for the Google Messages Bugle protocol.
//!
//! Implements the `RegisterPhoneRelay` flow: generates an ECDSA P-256 keypair and
//! random AES/HMAC keys, registers a phone relay with Google's servers, and encodes
//! the resulting pairing key into a QR-scannable URL.
//!
//! Ported from mautrix-gmessages:
//! <https://github.com/mautrix/gmessages/blob/main/pkg/libgm/pair.go>

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use p256::ecdsa::SigningKey;
use p256::pkcs8::EncodePublicKey;
use prost::Message;
use rand_core06::{OsRng, RngCore};
use thiserror::Error;

use super::client::{endpoints, BugleHttpClient, BugleHttpError};
use super::crypto::BugleCryptoKeys;
use super::proto::authentication;

/// Base URL for the QR code deep link. The base64-encoded [`authentication::UrlData`]
/// payload is appended directly after the `c=` query parameter.
const QR_URL_BASE: &str = "https://support.google.com/messages/?p=web_computer#?c=";

/// Network identifier required by the Bugle pairing handshake.
const BUGLE_NETWORK: &str = "Bugle";

// ── Error type ──────────────────────────────────────────────────────────────

/// Errors that can occur during the QR code pairing flow.
#[derive(Debug, Error)]
pub enum PairingError {
    /// An HTTP-level error from the Bugle RPC call.
    #[error("HTTP error: {0}")]
    Http(#[from] BugleHttpError),

    /// ECDSA key generation or DER encoding failed.
    #[error("key generation failed: {0}")]
    KeyGeneration(String),

    /// The server response could not be decoded as a protobuf message.
    #[error("protobuf decode error: {0}")]
    ProtoDecode(String),

    /// A required field was absent from the server response.
    #[error("missing required field: {0}")]
    MissingField(&'static str),
}

// ── Pairing session ─────────────────────────────────────────────────────────

/// State produced by a successful [`start_pairing`] call.
///
/// Contains everything needed to complete the pairing handshake once the user
/// scans the QR code on their phone.
pub struct PairingSession {
    /// Full URL to encode as a QR code and display to the user.
    pub qr_url: String,
    /// AES-256 + HMAC-SHA256 keys for the encrypted data channel.
    pub crypto_keys: BugleCryptoKeys,
    /// Initial Tachyon auth token returned by the relay registration.
    pub tachyon_auth_token: Vec<u8>,
    /// Token TTL from the relay registration response.
    pub tachyon_ttl: i64,
    /// Browser device identity assigned by the relay.
    pub browser_device: Vec<u8>,
    /// ECDSA P-256 signing key for this pairing session.
    pub signing_key: SigningKey,
    /// UUID request ID that identifies this pairing attempt.
    pub request_id: String,
    /// Pairing key bytes returned by the relay, embedded in the QR URL.
    pub pairing_key: Vec<u8>,
}

// ── Pairing flow ────────────────────────────────────────────────────────────

/// Initiates a new QR-code pairing session with Google Messages.
///
/// Generates fresh cryptographic material (ECDSA keypair, AES key, HMAC key),
/// registers a phone relay via [`endpoints::register_phone_relay`], and builds
/// the scannable QR URL from the response.
///
/// # Errors
///
/// Returns [`PairingError`] if key generation fails, the HTTP request errors,
/// the response cannot be decoded, or required fields are missing.
pub async fn start_pairing(http: &BugleHttpClient) -> Result<PairingSession, PairingError> {
    // 1. Generate ECDSA P-256 keypair.
    let signing_key = SigningKey::random(&mut OsRng);
    let public_key_der = signing_key
        .verifying_key()
        .to_public_key_der()
        .map_err(|e| PairingError::KeyGeneration(e.to_string()))?;

    // 2. Generate random 32-byte AES and HMAC keys.
    let crypto_keys = {
        let mut aes_key = [0u8; 32];
        let mut hmac_key = [0u8; 32];
        OsRng.fill_bytes(&mut aes_key);
        OsRng.fill_bytes(&mut hmac_key);
        BugleCryptoKeys { aes_key, hmac_key }
    };

    // 3. Build the RegisterPhoneRelay request (AuthenticationContainer).
    let request_id = uuid::Uuid::new_v4().to_string();

    let config_version = authentication::ConfigVersion {
        year: 2025,
        month: 11,
        day: 6,
        v1: 4,
        v2: 6,
    };

    let auth_message = authentication::AuthMessage {
        request_id: request_id.clone(),
        network: BUGLE_NETWORK.to_owned(),
        config_version: Some(config_version),
        tachyon_auth_token: Vec::new(),
    };

    let browser_details = authentication::BrowserDetails {
        user_agent: super::client::BROWSER_USER_AGENT.to_owned(),
        browser_type: authentication::BrowserType::Other.into(),
        os: "libgm".to_owned(),
        device_type: authentication::DeviceType::Tablet.into(),
    };

    let ecdsa_keys = authentication::EcdsaKeys {
        field1: 2,
        encrypted_keys: public_key_der.as_bytes().to_vec(),
    };

    let key_data = authentication::KeyData {
        ecdsa_keys: Some(ecdsa_keys),
        mobile: None,
        web_auth_key_data: None,
        browser: None,
    };

    let request = authentication::AuthenticationContainer {
        auth_message: Some(auth_message),
        browser_details: Some(browser_details),
        data: Some(authentication::authentication_container::Data::KeyData(
            key_data,
        )),
    };

    // 4. POST to RegisterPhoneRelay.
    let url = endpoints::register_phone_relay();
    let response_bytes = http.post_proto(&url, &request).await?;

    // 5. Decode the response.
    let response = authentication::RegisterPhoneRelayResponse::decode(response_bytes.as_slice())
        .map_err(|e| PairingError::ProtoDecode(e.to_string()))?;

    // 6. Extract pairing_key and tachyon_auth_token.
    let pairing_key = if response.pairing_key.is_empty() {
        return Err(PairingError::MissingField("pairing_key"));
    } else {
        response.pairing_key
    };

    let token_data = response
        .auth_key_data
        .ok_or(PairingError::MissingField("auth_key_data"))?;

    let tachyon_auth_token = if token_data.tachyon_auth_token.is_empty() {
        return Err(PairingError::MissingField("tachyon_auth_token"));
    } else {
        token_data.tachyon_auth_token
    };
    let tachyon_ttl = token_data.ttl;

    // Extract browser device from the relay response.
    let browser_device = response
        .browser
        .map(|d| d.encode_to_vec())
        .unwrap_or_default();

    // 7. Build URLData proto with pairing_key + AES key + HMAC key.
    let url_data = authentication::UrlData {
        pairing_key: pairing_key.clone(),
        aes_key: crypto_keys.aes_key.to_vec(),
        hmac_key: crypto_keys.hmac_key.to_vec(),
    };

    // 8. Base64-encode the URLData bytes, prepend QR_URL_BASE.
    let url_data_bytes = url_data.encode_to_vec();
    let encoded = BASE64.encode(&url_data_bytes);
    let qr_url = format!("{QR_URL_BASE}{encoded}");

    // 9. Return PairingSession.
    Ok(PairingSession {
        qr_url,
        crypto_keys,
        tachyon_auth_token,
        tachyon_ttl,
        browser_device,
        signing_key,
        request_id,
        pairing_key,
    })
}
