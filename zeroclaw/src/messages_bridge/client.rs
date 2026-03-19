// Copyright (c) 2026 @Natfii. All rights reserved.

//! HTTP client for the Google Messages Bugle protocol.
//!
//! All Bugle RPC calls are HTTP/1.1 POSTs to the `instantmessaging-pa.googleapis.com`
//! endpoint.  Outgoing payloads are binary protobuf; incoming long-poll streams use
//! the PBLite (JSON array) encoding.
//!
//! Ported from mautrix-gmessages:
//! - <https://github.com/mautrix/gmessages/blob/main/pkg/libgm/util/constants.go>
//! - <https://github.com/mautrix/gmessages/blob/main/pkg/libgm/util/paths.go>
//! - <https://github.com/mautrix/gmessages/blob/main/pkg/libgm/http.go>

use prost::Message;
use reqwest::header::{HeaderMap, HeaderValue, ORIGIN, REFERER, USER_AGENT};
use std::time::Duration;
use thiserror::Error;

// ── Constants ─────────────────────────────────────────────────────────────────

/// Google API key used by the Messages for Web client.
pub const GOOGLE_API_KEY: &str = "AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8";

/// User-Agent string that mimics the Chrome browser on Android.
pub const BROWSER_USER_AGENT: &str =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) \
     Chrome/141.0.0.0 Safari/537.36";

/// Base URL for all Bugle RPC endpoints.
pub const BASE_URL: &str = "https://instantmessaging-pa.googleapis.com";

/// gRPC-Web path prefix shared by every Bugle service method.
pub const RPC_PREFIX: &str =
    "$rpc/google.internal.communications.instantmessaging.v1";

// ── Endpoint helpers ──────────────────────────────────────────────────────────

/// URL helpers for every Bugle RPC endpoint.
pub mod endpoints {
    use super::{BASE_URL, RPC_PREFIX};

    /// `Pairing/RegisterPhoneRelay` — initiates a new QR-code pairing session.
    pub fn register_phone_relay() -> String {
        format!("{BASE_URL}/{RPC_PREFIX}.Pairing/RegisterPhoneRelay")
    }

    /// `Pairing/RefreshPhoneRelay` — refreshes an existing relay pairing.
    pub fn refresh_phone_relay() -> String {
        format!("{BASE_URL}/{RPC_PREFIX}.Pairing/RefreshPhoneRelay")
    }

    /// `Messaging/ReceiveMessages` — opens the long-poll stream for incoming messages.
    pub fn receive_messages() -> String {
        format!("{BASE_URL}/{RPC_PREFIX}.Messaging/ReceiveMessages")
    }

    /// `Messaging/SendMessage` — posts an outgoing message to a conversation.
    pub fn send_message() -> String {
        format!("{BASE_URL}/{RPC_PREFIX}.Messaging/SendMessage")
    }

    /// `Messaging/AckMessages` — acknowledges received messages.
    pub fn ack_messages() -> String {
        format!("{BASE_URL}/{RPC_PREFIX}.Messaging/AckMessages")
    }

    /// `Pairing/RevokeRelayPairing` — terminates an active relay pairing.
    pub fn revoke_relay_pairing() -> String {
        format!("{BASE_URL}/{RPC_PREFIX}.Pairing/RevokeRelayPairing")
    }

    /// `Pairing/GetWebEncryptionKey` — fetches the ECDH public key from the phone.
    pub fn get_web_encryption_key() -> String {
        format!("{BASE_URL}/{RPC_PREFIX}.Pairing/GetWebEncryptionKey")
    }

    /// `Registration/RegisterRefresh` — re-registers the web session.
    pub fn register_refresh() -> String {
        format!("{BASE_URL}/{RPC_PREFIX}.Registration/RegisterRefresh")
    }
}

// ── Header builder ────────────────────────────────────────────────────────────

/// Constructs the default `HeaderMap` required by every Bugle request.
///
/// Includes the Google API key, browser User-Agent, CORS origin/referer, and the
/// gRPC-Web user-agent header expected by the server.
fn bugle_headers() -> HeaderMap {
    let mut headers = HeaderMap::new();

    headers.insert(
        "x-goog-api-key",
        HeaderValue::from_static(GOOGLE_API_KEY),
    );
    headers.insert(USER_AGENT, HeaderValue::from_static(BROWSER_USER_AGENT));
    headers.insert(
        ORIGIN,
        HeaderValue::from_static("https://messages.google.com"),
    );
    headers.insert(
        REFERER,
        HeaderValue::from_static("https://messages.google.com/"),
    );
    headers.insert(
        "x-user-agent",
        HeaderValue::from_static("grpc-web-javascript/0.1"),
    );
    headers.insert(
        "sec-ch-ua",
        HeaderValue::from_static(
            r#""Google Chrome";v="141", "Chromium";v="141", "Not-A.Brand";v="24""#,
        ),
    );
    headers.insert("sec-ch-ua-mobile", HeaderValue::from_static("?1"));
    headers.insert(
        "sec-ch-ua-platform",
        HeaderValue::from_static("\"Android\""),
    );
    headers.insert("sec-fetch-site", HeaderValue::from_static("cross-site"));
    headers.insert("sec-fetch-mode", HeaderValue::from_static("cors"));
    headers.insert("sec-fetch-dest", HeaderValue::from_static("empty"));
    headers.insert(
        reqwest::header::ACCEPT,
        HeaderValue::from_static("*/*"),
    );
    headers.insert(
        reqwest::header::ACCEPT_LANGUAGE,
        HeaderValue::from_static("en-US,en;q=0.9"),
    );

    headers
}

// ── Error type ────────────────────────────────────────────────────────────────

/// Errors that can occur when communicating with the Bugle HTTP endpoints.
#[derive(Debug, Error)]
pub enum BugleHttpError {
    /// A transport-level or connection error from [`reqwest`].
    #[error("request error: {0}")]
    Request(#[from] reqwest::Error),

    /// The server returned a non-success HTTP status code.
    #[error("server returned HTTP {status}: {body}")]
    ServerError {
        /// The raw HTTP status code.
        status: u16,
        /// The response body text, if available.
        body: String,
    },
}

// ── HTTP client ───────────────────────────────────────────────────────────────

/// HTTP client pre-configured for the Google Messages Bugle protocol.
///
/// Two constructors are provided: [`BugleHttpClient::new`] for ordinary request/response
/// RPCs (120 s timeout) and [`BugleHttpClient::new_long_poll`] for the streaming
/// `ReceiveMessages` endpoint (1 800 s timeout).
pub struct BugleHttpClient {
    client: reqwest::Client,
}

impl BugleHttpClient {
    /// Creates a client with a **120-second** timeout, suitable for standard RPCs.
    ///
    /// # Errors
    ///
    /// Returns [`reqwest::Error`] if the underlying TLS backend fails to initialise.
    pub fn new() -> Result<Self, reqwest::Error> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(120))
            .default_headers(bugle_headers())
            .cookie_store(true)
            .build()?;
        Ok(Self { client })
    }

    /// Creates a client with an **1 800-second** (30-minute) timeout for long-poll streams.
    ///
    /// The extended timeout prevents the connection from being dropped while waiting for
    /// the next batch of messages on the `ReceiveMessages` stream.
    ///
    /// # Errors
    ///
    /// Returns [`reqwest::Error`] if the underlying TLS backend fails to initialise.
    pub fn new_long_poll() -> Result<Self, reqwest::Error> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(1800))
            .default_headers(bugle_headers())
            .build()?;
        Ok(Self { client })
    }

    /// POSTs a protobuf-encoded message and returns the raw response bytes.
    ///
    /// The request body is the binary-encoded protobuf wire format.  The caller is
    /// responsible for decoding the returned bytes with the appropriate `prost` type.
    ///
    /// # Errors
    ///
    /// Returns [`BugleHttpError::Request`] on transport failure or
    /// [`BugleHttpError::ServerError`] when the server responds with a non-2xx status.
    pub async fn post_proto(
        &self,
        url: &str,
        message: &impl Message,
    ) -> Result<Vec<u8>, BugleHttpError> {
        let body = message.encode_to_vec();

        let response = self
            .client
            .post(url)
            .header(reqwest::header::CONTENT_TYPE, "application/x-protobuf")
            .body(body)
            .send()
            .await?;

        let status = response.status();
        if !status.is_success() {
            let code = status.as_u16();
            let body_text = response.text().await.unwrap_or_default();
            return Err(BugleHttpError::ServerError {
                status: code,
                body: body_text,
            });
        }

        Ok(response.bytes().await?.to_vec())
    }

    /// POSTs a protobuf-encoded message and returns the **streaming** [`reqwest::Response`].
    ///
    /// Used exclusively with [`endpoints::receive_messages`] to open the PBLite
    /// long-poll stream.  The caller must read and decode chunks from the response body
    /// using [`crate::messages_bridge::pblite`].
    ///
    /// # Errors
    ///
    /// Returns [`BugleHttpError::Request`] on transport failure or
    /// [`BugleHttpError::ServerError`] when the server responds with a non-2xx status.
    pub async fn start_long_poll(
        &self,
        url: &str,
        message: &impl Message,
    ) -> Result<reqwest::Response, BugleHttpError> {
        let body = message.encode_to_vec();

        let response = self
            .client
            .post(url)
            .header(reqwest::header::CONTENT_TYPE, "application/x-protobuf")
            .body(body)
            .send()
            .await?;

        let status = response.status();
        if !status.is_success() {
            let code = status.as_u16();
            let body_text = response.text().await.unwrap_or_default();
            return Err(BugleHttpError::ServerError {
                status: code,
                body: body_text,
            });
        }

        Ok(response)
    }

    /// POSTs a PBLite-encoded body and returns the **streaming** [`reqwest::Response`].
    ///
    /// Used with [`endpoints::receive_messages`] for the long-poll stream. Google's
    /// `ReceiveMessages` endpoint requires PBLite encoding (`application/json+protobuf`),
    /// not binary protobuf. The caller must pre-encode the request body via
    /// [`crate::messages_bridge::pblite::encode_receive_messages_request`].
    ///
    /// # Errors
    ///
    /// Returns [`BugleHttpError::Request`] on transport failure or
    /// [`BugleHttpError::ServerError`] when the server responds with a non-2xx status.
    pub async fn start_long_poll_pblite(
        &self,
        url: &str,
        pblite_body: &str,
    ) -> Result<reqwest::Response, BugleHttpError> {
        let response = self
            .client
            .post(url)
            .header(
                reqwest::header::CONTENT_TYPE,
                "application/json+protobuf",
            )
            .body(pblite_body.to_owned())
            .send()
            .await?;

        let status = response.status();
        if !status.is_success() {
            let code = status.as_u16();
            let body_text = response.text().await.unwrap_or_default();
            return Err(BugleHttpError::ServerError {
                status: code,
                body: body_text,
            });
        }

        Ok(response)
    }

    /// POSTs a PBLite-encoded body and returns the raw response bytes.
    ///
    /// Used for authenticated Bugle RPC endpoints (e.g. `SendMessage`) that
    /// require PBLite encoding (`application/json+protobuf`).
    ///
    /// # Errors
    ///
    /// Returns [`BugleHttpError::Request`] on transport failure or
    /// [`BugleHttpError::ServerError`] when the server responds with a non-2xx status.
    pub async fn post_pblite(
        &self,
        url: &str,
        pblite_body: &str,
    ) -> Result<Vec<u8>, BugleHttpError> {
        let response = self
            .client
            .post(url)
            .header(
                reqwest::header::CONTENT_TYPE,
                "application/json+protobuf",
            )
            .body(pblite_body.to_owned())
            .send()
            .await?;

        let status = response.status();
        if !status.is_success() {
            let code = status.as_u16();
            let body_text = response.text().await.unwrap_or_default();
            return Err(BugleHttpError::ServerError {
                status: code,
                body: body_text,
            });
        }

        Ok(response.bytes().await?.to_vec())
    }

    /// Returns a reference to the underlying [`reqwest::Client`].
    ///
    /// Useful when callers need to set per-request headers or parameters that are not
    /// covered by the default Bugle configuration.
    pub fn inner(&self) -> &reqwest::Client {
        &self.client
    }
}
