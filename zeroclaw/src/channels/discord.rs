// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

use super::traits::{Channel, ChannelMessage, SendMessage};
use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use parking_lot::Mutex;
use reqwest::multipart::{Form, Part};
use serde_json::json;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock, RwLock};
use std::time::Duration;
use tokio_tungstenite::tungstenite::Message;
use uuid::Uuid;

static LINKED_DM_USER: OnceLock<RwLock<Option<String>>> = OnceLock::new();

fn linked_dm_user_state() -> &'static RwLock<Option<String>> {
    LINKED_DM_USER.get_or_init(|| RwLock::new(None))
}

/// Sets the runtime Discord DM-link target used for live DM routing.
///
/// The Android wrapper persists the durable source of truth separately and
/// replays it through this setter during daemon startup or when the user
/// changes the link at runtime.
pub fn set_linked_dm_user(user_id: Option<String>) {
    let mut guard = linked_dm_user_state()
        .write()
        .unwrap_or_else(|e| e.into_inner());
    *guard = user_id;
}

fn current_linked_dm_user() -> Option<String> {
    linked_dm_user_state()
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// Discord channel — connects via Gateway WebSocket for real-time messages
pub struct DiscordChannel {
    bot_token: String,
    guild_id: Option<String>,
    allowed_users: Vec<String>,
    listen_to_bots: bool,
    mention_only: bool,
    typing_handles: Mutex<HashMap<String, tokio::task::JoinHandle<()>>>,
    archive: Option<Arc<crate::memory::discord_archive::DiscordArchive>>,
    message_buffer: Option<Arc<super::discord_buffer::MessageBuffer>>,
    daemon_name: Option<String>,
    linked_dm_user: Option<String>,
    memory: Option<Arc<dyn crate::memory::Memory>>,
    /// Whether to send emoji reactions acknowledging received messages.
    ack_reactions: bool,
}

impl DiscordChannel {
    pub fn new(
        bot_token: String,
        guild_id: Option<String>,
        allowed_users: Vec<String>,
        listen_to_bots: bool,
        mention_only: bool,
        archive: Option<Arc<crate::memory::discord_archive::DiscordArchive>>,
        daemon_name: Option<String>,
        linked_dm_user: Option<String>,
        memory: Option<Arc<dyn crate::memory::Memory>>,
    ) -> Self {
        let message_buffer = archive
            .as_ref()
            .map(|_| Arc::new(super::discord_buffer::MessageBuffer::new(50)));
        Self {
            bot_token,
            guild_id,
            allowed_users,
            listen_to_bots,
            mention_only,
            typing_handles: Mutex::new(HashMap::new()),
            archive,
            message_buffer,
            daemon_name,
            linked_dm_user,
            memory,
            ack_reactions: true,
        }
    }

    /// Configure whether to send emoji reactions acknowledging received messages.
    pub fn with_ack_reactions(mut self, ack_reactions: bool) -> Self {
        self.ack_reactions = ack_reactions;
        self
    }

    fn http_client(&self) -> reqwest::Client {
        crate::config::build_runtime_proxy_client("channel.discord")
    }

    /// Check if a Discord user ID is in the allowlist.
    /// Empty list means allow everyone (bot was added to the server).
    /// `"*"` also means allow everyone (explicit wildcard).
    /// Non-empty list restricts to listed user IDs only.
    fn is_user_allowed(&self, user_id: &str) -> bool {
        self.allowed_users.is_empty() || self.allowed_users.iter().any(|u| u == "*" || u == user_id)
    }

    fn bot_user_id_from_token(token: &str) -> Option<String> {
        let part = token.split('.').next()?;
        base64_decode(part)
    }
}

/// Computes the Discord gateway intent bitfield.
///
/// Bits: `GUILDS` (0), `GUILD_MESSAGES` (9), `DIRECT_MESSAGES` (12),
/// `MESSAGE_CONTENT` (15, privileged — must be enabled in the Developer Portal).
fn discord_gateway_intents() -> u64 {
    const GUILDS: u64 = 1 << 0;
    const GUILD_MESSAGES: u64 = 1 << 9;
    const DIRECT_MESSAGES: u64 = 1 << 12;
    const MESSAGE_CONTENT: u64 = 1 << 15;
    GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES | MESSAGE_CONTENT
}

/// Returns a human-readable hint for a Discord gateway close code.
///
/// See <https://discord.com/developers/docs/topics/opcodes-and-status-codes#gateway-gateway-close-event-codes>.
fn discord_close_code_hint(code: u16) -> &'static str {
    match code {
        4000 => "Unknown error — try reconnecting",
        4001 => "Unknown opcode sent",
        4002 => "Failed to decode payload",
        4003 => "Not authenticated — sent payload before identifying",
        4004 => "Authentication failed — bot token is invalid or revoked",
        4005 => "Already authenticated",
        4007 => "Invalid sequence number on resume",
        4008 => "Rate limited — sending payloads too fast",
        4009 => "Session timed out",
        4010 => "Invalid shard",
        4011 => "Sharding required — bot is in too many guilds",
        4012 => "Invalid API version",
        4013 => "Invalid intents",
        4014 => "Disallowed intent(s) — enable MESSAGE_CONTENT in the Discord Developer Portal \
                 (Bot → Privileged Gateway Intents → Message Content Intent)",
        _ => "Unknown close code",
    }
}

/// Process Discord message attachments and return a string to append to the
/// agent message context.
///
/// Only `text/*` MIME types are fetched and inlined. All other types are
/// silently skipped. Fetch errors are logged as warnings.
async fn process_attachments(
    attachments: &[serde_json::Value],
    client: &reqwest::Client,
) -> String {
    let mut parts: Vec<String> = Vec::new();
    for att in attachments {
        let ct = att
            .get("content_type")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let name = att
            .get("filename")
            .and_then(|v| v.as_str())
            .unwrap_or("file");
        let Some(url) = att.get("url").and_then(|v| v.as_str()) else {
            tracing::warn!(name, "discord: attachment has no url, skipping");
            continue;
        };
        if ct.starts_with("text/") {
            match client.get(url).send().await {
                Ok(resp) if resp.status().is_success() => {
                    if let Ok(text) = resp.text().await {
                        parts.push(format!("[{name}]\n{text}"));
                    }
                }
                Ok(resp) => {
                    tracing::warn!(name, status = %resp.status(), "discord attachment fetch failed");
                }
                Err(e) => {
                    tracing::warn!(name, error = %e, "discord attachment fetch error");
                }
            }
        } else {
            tracing::debug!(
                name,
                content_type = ct,
                "discord: skipping unsupported attachment type"
            );
        }
    }
    parts.join("\n---\n")
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum DiscordAttachmentKind {
    Image,
    Document,
    Video,
    Audio,
    Voice,
}

impl DiscordAttachmentKind {
    fn from_marker(kind: &str) -> Option<Self> {
        match kind.trim().to_ascii_uppercase().as_str() {
            "IMAGE" | "PHOTO" => Some(Self::Image),
            "DOCUMENT" | "FILE" => Some(Self::Document),
            "VIDEO" => Some(Self::Video),
            "AUDIO" => Some(Self::Audio),
            "VOICE" => Some(Self::Voice),
            _ => None,
        }
    }

    fn marker_name(&self) -> &'static str {
        match self {
            Self::Image => "IMAGE",
            Self::Document => "DOCUMENT",
            Self::Video => "VIDEO",
            Self::Audio => "AUDIO",
            Self::Voice => "VOICE",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DiscordAttachment {
    kind: DiscordAttachmentKind,
    target: String,
}

fn parse_attachment_markers(message: &str) -> (String, Vec<DiscordAttachment>) {
    let mut cleaned = String::with_capacity(message.len());
    let mut attachments = Vec::new();
    let mut cursor = 0usize;

    while let Some(rel_start) = message[cursor..].find('[') {
        let start = cursor + rel_start;
        cleaned.push_str(&message[cursor..start]);

        let Some(rel_end) = message[start..].find(']') else {
            cleaned.push_str(&message[start..]);
            cursor = message.len();
            break;
        };
        let end = start + rel_end;
        let marker_text = &message[start + 1..end];

        let parsed = marker_text.split_once(':').and_then(|(kind, target)| {
            let kind = DiscordAttachmentKind::from_marker(kind)?;
            let target = target.trim();
            if target.is_empty() {
                return None;
            }
            Some(DiscordAttachment {
                kind,
                target: target.to_string(),
            })
        });

        if let Some(attachment) = parsed {
            attachments.push(attachment);
        } else {
            cleaned.push_str(&message[start..=end]);
        }

        cursor = end + 1;
    }

    if cursor < message.len() {
        cleaned.push_str(&message[cursor..]);
    }

    (cleaned.trim().to_string(), attachments)
}

fn classify_outgoing_attachments(
    attachments: &[DiscordAttachment],
) -> (Vec<PathBuf>, Vec<String>, Vec<String>) {
    let mut local_files = Vec::new();
    let mut remote_urls = Vec::new();
    let mut unresolved_markers = Vec::new();

    for attachment in attachments {
        let target = attachment.target.trim();
        if target.starts_with("https://") || target.starts_with("http://") {
            remote_urls.push(target.to_string());
            continue;
        }

        let path = Path::new(target);
        if path.exists() && path.is_file() {
            local_files.push(path.to_path_buf());
            continue;
        }

        unresolved_markers.push(format!("[{}:{}]", attachment.kind.marker_name(), target));
    }

    (local_files, remote_urls, unresolved_markers)
}

fn with_inline_attachment_urls(
    content: &str,
    remote_urls: &[String],
    unresolved_markers: &[String],
) -> String {
    let mut lines = Vec::new();
    if !content.trim().is_empty() {
        lines.push(content.trim().to_string());
    }
    if !remote_urls.is_empty() {
        lines.extend(remote_urls.iter().cloned());
    }
    if !unresolved_markers.is_empty() {
        lines.extend(unresolved_markers.iter().cloned());
    }
    lines.join("\n")
}

async fn send_discord_message_json(
    client: &reqwest::Client,
    bot_token: &str,
    recipient: &str,
    content: &str,
) -> anyhow::Result<()> {
    let url = format!("https://discord.com/api/v10/channels/{recipient}/messages");
    let body = json!({ "content": content });

    let resp = client
        .post(&url)
        .header("Authorization", format!("Bot {bot_token}"))
        .json(&body)
        .send()
        .await?;

    if !resp.status().is_success() {
        let status = resp.status();
        let err = resp
            .text()
            .await
            .unwrap_or_else(|e| format!("<failed to read response body: {e}>"));
        anyhow::bail!("Discord send message failed ({status}): {err}");
    }

    Ok(())
}

async fn send_discord_message_with_files(
    client: &reqwest::Client,
    bot_token: &str,
    recipient: &str,
    content: &str,
    files: &[PathBuf],
) -> anyhow::Result<()> {
    let url = format!("https://discord.com/api/v10/channels/{recipient}/messages");

    let mut form = Form::new().text("payload_json", json!({ "content": content }).to_string());

    for (idx, path) in files.iter().enumerate() {
        let bytes = tokio::fs::read(path).await.map_err(|error| {
            anyhow::anyhow!(
                "Discord attachment read failed for '{}': {error}",
                path.display()
            )
        })?;
        let filename = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("attachment.bin")
            .to_string();
        form = form.part(
            format!("files[{idx}]"),
            Part::bytes(bytes).file_name(filename),
        );
    }

    let resp = client
        .post(&url)
        .header("Authorization", format!("Bot {bot_token}"))
        .multipart(form)
        .send()
        .await?;

    if !resp.status().is_success() {
        let status = resp.status();
        let err = resp
            .text()
            .await
            .unwrap_or_else(|e| format!("<failed to read response body: {e}>"));
        anyhow::bail!("Discord send message with files failed ({status}): {err}");
    }

    Ok(())
}

const BASE64_ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Discord's maximum message length for regular messages.
///
/// Discord rejects longer payloads with `50035 Invalid Form Body`.
const DISCORD_MAX_MESSAGE_LENGTH: usize = 2000;
const DISCORD_ACK_REACTIONS: &[&str] = &["⚡️", "🦀", "🙌", "💪", "👌", "👀", "👣"];

/// Split a message into chunks that respect Discord's 2000-character limit.
/// Tries to split at word boundaries when possible.
fn split_message_for_discord(message: &str) -> Vec<String> {
    if message.chars().count() <= DISCORD_MAX_MESSAGE_LENGTH {
        return vec![message.to_string()];
    }

    let mut chunks = Vec::new();
    let mut remaining = message;

    while !remaining.is_empty() {
        let hard_split = remaining
            .char_indices()
            .nth(DISCORD_MAX_MESSAGE_LENGTH)
            .map_or(remaining.len(), |(idx, _)| idx);

        let chunk_end = if hard_split == remaining.len() {
            hard_split
        } else {
            let search_area = &remaining[..hard_split];

            if let Some(pos) = search_area.rfind('\n') {
                if search_area[..pos].chars().count() >= DISCORD_MAX_MESSAGE_LENGTH / 2 {
                    pos + 1
                } else {
                    search_area.rfind(' ').map_or(hard_split, |space| space + 1)
                }
            } else if let Some(pos) = search_area.rfind(' ') {
                pos + 1
            } else {
                hard_split
            }
        };

        chunks.push(remaining[..chunk_end].to_string());
        remaining = &remaining[chunk_end..];
    }

    chunks
}

fn pick_uniform_index(len: usize) -> usize {
    debug_assert!(len > 0);
    let upper = len as u64;
    let reject_threshold = (u64::MAX / upper) * upper;

    loop {
        let value = rand::random::<u64>();
        if value < reject_threshold {
            return (value % upper) as usize;
        }
    }
}

fn random_discord_ack_reaction() -> &'static str {
    DISCORD_ACK_REACTIONS[pick_uniform_index(DISCORD_ACK_REACTIONS.len())]
}

/// URL-encode a Unicode emoji for use in Discord reaction API paths.
///
/// Discord's reaction endpoints accept raw Unicode emoji in the URL path,
/// but they must be percent-encoded per RFC 3986. Custom guild emojis use
/// the `name:id` format and are passed through unencoded.
fn encode_emoji_for_discord(emoji: &str) -> String {
    if emoji.contains(':') {
        return emoji.to_string();
    }

    let mut encoded = String::new();
    for byte in emoji.as_bytes() {
        encoded.push_str(&format!("%{byte:02X}"));
    }
    encoded
}

fn discord_reaction_url(channel_id: &str, message_id: &str, emoji: &str) -> String {
    let raw_id = message_id.strip_prefix("discord_").unwrap_or(message_id);
    let encoded_emoji = encode_emoji_for_discord(emoji);
    format!(
        "https://discord.com/api/v10/channels/{channel_id}/messages/{raw_id}/reactions/{encoded_emoji}/@me"
    )
}

fn mention_tags(bot_user_id: &str) -> [String; 2] {
    [format!("<@{bot_user_id}>"), format!("<@!{bot_user_id}>")]
}

fn contains_bot_mention(content: &str, bot_user_id: &str) -> bool {
    let tags = mention_tags(bot_user_id);
    content.contains(&tags[0]) || content.contains(&tags[1])
}

/// Check if `content` contains the daemon name as a whole word (case-insensitive).
///
/// Uses `match_indices` on lowered strings and checks adjacent bytes for word
/// boundaries — a match only counts when the character before and after the
/// occurrence is non-alphanumeric (or absent, i.e. start/end of string).
fn matches_daemon_name(content: &str, daemon_name: &str) -> bool {
    if daemon_name.is_empty() {
        return false;
    }
    let lower_content = content.to_lowercase();
    let lower_name = daemon_name.to_lowercase();
    for (start, _) in lower_content.match_indices(&lower_name) {
        let end = start + lower_name.len();
        let before_ok = start == 0 || !lower_content.as_bytes()[start - 1].is_ascii_alphanumeric();
        let after_ok =
            end == lower_content.len() || !lower_content.as_bytes()[end].is_ascii_alphanumeric();
        if before_ok && after_ok {
            return true;
        }
    }
    false
}

fn normalize_incoming_content(
    content: &str,
    mention_only: bool,
    bot_user_id: &str,
) -> Option<String> {
    if content.is_empty() {
        return None;
    }

    if mention_only && !contains_bot_mention(content, bot_user_id) {
        return None;
    }

    let mut normalized = content.to_string();
    if mention_only {
        for tag in mention_tags(bot_user_id) {
            normalized = normalized.replace(&tag, " ");
        }
    }

    let normalized = normalized.trim().to_string();
    if normalized.is_empty() {
        return None;
    }

    Some(normalized)
}

/// Minimal base64 decode (no extra dep) — only needs to decode the user ID portion
#[allow(clippy::cast_possible_truncation)]
fn base64_decode(input: &str) -> Option<String> {
    let padded = match input.len() % 4 {
        2 => format!("{input}=="),
        3 => format!("{input}="),
        _ => input.to_string(),
    };

    let mut bytes = Vec::new();
    let chars: Vec<u8> = padded.bytes().collect();

    for chunk in chars.chunks(4) {
        if chunk.len() < 4 {
            break;
        }

        let mut v = [0usize; 4];
        for (i, &b) in chunk.iter().enumerate() {
            if b == b'=' {
                v[i] = 0;
            } else {
                v[i] = BASE64_ALPHABET.iter().position(|&a| a == b)?;
            }
        }

        bytes.push(((v[0] << 2) | (v[1] >> 4)) as u8);
        if chunk[2] != b'=' {
            bytes.push((((v[1] & 0xF) << 4) | (v[2] >> 2)) as u8);
        }
        if chunk[3] != b'=' {
            bytes.push((((v[2] & 0x3) << 6) | v[3]) as u8);
        }
    }

    String::from_utf8(bytes).ok()
}

#[async_trait]
impl Channel for DiscordChannel {
    fn name(&self) -> &str {
        "discord"
    }

    async fn send(&self, message: &SendMessage) -> anyhow::Result<()> {
        let raw_content = super::sanitize_outbound_text(&message.content);
        let (cleaned_content, parsed_attachments) = parse_attachment_markers(&raw_content);
        let (mut local_files, remote_urls, unresolved_markers) =
            classify_outgoing_attachments(&parsed_attachments);

        if !unresolved_markers.is_empty() {
            tracing::warn!(
                unresolved = ?unresolved_markers,
                "discord: unresolved attachment markers were sent as plain text"
            );
        }

        if local_files.len() > 10 {
            tracing::warn!(
                count = local_files.len(),
                "discord: truncating local attachment upload list to 10 files"
            );
            local_files.truncate(10);
        }

        let content =
            with_inline_attachment_urls(&cleaned_content, &remote_urls, &unresolved_markers);
        let chunks = split_message_for_discord(&content);
        let client = self.http_client();

        for (i, chunk) in chunks.iter().enumerate() {
            if i == 0 && !local_files.is_empty() {
                send_discord_message_with_files(
                    &client,
                    &self.bot_token,
                    &message.recipient,
                    chunk,
                    &local_files,
                )
                .await?;
            } else {
                send_discord_message_json(&client, &self.bot_token, &message.recipient, chunk)
                    .await?;
            }

            if i < chunks.len() - 1 {
                tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            }
        }

        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn listen(&self, tx: tokio::sync::mpsc::Sender<ChannelMessage>) -> anyhow::Result<()> {
        let bot_user_id = Self::bot_user_id_from_token(&self.bot_token).unwrap_or_default();

        let gw_resp: serde_json::Value = {
            let mut attempts = 0u32;
            loop {
                let resp = self
                    .http_client()
                    .get("https://discord.com/api/v10/gateway/bot")
                    .header("Authorization", format!("Bot {}", self.bot_token))
                    .send()
                    .await?;

                let status = resp.status();
                if status == reqwest::StatusCode::TOO_MANY_REQUESTS {
                    attempts += 1;
                    if attempts > 5 {
                        anyhow::bail!("Discord rate limited 5 times in a row on /gateway/bot");
                    }
                    let body: serde_json::Value =
                        resp.json().await.unwrap_or_else(|_| json!({}));
                    let wait_secs = body
                        .get("retry_after")
                        .and_then(serde_json::Value::as_f64)
                        .unwrap_or(1.0)
                        .max(0.1);
                    tracing::warn!(
                        retry_after = wait_secs,
                        attempt = attempts,
                        "Discord rate limited on /gateway/bot, waiting"
                    );
                    tokio::time::sleep(Duration::from_secs_f64(wait_secs)).await;
                    continue;
                }

                if !status.is_success() {
                    let body = resp.text().await.unwrap_or_default();
                    anyhow::bail!(
                        "Discord gateway/bot returned HTTP {status}: {body}. \
                         Check that the bot token is valid and the bot has not been deleted."
                    );
                }

                break resp.json().await?;
            }
        };

        let gw_url = gw_resp
            .get("url")
            .and_then(|u| u.as_str())
            .unwrap_or("wss://gateway.discord.gg");

        let ws_url = format!("{gw_url}/?v=10&encoding=json");
        tracing::info!("Discord: connecting to gateway...");

        let (ws_stream, _) = tokio_tungstenite::connect_async(&ws_url).await?;
        let (mut write, mut read) = ws_stream.split();

        let hello = read.next().await.ok_or(anyhow::anyhow!("No hello"))??;
        let hello_data: serde_json::Value = serde_json::from_str(&hello.to_string())?;
        let heartbeat_interval = hello_data
            .get("d")
            .and_then(|d| d.get("heartbeat_interval"))
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(41250);

        let intents = discord_gateway_intents();
        let identify = json!({
            "op": 2,
            "d": {
                "token": self.bot_token,
                "intents": intents,
                "properties": {
                    "os": "linux",
                    "browser": "zeroclaw",
                    "device": "zeroclaw"
                }
            }
        });
        write
            .send(Message::Text(identify.to_string().into()))
            .await?;

        tracing::info!("Discord: connected and identified");

        let mut sequence: i64 = -1;

        let (hb_tx, mut hb_rx) = tokio::sync::mpsc::channel::<()>(1);
        let hb_interval = heartbeat_interval;
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(std::time::Duration::from_millis(hb_interval));
            loop {
                interval.tick().await;
                if hb_tx.send(()).await.is_err() {
                    break;
                }
            }
        });

        let guild_filter = self.guild_id.clone();

        let mut flush_interval = tokio::time::interval(Duration::from_secs(60));

        let mut cached_enabled_ids: HashSet<String> = self
            .archive
            .as_ref()
            .and_then(|a| a.enabled_channel_ids().ok())
            .map(|v| v.into_iter().collect())
            .unwrap_or_default();

        loop {
            tokio::select! {
                _ = hb_rx.recv() => {
                    let d = if sequence >= 0 { json!(sequence) } else { json!(null) };
                    let hb = json!({"op": 1, "d": d});
                    if write.send(Message::Text(hb.to_string().into())).await.is_err() {
                        break;
                    }
                }
                _ = flush_interval.tick() => {
                    if let (Some(archive), Some(buffer)) = (&self.archive, &self.message_buffer) {
                        if let Ok(ids) = archive.enabled_channel_ids() {
                            cached_enabled_ids = ids.into_iter().collect();
                        }
                        if !buffer.is_empty() {
                            let msgs = buffer.drain();
                            let archive_ref = Arc::clone(archive);
                            tokio::spawn(async move {
                                let _ = tokio::task::spawn_blocking(move || {
                                    if let Err(e) = archive_ref.store_messages(&msgs) {
                                        tracing::warn!("discord archive periodic flush failed: {e}");
                                    }
                                }).await;
                            });
                        }
                    }
                }
                msg = read.next() => {
                    let msg = match msg {
                        Some(Ok(Message::Text(t))) => t,
                        Some(Ok(Message::Close(frame))) => {
                            if let Some(ref f) = frame {
                                let hint = discord_close_code_hint(f.code.into());
                                tracing::error!(
                                    code = u16::from(f.code),
                                    reason = %f.reason,
                                    "Discord gateway closed: {hint}"
                                );
                                anyhow::bail!(
                                    "Discord gateway closed with code {}: {hint}",
                                    u16::from(f.code)
                                );
                            }
                            tracing::warn!("Discord gateway closed without a close frame");
                            break;
                        }
                        None => break,
                        Some(Err(e)) => {
                            tracing::warn!("Discord WebSocket error: {e}");
                            continue;
                        }
                        _ => continue,
                    };

                    let event: serde_json::Value = match serde_json::from_str(msg.as_ref()) {
                        Ok(e) => e,
                        Err(_) => continue,
                    };

                    if let Some(s) = event.get("s").and_then(serde_json::Value::as_i64) {
                        sequence = s;
                    }

                    let op = event.get("op").and_then(serde_json::Value::as_u64).unwrap_or(0);

                    match op {
                        1 => {
                            let d = if sequence >= 0 { json!(sequence) } else { json!(null) };
                            let hb = json!({"op": 1, "d": d});
                            if write.send(Message::Text(hb.to_string().into())).await.is_err() {
                                break;
                            }
                            continue;
                        }
                        7 => {
                            tracing::warn!("Discord: received Reconnect (op 7), closing for restart");
                            break;
                        }
                        9 => {
                            tracing::error!(
                                "Discord: received Invalid Session (op 9). \
                                 The bot token may be invalid or revoked."
                            );
                            anyhow::bail!(
                                "Discord rejected session (op 9): token may be invalid or revoked"
                            );
                        }
                        _ => {}
                    }

                    let event_type = event.get("t").and_then(|t| t.as_str()).unwrap_or("");

                    if event_type == "READY" {
                        let username = event
                            .get("d")
                            .and_then(|d| d.get("user"))
                            .and_then(|u| u.get("username"))
                            .and_then(serde_json::Value::as_str)
                            .unwrap_or("unknown");
                        tracing::info!("Discord: bot is online as @{username}");
                    }

                    if event_type != "MESSAGE_CREATE" {
                        continue;
                    }

                    let Some(d) = event.get("d") else {
                        continue;
                    };

                    let author_id = d.get("author").and_then(|a| a.get("id")).and_then(|i| i.as_str()).unwrap_or("");
                    if author_id == bot_user_id {
                        continue;
                    }

                    if !self.listen_to_bots && d.get("author").and_then(|a| a.get("bot")).and_then(serde_json::Value::as_bool).unwrap_or(false) {
                        continue;
                    }

                    if !self.is_user_allowed(author_id) {
                        tracing::warn!("Discord: ignoring message from unauthorized user: {author_id}");
                        continue;
                    }

                    let msg_guild = d.get("guild_id").and_then(serde_json::Value::as_str);
                    let in_guild = msg_guild.is_some() && msg_guild != Some("");
                    if in_guild {
                        match guild_filter.as_deref() {
                            None => continue,
                            Some(gid) if msg_guild != Some(gid) => continue,
                            _ => {}
                        }
                    }

                    let content = d.get("content").and_then(|c| c.as_str()).unwrap_or("");
                    let author_name = d
                        .get("author")
                        .and_then(|a| a.get("username"))
                        .and_then(|u| u.as_str())
                        .unwrap_or("");
                    let message_id = d.get("id").and_then(|i| i.as_str()).unwrap_or("");
                    let channel_id = d
                        .get("channel_id")
                        .and_then(|c| c.as_str())
                        .unwrap_or("")
                        .to_string();
                    let guild_id_str = d
                        .get("guild_id")
                        .and_then(serde_json::Value::as_str);

                    let is_dm = guild_id_str.is_none() || guild_id_str == Some("");
                    if is_dm {
                        if let Some(ref linked_user) =
                            self.linked_dm_user.clone().or_else(current_linked_dm_user)
                        {
                            if author_id == linked_user {
                                if let Some(ref memory) = self.memory {
                                    let key = format!("dm_{}_{}", author_id, message_id);
                                    let dm_content = format!("{}: {}", author_name, content);
                                    let _ = memory.store(
                                        &key,
                                        &dm_content,
                                        crate::memory::MemoryCategory::Custom("direct_message".into()),
                                        None,
                                    ).await;
                                }
                            }
                        }
                    }

                    if let (Some(archive), Some(buffer)) = (&self.archive, &self.message_buffer) {
                        if cached_enabled_ids.contains(&*channel_id) {
                            buffer.push(crate::memory::discord_archive::ArchiveMessage {
                                id: message_id.to_string(),
                                channel_id: channel_id.to_string(),
                                guild_id: guild_id_str.unwrap_or("dm").to_string(),
                                author_id: author_id.to_string(),
                                author_name: author_name.to_string(),
                                content: content.to_string(),
                                timestamp: chrono::Utc::now().timestamp(),
                            });
                            if buffer.should_flush() {
                                let msgs = buffer.drain();
                                let archive_ref = Arc::clone(archive);
                                tokio::spawn(async move {
                                    let _ = tokio::task::spawn_blocking(move || {
                                        if let Err(e) = archive_ref.store_messages(&msgs) {
                                            tracing::warn!("discord archive flush failed: {e}");
                                        }
                                    }).await;
                                });
                            }
                        }
                    }

                    let has_bot_mention = contains_bot_mention(content, &bot_user_id);
                    let has_name_mention = self.daemon_name.as_ref().map_or(false, |name| {
                        matches_daemon_name(content, name)
                    });
                    let mentioned = has_bot_mention || has_name_mention;

                    let should_respond = if is_dm {
                        true
                    } else {
                        mentioned
                    };

                    if !should_respond {
                        continue;
                    }

                    let linked_user_id =
                        self.linked_dm_user.clone().or_else(current_linked_dm_user);
                    let sender_is_paired = linked_user_id
                        .as_deref()
                        .map_or(false, |lu| lu == author_id);

                    if !is_dm && !sender_is_paired {
                        super::traits::mark_tools_restricted(&format!("discord_{message_id}"));
                    }

                    let Some(clean_content) =
                        normalize_incoming_content(
                            content,
                            has_bot_mention && !is_dm,
                            &bot_user_id,
                        )
                    else {
                        continue;
                    };

                    let attachment_text = {
                        let atts = d
                            .get("attachments")
                            .and_then(|a| a.as_array())
                            .cloned()
                            .unwrap_or_default();
                        process_attachments(&atts, &self.http_client()).await
                    };
                    let final_content = if attachment_text.is_empty() {
                        clean_content
                    } else {
                        format!("{clean_content}\n\n[Attachments]\n{attachment_text}")
                    };

                    if self.ack_reactions
                        && !message_id.is_empty()
                        && !channel_id.is_empty()
                    {
                        let reaction_channel = DiscordChannel::new(
                            self.bot_token.clone(),
                            self.guild_id.clone(),
                            self.allowed_users.clone(),
                            self.listen_to_bots,
                            self.mention_only,
                            None,
                            None,
                            None,
                            None,
                        );
                        let reaction_channel_id = channel_id.clone();
                        let reaction_message_id = message_id.to_string();
                        let reaction_emoji = random_discord_ack_reaction().to_string();
                        tokio::spawn(async move {
                            if let Err(err) = reaction_channel
                                .add_reaction(
                                    &reaction_channel_id,
                                    &reaction_message_id,
                                    &reaction_emoji,
                                )
                                .await
                            {
                                tracing::debug!(
                                    "Discord: failed to add ACK reaction for message {reaction_message_id}: {err}"
                                );
                            }
                        });
                    }

                    let channel_msg = ChannelMessage {
                        id: if message_id.is_empty() {
                            Uuid::new_v4().to_string()
                        } else {
                            format!("discord_{message_id}")
                        },
                        sender: author_id.to_string(),
                        reply_target: if channel_id.is_empty() {
                            author_id.to_string()
                        } else {
                            channel_id.clone()
                        },
                        content: final_content,
                        channel: "discord".to_string(),
                        timestamp: std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_secs(),
                        thread_ts: None,
                    };

                    if tx.send(channel_msg).await.is_err() {
                        break;
                    }
                }
            }
        }

        Ok(())
    }

    async fn health_check(&self) -> bool {
        self.http_client()
            .get("https://discord.com/api/v10/users/@me")
            .header("Authorization", format!("Bot {}", self.bot_token))
            .send()
            .await
            .map(|r| r.status().is_success())
            .unwrap_or(false)
    }

    async fn start_typing(&self, recipient: &str) -> anyhow::Result<()> {
        self.stop_typing(recipient).await?;

        let client = self.http_client();
        let token = self.bot_token.clone();
        let channel_id = recipient.to_string();

        let handle = tokio::spawn(async move {
            let url = format!("https://discord.com/api/v10/channels/{channel_id}/typing");
            loop {
                let _ = client
                    .post(&url)
                    .header("Authorization", format!("Bot {token}"))
                    .send()
                    .await;
                tokio::time::sleep(std::time::Duration::from_secs(8)).await;
            }
        });

        let mut guard = self.typing_handles.lock();
        guard.insert(recipient.to_string(), handle);

        Ok(())
    }

    async fn stop_typing(&self, recipient: &str) -> anyhow::Result<()> {
        let mut guard = self.typing_handles.lock();
        if let Some(handle) = guard.remove(recipient) {
            handle.abort();
        }
        Ok(())
    }

    async fn add_reaction(
        &self,
        channel_id: &str,
        message_id: &str,
        emoji: &str,
    ) -> anyhow::Result<()> {
        let url = discord_reaction_url(channel_id, message_id, emoji);

        let resp = self
            .http_client()
            .put(&url)
            .header("Authorization", format!("Bot {}", self.bot_token))
            .header("Content-Length", "0")
            .send()
            .await?;

        if !resp.status().is_success() {
            let status = resp.status();
            let err = resp
                .text()
                .await
                .unwrap_or_else(|e| format!("<failed to read response body: {e}>"));
            anyhow::bail!("Discord add reaction failed ({status}): {err}");
        }

        Ok(())
    }

    async fn remove_reaction(
        &self,
        channel_id: &str,
        message_id: &str,
        emoji: &str,
    ) -> anyhow::Result<()> {
        let url = discord_reaction_url(channel_id, message_id, emoji);

        let resp = self
            .http_client()
            .delete(&url)
            .header("Authorization", format!("Bot {}", self.bot_token))
            .send()
            .await?;

        if !resp.status().is_success() {
            let status = resp.status();
            let err = resp
                .text()
                .await
                .unwrap_or_else(|e| format!("<failed to read response body: {e}>"));
            anyhow::bail!("Discord remove reaction failed ({status}): {err}");
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discord_channel_name() {
        set_linked_dm_user(None);
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec![],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert_eq!(ch.name(), "discord");
    }

    #[test]
    fn runtime_linked_dm_user_can_be_updated() {
        set_linked_dm_user(Some("123".into()));
        assert_eq!(current_linked_dm_user().as_deref(), Some("123"));
        set_linked_dm_user(None);
        assert!(current_linked_dm_user().is_none());
    }

    #[test]
    fn base64_decode_bot_id() {
        let decoded = base64_decode("MTIzNDU2");
        assert_eq!(decoded, Some("123456".to_string()));
    }

    #[test]
    fn bot_user_id_extraction() {
        let token = "MTIzNDU2.fake.hmac";
        let id = DiscordChannel::bot_user_id_from_token(token);
        assert_eq!(id, Some("123456".to_string()));
    }

    #[test]
    fn empty_allowlist_denies_everyone() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec![],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert!(!ch.is_user_allowed("12345"));
        assert!(!ch.is_user_allowed("anyone"));
    }

    #[test]
    fn wildcard_allows_everyone() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec!["*".into()],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert!(ch.is_user_allowed("12345"));
        assert!(ch.is_user_allowed("anyone"));
    }

    #[test]
    fn specific_allowlist_filters() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec!["111".into(), "222".into()],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert!(ch.is_user_allowed("111"));
        assert!(ch.is_user_allowed("222"));
        assert!(!ch.is_user_allowed("333"));
        assert!(!ch.is_user_allowed("unknown"));
    }

    #[test]
    fn allowlist_is_exact_match_not_substring() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec!["111".into()],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert!(!ch.is_user_allowed("1111"));
        assert!(!ch.is_user_allowed("11"));
        assert!(!ch.is_user_allowed("0111"));
    }

    #[test]
    fn allowlist_empty_string_user_id() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec!["111".into()],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert!(!ch.is_user_allowed(""));
    }

    #[test]
    fn allowlist_with_wildcard_and_specific() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec!["111".into(), "*".into()],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert!(ch.is_user_allowed("111"));
        assert!(ch.is_user_allowed("anyone_else"));
    }

    #[test]
    fn allowlist_case_sensitive() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec!["ABC".into()],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert!(ch.is_user_allowed("ABC"));
        assert!(!ch.is_user_allowed("abc"));
        assert!(!ch.is_user_allowed("Abc"));
    }

    #[test]
    fn base64_decode_empty_string() {
        let decoded = base64_decode("");
        assert_eq!(decoded, Some(String::new()));
    }

    #[test]
    fn base64_decode_invalid_chars() {
        let decoded = base64_decode("!!!!");
        assert!(decoded.is_none());
    }

    #[test]
    fn bot_user_id_from_empty_token() {
        let id = DiscordChannel::bot_user_id_from_token("");
        assert_eq!(id, Some(String::new()));
    }

    #[test]
    fn contains_bot_mention_supports_plain_and_nick_forms() {
        assert!(contains_bot_mention("hi <@12345>", "12345"));
        assert!(contains_bot_mention("hi <@!12345>", "12345"));
        assert!(!contains_bot_mention("hi <@99999>", "12345"));
    }

    #[test]
    fn normalize_incoming_content_requires_mention_when_enabled() {
        let cleaned = normalize_incoming_content("hello there", true, "12345");
        assert!(cleaned.is_none());
    }

    #[test]
    fn normalize_incoming_content_strips_mentions_and_trims() {
        let cleaned = normalize_incoming_content("  <@!12345> run status  ", true, "12345");
        assert_eq!(cleaned.as_deref(), Some("run status"));
    }

    #[test]
    fn normalize_incoming_content_rejects_empty_after_strip() {
        let cleaned = normalize_incoming_content("<@12345>", true, "12345");
        assert!(cleaned.is_none());
    }

    #[test]
    fn split_empty_message() {
        let chunks = split_message_for_discord("");
        assert_eq!(chunks, vec![""]);
    }

    #[test]
    fn split_short_message_under_limit() {
        let msg = "Hello, world!";
        let chunks = split_message_for_discord(msg);
        assert_eq!(chunks, vec![msg]);
    }

    #[test]
    fn split_message_exactly_2000_chars() {
        let msg = "a".repeat(DISCORD_MAX_MESSAGE_LENGTH);
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0].chars().count(), DISCORD_MAX_MESSAGE_LENGTH);
    }

    #[test]
    fn split_message_just_over_limit() {
        let msg = "a".repeat(DISCORD_MAX_MESSAGE_LENGTH + 1);
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 2);
        assert_eq!(chunks[0].chars().count(), DISCORD_MAX_MESSAGE_LENGTH);
        assert_eq!(chunks[1].chars().count(), 1);
    }

    #[test]
    fn split_very_long_message() {
        let msg = "word ".repeat(2000);
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 5);
        assert!(chunks
            .iter()
            .all(|chunk| chunk.chars().count() <= DISCORD_MAX_MESSAGE_LENGTH));
        let reconstructed = chunks.concat();
        assert_eq!(reconstructed, msg);
    }

    #[test]
    fn split_prefer_newline_break() {
        let msg = format!("{}\n{}", "a".repeat(1500), "b".repeat(500));
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 2);
        assert!(chunks[0].ends_with('\n'));
        assert!(chunks[1].starts_with('b'));
    }

    #[test]
    fn split_prefer_space_break() {
        let msg = format!("{} {}", "a".repeat(1500), "b".repeat(600));
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 2);
    }

    #[test]
    fn split_without_good_break_points_hard_split() {
        let msg = "a".repeat(5000);
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 3);
        assert_eq!(chunks[0].chars().count(), DISCORD_MAX_MESSAGE_LENGTH);
        assert_eq!(chunks[1].chars().count(), DISCORD_MAX_MESSAGE_LENGTH);
        assert_eq!(chunks[2].chars().count(), 1000);
    }

    #[test]
    fn split_multiple_breaks() {
        let part1 = "a".repeat(900);
        let part2 = "b".repeat(900);
        let part3 = "c".repeat(900);
        let msg = format!("{part1}\n{part2}\n{part3}");
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 2);
        assert!(chunks[0].chars().count() <= DISCORD_MAX_MESSAGE_LENGTH);
        assert!(chunks[1].chars().count() <= DISCORD_MAX_MESSAGE_LENGTH);
    }

    #[test]
    fn split_preserves_content() {
        let original = "Hello world! This is a test message with some content. ".repeat(200);
        let chunks = split_message_for_discord(&original);
        let reconstructed = chunks.concat();
        assert_eq!(reconstructed, original);
    }

    #[test]
    fn split_unicode_content() {
        let msg = "🦀 Rust is awesome! ".repeat(500);
        let chunks = split_message_for_discord(&msg);
        for chunk in &chunks {
            assert!(std::str::from_utf8(chunk.as_bytes()).is_ok());
            assert!(chunk.chars().count() <= DISCORD_MAX_MESSAGE_LENGTH);
        }
        let reconstructed = chunks.concat();
        assert_eq!(reconstructed, msg);
    }

    #[test]
    fn split_newline_too_close_to_end() {
        let msg = format!("{}\n{}", "a".repeat(1900), "b".repeat(500));
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 2);
    }

    #[test]
    fn split_multibyte_only_content_without_panics() {
        let msg = "🦀".repeat(2500);
        let chunks = split_message_for_discord(&msg);
        assert_eq!(chunks.len(), 2);
        assert_eq!(chunks[0].chars().count(), DISCORD_MAX_MESSAGE_LENGTH);
        assert_eq!(chunks[1].chars().count(), 500);
        let reconstructed = chunks.concat();
        assert_eq!(reconstructed, msg);
    }

    #[test]
    fn split_chunks_always_within_discord_limit() {
        let msg = "x".repeat(12_345);
        let chunks = split_message_for_discord(&msg);
        assert!(chunks
            .iter()
            .all(|chunk| chunk.chars().count() <= DISCORD_MAX_MESSAGE_LENGTH));
    }

    #[test]
    fn split_message_with_multiple_newlines() {
        let msg = "Line 1\nLine 2\nLine 3\n".repeat(1000);
        let chunks = split_message_for_discord(&msg);
        assert!(chunks.len() > 1);
        let reconstructed = chunks.concat();
        assert_eq!(reconstructed, msg);
    }

    #[test]
    fn typing_handles_start_empty() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec![],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        let guard = ch.typing_handles.lock();
        assert!(guard.is_empty());
    }

    #[tokio::test]
    async fn start_typing_sets_handle() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec![],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        let _ = ch.start_typing("123456").await;
        let guard = ch.typing_handles.lock();
        assert!(guard.contains_key("123456"));
    }

    #[tokio::test]
    async fn stop_typing_clears_handle() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec![],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        let _ = ch.start_typing("123456").await;
        let _ = ch.stop_typing("123456").await;
        let guard = ch.typing_handles.lock();
        assert!(!guard.contains_key("123456"));
    }

    #[tokio::test]
    async fn stop_typing_is_idempotent() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec![],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        assert!(ch.stop_typing("123456").await.is_ok());
        assert!(ch.stop_typing("123456").await.is_ok());
    }

    #[tokio::test]
    async fn concurrent_typing_handles_are_independent() {
        let ch = DiscordChannel::new(
            "fake".into(),
            None,
            vec![],
            false,
            false,
            None,
            None,
            None,
            None,
        );
        let _ = ch.start_typing("111").await;
        let _ = ch.start_typing("222").await;
        {
            let guard = ch.typing_handles.lock();
            assert_eq!(guard.len(), 2);
            assert!(guard.contains_key("111"));
            assert!(guard.contains_key("222"));
        }
        let _ = ch.stop_typing("111").await;
        let guard = ch.typing_handles.lock();
        assert_eq!(guard.len(), 1);
        assert!(guard.contains_key("222"));
    }

    #[test]
    fn encode_emoji_unicode_percent_encodes() {
        let encoded = encode_emoji_for_discord("\u{1F440}");
        assert_eq!(encoded, "%F0%9F%91%80");
    }

    #[test]
    fn encode_emoji_checkmark() {
        let encoded = encode_emoji_for_discord("\u{2705}");
        assert_eq!(encoded, "%E2%9C%85");
    }

    #[test]
    fn encode_emoji_custom_guild_emoji_passthrough() {
        let encoded = encode_emoji_for_discord("custom_emoji:123456789");
        assert_eq!(encoded, "custom_emoji:123456789");
    }

    #[test]
    fn encode_emoji_simple_ascii_char() {
        let encoded = encode_emoji_for_discord("A");
        assert_eq!(encoded, "%41");
    }

    #[test]
    fn random_discord_ack_reaction_is_from_pool() {
        for _ in 0..128 {
            let emoji = random_discord_ack_reaction();
            assert!(DISCORD_ACK_REACTIONS.contains(&emoji));
        }
    }

    #[test]
    fn discord_reaction_url_encodes_emoji_and_strips_prefix() {
        let url = discord_reaction_url("123", "discord_456", "👀");
        assert_eq!(
            url,
            "https://discord.com/api/v10/channels/123/messages/456/reactions/%F0%9F%91%80/@me"
        );
    }

    #[test]
    fn discord_message_id_format_includes_discord_prefix() {
        let message_id = "123456789012345678";
        let expected_id = format!("discord_{message_id}");
        assert_eq!(expected_id, "discord_123456789012345678");
    }

    #[test]
    fn discord_message_id_is_deterministic() {
        let message_id = "123456789012345678";
        let id1 = format!("discord_{message_id}");
        let id2 = format!("discord_{message_id}");
        assert_eq!(id1, id2);
    }

    #[test]
    fn discord_message_id_different_message_different_id() {
        let id1 = "discord_123456789012345678".to_string();
        let id2 = "discord_987654321098765432".to_string();
        assert_ne!(id1, id2);
    }

    #[test]
    fn discord_message_id_uses_snowflake_id() {
        let message_id = "123456789012345678";
        let id = format!("discord_{message_id}");
        assert!(id.starts_with("discord_"));
        assert!(message_id.chars().all(|c| c.is_ascii_digit()));
    }

    #[test]
    fn discord_message_id_fallback_to_uuid_on_empty() {
        let message_id = "";
        let id = if message_id.is_empty() {
            format!("discord_{}", uuid::Uuid::new_v4())
        } else {
            format!("discord_{message_id}")
        };
        assert!(id.starts_with("discord_"));
        assert!(id.contains('-'));
    }

    #[test]
    fn split_message_code_block_at_boundary() {
        let mut msg = String::new();
        msg.push_str("```rust\n");
        msg.push_str(&"x".repeat(1990));
        msg.push_str("\n```\nMore text after code block");
        let parts = split_message_for_discord(&msg);
        assert!(
            parts.len() >= 2,
            "code block spanning boundary should split"
        );
        for part in &parts {
            assert!(
                part.len() <= DISCORD_MAX_MESSAGE_LENGTH,
                "each part must be <= {DISCORD_MAX_MESSAGE_LENGTH}, got {}",
                part.len()
            );
        }
    }

    #[test]
    fn split_message_single_long_word_exceeds_limit() {
        let long_word = "a".repeat(2500);
        let parts = split_message_for_discord(&long_word);
        assert!(parts.len() >= 2, "word exceeding limit must be split");
        for part in &parts {
            assert!(
                part.len() <= DISCORD_MAX_MESSAGE_LENGTH,
                "hard-split part must be <= {DISCORD_MAX_MESSAGE_LENGTH}, got {}",
                part.len()
            );
        }
        let reassembled: String = parts.join("");
        assert_eq!(reassembled, long_word);
    }

    #[test]
    fn split_message_exactly_at_limit_no_split() {
        let msg = "a".repeat(DISCORD_MAX_MESSAGE_LENGTH);
        let parts = split_message_for_discord(&msg);
        assert_eq!(parts.len(), 1, "message exactly at limit should not split");
        assert_eq!(parts[0].len(), DISCORD_MAX_MESSAGE_LENGTH);
    }

    #[test]
    fn split_message_one_over_limit_splits() {
        let msg = "a".repeat(DISCORD_MAX_MESSAGE_LENGTH + 1);
        let parts = split_message_for_discord(&msg);
        assert!(parts.len() >= 2, "message 1 char over limit must split");
    }

    #[test]
    fn split_message_many_short_lines() {
        let msg: String = (0..500).map(|i| format!("line {i}\n")).collect();
        let parts = split_message_for_discord(&msg);
        for part in &parts {
            assert!(
                part.len() <= DISCORD_MAX_MESSAGE_LENGTH,
                "short-line batch must be <= limit"
            );
        }
        let reassembled: String = parts.join("");
        assert_eq!(reassembled.trim(), msg.trim());
    }

    #[test]
    fn split_message_only_whitespace() {
        let msg = "   \n\n\t  ";
        let parts = split_message_for_discord(msg);
        assert!(parts.len() <= 1);
    }

    #[test]
    fn split_message_emoji_at_boundary() {
        let mut msg = "a".repeat(1998);
        msg.push_str("🎉🎊");
        let parts = split_message_for_discord(&msg);
        for part in &parts {
            assert!(
                part.chars().count() <= DISCORD_MAX_MESSAGE_LENGTH,
                "emoji boundary split must respect limit"
            );
        }
    }

    #[test]
    fn split_message_consecutive_newlines_at_boundary() {
        let mut msg = "a".repeat(1995);
        msg.push_str("\n\n\n\n\n");
        msg.push_str(&"b".repeat(100));
        let parts = split_message_for_discord(&msg);
        for part in &parts {
            assert!(part.len() <= DISCORD_MAX_MESSAGE_LENGTH);
        }
    }

    #[tokio::test]
    async fn process_attachments_empty_list_returns_empty() {
        let client = reqwest::Client::new();
        let result = process_attachments(&[], &client).await;
        assert!(result.is_empty());
    }

    #[tokio::test]
    async fn process_attachments_skips_unsupported_types() {
        let client = reqwest::Client::new();
        let attachments = vec![serde_json::json!({
            "url": "https://cdn.discordapp.com/attachments/123/456/doc.pdf",
            "filename": "doc.pdf",
            "content_type": "application/pdf"
        })];
        let result = process_attachments(&attachments, &client).await;
        assert!(result.is_empty());
    }

    #[test]
    fn parse_attachment_markers_extracts_supported_markers() {
        let input = "Report\n[IMAGE:https://example.com/a.png]\n[DOCUMENT:/tmp/a.pdf]";
        let (cleaned, attachments) = parse_attachment_markers(input);

        assert_eq!(cleaned, "Report");
        assert_eq!(attachments.len(), 2);
        assert_eq!(attachments[0].kind, DiscordAttachmentKind::Image);
        assert_eq!(attachments[0].target, "https://example.com/a.png");
        assert_eq!(attachments[1].kind, DiscordAttachmentKind::Document);
        assert_eq!(attachments[1].target, "/tmp/a.pdf");
    }

    #[test]
    fn parse_attachment_markers_keeps_invalid_marker_text() {
        let input = "Hello [NOT_A_MARKER:foo] world";
        let (cleaned, attachments) = parse_attachment_markers(input);

        assert_eq!(cleaned, input);
        assert!(attachments.is_empty());
    }

    #[test]
    fn classify_outgoing_attachments_splits_local_remote_and_unresolved() {
        let temp = tempfile::tempdir().expect("tempdir");
        let file_path = temp.path().join("image.png");
        std::fs::write(&file_path, b"fake").expect("write fixture");

        let attachments = vec![
            DiscordAttachment {
                kind: DiscordAttachmentKind::Image,
                target: file_path.to_string_lossy().to_string(),
            },
            DiscordAttachment {
                kind: DiscordAttachmentKind::Image,
                target: "https://example.com/remote.png".to_string(),
            },
            DiscordAttachment {
                kind: DiscordAttachmentKind::Video,
                target: "/tmp/does-not-exist.mp4".to_string(),
            },
        ];

        let (locals, remotes, unresolved) = classify_outgoing_attachments(&attachments);
        assert_eq!(locals.len(), 1);
        assert_eq!(locals[0], file_path);
        assert_eq!(remotes, vec!["https://example.com/remote.png".to_string()]);
        assert_eq!(
            unresolved,
            vec!["[VIDEO:/tmp/does-not-exist.mp4]".to_string()]
        );
    }

    #[test]
    fn with_inline_attachment_urls_appends_urls_and_unresolved_markers() {
        let content = "Done";
        let remote_urls = vec!["https://example.com/a.png".to_string()];
        let unresolved = vec!["[IMAGE:/tmp/missing.png]".to_string()];

        let rendered = with_inline_attachment_urls(content, &remote_urls, &unresolved);
        assert_eq!(
            rendered,
            "Done\nhttps://example.com/a.png\n[IMAGE:/tmp/missing.png]"
        );
    }

    #[test]
    fn test_name_mention_matches_case_insensitive() {
        assert!(matches_daemon_name("hey Zero can you help", "zero"));
        assert!(matches_daemon_name("ZERO do this", "zero"));
        assert!(!matches_daemon_name("there are zero bugs", "zeroai"));
    }

    #[test]
    fn test_name_mention_requires_word_boundary() {
        assert!(matches_daemon_name("zero, help me", "zero"));
        assert!(matches_daemon_name("ask zero about it", "zero"));
        assert!(!matches_daemon_name("subzero temps", "zero"));
    }
}
