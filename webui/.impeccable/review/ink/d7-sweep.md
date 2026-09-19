# M1-52 — the D7 sweep, enumerated from the source

D7 is: **a rule paints one plane's ink on another plane's ground.** It was found once, fixed in
one file, and never swept for. This is the sweep.

## The denominator, and why it is the source and not the captures

**187 `color: var(--token)` rules across 38 CSS files under `webui/src`.**

`probe-ink.mjs` measures the pairs it finds *rendered*, so a rule on a page no fixture opens is
invisible to it — and both previously known D7 instances were found by a human noticing. The
denominator here is every colour declaration in the tree. Each one gets a disposition; **a rule
in none of them fails by name.** `d7-dispositions.json` carries all 187.

| disposition | n |
|---|---|
| CORRECT — measured ≥ 4.5:1 on every plane it renders on | 90 |
| UNEXERCISED — no fixture renders it; named, not silently absent | 73 |
| EXEMPT — a state or data colour, not body text | 17 |
| CORRECT on every real plane, EXEMPT on the ghost wash | 4 |
| ROUTED to M1-53 with the row named | 3 |
| **FAIL** | **0** |

## What the sweep found: nine instances, not two

Two were known. **Seven were not**, and two of those only became visible after the sweep itself
was corrected twice (below).

| # | where | the pairing | dark | status |
|---|---|---|---|---|
| 1 | `controls.css:86` `.myx-input-label` | `--ink-mute` on `--strip` | 1.98:1 | already fixed (the original D7) |
| 2 | `doctor.css:88` `.myx-doc-fix` | `--ink` on `--strip-field` | 1.33:1 | **fixed** |
| 3 | `compact-feed.css:38` `.myx-cfeed-error` | `--ink` on `--strip-field` | 1.33:1 | **fixed** — byte-identical to #2 |
| 4 | `head-edit.css:21` `.myx-head-key` | `--ink-strong` on `--strip-field` | 1.07:1 | **fixed by scoping** |
| 5 | `head-edit.css:29` `.myx-head-note` | `--ink-mute` on `--strip-field` | 2.11:1 | **fixed by scoping** |
| 6 | `log-tail.css:49` `.myx-lt-field-label` | `--ink-mute` on `--strip-field` | 2.11:1 | **fixed** |
| 7 | `log-tail.css:71` `.myx-lt-toggle` | `--ink` on `--strip` | 1.24:1 | **fixed** |
| 8 | `accounts.css:69` `.myx-accounts-order` | `--ink-mute` on `--plate` | 1.40:1 | **borrowing fixed**, residual routed |
| 9 | `ui.css:873/881` `.myx-fig-value` / `.myx-fig-unit` | `--ink-strong` / `--ink-mute` on `--strip` | 1.15:1 / **1.98:1** | ROUTED to M1-53 |

### Instance 9 is the one worth reading twice

`.myx-fig-unit` prints `--ink-mute` on `--strip` at **1.98:1** — the *exact* number D7's own
comment cites in `controls.css:91-94`: *"`--ink-mute` is the room's ink — measured 1.98:1 over
`--strip` against a 4.5:1 bar."* That comment ends: **"Nothing had put an Input on a strip yet;
the page sweep will."**

It predicted this instance. `Figure` is the shared component every value in the console prints
through; it renders on `--room` 35 and 21 times (passing, 15.73:1 and 6.93:1) and on `--strip`
once — the logs page's `.myx-lt-head`, samples `15` and `new lines`. **That is the same node
M1-40 flagged as "15new lines".** Its spacing was wrong and its ink is unreadable in dark, and
neither instrument saw the other's defect.

It is in `ui.css`, which is M1-53's. **I did not patch it in `log-tail.css`**, though the ground
is mine and I could have: a per-site override of a shared component's defect is exactly how D7
reached nine instances. It is routed with the row named.

### The second shape of the class: one rule serving two planes

Instances 4 and 5 are not "an ink borrowed onto the wrong plane". `.myx-head-key` and
`.myx-head-note` are printed **both** inside `.myx-bay` and inside `.myx-head-new`, which paints
`--strip-field`. Measured, all four combinations:

| | on the bay | on `.myx-head-new`'s paper |
|---|---|---|
| room inks | 15.85 / 6.99 dark, 8.91 / 4.68 light | **1.07 / 2.11 dark** |
| paper inks | **1.06 / 2.69 dark** | 13.93 / 5.49 dark, 16.97 / 6.68 light |

**No single token can serve both.** My first cut of this row swapped the tokens on the class and
made `.myx-head-key` 1.07 → 1.06 — a fix that moved the failure rather than removing it. The ink
is now scoped to the plane (`.myx-head-new .myx-head-key`, etc.), which is the only shape that
passes on both.

## The ghost

Ruled decorative (M1-52, read off `team-board-a.png`): it is the motion trail of the strip being
handed off, `index.tsx` marks both layers `aria-hidden="true"`, and the live strip above it
carries the same text at full contrast. AA does not apply — **provided it is never the only place
its content appears**, and that proviso is walled rather than asserted.

`probe-ink.mjs` now evaluates the wall on every page: *every text node inside `.myx-board-ghost`
must have a node outside it carrying the same text at ≥ 4.5:1.* **The wall never consults the
ghost's own contrast** — a wall phrased as "the ghost must be faint" is satisfied by fading it
until nothing is legible, and unreadable-by-design and unreadable-by-accident are identical in a
contrast table and opposite on screen.

Current state: **22 ghost text nodes checked, 0 violations.** `--selftest` runs five synthetic
cases and asserts the wall fires on the violating ones.

## The sweep was wrong three times, and each was the campaign's own failure mode

Recorded because the instrument is the deliverable as much as the fixes are.

1. **A parser that read `color-mix()` as transparent** (M1-47) reported 169 rail labels as black
   on black. Caught against the M1-36 crops.
2. **`export const AA` in the temporal dead zone.** `ghostViolations` is hoisted and read `AA`
   before its initialiser ran, so it threw on both `teams` pages — **and `teams` is the only page
   with a ghost.** The per-page `catch` swallowed it and the run printed *"ghost wall: 0 text
   nodes … 0 violations"* and exited 0. **A wall that checks nothing reports the same green as a
   wall that passes.** The probe now refuses a zero-node ghost run by name, and refuses any run in
   which a page threw.
3. **A regex eaten by the template literal.** `split(/\s+/)` inside the probe's backtick string
   arrives in the page as `/s+/`, which split `myx-console` into `myx-con` and `ole` and left
   `myx-rule-cell` whole. That silently mis-attributed ancestry.

Fault 3 mattered beyond itself: resolving **inherited** colours is what took UNRESOLVED from 106
to 73 and RENDERED from 34 to 67, and it is what surfaced instance 7. A rule like
`.myx-lt-toggle` sets a colour its children inherit, so it parents no text node of its own and a
sweep joining on the node's own class calls it "never rendered" while it renders on every visit.

## What is not covered

- **73 unexercised rules.** Modals, alerts, budgets, the palette, account-login, detail panels.
  No fixture opens them, so no ground was observed and none is claimed. They are listed by name
  with their ink and any file-local declared ground in `d7-dispositions.json`. Reaching them
  needs fixtures, which is a row, not a paragraph.
- **States.** Six pseudo-state, four attribute-state and two pseudo-element rules are in that 73:
  `:hover`, `:focus-visible`, `[disabled]`, `.myx-key-armed`, `::placeholder`. Forcing them needs
  `CSS.forcePseudoState`.
- **`ui.css` and `board.css`** are M1-53's; their instances are routed, not fixed.
- **`--hair` used as a border COLOUR** at `head-edit.css:9` (`border-bottom: 1px solid var(--hair)`)
  — `--hair` is the 1px width token, so the declaration is invalid and the border falls back to
  `currentColor`. Not a D7 instance and not this row's class; reported, untouched.

## Regenerating

    node webui/.impeccable/review/ink/probe-ink.mjs            # 26 pages, ink+ground+ancestry, ghost wall
    node webui/.impeccable/review/ink/probe-ink.mjs --selftest # the wall's five cases
    bun webui/.impeccable/review/ink/sweep-d7.mjs out.json  # the 187-rule denominator
