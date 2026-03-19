// Copyright (c) 2026 @Natfii. All rights reserved.

//! Integration tests for the messages bridge crypto.

use zeroclaw::messages_bridge::crypto::{decrypt, encrypt, BugleCryptoKeys, CryptoError};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn keys_a() -> BugleCryptoKeys {
    BugleCryptoKeys {
        aes_key: [0x11u8; 32],
        hmac_key: [0x22u8; 32],
    }
}

fn keys_b() -> BugleCryptoKeys {
    BugleCryptoKeys {
        aes_key: [0xAAu8; 32],
        hmac_key: [0xBBu8; 32],
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

/// Encrypt a 1 MB payload and verify the round-trip produces identical bytes.
#[test]
fn test_large_payload() {
    let keys = keys_a();

    // Build a 1 MB payload with a repeating pattern.
    let plaintext: Vec<u8> = (0..1_048_576).map(|i| (i % 251) as u8).collect();

    let encrypted = encrypt(&keys, &plaintext);

    // Encrypted form must be longer than plaintext (IV + HMAC overhead = 48 bytes).
    assert_eq!(
        encrypted.len(),
        plaintext.len() + 48,
        "encrypted length should be plaintext + 48 bytes overhead"
    );

    let decrypted = decrypt(&keys, &encrypted).expect("decryption of 1 MB payload should succeed");
    assert_eq!(decrypted, plaintext, "decrypted 1 MB payload must match original");
}

/// Encrypting with keys A and decrypting with keys B must return HmacMismatch.
#[test]
fn test_wrong_keys_fail() {
    let plaintext = b"sensitive message that must not leak";

    let encrypted = encrypt(&keys_a(), plaintext);
    let result = decrypt(&keys_b(), &encrypted);

    assert!(
        matches!(result, Err(CryptoError::HmacMismatch)),
        "expected HmacMismatch when decrypting with wrong keys, got {:?}",
        result
    );
}

/// Encrypting the same plaintext twice must produce different ciphertexts (random IV).
#[test]
fn test_different_encryptions_differ() {
    let keys = keys_a();
    let plaintext = b"repeated plaintext to check IV randomness";

    let enc1 = encrypt(&keys, plaintext);
    let enc2 = encrypt(&keys, plaintext);

    assert_ne!(
        enc1, enc2,
        "two encryptions of the same plaintext must differ due to random IV"
    );

    // Both must decrypt to the same original plaintext.
    let dec1 = decrypt(&keys, &enc1).expect("first decryption should succeed");
    let dec2 = decrypt(&keys, &enc2).expect("second decryption should succeed");
    assert_eq!(dec1, plaintext.as_ref());
    assert_eq!(dec2, plaintext.as_ref());
}
