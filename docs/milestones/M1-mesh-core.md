# M1 — Mesh engine on a laptop (`mesh-core` + simulator)

**Status:** implemented 2026-11-23; results and open owner decision in [docs/devlog.md](../devlog.md). Owner walkthrough given; owner sign-off pending.
**Reads first:** [AGENTS.md](../../AGENTS.md), [ARCHITECTURE.md](../../ARCHITECTURE.md) §2–§4,
[PLAN.md](../../PLAN.md) §4–§5. If this file and ARCHITECTURE.md disagree, stop and ask;
don't guess.

---

## 1. Goal in one paragraph

Build the part of Taptap that decides *what moves where*: the binary message format, its
signing and sealing, the rules for accepting a message, the store with eviction and
tombstones, and the phone-to-phone sync session. All of it is pure Kotlin on the JVM, with no
Android. Then prove it works by running **100 virtual phones** walking around a virtual
map on a laptop, with a fake server, and measuring delivery rate and latency. M2 later
plugs real Bluetooth/Wi-Fi in underneath without changing this code.

## 2. Done when (acceptance checklist)

Every box must be true. Copy this list into the final walkthrough with ✅/❌.

- [ ] `gradlew test` passes from a clean clone on Windows (owner's machine) with only a JDK installed.
- [ ] `mesh-core` has **zero** `android.*` / `androidx.*` imports, and its `main` source set
      and compile classpath have no crypto library: crypto is behind the `Crypto` interface
      (P1). The *test* classpath may include `crypto-jvm`.
- [ ] Envelope codec round-trips every kind (SOS, UPDATE, ACK, RECEIPT); a test asserts the
      **max SOS envelope is 269 bytes** and every encoded envelope is **< 300 bytes** (P3).
- [ ] Every SOS/UPDATE is **signed** (Ed25519) and its body **sealed** (`crypto_box_seal`)
      with real libsodium in `crypto-jvm`.
- [ ] Each receive rule (§6) has at least one test that fails the rule and checks the
      exact `RejectReason`. Includes: tampered byte, forged signature, wrong server key,
      expired, future-dated, hop limit, duplicate, tombstoned, rate-limited, oversize.
- [ ] Changing `hops` does **not** break the signature; changing any other byte **does**.
- [ ] Store eviction order and tombstones tested (§7).
- [ ] Sync session sends in order **ACK/RECEIPT → Critical → Urgent → Info, oldest first**,
      sends nothing the peer already has, honours `criticalOnly`, ends on a 10 s stall or
      the 120 s cap, keeps everything validated before it ends, and enforces the 2-min
      per-peer cooldown (§8).
- [ ] Golden test vectors written to `mesh-core/src/test/resources/vectors/envelope-v1.txt`
      (§5.6) for the M3 Deno server to test against.
- [ ] Simulator CLI runs the `baseline` scenario in **< 60 s** on the owner's laptop and
      prints the metrics table + writes JSON (§10).
- [ ] `baseline` scenario (5 seeds) meets **delivery ≥ 95% within TTL** (PLAN.md §5.1).
      If it doesn't, **do not tune the scenario or weaken rules to pass**; report the
      numbers to the owner with an analysis (P2).
- [ ] Simulator reports **0 duplicate envelope transfers** (summary exchange works; holds
      because a node runs one session at a time, §8.2). Counted per envelope; a duplicate
      of an id the receiver got by uplink *during* that session is reported separately
      (`uplinkOverlapDuplicates`, §10.2). Split accepted by the owner on 2026-11-23.
- [ ] AGENTS.md "Commands" section filled in; ARCHITECTURE.md updated for anything that
      changed; `docs/devlog.md` entry; owner walkthrough given (W8).

## 3. Scope

**In:** Gradle multi-module setup · data model · binary codec · `Crypto` interface + JVM
libsodium implementation · envelope factory (build SOS/UPDATE) · fake-server helpers
(open body, issue RECEIPT/ACK) · receive validator · `MessageStore` + in-memory store ·
eviction + tombstones · sync session state machine + wire frames · `Node` glue · uplink
*model types* · simulator (world, mobility, contacts, internet zones, fake server,
metrics, CLI) · test vectors.

**Out (later milestones; do not build):** Android app, Nearby Connections, Room, foreground
service, duty cycling, battery logic beyond the `criticalOnly` flag, HTTP/JSON uplink,
Supabase, dashboard, UI, i18n, Bloom filters, BLE transport, any network code.

## 4. Repo layout and build

```
Taptap/
├── settings.gradle.kts          # include(":mesh-core", ":crypto-jvm", ":simulator")
├── build.gradle.kts             # shared config only (no code)
├── gradle/libs.versions.toml    # every version pinned here, nowhere else
├── gradlew, gradlew.bat, gradle/wrapper/   # commit the wrapper (small, required)
├── mesh-core/                   # pure Kotlin, explicitApi(), no crypto lib
│   └── src/{main,test}/kotlin/io/github/llenoxinn/taptap/mesh/...
├── crypto-jvm/                  # SodiumJvmCrypto : Crypto (lazysodium-java)
└── simulator/                   # CLI app (application plugin)
```

Build rules:
- **Kotlin JVM plugin**, Gradle **Kotlin DSL**, **version catalog** for every version
  (one exception: the foojay settings plugin, because settings plugins can't read the catalog).
- **JDK toolchain 21** via the `foojay-resolver-convention` settings plugin, so Gradle
  downloads a free JDK 21 if needed. The owner has Java 24 installed; the Gradle version
  must support *running* on Java 24 (Gradle ≥ 8.14). Pin exact, current stable versions
  of Gradle, Kotlin, JUnit 5 and lazysodium-java at the time of work; check each one's
  release notes rather than trusting memory.
- `mesh-core`: `kotlin { explicitApi() }`. Only **main-scope** dependency: Kotlin stdlib.
  Test deps: JUnit 5 (`kotlin("test")` with JUnit 5 is fine) and `crypto-jvm` (below).
- `crypto-jvm`: depends on `mesh-core` and `lazysodium-java` (+ its JNA dependency).
  **Check that the licence is compatible with Apache-2.0** (lazysodium is MPL-2.0, which
  is fine for an unmodified dependency; JNA is Apache-2.0/LGPL). Record it in the
  decisions log.
- `simulator`: depends on `mesh-core`, `crypto-jvm`. `application` plugin, main class
  `...simulator.MainKt`. No CLI library: parse `--key value` args by hand (YAGNI).
- `mesh-core` tests that need real crypto use `crypto-jvm` as a **test** dependency
  (`testImplementation(project(":crypto-jvm"))`). That's allowed; the *main* source set
  stays crypto-free.
- Package root: `io.github.llenoxinn.taptap`.

## 5. Envelope (exact binary layout, version 1)

All integers **little-endian**. Offsets are bytes from the start.

### 5.1 Header (all kinds)

| Offset | Size | Field | Type | Notes |
|---|---|---|---|---|
| 0 | 1 | `version` | u8 | `1` |
| 1 | 1 | `kind` | u8 | `0 SOS`, `1 UPDATE`, `2 ACK`, `3 RECEIPT` |
| 2 | 16 | `msgId` | bytes | random 128-bit (`SecureRandom`, injectable for tests) |
| 18 | 1 | `priority` | u8 | `0 Critical`, `1 Urgent`, `2 Info`; ACK/RECEIPT copy the referenced message's priority |
| 19 | 4 | `createdAt` | u32 | unix seconds |
| 23 | 4 | `expiresAt` | u32 | unix seconds |
| 27 | 1 | `hops` | u8 | **not signed**; `0` at origin |
| 28 | 32 | `senderKey` | bytes | SOS/UPDATE: device Ed25519 public key. ACK/RECEIPT: server Ed25519 public key |
| 60 | 1 | `keyId` | u8 | index into the `ServerKeyring` (§9): SOS/UPDATE sealed to that entry's `boxPub`; ACK/RECEIPT signed by that entry's `signPub` |
| 61 | n | `body` | bytes | per kind, below; `n = totalLength − 61 − 64` |
| 61+n | 64 | `sig` | bytes | Ed25519 over bytes `[0, 27)` ++ `[28, 61+n)` |

Fixed overhead = 61 + 64 = **125 bytes**.

### 5.2 SOS body (sealed)

`body = crypto_box_seal(payload, keyring[keyId].boxPub)` → `48 + len(payload)` bytes.

| Offset | Size | Field | Type | Notes |
|---|---|---|---|---|
| 0 | 4 | `lat` | i32 | degrees × 1e7; `Int.MIN_VALUE` = no fix yet |
| 4 | 4 | `lon` | i32 | degrees × 1e7; `Int.MIN_VALUE` = no fix yet |
| 8 | 2 | `accuracyM` | u16 | metres, saturate at 65535 |
| 10 | 2 | `fixAgeS` | u16 | seconds since the fix, saturate |
| 12 | 1 | `people` | u8 | 1–255 |
| 13 | 1 | `injured` | u8 | 0–255, ≤ people |
| 14 | 2 | `flags` | u16 | bit 0 Flooding, 1 Elderly, 2 Child, 3 Pregnant, 4 NeedMedicine, 15 TEST; others 0 |
| 16 | 0–80 | `note` | UTF-8 | length = rest of payload; truncate on a code-point boundary |

Max payload 96 → max SOS envelope **125 + 48 + 96 = 269 bytes**.

### 5.3 UPDATE body (parent cleartext, location sealed)

Better location for an earlier SOS. Same header; `priority` = parent's; `expiresAt` = parent's.
`refMsgId` is **cleartext** so relays can link an UPDATE to its parent for eviction and
ACK/RECEIPT clean-up (§7); relays can't read the location.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 16 | `refMsgId` | cleartext |
| 16 | 60 | sealed | `crypto_box_seal` of `lat i32, lon i32, accuracyM u16, fixAgeS u16` (12 B) |

→ 76-byte body, 201-byte envelope.

### 5.4 ACK / RECEIPT body (cleartext, server-signed)

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 16 | `refMsgId` | the SOS (or UPDATE) this answers |
| 16 | 1 | `status` | RECEIPT: `0 received`. ACK: `0 acknowledged`, `1 resolved`, `2 false` |
| 17 | 4 | `at` | unix seconds of the server event |

→ 21-byte body, 146-byte envelope. `expiresAt` = referenced message's `expiresAt`.
The server generates a fresh random `msgId` for every ACK/RECEIPT, and `createdAt = at`.

### 5.5 Expiry by priority (set by the origin)

Critical 72 h · Urgent 48 h · Info 24 h (`expiresAt = createdAt + ttl`).

### 5.6 Golden vectors

A test (tagged so it can be re-run deliberately) generates fixed keys from fixed seeds,
builds one envelope of each kind with fixed `msgId`/timestamps/payload, and writes
`vectors/envelope-v1.txt` as `name=hex` lines: device seed, server sign seed, server box
keypair, each envelope's hex, and each SOS's plaintext payload hex. A second test reads
the file and asserts decoding + verification + opening reproduce the inputs. Sealing uses
a random ephemeral key, so vectors store the *sealed output* and test *opening*, not
byte-identical re-sealing.

## 6. Receive rules (validator)

`Validator.check(bytes, context): ReceiveOutcome` → `Accepted(envelope)` or
`Rejected(reason: RejectReason)`. Check **in this order** and return on the first failure:

| # | Rule | `RejectReason` |
|---|---|---|
| 1 | `bytes.size` in `[125, 300)` | `SIZE` |
| 2 | `version == 1`, known `kind`, valid `priority` | `UNKNOWN_FORMAT` |
| 3 | Body parses for its kind (length rules) | `MALFORMED` |
| 4 | SOS/UPDATE: `sig` verifies with `senderKey` | `BAD_SIGNATURE` |
| 4b | ACK/RECEIPT: `keyId` exists in the pinned `ServerKeyring`, `senderKey == keyring[keyId].signPub`, and `sig` verifies | `UNTRUSTED_SERVER` / `BAD_SIGNATURE` |
| 5 | `expiresAt > now` | `EXPIRED` |
| 6 | `createdAt ≤ now + 600` (10 min clock-skew allowance) | `FUTURE_DATED` |
| 7 | `expiresAt − createdAt ≤ ttl(priority)` (§5.5). UPDATE/ACK/RECEIPT pass naturally: they copy the parent's `expiresAt` and have a later `createdAt` | `TTL_TOO_LONG` |
| 8 | `hops + 1 ≤ 16` (receiver stores `hops + 1`) | `HOP_LIMIT` |
| 9 | `msgId` not in store and not tombstoned | `DUPLICATE` / `TOMBSTONED` |
| 10 | Rate limit per `senderKey`, counted in a **local accept log keyed by the receiver's own clock** (not `createdAt`, which senders control; not the store, which shrinks): ≤ 5 SOS and ≤ 10 UPDATE accepted per rolling hour. Log entries older than 1 h are dropped. Not applied to ACK/RECEIPT | `RATE_LIMITED` |
| 11 | Store policy admits it (§7) | `STORE_FULL` |

Relays **cannot** open sealed bodies, so they never validate payload contents. The fake
server validates contents (people ≥ 1, injured ≤ people) after opening.

## 7. Store

```kotlin
public interface MessageStore {
    public fun get(msgId: MsgId): Envelope?
    public fun put(env: Envelope)                    // stores as given (hops already incremented)
    public fun remove(msgId: MsgId)
    public fun all(): List<Envelope>
    public fun addTombstone(msgId: MsgId, until: Long)
    public fun isTombstoned(msgId: MsgId): Boolean
    public fun removeTombstone(msgId: MsgId)          // for the tombstone cap (policy)
    public fun tombstones(): Map<MsgId, Long>         // id → until
    public fun purgeExpired(now: Long)               // removes expired envelopes + tombstones
    public fun summary(): Set<IdPrefix>              // 8-byte prefixes of envelopes + tombstones
}
```

`InMemoryMessageStore` lives in `mesh-core` (used by tests and simulator; Room comes in M2).

Policy (in `mesh-core`, **not** inside the store implementation):
- **Capacity 2,000 envelopes.** On overflow, evict: expired → UPDATEs whose parent is not
  held → oldest Info → oldest Urgent, but never an envelope of higher priority than the
  incoming one (an incoming Info can't push out an Urgent; ACK/RECEIPT rank as Critical). **Never evict Critical SOS or any ACK/RECEIPT** before
  expiry; if the store is full of those, reject the incoming envelope (`STORE_FULL`),
  except an ACK/RECEIPT whose referenced SOS is held (accepting it frees that slot).
- **Capacity 4,000 tombstones.** On overflow, drop the tombstone with the earliest
  `until`. (Worst case: a cleared SOS is accepted again and cleared again by its ACK.)
- On accepting an **ACK or RECEIPT** for `refMsgId`: remove that SOS and all its UPDATEs,
  add a tombstone until the SOS's `expiresAt`, and **keep the ACK/RECEIPT envelope** so it
  keeps travelling back toward the origin.
- The node's **own** SOS/UPDATE is always stored, even past capacity (never drop your own SOS).
- UPDATEs are **not** tombstoned when their SOS is cleared: a late UPDATE (better location
  after the RECEIPT) must still reach the server, so relays accept and carry it.
- The origin's own SOS is also kept in an app-level outbox (M2); in M1, `Node` records
  "my SOS ids" so the simulator can detect when the origin receives its ACK.
- The summary uses 8-byte prefixes. A prefix collision (≈ 2⁻⁶⁴ per pair) only means a
  message is not sent in that session; accepted risk.

> **Decision taken in this spec:** a **RECEIPT** also clears relay copies (not only ACK).
> Once the server has a message, relays carrying it waste storage and airtime. Already
> recorded in ARCHITECTURE.md §3.3 and its decisions log.

## 8. Sync session (state machine)

Pure, transport-agnostic, deterministic. The transport (simulator now, Nearby in M2) feeds
events in and sends the frames it returns.

### 8.1 Frames

`[type u8][length u16 LE][payload]`

| Type | Name | Payload |
|---|---|---|
| 1 | `HELLO` | `protocolVersion u8`, `nodeToken 8 bytes`, `flags u8` (bit 0 = criticalOnly) |
| 2 | `SUMMARY` | `more u8` (1 = another SUMMARY frame follows), `count u16` (≤ 2,000), then `count × 8-byte prefix`. Max 6,000 prefixes (2,000 envelopes + 4,000 tombstones) = up to 3 frames of ≤ 16,003 B, each under Nearby's 32 KB BYTES payload limit |
| 3 | `ENVELOPE` | raw envelope bytes |
| 4 | `DONE` | empty |

A `HELLO` with an unknown `protocolVersion` ends the session (`FAILED`) without sending
anything else.

`nodeToken` is random per node and rotated every 24 h, **not** derived from `senderKey`
(privacy: peers can't link relaying to a sender identity).

### 8.2 Flow

0. **A node runs at most one session at a time.** Other in-range peers wait until it
   ends. This is what makes "0 duplicate transfers" achievable.
1. Both sides send `HELLO`, then their `SUMMARY` frame(s).
2. On receiving the peer's last `SUMMARY` frame (`more = 0`), compute `toSend` = held envelopes whose prefix is not
   in it, filtered by the **peer's** criticalOnly flag *and our own* (send only ACK/RECEIPT
   and Critical if either is set), sorted: kind ACK/RECEIPT first, then priority 0→2, then
   `createdAt` ascending, then `msgId` (stable tie-break).
3. Send each as `ENVELOPE`, then `DONE`.
4. Each incoming `ENVELOPE` goes through the validator → store (via `Node`).
5. Session ends when both `DONE`s are exchanged, or on a **stall** (no frame received for
   10 s), or at a **hard cap of 120 s** after `start()`, or on transport disconnect. Both
   timers use the injected `Clock` via `onTick()`. Envelopes already validated and stored
   are **kept** whatever the ending. (At 20 envelopes/s a full 2,000-envelope store needs
   100 s, so 120 s covers it.)
6. After a session with `nodeToken` T ends, refuse/skip a new session with T for **120 s**.
7. `toSend` is a snapshot taken at step 2. If the peer gains an envelope mid-session from
   elsewhere (its own uplink), it may still be sent once; the receiver rejects it as `DUPLICATE`.

### 8.3 API sketch

```kotlin
public class SyncSession(/* node, clock, config */) {
    public fun start(): List<Frame>
    public fun onFrame(frame: Frame): List<Frame>
    public fun onBytes(bytes: ByteArray): List<Frame>   // decode; malformed → FAILED
    public fun onTick()                       // checks stall/cap; never sends
    public fun onDisconnect()                 // transport lost the peer → DISCONNECTED
    public val state: SessionState
    public val stats: SessionStats
}
```

### 8.4 Type declarations (implement these names; fields may grow, names must not change)

```kotlin
@JvmInline public value class MsgId(public val bytes: ByteArray)     // 16 B; equals/hashCode via contentEquals/contentHashCode (wrap in a data class if value-class equality is insufficient)
@JvmInline public value class IdPrefix(public val value: Long)       // first 8 bytes of MsgId, little-endian

public enum class Kind { SOS, UPDATE, ACK, RECEIPT }
public enum class Priority { CRITICAL, URGENT, INFO }
public enum class AckStatus { ACKNOWLEDGED, RESOLVED, FALSE }

public class Envelope(/* header fields of §5.1 + raw body + sig + the original encoded bytes */)
public data class SosRequest(val priority: Priority, val lat: Int?, val lon: Int?, val accuracyM: Int,
    val fixAgeS: Int, val people: Int, val injured: Int, val flags: Int, val note: String)

public data class SignKeypair(val publicKey: ByteArray, val secretKey: ByteArray)  // 32 / 64 B
public data class BoxKeypair(val publicKey: ByteArray, val secretKey: ByteArray)   // 32 / 32 B
public data class ServerKeys(val signPub: ByteArray, val boxPub: ByteArray)
public class ServerKeyring(public val keys: Map<Int, ServerKeys>, public val currentKeyId: Int)

public enum class RejectReason { SIZE, UNKNOWN_FORMAT, MALFORMED, BAD_SIGNATURE, UNTRUSTED_SERVER,
    EXPIRED, FUTURE_DATED, TTL_TOO_LONG, HOP_LIMIT, DUPLICATE, TOMBSTONED, RATE_LIMITED, STORE_FULL }
public sealed interface ReceiveOutcome {
    public data class Accepted(val envelope: Envelope) : ReceiveOutcome
    public data class Rejected(val reason: RejectReason) : ReceiveOutcome
}

public sealed interface Frame { /* Hello, Summary(more, prefixes), EnvelopeFrame(bytes), Done */ }
public enum class SessionState { HANDSHAKE, EXCHANGING, DONE, STALLED, CAPPED, DISCONNECTED, FAILED }
public data class SessionStats(val sent: Int, val received: Int, val rejected: Map<RejectReason, Int>)

public data class MeshConfig(val storeCapacity: Int = 2000, val tombstoneCapacity: Int = 4000,
    val maxHops: Int = 16, val stallTimeoutS: Long = 10, val sessionCapS: Long = 120,
    val peerCooldownS: Long = 120, val clockSkewS: Long = 600)

public data class UplinkBatch(val envelopes: List<ByteArray>, val summary: Set<IdPrefix>)
public data class UplinkResult(val envelopes: List<ByteArray>)   // RECEIPTs + ACKs, re-validated on arrival
```

The validator's context is the `Node` itself (store, keyring, accept log, clock, config):
`Validator(node).check(bytes)`.

Malformed frames end the session (state `FAILED`), never crash.

## 9. Node, crypto, uplink types

```kotlin
public interface Clock { public fun now(): Long }                 // unix seconds
public interface Crypto {
    public fun signKeypair(seed: ByteArray? = null): SignKeypair
    public fun sign(message: ByteArray, secretKey: ByteArray): ByteArray       // 64 B
    public fun verify(sig: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean
    public fun boxKeypair(seed: ByteArray? = null): BoxKeypair
    public fun seal(plaintext: ByteArray, recipientPub: ByteArray): ByteArray  // +48 B
    public fun openSealed(ciphertext: ByteArray, pub: ByteArray, secret: ByteArray): ByteArray?
}
```

- `EnvelopeFactory` builds signed, sealed SOS/UPDATE from a `SosRequest` and the device keys.
- `ServerCodec` (in `mesh-core`, used by the fake server now and mirrored in Deno in M3):
  opens bodies, builds signed RECEIPT/ACK. It holds **server** secret keys
  (`ServerSecrets: keyId → {signSecret, boxSecret}`), which never go into any `Node`.
- Every `Node` is constructed with the public `ServerKeyring` (pinned keys). Tests and the
  simulator inject a keyring generated from fixed seeds.
- `Node` = store + validator + policy + keys + `criticalOnly` flag; creates `SyncSession`s;
  `sendSos(...)`; `uplinkBatch(): UplinkBatch` (all SOS/UPDATE held + summary);
  `applyUplinkResult(UplinkResult)` (inject RECEIPT/ACK envelopes through the validator).
- `UplinkBatch` / `UplinkResult` are plain Kotlin data classes; no HTTP/JSON in M1.
- Ed25519 **public** keys are used as `senderKey`; a device's secret keys never leave its `Node`.

## 10. Simulator

### 10.1 World model (discrete time, 1 s ticks)

| Parameter | `baseline` default | Notes |
|---|---|---|
| Area | 1,000 m × 1,000 m | |
| Nodes | 100 | ≈ a rural barangay's installed base |
| Mobility | random waypoint, walking 0.5–1.5 m/s, pause 0–600 s | 10% of nodes are vehicles at 5–10 m/s |
| Radio range | 30 m | Bluetooth-class |
| Contact setup | 5 s in range before a session starts | models discovery + connect |
| Transfer | 20 envelopes/s per session after setup | session ends (`DISCONNECTED`) if out of range |
| Concurrency | one session per node at a time | queued peers retry next tick (§8.2 step 0) |
| Internet zones | 2 circles, r = 50 m, at fixed random points | nodes inside can uplink every 30 s |
| SOS events | 50 over the first 2 h, random non-zone nodes, priority mix 40/40/20 | 20% also send one UPDATE 5–15 min later |
| Fake server | RECEIPT immediately; ACK after 2–10 min (responder delay) | only for non-Info, configurable |
| Duration | 6 h simulated | |
| Seeds | 1–5 | mobility, events, `msgId`s and node keys (via `seed`) all come from one seeded `Random`. Sealing uses libsodium's own random ephemeral key, so ciphertext bytes differ between runs; nothing in the metrics depends on them |

Presets: `baseline`, `sparse` (30 nodes), `dense` (300 nodes), `lowbattery` (baseline with
30% of nodes `criticalOnly`). Every parameter is overridable: `--nodes 200 --range 20`.

Contacts are found by pairwise distance each tick (O(n²) is fine up to ~300 nodes; add a
spatial grid only if `dense` exceeds 60 s).

### 10.2 Metrics (per run and aggregated over seeds)

- **Delivery rate** = SOS reaching the server before `expiresAt` / SOS sent, by priority.
- **Latency** SOS → server: median, p90, max.
- **ACK return rate** and **ACK latency** (server ACK → origin node holds it).
- **Duplicate transfers** (envelope received that the receiver already held/tombstoned) —
  must be 0. Split by cause: if a phone uploads *during* a session, the server's
  RECEIPT/ACK reaches it after its SUMMARY was sent, so the peer may still send that
  envelope. A duplicate whose id the receiver got by uplink during that session counts as
  `uplinkOverlapDuplicates` (wasted airtime, not a protocol fault; first baseline run:
  23 of 69,696 envelope transfers). Every other duplicate counts as a protocol duplicate.
- Bytes and envelopes transmitted, sessions started/completed/timed-out.
- Rejects by `RejectReason`.
- Max store size on any node.

Output: a table to stdout + `simulator/out/<preset>-<timestamp>.json` (`out/` is
git-ignored).

### 10.3 Commands (to be confirmed and written into AGENTS.md)

```
gradlew.bat :simulator:run --args="--preset baseline --seeds 1-5"
gradlew.bat :mesh-core:test
gradlew.bat :mesh-core:test --tests "*ValidatorTest*"
gradlew.bat test
```

## 11. Tests to write (minimum)

| Class | Cases |
|---|---|
| `EnvelopeCodecTest` | round-trip each kind; max SOS = 269; all < 300; note truncation on code points; `Int.MIN_VALUE` no-fix; reject truncated input |
| `SigningTest` | hops change keeps sig valid; flip each other byte → invalid |
| `SealingTest` | open with right key; wrong key → null; ciphertext = plaintext + 48 |
| `GoldenVectorsTest` | §5.6 |
| `ValidatorTest` | one case per `RejectReason`, plus the happy path for each kind |
| `StorePolicyTest` | eviction order; Critical/ACK never evicted; `STORE_FULL`; ACK/RECEIPT removes SOS + UPDATEs and tombstones; purge expired |
| `SyncSessionTest` | ordering; no resend of held ids; criticalOnly (both directions); stall at 10 s; cap at 120 s; envelopes kept after any ending; multi-frame SUMMARY; unknown protocol version → FAILED; cooldown 120 s; malformed frame → FAILED |
| `RateLimitTest` | 6th SOS from one key within an hour → `RATE_LIMITED`; backdated `createdAt` doesn't bypass it; accepted again after 1 h |
| `NodeTest` | 3-node chain A–B–C delivers A's SOS to C; RECEIPT from C flows back to A and clears B (SOS + its UPDATEs) |
| `SimulatorScenarioTest` | `baseline` 5 seeds: delivery ≥ 95%, 0 duplicates; same seed → identical metrics JSON (excluding timestamps in the file name) |

Rules: inject `Clock` and `Random`; no `Thread.sleep`; no real network; tests independent.

## 12. Task order and commits

One task = one or more atomic commits (G1). Run the module's tests once at the end of each
task (W2).

1. **Scaffold:** wrapper, settings, catalog, three modules, toolchain, `explicitApi`, one
   trivial test per module; fill AGENTS.md "Commands". Add `simulator/out/` to `.gitignore`.
2. **Model + codec + size tests** (§5.1–5.5).
3. **Crypto:** `Crypto` interface in core, `SodiumJvmCrypto`, signing/sealing tests.
4. **EnvelopeFactory + ServerCodec + golden vectors** (§5.6, §9).
5. **Validator** (§6).
6. **Store + policy** (§7) + rate-limit accept log (§6 rule 10).
7. **SyncSession + frames** (§8).
8. **Node + uplink types** (§9) + 3-node chain test.
9. **Simulator** world, mobility, contacts, zones, fake server, metrics, CLI (§10).
10. **Scenario tests + results:** run all presets, put the results table in the devlog,
    W5 review, owner walkthrough (W8).

Stop and ask the owner (don't decide alone) if: a pinned library's licence isn't
Apache-compatible; `baseline` misses 95%; the envelope would need to exceed 300 bytes; or
anything here contradicts ARCHITECTURE.md.

## 13. Likely pitfalls

- **Signing the wrong bytes.** Exclude exactly byte 27 (`hops`). Test it (§11).
- **Unsigned ints.** Kotlin has no u32 in `ByteBuffer`; read with `toLong() and 0xFFFFFFFF`.
  Use `ByteBuffer.order(LITTLE_ENDIAN)` everywhere.
- **Equality on `ByteArray`.** Use value classes (`MsgId`, `IdPrefix`) with `contentEquals`/
  `contentHashCode`, never raw `ByteArray` keys in maps or sets.
- **lazysodium native loading** on Windows: verify on the owner's machine in task 3, not at
  the end.
- **Non-determinism:** `HashMap` iteration order leaking into send order. Always sort
  explicitly (§8.2).
- **Simulator tuning to pass.** Forbidden (P2). Report instead.
