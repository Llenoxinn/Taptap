# Taptap — Architecture

Status: **design, pre-code.** When code disagrees with this file, fix one of them in the
same commit.

## 1. Components

```
 Offline phones                       Online phone              Cloud                    Responder
┌────────────┐  Nearby   ┌────────────┐  HTTPS  ┌──────────────────────────┐  Realtime  ┌───────────┐
│ Taptap app │◄────────►│ Taptap app │────────►│ Edge Function  /intake   │──────────►│ Dashboard │
│ mesh-core  │ BT/Wi-Fi │ mesh-core  │◄────────│ Postgres+PostGIS reports │◄──────────│ (React)   │
└────────────┘           └────────────┘ receipts└──────────────────────────┘   ACK     └───────────┘
                                          + ACKs
```

| Module | Path (planned) | Language | Depends on |
|---|---|---|---|
| `mesh-core` | `mesh-core/` | Kotlin/JVM, no Android imports | libsodium binding (lazysodium) |
| `simulator` | `simulator/` | Kotlin/JVM CLI | `mesh-core` |
| Android app | `android/` | Kotlin, Compose, Room | `mesh-core`, Nearby Connections |
| Backend | `supabase/` | SQL migrations, Deno Edge Functions | libsodium-wrappers |
| Dashboard | `dashboard/` | TypeScript, React, Vite, MapLibre | supabase-js |

**Rule:** all relay logic lives in `mesh-core`. The Android layer only adapts transport,
storage and OS lifecycle to interfaces `mesh-core` defines (`Transport`, `MessageStore`,
`Clock`, `Crypto`). This is what makes the 100-phone laptop simulation possible.

## 2. Message envelope (target < 300 bytes)

Binary, little-endian, versioned. Cleartext header is what relays need to route; the body
is readable only by the server.

| Field | Bytes | Cleartext? | Notes |
|---|---|---|---|
| `version` | 1 | yes | starts at `1` |
| `kind` | 1 | yes | `SOS`, `UPDATE` (better location), `ACK`, `RECEIPT` |
| `msgId` | 16 | yes | random 128-bit |
| `priority` | 1 | yes | `0 Critical`, `1 Urgent`, `2 Info` — cleartext so relays can prioritise |
| `createdAt` | 4 | yes | unix seconds |
| `expiresAt` | 4 | yes | Critical 72 h, Urgent 48 h, Info 24 h |
| `hops` | 1 | yes | **excluded from signature**; incremented per relay; drop at 16 |
| `senderKey` | 32 | yes | Ed25519 public key, per install (pseudonymous) |
| `keyId` | 1 | yes | which server key the body is sealed to / the ACK is signed with |
| `body` | 48 + payload | no | `crypto_box_seal` to server X25519 key (32 ephemeral + 16 MAC) |
| `sig` | 64 | yes | Ed25519 over every field except `hops` |

Body payload (before sealing): `lat`, `lon` (int32, 1e-7°), `accuracyM` (uint16),
`fixAgeS` (uint16), `people` (uint8), `injured` (uint8), `flags` (uint16: preset chips +
bit 15 = `TEST`), `note` (≤ 80 bytes UTF-8) = 96 bytes max.

Worst case = 125 cleartext/sig + 48 seal overhead + 96 payload = **269 bytes** (binary;
the < 300 target in PLAN.md §5 is this binary size, not the base64 uplink form). Codec has
a unit test that asserts the max size.

**ACK / RECEIPT** envelopes carry `refMsgId` + status in cleartext and are signed by the
**server** Ed25519 key named by `keyId`. The app pins a small set of server public keys
(current + next) so the key can rotate without breaking phones in the field.

**Test SOS** (DESIGN.md §3.6) sets the `TEST` flag; the server stores it with
`is_test = true` and the dashboard hides test reports unless a toggle is on.

## 3. Mesh protocol (store-carry-forward)

### 3.1 Session

1. Advertise + discover with Nearby Connections, strategy `P2P_CLUSTER`.
2. On connect, both sides send a **summary**: the first 8 bytes of every `msgId` they hold
   (SOS and ACK tombstones). 1,000 messages = 8 KB; the 2,000 cap = 16 KB. No Bloom filter
   until measured as needed.
3. Each side sends what the other is missing, in this order:
   **ACKs/RECEIPTs → Critical → Urgent → Info**, oldest first within a level.
4. Disconnect after the swap or 30 s, whichever comes first. Cool down 2 min per peer
   session token before reconnecting.

### 3.2 Receive rules (in order, drop on first failure)

1. Version known, size < 300 bytes.
2. Signature valid (sender key for SOS, pinned server key for ACK/RECEIPT).
3. Not expired, `hops < 16`.
4. Not already held, not tombstoned.
5. Per-sender rate limit: ≤ 5 SOS per sender key per hour accepted for relay.

### 3.3 Storage

- Room DB on Android, in-memory store in simulator, same `MessageStore` interface.
- Cap 2,000 envelopes. Evict expired first, then oldest Info, then oldest Urgent.
  Critical is never evicted before expiry.
- An ACK for `msgId` deletes that SOS and keeps a tombstone (16 bytes) until its `expiresAt`.

### 3.4 Uplink (any phone with internet)

`POST /functions/v1/intake` with a batch of envelopes (base64 in JSON) plus the phone's
summary list. Response: a `RECEIPT` per accepted envelope and every `ACK` the server holds
for IDs in the summary. The phone injects those into its store so they flow back into the mesh.

### 3.5 Power

- Foreground service, type `connectedDevice` (Android 14+ requirement).
- Duty cycle: scan/advertise 20 s on, 40 s off by default.
- Battery < 20%: relay Critical + ACK only, duty cycle 10 s on / 110 s off.

## 4. Security model

| Threat | Control |
|---|---|
| Forged or altered SOS | Ed25519 signature checked by every relay and the server |
| Relays reading victim data | Body sealed to server key. Relays see priority, kind, timestamps, hops, body length and a per-install pseudonymous key (which links one install's messages) — never location, people or note |
| Fake "help is coming" | ACKs signed by pinned server key only |
| Spam flooding mesh | Per-sender relay rate limit, hop limit, expiry, storage cap |
| Spam reaching dashboard | Server-side rate limit per sender key; responder "mark false" flags the key |
| Server key leak | Private key only in Supabase secrets; `keyId` + pinned key set allow rotation |

Not solved in MVP (documented, accepted): Sybil attacks with many fresh keys; traffic
analysis of priority/timing.

## 5. Backend (Supabase)

- `reports` — `msg_id uuid pk`, `sender_key bytea`, `priority smallint`, `created_at`,
  `received_at`, `location geography(Point,4326)`, `accuracy_m`, `people`, `injured`,
  `flags`, `note`, `hops`, `is_test bool`, `status` (`new|acked|resolved|false`),
  `acked_at`, `acked_by`.
- `senders` — `sender_key pk`, `first_seen`, `report_count`, `flagged bool`.
- RLS: no anon access. Inserts only from the Edge Function (service role). Reads/updates only
  for authenticated responder accounts.
- Realtime enabled on `reports` for the dashboard.
- Idempotent intake: `insert … on conflict (msg_id) do update set hops = least(reports.hops, excluded.hops)`.
  Intake **never** writes `status`, `acked_at` or `acked_by` — only responders do.
- Keep-alive: scheduled ping every 2 days so the free-tier project never pauses
  (PLAN.md R4; verify the current inactivity window before relying on it).

## 6. Android specifics

- `minSdk 26` (Android 8), `targetSdk` = latest stable.
- Permissions (set `maxSdkVersion` on the legacy ones; check against the Nearby Connections
  setup docs before M2):
  - All: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
    `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE` (28+)
  - ≤ 30: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_COARSE_LOCATION`
  - 31+: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
  - 33+: `NEARBY_WIFI_DEVICES`, `POST_NOTIFICATIONS`
  - 34+: `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- Location: `FusedLocationProvider` with last-known fallback; send `UPDATE` when accuracy improves by > 50%.
- Requires Google Play services (PLAN.md R3).

## 7. Testing strategy

| Layer | What | How |
|---|---|---|
| `mesh-core` | codec, signature, receive rules, eviction, ordering | JUnit 5, deterministic `Clock` |
| `simulator` | delivery rate, latency, duplicate traffic on N nodes | seeded random-walk scenarios; asserts PLAN.md §5 targets |
| Edge Function | verify/decrypt/insert, idempotency, rate limit | `deno test` |
| Dashboard | queue sort, ack action | Vitest |
| Android | permissions + service lifecycle | manual checklist on 2+ budget phones (M4) |

## 8. Decisions log

| Date | Decision | Why |
|---|---|---|
| 2026-11-23 | Personal project; no LGU, no SMS gateway in MVP | Ship a working app first (PLAN.md §0) |
| 2026-11-23 | Plain ID-list summary instead of Bloom filter | ≤ 16 KB even at the storage cap; YAGNI |
| 2026-11-23 | Info priority ("I'm safe", hazards) is in MVP | Cheap to support; reduces "is my family OK" load on responders |
| 2026-11-23 | libsodium everywhere | Same primitives on JVM, Android and Deno |
