// Copyright (c) 2026 @Natfii. All rights reserved.

//! `zssh` — ZeroAI's bundled interactive SSH client.
//!
//! A small russh-based SSH client packaged inside the app as a native
//! library (`libssh.so`) so it can be invoked as `ssh` from the in-app
//! shell. It inherits the shell's PTY, so host-key and password prompts
//! and the remote session are ordinary terminal I/O — no app dialogs.

mod client;
mod known_hosts;
mod term;

use std::process::ExitCode;

/// Boxed error type used across the CLI.
pub(crate) type BoxError = Box<dyn std::error::Error + Send + Sync>;

/// Default SSH port.
const DEFAULT_PORT: u16 = 22;
/// Exit code for usage / argument errors.
const USAGE_EXIT: u8 = 2;
/// Exit code for a failed connection or session error.
const ERROR_EXIT: u8 = 1;

/// Parsed command-line arguments.
pub(crate) struct Args {
    /// Login user name.
    pub(crate) user: String,
    /// Remote host.
    pub(crate) host: String,
    /// Remote port.
    pub(crate) port: u16,
    /// Optional identity (private key) file path.
    pub(crate) identity: Option<String>,
}

/// Parses `argv` as `[user@]host [-p port] [-i identity_file]`.
///
/// @return The parsed [`Args`], or a usage error.
fn parse_args() -> Result<Args, BoxError> {
    let mut user: Option<String> = None;
    let mut host: Option<String> = None;
    let mut port = DEFAULT_PORT;
    let mut identity: Option<String> = None;

    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-p" => {
                let value = args.next().ok_or("-p requires a port number")?;
                port = value.parse().map_err(|_| "invalid port number")?;
            }
            "-i" => {
                identity = Some(args.next().ok_or("-i requires a key file path")?);
            }
            "-h" | "--help" => {
                return Err("usage: ssh [user@]host [-p port] [-i identity_file]".into());
            }
            target => {
                if let Some((name, addr)) = target.split_once('@') {
                    user = Some(name.to_owned());
                    host = Some(addr.to_owned());
                } else {
                    host = Some(target.to_owned());
                }
            }
        }
    }

    let host = host.ok_or("missing host (usage: ssh [user@]host [-p port])")?;
    let user = user
        .or_else(|| std::env::var("USER").ok())
        .filter(|name| !name.is_empty())
        .ok_or("missing user; specify as user@host")?;
    Ok(Args {
        user,
        host,
        port,
        identity,
    })
}

/// Parses arguments and runs the session on a single-threaded runtime,
/// mapping the outcome to a process exit code.
#[tokio::main(flavor = "current_thread")]
async fn main() -> ExitCode {
    let args = match parse_args() {
        Ok(args) => args,
        Err(err) => {
            eprintln!("zssh: {err}");
            return ExitCode::from(USAGE_EXIT);
        }
    };
    match client::run(args).await {
        Ok(code) => ExitCode::from(code),
        Err(err) => {
            eprintln!("zssh: {err}");
            ExitCode::from(ERROR_EXIT)
        }
    }
}
