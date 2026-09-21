# The material spec: what the comp has that the build does not

M1-21 · design-reviewer · 2026-09-18
Comp of record `webui/.impeccable/mocks/team-board-a.png` (1536×1024).
Build `webui/.impeccable/review/sections/teams.png` (1536×1024, same scale, so 1 comp px = 1 px here).
Crops and overlays: `webui/.impeccable/review/gate/compdiff/look/`.

**Method, under law 24.** Every band crop was opened and inventoried before a single number was
taken. The dark bands were levels-stretched (0–40 → 0–255) only to *read* them; every value below is
sampled from the unstretched comp. Nothing in this document came from the build's source, which I did
not open for this row.

**Scale caveat.** The capture is 1536 px wide and the comp is 1536 px wide, so the pixel figures are
CSS px at a 1536-px viewport *if the capture is 1×*. If it is 2× DPR, halve every px figure and keep
every colour. Confirm before coding.

---

## 1. Where the deficit actually is

Edge energy (`|∂L/∂x| + |∂L/∂y|`, summed) over the whole frame: **comp 2.814e7, build 1.988e7 — the
build carries 71 % of the comp's, a 29 % deficit.** Split by zone, measured rather than modelled:

| zone | comp | build | deficit | share |
|---|---|---|---|---|
| within ±4 px of a paper boundary | 2.136e7 | 1.743e7 | 3.94e6 | **48 %** |
| the rack and room, away from paper | 6.51e6 | 2.42e6 | 4.09e6 | **50 %** |
| paper interior (type, column rules) | 2.64e5 | 4.18e4 | 2.23e5 | 3 % |

Three consequences, and the first two decide what this document is about.

- **E4 is not a typography problem.** The paper interior — where every glyph and column rule lives —
  is 3 % of the deficit. The type is not what makes this console look empty.
- **It is two problems of almost equal size: the edge of every strip, and the carcass of every rack.**
  In the rack zone the build retains **37 %** of the comp's energy.
- The strips themselves are the right shapes in the right places: cream covers **28.0 %** of the comp
  and **27.7 %** of the build, with a boundary length of 88,920 px against 86,414 px. Nothing needs to
  move. What is missing is the three pixels either side of every boundary, and everything behind them.

---

## 2. Drawn, or the renderer's accident

The packet asks this per entry, and one measurement settles most of it at once.

The comp's bay ground (y 895–912, x 300–520) measures **mean L 11.68, std 0.90**, and its
**horizontal 1-px difference std is 1.04** — one level. That is 8-bit quantisation, not texture. The
grain visible in the stretched crops is a 6.4× amplification of ±1 level. **The mottling is an
artefact. Do not reproduce it, do not add a noise layer, do not spend a token on it.**

For contrast, the build's same patch has a 1-px difference std of **0.17** — a CSS flat fill, exactly
as expected, which is what makes the comparison trustworthy: an image-generated comp carries ~1 level
of noise and a rendered page carries none, so anything above that floor in the comp is drawn.

By the same test, every entry in section 3 is **drawn**: each is a coherent member 12–110 L above or
below its ground, with consistent lighting across every instance in the frame. Two things I looked
for and did **not** find, recorded so nobody adds them:

- **No vertical gradient inside a bay.** Mean L per row across the bay interior (x 180–545, y 840–958,
  every 12 px): 12.5 · 50.7 · 11.6 · 12.2 · 2.3 · 11.9 · 12.3 · 18.3 · 19.0 · 3.8. The highs are rails
  and the lows are gutters; there is no trend. The bay floor is flat.
- **No paper texture.** Strip interiors carry the same ~1-level noise as the ground.

---

## 3. The entries, ranked by the share of the deficit they close

### E-1 — The strip's three-part holder edge · **~48 % of the deficit** · every strip in the world

**What it is.** The comp's paper does not meet the room in one step. It meets it in three: a near-black
outline, a mid-grey shade line, then a bright lip on the paper's own edge, after which the paper ramps
up over ~8 px to its body tone. It is a card seated in a holder with light catching the near edge. The
build goes from room to paper in a single hard transition.

**Where, measured.** Cross-section across the team strip's left edge, comp y=90:

| x | L | colour | what |
|---|---|---|---|
| 152–156 | 15→30 | `#0C1010`→`#1C1F1D` | rail/room outside |
| **157** | **3.6** | **`#030402`** | **1 px outline, darker than the room** |
| **158** | **112.9** | **`#73716A`** | **1 px shade** |
| **159** | **226.0** | **`#E8E2D1`** | **1 px lip, brighter than the paper body** |
| 160–167 | 204→213 | `#D3CCB9`→`#DDD5C3` | 8 px inner ramp to body |
| 168+ | ~213 | `#DCD5C3` | paper body |

The build at the same place: room `#0B0E0E` → `#DED9C6` in one step, no outline, no shade, no lip, no
ramp. Per-pixel edge energy: comp ≈ 256, build ≈ 204 across a boundary of 88,920 px.

**Mechanism.** Not a border — a border cannot hold three tones. `box-shadow` with three non-blurred
spread rings on the strip element, or `outline` + `border` + an `inset` highlight:
```
box-shadow:
  0 0 0 1px #E8E2D1 inset,      /* the lip, on the paper's own edge */
  0 0 0 1px #73716A,            /* the shade */
  0 0 0 2px #030402;            /* the outline, against the room */
```
The 8 px inner ramp is a second inset: `inset 0 0 8px rgba(0,0,0,.05)` reads as the measured
204→213 climb. Verify the ramp against a crop before shipping it; it is the least certain value here
and it is worth ~10 L over 8 px.

**Tokens.** All three are new. Measured values to carry: `--strip-edge-line: #030402`,
`--strip-edge-shade: #73716A`, `--strip-edge-lip: #E8E2D1`. The lip is one step above `--strip`
(`#DED9C6`) and the shade sits between `--strip-ink-mute` (`#5A5749`) and `--strip-field-line`
(`#A8A392`) — check whether `--strip-field-line` can carry the shade before cutting a token for it.

**Hero-only or world?** **World.** It is on every piece of paper in the frame, which is why it is the
single largest entry.

---

### E-2 — Slot rails in the bay floor · **~17 % of the deficit** · **ALREADY OWNED — M1-17, restated B11**

Listed for completeness and not re-specified. The measurements are M1-17's acceptance; the
cross-section below is offered only because this row took it anyway and it adds the shadow half.

Comp x=350, band-7: ground L 12.4; rail at **y=852 L 53.4 `#343634`, 1 px**; then **y=856–858 at L 7.4,
0.9, 3.4 — a 3 px under-shadow darker than the floor** (`#000102`). Pitch measured 852→883→915→943 =
**31, 32, 28 px**. Build at the same column: ground perfectly flat `#080A0A` L 9.6, rails at L 31.8
`#1F2020`, single rows, **no shadow**, and spaced 842/871/874/877/906/920/938 — table row borders, not
a constant pitch.

Frame-wide: long horizontal bright runs in dark ground, **comp 74 rows / 21,795 px; build 26 rows /
10,611 px.** `--bay-slot` already exists at `rgba(236,234,226,.19)`; there is **no token for the
under-shadow**, and the shadow is half of what makes a rail read as a groove rather than a line.

---

### E-3 — The rack carcass: stiles with a lit edge · **~15 % of the deficit** · every rack

**What it is.** Each bay is a box with vertical side members. A stile is a flat face brighter than both
the bay floor and the room, with a 1 px highlight on its outer edge and a hard black shadow on its
inner edge. It is what makes a bay read as a container instead of a change of background.

**Where, measured.** Comp y=875, crossing a stile between two bays:

| x | L | colour | what |
|---|---|---|---|
| 553–558 | 10–19 | `#0A0C0C`–`#111415` | gutter |
| **559** | **58.7** | **`#3A3B3A`** | **1 px outer highlight** |
| **560–573** | **25–31** | **`#1A1D1C`–`#1D201F`** | **14 px stile face** |
| 574–575 | <6 | — | hard dark inner edge |
| 576–593 | 7–16 | `#0C1010` | room between bays |

The same member in band-1 (comp y=190, a different rack entirely): **12 px face at L 24–32
(`#191B19`–`#1E211E`), bounded at x=164–165 by L 0.0 `#000000`** — a pure-black 2 px shadow. Two racks
590 px apart agree on the construction, which is what makes this a world rule and not a one-off.

The build at band-1 y=190: `#0B0E0E` stepping to `#080A0A` at x=151 and nothing else. Flat.

**Geometry.** Face **12–14 px**; bay inner width ≈ 379 px; bay outer pitch ≈ 443 px.

**Mechanism.** The bay element gets left and right borders, not a background:
```
border-inline: 13px solid #1B1E1D;
box-shadow: inset 0 0 0 1px #000000;   /* the inner black edge */
```
with the outer highlight as a 1 px `outline: 1px solid #3A3B3A` or a fourth shadow ring.

**Tokens.** New: `--rack-stile: #1B1E1D` (L 28), `--rack-stile-lit: #3A3B3A` (L 59),
`--rack-shadow: #000000`. Note `--rack-stile` sits between `--room` (`#0B0E0E`) and the existing
`--hairline-strong`; it is a *surface*, not a line, so it needs its own value.

**Hero-only or world?** **World.** Measured in band-1 and band-7, top and bottom of the frame.

---

### E-4 — Bolt insets in the stiles · **~3 % of the deficit** · every rack

**What it is.** Dark rectangular holes punched into the stile face at a regular pitch. They read as
the holes a shelf rail clips into. They are recesses, not raised rivets: they are *darker* than the
stile, not brighter.

**Where, measured.** Comp stile x=566, y 835–960. Stile median L 28.4. Dark insets at **y 849–857 and
y 896–904, each 9 px tall**, spanning **x 556–577, 12–14 px wide**, colour **`#020202`** — effectively
the room punched through the stile. Pitch **47 px** (849 → 896), and a third at 946 (+50). The pitch is
the bolt's own, not the slot pitch of 31.

**Mechanism.** `repeating-linear-gradient` down the stile border, or a `::before` on the bay with a
repeating background at 47 px. No new colour: `#020202` is within a rounding step of `--room-deep`
darkened, but measure before aliasing it to an existing token — at this size a wrong value reads as
dirt.

**Tokens.** `--rack-bolt: #020202`, pitch `47px`, size `13px × 9px`.

**Hero-only or world?** **World**, wherever a stile is drawn. Cut it in the same row as E-3; a stile
without bolts is a plain bar and loses most of what makes the rack read as hardware.

---

### E-5 — Holder pins on the paper · **~2 % of the deficit** · every strip row

**What it is.** A small round dark pin printed on the paper at the strip's edge, one per row. In the
comp the team strip carries one at the left of each of its two rows. This is the mark the world uses
where the build uses a colour bar (see E-8).

**Where, measured.** Comp, upper pin on the team strip: extent **14 × 14 px** at the L<100 threshold
with a solid core around 8–9 px and a soft edge; darkest pixel **`#0A080A`**; centre colour
`#181A16`. Its centre sits **6 px right of the strip's left edge** (strip x0 = 159) and **10 px below
the strip top** (strip y0 = 66) — i.e. vertically centred in the 46 px strip's first row.

Verified by eye at 8× in `look/pin-corner-8x.png`: comp top, build bottom. The build has none.

**Mechanism.** A `::before` on the strip row: `width/height: 9px; border-radius: 50%; background:
#0A080A;` positioned from the row's leading edge. It is *ink on paper*, so it takes the paper's own
ink, not a holder colour.

**Tokens.** Reuse `--strip-ink` (`#141414`) — measured `#0A080A` is one step darker and the difference
is below the threshold that matters on a 9 px dot. **No new token.** This is the cheapest entry here.

**Hero-only or world?** **World.**

---

### E-6 — The lift: a handed-off strip is tilted and casts a hard shadow · **~2 % of the deficit** · **hero-only**

**What it is.** The strip mid-hand-off is rotated off the rack's axis and throws a shadow onto what is
behind it. It is the one gesture in the comp that is a *moment* rather than a state, and it is the
single most expressive element in the frame.

**Where, measured.** Band-4's hand-off strip, top edge sampled every 40 px from x=420 to x=980
(15 columns: y = 524, 522, 519, 516, 513, 511, 508, 505, 501, 499, 497, 493, 490, 487, 483). Linear
fit: slope **−0.0726 px/px → −4.15°**, rising to the right.

The shadow, sampled at four columns where open rack lies below (x = 400, 430, 460, 490): a **hard dark
line at L 2.6–8.5 (`#010303`–`#070908`), 5–6 px below the strip's lower edge**, against a rack ground
of L ≈ 18–25 — **15–22 L below the surface it falls on**. It is a *line*, not a gradient: the rows
either side of it are 3–4× brighter. A blurred `box-shadow` is the wrong instrument.

**Mechanism.** `transform: rotate(-4.15deg)` plus `box-shadow: 0 6px 0 -1px #030505` — zero blur, 6 px
offset. Keep it behind `prefers-reduced-motion` only if it animates; as a static state it is not
motion.

**Tokens.** `--lift-angle: -4.15deg`, `--lift-shadow: #030505`, offset `6px`.

**Hero-only or world?** **Hero-only**, and deliberately so: it belongs to the hand-off gesture, not to
every strip. Cut it last of the six, and cut it as one row, because it is the entry most likely to be
judged by eye rather than by number.

---

### E-7 — Cell dividers in the top rule · **<1 % of the deficit** · the rule only

**What it is.** Thin vertical rules separating the rule bar's cells. The build separates its cells with
whitespace alone, which is why the bar reads as floating words rather than a instrument panel.

**Where, measured.** Comp y=12 (a text-free row inside the bar, whose ground is L 13.4): **four** thin
bright verticals at **x = 140–141, 452–453, 620, 1106–1107**, width **1–2 px**, colours `#47443F`,
`#38342F`, `#53514C`, `#302F2D` — call it `#3E3B37`, L ≈ 40. The bar itself has a top edge at y=6
(L 45.6 `#2D2E2C`) and a bottom edge at y=48 (L 28.6) followed by a shadow row at y=50 (L 7.1).

The build at the same row: **zero**.

**Mechanism.** `border-inline-start: 1px solid` on each rule cell after the first.

**Tokens.** `--rule-divider: #3E3B37`. Check `--hairline-strong` (`rgba(236,234,226,.22)`) first — over
`--room` it resolves near this value and would need no new token.

**Hero-only or world?** The rule bar is chrome on every page, so **world**, but it is one bar and the
cheapest fix in the document. Cut it wherever it fits.

---

### E-8 — A subtraction: the green bar the comp does not have

**What it is.** Not missing material — *surplus* material, and the reason it belongs in this document is
that it is the most saturated object in the build's hero and it has no counterpart in the comp.

**Where, measured.** The team header strip (y 66–111, x 159–1510): **comp — 0 green pixels. Build — 498
green pixels at `#548630`** (`--edge-green`), as a full-height bar at the strip's leading edge. Where
the build puts that bar, the comp puts the two pins of E-5 and nothing else.

**The judgement, which is not mine to make.** Either the build is signalling a state on the team header
that the comp does not signal, or the bar is decoration that arrived because `.myx-strip` carries a
holder edge by default. The comp does use green holder edges — they are on the *data rows* inside the
bays, where a row has a state. A header has no state. My reading is that the holder edge belongs to
the row and leaked onto the header, but the comp is the authority on where state is shown and
splice-design owns that call. **Measured and flagged; not specified.**

**Hero-only or world?** Wherever a stateless strip currently draws an edge.

---

## 4. The ranking, and what it adds up to

| # | entry | share of the deficit | scope | owner |
|---|---|---|---|---|
| E-1 | strip's three-part holder edge | **~48 %** | world | open |
| E-3 | rack stiles with lit edge | ~15 % | world | open |
| E-2 | slot rails + under-shadow | ~17 % | world | **M1-17** |
| E-4 | bolt insets | ~3 % | world | open |
| E-5 | holder pins | ~2 % | world | open |
| E-6 | the lift: tilt + hard shadow | ~2 % | **hero-only** | open |
| E-7 | rule cell dividers | <1 % | world | open |
| E-8 | the surplus green bar | — | world | **judgement** |

E-1 through E-7 account for roughly **87 %** of the measured 29 % deficit. The remaining ~13 % is
type rendering (the comp is greyscale-antialiased, the build subpixel — M1-15's row, not material) and
the hand-off strip's own composition, which comp-diff charges to structure rather than detail.

**How the shares were derived.** E-1 and E-2/3/4 come from the measured zone split in section 1 —
paper-boundary 48 %, rack 50 % — with the rack's 50 % divided between rails, stiles and bolts in
proportion to the linear extent each contributes (rails 21,795 px, stiles ≈ 28 members × ~120 px,
bolts ≈ 3 per stile × 13×9 px). E-5, E-6 and E-7 are counted directly from their instances. The zone
split is measured; the division *within* the rack zone is an estimate, and the sensible way to settle
it is to build E-3 and re-run comp-diff rather than to argue about the arithmetic.

**The acceptance for the rows that follow this one.** Not the ranking — the instrument. After each
entry lands, re-run `comp-diff.mjs` and read the **per-region `detail` column** with no region below
65. Today it reads 83 · 78 · 82 · 71 · 50 · 45 · 31 · 52 and the aggregate reads 86 % `match`. The
aggregate will keep reading `match` the entire time this work is being done, which is exactly why the
campaign now forbids it.

---

## 5. What I could not measure, stated so it is not mistaken for absence

- **Pin count across the frame.** My blob detector returned 12 for the comp and 50 for the build, and
  the build's are letterforms — an `o` and an `e` are round dark marks on paper too. The detector is
  wrong and I am not reporting its number. E-5 gives the *rule* (one pin per strip row at the leading
  edge) and one instance measured exactly; a builder needs the rule, not a tally.
- **Vertical members frame-wide.** Comp 80 columns / 5,228 px against build 79 / 4,853 px — nearly
  equal, and the count is worthless because it cannot tell a rack stile in the dark ground from a
  table column rule on cream. The finding survives only because the two cross-sections (y=190 and
  y=875) name the object. An aggregate that does not separate object classes cannot see the defect,
  which is the same lesson as the 86 % `match`.
- **The 8 px inner ramp in E-1.** Measured once, at one edge, on one strip. Worth ~10 L. Check it at a
  second edge before it becomes a token.

---

## 6. Regenerating the evidence

`review/gate/` is gitignored, so every crop this document cites is regenerable rather than committed.
The comp-diff pass and its band crops:

```bash
node ~/.claude/skills/impeccable/scripts/comp-diff.mjs \
  --comp webui/.impeccable/mocks/team-board-a.png \
  --build webui/.impeccable/review/sections/teams.png \
  --out-dir webui/.impeccable/review/gate/compdiff --label m1-teams
```

The band crops in `look/`, the 0–40 → 0–255 stretches, and the 8× pin crop came from throwaway PIL
scripts, not from a tool. Every number in sections 2–3 is a direct sample of
`mocks/team-board-a.png` at the stated coordinate, so any of them can be re-taken in three lines:

```python
from PIL import Image; import numpy as np
c = np.asarray(Image.open('webui/.impeccable/mocks/team-board-a.png').convert('RGB')).astype(float)
L = lambda a: 0.2126*a[...,0] + 0.7152*a[...,1] + 0.0722*a[...,2]
print(L(c)[875, 559], c[875, 559])   # E-3's stile highlight: 58.7, [58 59 58]
```

If a number here disagrees with the comp, **the comp wins and the number gets corrected in place** —
the same rule that governs `CONTRACTS.md` section 1.
