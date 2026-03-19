# Google Messages Bugle Protocol — Reverse Engineering Notes

Findings from implementing a Google Messages bridge in ZeroAI (Rust), based on the
[mautrix-gmessages](https://github.com/mautrix/gmessages) Go reference implementation
and live testing against Google's production APIs.

**Date**: 2026-03-18
**Status**: Active development — pairing + RPC auth working, conversation sync in progress

---

## Architecture Overview

Google Messages for Web uses a proprietary RPC protocol called **Bugle**, hosted on
`instantmessaging-pa.googleapis.com`. Two base URLs exist:

| URL | Usage |
|-----|-------|
| `https://instantmessaging-pa.googleapis.com` | QR-paired (relay) sessions |
| `https://instantmessaging-pa.clients6.google.com` | Google-account-paired sessions |

All endpoints share the gRPC-Web path prefix:
```
$rpc/google.internal.communications.instantmessaging.v1
```

## Encoding: PBLite vs Binary Protobuf

Google's Bugle API uses **two** protobuf encodings, and the choice is **endpoint-specific**:

| Encoding | Content-Type | Used By |
|----------|-------------|---------|
| **PBLite** (JSON arrays) | `application/json+protobuf` | `ReceiveMessages`, `SendMessage`, `RegisterRefresh` |
| **Binary protobuf** | `application/x-protobuf` | `RegisterPhoneRelay` |

**Critical finding**: Sending binary protobuf to a PBLite endpoint returns HTTP 401
("missing required authentication credential") because the server can't parse the auth
token from the body. This is **not** a token validity issue — it's a content encoding
mismatch that manifests as an auth error.

### PBLite Format

PBLite maps protobuf fields to positional JSON arrays where array index = field_number - 1:

```json
// AuthMessage { requestID=1, network=3, tachyonAuthToken=6, configVersion=7 }
["request-uuid", null, "Bugle", null, null, "base64token==", [null, null, 2025, 11, 6, null, 4, null, 6]]
```

Rules:
- Absent fields → `null`
- `int64` fields → JSON string (avoids JS precision loss): `"1773843590773562"`
- `bytes` fields → standard base64-encoded string
- Nested messages → nested arrays
- `repeated` fields → JSON arrays
- Array size = highest field number in the message
- Enums → integer value

## QR Pairing Flow

### Step 1: RegisterPhoneRelay

```
POST /…Pairing/RegisterPhoneRelay
Content-Type: application/x-protobuf
```

- Generates ECDSA P-256 keypair and random AES-256/HMAC-SHA256 keys
- Sends `AuthenticationContainer` with browser details and ECDSA public key
- Response: `RegisterPhoneRelayResponse` with `pairing_key`, `tachyon_auth_token`, `TTL`, browser `Device`
- **This is the only endpoint that uses binary protobuf**

### Step 2: QR Code Generation

The QR URL format:
```
https://support.google.com/messages/?p=web_computer#?c=<base64(UrlData)>
```

`UrlData` proto contains: `pairing_key` + `AES key` + `HMAC key`

The phone scans this, decodes the keys, and initiates the pairing handshake via Google's relay.

### Step 3: Long-Poll Watcher (ReceiveMessages)

```
POST /…Messaging/ReceiveMessages
Content-Type: application/json+protobuf
```

Must be started **before** the QR code is displayed. The watcher listens for the
`PairEvent` (BugleRoute = 14) on the streaming response. Missing the PairEvent means
the pairing silently fails.

Response format: `[[ payload1, payload2, ... ]]` — streaming PBLite array.

### Step 4: PairEvent Processing

When the phone scans the QR code and completes the handshake, the server sends a
`PairEvent` on the long-poll stream containing `PairedData`:

- `mobile` Device (phone identity)
- `browser` Device (browser/client identity)
- `tokenData` with fresh `tachyon_auth_token` + `TTL`

**Critical**: The `messageData` bytes in the PairEvent `IncomingRPCMessage` are NOT
raw `PairedData` — they are wrapped in `RPCPairData` (from `events.proto`):

```protobuf
message RPCPairData {
    oneof event {
        PairedData paired = 4;    // field 4, not field 1!
        RevokePairData revoked = 5;
    }
}
```

Decoding `messageData` directly as `PairedData` will "succeed" (protobuf is lenient)
but produce **empty fields** because the field numbers don't align. You MUST decode
as `RPCPairData` first, then extract `PairedData` from the `Paired` variant.

Without the correct mobile Device identity, all subsequent `SendMessage` RPCs will
return 401 — the server validates the mobile device in the request against the paired
session.

### Step 5: Post-Pairing Long-Poll

After receiving the PairEvent, start a **new** long-poll with a **2-second delay**.
Without the delay, the phone hasn't saved the pair data yet and Google will send
another PairEvent that looks like an unpair.

The new long-poll receives:
1. A replayed PairEvent (confirmation, not an unpair)
2. Heartbeats every ~10 seconds
3. Data events (conversations, messages) when requested

### Step 6: SetActiveSession (GET_UPDATES)

After the long-poll connects, send a `GET_UPDATES` action (ActionType 16) with **no
data payload** and `TTL=0` (OmitTTL). This tells the phone "I'm actively listening."

Without this call, the phone will NOT push conversations or messages on the long-poll
stream, even if `ListConversations` succeeds. The upstream calls this via
`SetActiveSession()` in the `postConnect` callback.

### Step 7: ListConversations

Send `ListConversations` (ActionType 1) with `MessageType=BUGLE_ANNOTATION` (16) for
the first call. The response arrives asynchronously on the long-poll as an encrypted
`DataEvent`.

## Sending RPC Actions (e.g., ListConversations)

Outgoing RPCs use the `SendMessage` endpoint with a wrapping `OutgoingRPCMessage`:

```
POST /…Messaging/SendMessage
Content-Type: application/json+protobuf
```

### OutgoingRPCMessage Structure

```
OutgoingRPCMessage {
  mobile: Device,              // field 1 — phone identity from pairing
  data: Data {                 // field 2
    requestID: string,         //   field 1
    bugleRoute: DataEvent(19), //   field 2
    messageData: bytes,        //   field 12 — encoded OutgoingRPCData
    messageTypeData: Type {    //   field 23
      emptyArr: EmptyArr,      //     field 1
      messageType: int,        //     field 2
    }
  },
  auth: Auth {                 // field 3
    requestID: string,         //   field 1
    tachyonAuthToken: bytes,   //   field 6
    configVersion: CV,         //   field 7
  },
  TTL: int64,                  // field 5 — from TokenData.TTL
  destRegistrationIDs: [bytes] // field 9 — browser registration UUID
}
```

### messageData Encoding

The `messageData` bytes field contains a **binary protobuf** `OutgoingRPCData`:

```
OutgoingRPCData {
  requestID: string,
  action: ActionType,
  encryptedProtoData: bytes,  // AES-256-CTR + HMAC-SHA256 encrypted
  sessionID: string,
}
```

The inner request (e.g., `ListConversationsRequest`) is encrypted with the session's
AES/HMAC keys before being placed in `encryptedProtoData`.

### MessageType

| Value | Name | Usage |
|-------|------|-------|
| 2 | BUGLE_MESSAGE | Normal RPC actions |
| 16 | BUGLE_ANNOTATION | **First** ListConversations call after pairing |

The upstream uses `BUGLE_ANNOTATION` for the initial conversation fetch, then
`BUGLE_MESSAGE` for subsequent calls.

## Token Lifecycle

### RegisterRefresh (Optional)

```
POST /…Registration/RegisterRefresh
Content-Type: application/json+protobuf
```

Called by the upstream's `refreshAuthToken()` to refresh expiring tokens. **Skipped**
for freshly paired sessions where the token has >1 hour remaining (which is always
the case right after pairing).

The request requires an ECDSA-SHA256 signature over `"{requestID}:{timestamp}"` using
the signing key generated during `RegisterPhoneRelay`.

### Token Validity

- The relay token from `RegisterPhoneRelay` is valid for `ReceiveMessages` immediately
- After the PairEvent, `PairedData.tokenData` provides a refreshed token
- `RegisterRefresh` is only needed when the token is within 1 hour of expiry

## ConfigVersion

Must be included in every `AuthMessage` and `OutgoingRPCMessage.Auth`:

```
ConfigVersion { Year=2025, Month=11, Day=6, V1=4, V2=6 }
```

Matches upstream mautrix-gmessages as of v25.11 (November 2025). Google may reject
outdated versions — update when upstream bumps these values.

## HTTP Headers

All requests must include these headers (mimicking Chrome on Android):

```
x-goog-api-key: AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8
User-Agent: Mozilla/5.0 (Linux; Android 14) … Chrome/141.0.0.0 …
Origin: https://messages.google.com
Referer: https://messages.google.com/
x-user-agent: grpc-web-javascript/0.1
sec-ch-ua: "Google Chrome";v="141", "Chromium";v="141", "Not-A.Brand";v="24"
```

## Encryption (AES-256-CTR + HMAC-SHA256)

Wire format: `ciphertext || IV (16 bytes) || HMAC-SHA256 (32 bytes)`

- Random 16-byte IV per message
- AES-256-CTR encryption
- HMAC-SHA256 over ciphertext only (not IV)
- Keys established during `RegisterPhoneRelay` (random 32-byte each)

## Common Pitfalls

### VPN Interference

VPNs (especially Tailscale, WireGuard) on the **phone** can prevent the QR scan
handshake from completing. The phone shows "Something went wrong" because the VPN
routes the pairing traffic through a different path than the relay expects. Disable
VPNs on the phone before scanning.

### Rate Limiting

Google rate-limits `RegisterPhoneRelay` after multiple failed pairing attempts.
Symptoms: phone shows "Something went wrong" on QR scan. Cooldown: 5-20 minutes,
escalating with each failed attempt. Force-stop Google Messages and wait before retrying.

### Cookie Persistence

The upstream Go client stores cookies from responses via `AddCookiesToRequest` /
`UpdateCookiesFromResponse`. Enable `cookie_store(true)` on the HTTP client and
reuse the same client instance across `RegisterPhoneRelay` → `SendMessage` calls.

### RPCPairData vs PairedData

The PairEvent `messageData` is `RPCPairData`, NOT `PairedData`. Decoding as
`PairedData` directly produces empty device identities (field 4 vs field 1 mismatch).
This causes all subsequent RPCs to return 401 — the most misleading bug in the
protocol.

### SetActiveSession Required

The phone won't push ANY data on the long-poll until it receives a `GET_UPDATES`
action (SetActiveSession). Without it, `ListConversations` succeeds (HTTP 200) but
the response never arrives on the stream.

### Session ID Routing

The `OutgoingRPCData.sessionID` field links outgoing RPCs to the long-poll session.
The upstream generates a UUID via `ResetSessionID()`, uses it as the `requestID` for
`SetActiveSession`, and then as the `sessionID` for all subsequent `OutgoingRPCData`
messages. Without a session ID, the server may not route responses back to the
correct long-poll.

### Empty Data Encryption

When sending actions with no payload (e.g., `GET_UPDATES`), the upstream sends
`OutgoingRPCData` with BOTH `encryptedProtoData` and `unencryptedProtoData` empty.
Do NOT encrypt an empty byte array — `AES-CTR + HMAC` over zero bytes still produces
48 bytes (16 IV + 32 HMAC tag), which the phone may misinterpret as a corrupted
payload.

### destRegistrationIDs Encoding

The proto defines `repeated string destRegistrationIDs = 9` but Google's server
treats it as `TYPE_BYTES`. Values must be valid base64. Sending the browser
`sourceID` as a raw string causes HTTP 400. For QR-paired sessions without Google
login, this field can be left empty — the upstream's `DestRegID` is `uuid.Nil` in
this case.

## Action Types

```
LIST_CONVERSATIONS = 1    SEND_MESSAGE = 3
LIST_MESSAGES = 2         MESSAGE_UPDATES = 4
LIST_CONTACTS = 6         CONVERSATION_UPDATES = 7
BROWSER_PRESENCE_CHECK = 11
TYPING_UPDATES = 12       USER_ALERT = 14
GET_UPDATES = 16          ACK_BROWSER_PRESENCE = 17
```

## Bugle Routes

```
DataEvent = 19   // Normal RPC data
PairEvent = 14   // Pairing lifecycle
GaiaEvent = 7    // Google account pairing
```

## References

- [mautrix-gmessages](https://github.com/mautrix/gmessages) — Go reference implementation (AGPL-3.0)
- [mautrix-gmessages proto definitions](https://github.com/mautrix/gmessages/tree/main/pkg/libgm/gmproto)
- [Beeper Google Messages bridge docs](https://help.beeper.com/en_US/android/google-messages)
