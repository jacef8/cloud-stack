# Record Room — design system

**Style: Showpiece** (design-directive §3.3) — a dark, layered instrument panel with
sharp text. Picked because this is a league app meant to impress a group of friends,
not a work tool and not a page for customers.

Built with the UI/UX Pro Max reference library as a second opinion. Where the two
disagreed, **design-directive won** — the notes below say where that happened.

## 1. Who, where, on what, what for

| | |
|---|---|
| **Who** | Jace's friend's 13-manager Sleeper league |
| **Where** | couch, phone in hand, mostly during and after games |
| **On what** | phone first (installed as an app), desktop second |
| **One thing** | see who holds a record, and whether this week broke one |

## 2. Colour tokens

All colour is a token on `:root`; no component carries a raw hex.

| Token | Value | Use |
|---|---|---|
| `--e0` | `#0A0B0E` | page canvas |
| `--e1` / `--e1-hi` | `#14161B` / `#1A1D23` | cards — **solid**, never see-through |
| `--e2` / `--e2-hi` | `#232730` / `#2C313B` | raised controls |
| `--recess` | `#060709` | wells and tracks |
| `--ink` / `--ink-2` / `--ink-3` | `#FFF` / white .86 / white .70 | body / secondary / labels |
| `--accent` | `#F0B63C` | trophy gold — the one accent |
| `--accent-hi` / `--accent-2` / `--accent-lo` | `#FFD27A` / `#C98A2E` / `#8A5E18` | highlight, bronze secondary, dim rail |
| `--on-accent` | `#1A1201` | text on gold |
| `--ok` / `--bad` | `#35C98A` / `#E5564C` | **status only** — never decoration |
| `--nil` / `--nil-2` | `#4A4F58` / `#2E333B` | "no avatar" placeholder |

Gold replaced the old green accent because directive §2.6 reserves green and red for
status. It also suits the subject: this is a book of trophies.

**Measured contrast** (exact, over every surface): body ink never below **10.1:1**
(needs 7), labels never below **7.3:1** (needs 4.5), gold text never below **7.1:1**,
`--on-accent` on gold **10.1:1**. Bronze, green and red are fills and dots only —
none of them is ever a text colour.

## 3. Type

Archivo (display) · Inter Tight (text) · JetBrains Mono (readouts and labels).
All three are on the directive's approved list; the pair was changed from
Space Grotesk / Instrument Sans so projects don't blur together (§2.7).

Body 15px desktop, **16px phone**. Nothing below 12px, and 12px only for short
uppercase labels. Figures use `tabular-nums`.

## 4. Surfaces and depth

Lighter means closer: canvas → card → raised control. Depth comes from a 1px bevel
highlight, a short shadow (blur ≤14px) and recessed tracks — never from a glow.

- **No `backdrop-filter` under body text.** The only one left is `dialog::backdrop`,
  which is a floating overlay (allowed by §3.3).
- **No grain, no noise grid, no blurred blobs.** One faint background gradient, which
  is also the vignette.
- Accent strokes and status dots may glow tightly (blur ≤6px). Cards and buttons get
  a neutral shadow, never a coloured halo.
- Card radius 22px, controls 14px — one value each, project-wide.

## 5. Motion

- Rows stagger in 28ms apart; bars grow from zero; figures count up.
- Lenis smooths the wheel on the window, the dialog and every scrolling panel
  (fine pointers only — phones keep native momentum).
- One slow ambient element: the Three.js particle field, tinted to the accent tokens.
- Everything above is off under `prefers-reduced-motion`.

## 6. Where this departs from the reference library

| UI/UX Pro Max said | What shipped | Why |
|---|---|---|
| Glassmorphism, frosted translucent cards | solid cards | directive §2.1 — see-through panels make text muddy |
| Landing-page pattern (hero → testimonials → CTA) | the app's five sections | the database only holds landing patterns; this is an app |
| Fira Code / Fira Sans | Archivo / Inter Tight / JetBrains Mono | directive §2.7 approved list |
| `#22C55E` status green as the accent | trophy gold | directive §2.6 — green is for status |

## 7. Still open

The directive's **§2.10** says no heavy animation unless it does a real job. The
Three.js field is decoration, and the module is 671KB. It stays because Jace asked
for it twice; it is lazy-loaded, excluded from the offline precache, and disposes
itself if it measures under 28fps.
