# Google Messages Bugle Protocol — Reverse Engineering Notes

Findings from implementing a Google Messages bridge in ZeroAI (Rust), based on the
[mautrix-gmessages](https://github.com/mautrix/gmessages) Go reference implementation
and live testing against Google's production APIs.

**Date**: 2026-03-19
**Status**: Active development — pairing + RPC auth + conversation fetch working, HMAC decryption under investigation

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

### Endpoints

| Endpoint | Path Suffix |
|----------|-------------|
| SendMessage | `.Messaging/SendMessage` |
| ReceiveMessages | `.Messaging/ReceiveMessages` |
| AckMessages | `.Messaging/AckMessages` |
| RegisterPhoneRelay | `.Pairing/RegisterPhoneRelay` |
| RegisterRefresh | `.Registration/RegisterRefresh` |
| Media Upload | `/upload` (on base URL directly) |

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

## Two Crypto Systems

The protocol uses two entirely different encryption schemes for different purposes:

### 1. AES-256-CTR + HMAC-SHA256 (RPC Messages)

Used for all **RPC message encryption/decryption** between the web client and phone.

Wire format: `ciphertext || IV (16 bytes) || HMAC-SHA256 (32 bytes)`

- Random 16-byte IV per message
- AES-256-CTR encryption
- HMAC-SHA256 computed over `ciphertext || IV` (not ciphertext alone)
- Keys established during `RegisterPhoneRelay` (random 32-byte AES key + 32-byte HMAC key)
- Keys are embedded in the QR code so the phone learns them at scan time

### 2. AES-256-GCM (Media Only)

Used for **media file encryption** during upload/download. A fresh random 32-byte key
is generated per upload.

Wire format: `0x00 || log2(chunkSize) || [nonce(12) || ciphertext || tag(16)]...`

- Chunked encryption with 32KB raw chunks
- AAD per chunk: `isLastChunk (1 byte) || chunkIndex (4 bytes big-endian)`
- The `decryptionKey` is stored in the `MediaContent` proto so the recipient can decrypt

---

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

The `requestID` for SetActiveSession is set to the **session ID** itself (not a random
UUID). This same session ID is then reused as `OutgoingRPCData.sessionID` for all
subsequent RPCs. The upstream tracks this via `ResetSessionID()` → `postConnect()`.

Without this call, the phone will NOT push conversations or messages on the long-poll
stream, even if `ListConversations` succeeds.

### Step 7: ListConversations

Send `ListConversations` (ActionType 1) with `MessageType=BUGLE_ANNOTATION` (16) for
the first call. The response arrives asynchronously on the long-poll as an encrypted
`DataEvent`.

---

## Sending RPC Actions

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
  requestID: string,           // field 1
  action: ActionType,          // field 2
  unencryptedProtoData: bytes, // field 3
  encryptedProtoData: bytes,   // field 5 — AES-256-CTR + HMAC-SHA256 encrypted
  sessionID: string,           // field 6
}
```

The inner request (e.g., `ListConversationsRequest`) is first `proto.Marshal`ed, then
encrypted with AES-CTR, then placed in `encryptedProtoData`. The `OutgoingRPCData` is
then `proto.Marshal`ed (binary) and placed in `OutgoingRPCMessage.Data.messageData`.

### Request-Response Correlation

The same UUID `requestID` is placed in three locations:
- `OutgoingRPCMessage.Auth.requestID`
- `OutgoingRPCMessage.Data.requestID`
- `OutgoingRPCData.requestID`

Responses arrive on the long-poll stream. The `RPCMessageData.sessionID` matches the
`OutgoingRPCData.sessionID` (**not** the requestID). The session handler correlates
responses using the session ID. Response timeout is **5 seconds**.

### MessageType

| Value | Name | Usage |
|-------|------|-------|
| 2 | BUGLE_MESSAGE | Normal RPC actions |
| 16 | BUGLE_ANNOTATION | **First** ListConversations call after pairing |

The upstream uses `BUGLE_ANNOTATION` for the initial conversation fetch (tracked via a
`conversationsFetchedOnce` flag), then `BUGLE_MESSAGE` for all subsequent calls.

---

## Incoming Data Pipeline (Decrypt → Decode → Route)

All responses and push events arrive on the long-poll stream. The processing pipeline:

### Step 1: Parse LongPollingPayload

The long-poll returns chunked PBLite-encoded JSON. Each chunk is parsed into:

```protobuf
message LongPollingPayload {
    optional IncomingRPCMessage data = 2;
    optional EmptyArr heartbeat = 3;
    optional StartAckMessage ack = 4;
    optional EmptyArr startRead = 5;
}
```

### Step 2: Route by BugleRoute

```protobuf
message IncomingRPCMessage {
    string responseID = 1;
    BugleRoute bugleRoute = 2;
    // ...
    bytes messageData = 12;
}
```

Three routes:
- **PairEvent (14)**: `messageData` decoded as `RPCPairData` (pairing lifecycle)
- **GaiaEvent (7)**: `messageData` decoded as `RPCGaiaData` (Google account pairing)
- **DataEvent (19)**: The main data path — see below

### Step 3: Decode RPCMessageData

For DataEvents, `messageData` is decoded into:

```protobuf
message RPCMessageData {
    string sessionID = 1;
    int64 timestamp = 3;
    ActionType action = 4;
    bytes unencryptedData = 5;
    bool bool1 = 6;
    bool bool2 = 7;
    bytes encryptedData = 8;
    bool bool3 = 9;
    bytes encryptedData2 = 11;
}
```

### Step 4: Decrypt

- If `encryptedData` (field 8) is present → decrypt with AES-256-CTR + HMAC-SHA256
- If `encryptedData2` (field 11) is present → same decrypt, but result is wrapped in
  `EncryptedData2Container` (used for account change events)
- The `action` field determines which response proto to unmarshal the decrypted bytes into

### Step 5: Dispatch by ActionType

The `action` field maps to a response type:

| Action | Response Proto |
|--------|---------------|
| LIST_CONVERSATIONS (1) | `ListConversationsResponse` |
| LIST_MESSAGES (2) | `ListMessagesResponse` |
| SEND_MESSAGE (3) | `SendMessageResponse` |
| LIST_CONTACTS (6) | `ListContactsResponse` |
| GET_UPDATES (16) | `UpdateEvents` (the main push event wrapper) |

### Step 6: UpdateEvents (Push Events)

`GET_UPDATES` responses carry real-time push events in a oneof wrapper:

```protobuf
message UpdateEvents {
    oneof event {
        ConversationEvent conversationEvent = 2;
        MessageEvent messageEvent = 3;
        TypingEvent typingEvent = 4;
        Settings settingsEvent = 5;
        UserAlertEvent userAlertEvent = 6;
        BrowserPresenceCheckEvent browserPresenceCheckEvent = 7;
        AccountChangeOrSomethingEvent accountChange = 15;
    }
}
```

Both `ConversationEvent` and `MessageEvent` carry **full objects**, not diffs:

```protobuf
message ConversationEvent {
    repeated Conversation data = 2;
}

message MessageEvent {
    repeated Message data = 2;
}
```

A `ConversationEvent` fires when: metadata changes (name, status, mute, archive),
new messages arrive (latestMessage updates), or read status changes.

A `MessageEvent` fires when: new messages arrive, message status changes
(sent → delivered → read), or messages are deleted.

### Step 7: Deduplication

The upstream deduplicates using SHA-256 of decrypted data, stored in a ring buffer of
8 recent entries. If the same `(id, hash)` pair appears again, the event is silently
dropped.

### Step 8: Acknowledgment

Every incoming RPC message's `responseID` is queued for acknowledgment. Acks are
batched and sent every 5 seconds via the `AckMessages` endpoint.

### IsOld Flag

On connect, the server replays recent events. The `skipCount` (from `StartAckMessage.count`
in the long-poll ack) marks these replayed events as `IsOld = true`. Old conversation
events are ignored entirely; old message events are forwarded but flagged.

### Logged-Out Detection

If decrypted data is nil and `unencryptedData == [0x72, 0x00]`, the phone has logged
out. This triggers a `GaiaLoggedOut` event.

---

## ListConversations (ActionType 1)

### Request

```protobuf
message ListConversationsRequest {
    enum Folder {
        UNKNOWN = 0;
        INBOX = 1;
        ARCHIVE = 2;
        SPAM_BLOCKED = 5;
    }
    int64 count = 2;
    Folder folder = 4;
    optional Cursor cursor = 5;
}
```

### Response

```protobuf
message ListConversationsResponse {
    repeated Conversation conversations = 2;
    optional bytes cursorBytes = 3;
    optional Cursor cursor = 5;
}
```

### Conversation Proto

```protobuf
message Conversation {
    string conversationID = 1;
    string name = 2;
    LatestMessage latestMessage = 4;
    int64 lastMessageTimestamp = 5;  // microseconds
    bool unread = 6;
    bool isGroupChat = 10;
    ConversationStatus status = 12;  // ACTIVE, ARCHIVED, DELETED, SPAM_FOLDER, BLOCKED_FOLDER
    repeated Participant participants = 20;
    ConversationType type = 22;      // SMS=1, RCS=2
    ConversationSendMode sendMode = 24; // AUTO, XMS, XMS_LATCH
}
```

---

## ListMessages (ActionType 2)

### Request

```protobuf
message ListMessagesRequest {
    string conversationID = 2;
    int64 count = 3;
    Cursor cursor = 5;
}

message Cursor {
    string lastItemID = 1;
    int64 lastItemTimestamp = 2;  // milliseconds!
}
```

### Response

```protobuf
message ListMessagesResponse {
    repeated Message messages = 2;
    bytes someBytes = 3;
    int64 totalMessages = 4;
    Cursor cursor = 5;             // next-page cursor
}
```

### Message Proto

```protobuf
message Message {
    string messageID = 1;
    string conversationID = 3;
    string participantID = 5;
    int64 timestamp = 9;           // microseconds!
    repeated MessageInfo messageInfo = 10;
    MessageStatus messageStatus = 11;
    Participant senderParticipant = 15;
    repeated Reaction reactions = 19;
}

message MessageInfo {
    optional string actionMessageID = 1;
    oneof data {
        MessageContent messageContent = 2;
        MediaContent mediaContent = 3;
    }
}

message MessageContent {
    string content = 1;
}
```

### Pagination

Cursor-based. The cursor uses `lastItemID` + `lastItemTimestamp`.

**Critical timestamp gotcha**: Message `timestamp` is in **microseconds**, but
`Cursor.lastItemTimestamp` is in **milliseconds**. You must convert:
`cursor_ts = message.timestamp / 1000`.

Messages arrive in **reverse chronological order** (newest first). The upstream calls
`slices.Reverse()` before processing for chronological display.

If the response has no cursor but messages exist, fabricate one from the first (oldest)
message in the reversed list.

---

## ListContacts (ActionType 6)

### Request

```protobuf
message ListContactsRequest {
    int32 i1 = 5;   // = 1
    int32 i2 = 6;   // = 350
    int32 i3 = 7;   // = 50
}
```

The field names are reverse-engineered placeholders. The upstream hardcodes
`{I1: 1, I2: 350, I3: 50}`. These appear to be pagination/limit hints.

### Response

```protobuf
message ListContactsResponse {
    repeated Contact contacts = 2;
}

message Contact {
    string participantID = 1;
    string name = 2;
    ContactNumber number = 3;
    string avatarHexColor = 7;
    bool unknownBool = 10;
    string contactID = 11;
}

message ContactNumber {
    int32 mysteriousInt = 1;       // 2 for contact, 7 for user input
    string number = 2;
    string number2 = 3;
    optional string formattedNumber = 4;
}
```

**No pagination** — no cursor mechanism exists. The `i2: 350` and `i3: 50` may act as
limits, but all contacts are fetched in a single call.

There is also `LIST_TOP_CONTACTS` (ActionType 28) with `ListTopContactsRequest{Count: 8}`
that returns a small subset of frequently contacted people.

---

## SendMessage — Sending Texts (ActionType 3)

### Request

```protobuf
message SendMessageRequest {
    string conversationID = 2;
    MessagePayload messagePayload = 3;
    SIMPayload SIMPayload = 4;
    string tmpID = 5;
    bool forceRCS = 6;
    ReplyPayload reply = 8;
}

message MessagePayload {
    string tmpID = 1;
    MessagePayloadContent messagePayloadContent = 6;
    string conversationID = 7;
    string participantID = 9;
    repeated MessageInfo messageInfo = 10;
    string tmpID2 = 12;
}

message MessagePayloadContent {
    MessageContent messageContent = 1;
}

message ReplyPayload {
    string messageID = 1;
}
```

### Building a Text Message

A single `MessageInfo` entry with `MessageContent`:

```
messageInfo: [{
    data: MessageContent { content: "Hello world" }
}]
```

### Building a Media Message

First entry is `MediaContent`, optional second entry is caption:

```
messageInfo: [
    { data: MediaContent { ... uploaded media ... } },
    { data: MessageContent { content: "caption text" } }  // only if caption != filename
]
```

### Building a Reply

Set `reply = ReplyPayload { messageID: "<target-message-id>" }`.

### Key Fields

- `tmpID` appears in **three places**: `SendMessageRequest.tmpID`,
  `MessagePayload.tmpID`, and `MessagePayload.tmpID2` — all set to the same
  transaction ID string
- `forceRCS` is set when `ConversationType == RCS && SendMode == AUTO && ForceRCS`
- `SIMPayload` is needed for dual-SIM devices
- `participantID` in MessagePayload is the sender's own outgoing ID

### Response

```protobuf
message SendMessageResponse {
    enum Status {
        UNKNOWN = 0;
        SUCCESS = 1;
        FAILURE_2 = 2;
        FAILURE_3 = 3;
        FAILURE_4 = 4;   // not default SMS app?
    }
    AccountChangeOrSomethingEvent googleAccountSwitch = 2;
    Status status = 3;
}
```

---

## Media Upload Sub-Flow

Before sending a media message, the file must be uploaded:

1. **Generate key**: Random 32-byte AES-256-GCM key
2. **Encrypt**: Chunked AES-256-GCM (see "Two Crypto Systems" above)
3. **Start upload**: POST to `instantmessaging-pa.googleapis.com/upload` with
   base64-encoded `StartMediaUploadRequest` proto → returns resumable upload URL
4. **Finalize upload**: POST encrypted bytes to the upload URL → returns
   `UploadMediaResponse` with `mediaID`
5. **Build MediaContent**:

```protobuf
message MediaContent {
    MediaFormats format = 1;
    string mediaID = 2;
    string mediaName = 4;
    int64 size = 5;
    Dimensions dimensions = 6;
    bytes mediaData = 7;
    string thumbnailMediaID = 9;
    bytes decryptionKey = 11;
    bytes thumbnailDecryptionKey = 12;
    string mimeType = 14;
}
```

---

## Typing Indicators (ActionType 12)

### Sending (Outgoing)

```protobuf
message TypingUpdateRequest {
    message Data {
        string conversationID = 1;
        bool typing = 3;
    }
    Data data = 2;
    SIMPayload SIMPayload = 3;
}
```

Fire-and-forget — no response expected. The upstream only sends `typing: true`; the
phone handles the "stopped typing" timeout automatically.

### Receiving (Incoming)

Arrives as `UpdateEvents.typingEvent`:

```protobuf
message TypingEvent {
    TypingData data = 2;
}

message TypingData {
    string conversationID = 1;
    User user = 2;
    TypingTypes type = 3;
}

message User {
    int64 field1 = 1;
    string number = 2;
}

enum TypingTypes {
    STOPPED_TYPING = 0;
    STARTED_TYPING = 1;
}
```

The upstream uses a 15-second timeout for `STARTED_TYPING` and immediate clear for
`STOPPED_TYPING`.

---

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

---

## Action Types (Complete)

```
LIST_CONVERSATIONS    =  1    SEND_MESSAGE           =  3
LIST_MESSAGES         =  2    MESSAGE_UPDATES         =  4
LIST_CONTACTS         =  6    CONVERSATION_UPDATES    =  7
BROWSER_PRESENCE_CHECK = 11   TYPING_UPDATES          = 12
USER_ALERT            = 14    GET_UPDATES             = 16
ACK_BROWSER_PRESENCE  = 17    LIST_TOP_CONTACTS       = 28
```

Note: `MESSAGE_UPDATES (4)` and `CONVERSATION_UPDATES (7)` exist in the enum but are
**not used as outgoing request actions**. All incoming push events use `GET_UPDATES (16)`
with the `UpdateEvents` oneof wrapper. The individual action type values may appear in
`RPCMessageData.action` for direct request-response correlation.

## Bugle Routes

```
DataEvent = 19   // Normal RPC data
PairEvent = 14   // Pairing lifecycle
GaiaEvent = 7    // Google account pairing
```

---

## Pagination Summary

| Operation | Mechanism | Details |
|-----------|-----------|---------|
| ListConversations | `Cursor` + count | `count` (field 2), `folder` (field 4), `cursor` (field 5). Response has `cursorBytes` (field 3) and `cursor` (field 5). |
| ListMessages | `Cursor` | `cursor` in request field 5, response returns next cursor in field 5. **Timestamps in milliseconds** (not microseconds like Message.timestamp). |
| ListContacts | Fixed params only | `i1=1, i2=350, i3=50` — no cursor, single fetch. |
| ListTopContacts | Count only | `count=8`, no cursor. |

---

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

### Timestamp Unit Mismatch

Message `timestamp` fields are in **microseconds**, but `Cursor.lastItemTimestamp` is
in **milliseconds**. Converting wrong will either fetch no messages (cursor too far in
the future) or re-fetch everything (cursor at epoch).

### BUGLE_ANNOTATION vs BUGLE_MESSAGE

The **first** `ListConversations` call after pairing must use `MessageType =
BUGLE_ANNOTATION (16)`. All subsequent calls use `BUGLE_MESSAGE (2)`. Using the wrong
type on the first call causes the server to not send conversation data. Track this with
a `conversationsFetchedOnce` flag.

### tmpID Triple Placement

When sending messages, the same transaction ID must appear in `SendMessageRequest.tmpID`,
`MessagePayload.tmpID`, AND `MessagePayload.tmpID2`. Missing any of the three can cause
the phone to reject or misroute the message.

---

## References

- [mautrix-gmessages](https://github.com/mautrix/gmessages) — Go reference implementation (AGPL-3.0)
- [mautrix-gmessages proto definitions](https://github.com/mautrix/gmessages/tree/main/pkg/libgm/gmproto)
- [Beeper Google Messages bridge docs](https://help.beeper.com/en_US/android/google-messages)
