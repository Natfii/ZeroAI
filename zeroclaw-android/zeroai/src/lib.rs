// Copyright (c) 2026 @Natfii. All rights reserved.

//! ZeroAI — Android-only Rust modules that layer on top of upstream zeroclaw.
//!
//! All modules in this crate are local additions that have no upstream
//! equivalent. The upstream `zeroclaw` crate stays pristine; everything
//! Android- or ZeroAI-specific lives here.

pub mod auth;
pub mod channels;
pub mod clawboy_triggers;
pub mod ffi_credential_hook;
pub mod memory;
pub mod messages_bridge;
pub mod router;
pub mod scripting;

/// Convenience re-exports used by `zeroclaw-ffi` and other callers that
/// expect the old monolithic `zeroclaw::auth_exports::*` shape.
pub mod auth_exports {
    pub use crate::auth::gemini_oauth::extract_account_email_from_id_token;
    pub use crate::auth::openai_oauth::extract_account_id_from_jwt;
    pub use crate::auth::profiles::{
        profile_id, AuthProfile, AuthProfileKind, AuthProfilesData, AuthProfilesStore, TokenSet,
    };
    pub use crate::auth::state_dir_from_config;
    pub use crate::auth::AuthService;
}
