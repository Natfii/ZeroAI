// Copyright (c) 2026 @Natfii. All rights reserved.

//! SSH connection, authentication, and the interactive PTY pump.
//!
//! Builds on russh's client API: connect with host-key verification,
//! authenticate (public key then password / keyboard-interactive),
//! request a PTY and shell, then pump bytes between the local terminal
//! and the remote channel until the remote shell closes.

use std::io::{BufRead, Write};
use std::sync::Arc;
use std::time::Duration;

use russh::client::{self, KeyboardInteractiveAuthResponse};
use russh::keys::agent::client::AgentClient;
use russh::keys::{HashAlg, PrivateKeyWithHashAlg};
use russh::{ChannelMsg, ChannelReadHalf, ChannelWriteHalf};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::known_hosts::{self, Verdict};
use crate::term::{self, RawModeGuard};
use crate::{Args, BoxError};

/// Seconds allowed for the TCP connect and SSH handshake.
const CONNECT_TIMEOUT_SECS: u64 = 30;
/// SSH keepalive interval in seconds.
const KEEPALIVE_SECS: u64 = 30;
/// Idle timeout in seconds before the connection is dropped.
const INACTIVITY_SECS: u64 = 3600;
/// Maximum interactive password attempts before giving up.
const AUTH_ATTEMPTS: u32 = 3;
/// Size of the stdin read buffer forwarded to the remote channel.
const STDIN_BUF: usize = 4096;
/// Exit code reported when the remote process is terminated by a signal.
const SIGNAL_EXIT_CODE: u8 = 128;

/// russh client handler performing trust-on-first-use host-key checks.
struct CliHandler {
    /// Remote host, used for the known-hosts lookup and prompt.
    host: String,
    /// Remote port, used for the known-hosts lookup and prompt.
    port: u16,
}

impl client::Handler for CliHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        server_public_key: &russh::keys::PublicKey,
    ) -> Result<bool, Self::Error> {
        let fingerprint = server_public_key.fingerprint(HashAlg::Sha256).to_string();
        let algorithm = server_public_key.algorithm().to_string();

        match known_hosts::check(&self.host, self.port, &fingerprint) {
            Verdict::Trusted => return Ok(true),
            Verdict::Changed => {
                eprintln!("@@@ WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! @@@");
                eprintln!("The {algorithm} key fingerprint is {fingerprint}.");
                eprintln!("Refusing to connect. Remove the stale known-hosts entry to override.");
                return Ok(false);
            }
            Verdict::Unknown => {}
        }

        eprintln!(
            "The authenticity of host '{}:{}' can't be established.",
            self.host, self.port
        );
        eprintln!("{algorithm} key fingerprint is {fingerprint}.");
        eprint!("Are you sure you want to continue connecting (yes/no)? ");
        let _ = std::io::stderr().flush();

        let mut answer = String::new();
        if std::io::stdin().lock().read_line(&mut answer).is_err() {
            return Ok(false);
        }
        if answer.trim() == "yes" {
            let _ = known_hosts::trust(&self.host, self.port, &fingerprint);
            Ok(true)
        } else {
            eprintln!("Host key verification failed.");
            Ok(false)
        }
    }
}

/// Connects, authenticates, and runs the interactive session.
///
/// @param args Parsed connection arguments.
/// @return The remote shell's exit code, or an error before the session
///   is established.
pub(crate) async fn run(args: Args) -> Result<u8, BoxError> {
    let config = Arc::new(client::Config {
        inactivity_timeout: Some(Duration::from_secs(INACTIVITY_SECS)),
        keepalive_interval: Some(Duration::from_secs(KEEPALIVE_SECS)),
        ..Default::default()
    });
    let handler = CliHandler {
        host: args.host.clone(),
        port: args.port,
    };

    let connect = client::connect(config, (args.host.as_str(), args.port), handler);
    let mut handle =
        match tokio::time::timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS), connect).await {
            Ok(result) => result?,
            Err(_) => return Err("connection timed out".into()),
        };

    if !authenticate(&mut handle, &args).await? {
        return Err("Permission denied.".into());
    }

    let channel = handle.channel_open_session().await?;
    let (cols, rows) = term::terminal_size();
    let term_env = std::env::var("TERM").unwrap_or_else(|_| "xterm-256color".to_owned());
    channel
        .request_pty(
            false,
            &term_env,
            u32::from(cols),
            u32::from(rows),
            0,
            0,
            &[],
        )
        .await?;
    channel.request_shell(true).await?;

    let (read_half, write_half) = channel.split();
    let write_half = Arc::new(write_half);

    // Raw mode is restored when `_raw` drops (normal return or unwind).
    let _raw = RawModeGuard::enable()?;

    let stdin_task = tokio::spawn(stdin_loop(Arc::clone(&write_half)));
    let winch_task = tokio::spawn(winch_loop(Arc::clone(&write_half)));

    let code = read_loop(read_half).await;

    stdin_task.abort();
    winch_task.abort();
    Ok(code)
}

/// Hash algorithm for an RSA key (SHA-256), or `None` for non-RSA keys.
///
/// RSA public-key auth must request the modern `rsa-sha2-256` signature
/// algorithm; every other key type signs with its native algorithm.
fn rsa_hash_alg(is_rsa: bool) -> Option<HashAlg> {
    if is_rsa { Some(HashAlg::Sha256) } else { None }
}

/// Authenticates the handle: an explicit `-i` key file, otherwise every
/// identity held by the in-process ssh-agent (`SSH_AUTH_SOCK`), then falling
/// back to interactive password / keyboard-interactive auth.
///
/// @return `true` if authentication succeeded.
async fn authenticate(
    handle: &mut client::Handle<CliHandler>,
    args: &Args,
) -> Result<bool, BoxError> {
    if let Some(path) = &args.identity {
        if authenticate_identity_file(handle, args, path).await? {
            return Ok(true);
        }
        eprintln!("zssh: public key rejected; falling back to password.");
    } else if authenticate_agent(handle, args).await? {
        return Ok(true);
    }

    for attempt in 1..=AUTH_ATTEMPTS {
        // `Zeroizing<String>` scrubs the secret when it drops each iteration.
        let password = term::read_password(&format!("{}@{}'s password: ", args.user, args.host))?;
        let ok = handle
            .authenticate_password(&args.user, password.as_str())
            .await?
            .success();
        let ok = if ok {
            true
        } else {
            keyboard_interactive(handle, &args.user, password.as_str()).await?
        };
        if ok {
            return Ok(true);
        }
        if attempt < AUTH_ATTEMPTS {
            eprintln!("Permission denied, please try again.");
        }
    }
    Ok(false)
}

/// Attempts public-key auth with the explicit `-i` identity file.
///
/// Loads the (possibly passphrase-less) secret key from disk and offers it to
/// the server. A load failure is surfaced to the caller as an error rather
/// than silently skipped, since the user named this file explicitly.
///
/// @return `true` if the key was accepted.
async fn authenticate_identity_file(
    handle: &mut client::Handle<CliHandler>,
    args: &Args,
    path: &str,
) -> Result<bool, BoxError> {
    let key = russh::keys::load_secret_key(path, None)?;
    let hash_alg = rsa_hash_alg(key.algorithm().is_rsa());
    let key_with_alg = PrivateKeyWithHashAlg::new(Arc::new(key), hash_alg);
    Ok(handle
        .authenticate_publickey(&args.user, key_with_alg)
        .await?
        .success())
}

/// Attempts public-key auth against every identity held by the ssh-agent.
///
/// Connects to the agent named by `SSH_AUTH_SOCK`, lists its identities, and
/// offers each one in turn, letting the agent produce the signatures. A
/// missing or unreachable agent (no `SSH_AUTH_SOCK`) is treated as "no keys"
/// so the caller falls back to password auth.
///
/// @return `true` if any agent identity was accepted.
async fn authenticate_agent(
    handle: &mut client::Handle<CliHandler>,
    args: &Args,
) -> Result<bool, BoxError> {
    let mut agent = match AgentClient::connect_env().await {
        Ok(agent) => agent,
        Err(_) => return Ok(false),
    };
    let identities = agent.request_identities().await?;
    for identity in identities {
        let public_key = identity.public_key().into_owned();
        let hash_alg = rsa_hash_alg(public_key.algorithm().is_rsa());
        if handle
            .authenticate_publickey_with(&args.user, public_key, hash_alg, &mut agent)
            .await?
            .success()
        {
            return Ok(true);
        }
    }
    Ok(false)
}

/// Runs the keyboard-interactive exchange, answering every prompt with the
/// supplied password (covers servers that only advertise this method).
///
/// @return `true` if authentication succeeded.
async fn keyboard_interactive(
    handle: &mut client::Handle<CliHandler>,
    user: &str,
    password: &str,
) -> Result<bool, BoxError> {
    let mut response = handle
        .authenticate_keyboard_interactive_start(user, None)
        .await?;
    loop {
        match response {
            KeyboardInteractiveAuthResponse::Success => return Ok(true),
            KeyboardInteractiveAuthResponse::Failure { .. } => return Ok(false),
            KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                let answers = prompts.iter().map(|_| password.to_owned()).collect();
                response = handle
                    .authenticate_keyboard_interactive_respond(answers)
                    .await?;
            }
        }
    }
}

/// Forwards raw stdin bytes to the remote channel until stdin closes.
async fn stdin_loop(write_half: Arc<ChannelWriteHalf<client::Msg>>) {
    let mut stdin = tokio::io::stdin();
    let mut buf = [0u8; STDIN_BUF];
    loop {
        match stdin.read(&mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                let chunk = buf[..n].to_vec();
                if write_half.data(std::io::Cursor::new(chunk)).await.is_err() {
                    break;
                }
            }
        }
    }
}

/// Sends a window-change request to the remote on every `SIGWINCH`.
async fn winch_loop(write_half: Arc<ChannelWriteHalf<client::Msg>>) {
    let mut signal =
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::window_change()) {
            Ok(signal) => signal,
            Err(_) => return,
        };
    while signal.recv().await.is_some() {
        let (cols, rows) = term::terminal_size();
        let _ = write_half
            .window_change(u32::from(cols), u32::from(rows), 0, 0)
            .await;
    }
}

/// Drains remote channel messages to stdout, returning the shell exit code.
async fn read_loop(mut read_half: ChannelReadHalf) -> u8 {
    let mut stdout = tokio::io::stdout();
    let mut exit_code: u8 = 0;
    loop {
        match read_half.wait().await {
            Some(ChannelMsg::Data { ref data })
            | Some(ChannelMsg::ExtendedData { ref data, .. }) => {
                if stdout.write_all(data).await.is_err() {
                    break;
                }
                let _ = stdout.flush().await;
            }
            Some(ChannelMsg::ExitStatus { exit_status }) => {
                exit_code = exit_status as u8;
            }
            Some(ChannelMsg::ExitSignal { .. }) => {
                exit_code = SIGNAL_EXIT_CODE;
            }
            Some(ChannelMsg::Eof) | Some(ChannelMsg::Close) | None => break,
            _ => {}
        }
    }
    exit_code
}
