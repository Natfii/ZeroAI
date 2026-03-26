// Copyright (c) 2026 @Natfii. All rights reserved.

//! Owned wrapper around a raw `ANativeWindow` pointer.
//!
//! The Android Kotlin layer passes the native window handle as a `u64`
//! across FFI. This module validates and wraps that pointer so the
//! rendering subsystem can use it safely.

/// Wrapper around a raw `ANativeWindow*` passed from the Android layer.
///
/// Holds the pointer as a `u64` for FFI safety. The actual
/// `ANativeWindow_acquire` / `ANativeWindow_release` lifecycle will be
/// added when the GPU renderer is wired in.
#[derive(Debug)]
pub(crate) struct OwnedNativeWindow(u64);

impl OwnedNativeWindow {
    /// Validate and wrap a raw native-window pointer.
    ///
    /// Returns [`FfiError::InvalidArgument`] if `ptr` is null (zero).
    pub(crate) fn try_from_ptr(ptr: u64) -> Result<Self, crate::error::FfiError> {
        if ptr == 0 {
            return Err(crate::error::FfiError::InvalidArgument {
                detail: "window_ptr must not be null".to_string(),
            });
        }
        Ok(Self(ptr))
    }

    /// Return the raw pointer value for passing to native APIs.
    #[allow(dead_code)]
    pub(crate) fn as_raw(&self) -> u64 {
        self.0
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn rejects_null_pointer() {
        let result = OwnedNativeWindow::try_from_ptr(0);
        assert!(result.is_err());
    }

    #[test]
    fn accepts_nonzero_pointer() {
        let window = OwnedNativeWindow::try_from_ptr(0xDEAD_BEEF).unwrap();
        assert_eq!(window.as_raw(), 0xDEAD_BEEF);
    }
}
