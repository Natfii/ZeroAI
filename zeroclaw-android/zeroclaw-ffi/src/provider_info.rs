/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! FFI records for provider-level metadata (token usage, capabilities).
//!
//! Maps upstream [`zeroclaw::providers::traits::TokenUsage`] and
//! [`zeroclaw::providers::traits::ProviderCapabilities`] to flat UniFFI
//! records suitable for transfer across the JNI boundary.

/// Token usage from a single LLM API call.
///
/// Fields are optional because not all providers report usage.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiTokenUsage {
    /// Number of input (prompt) tokens, if reported.
    pub input_tokens: Option<u64>,
    /// Number of output (completion) tokens, if reported.
    pub output_tokens: Option<u64>,
}

impl FfiTokenUsage {
    /// Converts from the upstream provider [`TokenUsage`].
    #[allow(dead_code)]
    pub(crate) fn from_upstream(usage: &zeroclaw::providers::traits::TokenUsage) -> Self {
        Self {
            input_tokens: usage.input_tokens,
            output_tokens: usage.output_tokens,
        }
    }
}

/// Provider capability flags exposed to the Android UI.
///
/// Enables the UI to adapt (e.g. disable vision button when the
/// provider does not support images).
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiProviderCapabilities {
    /// Whether the provider supports native tool calling via API primitives.
    pub native_tool_calling: bool,
    /// Whether the provider supports vision (image) inputs.
    pub vision: bool,
}

impl FfiProviderCapabilities {
    /// Converts from the upstream [`ProviderCapabilities`].
    #[allow(dead_code)]
    pub(crate) fn from_upstream(
        caps: &zeroclaw::providers::traits::ProviderCapabilities,
    ) -> Self {
        Self {
            native_tool_calling: caps.native_tool_calling,
            vision: caps.vision,
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_ffi_token_usage_from_upstream() {
        let upstream = zeroclaw::providers::traits::TokenUsage {
            input_tokens: Some(100),
            output_tokens: Some(50),
        };
        let ffi = FfiTokenUsage::from_upstream(&upstream);
        assert_eq!(ffi.input_tokens, Some(100));
        assert_eq!(ffi.output_tokens, Some(50));
    }

    #[test]
    fn test_ffi_token_usage_none_fields() {
        let upstream = zeroclaw::providers::traits::TokenUsage::default();
        let ffi = FfiTokenUsage::from_upstream(&upstream);
        assert_eq!(ffi.input_tokens, None);
        assert_eq!(ffi.output_tokens, None);
    }

    #[test]
    fn test_ffi_provider_capabilities_from_upstream() {
        let upstream = zeroclaw::providers::traits::ProviderCapabilities {
            native_tool_calling: true,
            vision: false,
        };
        let ffi = FfiProviderCapabilities::from_upstream(&upstream);
        assert!(ffi.native_tool_calling);
        assert!(!ffi.vision);
    }

    #[test]
    fn test_ffi_provider_capabilities_default() {
        let upstream = zeroclaw::providers::traits::ProviderCapabilities::default();
        let ffi = FfiProviderCapabilities::from_upstream(&upstream);
        assert!(!ffi.native_tool_calling);
        assert!(!ffi.vision);
    }
}
