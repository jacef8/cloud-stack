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

## 4. Layout

The stat filter sits **beside** the board from 700px up — a 262px rail on the
left. Directive §2.3 wants controls next to what they change, and §4.1 lists
"scroll past filters to find results" as rejected, so it is never stacked above.

On the phone it moves into a right-hand **drawer** behind a "Stat filter" button
that sits at the top of the board, showing how many stats are on ("9 of 9").
The drawer closes on the ×, the scrim, Escape, a "Show N records" button, and
whenever you leave the page or the window grows wide enough for the rail.

## 5. Surfaces and depth

Lighter means closer: canvas → card → raised control. Depth comes from a 1px bevel
highlight, a short shadow (blur ≤14px) and recessed tracks — never from a glow.

- **No `backdrop-filter` under body text.** The only one left is `dialog::backdrop`,
  which is a floating overlay (allowed by §3.3).
- **No grain, no noise grid, no blurred blobs.** One faint background gradient, which
  is also the vignette.
- Accent strokes and status dots may glow tightly (blur ≤6px). Cards and buttons get
  a neutral shadow, never a coloured halo.
- Card radius 22px, controls 14px — one value each, project-wide.

## 6. Motion and extra tools (directive §6)

**GSAP 3.15.0** (pinned `@3`) — rows stagger in 28ms apart, bars grow from zero,
figures count up. One motion system instead of hand-rolled keyframes, and it
cleans up its own inline styles, so tapping through sections quickly can't leave
a card stranded mid-animation. 73KB, precached.

**View Transitions** — the browser's own crossfade between the five sections.
Nothing to load. `startViewTransition` updates the DOM asynchronously, so the
reveal waits on `updateCallbackDone` before animating the new view.

**three.js 0.186** — the ambient particle field, tinted to the accent tokens.
Lazy-imported, left out of the offline precache, and it disposes itself if it
measures under 28fps.

**Lenis 1.x** — smooth wheel scrolling on the window, the record dialog and
every scrolling panel. Directive §6.3 says Lenis never runs inside the
fit-one-screen shell; **Jace asked for it anyway and that stands.** It is gated
to fine pointers, so phones keep native momentum, and it is skipped entirely
under reduced motion.

Not used: **Flip**, which was tried and removed — toggled-off stats and
trophies dim in place rather than leaving the grid, so there was no layout
change for it to carry.

All of the above is skipped under `prefers-reduced-motion`, and the page renders
complete with either extra script blocked.

## 7. Where this departs from the reference library

| UI/UX Pro Max said | What shipped | Why |
|---|---|---|
| Glassmorphism, frosted translucent cards | solid cards | directive §2.1 — see-through panels make text muddy |
| Landing-page pattern (hero → testimonials → CTA) | the app's five sections | the database only holds landing patterns; this is an app |
| Fira Code / Fira Sans | Archivo / Inter Tight / JetBrains Mono | directive §2.7 approved list |
| `#22C55E` status green as the accent | trophy gold | directive §2.6 — green is for status |

Departures from the directive itself, both at Jace's explicit request: **Lenis**
inside the fit-one-screen shell (§6.3), and **three.js** as atmosphere rather
than where 3D is the point (§6.3).

## 8. Still open

Directive **§6.2** now lists three.js as approved for Showpiece, which settles the
old §2.10 question. **§6.3** still says three.js should load "only where 3D is the
point" — here it is atmosphere, not the point. It stays because Jace asked for it
twice and it costs nothing until it loads.
