# m1 design review — the console as built against The Strip Bay

Seat: design-reviewer, 2026-09-18. Read-only pass over the worktree at
`.claude/worktrees/v0.4.0`, requested by splice-design ahead of the m1 gate.

**What this was judged against.** `webui/.impeccable/surfaces/src-app-tsx.md` (the surface
brief and its direction contract), the approved comp `webui/.impeccable/mocks/team-board-a.png`
and its sidecar, `dev/web-console/CONTRACTS.md` sections 1 and 2,
`~/.claude/skills/impeccable/reference/craft-floor.md` and its detector floors, and the
released Torad plate system at `git show main:webui/src/shared/{tokens.css,ui/ui.css}`.

**What was looked at.** All thirteen section captures plus `hero-repro.png` (1536x1024, dark),
opened and read, not summarised; then every finding traced to a line of source. Colour,
contrast, field-grid geometry and type heights were measured off the PNGs with PIL/numpy
rather than eyeballed, and each measurement is printed beside the claim it supports.

**Coverage, stated honestly before anything else.** The capture set is **dark theme only:
13 of 26 theme x page surfaces, 0 % of the light theme.** The brief commits to two designed
themes; the light set exists in `tokens.css` and has never been rendered. Nothing below is a
statement about the paper bay. Two light-theme numbers fell out of the measurements anyway and
are recorded at B14 — both are bad, and neither has been seen by anyone.

**Three premises in the brief that the tree does not support**, flagged once here rather than
repeated below (CLAUDE.md section 6):

1. *"app.css carries 29 old-token rules."* It carries **one**. Measured: a tree-wide census of
   every retired token name gives 409 references across 30 CSS files, of which 150 are the
   definitions in `tokens.css` itself. `app/app.css` accounts for 1. The real concentration is
   `shared/ui/ui.css` (106, the old primitive block) and the three retired pages
   `pages/burn` (64), `pages/auth`, `pages/config` — all already on section 7's deletion list.
   Outside those, **old pigment survives in exactly two files**: `widgets/rail/rail.css`
   (`--mist-400`, `--mist-300`) and `widgets/team-board/board.css` (a local `--plate-deep`,
   which is a name collision, not a Torad token). The sweep is far smaller than the row implies,
   and the visible Torad-ness is not coming from where the row says it is. See B3.
2. *"Row M1-07 (in flight)."* M1-07 is `status = "done"` and its files are on disk with a
   receipt. Section D below therefore reviews five **landed** controls, not a spec.
3. *"The rule's connection cell rendering at (0,0) is fixed."* Confirmed fixed in
   `rule.css` (mtime 03:00:51). It is still present in four captures taken before that —
   `settings.png`, `accounts.png`, `sessions.png`, `projects.png` (02:55) — where
   "reconnecting no frame yet" is drawn over the `splice` wordmark. **Not reported as a live
   defect.** Those four captures are stale for the rule and should be retaken before anyone
   else reads them.

---

## A. Verdict on the comp itself

### Does `team-board-a.png` carry the Torad look?

**Yes, on the one axis that reads from across the room, and it is measurable.**

The Torad plate system is five things (`main:webui/src/shared/tokens.css`): warm rag cream,
iron-gall brown ink, vermilion as the single charged accent, serif faces (Fraunces / Source
Serif 4), and the plate emboss (radius 1-2 px, `--plate-leaf` inset and drop shadows). The comp
refuses four of them outright — near-black ink, no vermilion anywhere, no serif, radius 0 with
no shadow. It keeps the fifth, and keeps it over more surface than Torad ever did.

Measured over the whole frame: **438,798 pale pixels, mean RGB (216, 208, 190), per-channel
standard deviation 16-18, mean channel spread 26.4 (p95 = 36).** In CIELAB the comp's mean pale
is **L\* 83.7, a\* -0.3, b\* +9.9 — chroma 9.9 at hue 92 deg.** Torad's `--paper-200` is
**L\* 85.2, a\* -0.4, b\* +15.3 — hue 92 deg.** Same hue, same neutral a\*, 1.5 of lightness
apart: **dE76 = 5.6.** The build's own `--strip` (#DED9C6) is **dE76 = 3.2** from the comp's
mean pale.

There is not one pale surface of a different hue anywhere in the comp. Every strip, every label
plate, the team header and the footer are the same warm cream at different lightnesses. So the
operator's sentence is literally true of the largest surface in the frame: the console's paper
is Torad's paper at slightly lower chroma. The tokens are innocent — `--vermilion`, `--paper-0`
and the serif stack appear nowhere in the new sheet — and the silhouette is guilty.

### Does the world need strip colours?

**Yes, and the brief already promised them.** Section 3: *"opaque strips in **pale saturated
colors** with black ink on them"*. The comp's own prompt asked for the opposite — *"opaque pale
strip (warm off-white #ECEAE2)"*, singular and unsaturated. **The comp is off-brief on the one
line that would have prevented the Torad read**, and the build faithfully implemented the comp.
This is the comp round's defect, not a builder's.

It is also the world's own warrant rather than decoration: real flight progress strips are
printed on coloured stock precisely so a controller can tell classes apart without reading. A
rack of identical cream slips is the one thing an actual strip bay is not.

### By role or by state? — **By role. Never by state.**

State is already spoken for: *"Holder edge color means attention state, always with a text
label"*. If the strip body also carried state there would be two colour channels for one
meaning and the edge would be redundant. Role/class is the free axis, it is stable per page
(which is what makes a rack scannable), and it is already printed in a field on every strip —
so the colour is redundant by construction, which is the only defensible use of colour under
this world's own law.

### The proposed token set, measured

The whole objection to coloured stock is that it costs contrast. **It does not, if every stock
is cut at one lightness.** Holding L\* at 86.6 — the current `--strip`'s own lightness — and
varying only hue at C\* 16 gives five stocks whose every contrast bar is identical to today's
single cream to within +/-0.03.

**Dark (the graphite room), L\* 86.6 / C\* 16:**

| token | value | hue | ink 13:1 bar 4.5 | mute bar 4.5 | on room bar 3.0 | green | amber | red | grey |
|---|---|---|---|---|---|---|---|---|---|
| `--strip-manila` | `#E9D6BB` | 82 deg | 12.98 | 5.11 | 13.66 | 3.06 | 3.21 | 3.02 | 3.65 |
| `--strip-green` | `#C9DFC5` | 140 deg | 13.02 | 5.13 | 13.70 | 3.07 | 3.22 | 3.03 | 3.67 |
| `--strip-blue` | `#B9DEF2` | 240 deg | 12.98 | 5.11 | 13.65 | 3.06 | 3.21 | 3.02 | 3.65 |
| `--strip-rose` | `#F9CFCF` | 20 deg | 13.03 | 5.13 | 13.71 | 3.08 | 3.22 | 3.03 | 3.67 |
| `--strip-violet` | `#E2D3F0` | 310 deg | 12.99 | 5.12 | 13.67 | 3.07 | 3.21 | 3.02 | 3.66 |

Worst pairwise separation **dE76 = 15.4** (manila vs green); the rest run 17.7 to 31.3. At
C\* 10 the same five are all still above every bar but collapse to dE 4.8 in places — a tint,
not a stock. **C\* 16 is the floor for "a different colour of paper".** A straw at 100 deg was
tried and dropped: dE 4.8 from manila.

Note what the table also says: **the edge colours clear their 3:1 bar by 0.02 to 0.23 — on
today's single cream too (3.03-3.22).** The holder edge has no headroom now and coloured stock
does not take any away, but nothing may ever lighten the strip again. The cheap structural fix
is to inset the edge mark in a 2 px room-coloured gutter, which moves its contrast test to the
room: **green 4.46, amber 4.26, red 4.52, grey 3.74** — 1.4x the headroom, for two lines of CSS.

**Light (the paper bay).** Here there is a real cost and it needs a ruling. At the brief's white
paper (L\* 97) five hues collapse to **worst dE 6.2** — invisible as classes. At **L\* 94 /
C\* 12** they separate at **worst dE 11.2** and every bar still holds (ink 15.5+, mute 6.1+,
edges 3.62+): `#FBECD7 #E2F3DF #D6F2FF #FFE6E6 #F5EAFF`. So **the light theme must give up one
step of paper whiteness to carry role colour.** That is the trade; it is the orchestrator's call,
not a builder's.

**Suggested role mapping** (five is the ceiling; use four and leave manila as "unassigned"):
manila = no role / the default rack; blue = lead; green = builder; rose = observer or reviewer;
violet = the daemon's own strips (heads, accounts, knobs) as distinct from session strips. The
mapping must be printed in a field on every strip regardless, per the grayscale law.

---

## B. Material fixes, most damaging first

Buckets: **[tokens]** `shared/tokens.css` · **[prim]** `shared/ui`, before the sweep ·
**[sweep]** row M1-08, mechanical · **[page]** a page row · **[comp]** a direction change.

---

### B1 — The field grid does not line up. [prim]

**What.** The holder-edge column has no width, so every strip's first field starts at a
different x and the whole rack staggers.

**Where.** `shared/ui/ui.css:234-240` (`.myx-strip > .myx-edge` is a bare flex item) and
`shared/ui/strip.tsx:52` (`<HolderEdge>` rendered inline before the fields). The edge column is
as wide as its label text, and the label is data.

**Evidence.** Field-border x positions measured down a single bay:
`doctor.png` — 397, 394, 407, 394, 407, 401, 401, 394 (four distinct origins, 13 px spread,
driven only by "fail"/"ok"/"warn"/"info").
`compaction.png` — 533, 524, 495, 516, 512, 525 (38 px spread).
`sessions.png` — 438, 438, 438, 445, 447, and every later column shifts with it
(630→639, 879→888, 1033→1042, 1187→1195, 1359→1368).
`models.png` — 461, 461, 454, —, 452.

This is the single biggest reason the console looks wrong. The whole readability of a strip bay
is that field N is at the same x on every strip in the rack; a controller scans down a column,
never across a row. Nothing else on this list costs as much.

**Fix.** Give the edge column a fixed `ch` width in the primitive and clip the label to it (see
B10, which removes the variable-length label from the column entirely and makes this free).

---

### B2 — The type is mechanically distorted in two files. [page]

**What.** Type is anisotropically scaled to fake a condensed face: **`scaleY(1.5)`** on every
rail label, **`scaleX(0.78)`** on every label and value in the team board.

**Where.** `widgets/rail/rail.css:73` and `widgets/team-board/board.css:63-82` (two rules,
each with `width: 128.2%` to undo the horizontal squeeze).

**Evidence.** The CSS itself, and the comment at `board.css:36-42` that admits the reason:
*"Neither face carries a width axis (font-stretch is inert on both, measured), so each cell is
laid out at the width its words need and scaled back onto the comp's column."* Rendered ink
height of the rail's "fleet" in `fleet.png` measures 15 px for a declared 12 px.

**Why it is second.** It also defeats the type floor that was just re-derived. The computed
`font-size` stays 12 px, so every mechanical check passes, while the glyphs render 9.4 px wide
in the board and 12 px wide by 18 px tall in the rail. This is exactly the escape hatch the
craft floor names: *"Being ON the DESIGN.md size ramp does not exempt a value here: adding 8px
to the ramp launders the token but not the legibility problem."* A transform launders it the
same way. **Every capture passes the type-size check in the token sheet; the rail fails it in
the eye on all thirteen, and the hero fails it twice.**

**Fix.** Delete both transforms, then solve the actual problem the right way: source a face with
a real width axis. Archivo ships a variable `wdth` axis (62-125) and Archivo Narrow exists as a
static cut; the tree evidently loaded a static Archivo without it — `shared/fonts.css` is 26
lines and worth one look. The craft floor's ruling is explicit: *"Source and self-host a face
whose character matches the approved lettering; the closest installed font is a failure, not a
fallback."* A scaled face is worse than the closest installed one.

---

### B3 — One cream for every strip on every page. [comp] then [tokens]

**What.** `--strip` is a single value in both themes; every strip in the console is the same
paper, so the console's silhouette is Torad's.

**Where.** `shared/tokens.css:` the dark block's `--strip:#DED9C6` and the light block's
`--strip:#F6F6F3`. There is no role dimension in the token sheet at all.

**Evidence.** Section A's measurements: 438,798 pale pixels in the comp at one hue, dE76 5.6
from Torad's `--paper-200`; the build at dE76 3.2 from the comp.

**Fix.** Section A's five stocks. This is a comp-round change first (the comp has to be
regenerated with coloured stock before the tokens are cut from it), then a token row.

---

### B4 — The vermilion pill. [sweep]

**What.** A filled `#E2563B` rounded pill — Torad's cinnabar accent, verbatim — used as the
*selected* state of a tab group on two pages.

**Where.** `pages/settings/index.tsx:158` (`global` | `claudex`) and
`pages/usage/index.tsx:185-187` (`1 hour` | `24 hours` | `7 days`), both via
`Btn kind="primary"` → `shared/ui/ui.css:82` → `--control-fill` → `--accent` →
`--vermilion-400`, with `border-radius: var(--radius-1)` (2 px).

**Evidence.** `settings.png` at (176-300, 262-290) and `usage.png` at (176-410, 174-198); the
crop is unambiguous. Four more primary call sites will surface the same pill as soon as their
state is reached: `widgets/knob-form/index.tsx:56`, `pages/settings/sections.tsx:89`,
`features/edit-config/index.tsx:104,134`, `features/head-edit/index.tsx:104`.

**Why it matters beyond the pigment.** It is colour as the only signal for *selection*, in a
world whose law is that colour means attention and nothing else. Even in the right hue it would
be wrong. Selection is a holder edge, or the underline the view tabs already use six inches
above it — the page contains both grammars at once.

---

### B5 — `Figure` is `Metric` rebuilt, and it prints "measured". [prim]

**What.** The new figure primitive is a line-for-line re-implementation of the retired Torad
metric: a big number, a small unit, a smaller label. And it prints its basis unconditionally,
so the word "measured" is stamped beside every trustworthy number in the console.

**Where.** `shared/ui/ui.css:459-479` against `shared/ui/ui.css:47-62`:

```
.myx-metric-value  font-size: var(--text-6)     .myx-fig-value  font-size: var(--text-6)
.myx-metric-unit   font-size: var(--text-2)     .myx-fig-unit   font-size: var(--text-2)
.myx-metric-label  font-size: var(--text-1)     .myx-fig-basis  font-size: var(--text-1)
```

and `shared/ui/figure.tsx:15` (`<span className="myx-fig-basis">{basis}</span>`, no guard).

**Evidence.** The rule on all thirteen captures: `nearest window claude-grok browser 5h` then a
**23 px "2"**, a **14 px "%"** and a **12 px "measured"** with no space — reading as
`5h 2%measured`. And `NoneCell` (`widgets/rule/index.tsx:118-119`) renders
`<Figure/>` then the tail string, so the basis lands *between* the number and its noun:
**"6 measured heads report none"**. Same shape in `logs.png` ("15 new lines measured") and on
every figure in `usage.png`.

**Why it is this high.** This is the operator's sentence — *"the components even have the same
mistakes that torad design uses"* — with a file and a line number. The craft floor refuses the
hero-metric template by name, the brief's anti-goals refuse "the KPI-tile dashboard of the
category", and the primitive reproduces it under a new class name.

**Fix.** (a) Suppress the basis when it is `measured` — `StripField` already does exactly this
at `strip-field.tsx:25`, so the two primitives in one set currently disagree about the same
rule; make `Figure` match, and fix the contract line that made it wrong. (b) Collapse the three
sizes to one: a figure in a sentence is the sentence's size, and the tabular face is what marks
it as a figure. A 23 px rung inside a 14 px rule cell is what makes the top of every page look
broken.

---

### B6 — Every strip has a dead tail. [prim]

**What.** The strip stretches to its container while its fields only take the `ch` they declare,
so each strip ends in a slab of bare paper behind a hard vertical rule.

**Where.** `shared/ui/ui.css:228-233` — `.myx-strip-fields { flex: 1 }` on a strip whose
children are `flex: 0 0 auto` (`ui.css:280`).

**Evidence.** Measured field borders: `fleet.png` last field ends at x=1383, strip ends at
x=1511 — **128 px of dead paper on all seven strips**. `accounts.png`: last field ends at 717,
strip ends at 1115 — **398 px, 53 % of the strip**. `doctor.png`: fields end at 704, strip ends
at 1511 — **807 px, more than half**, while the `fix` value ("splice login claude-grok") is
squeezed into 200 px next to it.

**Fix.** `width: max-content` on `.myx-strip`; the rack scrolls (B7) and the bay's own ground
shows past the last field, which is what a rack of strips actually looks like.

---

### B7 — Racks do not scroll, so values are cut off the screen. [prim] + [sweep]

**What.** `Bay` has no horizontal overflow and no side walls, so a strip wider than the viewport
is clipped by the window rather than scrolled inside its rack. The brief's own words: *"strips
keep fixed field widths in ch units and scroll inside their bay"*.

**Where.** `shared/ui/ui.css:307-343` — `.myx-bay` has `border-top`/`border-bottom` only, and
`.myx-bay-rows` sets no `overflow`. The rule exists, copy-pasted, in five page sheets:
`fleet.css:51`, `accounts.css:46`, `doctor.css:44`, `mcp.css:45`, `head-strip.css:6`. It does
**not** exist for sessions, projects, turns, models, compaction, logs, usage or teams.

**Evidence.** `turns.png`: the `summary` and `landed` racks run past x=1536 — `refreshes 3`,
`cac…`, `- unavailabl` are cut by the window edge with no scrollbar and no bay border.
`logs.png`: every one of thirteen log lines is truncated mid-token at the frame edge
(`model=d`, `session=3`, `chunke`, `429 fr`) — on the one page whose entire job is reading a
message. `models.png`: struck strips end at x=1087 while their neighbours end at 1123.

**Fix.** Put `overflow-x: auto` and side rails in `.myx-bay-rows` once, and delete the five page
copies in the sweep. A behaviour the brief states as a law should not be a per-page patch that
eight pages forgot.

---

### B8 — Thirteen different ways to say "nothing here". [sweep] + [page]

**What.** The console has thirteen distinct absence phrasings, so a reader must *read* every
cell to discover it says nothing. This is the mechanical core of the operator's "lots of text".

**Where.** Six string tables. Census of the source:

```
unavailable 26 · none 13 · n/r 7 · no rates 6 · ineligible 3 · not reported 2
not declared 2 · not built 2 · no turn in flight 1 · not started 1 · not running 1
not reported by provider 1 · no fix offered 1
```

`widgets/scope-chart/strings.ts:23`, `pages/doctor/strings.ts:29`,
`widgets/head-strip/strings.ts:13`, `pages/models/strings.ts:22`,
`pages/projects/strings.ts:25`, `features/account-login/strings.ts:21`.

**Evidence.** `fleet.png` prints "not reported by provider" six times and "no turn in flight"
seven times in one rack — 24 and 17 characters of prose, per row, meaning *empty*.
`turns.png` prints a leading-hyphen "- unavailable" ten times (`Figure` again: value `-`, basis
`unavailable`), which reads as a typo. `doctor.png` prints "no fix offered" seven times.
`mcp.png` prints "transport 'http' already serves many clients" five times verbatim.
**The approved comp solved this with one token: it prints `n/r`.**

**Fix.** One absence glyph, one meaning, in one place — `n/r`, which the comp already chose and
which the code already uses seven times. Absence that carries a *reason* ("not reported by
provider", "no rates declared", "transport 'http' already serves many clients") is detail, and
the brief puts detail in the strip's detail on open, never in the rack cell.

---

### B9 — The field labels are reprinted on every strip. [prim]

**What.** `StripField` prints its label above its value on every strip, so a rack of seven heads
carries seventy label instances of nine identical column names.

**Where.** `shared/ui/strip-field.tsx:22`, `shared/ui/ui.css:282-287`.

**Evidence.** `fleet.png`: nine labels x seven strips. `turns.png`: ten x six in the `landed`
bay alone. `doctor.png`: three x fourteen. It also costs the type ladder twice over — the
label/value stack is why a 52 px strip carries a 12 px label and a 16 px value where one row of
16 px would fit, which is the other half of the operator's "small font".

**Why it is not simply "the comp does it too".** The comp does, and the comp is a single board
where the bays hold heterogeneous strips. A rack of *homogeneous* rows has a rack header — that
is what the printed header strip on a real bay is for. Keep per-strip labels on the team board
(B18's territory); print the labels once on the bay's own head everywhere else.

**Fix.** A `fields` prop on `Bay` that renders the column names once, at the same `ch` widths,
in the bay head; `StripField` gains `label?` and omits it inside a labelled bay.

---

### B10 — The edge label duplicates the value beside it. [page]

**What.** On four pages the holder edge's printed label is the same string as the first field's
value, so every strip says the same word twice, 8 px apart — and the label's variable width is
what staggers the grid in B1.

**Where.** `pages/compaction` (edge `model_summary` + field `outcome: model_summary`),
`pages/mcp` (edge `hosted` + field `state: hosted`), `pages/models` (edge `sonnet` + field
`slot: sonnet`), `pages/projects` (edge `repo` + field `repo: …`).

**Evidence.** `compaction.png` rows at y=390..688: `model_summary`, `model_fallback`,
`truncated`, `empty_model`, `stream_error`, `upstream_error` — each printed twice per strip.
`mcp.png` y=183..726. `models.png` y=292, 351. `projects.png`, where the edge label is the noun
"repo" on all four strips and the edge is grey, so every project reads as struck.

**Also a contract gap.** `CONTRACTS.md` section 2 caps `edgeLabel` at "3 words or fewer", which
is a word count where the layout needs a **character budget**: `upstream_error` is one word and
14 characters. And `pages/turns` passes a four-word label ("no turns in window"), over the cap,
with no check to catch it.

**Fix.** The edge label is a *state*, never a datum: ok / warn / stale / struck. Give it a
fixed `ch` budget in the contract and clip to it. Where the state word and the first field say
the same thing, drop the field.

---

### B11 — ~~Bays are not racks.~~ **RESTATED 2026-09-18:** bays carry no slot texture. [tokens]

> **The ground half of this finding is struck.** I wrote it against `CONTRACTS.md` section 1's
> prose ("graphite `#1B1D1C` class") rather than against the comp's pixels, which is the same
> error B12 made and the reason both had to be remeasured. **Measured off `team-board-a.png`:
> comp room `#0C1010` (L\* 3.65), comp bay `#0B0F0F`, comp bay-to-room contrast 1.025. The build:
> room `#0B0E0E` (L\* 2.67), bay `#080A0A`, contrast 1.024.** The build's bay separation is within
> 0.001 of the comp's. **The bay ground is correct and does not move.** A bay in the comp is not
> a darker box; it is a box with *printed slot lines in it*, and that is the whole of what is
> missing.

**What.** A bay in the comp reads as a rack because its floor carries a printed slot rhythm — a
faint line per strip slot, occupied or not. The build's bay floor is blank, so strips read as
floating on the room rather than seated in a holder.

**Where.** `shared/ui/ui.css:310` — `.myx-bay { background: var(--room-deep) }` and no rule on
`.myx-bay-rows`. `CONTRACTS.md` section 1 has no `--bay-slot`.

**Evidence.** A 240-row vertical sample across the bay region, comp against build:
**comp — 232 of 240 rows are brighter than the bay floor, peaking at L\* 52. Build — 50 hard
hairlines, peaking at L\* 70.** The comp draws many faint lines; the build draws few hard ones.
That difference is the rack.

**Fix.** Add `--bay-slot` to section 1 and give `.myx-bay-rows` a repeating slot line at the row
pitch. **The ground does not move and the pitch does not move — `--bay-slot` is the only knob.**
Acceptance is the same 240-row sample run again and reported as two numbers.

*Carried as ledger row M1-17.*

---

### B12 — ~~The room is near-black, not graphite.~~ **WITHDRAWN 2026-09-18.** [tokens]

> **This finding was wrong and is withdrawn in full.** I measured the build against
> `CONTRACTS.md` section 1's prose — "graphite `#1B1D1C` class", "pale `#ECEAE2` class" — and
> treated that prose as the authority. It never was: **neither number was ever sampled from the
> comp.** The comp of record is `team-board-a.png` and the comp's own room is **`#0C1010`, L\*
> 3.65**, against the build's `#0B0E0E`, **L\* 2.67**. The build is within one L\* unit of the
> authority. There is no glare defect, the 13.7:1 figure was measured against the right pixels
> and compared to the wrong target, and my proposed "restoration" to `#1B1D1C` would have moved
> the room **seven L\* units away from the comp** — a regression dressed as a fix.
>
> The underlying fault was real but sat in the contract, not the build: splice-design has since
> replaced section 1's colour table with measurements off `team-board-a.png` and added the rule
> that **when a token and the table diverge you sample the comp and correct whichever one the
> comp contradicts** (commit `a850540f`). The figure-face row is now marked under measurement for
> the same reason — "JetBrains Mono" was an authored name, not a measured one.
>
> **Method note for anyone rereading this document:** every finding here that cites
> `CONTRACTS.md` prose as its target rather than a pixel off the comp should be re-read with this
> in mind. Prose in a contract is an intention; the comp is the authority.

---

### B13 — The same console lays out three different ways at the same width. [page] + [sweep]

**What.** The detail column's breakpoint was decided independently on each page.

**Where.** `accounts.css:33` (1120 px), `fleet.css:38` / `doctor.css:33` / `mcp.css:34`
(1600 px), and sessions / projects / turns / compaction / models with no breakpoint at all.

**Evidence.** At the review's own 1536 px: `accounts.png` and `compaction.png` show a right-hand
detail column; `fleet.png`, `doctor.png` and `mcp.png` show the detail stacked *below* the rack
as a full-width dashed box. **The brief's stated topology — "one rail, one fixed rule, one bay
area, one detail column", "the opened strip swells into its detail on the right" — never
appears at all on the pages the operator is most likely to open.** This is the largest part of
"the user experience and layout is different" that is not about pigment.

**Fix.** One breakpoint, in one place (the shell), applied by every page row.

---

### B14 — Browser chrome showing through. [prim gap] + [sweep]

**What.** Three surfaces still ship the browser's own design.

**Where and evidence.**
- `pages/logs/index.tsx:66,74,82,91` — four `<select>`s. The fill *is* correct
  (`logs.css:36-44`: strip paper, radius 0), but `appearance` is never reset, so the OS draws
  the dropdown chevron — **and draws it at two different sizes** in the same row, visible in
  `logs.png` at (110-1080, 55-100). In a world whose contract says "no icons", the only glyph
  on the page is one the browser supplied.
- `widgets/log-tail/index.tsx:98` — a raw `<input type="checkbox">`, rendered in the OS's
  saturated blue with a white tick, at (1335, 205) in `logs.png`. The one blue the world allows
  is `--focus`.
- **38 hand-rolled `<button>`s across 13 files** (counted outside `shared/ui`, `shared/controls`
  and section 7's deletion list), each with its own page-local class:
  `myx-doc-btn`, `myx-fleet-btn`, `myx-acct-btn`, `myx-views-action`, `myx-sx-close`,
  `myx-px-close`, `myx-tn-close`, `myx-cv-more`. Visible as "add account" in `accounts.png`,
  "open head" x3 in `sessions.png`, "sessions" in `projects.png`.

**Why this is a spec gap, not just a sweep.** M1-07 landed a button, an input, a loading state
and an error note. **It landed nothing for a choice, and nothing for a two-state flag** — so
M1-08 has nowhere to sweep the selects and the checkbox to. See D6.

Also filed here because it is the same class: `.myx-fbox-input` (`field-box.tsx:30`) renders a
real `<input>` even when read-only, so Settings puts **45 focusable inputs that do nothing** in
the tab order.

---

### B15 — `Empty` is `EmptyState` rebuilt: a centred dashed card. [prim]

**What.** The new empty is the old empty with a new class name — centred text in a dashed box —
in a world that has no centred text and no cards.

**Where.** `shared/ui/ui.css:437-456` against `shared/ui/ui.css:129-138` (both
`border: dashed`, both centring). `shared/ui/empty.tsx:6` also puts `role="status"` on every
one, so a page with several empties announces all of them on mount.

**Evidence.** Two stacked dashed boxes on `fleet.png` (y=585-735), `accounts.png` (y=550-790),
`mcp.png` (y=760-900); `compaction.png`, `models.png`, `turns.png`, `logs.png` each carry one or
more. On `accounts.png` one of them is not an empty at all — "launch-time selected, never a
pool / one login per claude head" is an explanatory note wearing an empty's clothes.

**Fix.** An empty bay is an **unprinted strip in the rack**, left-aligned on the field grid,
carrying its text and its source in the fields — the vocabulary the world already has, and the
same shape `Blank` already uses for loading. Reserve `role="status"` for empties that appear
after a fetch.

---

### B16 — A four-line explanatory paragraph on Compaction. [page]

**What.** The brief bans it in one sentence: *"no paragraph on any page except an honest empty
and a Doctor fix; explanation is on demand behind a reveal, never inline"*.

**Where.** `pages/compaction`, rendered at `compaction.png` (176-590, 170-245);
`compaction.css:31` sets it a 66ch measure.

**Evidence.** *"compaction runs on the session own model and effort by law: pinning another
model would move the reasoning off the session and miss the backend prompt cache on the whole
transcript, which is the most expensive turn class there is. This page reads outcomes; it never
offers a model."* The copy is also ungrammatical — "the session own model" — and it sits in the
left 27 % of the page with 900 px of empty room beside it.

**Fix.** `Reveal` exists (`shared/ui/reveal.tsx`) and is exactly this. Move it behind one.

---

### B17 — Settings is 45 stacked cards. [prim] + [page]

**What.** `FieldBox` is a four-line vertical stack (label / value / provenance / hot), so each
knob is a ~100 px slab and the page is ~4,500 px of scroll for 45 values. The craft floor's
first refusal is "same-size cards of icon plus heading plus text as the page structure"; the
brief's anti-goal is "the KPI-tile dashboard of the category".

**Where.** `shared/ui/ui.css:379-414` (`.myx-fbox { flex-direction: column }`), the same shape
as the retired `.myx-field` at `ui.css:98-117`.

**Evidence.** `settings.png`: six knobs fill the viewport. `contextWindowOverride` renders an
**empty value line with an input underline and no empty state** and reads as broken. Three
different left edges in one column — title at x=176, bay label at x=180, boxes at x=220 — and
70 px of ragged right. The ordinals `01`-`06` sit in the gutter at 9 px, detached from the
label they number: the brief *does* earn numbering ("a numbered form of field boxes"), so this
is an execution note, not a refusal — put the ordinal in the strip's edge column where the
world already has a slot for it, or drop it.

**Fix.** A knob is a strip: `label | value | provenance | hot` on one row of the same field grid
as everything else. 45 strips in a rack is a page; 45 cards is a scroll.

---

### B18 — The charts. [page]

**What.** Three separate problems in `usage.png` and the scope insets.

**Where.** `widgets/scope-chart/scope-chart.css` and `widgets/scope-chart/index.tsx:82-97`.

**Evidence.**
- **No axes.** No y scale, no x labels, no baseline. The window ("24 hours") appears only in
  the title. `.myx-scope-body`'s `repeating-linear-gradient` grid
  (`ui.css:373-375`) is decorative — its 24 px pitch has no relation to the data — which is the
  craft floor's "sparklines… standing in for content".
- **The legend is a stack of cream field boxes inside a dark scope**, consuming about half the
  inset's height while the chart is pinned to a fixed 96 px (`scope-chart.css:7`). Two grounds
  fighting inside one frame; measured at (208-640, 618-730) in `usage.png`. The swatches sit
  *outside* their boxes on the dark ground at 12x8 px, and the 0.38 and 0.2 opacity steps
  (`scope-chart.css:51-53`) are indistinguishable from each other and from `#090C0C`.
- **"cost by hour 24 hours" draws no chart at all** — a grid, a `$0.7710` and a note, with
  basis "estimated" and no honest empty naming why.

**Fix.** Axes and tick labels; the legend in the inset's head as a single printed row, not as
strips; and an `Empty` when a series has no data, like everywhere else.

---

### B19 — The hero's hand-off reads as three messages, not one gesture. [page]

**What.** The two motion ghosts are drawn with `filter: brightness(0.5)`, which makes a cream
strip into an **opaque mid-grey strip at full alpha** — so a still frame shows three solid
strips crossing the bays rather than one strip with a trail.

**Where.** `widgets/team-board/board.css:186-188`.

**Evidence.** `hero-repro.png` (330-1030, 485-760): three fully opaque strips, each printing the
complete message "packet GS-42: dearm the machine-update schedule", overlapping each other and
the rack lines. The comp's own ghosts are translucent.

**Fix.** `opacity`, not `brightness`; and one ghost, not two, since the surface is read as a
still far more often than as an animation.

---

### B20 — The team board's own defects. [page]

**Where and evidence**, all in `hero-repro.png`:
- **The rail clips its own labels.** `rail.css:39-58` sizes the tab as a percentage of the rail
  while the label stays 12 px, and `.myx-rail { overflow: hidden }` (`rail.css:24`) cuts the
  overflow: "sessions", "projects", "accounts", "settings", "models" are shaved, and
  **"compaction" runs off its plate entirely — "tion" lands as dark ink on the dark room and is
  invisible.** The same rail renders correctly in `fleet.png`, which means *the fixed frame is
  not fixed*: page content changes the rail's width. The brief's raise is "one fixed frame that
  never moves".
- **Field labels overflow their own boxes and collide.** `.myx-sfield-label` has
  `white-space: nowrap` and no `overflow` (`ui.css:282-287`), so "window" and "last turn" run
  together as `windowlast turn` in the deepseek bay.
- **Zero horizontal padding inside bordered cells.** `board.css:58` —
  `padding: var(--space-2) 0 var(--space-0)` — puts every value flush against its cell rule.
  The detector's floor is 8 px minimum inside a bordered container, ideally 12-16.
- **The chat message wraps and is then clipped.** `board.css:215` makes the last field wrap,
  inside a row with a fixed height and `overflow: hidden` — so "packet GS-41: promoter
  bookkeep / lives on fly raw. build, run parity, rep" is cut mid-word, twice.
- **Five activity strips print only their labels**, because the activity `Bay` is given **no
  `empty` prop** (`widgets/team-board/index.tsx:230`) and the rows render regardless.
- **`edgeLabel=""` with `edge="grey"`** on every activity strip
  (`widgets/team-board/index.tsx:237-238`): a colour with no printed word, which is the one
  thing this world forbids, and grey is the struck state. The type allows it —
  `edgeLabel: string` accepts the empty string.
- **The board runs at a second type density** — `board.css:49` sets `.myx-board .myx-sfield` to
  `--text-1` where the rest of the console uses `--text-3`. The brief says "one density
  everywhere".
- **Every strip is absolutely positioned** at a measured percentage of the comp frame
  (`board.css:30-31, 93-94, 183-188, 205-215, 228-238`). That is a tracing of the comp, not a
  layout: it cannot respond to a longer session name, cannot reflow, and the eight empty rack
  rows under each bay are drawn decoration.

---

## C. What the new primitives repeat from Torad, by file and line

Ordered by how directly each reproduces the old system.

| # | New primitive | Old Torad primitive | The repeat |
|---|---|---|---|
| C1 | `Figure` — `ui.css:459-479`, `figure.tsx:13-15` | `Metric` — `ui.css:47-62` | The same three-tier hero-metric template, token for token: value `--text-6`, unit `--text-2`, label/basis `--text-1`. A refusal in the craft floor and an anti-goal in the brief, re-shipped under a new class name. |
| C2 | `Empty` — `ui.css:437-456`, `empty.tsx:6` | `EmptyState` — `ui.css:129-138` | Centred text in a dashed box. Same centring, same dashed border, in a world with no centred text and no cards. |
| C3 | `FieldBox` — `ui.css:379-414`, `field-box.tsx:28-38` | `Field` — `ui.css:98-117` | Label-above-control vertical stack, extended to four lines. Produces the card grid at B17. Also renders a real `<input>` for read-only values (`field-box.tsx:33`), which is the old `Field`'s markup unchanged. |
| C4 | `Bay` — `ui.css:307-343` | `Panel` — `ui.css:5-30` | A titled container with a head row and a body — the `.myx-panel` / `.myx-panel-head` / `.myx-panel-body` shape, minus the border. It inherited the panel's habits and not the rack's: no side walls, no slots, no overflow, and it borrows `--room-deep` because no bay token was ever cut. The ground is right; the slot rhythm is what is missing (B11, restated). |
| C5 | `HolderEdge` on a `Strip` — `ui.css:234-249`, `holder-edge.tsx:10-13` | `StatusPill` — `ui.css:32-45` | A coloured state marker inline before the content, sized by its own text. The pill's variable width was harmless in a flowing panel; in a rack it is B1. |
| C6 | `Strip` — `ui.css:218-233` | — | Not a repeat of a Torad component, but of a Torad *habit*: the row stretches to its container and the content sits inside it (B6), which is how a `.myx-panel` behaves and not how a printed strip does. |
| C7 | `ScopeInset` — `ui.css:346-376` | `Well` — `ui.css:119-127` | A sunk frame whose body is decorated rather than measured: the `repeating-linear-gradient` grid is the `Well`'s surface treatment moved onto a chart (B18). |

**Two things the new set does right**, recorded because they are the fix pattern for the rest:
`StripField` suppresses a `measured` basis (`strip-field.tsx:25`) where `Figure` does not; and
`Strip` resolves `struck` over `cocked` in one place (`strip.tsx:24`) rather than leaving it to
call sites.

---

## D. Corrections to the five controls M1-07 landed

They are landed and good in outline — real `<button>`s, no dialog, no shimmer, radius 0, tokens
from section 1. Six corrections, the first two before anything sweeps onto them.

### D1 — An armed key and a hovered key are pixel-identical. **(blocking)**

`controls.css:34-36` sets the edge amber and the border to `--strip-ink` on `:hover`.
`controls.css:53-54` sets the edge amber and the border to `--strip-ink` on `.myx-key-armed`.
**Those are the same two declarations.** So the operator who has just clicked "restart" — and
whose pointer is therefore still on the key — cannot see that it armed. `Confirm`'s entire
safety is that the armed state is unmistakable; under the pointer it is invisible.

Fix: the armed state needs a channel hover does not use. The world has one: the armed key prints
a **different word** (`confirmLabel` already does this — make sure the two labels can never be
the same length) plus the cancel key appearing beside it, and the edge should be the *red*
holder edge for a destructive arm, not the amber that hover also uses. Give hover something
quieter — the border alone, or the edge stepping from grey to the strip's ink.

### D2 — `Fault` truncates the error. **(blocking)**

`fault.tsx:27` renders the daemon's message through `StripField w={60}`, which clips with an
ellipsis and never wraps (`ui.css:288-295`). The comment at `fault.tsx:21-22` states the intent:
*"a long message clips rather than wrapping the strip"*. That is the correct trade inside a
rack, whose row pitch must hold, and the wrong one for the single field whose only job is to be
read. A SafeFailureText longer than 60 characters — most of them — becomes unreadable.

Fix: the fault's message field wraps and the strip grows. A fault is not in a rack. The board
already has the precedent (`board.css:215`).

### D3 — The key's edge is colour with no word.

`key.tsx:42` renders `<span className="myx-key-edge" aria-hidden="true" />` — a bare span with
hand-rolled edge colours in `controls.css:25-35`. It does not compose `HolderEdge` (the row says
these controls compose `shared/ui` only; this one composes nothing), so there are now **two
implementations of the holder edge**, and this one prints no label. The world's law is that
attention is a holder edge *with a printed label* and that colour is never the only signal — an
amber mark with no word breaks both.

Fix: compose `HolderEdge`, or drop the edge from the key entirely and let hover/press live in
the border. A key is not a strip that needs attention; it is a thing you press.

### D4 — `busy` disables the button, and draws nothing.

`key.tsx:38` — `disabled={disabled === true || busy === true}`. A busy key leaves the tab order
mid-interaction and focus falls to `<body>`. And `busy` has **no visual at all**: `aria-busy` is
set, nothing is drawn, and a working key looks exactly like a disabled key. The craft floor lists
loading as a required state.

Fix: `aria-disabled` plus an ignored click (keeps focus), and a visible busy state in the
world's own vocabulary — the key prints a second word, or its edge holds at grey while the
label changes.

### D5 — The 4-second auto-disarm.

`confirm.tsx:16,77` — `ARM_MS = 4_000`. A confirmation that vanishes on a clock means a
deliberate second click at 4.1 s lands on a disarmed key and silently re-arms. It fails safe,
but "I clicked confirm and nothing happened" is a bad answer for a daemon restart, and 4 s is
short for a decision that reads a second label first. There is also no key handler, so **Escape
does not cancel**.

Fix: disarm on blur, on Escape, or on a click elsewhere — all of which mean "the operator moved
on" — rather than on a timer. If a timer is kept, 4 s is too short; and it should reset while
the pointer is on the key.

### D6 — The set is missing a choice control and a flag control. **(the gap that blocks M1-08)**

Key / Confirm / Input / Blank / Fault covers pressing, confirming, typing, waiting and failing.
It does not cover **choosing from a list** or **a two-state flag** — and those are exactly the
two surfaces still showing browser chrome (B14): four `<select>`s on Logs and the system-blue
`<input type="checkbox">` in the log tail. As the set stands, M1-08 must either leave them
native or invent one per page, which is the same gap that produced this review.

Fix: add two before M1-08 runs.
- **`Pick`** — the strip's own field box printing the current value, opening a rack of options
  (a bay of one-field strips) rather than an OS menu. `appearance: none` is not enough on its
  own: a native select's popup is still the OS's, so the control has to own the list.
- **`Flag`** — a two-state key. It already has a shape in this world: the holder edge, printed
  word and all. `follow` becomes a key whose edge is green when following.

### D7 — Two smaller ones.

- **`Input`'s label is in the wrong ink.** `controls.css:76` — `--ink-mute`, the *room*'s ink,
  where every other field label in the console uses `--strip-ink-mute` on paper
  (`.myx-sfield-label`, `.myx-fbox-label`). The brief puts edits *in the strip's own field
  boxes*, which is this control's stated purpose — and on a strip, **`--ink-mute` #9D9B8E over
  `--strip` #DED9C6 measures 1.98:1** against a 4.5:1 bar. It passes today only because nothing
  has put an `Input` on a strip yet. M1-08 will.
- **`Blank` is shorter than the strip it stands in for.** `controls.css:115` computes
  `4+4+12+2+16 = 38 px` by summing font-sizes, but a rendered strip is ~52 px (measured between
  field borders at y=157 and y=209 in `fleet.png`) because line boxes are taller than their
  font-size. **The loading rack is ~27 % short and the page jumps when data lands**, which is
  the one thing a loading state exists to prevent. Derive it from a real `Strip` with empty
  fields so the two can never disagree.

---

## Where the operator's three words land

- **"small font"** — the ladder is now correct in the token sheet and every capture passes the
  mechanical floor. It is defeated in two files by transform (B2) and made to *feel* small
  everywhere by the label/value stack inside a 52 px row (B9).
- **"things overlapping"** — the live ones are B20 (rail labels off their plates and into the
  dark, `windowlast turn`, values flush against cell rules, chat text clipped mid-word) and B7
  (values cut off the right edge of the window on eight pages). The wordmark overlap in four
  captures is already fixed; retake them.
- **"lots of text"** — B8 (thirteen phrasings for *empty*, printed dozens of times per page),
  B9 (seventy label instances per rack), B10 (the same word twice on every strip), B16 (a
  paragraph the brief forbids).
- **"all the colors and design language from torad"** — B3 with the measurement (dE76 5.6 from
  Torad's rag), B4 (the literal vermilion pill), and section C, where the operator's "the
  components even have the same mistakes" is C1 through C7 with line numbers.

---

## E. Addendum, 2026-09-18 — the operator, directly: *"the font size is too small too, there is also no proper spacing, it's ugly as fuck. There is no wow to it either"*

Three more defects, measured. Section B stands; this corrects one thing B under-called and adds
two it did not name at all.

**Where the earlier pass was too generous.** B2 said the ladder "is now correct in the token
sheet and every capture passes the mechanical floor." That is true and it is not the question.
The floor is not the finding — the **distribution** is, and it fails three separate ways below.

---

### E1 — The ladder has six rungs and no steps. [tokens]

**Measured.** `--text-1..6` = 12 / 14 / 16 / 18 / 20 / 23 px. Step ratios:

```
1.167   1.143   1.125   1.111   1.150        top:floor = 1.92x over six rungs
```

**Zero of five steps reach 1.25x.** The detector's own antipattern text names exactly this:
*"Dominant heading and body roles are separated by less than 1.25× at every step, leaving the
size hierarchy flat. Add at least one stronger size step."* This ladder is a linear ramp, not a
scale: no two adjacent rungs are tellable apart, so hierarchy cannot be built out of it at all.

**And there is no top.** The largest type anywhere in the console is `--text-6` = **23 px**,
used for the wordmark and eleven page titles. Nothing is bigger. A 1536x1024 console whose
largest element is 23 px has no focal point on any page by construction.

**Why the re-derivation produced this.** The ladder was measured off the comp's cap heights —
honest method, wrong surface. The interior of a dense strip is precisely where every size is
nearly the same; measuring it faithfully reproduces a ramp with no steps. A ladder comes from
the relationship between the page's loudest and quietest element, and the comp's loudest
element (its wordmark) was measured as a *rung* instead of as the *top*.

**Fix.** Keep the data rungs where the comp put them and give the ladder a real top and a real
step. A minor third (1.2) from a 16 px body: **11 / 13 / 16 / 19 / 23 / 28 / 34 / 41**. A major
third (1.25): **10 / 13 / 16 / 20 / 25 / 31 / 39**. Either gives what the console has never had
— a rung big enough for the one number on a page that matters, three clear steps below it, and
labels far enough down that they stop competing with values.

---

### E2 — 71 % of the type sits on the two smallest rungs. [sweep] + [prim]

**Measured**, every `font-size` declaration in the tree:

```
--text-1 (12px)  59        --text-4 (18px)   8
--text-2 (14px)  50        --text-5 (20px)   9
--text-3 (16px)  16        --text-6 (23px)  11
```

**109 of 154 declarations (71 %) are 12 px or 14 px.** The rung that carries the console's
actual data — `--text-3`, 16 px — is used **sixteen times in the whole application.**

Everything is a label: bay labels, field labels, provenance lines, hot/restart verdicts, scope
titles, chart bases, empty sources, figure bases, clock words, page notes. All 12 px, all
`--ink-mute` or `--strip-ink-mute`, all competing with each other, and all of it printed on
every row (B9). The operator's "too small" is not the floor being wrong — it is that **the
console is 71 % chrome by volume and the chrome is set at the same size as itself.**

**Fix.** It is the same fix as B9, and it is subtractive before it is additive: move the column
names onto the bay head so ~70 % of the 12 px instances stop existing, then raise what remains.
Values go to the new 19-20 px rung, labels stay at the floor. Raising type without deleting
labels first just makes the mat louder.

---

### E3 — Crammed content floating in an empty room. [sweep] + [page]

The spacing scale is used the same way the type ladder is: all of it at the bottom.

**Measured**, every spacing use in the tree:

```
--space-1 (2px)   48      --space-5 (16px)  32
--space-2 (4px)  107      --space-6 (24px)  16
--space-3 (8px)   84      --space-7 (32px)   6
--space-4 (12px)  62      --space-8 (48px)   3
```

**62 % of all spacing in the console is 8 px or less. 6.5 % is 24 px or more.** There is no
separation register at all — the craft floor's rule is *"tight groups, generous separation"*
and this console has only the tight half, so nothing groups because nothing separates.

**Measured off the captures**, strip bands and the gaps between them:

```
fleet.png    strips 55px tall, gaps 4, 4, 4, 4, 4, 4      gap = 7% of the object
turns.png    strips 55px tall, gaps 4, 4, 4
```

A 55 px object separated by 4 px is a **solid mat**, not a rack. A strip bay's whole legibility
is that each slip is a discrete physical thing in a holder.

**And the same pages leave most of the canvas empty.** Dead space below the last ink-bearing
row, per capture:

```
projects 629px (61% of the page)   sessions 461px   doctor 364px
fleet    292px                     accounts 225px
```

**Five of thirteen pages cram their content at 4 px and then leave a quarter to two-thirds of
the canvas unused.** That single sentence is the whole diagnosis: the console has no spacing
*rhythm*, only a spacing *minimum*, and the room it refuses to breathe into is sitting right
there underneath it.

**Fix.** Give the world three separation registers and use them: strips 8-12 px apart inside a
rack, racks 32-48 px apart, page gutters at 32. Then spend the recovered canvas on the thing
the brief already specified and B13 shows never renders at 1536 — the detail column on the
right.

---

### E4 — "No wow": the world's middle register was never built. [prim] + [tokens]

This one is measurable too, and it has a single mechanical cause rather than being a matter of
taste.

**Tonal census** — every pixel classified as flat room (`< 30` grey), flat cream (`> 150`), or
anything in between:

```
                             room    cream    MID-TONE
the approved comp           60.2%    27.9%      11.9%
the build of that comp      64.1%    28.0%       7.9%    (a third less)
doctor.png                  95.1%     2.8%       2.1%
projects.png                78.5%    19.8%       1.7%
settings.png                50.5%    48.0%       1.5%
...all thirteen sections            average      3.3%    (max 8.3%, min 1.5%)
```

**By area the console is 96.7 % two flat values.** Paper or void, hard edge between them,
nothing in between — no depth, no material, no light, nothing for an eye to land on.

**The missing 4-10 % is not decoration; it is the world.** In the comp, the mid-tone *is* the
bays as darker racks, the printed label plates at a third cream, the rack slot lines, and the
ghost trail. Those four things are what make the comp read as a physical bay holding physical
slips instead of as rows on a page — and the build has **none** of them:

- bays carry no slot texture at all: the comp's bay floor has 232 of 240 rows brighter than the
  floor, peaking L* 52; the build draws 50 hard hairlines peaking L* 70 (B11, as restated — the
  bay *ground* is correct to within 0.001 of the comp and is not the defect);
- there is no slot line anywhere, because no token was ever cut for one;
- label plates exist only on the team board (`board.css:113`), nowhere else;
- the ghost trail renders opaque (B19).

So "no wow" is not a request for more decoration. **It is the same finding as B11, and it is the
largest single visual change available**: build the rack. That is where this world's whole
character lives, it is already drawn in the approved comp, and it costs two tokens and one
element.

**The second half of it is scale.** The quality-bar boards this round was judged against do not
win on tonal range — Ikeda measures **3.0 % mid-tone**, lower than the build. It wins on
*composition*: violent scale contrast and deliberate negative space. The build has neither
register: it is flat in tone (3.3 %), flat in type (E1: no step reaches 1.25x, nothing above
23 px), and flat in rhythm (E3: everything 4 px apart). **A surface needs to be loud on at
least one axis, and this one is quiet on all three.** Fixing any single one of E1, E3 or E4
gives the console a voice; fixing them together is the difference the operator is asking for.

**The third half is the gesture that was promised and is not there.** The comp round's own
rejection test required that *"the first viewport holds one gesture mid-flight"*. The console
ships exactly one gesture, on one page, and it renders as three opaque grey slabs (B19). The
brief's five named motions — print, cock, hand off, swell, strike — are code with no visible
result: `strike` is a hairline, `cock` is a colour swap, and `swell` (the opened strip growing
into its detail) never renders at all at 1536 px because the detail column is below the fold
on five pages (B13). **Nothing in this console moves, and nothing in it is large.** That is
what "no wow" is.

---

### What to do first, if only three things get done

1. **Build the rack** (E4 + B11) — bay ground, slot line, holder plate. Two tokens, one
   element, and it is the change that alters every page at once.
2. **Delete the labels off the strips and give the ladder a top** (E2 + B9 + E1) — subtract 70 %
   of the 12 px instances, then raise values to a rung that can be read at a glance.
3. **Give spacing three registers and land the detail column** (E3 + B13) — stop cramming at
   4 px, stop leaving 600 px empty.

B1 (the staggered field grid) stays the single most damaging defect and is cheaper than all
three.
