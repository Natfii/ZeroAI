//! Static file serving for the embedded web dashboard.
//!
//! Uses `rust-embed` to bundle the `web/dist/` directory into the binary at compile time.

use axum::{
    http::{header, StatusCode, Uri},
    response::IntoResponse,
};
use rust_embed::Embed;

#[derive(Embed)]
#[folder = "web/dist/"]
struct WebAssets;

/// Serve static files from `/_app/*` path
pub async fn handle_static(uri: Uri) -> impl IntoResponse {
    let path = uri.path().strip_prefix("/_app/").unwrap_or(uri.path());

    serve_embedded_file(path)
}

/// SPA fallback: serve index.html for any non-API, non-static GET request
pub async fn handle_spa_fallback() -> impl IntoResponse {
    serve_embedded_file("index.html")
}

fn serve_embedded_file(path: &str) -> impl IntoResponse {
    match WebAssets::get(path) {
        Some(content) => {
            let mime = mime_guess::from_path(path)
                .first_or_octet_stream()
                .to_string();

            (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, mime),
                    (
                        header::CACHE_CONTROL,
                        if path.contains("assets/") {
                            "public, max-age=31536000, immutable".to_string()
                        } else {
                            "no-cache".to_string()
                        },
                    ),
                    (
                        header::CONTENT_SECURITY_POLICY,
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'"
                            .to_string(),
                    ),
                ],
                content.data.to_vec(),
            )
                .into_response()
        }
        None => (StatusCode::NOT_FOUND, "Not found").into_response(),
    }
}
