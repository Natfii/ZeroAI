// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for `crate::vision`.

#![allow(clippy::unwrap_used)]

use super::*;

// ── classify_provider tests ────────────────────────────────────

#[test]
fn test_classify_anthropic() {
    assert_eq!(
        classify_provider("anthropic"),
        Some(VisionProvider::Anthropic { base_url: None })
    );
    assert_eq!(
        classify_provider("claude"),
        Some(VisionProvider::Anthropic { base_url: None })
    );
    assert_eq!(
        classify_provider("Anthropic"),
        Some(VisionProvider::Anthropic { base_url: None })
    );
}

#[test]
fn test_classify_anthropic_custom_prefix_removed() {
    assert_eq!(
        classify_provider("anthropic-custom:https://my-proxy.example.com"),
        None,
    );
}

#[test]
fn test_classify_openai() {
    assert_eq!(
        classify_provider("openai"),
        Some(VisionProvider::OpenAi { base_url: None })
    );
    assert_eq!(
        classify_provider("gpt"),
        Some(VisionProvider::OpenAi { base_url: None })
    );
    assert_eq!(
        classify_provider("chatgpt"),
        Some(VisionProvider::OpenAi { base_url: None })
    );
}

#[test]
fn test_classify_removed_providers_return_none() {
    for name in &[
        "together",
        "groq",
        "perplexity",
        "deepseek",
        "fireworks",
        "mistral",
        "lmstudio",
        "vllm",
        "localai",
    ] {
        assert_eq!(
            classify_provider(name),
            None,
            "removed provider {name} should return None"
        );
    }
}

#[test]
fn test_classify_custom_url_returns_none() {
    assert_eq!(classify_provider("custom:http://localhost:8080/v1"), None);
}

#[test]
fn test_classify_gemini() {
    assert_eq!(classify_provider("gemini"), Some(VisionProvider::Gemini));
    assert_eq!(classify_provider("google"), Some(VisionProvider::Gemini));
}

#[test]
fn test_classify_ollama() {
    let result = classify_provider("ollama");
    assert_eq!(
        result,
        Some(VisionProvider::OpenAi {
            base_url: Some("http://localhost:11434/v1".into()),
        })
    );
}

#[test]
fn test_classify_anthropic_custom_returns_none() {
    assert_eq!(classify_provider("anthropic-custom:"), None);
    assert_eq!(
        classify_provider("anthropic-custom:https://my-proxy.example.com"),
        None
    );
}

#[test]
fn test_classify_unsupported() {
    assert_eq!(classify_provider("unknown-provider-xyz"), None);
    assert_eq!(classify_provider(""), None);
}

// ── build body tests ───────────────────────────────────────────

#[test]
fn test_build_anthropic_body_single_image() {
    let body = build_anthropic_body(
        "describe this",
        &["aGVsbG8=".into()],
        &["image/jpeg".into()],
        "claude-sonnet-4-20250514",
    );
    assert_eq!(body["model"], "claude-sonnet-4-20250514");
    assert_eq!(body["max_tokens"], DEFAULT_MAX_TOKENS);
    let content = body["messages"][0]["content"].as_array().unwrap();
    assert_eq!(content.len(), 2);
    assert_eq!(content[0]["type"], "image");
    assert_eq!(content[0]["source"]["type"], "base64");
    assert_eq!(content[0]["source"]["media_type"], "image/jpeg");
    assert_eq!(content[0]["source"]["data"], "aGVsbG8=");
    assert_eq!(content[1]["type"], "text");
    assert_eq!(content[1]["text"], "describe this");
}

#[test]
fn test_build_anthropic_body_multiple_images() {
    let body = build_anthropic_body(
        "compare",
        &["img1".into(), "img2".into(), "img3".into()],
        &["image/jpeg".into(), "image/png".into(), "image/jpeg".into()],
        "claude-sonnet-4-20250514",
    );
    let content = body["messages"][0]["content"].as_array().unwrap();
    assert_eq!(content.len(), 4);
    assert_eq!(content[2]["source"]["media_type"], "image/jpeg");
}

#[test]
fn test_build_openai_body_single_image() {
    let body = build_openai_body(
        "what is this?",
        &["aGVsbG8=".into()],
        &["image/jpeg".into()],
        "gpt-4o",
    );
    assert_eq!(body["model"], "gpt-4o");
    assert_eq!(body["max_tokens"], DEFAULT_MAX_TOKENS);
    let content = body["messages"][0]["content"].as_array().unwrap();
    assert_eq!(content.len(), 2);
    assert_eq!(content[0]["type"], "image_url");
    let url = content[0]["image_url"]["url"].as_str().unwrap();
    assert!(url.starts_with("data:image/jpeg;base64,"));
    assert_eq!(content[0]["image_url"]["detail"], "auto");
    assert_eq!(content[1]["text"], "what is this?");
}

#[test]
fn test_build_gemini_body_single_image() {
    let body = build_gemini_body("analyze", &["aGVsbG8=".into()], &["image/jpeg".into()]);
    let parts = body["contents"][0]["parts"].as_array().unwrap();
    assert_eq!(parts.len(), 2);
    assert_eq!(parts[0]["inline_data"]["mime_type"], "image/jpeg");
    assert_eq!(parts[0]["inline_data"]["data"], "aGVsbG8=");
    assert_eq!(parts[1]["text"], "analyze");
}

// ── parse response tests ───────────────────────────────────────

#[test]
fn test_parse_anthropic_response_ok() {
    let body = json!({
        "content": [
            {"type": "text", "text": "This is a cat."}
        ]
    });
    assert_eq!(parse_anthropic_response(&body).unwrap(), "This is a cat.");
}

#[test]
fn test_parse_anthropic_response_missing() {
    let body = json!({"content": []});
    assert!(parse_anthropic_response(&body).is_err());
}

#[test]
fn test_parse_openai_response_ok() {
    let body = json!({
        "choices": [{
            "message": {"content": "A beautiful sunset."}
        }]
    });
    assert_eq!(parse_openai_response(&body).unwrap(), "A beautiful sunset.");
}

#[test]
fn test_parse_openai_response_missing() {
    let body = json!({"choices": []});
    assert!(parse_openai_response(&body).is_err());
}

#[test]
fn test_parse_gemini_response_ok() {
    let body = json!({
        "candidates": [{
            "content": {
                "parts": [{"text": "A dog playing."}]
            }
        }]
    });
    assert_eq!(parse_gemini_response(&body).unwrap(), "A dog playing.");
}

#[test]
fn test_parse_gemini_response_missing() {
    let body = json!({"candidates": []});
    assert!(parse_gemini_response(&body).is_err());
}

// ── input validation tests ─────────────────────────────────────

#[test]
fn test_send_vision_empty_images() {
    let result = send_vision_message_inner("hello".into(), vec![], vec![]);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("at least one image"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_send_vision_too_many_images() {
    let result = send_vision_message_inner(
        "hello".into(),
        vec!["a".into(); 6],
        vec!["image/jpeg".into(); 6],
    );
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("too many images"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_send_vision_mismatched_lengths() {
    let result = send_vision_message_inner(
        "hello".into(),
        vec!["a".into()],
        vec!["image/jpeg".into(), "image/png".into()],
    );
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("length"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_validate_vision_input_allowed_mimes() {
    let ok = validate_vision_input(&["a".into()], &["image/jpeg".into()]);
    assert!(ok.is_ok());
    for mime in &["image/png", "image/gif", "image/webp"] {
        assert!(
            validate_vision_input(&["a".into()], &[(*mime).into()]).is_ok(),
            "expected {mime} to be allowed"
        );
    }
}

#[test]
fn test_validate_vision_input_rejects_bad_mime() {
    let result = validate_vision_input(&["a".into()], &["image/svg+xml".into()]);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::InvalidArgument { detail } => {
            assert!(detail.contains("unsupported MIME type"));
            assert!(detail.contains("image/svg+xml"));
        }
        other => panic!("expected InvalidArgument, got {other:?}"),
    }
}

#[test]
fn test_validate_vision_input_rejects_arbitrary_mime() {
    let result = validate_vision_input(&["a".into()], &["application/pdf".into()]);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::InvalidArgument { detail } => {
            assert!(detail.contains("unsupported MIME type"));
        }
        other => panic!("expected InvalidArgument, got {other:?}"),
    }
}

// ── is_local_provider tests ───────────────────────────────────

#[test]
fn test_is_local_provider_ollama() {
    let p = VisionProvider::OpenAi {
        base_url: Some("http://localhost:11434/v1".into()),
    };
    assert!(is_local_provider(&p));
}

#[test]
fn test_is_local_provider_loopback() {
    let p = VisionProvider::OpenAi {
        base_url: Some("http://127.0.0.1:8000/v1".into()),
    };
    assert!(is_local_provider(&p));
}

#[test]
fn test_is_not_local_provider_cloud() {
    let p = VisionProvider::OpenAi {
        base_url: Some("https://api.openai.com/v1".into()),
    };
    assert!(!is_local_provider(&p));
}

#[test]
fn test_is_not_local_provider_anthropic() {
    let p = VisionProvider::Anthropic { base_url: None };
    assert!(!is_local_provider(&p));
}

// ── classify_provider vision support tests ───────────────────

#[test]
fn test_classify_provider_vision_supported() {
    assert!(classify_provider("anthropic").is_some());
    assert!(classify_provider("openai").is_some());
    assert!(classify_provider("gemini").is_some());
    assert!(classify_provider("ollama").is_some());
}

#[test]
fn test_classify_provider_vision_unsupported() {
    assert!(classify_provider("unknown-provider").is_none());
    assert!(classify_provider("").is_none());
}

#[test]
fn test_classify_provider_case_insensitive() {
    assert!(classify_provider("Anthropic").is_some());
    assert!(classify_provider("OPENAI").is_some());
    assert!(classify_provider("Gemini").is_some());
}
