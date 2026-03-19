// Copyright (c) 2026 @Natfii. All rights reserved.

//! PBLite codec for Google's JSON+protobuf array format.
//!
//! Google Messages for Web uses PBLite encoding where protobuf messages are
//! represented as positional JSON arrays. Array index N corresponds to protobuf
//! field number N+1. Nested messages are nested arrays. Bytes fields are
//! standard base64-encoded strings.
//!
//! The long-poll stream format wraps multiple payloads as `[[ p1, p2, ... ]]`.
//!
//! # Encoding
//!
//! [`encode_receive_messages_request`] converts a [`ReceiveMessagesRequest`]
//! into the PBLite JSON string required by the `ReceiveMessages` long-poll
//! endpoint (Content-Type `application/json+protobuf`).
//!
//! # Streaming
//!
//! [`read_pblite_stream`] reads a long-poll response incrementally, calling
//! a callback for each payload as it arrives rather than waiting for the
//! entire response to complete.

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use serde_json::Value;

/// Errors that can occur during PBLite decoding.
#[derive(Debug, thiserror::Error)]
pub enum PbLiteError {
    /// The JSON value was not an array as expected by PBLite.
    #[error("expected JSON array, got: {0}")]
    NotAnArray(String),

    /// Protobuf decode failed after converting the JSON array to wire format.
    #[error("protobuf decode failed: {0}")]
    ProtoDecode(#[from] prost::DecodeError),

    /// JSON parsing of the stream data failed.
    #[error("JSON parse failed: {0}")]
    JsonParse(#[from] serde_json::Error),
}

/// Decodes a PBLite JSON array into a prost [`Message`].
///
/// The input must be a [`serde_json::Value::Array`] whose positional elements
/// map to protobuf field numbers (index 0 → field 1, index 1 → field 2, …).
///
/// # Errors
///
/// Returns [`PbLiteError::NotAnArray`] if `json` is not a JSON array.
/// Returns [`PbLiteError::ProtoDecode`] if the synthesized wire bytes cannot
/// be decoded into `T`.
pub fn decode<T: prost::Message + Default>(json: &Value) -> Result<T, PbLiteError> {
    let arr = match json {
        Value::Array(a) => a.as_slice(),
        other => {
            return Err(PbLiteError::NotAnArray(
                other.to_string().chars().take(80).collect::<String>(),
            ));
        }
    };

    let wire_bytes = json_array_to_wire(arr, 0)?;
    let msg = T::decode(wire_bytes.as_slice())?;
    Ok(msg)
}

/// Parses the outer `[[ ... ]]` wrapper from a Google Messages long-poll stream.
///
/// The stream data is a JSON string whose top-level value is an array of
/// PBLite payload arrays. This function extracts the inner payloads as
/// individual [`serde_json::Value`] items suitable for passing to [`decode`].
///
/// # Errors
///
/// Returns [`PbLiteError::JsonParse`] if `stream_data` is not valid JSON.
/// Returns [`PbLiteError::NotAnArray`] if the outer or inner wrapper is not
/// a JSON array.
pub fn split_stream(stream_data: &str) -> Result<Vec<Value>, PbLiteError> {
    let outer: Value = serde_json::from_str(stream_data)?;

    let outer_arr = match &outer {
        Value::Array(a) => a,
        other => {
            return Err(PbLiteError::NotAnArray(format!(
                "outer wrapper: {}",
                other.to_string().chars().take(80).collect::<String>(),
            )));
        }
    };

    // The format is `[[ payload1, payload2, ... ]]` — a single-element outer
    // array whose only element is the inner array of payloads.
    if outer_arr.len() == 1 {
        if let Value::Array(inner) = &outer_arr[0] {
            return Ok(inner.clone());
        }
    }

    // Fallback: treat every element of the outer array as a payload.
    Ok(outer_arr.clone())
}

/// Converts a PBLite positional array into protobuf wire-format bytes.
///
/// Each array element at index `i` maps to protobuf field number `i + 1`.
/// The `depth` parameter is used for recursive nested-message encoding but
/// does not alter the output semantics.
fn json_array_to_wire(arr: &[Value], depth: usize) -> Result<Vec<u8>, PbLiteError> {
    let mut buf: Vec<u8> = Vec::new();

    for (idx, val) in arr.iter().enumerate() {
        let field_number = (idx + 1) as u64;
        encode_field(&mut buf, field_number, val, depth)?;
    }

    Ok(buf)
}

/// Encodes a single protobuf field from a JSON value.
///
/// The wire type is inferred from the JSON value kind:
/// - [`Value::Null`] and [`Value::Object`] are silently skipped.
/// - [`Value::Bool`] → varint (wire type 0).
/// - [`Value::Number`] → varint for integers, 64-bit double for floats.
/// - [`Value::String`] → length-delimited (wire type 2).
/// - [`Value::Array`] → length-delimited nested message (wire type 2).
fn encode_field(
    buf: &mut Vec<u8>,
    field_number: u64,
    val: &Value,
    depth: usize,
) -> Result<(), PbLiteError> {
    match val {
        Value::Null | Value::Object(_) => {
            // Not present in this message; skip.
        }

        Value::Bool(b) => {
            encode_varint_field(buf, field_number, if *b { 1 } else { 0 });
        }

        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                // Encode as varint (wire type 0). Negative integers use the
                // raw two's-complement u64 representation (standard protobuf).
                encode_varint_field(buf, field_number, i.cast_unsigned());
            } else if let Some(u) = n.as_u64() {
                encode_varint_field(buf, field_number, u);
            } else if let Some(f) = n.as_f64() {
                // Encode as 64-bit double (wire type 1).
                let tag = (field_number << 3) | 1;
                encode_varint(buf, tag);
                buf.extend_from_slice(&f.to_le_bytes());
            }
        }

        Value::String(s) => {
            // PBLite has three kinds of string values:
            //
            // 1. Large integers encoded as strings to avoid JS precision
            //    loss (e.g. "1773787130774564" for a uint64 field).
            //    → emit as varint (wire type 0).
            //
            // 2. Base64-encoded binary data for protobuf `bytes` fields
            //    (e.g. messageData, tachyonAuthToken).
            //    → base64-decode, emit as length-delimited raw bytes.
            //
            // 3. Genuine strings (e.g. requestID, network).
            //    → emit as length-delimited UTF-8 bytes.
            if let Ok(n) = s.parse::<u64>() {
                encode_varint_field(buf, field_number, n);
            } else if let Ok(n) = s.parse::<i64>() {
                encode_varint_field(buf, field_number, n.cast_unsigned());
            } else if let Ok(decoded) = BASE64.decode(s.as_bytes()) {
                // Looks like base64-encoded bytes.
                let tag = (field_number << 3) | 2;
                encode_varint(buf, tag);
                encode_varint(buf, decoded.len() as u64);
                buf.extend_from_slice(&decoded);
            } else {
                // Genuine string — length-delimited (wire type 2).
                let tag = (field_number << 3) | 2;
                encode_varint(buf, tag);
                let bytes = s.as_bytes();
                encode_varint(buf, bytes.len() as u64);
                buf.extend_from_slice(bytes);
            }
        }

        Value::Array(nested) => {
            // Treat as a nested message (length-delimited, wire type 2).
            let nested_bytes = json_array_to_wire(nested.as_slice(), depth + 1)?;
            let tag = (field_number << 3) | 2;
            encode_varint(buf, tag);
            encode_varint(buf, nested_bytes.len() as u64);
            buf.extend_from_slice(&nested_bytes);
        }
    }

    Ok(())
}

/// Writes a varint-encoded field tag and value for wire type 0 (varint).
fn encode_varint_field(buf: &mut Vec<u8>, field_number: u64, value: u64) {
    let tag = field_number << 3; // wire type 0
    encode_varint(buf, tag);
    encode_varint(buf, value);
}

/// Encodes a single unsigned 64-bit integer as a protobuf varint.
///
/// Each byte carries 7 data bits; the MSB is set on all bytes except the last.
fn encode_varint(buf: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 {
            buf.push(byte);
            break;
        }
        buf.push(byte | 0x80);
    }
}

// ── PBLite stream reader ────────────────────────────────────────────────

/// Reads a PBLite long-poll response stream incrementally.
///
/// The `ReceiveMessages` endpoint returns a streaming response in the format
/// `[[ payload1, payload2, ... ]]` where each payload is a comma-separated
/// PBLite JSON array. This function reads data as it arrives and calls
/// `on_payload` for each complete payload, rather than waiting for the
/// entire response to finish (which can take 30+ minutes).
///
/// `on_payload` should return `true` to continue reading or `false` to stop.
///
/// Returns `Ok(true)` if events were received, `Ok(false)` if the stream
/// closed without data payloads, or `Err` on I/O failure.
pub async fn read_pblite_stream(
    response: reqwest::Response,
    mut on_payload: impl FnMut(Value) -> bool,
) -> Result<bool, String> {
    use futures_util::StreamExt as _;

    let mut buf = Vec::<u8>::new();
    let mut started = false;
    let mut received_events = false;
    let mut stream = response.bytes_stream();
    let mut chunk_count = 0u32;

    tracing::warn!(
        target: "messages_bridge::pblite",
        "stream reader started"
    );

    while let Some(result) = stream.next().await {
        let chunk = result.map_err(|e| format!("stream read error: {e}"))?;
        chunk_count += 1;
        buf.extend_from_slice(&chunk);

        if chunk_count <= 3 {
            let preview: String = String::from_utf8_lossy(&buf)
                .chars()
                .take(200)
                .collect();
            tracing::warn!(
                target: "messages_bridge::pblite",
                chunk_count,
                buf_len = buf.len(),
                %preview,
                "stream chunk received"
            );
        }

        if !started {
            if let Some(pos) = buf.windows(2).position(|w| w == b"[[") {
                buf = buf[pos + 2..].to_vec();
                started = true;
                tracing::warn!(
                    target: "messages_bridge::pblite",
                    "found [[ opener, streaming started"
                );
            } else {
                continue;
            }
        }

        // Extract complete JSON arrays from the buffer.
        loop {
            // Skip whitespace and comma separators.
            let start = buf
                .iter()
                .position(|&b| !matches!(b, b',' | b' ' | b'\n' | b'\r' | b'\t'));
            if let Some(start) = start {
                if start > 0 {
                    buf = buf[start..].to_vec();
                }
            } else {
                buf.clear();
                break;
            }

            // Check for the stream end marker `]]`.
            if buf.starts_with(b"]]") {
                tracing::warn!(
                    target: "messages_bridge::pblite",
                    chunk_count,
                    "stream ended with ]]"
                );
                return Ok(received_events);
            }

            // Find the end of the next JSON array via bracket tracking.
            if let Some(end) = find_json_array_end(&buf) {
                let json_str = String::from_utf8_lossy(&buf[..end]);
                tracing::warn!(
                    target: "messages_bridge::pblite",
                    payload_len = end,
                    "found complete JSON payload"
                );
                match serde_json::from_str::<Value>(&json_str) {
                    Ok(val) => {
                        received_events = true;
                        buf = buf[end..].to_vec();
                        if !on_payload(val) {
                            return Ok(true);
                        }
                    }
                    Err(e) => {
                        tracing::warn!(
                            target: "messages_bridge::pblite",
                            error = %e,
                            "JSON parse failed despite bracket match"
                        );
                        break;
                    }
                }
            } else {
                break; // need more data
            }
        }
    }

    tracing::warn!(
        target: "messages_bridge::pblite",
        chunk_count,
        buf_remaining = buf.len(),
        "stream ended (connection closed)"
    );

    Ok(received_events)
}

/// Finds the byte position after the closing `]` of a top-level JSON array.
///
/// Tracks bracket depth and handles string literals (including escaped
/// characters) so that brackets inside strings are not counted.
///
/// Returns `None` if the array is incomplete.
fn find_json_array_end(data: &[u8]) -> Option<usize> {
    let mut depth = 0i32;
    let mut in_string = false;
    let mut escape_next = false;

    for (i, &b) in data.iter().enumerate() {
        if escape_next {
            escape_next = false;
            continue;
        }
        if in_string {
            match b {
                b'\\' => escape_next = true,
                b'"' => in_string = false,
                _ => {}
            }
            continue;
        }
        match b {
            b'"' => in_string = true,
            b'[' => depth += 1,
            b']' => {
                depth -= 1;
                if depth == 0 {
                    return Some(i + 1);
                }
            }
            _ => {}
        }
    }
    None
}

// ── PBLite encoder ──────────────────────────────────────────────────────

/// Encodes a [`ReceiveMessagesRequest`] as a PBLite JSON string.
///
/// The `ReceiveMessages` long-poll endpoint requires the request body in
/// PBLite format (`application/json+protobuf`), not binary protobuf.
/// This function converts the prost-generated struct into the positional
/// JSON array expected by Google's server.
///
/// Bytes fields (e.g. `tachyon_auth_token`) are standard-base64 encoded.
pub fn encode_receive_messages_request(
    request: &super::proto::client::ReceiveMessagesRequest,
) -> String {
    use serde_json::json;

    let auth_val = request
        .auth
        .as_ref()
        .map(|auth| {
            let config_val = auth
                .config_version
                .as_ref()
                .map(|cv| {
                    // ConfigVersion: Year=3, Month=4, Day=5, V1=7, V2=9
                    json!([
                        Value::Null,
                        Value::Null,
                        cv.year,
                        cv.month,
                        cv.day,
                        Value::Null,
                        cv.v1,
                        Value::Null,
                        cv.v2,
                    ])
                })
                .unwrap_or(Value::Null);

            let token_val = if auth.tachyon_auth_token.is_empty() {
                Value::Null
            } else {
                Value::String(BASE64.encode(&auth.tachyon_auth_token))
            };

            // AuthMessage: requestID=1, network=3, tachyonAuthToken=6,
            //              configVersion=7
            json!([
                auth.request_id,
                Value::Null,
                auth.network,
                Value::Null,
                Value::Null,
                token_val,
                config_val,
            ])
        })
        .unwrap_or(Value::Null);

    // UnknownEmptyObject2 (field 4): contains UnknownEmptyObject1 (field 2)
    let unknown_val = request
        .unknown
        .as_ref()
        .map(|_| json!([Value::Null, []]))
        .unwrap_or(Value::Null);

    // ReceiveMessagesRequest: auth=1, unknown=4
    let request_val = json!([auth_val, Value::Null, Value::Null, unknown_val,]);

    request_val.to_string()
}

/// Encodes an [`OutgoingRpcMessage`] as a PBLite JSON string.
///
/// The `SendMessage` endpoint requires PBLite encoding
/// (`application/json+protobuf`), not binary protobuf.
pub fn encode_outgoing_rpc_message(
    msg: &super::proto::rpc::OutgoingRpcMessage,
) -> String {
    use serde_json::{json, Value};

    /// Builds a PBLite array of `size` nulls, then sets entries via `setters`.
    fn make_arr(size: usize, setters: &[(usize, Value)]) -> Value {
        let mut arr = vec![Value::Null; size];
        for (idx, val) in setters {
            arr[*idx] = val.clone();
        }
        Value::Array(arr)
    }

    fn encode_config_version(
        cv: &super::proto::authentication::ConfigVersion,
    ) -> Value {
        // ConfigVersion: Year=3, Month=4, Day=5, V1=7, V2=9
        make_arr(9, &[
            (2, json!(cv.year)),
            (3, json!(cv.month)),
            (4, json!(cv.day)),
            (6, json!(cv.v1)),
            (8, json!(cv.v2)),
        ])
    }

    fn encode_device(dev: &super::proto::authentication::Device) -> Value {
        // Device: userID=1, sourceID=2, network=3
        let mut entries = Vec::new();
        if dev.user_id != 0 {
            entries.push((0, json!(dev.user_id.to_string())));
        }
        if !dev.source_id.is_empty() {
            entries.push((1, json!(dev.source_id)));
        }
        if !dev.network.is_empty() {
            entries.push((2, json!(dev.network)));
        }
        make_arr(3, &entries)
    }

    // Auth (field 3): requestID=1, tachyonAuthToken=6, configVersion=7
    let auth_val = msg
        .auth
        .as_ref()
        .map(|auth| {
            let mut entries = vec![(0, json!(auth.request_id))];

            if !auth.tachyon_auth_token.is_empty() {
                entries.push((
                    5,
                    Value::String(BASE64.encode(&auth.tachyon_auth_token)),
                ));
            }
            if let Some(cv) = &auth.config_version {
                entries.push((6, encode_config_version(cv)));
            }
            make_arr(7, &entries)
        })
        .unwrap_or(Value::Null);

    // Data.Type (messageTypeData): emptyArr=1, messageType=2
    let type_val = msg
        .data
        .as_ref()
        .and_then(|d| d.message_type_data.as_ref())
        .map(|t| {
            make_arr(2, &[
                (0, json!([])),
                (1, json!(t.message_type)),
            ])
        })
        .unwrap_or(Value::Null);

    // Data (field 2): requestID=1, bugleRoute=2, messageData=12,
    //                  messageTypeData=23
    let data_val = msg
        .data
        .as_ref()
        .map(|d| {
            let mut entries = vec![
                (0, json!(d.request_id)),
                (1, json!(d.bugle_route)),
            ];
            if !d.message_data.is_empty() {
                entries.push((
                    11,
                    Value::String(BASE64.encode(&d.message_data)),
                ));
            }
            entries.push((22, type_val.clone()));
            make_arr(23, &entries)
        })
        .unwrap_or(Value::Null);

    // Mobile device (field 1)
    let mobile_val = msg
        .mobile
        .as_ref()
        .map(encode_device)
        .unwrap_or(Value::Null);

    // OutgoingRPCMessage: mobile=1, data=2, auth=3, TTL=5,
    //                     destRegistrationIDs=9
    let mut entries = vec![
        (0, mobile_val),
        (1, data_val),
        (2, auth_val),
    ];
    if msg.ttl != 0 {
        entries.push((4, json!(msg.ttl.to_string())));
    }
    if !msg.dest_registration_i_ds.is_empty() {
        // Repeated string field — PBLite encodes as a JSON array of strings.
        entries.push((8, json!(msg.dest_registration_i_ds)));
    }

    make_arr(9, &entries).to_string()
}

/// Encodes a [`RegisterRefreshRequest`] as a PBLite JSON string.
///
/// The `RegisterRefresh` endpoint requires PBLite encoding, matching
/// the upstream mautrix-gmessages `refreshAuthToken()` behaviour.
pub fn encode_register_refresh_request(
    msg: &super::proto::authentication::RegisterRefreshRequest,
) -> String {
    use serde_json::{json, Value};

    fn make_arr(size: usize, setters: &[(usize, Value)]) -> Value {
        let mut arr = vec![Value::Null; size];
        for (idx, val) in setters {
            arr[*idx] = val.clone();
        }
        Value::Array(arr)
    }

    fn encode_config_version(
        cv: &super::proto::authentication::ConfigVersion,
    ) -> Value {
        make_arr(9, &[
            (2, json!(cv.year)),
            (3, json!(cv.month)),
            (4, json!(cv.day)),
            (6, json!(cv.v1)),
            (8, json!(cv.v2)),
        ])
    }

    fn encode_device(dev: &super::proto::authentication::Device) -> Value {
        let mut entries = Vec::new();
        if dev.user_id != 0 {
            entries.push((0, json!(dev.user_id.to_string())));
        }
        if !dev.source_id.is_empty() {
            entries.push((1, json!(dev.source_id)));
        }
        if !dev.network.is_empty() {
            entries.push((2, json!(dev.network)));
        }
        make_arr(3, &entries)
    }

    // AuthMessage (field 1): requestID=1, network=3, tachyonAuthToken=6,
    //                         configVersion=7
    let auth_val = msg
        .message_auth
        .as_ref()
        .map(|auth| {
            let mut entries = vec![
                (0, json!(auth.request_id)),
                (2, json!(auth.network)),
            ];
            if !auth.tachyon_auth_token.is_empty() {
                entries.push((
                    5,
                    Value::String(BASE64.encode(&auth.tachyon_auth_token)),
                ));
            }
            if let Some(cv) = &auth.config_version {
                entries.push((6, encode_config_version(cv)));
            }
            make_arr(7, &entries)
        })
        .unwrap_or(Value::Null);

    // Device (field 2)
    let device_val = msg
        .curr_browser_device
        .as_ref()
        .map(encode_device)
        .unwrap_or(Value::Null);

    // Parameters (field 13): emptyArr=9, moreParameters=23
    let params_val = msg
        .parameters
        .as_ref()
        .map(|p| {
            let mut entries = Vec::new();
            if p.empty_arr.is_some() {
                entries.push((8, json!([])));
            }
            make_arr(23, &entries)
        })
        .unwrap_or(Value::Null);

    // RegisterRefreshRequest: messageAuth=1, currBrowserDevice=2,
    //   unixTimestamp=3, signature=4, parameters=13, messageType=16
    let mut entries = vec![
        (0, auth_val),
        (1, device_val),
        (2, json!(msg.unix_timestamp.to_string())),
    ];
    if !msg.signature.is_empty() {
        entries.push((3, Value::String(BASE64.encode(&msg.signature))));
    }
    if msg.parameters.is_some() {
        entries.push((12, params_val));
    }
    entries.push((15, json!(msg.message_type)));

    make_arr(16, &entries).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn encode_varint_single_byte() {
        let mut buf = Vec::new();
        encode_varint(&mut buf, 42);
        assert_eq!(buf, [42]);
    }

    #[test]
    fn encode_varint_multi_byte() {
        let mut buf = Vec::new();
        encode_varint(&mut buf, 300);
        assert_eq!(buf, [0xAC, 0x02]);
    }

    #[test]
    fn split_stream_parses_multiple_payloads() {
        let data = r#"[[ ["hello", 1], ["world", 2] ]]"#;
        let payloads = split_stream(data).expect("split_stream failed");
        assert_eq!(payloads.len(), 2);
        assert_eq!(payloads[0], json!(["hello", 1]));
        assert_eq!(payloads[1], json!(["world", 2]));
    }

    #[test]
    fn null_fields_skipped() {
        // Array [null, "test", null] → only field 2 should be encoded.
        let arr = [Value::Null, Value::String("test".into()), Value::Null];
        let wire = json_array_to_wire(&arr, 0).expect("encode failed");

        // Field 2, wire type 2 → tag = (2 << 3) | 2 = 18
        // Length of "test" = 4
        // Bytes: [18, 4, 116, 101, 115, 116]
        assert_eq!(wire, [18, 4, b't', b'e', b's', b't']);
    }

    #[test]
    fn bool_encoded_as_varint() {
        // [true] → field 1, wire type 0, value 1
        // tag = (1 << 3) | 0 = 8
        // bytes: [8, 1]
        let arr = [Value::Bool(true)];
        let wire = json_array_to_wire(&arr, 0).expect("encode failed");
        assert_eq!(wire, [8, 1]);
    }

    #[test]
    fn encode_receive_messages_produces_valid_pblite() {
        use super::super::proto::{authentication, client};

        let request = client::ReceiveMessagesRequest {
            auth: Some(authentication::AuthMessage {
                request_id: "test-uuid".to_owned(),
                network: "Bugle".to_owned(),
                tachyon_auth_token: vec![0xDE, 0xAD],
                config_version: Some(authentication::ConfigVersion {
                    year: 2025,
                    month: 11,
                    day: 6,
                    v1: 4,
                    v2: 6,
                }),
            }),
            unknown: Some(
                client::receive_messages_request::UnknownEmptyObject2 {
                    unknown: Some(
                        client::receive_messages_request::UnknownEmptyObject1 {},
                    ),
                },
            ),
        };

        let json_str = encode_receive_messages_request(&request);
        let parsed: serde_json::Value =
            serde_json::from_str(&json_str).expect("output should be valid JSON");

        // Root is an array of 4 elements (max field = 4)
        let root = parsed.as_array().expect("root should be array");
        assert_eq!(root.len(), 4);

        // auth at index 0
        let auth = root[0].as_array().expect("auth should be array");
        assert_eq!(auth[0], json!("test-uuid"));
        assert_eq!(auth[2], json!("Bugle"));
        // tachyon token at index 5 should be base64
        assert_eq!(auth[5], json!("3q0="));
        // config version at index 6
        let cv = auth[6].as_array().expect("config version should be array");
        assert_eq!(cv[2], json!(2025));
        assert_eq!(cv[3], json!(11));
        assert_eq!(cv[4], json!(6));
        assert_eq!(cv[6], json!(4));
        assert_eq!(cv[8], json!(6));

        // fields 2 and 3 are null
        assert!(root[1].is_null());
        assert!(root[2].is_null());

        // unknown at index 3
        let unknown = root[3].as_array().expect("unknown should be array");
        assert!(unknown[0].is_null());
        assert_eq!(unknown[1], json!([]));
    }
}
