// Copyright (c) 2026 @Natfii. All rights reserved.

//! Build script for zeroclaw-ffi.
//!
//! When the `ghostty-vt` feature is enabled, links the pre-built
//! libghostty-vt shared library for the current Android target.
//! The .so ships in app/src/main/jniLibs/ and is loaded at runtime.

use std::env;
use std::path::PathBuf;

fn main() {
    let target = env::var("TARGET").unwrap_or_default();

    // NOTE: cfg!(feature = ...) evaluates the *build script's* features, not
    // the crate's. Use the CARGO_FEATURE_* env var injected by Cargo instead.
    let has_ghostty_vt = env::var("CARGO_FEATURE_GHOSTTY_VT").is_ok();
    if !has_ghostty_vt || !target.contains("-android") {
        return;
    }

    let manifest_dir =
        PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR not set"));

    let arch_dir = if target.starts_with("aarch64") {
        "aarch64"
    } else if target.starts_with("x86_64") {
        "x86_64"
    } else {
        println!("cargo:warning=libghostty_vt: unsupported target '{target}', skipping link");
        return;
    };

    let libs_dir = manifest_dir.join("libs").join(arch_dir);

    let so_path = libs_dir.join("libghostty_vt.so");
    assert!(
        so_path.exists(),
        "libghostty_vt.so not found at {}. \
         Run scripts/build-ghostty.sh first.",
        so_path.display()
    );

    println!("cargo:rustc-link-search=native={}", libs_dir.display());
    // Dynamic linking — the .so ships in jniLibs and is loaded at runtime
    // via System.loadLibrary("ghostty_vt") before System.loadLibrary("zeroclaw").
    // Static linking was attempted but fails because the Zig-compiled archive
    // references C++ symbols from a newer libc++ than the NDK provides.
    println!("cargo:rustc-link-lib=dylib=ghostty_vt");
    println!(
        "cargo:rerun-if-changed={}",
        libs_dir.join("libghostty_vt.so").display()
    );
}
