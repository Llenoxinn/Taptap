# Taptap — Product & UX Design

Technical internals live in [ARCHITECTURE.md](ARCHITECTURE.md). This file covers what users
see, touch and read.

## 1. Design principles

1. **Built for panic.** One hand, wet screen, shaking, in the dark. Large targets, no typing,
   no hidden gestures.
2. **Never lie about status.** Show exactly what has happened to the SOS (saved, passed to
   N phones, delivered, acknowledged). Never imply help is coming before an ACK exists.
3. **Works before it's needed.** All permissions, language and setup happen on a calm day.
4. **Battery is life.** Dark UI by default, no animations in relay mode, nothing polls.
5. **Local first.** Cebuano, Filipino and English are equal; every string is translated.

## 2. Brand

- **Mascot:** Taptap the firefly. Used in onboarding, empty states and the app icon —
  never on the SOS screen, which stays purely functional.
- **Voice:** calm, short, direct. Verbs first. "Tap to send SOS", not "Would you like to…".

### Color tokens

| Token | Hex | Use |
|---|---|---|
| `bg` | `#0E1116` | App background (dark default; saves OLED battery) |
| `surface` | `#1A1F27` | Cards, sheets |
| `text` | `#F2F4F7` | Primary text |
| `text-muted` | `#A0A8B5` | Secondary text |
| `glow` | `#E8D44D` | Brand accent, relay-active indicator |
| `critical` | `#E5484D` | Critical priority, SOS button |
| `urgent` | `#F5A524` | Urgent priority |
| `info` | `#3E9BFF` | Info priority |
| `ok` | `#30A46C` | Delivered / acknowledged |

Priority is **never conveyed by color alone**: each level has an icon and a text label.
All text/background pairs must meet WCAG AA (4.5:1 body, 3:1 large text). A light theme
follows the same tokens and is selectable in Settings.

### Type

System font (Roboto on Android) — no bundled fonts, keeps the APK small. Minimum body size
18sp; SOS button label 32sp; respect the system font-scale up to 200%.

## 3. Citizen app — screens

### 3.1 Onboarding (first run, calm day)

1. Welcome — Taptap mascot, one sentence: "Taptap passes your SOS phone to phone when there is no signal."
2. Language — Cebuano / Filipino / English (big buttons).
3. Permissions — one screen per permission, each with a one-line why:
   Location ("so rescuers can find you"), Nearby devices ("to pass messages to other phones"),
   Notifications ("to tell you when help is coming").
4. Battery exemption — brand-detected guide with screenshots (see R2 in PLAN.md).
5. Optional: name + number of people in household (pre-fills headcount).

### 3.2 Home

```
┌──────────────────────────┐
│  Taptap      ◉ Relay ON  │  ← glow dot when Disaster Mode is on
│                          │
│                          │
│        ┌────────┐        │
│        │  SOS   │        │  ← ≥ 160dp circle, critical red
│        └────────┘        │
│                          │
│  [ I'm safe ]            │  ← Info shortcut
│                          │
│  Disaster Mode   [ ON ]  │
│  Carrying 12 messages    │
└──────────────────────────┘
```

### 3.3 SOS flow (≤ 3 taps after SOS, no keyboard)

1. **Priority** — three full-width buttons, icon + label:
   - Critical — trapped or injured
   - Urgent — need rescue, evacuation, or missing person
   - Info — hazard, supplies, I'm safe
2. **People** — stepper `– 4 +` (pre-filled), and "Injured: 0 / 1 / 2 / 3+".
3. **Send** — big confirm button. Optional note via preset chips
   ("Flooding", "Elderly", "Child", "Pregnant", "Need medicine"); free text is optional and
   collapsed by default.

Sending works with zero GPS fix: the SOS goes out with "location pending" and updates itself.

### 3.4 Status (after sending)

A vertical progress list. Each step shows a time:

| State | Copy (EN) | Meaning |
|---|---|---|
| Saved | Saved on your phone | In local store, waiting for neighbours |
| Passed on | Passed to 3 phones | Relay count known locally |
| Delivered | Reached responders | Server receipt came back via mesh |
| Acknowledged | **Help is coming.** | Responder ACK received |

"Delivered" and "Acknowledged" appear only when the signed message actually arrives.
Below the list: "Keep Taptap open and your phone charged. Move to higher ground if safe."

### 3.5 Relay (Disaster Mode)

Persistent notification: "Taptap is relaying · 12 messages · tap to stop." Below 20%
battery: "Low battery: passing Critical messages only."

### 3.6 Settings

Language, theme, household size, battery guide, "Send test SOS" (sets the `TEST` flag —
see ARCHITECTURE.md §2 — so it is hidden from the responder queue by default), about/privacy.

## 4. Key strings (seed for translation)

| Key | English | Filipino | Cebuano |
|---|---|---|---|
| `sos_button` | SOS | SOS | SOS |
| `priority_critical` | Critical — trapped or injured | Kritikal — naipit o sugatan | Kritikal — naipit o samdan |
| `status_saved` | Saved on your phone | Naka-save sa phone mo | Na-save sa imong phone |
| `status_ack` | Help is coming. | Naabot na. Parating na ang tulong. | Naabot na. Padulong na ang tabang. |
| `relay_on` | Taptap is relaying | Nagpapasa ang Taptap | Nagpasa ang Taptap |

> Translations above are a first draft — have native speakers review before M6.

## 5. Responder dashboard

Single page, desktop-first, usable on a tablet.

- **Map (70%)** — MapLibre on OSM tiles. Pins colored *and shaped* by priority
  (critical = filled circle, urgent = triangle, info = square). Clusters at low zoom.
- **Queue (30%)** — sorted Critical → Urgent → Info, then oldest first. Each row: priority,
  headcount/injured, age ("12 min ago"), relay hops, location accuracy.
- **Actions per report:** Acknowledge · Mark resolved · Mark false. One click, undo for 5 s.
- **New critical report:** sound + row highlight. No modal pop-ups.
- Keyboard: `j/k` move, `a` acknowledge.

## 6. Accessibility checklist

- [ ] Touch targets ≥ 48dp (SOS ≥ 160dp)
- [ ] Works with TalkBack; SOS reachable as first focus
- [ ] Color never the only signal
- [ ] Font scale 200% without clipping
- [ ] Haptic confirmation on send
- [ ] Respects "reduce motion"
