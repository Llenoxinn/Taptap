# M2 — Android app ("Two phones, airplane mode")

**Status:** built 2026-11-25; automated checks pass (78 tests); pending two-phone hardware check.
**Reads first:** [AGENTS.md](../../AGENTS.md), [ARCHITECTURE.md](../../ARCHITECTURE.md) §1–§6, [DESIGN.md](../../DESIGN.md) §1–§4, §6, [PLAN.md](../../PLAN.md) §4–§5.

---

## 1. Goal in one paragraph

Wrap the pure-Kotlin `mesh-core` engine inside an Android 8+ app (`:android`) so two real Android phones with Wi-Fi and mobile data turned off (airplane mode with Bluetooth enabled) discover each other over Google Nearby Connections (`P2P_CLUSTER`), exchange signed + sealed SOS envelopes, store them durably in Room, and let the owner verify on Phone B that Phone A's SOS arrived in Phone B's carried store.

## 2. Done when (acceptance checklist)

- [x] `:android:assembleDebug` produces `android/build/outputs/apk/debug/android-debug.apk` on Windows and `:mesh-core` still has zero `android.*` / `androidx.*` imports (P1).
- [x] `:android:testDebugUnitTest` and `gradlew.bat test` pass deterministically with zero real sleeps.
- [x] `SodiumAndroidCrypto` implements `Crypto` via `lazysodium-android` (MPL-2.0) + `jna@aar` (Apache-2.0); device Ed25519 seed is wrapped with an Android Keystore AES-256-GCM key in `noBackupFilesDir`.
- [x] `RoomMessageStore` implements `MessageStore` (`envelopes` and `tombstones` tables) plus app-level `outbox` and `outbox_peers` tables.
- [x] `NearbySessionDriver` drives `SyncSession` over `P2P_CLUSTER`, runs one session at a time, enforces the 2-minute peer cooldown, waits for outbound `ENVELOPE` / `DONE` payload callbacks before disconnecting, and respects the 20s/40s (10s/110s below 20% battery) duty cycle.
- [x] `RelayService` runs as a `connectedDevice` foreground service with ongoing carried-count notification and low-battery switch to Critical+ACK only.
- [x] Citizen UI covers Onboarding (Language, Welcome, mandatory Test warning, Permissions, Battery exemption, Household size, Privacy), Home (SOS button, I'm safe, Disaster Mode switch, Carried-store inspector sheet, permanent Test banner), ≤ 3-tap SOS flow, Status screen (never showing Delivered/Acknowledged without a verified server RECEIPT/ACK, P4), and Settings (including Test SOS).
- [x] All user-facing strings exist in English, Filipino (`values-b+fil` + `values-tl`), and Cebuano (`values-b+ceb`).
- [ ] Hardware check (owner): SOS sent on Phone A appears in Phone B's carried-store list with Wi-Fi and mobile data off.

## 3. Scope boundaries (YAGNI)

- **In M2:** `:android` app, Room store, Nearby Connections transport + foreground service, `FusedLocationProvider` + `>50%` accuracy `UPDATE`, Compose UI + 3-language strings, deterministic unit/Robolectric tests.
- **Out (deferred to M3/M4):** HTTPS `/intake` uplink, Supabase Edge Function, responder dashboard (M3); 15-min `WorkManager` signal-loss auto-start and per-brand OEM screenshot battery guides (M4).
