# Taptap — Project Plan

_Last revised: 2026-11-23. Supersedes the October 2026 proposal draft._

**Mascot:** Taptap the firefly, from the Filipino *alitaptap*. One firefly's light is small,
but a swarm lights up the whole field. Each phone running Taptap is one light, and together
they carry an SOS to rescuers.

## 0. What changed from the October draft

The October draft was written as a funding proposal for an LGU pilot. This plan is a
**personal build project**: the target is a working app, not a contract.

| Removed | Why |
|---|---|
| LGU subscriptions, site licences, ₱ pricing | No customer yet; nothing to price |
| ₱204,050 ask and 12-month grant budget | Self-funded on free tiers |
| Barangay pilot, LGU drill, 2028 budget commitment | Needs partners; out of scope |
| SMS fallback gateway | Needs a GSM modem/number and a partner; defer (YAGNI) |
| "DRRMO" as the only responder | The dashboard serves whoever operates it — for now, the developer and test volunteers |

The problem statement and technical idea are unchanged. The LGU path stays open as a
**later option** (§8), not a dependency.

### Ground rules (decided 2026-11-23)

| Rule | Decision |
|---|---|
| Goal | **Proof it works.** End-to-end delivery shown with real phones and measured. Nobody relies on it in a real disaster |
| Distribution | Public APK on GitHub Releases with a strong "Not monitored" disclaimer. No Play Store |
| Licence | Open source, **Apache-2.0** |
| Budget | **₱0.** Free tools and free tiers only; anything that costs money needs the owner's OK |
| Schedule | **No deadline.** Work happens whenever the owner is free; `docs/devlog.md` records where work stopped |
| Who builds | **Agents write the code**; the owner reviews, steers and tests on real phones |

---

## 1. Summary

In December 2021, after Typhoon Odette cut every signal on Siargao, volunteers printed the
list of missing people each morning and flew it to the island by hand (Rappler, 2021).
Almost every Filipino now carries a phone, yet in the first hours after a disaster those
phones go silent. Taptap turns silent phones into a relay network.

A victim sends an SOS in three taps, with no typing: priority, headcount, send. Location is
attached automatically. Nearby phones pass it
along over Bluetooth and Wi-Fi — no signal or load needed — and carry it as their owners
move. The first phone that reaches internet uploads it to a responder dashboard. The
responder's acknowledgement then relays back to the victim.

**What makes it different:** offline chat apps help people talk to each other; Taptap is
built to get people *found*. It is a one-way-and-back delivery pipe (SOS out, ACK back),
not a messenger.

## 2. The problem

### 2.1 Highest disaster risk in the world

The Philippines ranks first of 193 countries on the WorldRiskIndex 2025, with river and
coastal flooding as leading threats (Bündnis Entwicklung Hilft & IFHV, 2025). About 20
tropical cyclones enter the Philippine Area of Responsibility each year (PAGASA, n.d.).
In six weeks of 2025, Central Visayas absorbed two major disasters:

| Event | Deaths | People affected | Source |
|---|---|---|---|
| M6.9 Bogo, Cebu earthquake (30 Sep 2025) | 79 | 754,733 in 18 cities/municipalities | IFRC (2025) |
| Typhoon Tino / Kalmaegi (4 Nov 2025) | 232 (150 in Cebu), plus 112 missing | 1.1 million families, 8 regions | SunStar (2025) |

After the Bogo earthquake, 89 cities and municipalities lost power (ECHO, 2025). During
Typhoon Tino, floodwaters trapped residents on their roofs (Asia News Network, 2025).

### 2.2 Communication collapses first

- **Yolanda / Haiyan (2013).** More than 6,300 dead, nearly half in Tacloban (Tan, 2014).
  Power and communications breakdown slowed the whole response (AFP, 2013); information
  channels stayed impeded for weeks (Humanitarian Practice Network, 2015).
- **Odette / Rai (2021).** Signal interruptions across Cebu, Bohol, Leyte, Southern Leyte,
  Eastern Samar, Siargao and Dinagat (Philippine Daily Inquirer, 2021). Surigao del Norte's
  governor got a satellite phone only ~24 hours after landfall (MindaNews, 2021). Bohol's
  governor could give only partial casualty figures (BenarNews, 2021). Some populations
  could stay without communication for weeks (Emergency Telecommunications Cluster, 2021).

### 2.3 Delay costs lives

Among trapped earthquake survivors in one Italian study, 93% of those who survived were
extracted within 24 hours (Noji, 1997). The chance of rescuing people alive falls sharply
over the first days (Macintyre et al., 2011). When reports cannot get out, rescue teams are
dispatched by guesswork.

### 2.4 Phones are everywhere but go silent

137 million active mobile connections (117% of population) and 98 million internet users
(DataReportal, 2026); about 88% of mobile devices run Android (StatCounter, 2026). These
phones can talk directly to each other, but no common tool turns that into a reporting
channel that ends with someone who can act.

**Root cause:** not a lack of phones, but a missing last-mile reporting channel that
survives network failure.

> **Citation TODO:** every source above was carried over from the October draft. Verify
> each one (URL, date, exact figure) before the text is reused publicly. Keep a full
> reference list in `docs/references.md` once verified.

## 3. Scope

### 3.1 MVP — in scope

1. **Citizen app (Android 8+):** one-tap SOS, priority, headcount, short note, GPS.
2. **Mesh relay:** store-carry-forward over Google Nearby Connections, priority-ordered,
   de-duplicated, hop- and TTL-limited.
3. **Uplink:** any phone with internet uploads everything it carries.
4. **Intake service:** Supabase Edge Function that verifies, decrypts and stores reports.
5. **Responder dashboard:** live map + list, one-click acknowledge.
6. **ACK relay back:** a server-signed ACK ("Help is coming.") flows back through the mesh
   and clears the SOS.
7. **Disaster Mode:** one switch that starts the relay foreground service. It also turns on
   automatically 15–30 minutes after signal is lost (Android's background job timing; see
   ARCHITECTURE.md §3.5), and stays on until the user turns it off.
8. **Test-version safeguards:** a "Not monitored. Call 911 if you can." warning during setup
   and on the SOS screen; a "TEST MODE" label on the dashboard.
9. **Privacy:** reports auto-delete (ARCHITECTURE.md §5); only anonymous `report_metrics`
   (no location, no sender key) are kept for the field-test numbers; a privacy notice in the app.
10. **Languages:** English, Filipino, Cebuano, all three from the first screen.

### 3.2 Out of scope for MVP (revisit only with evidence)

- SMS fallback gateway
- iOS app
- Chat, photos, voice, live location tracking
- Multiple responder organisations / multi-tenant dashboard
- Non-Google-Play phones (Huawei HMS) — see risk R3
- Usage tracking or third-party analytics, accounts for citizens, push notifications

## 4. Milestones

Each milestone ends with something runnable and a short demo note in `docs/devlog.md`.

| # | Milestone | Done when |
|---|---|---|
| M0 | **Repo + docs** | This plan, DESIGN.md, ARCHITECTURE.md, AGENTS.md committed |
| M1 | **Mesh engine on a laptop** — full spec: [docs/milestones/M1-mesh-core.md](docs/milestones/M1-mesh-core.md) | Pure-Kotlin engine passes unit tests; every envelope is signed and its body sealed to the server key; tampered or forged envelopes are rejected by the receive rules (ARCHITECTURE.md §3.2); simulator runs 100 virtual phones on a random-walk map and reports delivery rate + median latency |
| M2 | **Two phones, airplane mode** | SOS on phone A appears in phone B's store with Wi-Fi/mobile data off |
| M3 | **End-to-end** | SOS from offline phone → relay → online phone → dashboard pin → ACK shown on origin phone |
| M4 | **Hardening** | Foreground service survives 8 h on an entry-level phone; battery measured; OEM battery-saver guide written |
| M5a | **Scripted field test** | ~6 phones (2+ brands, 1+ budget phone), planned ~1 km route on a campus or barangay, mobile data + Wi-Fi off to fake a blackout, relay paths of 2–4 hops, one carrier walks into signal; 3 runs; meets every §5.2 check |
| M5b | **Natural field test** (only after M5a passes) | 15–20 phones carried through a normal day |
| M6 | **Public release** | Signed APK on GitHub Releases, Cebuano + Filipino reviewed by a native speaker, privacy notice, "Not monitored" disclaimer |

**Order matters:** M1 before any Android UI. The engine is the hard, testable part.

**Prerequisites per milestone** (all free): M1 needs only the JDK (installed: Java 24) and
Gradle (downloads itself). M2 needs Android Studio + SDK. M3 needs the Supabase CLI and Deno.

**Agent working rule:** before each milestone, explain the plan in plain language; after it,
give the owner a short walkthrough of what was built (AGENTS.md W8).

## 5. Success criteria (MVP)

### 5.1 Targets

| Metric | Target | Measured in |
|---|---|---|
| Delivery rate in simulator (100 nodes, realistic density) | ≥ 95% within TTL | M1 |
| Median SOS → dashboard latency in field test | < 30 min | M5a |
| Battery cost of relay mode | < 5% per hour on an entry-level phone | M4 |
| SOS payload size | < 300 bytes on the wire | M1 |
| Time from app open to SOS sent | < 10 s, no typing required | M5a |

### 5.2 M5a pass gate (all must pass across 3 runs, or fix and rerun M5a)

| Check | Pass if |
|---|---|
| Delivery | ≥ 90% of SOS messages reach the dashboard |
| Speed | Median send-to-dashboard time ≤ 30 min |
| Confirmation return | ≥ 80% of acknowledged SOS show "Help is coming." on the sender's phone |
| Battery | ≤ 5% per hour while relaying, on the budget phone |
| No false states | **0** cases of "Delivered" or "Help is coming." without a real signed confirmation |

## 6. Risks

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Low density — no other Taptap phones nearby | Nothing moves | Accept for MVP; this is a network-effect product. Field-test where density is controlled |
| R2 | OEM battery savers kill the service (Xiaomi, Oppo, Vivo, Realme, Infinix, Tecno) | Relay stops silently | Foreground service with `connectedDevice` type; in-app per-brand exemption guide; test on 2+ budget brands in M4 |
| R3 | Nearby Connections needs Google Play services | Huawei/HMS phones can't relay | Out of scope for MVP; a raw BLE transport behind the same interface is the later fix |
| R4 | Supabase free tier pauses idle projects | Intake down when disaster hits | Keep-alive ping every 2 days (scheduled job); verify the inactivity window; document upgrade path |
| R5 | False / spam SOS | Wastes responder time | Per-device signing key, rate limits, responder can mark "false" |
| R6 | GPS cold start slow without network (no A-GPS) | Missing or late location | Send immediately with last known fix + accuracy; send an update when a better fix arrives |
| R7 | Android permission friction (Bluetooth, Nearby Wi-Fi, location, notifications) | Users never finish setup | Onboarding asks once, explains why, done *before* a disaster |
| R8 | Solo developer, no deadline | Project stalls | Hard MVP scope (§3.2); YAGNI rule in AGENTS.md; small milestones; `docs/devlog.md` resume notes |
| R9 | A stranger relies on the public APK in a real disaster | Nobody answers their SOS | "Not monitored. Call 911 if you can." on setup and SOS screen; never an automatic "Help is coming." |
| R10 | Sleeping phones (Disaster Mode off) can't be woken by a neighbour's SOS | Gaps in no-warning events like earthquakes | Auto-on 15–30 min after signal loss; accepted gap until measured |
| R11 | Supabase built-in auth email is rate-limited | Magic-link logins/invites fail | Few responders, so likely fine; fallback: owner creates password accounts in the console; free SMTP only with owner OK |

## 7. Illustrative scenario

It is 2:00 a.m. and a typhoon is over a coastal barangay in northern Cebu. Floodwater has
reached the second floor of Lola Nena's house, and there is no signal. Her grandson opens
Taptap and taps SOS: Critical, 4 people, 1 injured.

The message jumps to a neighbour's phone two houses away, then to a tanod's phone at the
barangay hall. At 2:40 a.m., a volunteer driving toward the town centre catches one bar of
signal, and his phone uploads 17 SOS messages it picked up on the way. On the dashboard a
red pin appears on Lola Nena's street, and the responder sends the rescue boat there first.

Back in the flooded house, the grandson's phone lights up: **"Naabot na. Help is coming."**

## 8. Later options (not commitments)

- Offer the dashboard to an LGU DRRMO, school or hospital once M5 data exists.
- A monitored deployment (someone on duty during storms), which would allow removing the
  "Not monitored" disclaimer.
- Play Store listing (US$25 one-time; needs the owner's OK under the ₱0 rule).
- SMS gateway for weak-signal areas.
- BLE-only transport for non-GMS phones.
- iOS client.

## 9. Open questions

None open. Resolved 2026-11-23:

- Dashboard access: invite + request access, approved by the owner (ARCHITECTURE.md §5).
- Distribution: GitHub APK only, no Play Store.
- Info messages ("I'm safe", hazards) are in MVP (ARCHITECTURE.md §8).
