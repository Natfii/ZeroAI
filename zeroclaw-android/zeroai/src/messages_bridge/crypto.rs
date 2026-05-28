// Copyright (c) 2026 @Natfii. All rights reserved.

//! AES-256-CTR + HMAC-SHA256 encryption primitives for the Google Messages Bugle protocol.
//!
//! Wire format: `ciphertext || IV (16 bytes) || HMAC-SHA256(ciphertext || IV) (32 bytes)`
//!
//! Ported from mautrix-gmessages: <https://github.com/mautrix/gmessages/blob/main/pkg/libgm/crypto/aesctr.go>

use aes::Aes256;
use ctr::cipher::{KeyIvInit, StreamCipher};
use hmac::{Hmac, Mac};
use rand_core06::{OsRng, RngCore};
use sha2::Sha256;
use thiserror::Error;

type Aes256Ctr = ctr::Ctr128BE<Aes256>;
type HmacSha256 = Hmac<Sha256>;

/// The minimum length of an encrypted payload: 16-byte IV + 32-byte HMAC tag.
const MIN_DATA_LEN: usize = 16 + 32;

/// AES-256 key and HMAC-SHA256 key pair used by the Bugle protocol.
#[derive(Debug, Clone)]
pub struct BugleCryptoKeys {
    /// 256-bit AES key for CTR-mode encryption/decryption.
    pub aes_key: [u8; 32],
    /// 256-bit HMAC key for authenticating ciphertext.
    pub hmac_key: [u8; 32],
}

/// Errors that can occur during Bugle crypto operations.
#[derive(Debug, Error)]
pub enum CryptoError {
    /// The input data is shorter than the minimum required length (16 IV + 32 HMAC bytes).
    #[error("data too short: need at least {MIN_DATA_LEN} bytes, got {0}")]
    DataTooShort(usize),
    /// The HMAC tag did not match — data is corrupt or has been tampered with.
    #[error("HMAC verification failed")]
    HmacMismatch,
}

/// Encrypts `plaintext` using AES-256-CTR with a random IV, then appends the IV and an
/// HMAC-SHA256 tag over the ciphertext.
///
/// # Returns
///
/// A `Vec<u8>` in the wire format `ciphertext || IV (16 bytes) || HMAC-SHA256 (32 bytes)`.
pub fn encrypt(keys: &BugleCryptoKeys, plaintext: &[u8]) -> Vec<u8> {
    // Generate a fresh 128-bit IV for every message.
    let mut iv = [0u8; 16];
    OsRng.fill_bytes(&mut iv);

    // AES-256-CTR encrypt in-place.
    let mut ciphertext = plaintext.to_vec();
    let mut cipher = Aes256Ctr::new(keys.aes_key.as_ref().into(), iv.as_ref().into());
    cipher.apply_keystream(&mut ciphertext);

    // Compute HMAC-SHA256 over ciphertext || IV (matching upstream).
    let mut mac =
        HmacSha256::new_from_slice(&keys.hmac_key).expect("HMAC accepts any key length");
    mac.update(&ciphertext);
    mac.update(&iv);
    let tag = mac.finalize().into_bytes();

    // Assemble wire format: ciphertext || IV || HMAC.
    let mut output = ciphertext;
    output.extend_from_slice(&iv);
    output.extend_from_slice(&tag);
    output
}

/// Decrypts a payload produced by [`encrypt`].
///
/// Verifies the HMAC-SHA256 tag first, then decrypts with AES-256-CTR.
///
/// # Errors
///
/// Returns [`CryptoError::DataTooShort`] if `data` is fewer than 48 bytes.
/// Returns [`CryptoError::HmacMismatch`] if the tag does not match.
pub fn decrypt(keys: &BugleCryptoKeys, data: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if data.len() < MIN_DATA_LEN {
        return Err(CryptoError::DataTooShort(data.len()));
    }

    // Split wire format: ciphertext | IV (last 48..last 32) | HMAC (last 32).
    let tag_start = data.len() - 32;
    let iv_start = tag_start - 16;
    let ciphertext = &data[..iv_start];
    let iv: [u8; 16] = data[iv_start..tag_start].try_into().expect("slice is exactly 16 bytes");
    let expected_tag = &data[tag_start..];

    // Verify HMAC over ciphertext || IV (matching upstream).
    let mut mac =
        HmacSha256::new_from_slice(&keys.hmac_key).expect("HMAC accepts any key length");
    mac.update(ciphertext);
    mac.update(&iv);
    mac.verify_slice(expected_tag).map_err(|_| CryptoError::HmacMismatch)?;

    // AES-256-CTR decrypt in-place.
    let mut plaintext = ciphertext.to_vec();
    let mut cipher = Aes256Ctr::new(keys.aes_key.as_ref().into(), iv.as_ref().into());
    cipher.apply_keystream(&mut plaintext);

    Ok(plaintext)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_keys() -> BugleCryptoKeys {
        BugleCryptoKeys {
            aes_key: [0x42u8; 32],
            hmac_key: [0x7fu8; 32],
        }
    }

    #[test]
    fn round_trip_encrypt_decrypt() {
        let keys = test_keys();
        let plaintext = b"Hello, Google Messages!";
        let encrypted = encrypt(&keys, plaintext);
        let decrypted = decrypt(&keys, &encrypted).expect("decryption should succeed");
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn decrypt_rejects_tampered_data() {
        let keys = test_keys();
        let plaintext = b"sensitive payload";
        let mut encrypted = encrypt(&keys, plaintext);
        // Flip a byte in the ciphertext portion.
        encrypted[0] ^= 0xff;
        let result = decrypt(&keys, &encrypted);
        assert!(
            matches!(result, Err(CryptoError::HmacMismatch)),
            "expected HmacMismatch, got {:?}",
            result
        );
    }

    #[test]
    fn decrypt_rejects_too_short() {
        let keys = test_keys();
        let short_data = [0u8; 10];
        let result = decrypt(&keys, &short_data);
        assert!(
            matches!(result, Err(CryptoError::DataTooShort(10))),
            "expected DataTooShort(10), got {:?}",
            result
        );
    }

    #[test]
    fn empty_plaintext_round_trip() {
        let keys = test_keys();
        let plaintext: &[u8] = &[];
        let encrypted = encrypt(&keys, plaintext);
        // Encrypted form must be exactly IV + HMAC (48 bytes) for empty input.
        assert_eq!(encrypted.len(), MIN_DATA_LEN);
        let decrypted = decrypt(&keys, &encrypted).expect("decryption of empty plaintext should succeed");
        assert_eq!(decrypted, plaintext);
    }
}
