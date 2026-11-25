# Devlog

Newest entry first. One entry per work session (AGENTS.md W8).

## 2026-11-25 — M2 built

- **Done:** M2 Android citizen app (`:android` module on AGP 9.5.2 built-in Kotlin,
  `compileSdk`/`targetSdk` 36, `minSdk` 26, Compose BOM `2026.06.01`, Room 2.8.5, KSP 2.3.14).
  Installed Android SDK command-line tools (`platforms;android-36`, `platform-tools`,
  `build-tools;36.1.0`) locally with `local.properties` git-ignored. Implemented
  `SodiumAndroidCrypto` (`lazysodium-android:5.2.0` + `jna:5.17.0@aar`), Android Keystore
  AES-256-GCM device Ed25519 seed store (`DeviceIdentity`), pinned M2 test server keyring,
  `RoomMessageStore` + `outbox`/`outbox_peers`, `LocationUpdatePolicy` (>50% accuracy improvement
  or first fix triggers `UPDATE`) + `AndroidLocationProvider`, `MeshRuntime`,
  `NearbySessionDriver` (`P2P_CLUSTER`, tie-broken initiation, payload-flush wait before
  disconnect, 20s/40s and low-battery 10s/110s duty cycle), `RelayService` (`connectedDevice`
  foreground service), Compose UI (Onboarding, Home with carried-store inspector sheet,
  ≤ 3-tap SOS flow, P4-enforced Status screen, Settings with Test SOS), and full
  English / Filipino / Cebuano translations. Added a direct-extraction fallback in
  `SodiumJvmCrypto` for mixed classpaths under Robolectric and bounded Gradle memory in
  `gradle.properties` for 5 GB RAM hosts.
- **Checks:** 78 tests pass (`gradlew.bat test`: 64 `mesh-core`, 4 `crypto-jvm`, 4 `simulator`,
  6 `android` unit/Robolectric tests), `:android:assembleDebug` builds
  `android/build/outputs/apk/debug/android-debug.apk` (16,877,384 B), `mesh-core` has 0
  Android imports. Fresh-eyes W5 review (`flash_lite`) found no bugs, leaks, or rule
  violations.
- **Stopped at:** M2 built, verified, committed, and pushed to `origin/main`; ready for the
  owner's two-phone airplane-mode test (`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
  install -r android\build\outputs\apk\debug\android-debug.apk`).
- **Next:** Owner tests Phone A -> Phone B in airplane mode (Bluetooth on); then M3
  (Supabase schema/RLS/Edge Function `/intake`, Android HTTP uplink client, and React/MapLibre
  responder dashboard).

## 2026-11-23 (night) — M1 follow-ups

- **Done:** pushed M1 to GitHub. Owner **accepted the duplicate split** (protocol
  duplicates must be 0; uplink-overlap duplicates are reported separately). Eviction no
  longer lets a lower priority push out a higher one (ACK/RECEIPT rank as Critical).
  Simulator no longer prints the SLF4J warning. 72 tests pass; all presets re-run with
  the same numbers as the table below.
- **Finding (no change made):** `RATE_LIMITED` ×8 in `sparse` is **not** a simulator
  artefact. Capping every simulated origin at 5 SOS/hour left it at 8. Cause: relays count
  the limit by their own clock (deliberate, so senders can't backdate), so a relay that
  meets a carrier late can receive 6 of one person's SOS in one burst even if they were
  sent hours apart; the 6th waits until the relay's hour passes. Delivery was still 100%.
  The cap was reverted (it fixed nothing).
- **Stopped at:** M1 done and accepted; follow-up commits local until pushed.
- **Next:** M2 (Android app, two phones in airplane mode); needs Android Studio + SDK.

## 2026-11-23 (evening) — M1 built

- **Done:** M1 tasks 1–10. Gradle 9.9.2 / Kotlin 2.4.21 / JDK 21 build with `mesh-core`,
  `crypto-jvm` (lazysodium, MPL-2.0) and `simulator`. Envelope codec v1 (max SOS 269 B),
  Ed25519 signing (hops excluded) + `crypto_box_seal`, 11 ordered receive rules, store
  policy with tombstones, rate limit by receiver clock, sync session, uplink glue, golden
  vectors for M3, 100-phone simulator. 71 tests pass (`gradlew.bat test`). Fresh-eyes
  review (W5) done; findings fixed or rejected with reasons (see commit `fix(m1)`).
- **Results** (5 seeds each, 6 h simulated, 50 SOS per seed; latencies in minutes):

  | Preset | Delivery | Median / p90 / max latency | ACK returned | Protocol dup | Uplink-overlap dup | Wall time |
  |---|---|---|---|---|---|---|
  | baseline (100 nodes) | **100%** | 13.1 / 21.7 / 38.3 | 100% | **0** | 23 / 69,696 transfers | 20–43 s |
  | sparse (30) | 100% | 21.4 / 50.0 / 80.0 | 100% | 0 | 0 | 9–15 s |
  | dense (300) | 100% | 6.7 / 12.2 / 23.3 | 100% | 0 | 110 / 218,643 | 116–215 s |
  | lowbattery (30% critical-only) | 98.0% (Critical 100%) | 15.2 / 41.8 / 273.4 | 100% | 0 | 18 / 62,348 | 18–35 s |

  Other rejects seen: `RATE_LIMITED` ×8 in sparse (one of 30 phones originating > 5 SOS
  in an hour, a sim artefact), `HOP_LIMIT` ×4 in dense. Wall time varies with machine load.
- **Finding for the owner (decision needed):** the first baseline run showed 23 duplicate
  transfers. Cause: a phone in an internet zone uploads *during* a mesh session and gets a
  RECEIPT/ACK from the server after its SUMMARY was already sent, so the peer sends that
  same envelope again. The simulator now counts those separately (`uplinkOverlapDuplicates`)
  and protocol duplicates stay at 0. Options: (a) accept the split as is (wasted airtime
  only, about 0.03%); (b) make the app hold uplink replies until the session ends;
  (c) send a small "I now have" update mid-session. Recommendation: (a).
- **Tried and reverted:** a spatial grid for pairing to speed up `dense`. Results were
  identical but it was *slower* (159 s against 116 s), so it was removed.
- **Stopped at:** M1 complete and committed locally (not pushed); waiting for the owner's
  walkthrough sign-off and the duplicate-split decision.
- **Next:** owner decides the duplicate split; then M2 (Android app, two phones in
  airplane mode), which needs Android Studio + SDK.

## 2026-11-23 (later)

- **Done:** M1 build spec written (`docs/milestones/M1-mesh-core.md`) and reviewed; protocol
  refinements recorded in ARCHITECTURE.md (UPDATE parent id in cleartext, RECEIPT clears
  relays, receiver-clock rate limit, multi-frame SUMMARY, 10 s stall / 120 s cap).
- **Stopped at:** spec ready, no code.
- **Next:** owner reads the spec and gives a go; then M1 task 1 (Gradle scaffold).

## 2026-11-23

- **Done:** planning docs written and rescoped as a personal project; grilling session
  settled the goal, licence, access, encryption, retention, relay triggers, field test,
  pass gate and who builds (see PLAN.md §0 "Ground rules").
- **Stopped at:** docs complete, no code yet.
- **Next:** M1, the `mesh-core` envelope codec and simulator. Before starting, the agent
  explains the M1 plan and waits for a go (W8).
