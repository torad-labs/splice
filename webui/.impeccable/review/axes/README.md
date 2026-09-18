# The fixture axes: what boot conditions change what renders, and which have ever been captured

M1-100. The finding this row exists for is not a rule, it is a class of mistake: **every capture
this campaign has taken runs the console in ONE configuration** — keyed, fixtured, dark or light,
at one of two frames. Those are the axes anyone thought to vary. `features/unlock-mgmt` renders
its modal only when the console has **no** management key, and every instrument seeds one before
boot, so the unlock modal has not appeared in any capture this campaign has ever produced. Four
rules — `.myx-modal-title`, `.myx-modal .myx-field-label`, `.myx-field-label`,
`.myx-fault-message` — could not have rendered anywhere. Not dead rules, not a missing fixture:
**a fixture that was always seeded past.**

## The axis list

The axes are invented by `.dev/web-console/lib/cdp.mjs`, which seeds `localStorage` before the app
boots, so the complete set of things a harness can vary is the set of values it seeds plus what it
does after boot. Enumerated from the source, not from the instruments' option lists (law 24).

| axis | values | ever captured? | where the branch lives |
|---|---|---|---|
| **management key** | present / **absent** | present only — **absent NEVER** | `features/unlock-mgmt/index.tsx` renders the modal; `App.tsx:62` mounts it unconditionally and the component decides |
| **data source** | fixture / live daemon / **neither** | fixture (11 of 13) and live; **neither only by accident** (three captures in M1-25 were error pages, which is how doctor and mcp had no valid light capture) | every page's `wantsFixture(search)` |
| **theme** | dark / light | both | `splice.theme` seed |
| **frame** | 1536×1024 / 3840×2160 | both | `Emulation.setDeviceMetricsOverride` |
| **address** | 13 | all 13 | `rows.ts` |

**The conditions the app branches on but no axis drives:**

| boot condition | source evidence | ever captured? |
|---|---|---|
| no management key | `features/unlock-mgmt/index.tsx` — the modal is the whole component | **NEVER** |
| failed fetch from the daemon | `<Fault` in **15 files** | **NEVER deliberately** — a fixture suppresses the fetch and a live daemon answers it |
| loading / not yet arrived | `<Blank` in **8 files** | only transiently, never as a captured state |
| declared-empty | `<Empty` in **25 files**, **30 distinct phrases** | **NEVER** — every fixture carries data, so every empty branch is skipped |
| route pending (a V4 row not built) | **10 pages** carry `disposition: 'pending'` | **NEVER** — the panels render their pending `Empty` only when the route 404s, which a fixture prevents |
| first run, no config | `FieldBox` provenance `'default'` | **NEVER** |

## What this means in one sentence

**Six boot conditions the app is built around have never been rendered in a single capture this
campaign has taken**, and the reason is structural rather than negligent: the harness must seed a
key and data for *anything* to render, and seeding them is exactly what skips all six. The unlock
modal is not the exception; it is the one that was noticed.

## The unkeyed pass, and the correction it forces on this document

**The four rules HAVE been exercised, and not by a capture.** `.dev/web-console/review/ink/exercise.mjs`
carries an unkeyed mode (`{ name: 'unkeyed', storage: () => ({}) }`, line 356), and its artifact
`review/ink/exercised.json` records all four reached — `.myx-modal-title`,
`.myx-modal .myx-field-label`, `.myx-field-label` and `.myx-fault-message` each read
`reached=14`, with `unkeyed/dark/fleet/account` and `unkeyed/dark/fleet/base` among them. So the
axis is TURNED, by one instrument, and never by another.

**That makes the finding sharper rather than weaker.** "Never captured" is true and "never
exercised" is false, and the gap between those two sentences is the actual defect: the console's
boot-state axis is driven by the INK instrument and by NONE of the capture instruments, so a
surface can be measured for ink reachability and remain invisible to every picture the campaign
has ever looked at. Four rules passed their ink bar in that state and no human has seen them.
**A capture set is only as complete as the number of boot states it turns, and the ink pass is
not a substitute for turning them — it proves reachability, not appearance.**

**The other five conditions are still uncaptured AND unexercised**: no instrument turns them at
all, ink or capture. That is the part of the row that outlives it, and until this row nobody had
written down how many there are.

## What I did not verify

- **Whether a light-theme unkeyed pass differs.** Law 32: light findings before 12:40 on
  2026-09-18 are suspect, and the modal is not known to be theme-independent.
- **The exact rule count per condition.** The four are named because M1-80 measured them; the
  other five conditions have no measured rule count, only the file counts above. That is the
  denominator gap this row leaves open, and it is a measurement, not an inference.
- **Whether any of the six is reachable at all in production.** A console that always has a key
  never shows the modal; that is a design question about the world, not about the harness.
