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
| `mesh-core` | `mesh-core/` | Kotlin/JVM, no Android imports, no crypto library | stdlib only in main scope (tests may use `crypto-jvm`); crypto behind the `Crypto` interface |
| `crypto-jvm` | `crypto-jvm/` | Kotlin/JVM | `mesh-core`, lazysodium-java |
| `simulator` | `simulator/` | Kotlin/JVM CLI | `mesh-core`, `crypto-jvm` |
| Android app | `android/` | Kotlin, Compose, Room | `mesh-core`, lazysodium-android, Nearby Connections |
| Backend | `supabase/` | SQL migrations, Deno Edge Functions | libsodium-wrappers |
| Dashboard | `dashboard/` | TypeScript, React, Vite, MapLibre | supabase-js |

**Rule:** all relay logic lives in `mesh-core`. The Android layer only adapts transport,
storage and OS lifecycle to interfaces `mesh-core` defines (`Transport`, `MessageStore`,
`Clock`, `Crypto`). This is what makes the 100-phone laptop simulation possible.

## 2. Message envelope (target < 300 bytes)

Binary, little-endian, versioned. Cleartext header is what relays need to route; the body
is readable only by the server. Exact byte offsets and per-kind bodies:
[docs/milestones/M1-mesh-core.md §5](docs/milestones/M1-mesh-core.md).

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
   (envelopes and tombstones). At the caps (2,000 + 4,000) that is 48 KB, sent as up to 3
   SUMMARY frames of ≤ 16 KB each to stay under Nearby's 32 KB payload limit. No Bloom
   filter until measured as needed.
3. Each side sends what the other is missing, in this order:
   **ACKs/RECEIPTs → Critical → Urgent → Info**, oldest first within a level.
4. Disconnect after the swap, after a 10 s stall, or at a 120 s hard cap (enough for a
   full store at ~20 envelopes/s). Everything already validated is kept. A node runs one
   session at a time. Cool down 2 min per peer session token before reconnecting.

### 3.2 Receive rules (in order, drop on first failure)

1. Version known, size < 300 bytes.
2. Signature valid (sender key for SOS, pinned server key for ACK/RECEIPT).
3. Not expired, not dated > 10 min in the future, `expiresAt − createdAt` ≤ the
   priority's TTL, `hops < 16`.
4. Not already held, not tombstoned.
5. Per-sender rate limit: ≤ 5 SOS and ≤ 10 UPDATE per sender key per rolling hour,
   counted by the **receiver's** clock in a local accept log (never by `createdAt`, which
   the sender controls).

Full ordered rule list with reject reasons: [M1 spec §6](docs/milestones/M1-mesh-core.md).

### 3.3 Storage

- Room DB on Android, in-memory store in simulator, same `MessageStore` interface.
- Cap 2,000 envelopes. Evict expired first, then oldest Info, then oldest Urgent.
  Critical and ACK/RECEIPT are never evicted before expiry; if the store holds only those,
  a new envelope is refused, except an ACK/RECEIPT whose SOS is held (it frees that slot).
  The node's own SOS is always stored.
- Tombstone cap 4,000; on overflow drop the one expiring soonest.
- An ACK **or RECEIPT** for `msgId` deletes that SOS and its UPDATEs from relays and keeps
  a tombstone until its `expiresAt`. The ACK/RECEIPT itself keeps travelling toward the
  origin. UPDATE envelopes carry their parent `refMsgId` in cleartext so relays can do this.
  UPDATEs are not tombstoned, so a late UPDATE (better location after the RECEIPT) still
  reaches the server.

### 3.4 Uplink (any phone with internet)

`POST /functions/v1/intake` with a batch of envelopes (base64 in JSON) plus the phone's
summary list. Response: a `RECEIPT` per accepted envelope and every `ACK` the server holds
for IDs in the summary. The phone injects those into its store so they flow back into the mesh.

### 3.5 Power

- Foreground service, type `connectedDevice` (Android 14+ requirement).
- **"No signal" means no validated network** (`NET_CAPABILITY_VALIDATED` absent on every
  network). All docs use "no signal" in this sense.
- **Start triggers:** (1) the Disaster Mode switch; (2) a `WorkManager` periodic job
  (every 15 min, the platform minimum; this is the app's only background poll). It records
  when signal was first missing and starts the service once that is ≥ 15 min ago. Because
  of the job period and Doze, the actual auto-on happens **15–30 min** after signal loss.
- Android 12+ blocks foreground-service starts from the background unless an exemption
  applies; battery-optimisation exemption (onboarding step, DESIGN.md §3.1) is the one we
  rely on. If the start throws `ForegroundServiceStartNotAllowedException`, post the
  `relay_auto_failed` notification (DESIGN.md §4) instead.
- A phone that isn't relaying can't hear a neighbour's SOS, so there is no "wake on SOS"
  (PLAN.md R10). Once on, relaying stays on until the user turns it off.
- Duty cycle: scan/advertise 20 s on, 40 s off by default.
- Battery < 20%: relay Critical + ACK only, duty cycle 10 s on / 110 s off.

## 4. Security model

| Threat | Control |
|---|---|
| Forged or altered SOS | Ed25519 signature checked by every relay and the server |
| Relays reading victim data | Body sealed to server key. Relays see priority, kind, timestamps, hops, body length, an UPDATE's parent id, and a per-install pseudonymous key (which links one install's messages) — never location, people or note |
| Fake "help is coming" | ACKs signed by pinned server key only |
| Spam flooding mesh | Per-sender relay rate limit, hop limit, expiry, storage cap |
| Spam reaching dashboard | Server-side rate limit per sender key; responder "mark false" flags the key |
| Server key leak | Private key only in Supabase secrets; `keyId` + pinned key set allow rotation |

Not solved in MVP (documented, accepted): Sybil attacks with many fresh keys; traffic
analysis of priority/timing.

## 5. Backend (Supabase)

- `reports` — `msg_id uuid pk`, `sender_key bytea`, `priority smallint`, `created_at`,
  `received_at`, `location geography(Point,4326)`, `accuracy_m`, `people`, `injured`,
  `expires_at` (from the envelope), `flags`, `note`, `hops`, `is_test bool`,
  `status` (`new|acked|resolved|false`), `acked_at`, `acked_by`, `closed_at` (set when
  status becomes `resolved` or `false`).
- `senders` — `sender_key pk`, `first_seen`, `report_count`, `flagged bool`.
- `profiles` — `user_id pk → auth.users`, `role` (`pending|responder`, default `pending`),
  `created_at`. A trigger creates the row on sign-up and **hard-codes `role = 'pending'`**
  (it never reads sign-up metadata).
- `report_metrics` — anonymous, kept after reports are deleted: `priority`, `hops`,
  `latency_s` (created → received), `ack_latency_s`, `is_test`, `day`. No location,
  sender key, note or msg_id.
- **Access:** sign-up by email magic link is open, but every new account is `pending`. The
  owner promotes accounts to `responder` by editing `profiles.role` in the Supabase console
  (no approval UI in MVP). Accounts can also be invited from the console.
- RLS: no anon access. Inserts only from the Edge Function (service role). Reads/updates of
  `reports` only where the caller's `profiles.role = 'responder'`. `pending` sees nothing.
  `profiles` has a SELECT policy for the caller's own row and **no UPDATE/INSERT/DELETE
  policy** for `authenticated`, so nobody can promote themselves; only the console (service
  role) changes `role`.
- **Auth email:** Supabase's built-in email sender is rate-limited and meant for
  development. It is enough for a handful of responders; if it isn't, a free-tier SMTP
  provider needs the owner's OK (AGENTS.md P6). Fallback: the owner creates accounts with a
  password in the console (PLAN.md R11).
- **Retention** (nightly `pg_cron` job): delete `is_test` reports 7 days after
  `received_at`; delete other reports 30 days after `closed_at` or `expires_at`, whichever
  comes first. Delete `senders` rows with no remaining reports.
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
| 2026-11-23 | Signing **and** sealing from M1 | App is public, so real locations pass through strangers' phones; adding sealing later would change the format and cut old phones off the mesh |
| 2026-11-23 | Invite + request access with a `pending` role; approval in the console | No admin UI to build; each responder keeps their own login |
| 2026-11-23 | Auto-delete reports (7 d test, 30 d real); keep anonymous metrics only | Data Privacy Act (RA 10173): keep personal data only as long as needed |
| 2026-11-23 | Relay = manual + auto-on 15–30 min after signal loss; no wake-on-SOS | A non-relaying phone can't hear an SOS; battery drain drives uninstalls |
| 2026-11-23 | libsodium everywhere | Same primitives on JVM, Android and Deno |
| 2026-11-23 | RECEIPT (not only ACK) clears relay copies | Once the server has it, relay copies only waste storage and airtime |
| 2026-11-23 | Sync session: one at a time per node, 10 s stall / 120 s cap, multi-frame SUMMARY | Avoids duplicate transfers; fits a full store; stays under Nearby's 32 KB payload limit |
| 2026-11-23 | Rate limit counted by receiver clock | `createdAt` is sender-controlled and can be backdated |
| 2026-11-23 | Crypto behind an interface; libsodium bindings in `crypto-jvm` / Android, not in `mesh-core` | Keeps `mesh-core` dependency-free and lets the same logic run on JVM and Android bindings |
| 2026-11-23 | `crypto-jvm` uses lazysodium-java 5.2.0 (MPL-2.0, unmodified) + JNA 5.17.0 (Apache-2.0 option of its dual licence); transitive resource-loader and slf4j-api are MIT; bundled libsodium is ISC | All compatible with Apache-2.0 as unmodified dependencies (P6) |
| 2026-11-23 | A full store still admits an ACK/RECEIPT whose SOS it holds; the node's own SOS is always stored | The ACK frees its own slot; dropping your own SOS is never right |
| 2026-11-23 | UPDATEs are not tombstoned when their SOS is cleared | A late UPDATE carries a better location the server still needs |
| 2026-11-23 | Simulator reports duplicates caused by a mid-session uplink separately from protocol duplicates (owner to confirm) | The SUMMARY is a snapshot; an uplink can add an id after it is sent. Found in the first baseline run (23 of 69,696 transfers) |
| 2026-11-23 | Gradle 9.9.2, Kotlin 2.4.21, JUnit 5.14.4, JDK 21 toolchain; foojay plugin pinned in `settings.gradle.kts` | Settings plugins can't read the version catalog |
