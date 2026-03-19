// Copyright (c) 2026 @Natfii. All rights reserved.

//! IMAP/SMTP email client for the agent's own mailbox.
//!
//! [`EmailClient`] wraps the low-level `async-imap` and `lettre` crates
//! behind a high-level interface used by the email tool implementations.

use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use futures::TryStreamExt;
use lettre::message::{header as lettre_header, Mailbox, MessageBuilder};
use lettre::transport::smtp::authentication::Credentials;
use lettre::{AsyncSmtpTransport, AsyncTransport, Tokio1Executor};
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;

use crate::config::EmailConfig;

use super::types::{
    EmailSummary, ParsedEmail, SendRateLimiter, MAX_FETCH_LIMIT, MAX_READ_OUTPUT_CHARS,
    MAX_SEARCH_RESULTS, OPERATION_TIMEOUT_SECS, TRASH_FOLDER_NAMES,
};

/// High-level email client backed by IMAP (read) and SMTP (send).
pub struct EmailClient {
    imap_host: String,
    imap_port: u16,
    smtp_host: String,
    smtp_port: u16,
    address: String,
    password: String,
    rate_limiter: Arc<SendRateLimiter>,
}

/// Type alias for an authenticated async-imap session over TLS.
type ImapSession = async_imap::Session<tokio_rustls::client::TlsStream<TcpStream>>;

impl EmailClient {
    /// Constructs a client from a validated [`EmailConfig`].
    pub fn from_config(config: &EmailConfig) -> Self {
        Self {
            imap_host: config.imap_host.clone(),
            imap_port: config.imap_port,
            smtp_host: config.smtp_host.clone(),
            smtp_port: config.smtp_port,
            address: config.address.clone(),
            password: config.password.clone(),
            rate_limiter: Arc::new(SendRateLimiter::new()),
        }
    }

    /// Opens a TLS connection to the IMAP server and logs in, returning an
    /// authenticated [`async_imap::Session`].
    pub async fn connect_imap(&self) -> Result<ImapSession> {
        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(timeout, self.connect_imap_inner())
            .await
            .context("IMAP connection timed out")?
    }

    async fn connect_imap_inner(&self) -> Result<ImapSession> {
        let tcp = TcpStream::connect((&*self.imap_host, self.imap_port))
            .await
            .context("IMAP TCP connect failed")?;

        let mut root_store = rustls::RootCertStore::empty();
        root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let tls_config = rustls::ClientConfig::builder()
            .with_root_certificates(root_store)
            .with_no_client_auth();
        let connector = TlsConnector::from(Arc::new(tls_config));
        let domain = rustls_pki_types::ServerName::try_from(self.imap_host.clone())
            .context("invalid IMAP server name")?;
        let tls_stream = connector
            .connect(domain, tcp)
            .await
            .context("IMAP TLS handshake failed")?;

        let mut client = async_imap::Client::new(tls_stream);
        let _greeting = client
            .read_response()
            .await
            .context("failed to read IMAP greeting")?;

        let session = client
            .login(&self.address, &self.password)
            .await
            .map_err(|(e, _)| e)
            .context("IMAP login failed")?;

        Ok(session)
    }

    /// Builds an async SMTP transport with TLS.
    ///
    /// Port 465 uses implicit TLS (`relay`), port 587 uses STARTTLS
    /// (`starttls_relay`). Other ports fall back to STARTTLS.
    pub async fn connect_smtp(&self) -> Result<AsyncSmtpTransport<Tokio1Executor>> {
        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(timeout, self.connect_smtp_inner())
            .await
            .context("SMTP connection timed out")?
    }

    async fn connect_smtp_inner(&self) -> Result<AsyncSmtpTransport<Tokio1Executor>> {
        let creds = Credentials::new(self.address.clone(), self.password.clone());

        let transport = if self.smtp_port == 465 {
            AsyncSmtpTransport::<Tokio1Executor>::relay(&self.smtp_host)
                .context("SMTP relay setup failed")?
                .credentials(creds)
                .port(self.smtp_port)
                .build()
        } else {
            AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(&self.smtp_host)
                .context("SMTP STARTTLS relay setup failed")?
                .credentials(creds)
                .port(self.smtp_port)
                .build()
        };

        Ok(transport)
    }

    /// Fetches unread messages from INBOX, marks them as `\Seen`, and
    /// returns parsed representations (up to `limit`, capped at
    /// [`MAX_FETCH_LIMIT`]).
    pub async fn fetch_unread(&self, limit: u32) -> Result<Vec<ParsedEmail>> {
        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(timeout, self.fetch_unread_inner(limit))
            .await
            .context("fetch_unread timed out")?
    }

    async fn fetch_unread_inner(&self, limit: u32) -> Result<Vec<ParsedEmail>> {
        let limit = limit.min(MAX_FETCH_LIMIT);
        let mut session = self.connect_imap().await?;
        session
            .select("INBOX")
            .await
            .context("failed to select INBOX")?;

        let uids = session
            .uid_search("UNSEEN")
            .await
            .context("IMAP SEARCH UNSEEN failed")?;

        if uids.is_empty() {
            session.logout().await.ok();
            return Ok(Vec::new());
        }

        let mut uid_list: Vec<u32> = uids.into_iter().collect();
        uid_list.sort_unstable();
        uid_list.truncate(limit as usize);

        let uid_set = uid_list
            .iter()
            .map(|u| u.to_string())
            .collect::<Vec<_>>()
            .join(",");

        let fetches: Vec<_> = session
            .uid_fetch(&uid_set, "RFC822 UID")
            .await
            .context("IMAP UID FETCH failed")?
            .try_collect()
            .await
            .context("IMAP FETCH stream error")?;

        let mut emails = Vec::with_capacity(fetches.len());
        for fetch in &fetches {
            let uid = match fetch.uid {
                Some(u) => u,
                None => continue,
            };
            let body_bytes = match fetch.body() {
                Some(b) => b,
                None => continue,
            };
            let parsed = mail_parser::MessageParser::default().parse(body_bytes);
            let msg = match parsed {
                Some(m) => m,
                None => continue,
            };
            emails.push(ParsedEmail {
                uid,
                sender: extract_sender(&msg),
                subject: msg.subject().unwrap_or("(no subject)").to_string(),
                body: truncate_text(&extract_text(&msg), MAX_READ_OUTPUT_CHARS),
                message_id: msg.message_id().unwrap_or("").to_string(),
                date: format_date(&msg),
            });
        }

        // Mark fetched messages as Seen
        let _ = session
            .uid_store(&uid_set, "+FLAGS (\\Seen)")
            .await
            .ok();

        session.logout().await.ok();
        Ok(emails)
    }

    /// Fetches a single message by UID.
    pub async fn fetch_message(&self, uid: u32) -> Result<ParsedEmail> {
        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(timeout, self.fetch_message_inner(uid))
            .await
            .context("fetch_message timed out")?
    }

    async fn fetch_message_inner(&self, uid: u32) -> Result<ParsedEmail> {
        let mut session = self.connect_imap().await?;
        session
            .select("INBOX")
            .await
            .context("failed to select INBOX")?;

        let uid_str = uid.to_string();
        let fetches: Vec<_> = session
            .uid_fetch(&uid_str, "RFC822 UID")
            .await
            .context("IMAP UID FETCH failed")?
            .try_collect()
            .await
            .context("IMAP FETCH stream error")?;

        let fetch = fetches
            .first()
            .context("message not found for given UID")?;
        let body_bytes = fetch.body().context("message has no body")?;
        let msg = mail_parser::MessageParser::default()
            .parse(body_bytes)
            .context("failed to parse RFC822 message")?;

        let email = ParsedEmail {
            uid,
            sender: extract_sender(&msg),
            subject: msg.subject().unwrap_or("(no subject)").to_string(),
            body: truncate_text(&extract_text(&msg), MAX_READ_OUTPUT_CHARS),
            message_id: msg.message_id().unwrap_or("").to_string(),
            date: format_date(&msg),
        };

        session.logout().await.ok();
        Ok(email)
    }

    /// Searches for messages matching structured parameters.
    ///
    /// All `Option` parameters are ANDed into a single IMAP SEARCH query.
    #[allow(clippy::too_many_arguments)]
    pub async fn search(
        &self,
        from: Option<&str>,
        to: Option<&str>,
        subject: Option<&str>,
        body: Option<&str>,
        since: Option<&str>,
        before: Option<&str>,
        unread_only: bool,
    ) -> Result<Vec<EmailSummary>> {
        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(
            timeout,
            self.search_inner(from, to, subject, body, since, before, unread_only),
        )
        .await
        .context("search timed out")?
    }

    async fn search_inner(
        &self,
        from: Option<&str>,
        to: Option<&str>,
        subject: Option<&str>,
        body: Option<&str>,
        since: Option<&str>,
        before: Option<&str>,
        unread_only: bool,
    ) -> Result<Vec<EmailSummary>> {
        let mut parts = Vec::new();
        if let Some(f) = from {
            parts.push(format!("FROM \"{}\"", f));
        }
        if let Some(t) = to {
            parts.push(format!("TO \"{}\"", t));
        }
        if let Some(s) = subject {
            parts.push(format!("SUBJECT \"{}\"", s));
        }
        if let Some(b) = body {
            parts.push(format!("BODY \"{}\"", b));
        }
        if let Some(s) = since {
            parts.push(format!("SINCE {}", s));
        }
        if let Some(b) = before {
            parts.push(format!("BEFORE {}", b));
        }
        if unread_only {
            parts.push("UNSEEN".to_string());
        }
        if parts.is_empty() {
            parts.push("ALL".to_string());
        }
        let query = parts.join(" ");

        let mut session = self.connect_imap().await?;
        session
            .select("INBOX")
            .await
            .context("failed to select INBOX")?;

        let uids = session
            .uid_search(&query)
            .await
            .context("IMAP UID SEARCH failed")?;

        if uids.is_empty() {
            session.logout().await.ok();
            return Ok(Vec::new());
        }

        let mut uid_list: Vec<u32> = uids.into_iter().collect();
        uid_list.sort_unstable();
        uid_list.truncate(MAX_SEARCH_RESULTS);

        let uid_set = uid_list
            .iter()
            .map(|u| u.to_string())
            .collect::<Vec<_>>()
            .join(",");

        let fetches: Vec<_> = session
            .uid_fetch(&uid_set, "RFC822.HEADER UID")
            .await
            .context("IMAP UID FETCH headers failed")?
            .try_collect()
            .await
            .context("IMAP FETCH stream error")?;

        let mut results = Vec::with_capacity(fetches.len());
        for fetch in &fetches {
            let uid = match fetch.uid {
                Some(u) => u,
                None => continue,
            };
            let header_bytes = match fetch.body() {
                Some(b) => b,
                None => continue,
            };
            let msg = match mail_parser::MessageParser::default().parse(header_bytes) {
                Some(m) => m,
                None => continue,
            };
            results.push(EmailSummary {
                uid,
                sender: extract_sender(&msg),
                subject: msg.subject().unwrap_or("(no subject)").to_string(),
                date: format_date(&msg),
            });
        }

        session.logout().await.ok();
        Ok(results)
    }

    /// Sends an email through SMTP, subject to rate limiting.
    pub async fn send_email(
        &self,
        to: &str,
        subject: &str,
        body: &str,
        cc: Option<&str>,
        bcc: Option<&str>,
    ) -> Result<()> {
        self.rate_limiter.check_and_record()?;

        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(timeout, self.send_email_inner(to, subject, body, cc, bcc))
            .await
            .context("send_email timed out")?
    }

    async fn send_email_inner(
        &self,
        to: &str,
        subject: &str,
        body: &str,
        cc: Option<&str>,
        bcc: Option<&str>,
    ) -> Result<()> {
        let from_mailbox: Mailbox = self
            .address
            .parse()
            .context("invalid sender address")?;
        let to_mailbox: Mailbox = to.parse().context("invalid recipient address")?;

        let mut builder: MessageBuilder = lettre::Message::builder()
            .from(from_mailbox)
            .to(to_mailbox)
            .subject(subject);

        if let Some(cc_addr) = cc {
            let cc_mailbox: Mailbox = cc_addr.parse().context("invalid CC address")?;
            builder = builder.cc(cc_mailbox);
        }
        if let Some(bcc_addr) = bcc {
            let bcc_mailbox: Mailbox = bcc_addr.parse().context("invalid BCC address")?;
            builder = builder.bcc(bcc_mailbox);
        }

        let message = builder
            .header(lettre_header::ContentType::TEXT_PLAIN)
            .body(body.to_string())
            .context("failed to build email message")?;

        let transport = self.connect_smtp().await?;
        transport
            .send(message)
            .await
            .context("SMTP send failed")?;

        Ok(())
    }

    /// Replies to a message identified by UID.
    ///
    /// Fetches the original to build correct `In-Reply-To` and `References`
    /// headers. Returns `(recipient, subject)` of the reply sent.
    pub async fn reply(&self, uid: u32, body: &str) -> Result<(String, String)> {
        self.rate_limiter.check_and_record()?;

        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(timeout, self.reply_inner(uid, body))
            .await
            .context("reply timed out")?
    }

    async fn reply_inner(&self, uid: u32, body: &str) -> Result<(String, String)> {
        let original = self.fetch_message_inner(uid).await?;
        let reply_to = original.sender.clone();
        let reply_subject = if original.subject.starts_with("Re: ") {
            original.subject.clone()
        } else {
            format!("Re: {}", original.subject)
        };

        let from_mailbox: Mailbox = self
            .address
            .parse()
            .context("invalid sender address")?;

        // Extract bare email from sender string for the To header
        let to_addr = extract_bare_address(&reply_to);
        let to_mailbox: Mailbox = to_addr.parse().context("invalid reply-to address")?;

        let mut builder: MessageBuilder = lettre::Message::builder()
            .from(from_mailbox)
            .to(to_mailbox)
            .subject(&reply_subject);

        if !original.message_id.is_empty() {
            builder = builder
                .in_reply_to(original.message_id.clone())
                .references(original.message_id.clone());
        }

        let message = builder
            .header(lettre_header::ContentType::TEXT_PLAIN)
            .body(body.to_string())
            .context("failed to build reply message")?;

        let transport = self.connect_smtp().await?;
        transport
            .send(message)
            .await
            .context("SMTP reply send failed")?;

        Ok((reply_to, reply_subject))
    }

    /// Deletes messages by UID (sets `\Deleted` flag and expunges).
    ///
    /// Returns the count of successfully deleted messages.
    pub async fn delete(&self, uids: &[u32]) -> Result<usize> {
        if uids.is_empty() {
            return Ok(0);
        }

        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(timeout, self.delete_inner(uids))
            .await
            .context("delete timed out")?
    }

    async fn delete_inner(&self, uids: &[u32]) -> Result<usize> {
        let mut session = self.connect_imap().await?;
        session
            .select("INBOX")
            .await
            .context("failed to select INBOX")?;

        let uid_set = uids
            .iter()
            .map(|u| u.to_string())
            .collect::<Vec<_>>()
            .join(",");

        let _store_result: Vec<_> = session
            .uid_store(&uid_set, "+FLAGS (\\Deleted)")
            .await
            .context("IMAP UID STORE \\Deleted failed")?
            .try_collect()
            .await
            .context("IMAP STORE stream error")?;

        let expunged: Vec<_> = session
            .expunge()
            .await
            .context("IMAP EXPUNGE failed")?
            .try_collect()
            .await
            .context("IMAP EXPUNGE stream error")?;

        let count = expunged.len();
        session.logout().await.ok();
        Ok(count)
    }

    /// Empties the trash folder.
    ///
    /// Discovers the trash folder by name (provider-specific naming) then
    /// flags all messages as `\Deleted` and expunges. Returns the number
    /// of messages removed.
    pub async fn empty_trash(&self) -> Result<usize> {
        let timeout = Duration::from_secs(OPERATION_TIMEOUT_SECS);
        tokio::time::timeout(timeout, self.empty_trash_inner())
            .await
            .context("empty_trash timed out")?
    }

    async fn empty_trash_inner(&self) -> Result<usize> {
        let mut session = self.connect_imap().await?;

        // List all folders
        let folders: Vec<_> = session
            .list(Some(""), Some("*"))
            .await
            .context("IMAP LIST failed")?
            .try_collect()
            .await
            .context("IMAP LIST stream error")?;

        let trash_folder = folders
            .iter()
            .find(|f| {
                let name = f.name();
                TRASH_FOLDER_NAMES
                    .iter()
                    .any(|t| t.eq_ignore_ascii_case(name))
            })
            .map(|f| f.name().to_string());

        let trash_name = match trash_folder {
            Some(name) => name,
            None => {
                session.logout().await.ok();
                return Ok(0);
            }
        };

        let mailbox = session
            .select(&trash_name)
            .await
            .context("failed to select trash folder")?;

        let exists = mailbox.exists;
        if exists == 0 {
            session.logout().await.ok();
            return Ok(0);
        }

        // Flag all messages for deletion (sequence set 1:*)
        let _store: Vec<_> = session
            .store("1:*", "+FLAGS (\\Deleted)")
            .await
            .context("IMAP STORE \\Deleted in trash failed")?
            .try_collect()
            .await
            .context("IMAP STORE stream error")?;

        let expunged: Vec<_> = session
            .expunge()
            .await
            .context("IMAP EXPUNGE trash failed")?
            .try_collect()
            .await
            .context("IMAP EXPUNGE stream error")?;

        let count = expunged.len();
        session.logout().await.ok();
        Ok(count)
    }

    /// Tests IMAP connectivity without constructing a full [`EmailClient`].
    pub async fn test_imap(
        host: &str,
        port: u16,
        address: &str,
        password: &str,
    ) -> Result<()> {
        let client = Self {
            imap_host: host.to_string(),
            imap_port: port,
            smtp_host: String::new(),
            smtp_port: 0,
            address: address.to_string(),
            password: password.to_string(),
            rate_limiter: Arc::new(SendRateLimiter::new()),
        };
        let mut session = client.connect_imap().await?;
        session.logout().await.ok();
        Ok(())
    }

    /// Tests SMTP connectivity without constructing a full [`EmailClient`].
    pub async fn test_smtp(
        host: &str,
        port: u16,
        address: &str,
        password: &str,
    ) -> Result<()> {
        let client = Self {
            imap_host: String::new(),
            imap_port: 0,
            smtp_host: host.to_string(),
            smtp_port: port,
            address: address.to_string(),
            password: password.to_string(),
            rate_limiter: Arc::new(SendRateLimiter::new()),
        };
        let transport = client.connect_smtp().await?;
        transport.test_connection().await.context("SMTP test connection failed")?;
        Ok(())
    }
}

/// Extracts a formatted sender string from a parsed message's `From` header.
///
/// Returns `"Name <addr>"` when a display name is present, otherwise just the
/// bare address or `"(unknown sender)"`.
pub fn extract_sender(msg: &mail_parser::Message<'_>) -> String {
    let addr = match msg.from() {
        Some(mail_parser::Address::List(addrs)) => addrs.first(),
        Some(mail_parser::Address::Group(groups)) => {
            groups.first().and_then(|g| g.addresses.first())
        }
        None => None,
    };

    match addr {
        Some(a) => {
            let email = a.address.as_deref().unwrap_or("unknown");
            match &a.name {
                Some(name) if !name.is_empty() => format!("{} <{}>", name, email),
                _ => email.to_string(),
            }
        }
        None => "(unknown sender)".to_string(),
    }
}

/// Extracts the plain-text body from a parsed message.
///
/// Prefers `body_text(0)`, falls back to `body_html(0)` run through
/// `nanohtml2text`, then falls back to scanning text attachments.
pub fn extract_text(msg: &mail_parser::Message<'_>) -> String {
    if let Some(text) = msg.body_text(0) {
        return text.to_string();
    }

    if let Some(html) = msg.body_html(0) {
        return nanohtml2text::html2text(&html);
    }

    // Fall back to text attachments
    for attachment in msg.attachments() {
        if let mail_parser::PartType::Text(text) = &attachment.body {
            return text.to_string();
        }
    }

    "(no text content)".to_string()
}

/// Formats the message date for display, or returns a placeholder.
fn format_date(msg: &mail_parser::Message<'_>) -> String {
    match msg.date() {
        Some(dt) => format!(
            "{:04}-{:02}-{:02} {:02}:{:02}",
            dt.year, dt.month, dt.day, dt.hour, dt.minute
        ),
        None => "(unknown date)".to_string(),
    }
}

/// Truncates a string to at most `max_chars` characters, appending `...` if truncated.
fn truncate_text(s: &str, max_chars: usize) -> String {
    if s.len() <= max_chars {
        s.to_string()
    } else {
        let mut end = max_chars;
        while !s.is_char_boundary(end) && end > 0 {
            end -= 1;
        }
        format!("{}...", &s[..end])
    }
}

/// Extracts a bare email address from a formatted sender string.
///
/// Given `"Alice <alice@example.com>"`, returns `"alice@example.com"`.
/// If no angle brackets, returns the input unchanged.
fn extract_bare_address(formatted: &str) -> &str {
    if let Some(start) = formatted.find('<') {
        if let Some(end) = formatted.find('>') {
            return &formatted[start + 1..end];
        }
    }
    formatted.trim()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_sender_with_name() {
        let raw = b"From: Alice Smith <alice@example.com>\r\nSubject: Hello\r\n\r\nBody text.";
        let msg = mail_parser::MessageParser::default()
            .parse(raw)
            .expect("should parse");
        let sender = extract_sender(&msg);
        assert_eq!(sender, "Alice Smith <alice@example.com>");
    }

    #[test]
    fn test_extract_sender_without_name() {
        let raw = b"From: bob@example.com\r\nSubject: Hi\r\n\r\nBody.";
        let msg = mail_parser::MessageParser::default()
            .parse(raw)
            .expect("should parse");
        let sender = extract_sender(&msg);
        assert_eq!(sender, "bob@example.com");
    }

    #[test]
    fn test_extract_text_plain() {
        let raw = b"From: alice@example.com\r\nSubject: Test\r\nContent-Type: text/plain\r\n\r\nHello, world!";
        let msg = mail_parser::MessageParser::default()
            .parse(raw)
            .expect("should parse");
        let text = extract_text(&msg);
        assert_eq!(text, "Hello, world!");
    }

    #[test]
    fn test_extract_text_no_body() {
        let raw = b"From: alice@example.com\r\nSubject: Empty\r\n\r\n";
        let msg = mail_parser::MessageParser::default()
            .parse(raw)
            .expect("should parse");
        let text = extract_text(&msg);
        // An empty-body message yields either "" or the fallback placeholder
        assert!(text.is_empty() || text == "(no text content)");
    }

    #[test]
    fn test_rate_limiter() {
        let limiter = SendRateLimiter::new();
        for i in 0..super::super::types::MAX_OUTBOUND_PER_HOUR {
            limiter
                .check_and_record()
                .unwrap_or_else(|_| panic!("send {} should succeed", i + 1));
        }
        // The next one should fail
        assert!(limiter.check_and_record().is_err());
    }

    #[test]
    fn test_truncate_text() {
        assert_eq!(truncate_text("short", 100), "short");
        assert_eq!(truncate_text("hello world", 5), "hello...");
    }

    #[test]
    fn test_extract_bare_address() {
        assert_eq!(
            extract_bare_address("Alice <alice@example.com>"),
            "alice@example.com"
        );
        assert_eq!(
            extract_bare_address("bob@example.com"),
            "bob@example.com"
        );
    }

    #[test]
    fn test_format_date() {
        let raw = b"From: a@b.com\r\nDate: Mon, 10 Mar 2026 14:30:00 +0000\r\nSubject: x\r\n\r\ny";
        let msg = mail_parser::MessageParser::default()
            .parse(raw)
            .expect("should parse");
        let date = format_date(&msg);
        assert!(date.contains("2026"));
        assert!(date.contains("03"));
        assert!(date.contains("10"));
    }
}
