# The LIGHT room, re-measured

M1-25 · design-reviewer · 2026-09-18
Captures: `webui/.impeccable/review/gate/light-room/` (13 pages × 2 themes × 2 viewports + 2 sheets).
Comp of record: `webui/.impeccable/mocks/team-board-a.png` — **dark**, and the reason most of this
document is about relationships rather than values.

**Method, under law 24.** The contact sheets and the paired teams / usage / settings renders were
opened and inventoried before any token file, any ledger note, or `CONTRACTS.md` was read. Everything
in section 3 came off the pixels; sections 4 and 5 were written after, once the brief's own words were
allowed in.

## 0. The denominator, before any finding

**5 of 52 page captures are error pages**, all of them LIGHT:

```
compaction-light-1280x800   doctor-light-1280x800   doctor-light-1536x1024
mcp-light-1280x800          mcp-light-1536x1024
```

All five carry the same SyntaxError — `'/src/shared/controls/confirm.tsx?t=…' does not provide an
export named 'ARM_MS'` — which is a hot-reload race during capture, not a theme defect. But the
consequence is real and bounds this row: **`doctor` and `mcp` have no valid LIGHT capture at either
viewport.** This document therefore covers **11 of 13 pages in light**, not 13. `mcp-dark-1280x800`
additionally carries a Vite error overlay, so the dark set is 12 of 13 for that page pair.

(My first error-detector found zero of these, because I set its threshold from an assumption — "an
error page is white with red text" — instead of from the artifact. The page is `#E4E4E4` with black
text. The detector that works keys on ink distribution: an error page paints the top 260 rows and
nothing below, ratio 0.0000. Recorded because a validity check that cannot fail is worth less than no
check.)

---

## 1. The plane census

Sampled as region medians (not point samples — a point lands on a glyph) at matched addresses on
`teams-{light,dark}-1536x1024.png`:

| plane | LIGHT | L | DARK | L |
|---|---|---|---|---|
| room / rule ground | `#E0E2DF` | 225.4 | `#0B0E0E` | 13.4 |
| rail = bay floor = bay head band | `#ABB0AC` | 174.6 | `#080A0A` | 9.6 |
| strip paper | `#F6F6F3` | 245.8 | `#DED9C6` | 216.7 |
| ghost strip | `#7B7B7A` | 122.9 | `#6F6D63` | 108.7 |
| chart scope (`usage`) | `#1F2422` | 34.8 | `#090C0C` | 11.4 |

| contrast pair | LIGHT | DARK |
|---|---|---|
| bay vs room | **1.690** | **1.024** |
| rail vs room | 1.690 | 1.024 |
| **strip paper vs room** | **1.204** | **13.704** |
| strip paper vs bay | 2.034 | 14.031 |
| ghost vs bay | 1.924 | 3.821 |
| ghost vs its own live twin | 3.913 | 3.672 |
| **chart scope vs page ground** | **12.082** | **1.013** |

Two structural facts fall straight out of that table and govern everything below.

**Headroom is not symmetric between the themes.** In dark every plane sits at the black end with the
whole range above it. In light they sit at the white end with almost nothing above:

| | dark: room below it | light: room above it |
|---|---|---|
| paper | 216.7 L | **9.2 L** |
| room | 13.4 L | **29.6 L** |
| bay | 9.6 L | 80.4 L |

**In dark, the world's materials are lights on a dark ground. In light there is no room for them to be
lights.** That is the whole of section 4.

---

## 2. The one thing that already works, and why

`--bay-slot` is the only material in the world specified as an **alpha over its own plane** rather than
as a hex, and it is the only one that survives the inversion cleanly. Vertical profile at x=350 in the
empty rack:

- **light** — floor L 174.6, slot rows at L 151.6 → **−23.0 L**
- **dark** — floor L 9.6, slot rows at L 31.8 → **+22.2 L**

Same magnitude within one level; the sign follows the theme on its own because an alpha composites
against whatever is beneath it. **This is the working precedent, and section 4 is an argument that
every material M1-24 adds should be written the same way.**

---

## 3. Findings, ranked by what a person notices first

### L-1 — On a light page, a fifth of `usage` is painted near-black · LIGHT-ONLY

The chart scopes do not invert. Measured: the scope ground is `#1F2422` (L 34.8) in light against a
page ground of `#E0E2DF` (L 225.4) — **12.08:1**. In dark the same pair is **1.013:1**, i.e. the scope
is invisible because it *is* the room.

The tonal census puts a number on how much of the page this is: `#1C2420` occupies **22.2 % of the
light `usage` frame.** The frame-coverage figures printed on the two contact sheets show the same
thing from the other side — every page reports an identical "room" fraction in both themes (fleet
0.274/0.273, teams 0.169/0.169, settings 0.302/0.302, models 0.416/0.416 …) **except `usage`, which
reads 0.336 light against 0.560 dark.** The scope counts as room in dark and does not in light. One
page in thirteen diverges, and it is the page with the charts.

It is aggravated, not caused, by two findings already filed: the field boxes render *inside* the scope,
so a light page shows white boxes on a near-black slab on a light ground — three grounds in one
object — and the cost chart still draws nothing.

**Is this a defect?** `--scope` exists in `CONTRACTS.md` §1 as "chart inset ground", and a scope that
stays dark in both themes is a legitimate instrument-bezel decision. What is not a decision is that
nobody has stated it. Measured and ranked first because it is the largest visible difference between
the two themes; **the ruling on whether a scope inverts is splice-design's.**

### L-2 — The ghost inverts its meaning · LIGHT-ONLY, and the cause is a dark-derived mechanism

`.myx-board-ghost` uses `filter: brightness(0.5)`. Brightness multiplies luminance, so it always moves
a colour **toward black** — which reads as *receding* on a dark ground and as *advancing* on a light one.

The ghost's relationship to its own live twin is preserved almost exactly (**3.913 light / 3.672 dark**),
which is why this survived review: the mechanism looks theme-stable in isolation. Its relationship to
the **ground** is what inverts. Order the planes:

- **dark** — bay 9.6 · room 13.4 · **ghost 108.7** · paper 216.7 → the ghost sits *between* ground and
  paper. A faded strip. Correct.
- **light** — **ghost 122.9** · bay 174.6 · room 225.4 · paper 245.8 → the ghost falls *below the bay
  floor*. It is no longer a faded strip; it is the darkest and heaviest object on the page.

In the light `teams` render the two ghosted hand-off strips are the first thing the eye lands on. The
intent is "behind"; the result is "in front".

**The relationship it should hold.** In dark the ghost sits at **(108.7 − 9.6) / (216.7 − 9.6) = 47.9 %**
of the way from ground to paper. Holding that fraction in light gives 174.6 + 0.479 × (245.8 − 174.6)
= **L 208.7** — a value *between* bay and paper, where a faded strip belongs, instead of 122.9. Express
it as a `color-mix` toward the ground, never as a `brightness()` multiplier: a multiplier encodes the
dark theme's direction in the mechanism itself.

### L-3 — The world's central figure/ground is 1.204:1 in light · LIGHT-ONLY

Strip paper against the room: **13.704 in dark, 1.204 in light.** Against the bay: 14.031 against 2.034.

The Strip Bay world has one central gesture — printed paper seated in a holder — and it is carried
entirely by the separation between paper and what it sits on. At 1.2:1 the paper is not seated on
anything; it is the same value as the room, one step lighter.

**No light comp can arbitrate this** (see §4), so the relationship, not the value: **the ratio cannot be
matched and should not be.** Equal contrast is arithmetically impossible in a light theme — paper at
L 245.8 would need a ground near L 12 to reach 13.7:1, which is a dark room by definition. What must
be preserved is **rank and interval**, and the interval is what has collapsed: in dark the ground→paper
span is **207.1 L**; in light it is **71.2 L**, about a third. Every material and every state that has to
be legible inside that span — the ghost, the edge, the field box, the mute ink — has a third of the
room it had.

### L-4 — The light strip is not paper, and the contract's justification for it does not check out

`CONTRACTS.md` §1 says: `--strip` — "light: the comp's `#F6F6F3`". Tested against the comp of record:

- pixels within 6 of `#F6F6F3` in `team-board-a.png`: **232 of 1,572,864 = 0.015 %.** Not a plane; noise.
- the comp's actual paper, median over all 357,588 pixels brighter than mean 200: **`#DED6C4`**.
- **cream cast (R − B): comp paper +26. Dark token `#DED9C6` +24 — matches. Light token `#F6F6F3` +3.**

So `#F6F6F3` is not measured off the comp, and the difference is not lightness: **it is the loss of the
cream.** The dark theme's strip is paper; the light theme's is white card. That is a change of material,
made silently, and the contract sentence asserting a comp measurement for it is the same class of
error as the "graphite `#1B1D1C`" line that B12 was wrongly measured against — which this campaign has
now struck three times.

**The relationship it should hold**, and it is a conjunction of two constraints, neither of which names
a colour:
1. **Cream is a property of the material, not of the light.** `R − B` of `--strip` in light must sit
   within one step of the dark token's **+24**.
2. **The paper must have room above it for its own lit edge.** E-1's lip measures **+13 L over the
   paper**; light paper at L 245.8 has **9.2 L** of headroom, so it cannot carry it. `--strip` in light
   must sit at **L ≤ 242**.

Any value meeting both is defensible. Picking one here would be the authored number this row exists to
prevent.

### L-5 — M1-11's derivation no longer describes the light block, and the rows that broke it are named

M1-11 derived light when "every plane sat within **1.14 to 1.30** of its ground against dark's 13.70 to
14.66." Today:

| pair | light | inside M1-11's band? |
|---|---|---|
| strip vs room | 1.204 | yes |
| **bay vs room** | **1.690** | **no** |
| **rail vs room** | **1.690** | **no** |
| **strip vs bay** | **2.034** | **no** |

The two planes outside the band are the ones **M1-13 added after M1-11 ran**, and nothing re-derived
the band around them. This is not an argument that 1.690 is wrong — see L-6, where it is probably
right. It is an argument that **the light block's stated law and the light block's actual values are two
different things**, and one of them has to give.

### L-6 — The themes disagree about whether a bay is a plane, and this changes what M1-24 should build

- **dark:** bay vs room **1.024**. The bay is not a distinct plane. It is my own restated B11: the rack
  is carried by *material* — frame, stiles, slot rails — and the ground does nothing.
- **light:** bay vs room **1.690**, and `--bay` and `--room-deep` are deliberately the same value
  (`#ABB0AC`). The rack is carried by a **ground step**, and the light captures show visible bay columns
  where the dark ones show none.

Two different mechanisms for the same job, neither declared. The consequence is immediate: **M1-24 is
about to add eight materials, all sampled dark, to both themes.** In dark they are the only thing
separating a bay from the room and they are necessary. In light the ground already separates at 1.690,
and adding a full carcass on top risks an over-articulated rack — the light page getting both a step
and a frame where the dark page gets only a frame.

Measured support: total edge energy on `teams` is **2.206e7 light against 1.939e7 dark — light already
carries 14 % more articulation**, and in the room-and-rack zone it is 22 % of the frame's energy against
dark's 19 %.

This is a design fork and it is the orchestrator's, not a builder's.

### L-7 — Everything else that differs between the themes is the light face of something already filed

Checked pair by pair on `teams`, both viewports, and found in **both** themes, therefore not light-only:
the hand-off strip overflowing its bay leftwards over the rail (B7); the repeated header row between
every data row in the activity table; `team id … updated: 2025-05-22 14` clipped mid-value; no cell
dividers in the rule (M1-24's E-7); heavy `…` truncation across table cells; and the surplus green edge
on the team header (E-8, now ruled a deletion). Light makes several of them *more* visible because the
contrast against paper is higher, but none of them is a light defect.

---

## 4. The eight M1-24 materials in light, where the comp cannot arbitrate

**It cannot, and here is the check rather than the assertion.** All three comps of record —
`team-board-a/b/c.png` — are dark (dominant grounds `#080C0C`, `#0C1010`, `#081010`; mean L 74.9, 71.9,
87.2). The only light-ground artifacts in the mocks tree are `decision/canon.png` and
`decision/model-pick.png`, and `canon.png` is **the rejected incumbent** — blue accent, pill buttons,
icon nav, progress bars, the generic SaaS look the Strip Bay world was chosen to replace. It is an
anti-reference. `comp-spec.mjs` excludes `decision/` from the comps round for exactly this reason.

So: **no light value in this world can cite a comp, and any that does is citing prose.** What follows is
therefore a relationship per material, with the dark measurement it is tied to.

### The rule, derived from §1's headroom table and §2's working precedent

> **Magnitude comes from the comp; sign comes from headroom.** Every material is written as an alpha
> over the plane it sits on — never as a hex, never as a filter — with its magnitude measured off the
> dark comp and its direction chosen by which side of that plane has room. `--bay-slot` is the
> precedent: +22.2 L in dark, −23.0 L in light, one token pair, no special-casing.
>
> **Corollary, and it has teeth:** when a material must keep its sign in both themes for the object to
> read at all, and the light plane has no headroom for it, **the plane is wrong, not the material.**

Applying it, with each delta measured off `team-board-a.png` in M1-21 and carried onto the light plane:

| M1-24 entry | comp L | sits on | delta | light plane | literal result |
|---|---|---|---|---|---|
| E-1 outline | 3.6 | room | −11.5 | 225.4 | 213.9 ok |
| E-1 shade | 112.9 | paper | −100.1 | 245.8 | 145.7 ok |
| **E-1 lip** | 226.0 | paper | **+13.0** | 245.8 | **258.8 — CLIPS** |
| E-3 stile face | 29.3 | room | +14.1 | 225.4 | 239.5 ok |
| **E-3 stile highlight** | 58.7 | stile face | **+29.4** | 239.5 | **268.9 — CLIPS** |
| E-3 stile shadow | 0.0 | stile face | −29.3 | 239.5 | 210.2 ok |
| E-4 bolt | 2.0 | stile face | −27.3 | 239.5 | 212.2 ok |
| E-2 slot rail | 53.4 | bay | +41.7 | 174.6 | 216.4 ok |
| E-2 rail shadow | 0.9 | bay | −10.8 | 174.6 | 163.8 ok |
| E-5 pin | 8.6 | paper | −204.4 | 245.8 | 41.4 ok |
| **E-7 rule divider** | 59.3 | room | **+44.2** | 225.4 | **269.6 — CLIPS** |
| E-6 lift shadow | 4.6 | bay | −7.1 | 174.6 | 167.5 ok |

Three of the twelve clip, and **every one that clips is a highlight** — the three materials that are
*lighter* than the plane they sit on. That is the headroom asymmetry of §1 arriving as a build failure
rather than as a table. Per material:

- **E-1's lip** is the load-bearing one. It cannot be built in light against `--strip: #F6F6F3`, and it
  is on every strip on every page, so M1-24 would ship an edge that clips to white eleven pages wide.
  The remedy is L-4's second constraint — `--strip` light at **L ≤ 242** — not a smaller lip.
- **E-3's stile highlight** is a lit edge on a raised member and genuinely must stay lighter than its
  own face. Its face has 15.5 L of headroom in light against the comp's 29.4 L requirement, so the
  light *bay* plane is the thing to move, or the stile keeps its face-relative sign and takes a reduced
  magnitude with that reduction recorded as a deliberate light-only deviation.
- **E-7's rule divider** is the easy one: in dark it is a bright line on a near-black bar; in light it
  should simply be a **dark** line on a light bar, at the same |44| magnitude, sign flipped. This is the
  ordinary case the rule handles, and `--hairline-strong` already exists in both themes for exactly it.

**E-8 — the surplus green bar.** Ruled a deletion, and light adds nothing to the ruling: the bar is
present in both themes and equally unearned in both. One note for whoever cuts it — the green edge is
the only chromatic object on the light `teams` page, so removing it from stateless headers will make
light's remaining green edges *more* meaningful, not less visible.

---

## 5. What I could not measure, and what I am not claiming

- **`doctor` and `mcp` in light.** No valid capture at either viewport; five of fifty-two captures are
  error pages. Every "across every address" statement in this document means **11 of 13 pages**. The
  two missing pages carry controls (`confirm`, whose hot-reload caused the errors) and the doctor's
  fault strips, which are the surfaces most likely to hold a light-only state defect. **This row did not
  inspect them and cannot vouch for them.**
- **Whether 1.690 is right for a light bay.** L-6 states the disagreement and its consequence for
  M1-24; it does not resolve it, because resolving it is choosing what a rack is made of in a theme
  with no comp, which is a design decision.
- **Whether the scope should invert.** L-1 measures it at 12.082 against 1.013 and ranks it first.
  Whether a chart bezel stays dark in a light room is a stated decision the world has never made.
- **The two reduced-magnitude options in §4** (moving the light bay plane vs recording a light-only
  deviation for E-3's highlight) are alternatives, not a recommendation. Both are defensible; only one
  should be written down, and the choice belongs with the same seat that rules on L-6.
- **Every light value proposed here is a constraint, never a colour.** `L ≤ 242`, `R − B ≈ +24`,
  `47.9 % of the ground→paper interval`, `same magnitude, flipped sign`. If a later row wants a hex, it
  derives one that satisfies the constraint and then re-measures the capture — because a light value
  with no stated tie to a measured dark one is an authored number, and this campaign has struck three
  of those today.

## 6. Regenerating

`review/gate/` is gitignored. Every number above is a region median or a profile on a named capture at
a named address, re-takeable in three lines:

```python
from PIL import Image; import numpy as np
a = np.asarray(Image.open('webui/.impeccable/review/gate/light-room/teams-light-1536x1024.png').convert('RGB'))
print(np.median(a[880:900, 300:360].reshape(-1,3), axis=0))   # the light bay floor: [171 176 172]
```

Where a number here disagrees with the capture, the capture wins and the number is corrected in place.
