// Copyright (c) 2026 @Natfii. All rights reserved.

//! Email tool FFI surface (stubbed).
//!
//! The `tools::email` module (along with `EmailConfig` and
//! `validate_check_times`) was removed upstream during the workspace
//! split. The FFI surface is preserved so the Kotlin build stays green;
//! both entry points return [`FfiError::InvalidArgument`] so the UI can
//! surface a "not supported" message to the user.

use crate::error::FfiError;

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Configure the agent's email mailbox.
    ///
    /// Stubbed — returns [`crate::FfiError::InvalidArgument`]; see the
    /// module-level docs for context.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] (always), or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn configure_email(config_json: String) -> () = configure_email_inner
);

crate::ffi_export!(
    /// Test IMAP and SMTP connectivity for the given email configuration.
    ///
    /// Stubbed — returns [`crate::FfiError::InvalidArgument`]; see the
    /// module-level docs for context.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] (always), or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn test_email_connection(config_json: String) -> String = test_email_connection_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn configure_email_inner(_config_json: String) -> Result<(), FfiError> {
    Err(FfiError::InvalidArgument {
        detail: "email tools removed in upstream rebase; not yet ported".to_string(),
    })
}

pub(crate) fn test_email_connection_inner(_config_json: String) -> Result<String, FfiError> {
    Err(FfiError::InvalidArgument {
        detail: "email tools removed in upstream rebase; not yet ported".to_string(),
    })
}
