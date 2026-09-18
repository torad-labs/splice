# M1-40 — the light room at 3840, blind

Thirteen addresses, `splice.theme=light`, 3840x2160, captured fresh 2026-09-18 06:07–06:09
through `capturePage` from `.dev/web-console/capture.mjs`, so the four-claim guard (ANSWERED /
RENDERED / NOT FLAT / FIXTURE) applied to every frame. All 13 returned state `page`. The
M1-29 light set was not reused: it predates M1-24, M1-34, M1-35 and M1-36.

Method, in the order it was done, because the order is the point: the list below was closed
from the 3840 light renders alone — no note, no receipt, no `light-room.md`, no dark
inventory of mine was open — and only then was anything measured or re-read. The two
questions at the end were answered last.

Three dark twins (`settings`, `logs`, `fleet`) were captured at the same size for the paired
numbers. They land at the same coverage as their light counterparts to within 0.1 point —
settings 39.7% vs 39.70%, logs 51.8% vs 51.72%, fleet 38.8% vs 38.80% — so the pair differs
in pigment and in nothing else, which is what makes the comparison below a controlled one
rather than an impression.

## COVERAGE

Per page, share of the 3840x2160 frame. "field" is `--strip-field`, "strip" is `--strip`,
"rack" is the bay floor and its two gutter greys, "scope" is the chart inset.

| page | field | strip | PAPER | room | rack | plate | scope |
|---|---|---|---|---|---|---|---|
| fleet      | 26.39% |  2.52% | 28.91% | 38.80% | 19.86% | 0.10% | — |
| turns      | 30.08% |  1.13% | 31.21% | 16.53% | 35.39% | 0.24% | — |
| sessions   | 25.73% |  1.13% | 26.86% | 30.31% | 27.54% | 0.97% | — |
| teams      |  0.00% | 23.42% | 23.42% | 17.86% | 33.38% | 1.99% | — |
| projects   | 16.95% |  0.74% | 17.69% | 48.54% | 23.04% | 0.17% | — |
| accounts   | 20.01% |  1.69% | 21.70% | 41.36% | 21.87% | 2.33% | — |
| usage      | 15.99% |  0.48% | 16.47% | 32.75% | 18.69% | 0.10% | 20.54% |
| settings   | 39.70% |  0.00% | 39.70% | 34.66% | 13.32% | 0.19% | — |
| models     | 18.00% |  1.81% | 19.81% | 43.50% | 24.22% | 0.32% | — |
| logs       | 51.72% |  2.31% | 54.02% | 16.06% | 13.74% | 0.17% | — |
| compaction |  9.07% |  1.82% | 10.89% | 42.43% | 33.04% | 0.22% | — |
| mcp        | 31.83% |  1.99% | 33.81% | 24.12% | 21.94% | 0.13% | — |
| doctor     | 12.97% |  4.10% | 17.07% | 33.36% | 33.85% | 0.36% | — |
| **MEAN**   | **22.96%** | **3.32%** | **26.27%** | **32.33%** | **24.61%** | 0.56% | 1.58% |

Paper coverage in light averages **26.27%** of the frame and ranges 10.89% (compaction) to
54.02% (logs). Read that beside the next table before drawing any conclusion from it.

## The planes, both themes, measured

| plane | token | dark | L\* | R−B | light | L\* | R−B |
|---|---|---|---|---|---|---|---|
| room | `--room` | `#0B0E0E` | 3.8 | −3 | `#E0E2DF` | 89.7 | +1 |
| rail / bay | `--room-deep` / `--bay` | `#080A0A` / `#090D0D` | 2.6 / 3.4 | −2 / −4 | `#ABB0AC` | 71.3 | −1 |
| plate | `--plate` | `#C3B6A0` | 74.6 | **+35** | `#D6D8D3` | 86.1 | **+3** |
| strip | `--strip` | `#DED9C6` | 86.6 | +24 | `#F5EDDD` | 94.0 | +24 |
| field | `--strip-field` | `#E4E0D1` | 89.1 | **+19** | `#FFFFFF` | 100.0 | **+0** |

| separation (L\*) | dark | light |
|---|---|---|
| field − strip | +2.5 | +6.0 |
| strip − room | +82.9 | +4.3 |
| strip − bay | +83.2 | +22.6 |
| room − bay | **+0.4** | **+18.3** |
| plate − bay | +71.2 | +14.7 |
| headroom above the field | 10.9 | **0.0** |
| headroom above the strip | 13.4 | 6.0 |

## The list

### L3840-1 — The light room's paper is `--strip-field`, and `--strip-field` is pure white. The cream M1-35 landed is mostly not on screen.

Of the 26.27% of the frame that is paper, **22.96 points are `#FFFFFF`** and **3.32 points are
`#F5EDDD`**. `--strip-field` is the plane that actually covers this console: every value is
printed in a field box, so the box, not the strip, is what an operator looks at. It is
declared literally as `#FFFFFF`, with R−B **+0**.

Area-weighted cream cast of the light room's paper: **+3.03 R−B**. The same computation on
dark: **+19.63**. M1-35 moved `--strip` from R−B +3 to +24 to satisfy L-4, and that is
exactly what it did — `--strip` measures +24 here. But the box printed on top of it is
neutral white, and it outweighs the strip 6.9 to 1 by area, so the light room's paper still
averages +3.

**The defect moved one plane up rather than being fixed.** +3 is the same cream cast the
`--strip: #F6F6F3` finding reported before M1-35, now wearing a different token's name.

The token's own comment says the field is "one step above the strip as the dark block steps
it (1.08:1)". Measured:

- dark `--strip` → `--strip-field`: **1.070:1**
- light `--strip` → `--strip-field`: **1.164:1**

2.3x the step it claims to mirror. A true 1.08:1 step above `#F5EDDD` that keeps the strip's
own cream is **`#FEF6E5`** — L\* 97.1, R−B +25 — against `#FFFFFF`'s L\* 100.0, R−B +0.

**Why nobody saw it.** `teams` is the one page where field coverage is **0.00%** and strip
coverage is **23.42%** — the hero is built strip-first and every other page is field-first.
The comp of record is the team board, so the single page this console is checked against is
the single page that does not paint the plane at issue.

### L3840-2 — Two papers, 24 points of cream apart, side by side in one control row.

`logs`, the filter row. Measured on the frame:

| box | colour | R−B |
|---|---|---|
| the `path` input | neutral, `#DEDEDE` at the sampled patch | **+0** |
| the `search` input | `#F5EDDD` | **+24** |

Two inputs of the same kind, in the same row, on the same strip, in two different papers.
This is L3840-1 made visible in a single screenshot rather than in a coverage table.

### L3840-3 — The rack's slot rails are drawn across the control labels.

`logs`, same row: the word **"search"** is crossed by a 5px rule of `#444645` (L\* ≈ 29) at
its x-height — measured at x1500, y493–497 — and so is "new lines". The label is struck
through and unreadable. The `path` box beside it is opaque and covers the same rules, which
is why only its neighbour is damaged.

### L3840-4 — "follow 15 new lines" sets as "follow 15new lines".

Same row. The count and the following word have no space between them.

### L3840-5 — The light rack announces itself before anything is racked on it.

`room − bay` is **+18.3 L\*** in light against **+0.4** in dark. See the two questions below,
where this is the whole of the answer to the first one.

### L3840-6 — On `usage`, 20.54% of the frame is a near-black panel in a light room.

`--scope` `#1F2422`, L\* 13.6, three chart insets. The token's comment says this is measured
from the comp ("the comp's chart insets are dark on paper too"), so it is declared, not
accidental — but it is the largest single dark area in the light console by a wide margin and
no other light page carries any.

### L3840-7 — A tilted strip overlaps the label above it on `teams`.

The card reading "packet GS-42: dearm the machine-update sch…" is rotated and clips the word
"message" above it. This is E-6's territory (the lift: tilt + hard shadow, hero-only, marked
open in material-spec), so it may be intended; recorded because in light it reads as a
rendering fault rather than as a card in motion, and because it lands on an otherwise empty
rack.

### L3840-8 — The rack is mostly empty, in light as in dark.

`rack` averages **24.61%** of the frame against paper's 26.27%, and on five pages
(turns 35.39, teams 33.38, doctor 33.85, compaction 33.04, sessions 27.54) the carcass
covers more of the frame than the paper does. This is not a light-room finding — the same
ratio holds in dark — and it is recorded here only so the light numbers are not read as
better or worse than they are.

## The two questions

### Does the rack read as a rack in light?

**No — not the way it does in dark, and the numbers say why in one line: `room − bay` is
+0.4 L\* in dark and +18.3 in light.**

In dark the room and the bay floor are the same plane to within half an L\* step. Nothing
about the ground tells you a rack is there. The only thing that separates a strip from what
surrounds it is the strip's own material, at **+83.2 L\*** — a bright card on a void. That is
a card seated in a holder.

In light the bay is cut **18.3 L\*** below the room before any strip is on it, and the strip
then sits **+22.6** above that floor — of which 18.3 has already been spent putting the floor
below the page. Against the room the strip is only **+4.3 L\***.

So the light rack reads as a grey tray cut into the page, with pale content lying in it. The
relationship inverts: dark shows you an object lifted off a void, light shows you a recess
with the page's own tone inside it. Both are "separation", and the coverage numbers cannot
tell them apart — which is why this had to be looked at.

The plate is the second half of the answer. `--plate` is what bolts over each bay and is the
one member whose job is to say "rack". In dark it is a third cream at **R−B +35**, 71.2 L\*
above the bay — unmistakably a different material. In light it is **R−B +3** and 3.6 L\*
*below* the room, a neutral grey on a neutral grey. It reads as a slightly darker patch of
page, not as a plate. Coverage 0.10–2.33%, so it is also small.

### Does E-1's lip still read at +5 over paper?

**On the field it cannot exist at all: headroom above `--strip-field` is 0.0 L\*.** The light
field is `#FFFFFF`. E-1's lip is specified as brighter than the paper it sits on. There is
nothing brighter than white.

**On the strip it has 6.0 L\* of headroom, and a +5 lip would land 1.0 L\* under a plane
already in the frame.** `--strip` is L\* 94.0; +5 puts the lip at 99.0; `--strip-field`, on
that same strip, is 100.0. The lip and the field box would be one step apart.

And measured, the lip is not built today. Cross-section of a light strip's left edge on
`settings` at y1000:

| x | colour | L\* | what |
|---|---|---|---|
| 549–559 | `#616462` | 42.1 | the outline |
| 560 | `#848786` | 56.0 | one pixel of ramp |
| 561+ | `#FFFFFF` | 100.0 | paper |

Two parts, not the comp's three: outline, one ramp pixel, paper. No shade line and no lip —
which agrees with material-spec's own table, where E-1 is marked open.

So the honest answer to the question as put: the +5 lip is not there to read, and if E-1 is
built, light can carry it only on `--strip` and never on `--strip-field` unless the field
comes down off pure white. L3840-1 wants the field at `#FEF6E5` (L\* 97.1) for reasons that
have nothing to do with E-1; that value would also hand the lip 2.9 L\* of headroom on the
field, where today it has none.

## What I did not measure

- **Ink contrast in light.** Every number here is about grounds. The inks on them (`--ink`,
  `--ink-mute`, `--strip-ink-mute`) were not re-measured against the planes M1-35 moved, and
  `--strip-ink-mute` in particular is a light-theme value printed on a strip whose lightness
  changed. That is a second row's worth of work.
- **The light room at 1536.** Everything here is 3840. The coverage ratios are
  scale-invariant, so they will read the same; the absolute px measurements (the 5px rule
  across "search", the 1px edge ramp) will not.
- **Whether `#A5AAA6` / `#A4A9A5` / `#A4AAA6` are three planes or one plane plus dither.**
  They are within 1 level of each other in 8-bit and I grouped them as "rack" rather than
  claim a compositing chain I did not resolve. It does not change any separation above by
  more than 0.4 L\*.
- **The other ten pages in dark.** Three dark twins were captured, not thirteen; the
  light-vs-dark separations above are token arithmetic confirmed on those three, not a
  thirteen-page census in both themes.

## Regenerating this

    node webui/.impeccable/review/light-3840/capture-light.mjs      # 13 addresses, light, 3840x2160
    node webui/.impeccable/review/light-3840/capture-dark-pair.mjs  # settings, logs, fleet in dark

Both reuse `capturePage` from `.dev/web-console/capture.mjs`, so the four-claim guard is the
same one the dark sweep runs under; neither reimplements a capture.

Coverage is an exact-RGB count per plane over the full frame. L\* is CIE L\* from sRGB
relative luminance. R−B is the red minus the blue channel, which is how every cream number
in this campaign has been stated.
