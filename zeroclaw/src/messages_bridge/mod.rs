// Copyright (c) 2026 @Natfii. All rights reserved.

pub mod client;
pub mod crypto;
pub mod events;
pub mod longpoll;
pub mod methods;
pub mod pairing;
pub mod pblite;
pub mod session;
pub mod store;
pub mod tool;
pub mod types;

/// Generated protobuf types from Google Messages Bugle protocol.
/// Ported from mautrix-gmessages (<https://github.com/mautrix/gmessages>).
pub mod proto {
    pub mod authentication {
        include!(concat!(env!("OUT_DIR"), "/authentication.rs"));
    }
    pub mod client {
        include!(concat!(env!("OUT_DIR"), "/client.rs"));
    }
    pub mod conversations {
        include!(concat!(env!("OUT_DIR"), "/conversations.rs"));
    }
    pub mod events {
        include!(concat!(env!("OUT_DIR"), "/events.rs"));
    }
    pub mod rpc {
        include!(concat!(env!("OUT_DIR"), "/rpc.rs"));
    }
    pub mod settings {
        include!(concat!(env!("OUT_DIR"), "/settings.rs"));
    }
    pub mod config {
        include!(concat!(env!("OUT_DIR"), "/config.rs"));
    }
    pub mod util {
        include!(concat!(env!("OUT_DIR"), "/util.rs"));
    }
}
