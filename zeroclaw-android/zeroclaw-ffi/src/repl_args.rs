// Copyright (c) 2026 @Natfii. All rights reserved.

//! JSON argument-extraction helpers shared by the script host.
//!
//! These helpers translate `serde_json::Value` argument maps into the
//! typed values expected by [`crate::repl::dispatch_common_operation`],
//! converting missing or wrongly-typed entries into [`ScriptError`].
//! Kept separate from `repl.rs` so the dispatch and host code can stay
//! focused on operations rather than parsing.

use zeroai::scripting::ScriptError;

use crate::error::FfiError;

/// Wraps an [`FfiError`] into a [`ScriptError::HostError`] with the
/// given operation label.
pub(crate) fn script_host_error(
    operation: &'static str,
) -> impl FnOnce(FfiError) -> ScriptError {
    move |error| ScriptError::HostError {
        operation: operation.to_string(),
        detail: error.to_string(),
    }
}

/// Wraps a [`serde_json::Error`] into a [`ScriptError::HostError`] with
/// a serialization-failure message.
pub(crate) fn json_error(
    operation: &'static str,
) -> impl FnOnce(serde_json::Error) -> ScriptError {
    move |error| ScriptError::HostError {
        operation: operation.to_string(),
        detail: format!("serialization failed: {error}"),
    }
}

/// Converts a [`ScriptError`] into the appropriate [`FfiError`] variant.
pub(crate) fn map_script_error(error: ScriptError) -> FfiError {
    let detail = error.to_string();
    match error {
        ScriptError::InvalidArgument { .. }
        | ScriptError::ValidationError { .. }
        | ScriptError::CapabilityDenied { .. } => FfiError::InvalidArgument { detail },
        ScriptError::HostError { .. } => FfiError::StateError { detail },
        ScriptError::InternalState { .. } => FfiError::StateCorrupted { detail },
    }
}

/// Serializes a value to JSON, mapping failures to [`ScriptError`].
pub(crate) fn to_json<T: serde::Serialize>(value: &T) -> Result<String, ScriptError> {
    serde_json::to_string(value).map_err(json_error("json"))
}

/// Extracts a required string argument from a JSON value map.
pub(crate) fn string_arg(args: &serde_json::Value, key: &str) -> Result<String, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_str)
        .map(ToString::to_string)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing string argument: {key}"),
        })
}

/// Extracts an optional string argument, returning `None` when absent
/// or empty.
pub(crate) fn optional_string_arg(args: &serde_json::Value, key: &str) -> Option<String> {
    args.get(key)
        .and_then(serde_json::Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string)
}

/// Extracts a list of strings from a JSON array argument.
pub(crate) fn string_list_arg(args: &serde_json::Value, key: &str) -> Vec<String> {
    args.get(key)
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(serde_json::Value::as_str)
        .map(ToString::to_string)
        .collect()
}

/// Extracts a required `i64` argument from a JSON value map.
pub(crate) fn int_arg(args: &serde_json::Value, key: &str) -> Result<i64, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_i64)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing integer argument: {key}"),
        })
}

/// Extracts a required `i32` argument from a JSON value map.
pub(crate) fn i32_arg(args: &serde_json::Value, key: &str) -> Result<i32, ScriptError> {
    i32::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument out of range for i32: {key}"),
    })
}

/// Extracts a required non-negative `u32` argument from a JSON value map.
pub(crate) fn u32_arg(args: &serde_json::Value, key: &str) -> Result<u32, ScriptError> {
    u32::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument must be a non-negative u32: {key}"),
    })
}

/// Extracts a required non-negative `u64` argument from a JSON value map.
pub(crate) fn u64_arg(args: &serde_json::Value, key: &str) -> Result<u64, ScriptError> {
    u64::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument must be a non-negative u64: {key}"),
    })
}

/// Extracts a required `f64` argument from a JSON value map.
pub(crate) fn float_arg(args: &serde_json::Value, key: &str) -> Result<f64, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_f64)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing float argument: {key}"),
        })
}
