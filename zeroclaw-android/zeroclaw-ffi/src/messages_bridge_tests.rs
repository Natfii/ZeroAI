// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for `crate::messages_bridge`.

#![allow(clippy::unwrap_used)]

use super::*;

#[test]
fn generate_qr_svg_produces_svg_markup() {
    let svg = generate_qr_svg("https://example.com/pair?token=abc123");
    assert!(svg.contains("<svg"), "output should contain an SVG element");
}

#[test]
fn pairing_html_contains_placeholder() {
    assert!(
        PAIRING_HTML.contains("QR_SVG_PLACEHOLDER"),
        "PAIRING_HTML must contain the QR_SVG_PLACEHOLDER token"
    );
}

#[test]
fn pairing_html_placeholder_replaced() {
    let html = PAIRING_HTML.replace("QR_SVG_PLACEHOLDER", "<svg/>");
    assert!(html.contains("<svg/>"), "placeholder should be replaced");
    assert!(
        !html.contains("QR_SVG_PLACEHOLDER"),
        "placeholder should be gone"
    );
}

#[tokio::test]
async fn server_starts_and_reports_port() {
    let paired = Arc::new(AtomicBool::new(false));
    let server = PairingPageServer::start("https://example.com/pair".to_owned(), paired)
        .await
        .unwrap();
    assert!(server.port() > 0, "port should be non-zero");
    assert!(
        server.page_url().starts_with("http://"),
        "page URL should be an HTTP URL"
    );
    drop(server);
}
