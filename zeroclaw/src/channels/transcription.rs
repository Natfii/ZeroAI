/*
 * Copyright (c) 2026 Zeroclaw Labs. All rights reserved.
 */

/// Test-only voice transcription shim.
///
/// The production Telegram channel currently skips voice transcription when the
/// dedicated module is unavailable. This shim exists so ignored integration
/// tests continue to compile without reintroducing the removed runtime path.
pub async fn transcribe_audio(
    _audio_data: Vec<u8>,
    _filename: &str,
    _config: &crate::config::TranscriptionConfig,
) -> anyhow::Result<String> {
    anyhow::bail!("Voice transcription module is not available in this build")
}
