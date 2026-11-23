# Taptap

> One firefly's light is small, but a swarm lights up the whole field.

**Taptap** (from the Filipino *alitaptap*, firefly) is a free, offline-first Android SOS app.
When cell towers fail, phones running Taptap relay short SOS messages to each other over
Bluetooth and Wi-Fi until any one of them reaches the internet and delivers them to a
responder dashboard. The responder's signed acknowledgement ("Help is coming.") travels back the same way.

**Status:** personal project, pre-MVP. No partnerships, no LGU integration, no funding ask.
The goal is a working app that does the job end to end.

## Docs

| File | What it covers |
|---|---|
| [PLAN.md](PLAN.md) | Problem, scope, milestones, risks, success criteria |
| [DESIGN.md](DESIGN.md) | Product and UX design: brand, screens, copy, accessibility |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Technical design: message format, mesh protocol, security, backend |
| [AGENTS.md](AGENTS.md) | Rules every AI agent working on this repo must follow |

## Planned stack

- **Android app:** Kotlin, Jetpack Compose, Google Nearby Connections, Room
- **Mesh engine:** pure Kotlin/JVM module, simulated on a laptop
- **Backend:** Supabase (Postgres + PostGIS, Auth, Realtime, Edge Functions)
- **Dashboard:** React + Vite + MapLibre GL on OpenStreetMap tiles
