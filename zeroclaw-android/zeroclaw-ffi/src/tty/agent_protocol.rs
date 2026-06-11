// Copyright (c) 2026 @Natfii. All rights reserved.

//! Hand-rolled ssh-agent wire protocol with hardware-key dispatch.
//!
//! Replaces `russh::keys::agent::server::serve` because the stock loop has
//! no signing seam: it owns `Arc<PrivateKey>` entries and signs raw bytes
//! itself, while an Android Keystore key has no extractable private bytes.
//! This loop owns two identity maps — software keys (ported from the russh
//! server's add/remove/identities/sign branches) and hardware identities
//! whose `SIGN_REQUEST`s are routed to Kotlin/Keystore through the
//! [`crate::tty::hw_signer`] callback, with the DER signature re-encoded
//! into SSH wire format here.
//!
//! Wire compatibility notes:
//! - The 256 KiB frame guard is kept verbatim from russh; it is the fix for
//!   RUSTSEC-2026-0153/0154 (unbounded agent-frame allocation).
//! - Software entries use russh's fork of `ssh-key` (types must match what
//!   `AgentClient::add_identity` sends); hardware blobs are produced by the
//!   standalone `ssh-key` 0.6 in [`crate::tty::key_store`]. Interop between
//!   the two is byte-level only — both encode standard SSH public-key blobs.

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, RwLock, RwLockReadGuard, RwLockWriteGuard};
use std::time::Duration;

use russh::keys::signature::Signer;
use russh::keys::ssh_encoding::{Decode, Encode};
use russh::keys::ssh_key;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixStream;

/// Maximum accepted agent frame, kept verbatim from russh's server.
///
/// Guards against unbounded allocation from a hostile client
/// (RUSTSEC-2026-0153/0154). Do not raise without re-reading those
/// advisories.
const MAX_AGENT_FRAME_LEN: usize = 256 * 1024;

// Agent protocol message numbers (draft-miller-ssh-agent).
const MSG_FAILURE: u8 = 5;
const MSG_SUCCESS: u8 = 6;
const MSG_REQUEST_IDENTITIES: u8 = 11;
const MSG_IDENTITIES_ANSWER: u8 = 12;
const MSG_SIGN_REQUEST: u8 = 13;
const MSG_SIGN_RESPONSE: u8 = 14;
const MSG_ADD_IDENTITY: u8 = 17;
const MSG_REMOVE_IDENTITY: u8 = 18;
const MSG_REMOVE_ALL_IDENTITIES: u8 = 19;
const MSG_ADD_ID_CONSTRAINED: u8 = 25;

/// How long a hardware sign round-trip into Kotlin/Keystore may take
/// before the request fails. Keystore signs are normally milliseconds;
/// the margin covers a cold Keystore daemon or StrongBox wake-up.
const HW_SIGN_TIMEOUT: Duration = Duration::from_secs(10);

/// A hardware identity: public half in the map key, private half sealed in
/// the Android Keystore under [`keystore_alias`](Self::keystore_alias).
pub(crate) struct HardwareIdentity {
    /// Android Keystore alias Kotlin signs under.
    pub keystore_alias: String,
    /// Comment surfaced in `REQUEST_IDENTITIES` answers (the key label).
    pub comment: String,
}

/// The agent's identity registry, shared by every client connection.
///
/// Both maps are keyed by the SSH wire encoding of the public key — the
/// blob clients echo back in `SIGN_REQUEST`.
#[derive(Default)]
pub(crate) struct AgentState {
    /// Software keys added through the agent protocol (`ADD_IDENTITY`).
    software: HashMap<Vec<u8>, Arc<ssh_key::PrivateKey>>,
    /// Hardware identities registered directly over FFI.
    hardware: HashMap<Vec<u8>, HardwareIdentity>,
}

/// Process-global agent state. One agent serves the whole app session.
static STATE: LazyLock<RwLock<AgentState>> = LazyLock::new(|| RwLock::new(AgentState::default()));

/// Read-locks the state with poison recovery.
fn state_read() -> RwLockReadGuard<'static, AgentState> {
    STATE.read().unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Write-locks the state with poison recovery.
fn state_write() -> RwLockWriteGuard<'static, AgentState> {
    STATE.write().unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Registers a hardware identity under its public-key blob.
///
/// Replaces any prior identity with the same blob. Called from the FFI
/// layer when Kotlin loads Keystore-backed keys at unlock.
pub(crate) fn register_hardware_identity(blob: Vec<u8>, identity: HardwareIdentity) {
    state_write().hardware.insert(blob, identity);
}

/// Removes the hardware identity stored under `keystore_alias`.
///
/// Returns `true` if an identity was removed.
pub(crate) fn remove_hardware_identity(keystore_alias: &str) -> bool {
    let mut state = state_write();
    let blob = state
        .hardware
        .iter()
        .find(|(_, id)| id.keystore_alias == keystore_alias)
        .map(|(blob, _)| blob.clone());
    match blob {
        Some(blob) => state.hardware.remove(&blob).is_some(),
        None => false,
    }
}

/// Serves one agent client connection until EOF or protocol error.
///
/// Frame loop ported from russh's `Connection::run`: 4-byte big-endian
/// length, bounds-checked against [`MAX_AGENT_FRAME_LEN`] before
/// allocation, then a one-byte message type and payload.
pub(crate) async fn serve_connection(mut stream: UnixStream) {
    let mut frame: Vec<u8> = Vec::new();
    loop {
        let mut len_buf = [0_u8; 4];
        if stream.read_exact(&mut len_buf).await.is_err() {
            return;
        }
        let len = u32::from_be_bytes(len_buf) as usize;
        if len > MAX_AGENT_FRAME_LEN {
            tracing::warn!(
                target: "tty::ssh_agent",
                len,
                "agent frame exceeds limit; closing connection"
            );
            return;
        }
        frame.clear();
        frame.resize(len, 0);
        if stream.read_exact(&mut frame).await.is_err() {
            return;
        }

        let mut response = vec![0_u8; 4];
        if respond(&frame, &mut response).await.is_err() {
            response.truncate(4);
            response.push(MSG_FAILURE);
        }
        let body_len = (response.len() - 4) as u32;
        response[..4].copy_from_slice(&body_len.to_be_bytes());
        if stream.write_all(&response).await.is_err() || stream.flush().await.is_err() {
            return;
        }
    }
}

/// Encoding/decoding failure inside one request. The connection survives;
/// the client receives `MSG_FAILURE`.
struct ProtocolError;

impl<E: std::fmt::Display> From<E> for ProtocolError {
    fn from(e: E) -> Self {
        tracing::debug!(target: "tty::ssh_agent", "agent request failed: {e}");
        ProtocolError
    }
}

/// Dispatches one decoded frame, appending the response body after the
/// 4-byte length placeholder already in `out`.
async fn respond(frame: &[u8], out: &mut Vec<u8>) -> Result<(), ProtocolError> {
    let Some((&msg_type, mut payload)) = frame.split_first() else {
        out.push(MSG_FAILURE);
        return Ok(());
    };

    match msg_type {
        MSG_REQUEST_IDENTITIES => {
            let state = state_read();
            MSG_IDENTITIES_ANSWER.encode(out)?;
            ((state.software.len() + state.hardware.len()) as u32).encode(out)?;
            for blob in state.software.keys() {
                blob.encode(out)?;
                "".encode(out)?;
            }
            for (blob, identity) in &state.hardware {
                blob.encode(out)?;
                identity.comment.encode(out)?;
            }
        }
        MSG_SIGN_REQUEST => {
            let blob = Vec::<u8>::decode(&mut payload)?;
            let data = Vec::<u8>::decode(&mut payload)?;
            // Trailing u32 flags are intentionally ignored, matching the
            // stock russh server (no rsa-sha2 flag handling).
            match sign(&blob, data).await {
                Some(signature_blob) => {
                    out.push(MSG_SIGN_RESPONSE);
                    signature_blob.encode(out)?;
                }
                None => out.push(MSG_FAILURE),
            }
        }
        MSG_ADD_IDENTITY | MSG_ADD_ID_CONSTRAINED => {
            // Constraints on ADD_ID_CONSTRAINED (lifetime/confirm) are
            // accepted but ignored — no in-app caller sends them, and the
            // agent's whole lifetime is already session-scoped by the
            // app lock.
            let key_data = ssh_key::private::KeypairData::decode(&mut payload)?;
            let private_key = ssh_key::PrivateKey::new(key_data, "")?;
            let _comment = String::decode(&mut payload)?;
            let blob = encoded(private_key.public_key().key_data())?;
            state_write().software.insert(blob, Arc::new(private_key));
            out.push(MSG_SUCCESS);
        }
        MSG_REMOVE_IDENTITY => {
            let blob = Vec::<u8>::decode(&mut payload)?;
            let mut state = state_write();
            let removed =
                state.software.remove(&blob).is_some() || state.hardware.remove(&blob).is_some();
            out.push(if removed { MSG_SUCCESS } else { MSG_FAILURE });
        }
        MSG_REMOVE_ALL_IDENTITIES => {
            let mut state = state_write();
            state.software.clear();
            state.hardware.clear();
            out.push(MSG_SUCCESS);
        }
        _ => out.push(MSG_FAILURE),
    }
    Ok(())
}

/// Produces the SSH signature blob for `blob`'s identity, or `None` when
/// the identity is unknown or signing fails.
async fn sign(blob: &[u8], data: Vec<u8>) -> Option<Vec<u8>> {
    enum Identity {
        Software(Arc<ssh_key::PrivateKey>),
        Hardware(String),
    }

    let identity = {
        let state = state_read();
        if let Some(key) = state.software.get(blob) {
            Identity::Software(key.clone())
        } else if let Some(hw) = state.hardware.get(blob) {
            Identity::Hardware(hw.keystore_alias.clone())
        } else {
            return None;
        }
    };

    match identity {
        Identity::Software(key) => sign_software(&key, &data)
            .map_err(|e| {
                tracing::warn!(target: "tty::ssh_agent", "software sign failed: {e}");
            })
            .ok(),
        Identity::Hardware(alias) => sign_hardware(alias, data).await,
    }
}

/// Signs with an in-memory software key.
///
/// Port of russh's `sign_with_hash_alg` with a `None` hash algorithm: the
/// keypair data signs directly (ed25519 / ecdsa-p256 path; this build
/// disables russh's `rsa` feature, exactly as the stock loop did).
fn sign_software(
    key: &ssh_key::PrivateKey,
    data: &[u8],
) -> Result<Vec<u8>, russh::keys::ssh_key::Error> {
    let signature: ssh_key::Signature = key.key_data().try_sign(data)?;
    encoded(&signature)
}

/// Signs through the Kotlin/Keystore callback and re-encodes the DER
/// signature into SSH wire format.
///
/// The callback is a blocking JNI call, so it runs on the blocking pool
/// under [`HW_SIGN_TIMEOUT`]. Any failure (no signer registered, Keystore
/// error, timeout, malformed DER) logs and returns `None`, which the
/// caller answers with `MSG_FAILURE`.
async fn sign_hardware(alias: String, data: Vec<u8>) -> Option<Vec<u8>> {
    let log_alias = alias.clone();
    let join = tokio::task::spawn_blocking(move || crate::tty::hw_signer::sign(&alias, data));
    let der = match tokio::time::timeout(HW_SIGN_TIMEOUT, join).await {
        Ok(Ok(Some(der))) if !der.is_empty() => der,
        Ok(Ok(_)) => {
            tracing::warn!(
                target: "tty::ssh_agent",
                alias = %log_alias,
                "hardware signer returned no signature"
            );
            return None;
        }
        Ok(Err(e)) => {
            tracing::warn!(target: "tty::ssh_agent", "hardware sign task failed: {e}");
            return None;
        }
        Err(_) => {
            tracing::warn!(
                target: "tty::ssh_agent",
                alias = %log_alias,
                "hardware sign timed out after {HW_SIGN_TIMEOUT:?}"
            );
            return None;
        }
    };
    match ecdsa_der_to_ssh_signature(&der) {
        Ok(blob) => Some(blob),
        Err(detail) => {
            tracing::warn!(
                target: "tty::ssh_agent",
                alias = %log_alias,
                "DER signature conversion failed: {detail}"
            );
            None
        }
    }
}

/// Encodes an SSH-encodable value into a fresh buffer.
fn encoded<T: Encode>(value: &T) -> Result<Vec<u8>, russh::keys::ssh_key::Error> {
    let mut buf = Vec::new();
    value
        .encode(&mut buf)
        .map_err(russh::keys::ssh_key::Error::from)?;
    Ok(buf)
}

/// Converts a DER `ECDSA-Sig-Value` (as produced by Android's
/// `SHA256withECDSA`) into the SSH `ecdsa-sha2-nistp256` signature blob:
/// `string "ecdsa-sha2-nistp256" ‖ string (mpint r ‖ mpint s)`.
///
/// Returns a human-readable error description on malformed input.
pub(crate) fn ecdsa_der_to_ssh_signature(der: &[u8]) -> Result<Vec<u8>, String> {
    let (r, s) = parse_der_ecdsa(der)?;

    let mut inner = Vec::with_capacity(r.len() + s.len() + 10);
    encode_mpint(&r, &mut inner);
    encode_mpint(&s, &mut inner);

    let mut blob = Vec::with_capacity(inner.len() + 32);
    encode_ssh_string(b"ecdsa-sha2-nistp256", &mut blob);
    encode_ssh_string(&inner, &mut blob);
    Ok(blob)
}

/// Parses `SEQUENCE { INTEGER r, INTEGER s }` and returns the raw integer
/// bytes (DER minimal form, sign byte included when present).
fn parse_der_ecdsa(der: &[u8]) -> Result<(Vec<u8>, Vec<u8>), String> {
    let mut pos = 0_usize;
    let tag = *der.get(pos).ok_or("empty DER signature")?;
    if tag != 0x30 {
        return Err(format!("expected DER SEQUENCE, got tag {tag:#04x}"));
    }
    pos += 1;
    let (seq_len, len_size) = parse_der_length(&der[pos..])?;
    pos += len_size;
    if der.len() != pos + seq_len {
        return Err("DER SEQUENCE length mismatch".to_owned());
    }

    let (r, used) = parse_der_integer(&der[pos..])?;
    pos += used;
    let (s, used) = parse_der_integer(&der[pos..])?;
    pos += used;
    if pos != der.len() {
        return Err("trailing bytes after DER integers".to_owned());
    }
    Ok((r, s))
}

/// Parses a DER length octet (short or long form), returning the length
/// and the number of bytes consumed.
fn parse_der_length(bytes: &[u8]) -> Result<(usize, usize), String> {
    let first = *bytes.first().ok_or("truncated DER length")?;
    if first < 0x80 {
        return Ok((first as usize, 1));
    }
    let num_octets = (first & 0x7F) as usize;
    // P-256 signatures are well under 256 bytes; longer length forms are
    // not produced by Android's DER encoder.
    if num_octets != 1 {
        return Err(format!("unsupported DER length form ({num_octets} octets)"));
    }
    let value = *bytes.get(1).ok_or("truncated DER long-form length")?;
    Ok((value as usize, 2))
}

/// Parses one DER INTEGER, returning its content bytes and total size.
fn parse_der_integer(bytes: &[u8]) -> Result<(Vec<u8>, usize), String> {
    let tag = *bytes.first().ok_or("truncated DER integer")?;
    if tag != 0x02 {
        return Err(format!("expected DER INTEGER, got tag {tag:#04x}"));
    }
    let (len, len_size) = parse_der_length(&bytes[1..])?;
    let start = 1 + len_size;
    let content = bytes
        .get(start..start + len)
        .ok_or("truncated DER integer content")?;
    if content.is_empty() {
        return Err("empty DER integer".to_owned());
    }
    Ok((content.to_vec(), start + len))
}

/// Appends an SSH `mpint` (RFC 4251): minimal-length, big-endian, with a
/// leading zero byte iff the high bit of the first byte is set.
fn encode_mpint(int: &[u8], out: &mut Vec<u8>) {
    let mut start = 0;
    while start < int.len() - 1 && int[start] == 0 {
        start += 1;
    }
    let trimmed = &int[start..];
    let needs_pad = trimmed[0] & 0x80 != 0;
    let len = trimmed.len() + usize::from(needs_pad);
    out.extend_from_slice(&(len as u32).to_be_bytes());
    if needs_pad {
        out.push(0);
    }
    out.extend_from_slice(trimmed);
}

/// Appends an SSH `string`: 4-byte big-endian length plus raw bytes.
fn encode_ssh_string(bytes: &[u8], out: &mut Vec<u8>) {
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    /// Builds a DER ECDSA-Sig-Value from raw integer content bytes.
    fn der_sig(r: &[u8], s: &[u8]) -> Vec<u8> {
        let mut out = vec![0x30, (r.len() + s.len() + 4) as u8];
        out.extend_from_slice(&[0x02, r.len() as u8]);
        out.extend_from_slice(r);
        out.extend_from_slice(&[0x02, s.len() as u8]);
        out.extend_from_slice(s);
        out
    }

    /// Reads one SSH string from the front of `bytes`.
    fn read_string(bytes: &mut &[u8]) -> Vec<u8> {
        let len = u32::from_be_bytes(bytes[..4].try_into().unwrap()) as usize;
        let value = bytes[4..4 + len].to_vec();
        *bytes = &bytes[4 + len..];
        value
    }

    #[test]
    fn der_conversion_strips_padding_and_pads_high_bit() {
        // r carries DER's sign byte (high bit set in 0x80..); s is plain.
        let r = [0x00, 0x80, 0x01, 0x02];
        let s = [0x7F, 0xFF];
        let blob = ecdsa_der_to_ssh_signature(&der_sig(&r, &s)).unwrap();

        let mut cursor = blob.as_slice();
        assert_eq!(read_string(&mut cursor), b"ecdsa-sha2-nistp256");
        let mut inner = read_string(&mut cursor);
        assert!(cursor.is_empty());

        let mut inner_cursor = inner.as_mut_slice() as &[u8];
        // mpint r: high bit set, so the leading zero is preserved.
        assert_eq!(read_string(&mut inner_cursor), [0x00, 0x80, 0x01, 0x02]);
        // mpint s: no high bit, no padding.
        assert_eq!(read_string(&mut inner_cursor), [0x7F, 0xFF]);
        assert!(inner_cursor.is_empty());
    }

    #[test]
    fn der_conversion_rejects_garbage() {
        assert!(ecdsa_der_to_ssh_signature(&[]).is_err());
        assert!(ecdsa_der_to_ssh_signature(&[0x31, 0x00]).is_err());
        assert!(ecdsa_der_to_ssh_signature(&[0x30, 0x02, 0x05, 0x00]).is_err());
    }

    #[test]
    fn mpint_zero_stripping_keeps_one_byte() {
        let mut out = Vec::new();
        encode_mpint(&[0x00, 0x00, 0x01], &mut out);
        assert_eq!(out, [0, 0, 0, 1, 0x01]);
    }

    #[test]
    fn round_trip_p256_der_signature_verifies_as_ssh() {
        use p256::ecdsa::signature::Signer as P256Signer;
        use signature::Verifier;

        let signing_key = p256::ecdsa::SigningKey::random(&mut ::ssh_key::rand_core::OsRng);
        let message = b"zeroai hardware ssh round trip";
        let der: p256::ecdsa::DerSignature = signing_key.sign(message);

        let blob = ecdsa_der_to_ssh_signature(der.as_bytes()).unwrap();

        // Re-parse the blob into the standalone ssh-key Signature and verify
        // against the public key built the same way the Keystore path builds
        // it (SEC1 point through key_store::hardware_key_metadata).
        let mut cursor = blob.as_slice();
        let algo = read_string(&mut cursor);
        assert_eq!(algo, b"ecdsa-sha2-nistp256");
        let inner = read_string(&mut cursor);

        let point = signing_key
            .verifying_key()
            .to_encoded_point(false)
            .as_bytes()
            .to_vec();
        let metadata =
            crate::tty::key_store::hardware_key_metadata(&point, "test-id".into(), "test")
                .unwrap();
        let public_key =
            ::ssh_key::PublicKey::from_openssh(&metadata.public_key_openssh).unwrap();

        let signature = ::ssh_key::Signature::new(
            ::ssh_key::Algorithm::Ecdsa {
                curve: ::ssh_key::EcdsaCurve::NistP256,
            },
            inner,
        )
        .unwrap();
        Verifier::verify(&public_key, message.as_slice(), &signature).unwrap();
    }
}
