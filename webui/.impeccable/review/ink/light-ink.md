# M1-47 — every ink measured against the plane it actually sits on

Thirteen addresses x two themes at 1536x1024, the frame the comp is cut at. **4,610 measured
(ink, ground) pairs.** Nothing was fixed.

## Method, and why it is the method

The ground under each ink is **read from the live DOM**, not taken from the token the ink was
declared against. For every visible text node the probe composites the ancestor
`background-color` stack until it reaches an opaque plane, composites a translucent ink over
that result, and records the pair with the class of the element that supplied the ground. That
substitution — measuring an ink against the plane it was *declared* against instead of the one
it is *painted* on — is what M1-35 and the E-1 routing both got wrong, so this row refuses to
repeat it.

**The probe was wrong the first time and it is worth saying how.** Its colour parser matched
only `rgb()`. `.myx-rail-tab`'s plate is a `color-mix()`, which `getComputedStyle` returns as
`color(srgb …)`; the parser read that as transparent, walked past the plate, and reported the
rail's 169 tab labels as `--strip-ink` on `--room-deep` at **1.08:1** — black on black, on
every page, in dark. That would have been the headline finding of this row. It is false: the
plates are cream and I had 6x crops of them from M1-36 showing exactly that. The parser now
handles `color-mix()`, `color(srgb …)` and anything else the browser will normalise, **and it
counts what it cannot parse and refuses the page rather than treating it as transparent** — a
ground a check cannot read is not a ground it may skip. The corrected run reports 0 unparsed
across all 26 pages, and the rail measures 8.67:1 dark / 10.16:1 light.

Bar: WCAG AA, **4.5:1** for body text, 3:1 where every sample in the pair is large (≥24px, or
≥18.66px at weight ≥700). No pair here qualified as large; everything below is against 4.5.

## The contrast table

Every (ink role, plane) pair with n ≥ 3, both themes, same pages, same frame. **✗ marks below
the bar.**

| ink role | the plane it SITS ON | painted by | n | px | dark | light |
|---|---|---|---|---|---|---|
| `--strip-ink-mute` | `--strip-field` | `.myx-sfield` | 707 | 12–16 | 5.49 | 7.26 |
| `--strip-ink` | `--strip-field` | `.myx-sfield` | 622 | 12–16 | 13.93 | 18.42 |
| `--strip-ink` | `--strip` | `.myx-sfield` | 207 | 12–16 | 13.02 | 15.83 |
| `--strip-ink` | `rail plate (color-mix)` | `.myx-rail-tab` | 169 | 14 | 8.67 | 10.16 |
| `--ink-mute` | `--room` | `.myx-rule` | 133 | 12–20 | 6.93 | 7.90 |
| `--ink` | `--room` | `.myx-rule` | 120 | 12–20 | 11.03 | 13.47 |
| `--strip-ink-mute` | `--strip` | `.myx-sfield` | 94 | 12–14 | 5.13 | 6.23 |
| `--ink-strong` | `--room` | `.myx-rule` | 76 | 14–23 | 15.73 | 15.05 |
| `--ink-mute` | `--room-deep / --bay` | `.myx-bay` | 47 | 12–16 | — | 4.68 |
| `--ink-mute` | `--bay` | `.myx-bay` | 47 | 12–16 | 6.99 | — |
| `--strip-ink` | `--plate` | `.myx-bay-head` | 44 | 12 | 9.23 | 12.83 |
| `--strip-ink-mute` | `--plate` | `.myx-bay-head` | 36 | 12 | **3.63** ✗ | 5.05 |
| `--strip-ink-mute` | `ghosted strip (wash)` | `.myx-console` | 12 | 14 | **1.30–1.40** ✗ | 4.55–5.87 |
| `--strip-ink` | `ghosted strip (wash)` | `.myx-console` | 12 | 14 | **1.95–3.55** ✗ | 11.56–14.90 |
| `--scope-ink` | `--scope` | `.myx-scope` | 12 | 12–20 | — | 14.54 |
| `--ink-strong` | `--bay (composited)` | `.myx-scope` | 12 | 12–20 | 15.94 | — |
| `--ink-mute` | `--plate` | `.myx-bay-head` | 4 | 12 | **1.40** ✗ | 7.17 |
| `--ink` | `--strip-field` | `.myx-doc-fix` | 3 | 14 | **1.33** ✗ | 17.56 |

## The answer

**Every light pair clears AA. Seven dark pairs do not.** The row was cut on the worry that
light had become unreadable; measured, light is the clean theme and dark is where the failures
are. The three highest-traffic pairs in the console — the field box inks, 707 + 622 + 207
samples — clear in both themes and clear by more in light (5.49→7.26, 13.93→18.42,
13.02→15.83), because M1-35 raised light's paper under inks that did not move.

### The seven, and they are three causes

**1. The bay plate. `--strip-ink-mute` on `--plate`: 3.63:1 dark (n=36, 12px), 5.05:1 light.
And `--ink-mute` on `--plate`: 1.40:1 dark (n=4), 7.17:1 light.**

M1-24 bound `--plate` to `#C3B6A0` in dark, and the two inks printed on it were never
re-measured against it. This is the same shape as L3840-1 with the themes swapped: a plane was
bound, and the ink standing on it was left where it was. Looked at, not just measured — the
plate reads `summary 3` and the count is visibly the faint half of the pair
(`plate-dark-3x.png`).

**2. `.myx-doc-fix` paints the room's ink on the strip's paper. `--ink` on `--strip-field`:
1.33:1 dark (n=3, 14px), 17.56:1 light.**

Two adjacent lines in `webui/src/pages/doctor/doctor.css:93-94`:

    color: var(--ink);              /* the ROOM's ink */
    background: var(--strip-field); /* the STRIP's paper */

`--ink` is light in dark theme, because the room is dark. On paper it is light-on-light. It
passes in light only because light's `--ink` happens to be dark.

**This exact defect already has an entry.** `shared/controls/controls.css:91-94` carries it in
its own comment: "On PAPER, not on the room (m1 design review D7): this label sits in the
strip's own field box, and `--ink-mute` is the room's ink — measured 1.98:1 over `--strip`
against a 4.5:1 bar." D7 was found, fixed in `controls.css`, and never swept for elsewhere.
`.myx-doc-fix` is a second instance.

**3. The ghosted hand-off strip carries live text at 1.30–3.55:1 in dark.**

| ink | ghost wash | dark | light |
|---|---|---|---|
| `--strip-ink` | `#464641` (`.myx-console`) | **1.95** ✗ | 14.90 |
| `--strip-ink-mute` | `#464641` | **1.30** ✗ | 5.87 |
| `--strip-ink` | `#6F6D64` (`.myx-sfield`) | **3.55** ✗ | 11.56 |
| `--strip-ink-mute` | `#6F6D64` | **1.40** ✗ | 4.55 |

The samples are real content — `time`, `14:01`. In dark the ghost pulls the strip's paper down
to L\*≈29–46 while the strip's near-black inks stay where they are.

**I am naming a fork here rather than a verdict, because the answer depends on what the ghost
is for.** If the ghosted strip is decorative — a trail behind a strip in motion — then text on
it is not content and AA does not apply, but it should not be carrying a legible timestamp in
light either. If it is content, it fails in dark on four pairs. `board.css:291-299` already
documents the ghost's theme asymmetry **in the opposite direction** — that it becomes "the
heaviest object on the page" in light and "the first thing the eye lands on". So the ghost is
known to be theme-fragile; what is new here is that the direction of the fragility reverses
depending on whether you are looking at the wash or at the ink standing on it.

### One token with two jobs — the question as put

`--strip-ink-mute` is the same value in both themes, `#5A5749`, and it does clear AA on every
plane it is *meant* to sit on: `--strip-field` 5.49/7.26, `--strip` 5.13/6.23. It fails only on
planes it was never landed against — `--plate` (3.63 dark) and the ghost wash (1.30/1.40 dark).
So it is not one token with two jobs. It is one token doing its job on three planes and being
borrowed onto two more that nobody measured.

## The light room at 1536, briefly — and a correction to M1-40

The comp's own frame, which no light pass had used. Coverage, light, both frames:

| page | paper 1536 | paper 3840 | delta |
|---|---|---|---|
| fleet | 23.65% | 28.91% | −5.27 |
| turns | 30.00% | 31.21% | −1.21 |
| sessions | 24.01% | 26.86% | −2.85 |
| teams | 22.06% | 23.42% | −1.36 |
| projects | 18.98% | 17.69% | +1.29 |
| accounts | 21.34% | 21.70% | −0.35 |
| usage | 16.29% | 16.47% | −0.19 |
| settings | 43.57% | 39.70% | +3.88 |
| models | 19.72% | 19.81% | −0.09 |
| logs | 49.74% | 54.02% | −4.29 |
| **compaction** | **26.32%** | **10.89%** | **+15.43** |
| mcp | 39.82% | 33.81% | +6.01 |
| **doctor** | **28.18%** | **17.07%** | **+11.11** |

Mean |delta| **4.10 points**.

**This corrects a sentence I shipped in M1-40.** `blind-light.md` says, under "what I did not
measure", that "the coverage ratios are scale-invariant, so they will read the same" at 1536. I
did not measure that when I wrote it, and it is wrong. Coverage moves by up to **15.43 points**
between the two frames.

The direction is consistent and it is a finding in its own right: pages whose content is a
fixed-count list get **emptier as the frame grows** — compaction 26.32% → 10.89%, doctor
28.18% → 17.07%, mcp 39.82% → 33.81% — while pages whose content fills the available room
(usage −0.19, models −0.09, accounts −0.35) hold steady. The console does not fill a 3840 frame
the way it fills a 1536 one.

That does not overturn M1-40's diagnosis; it sharpens it. My M1-29 point was that the
instruments compare ratios *within* a frame and so cannot see a scale problem. They cannot —
and the thing they cannot see turns out to be real and as large as 15 points.

`blind-light.md` is outside this row's fence and M1-40 is committed (fdfa9547), so the sentence
is flagged here rather than edited there.

## What I did not measure

- **Ink on the `--scope` charts in dark.** `--scope-ink` on `--scope` appears in the light run
  (14.54:1) but the dark run resolved those 12 samples to `--ink-strong` on `#090C0C`
  (15.94:1). Both pass; I did not chase why the same nodes resolve to different roles per
  theme.
- **Hover, focus and armed states.** Every number is the resting state. `--focus`,
  `:hover` and `.myx-key-armed` change grounds and were not captured.
- **The holder-edge colours as ink.** `--edge-green/amber/red/grey` are marks, not text; where
  an edge carries a *label* that label is `--ink`/`--strip-ink` and is in the table.
- **3840.** This row is 1536 for the ink, by the row's own framing; the light coverage columns
  above reuse M1-40's 3840 frames.
- **Anything at all in the ghost's compositing chain.** I report the washes as measured
  (`#464641`, `#6F6D64`) and did not resolve which of `--ghost`, a `filter`, and the strip's
  own paper produce them.

## Regenerating this

    node webui/.impeccable/review/ink/probe-ink.mjs      # 13 addresses x 2 themes at 1536

Writes `ink-measurements.json` (4,610 rows: theme, address, class, ink, ground, groundFrom,
size, weight, large, contrast, sample) and the 13 light frames. It refuses a page on which any
colour string failed to parse.
