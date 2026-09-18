# The BLIND inventory at 3840×2160

M1-29 · design-reviewer · 2026-09-18
Captures: `webui/.impeccable/review/gate/blind/` — 13 addresses × 2 themes at **3840×2160**, plus
three 1:1 crops. 26 of 26 rendered; **zero error pages**, verified by ink distribution.

## How this was done, and the one way it is not blind

Captured first, through the repo's own vendored CDP plumbing (`.dev/web-console/lib/cdp.mjs`, seeding
`splice.theme` and the management key before boot). Then **the list below was closed from the renders
alone** — whole frame first, then 1:1 crops of the rail, the rule and a bay — **before re-opening any
ledger note, receipt, commit, `material-spec.md`, `light-room.md`, the punch list, or my own m1
review.** Measurements were taken after the list was closed, to put numbers on entries that already
existed, never to find them. Cross-referencing came last.

**The honest limitation:** I am the seat that wrote B1–B20, E-1–E-8 and L-1–L-7, in this same session.
I cannot un-know them, and no procedure makes me a naive observer. What law 24 could actually buy here
is what I did buy: the list came from the 3840 pixels rather than from a document, and nothing was
reordered or suppressed to match a row in flight. **A genuinely blind pass needs a reviewer who has not
read the earlier work.** That reviewer does not exist on this campaign, and the gap is worth naming
rather than papering over.

---

## 1. The finding that contains all the others

**Mean paper coverage of the content area, 13 dark pages at 3840×2160: 13.7 %.**

The content area is the frame minus the rail and the rule — 3480 × 2030 px. "Paper" is every pixel of
strip, plate or field. This is the fraction of the console that is *printed on*:

| page | paper | | page | paper |
|---|---|---|---|---|
| projects | **2.4 %** | | mcp | 8.6 % |
| usage | **2.9 %** | | turns | 12.2 % |
| sessions | 3.8 % | | teams | 24.1 % |
| doctor | 3.9 % | | logs | 25.5 % |
| models | 4.9 % | | settings | **70.0 %** |
| accounts | 6.1 % | | | |
| compaction | 6.4 % | | | |
| fleet | 7.7 % | | | |

**On eleven of thirteen pages less than a quarter of the console carries anything. On five it is under
5 %.** `projects` prints on 2.4 % of the space it occupies.

Settings at 70 % is the same defect inverted, not an exception: its strips run the full width to hold
one word each (§2, N-6). Both ends are one cause — **nothing in this layout has a considered
relationship to the width it is given.** At 1536 that reads as generous spacing. At 3840 it reads as an
empty room, and the operator has been looking at the empty room for two days while every instrument
measured the furniture.

---

## 2. The inventory, ranked by what the eye lands on first

`NEW` = no id I can attribute. `FACE` = the large-viewport face of something filed. `FILED` = already
has a row and needs nothing from me.

### N-1 · The console is empty · **NEW**
Section 1. The number is 13.7 %, and no instrument in this campaign could have produced it, because
every one of them was pointed at a 1536 frame where the same layout covers 2.5× more of the visual
field. Ranked first because it is what a person sees before they see anything on the page.

### N-2 · The type did not scale with the frame · **NEW** (M1-26 is deriving the scalar; this is the BEFORE)
Ink heights measured at 3840×2160:

| text | ink height |
|---|---|
| column header `repo` (on the room) | **9 px** |
| knob name `authCacheMs` | **9 px** |
| knob basis `default` | **9 px** |
| `restart to apply` | **9 px** |
| strip label `team` | 15 px |
| rule clock digits | 14 px |
| **wordmark `splice`** | **17 px** |
| **rail tab label `fleet`** | **18 px** |
| page title `projects` | 21 px |

Two things at once. **The floor is 9 px of ink on a 3840-wide frame** — the label on every knob name
and every column header, roughly 1.6 mm of cap height on a 32″ 4K panel. And **the hierarchy is
inverted: a nav tab label (18) is larger than the wordmark (17)**, and the page title is 21 px on a
frame 3840 px wide — 2.3× the smallest text, carrying a whole page.

The type did not shrink. The frame grew and the type did not follow. That is the operator's own
hypothesis, and it is correct.

### N-3 · The rule bar is five items with up to 801 px of nothing between them · **NEW**
Measured ink clusters across the top band and the gaps between them:

```
splice          19..78
                ---- 306 px of nothing ----
clocks         384..597
                ---- 555 px of nothing ----
daemon ok     1152..1219
                ---- 355 px of nothing ----
reconnecting  1574..1732
                ---- 799 px of nothing ----
nearest window 2531..2873
                ---- 801 px of nothing ----
heads          3674..3801
```

It does not read as a bar. It reads as six pieces of debris on a black band. **The band is 118 px tall
and its tallest ink run is 19 px** — 99 px of empty height. E-7's missing cell dividers are a
contributing cause but not the finding; at this width, dividers alone would not make this a bar.

### N-4 · Every plate is ~95 % empty, and the emptiness is all on one side · **NEW**
The `fleet` rail plate measures **197 × 58 px = 11,426 px**. Its label bbox is **31 × 18 = 558 px, 4.9 %
of the plate**; the ink itself is **1.36 %**. Padding: **left 12 px, right 154 px**, top 15, bottom 25.

So the plate is not "generously padded" — it is a left-aligned word with a 154 px tail, the same shape
as the strips in N-6. The bay head plates (`head: claude`, 460 × 55 carrying ~15 px of centred text)
are the same object at another size.

### N-5 · The slot lines run the full frame width, across gutters and across the void · **NEW** (distinct from B11/M1-17)
On `teams`, hairlines run continuously from x≈375 to x≈3810 at a constant pitch, crossing the gutters
between the three bays and continuing through the entire empty lower half of the frame. Two
consequences: a bay stops reading as a container, because the rack lines do not stop at its edge; and
the ruling actively draws the eye across the emptiest part of the page.

M1-17 is about whether slot texture *exists* and at what contrast. This is about its **scope** — that
it belongs to the page rather than to a bay — and that question is not in that row.

### N-6 · A 3400 px strip holding the word `true` · **NEW**
`settings`, at 3840: every knob strip spans essentially the full content width to carry a name and a
value. `debug` / `true`. `effort` / `high`. `foldMaxTier` / `6`. `controlPort` / `3096`. The value is
~40 px of ink on a ~3390 px strip. This is why settings measures 70 % paper while carrying less
information per page than any other address.

### N-7 · The hand-off strips are two cards that fell out of the layout · **FACE of B7**
At 1536 the tilted strips overflow their bay leftwards over the rail — filed. At 3840 the overflow is
not the story: the two strips sit alone in the middle of a very large empty region, at an angle,
touching nothing, with the nearest content 400 px away. The gesture reads as debris rather than as a
hand-off, and only the extra emptiness makes that visible.

### N-8 · Column headers sit on the room, and are 10–30 px left of the columns they name · **NEW**
`projects`, 1:1: the header row (`repo | live | teams | turns | cost | last seen`) is room-ink text on
the black rack, above the strips, while every other value in this world is printed on paper. It is also
misaligned — `live` at x≈487 against a cell boundary at x≈497, `cost` at x≈700 against 722, `last seen`
at x≈907 against 940 — consistently left of its own column, by 10 to 33 px.

### N-9 · The active-tab marker does not touch its tab · **NEW**
The green bar marking the current address floats ~40 px to the right of the rail plate, separated by
room. A holder edge that does not touch what it holds is not a holder edge; at this size the gap is
unmistakable.

### N-10 · The state word is printed over the holder edge · **NEW**
`projects`, 1:1: `busy` and `quiet` begin inside the coloured edge rather than beside it, so the first
glyph sits on the green or grey bar. Every row on the page.

### N-11 · `usage`: five small scopes in the top-left, and the legend eats the plot · **NEW** + **FILED** (cost chart)
The five chart scopes are ~365 × 280 px each, clustered in the top-left; below y≈1000 the frame is
entirely empty. Within a scope the vertical budget is roughly **plot 35 %, legend 25 %, empty grid
40 %** — the field boxes take the bottom of the inset and the plot is squeezed into the top third. The
cost scope still draws nothing, which is already filed.

### N-12 · The rail pin is a 5 px square in the corner · **NEW** (E-5 said the strips have no pins; it did not say the rail has a wrong one)
Each rail plate carries a ~5 × 5 px dark **square** at its top-left corner. The comp's pin is a ~9 px
**round** mark, vertically centred on its row. At 3840 a 5 px square reads as a speck of dirt.

### N-13 · Settings ordinals live outside the strip, at 9 px, on the room · **NEW**
`01`…`15` sit to the left of each knob strip as room-ink digits. Off paper, at the type floor, on the
largest screen the product supports.

### N-14 · Irregular row pitch on `settings` · **NEW**
Strip heights vary (a knob with no value keeps its height but loses a line), so the black bands between
rows alternate between ~0 and ~14 px down the page. The column does not scan.

### N-15 · Fifteen of forty-five knobs fit on the largest screen made · **NEW**
The tab reads `runtime knobs 45`; fifteen are visible at 2160 px tall. Each row is ~60 px to carry two
short strings.

### Already filed, confirmed at this size and needing nothing from me
**B6** the dead cream tail — at 3840 the head-table strip's columns end at x≈427 of a strip reaching
x≈1010, so **58 % of the strip is empty paper**; the tail is now the majority of the object.
**B13** three layouts at one width. **E-1** the strip's missing three-part edge — every strip here is a
flat cream rectangle on black. **E-3** the missing stiles — the bay gutters read as separation rather
than as two racks side by side, exactly because there are no side members. **E-7** rule dividers, see
N-3. **E-8** the surplus green bar, ~14 px wide at this scale and the most saturated object in the
frame. **M1-12/M1-26** the ladder, see N-2. **L-1** the light scopes.

---

## 3. What this changes

**The NEW category is eleven of fifteen entries**, and splice-design predicted both the size and the
content: every one of them is about **scale, spacing, or the relationship between an object and the
space it is given**. Not one is a colour, a contrast ratio, or a token value — the three things this
campaign has measured exhaustively for two days.

The reason is mechanical and worth stating plainly. Every instrument built here — comp-diff, the zone
split, the tonal census, the plane census, look-gate, the contact sheets — compares *ratios within a
frame*. Ratios are scale-invariant. **A 1536 capture and a 3840 capture of the same page have almost
identical ratios and completely different pictures**, so every instrument was right and so was the
operator. 13.7 % paper coverage, 9 px labels and 801 px voids do not appear in any of them because none
of them was ever pointed at a frame this size.

**This list is the BEFORE.** M1-26 is deriving the viewport scalar and M1-24 is building the material;
nothing here was softened, reordered or merged because a row is in flight. If M1-26 clears N-2 and N-4
and M1-24 clears N-5's scoping, that is the proof those rows worked, and it is only proof because the
list was written first.

## 4. What I did not do

- **I inspected four pages closely** — `teams`, `projects`, `settings`, `usage` — at whole frame plus
  three 1:1 crops, and scanned the other nine. The 13.7 % figure and the bounding boxes cover all
  thirteen; the per-entry findings do not. A second pass at 1:1 on the remaining nine will find more,
  and `logs`, `doctor` and `compaction` are the likeliest to hold something this list misses.
- **I did not measure the light captures beyond confirming all 13 rendered.** Every entry above is from
  the dark set. The light set is captured and in the fence for whoever takes the next row.
- **I did not open a source file**, and no entry names a cause in CSS. Where I say "the plate is 95 %
  empty" that is a measurement of the render, not a claim about which rule produced it.
- **The 1:1 crops are three regions of one page.** Text-height figures come from those plus targeted
  windows; two of the twelve windows caught a neighbouring line (the 26–27 px readings), so treat
  those two as upper bounds and the 9 px floor — which recurs in four independent places — as solid.
