# M1-24 — the material. What landed, what it moved, and what it could not reach.

Tree captured: `feat/v0.4.0` @ `05e0d3050deac7786e2610ce5477cd378669d860`, working tree dirty
(this row plus three peer seats live on the same picture). Source hash under test, over
`webui/src/**/*.{css,tsx}`: `b8b965b5d2042849ec18f1565a05b7eb77b2b0b4dee411…` — recomputed with
`find webui/src -name '*.css' -o -name '*.tsx' | sort | xargs sha256sum | sha256sum`.
Captures were taken **last**, after every edit.

Every number below is a region `detail` from `comp-diff.mjs` against
`.impeccable/mocks/team-board-a.png`, 1536x1024. **Detail is the high-frequency energy ratio:
"did the material survive, or did an illustration become a gradient."** It is the one score in
that instrument that this row is about, and it is the score the row's proof names.

## 1. The per-region detail table, before against after

| region | before | after | delta | |
|---|---|---|---|---|
| top-rule | 0.4263 | 0.4869 | **+0.0606** | <65 |
| wordmark | 0.4146 | 0.4146 | +0.0000 | <65 |
| clocks | 0.5188 | 0.4666 | −0.0522 | <65 · LIVE |
| health | 0.3712 | 0.3712 | +0.0000 | <65 |
| nearest-window | 0.3271 | 0.4501 | **+0.1230** | <65 |
| no-window | 0.3422 | 0.1802 | −0.1620 | <65 · LIVE |
| rail | 0.5379 | 0.5472 | +0.0093 | <65 · out of fence |
| rail-labels | 0.5730 | 0.5730 | +0.0000 | <65 · out of fence |
| team-header | 0.3776 | 0.3623 | −0.0153 | <65 |
| bay-claude | 0.7651 | 0.8012 | +0.0361 | |
| bay-claude-label | 0.4182 | 0.5473 | **+0.1291** | <65 |
| lead-strip | 0.7316 | 0.7300 | −0.0016 | |
| lead-strip-2 | 0.7251 | 0.7234 | −0.0017 | |
| lead-strip-3 | 0.6665 | 0.6691 | +0.0026 | |
| bay-deepseek | 0.8071 | 0.8923 | **+0.0852** | |
| bay-deepseek-label | 0.4492 | 0.5368 | **+0.0876** | <65 |
| builder-strip | 0.7688 | 0.7901 | +0.0213 | |
| builder-strip-2 | 0.7474 | 0.7784 | +0.0310 | |
| builder-strip-3 | 0.7348 | 0.7557 | +0.0209 | |
| handoff-strip | 0.6564 | 0.6751 | +0.0187 | |
| chat-bay | 0.7685 | 0.8157 | +0.0472 | |
| chat-label | 0.4732 | 0.5787 | **+0.1055** | <65 |
| chat-strips | 0.7787 | 0.7655 | −0.0132 | |
| activity-bay | 0.7094 | 0.8018 | **+0.0924** | |
| activity-label | 0.3929 | 0.4371 | +0.0442 | <65 |
| activity-strips | 0.6292 | 0.6610 | +0.0318 | |
| footer-strip | 0.2675 | 0.3125 | +0.0450 | <65 |

**Overall 0.8555 → 0.8760, +2.05 points.** Eleven regions gained, four lost, twelve flat.

## 2. The row's proof, read honestly

The packet asks for **no region below 65**. That was not reached, and the reason divides in two,
both measured rather than asserted:

**Nine regions this row cannot reach, because the material is not what is wrong with them.**

- `wordmark`, `health` (unchanged to four decimals), and `clocks` / `no-window` / `nearest-window`
  are the top rule's TEXT cells. Their cap heights are the whole deficit: `comp-check` reports
  `text.wordmark.cap comp 15.90px got 17.00px`, `text.clocks.cap comp 12.00 got 14.00`,
  `text.health.cap comp 12.40 got 11.00`, `text.window.cap comp 11.70 got 14.00`,
  `text.none.cap comp 12.00 got 14.00`, `text.bay.label.cap comp 10.90 got 9.00`. Type is
  explicitly out of this row's fence.
- **`clocks` and `no-window` are not A/B comparable across captures at all.** They carry live
  values — one capture shows `3 heads report no window`, another `6 heads report none` and
  `reconnecting no frame yet`. The −0.0522 and −0.1620 are content drift between a capture taken
  at 08:58 and one taken at 05:2x the next morning, not a regression. Proven: two captures taken
  seconds apart agree to four decimals (`no-window` 0.1802 twice), and the before/after crops show
  different sentences. Three of the twenty-seven regions cannot be measured this way.
- `rail` and `rail-labels` are `src/widgets/rail/rail.css`, outside this row's fence. The comp's
  rail tabs carry a plate edge and a pin at each end, exactly like the strips; the fence cannot
  reach them.

**Six regions the material did reach but did not close** (`team-header`, `footer-strip`,
`activity-label`, `bay-claude-label`, `bay-deepseek-label`, `chat-label`). Their remaining deficit
is geometry and type, not material:

- **`team-header` — the strip is 21px too tall.** Comp y64 outline → y98 outline, 35px, two text
  rows split by a rule at y86. Build y61 → y116, **56px**. The header's `height: 5.308%`
  (board.css) is the region box's height, and the comp's strip is 69% of its region. That is strip
  geometry, which this row is forbidden to touch, and it is the single largest remaining deficit in
  the region. **Reported, not fixed.**
- **`footer-strip` — the footer's text is set larger than the comp's** and the strip overflows its
  region (`overflow: hidden` clips `updated: 2025-05-22 14:02:05`). Type, and strip width.
- The four `*-label` regions are now within 1 L of the comp's plate face (measured 183 against the
  comp's 184-186, where the build was 191). Their deficit is the plate's own text.
- `activity-strips` crossed 65 (0.6292 → 0.6610) and `handoff-strip` crossed it (0.6564 → 0.6751).

**One premise in the packet does not hold, and it is worth stating.** "No region below 65" and
"do not touch the type" cannot both be satisfied: ten of the fifteen sub-65 regions are text
regions whose detail is a function of cap height, and five of them are unchanged to four decimals
by any material change. The material moved the regions that have material in them.

## 3. What was built

**E-1 · the three-part holder edge** (`ui.css`, `.myx-strip`; `ui.css`, `.myx-bay-head`;
`board.css`, `.myx-board-footer`). The comp crosses room → paper in three steps. Built as
**the border is the lip**, the shade is the first outer ring, the outline is the second, and the
8px ramp is an inset shadow:

```
border: var(--hair) solid color-mix(in srgb, var(--strip) 76%, white);   /* lip   comp L 226.0 */
box-shadow:
  inset 0 0 var(--space-2) color-mix(in srgb, transparent 90%, black),   /* ramp  comp 204→213 */
  0 0 0 var(--hair) color-mix(in srgb, var(--strip) 52%, black),         /* shade comp L 112.9 */
  0 0 0 calc(var(--hair)*2) color-mix(in srgb, var(--room) 27%, black);  /* line  comp L 3.6   */
```

Verified on the rendered page, left edge of the team strip: comp `157:4 158:112 159:225 160:204`
against build `153:4 154:113 155:226 156:214`. **Three tones, three rings, in the comp's order.**

The lip is the BORDER and not an inset ring because it was an inset ring first and rendered
invisible: an inset box-shadow paints above the element's background but **below its children**,
and `.myx-board .myx-sfield` is an opaque plate of the same paper, so the first field covered the
lip on every racked strip. The comp lights the strip on all four edges (top y304 L232, bottom
y366 L232, left x616 L225) and the border is the one ring a child cannot cover. The border was
already `--hair` wide, so no field origin, box or measured position moved.

**E-5 · the holder pins** (`ui.css`, `.myx-strip::before`; `board.css`, the header and footer).
9px, `--strip-ink`, no new token. Drawn on every strip and covered by the holder mark wherever a
strip has one — the mark is 16px wide at `inset-inline-start: 0`, full height, opaque in every
state, and painted after the pseudo-element. A stateless strip therefore shows its pin and an
edged one does not, which is E-5's own sentence: *"the mark the world uses where the build uses a
colour bar."* The header's trailing pin is `board.css`'s; comp pins measure x 164.5 and x 1504.5
at strip x0 159.

**E-8 · the green bar is deleted** (`board.css`, `.myx-board-header`). The comp has 0 green pixels
there; the build painted 498 at `#548630`. The mark is deleted, **the 16px edge column stays** so
the header's fields keep the grid every strip in the bay reserves. The comp agrees: its header's
paper runs to x 159 and its first field starts past the same inset.

**E-3 · the stiles** and **E-4 · the bolt insets** (`ui.css`, `.myx-bay` and its two background
layers). Face measured L 25-31 on two racks 590px apart, 1px lit outer edge at L 58.7, hard
near-black inner edge. Written against `--bay`: the face is the floor lifted 7.5% of the way to
white (9.6 → 28), the lit edge is the face lifted 13.7% of its remaining room. Bolts are 6px
phase, 9px hole, **47px pitch — deliberately not the floor's 31px slot pitch**, in the same layer
as the rail so the two cannot fall out of register.

**The rail is DRAWN, not laid out, and that is a measured decision.** `.myx-board-bay { border:
none }` (board.css:137) suppresses the bay's rails on the hero, and the strips are placed as a
percentage of the bay (`.myx-board-strip { left: 2.5%; width: 91.071% }`) — so restoring the
border would move and narrow **every strip in both bays**. The rails are therefore two
background layers sized `var(--space-4)` wide at the left and right edges, which cannot move
anything, and the border stays for every bay that lays out around one. Verified: build x 569-579
face at L 28-29, x 580 dark, x 581 lit — comp x 559 lit, 560-573 face L 25-32, 574-575 dark.

**E-2's shadow half** (`ui.css`, the bay's floor ribbing). M1-17 closed the rail and left the
shadow. The comp at x=350 band-7 carries the rail at L 53.4 **plus a 3px under-shadow darker than
the floor** at L 7.4, 0.9, 3.4. Added as an alpha over the floor, not the comp's hex. The slot is
now rule → shoulder → notch → wash, all length stops, pitch unchanged.

**E-6 · the lift** (`board.css`, `.myx-board-handoff-live`). Hard shadow, `0 6px 0 -1px`, **zero
blur** — a blurred box-shadow is the wrong instrument and would read as the old world. **The angle
moved: −4.41° → −4.15°.** M1-05 read it off the region's corners; E-6 fitted the top edge over 15
columns and this row re-took that fit against the comp — x 420…980 step 40 returns y 524, 522,
519, 516, 513, 511, 508, 505, 501, 499, 497, 493, 490, 487, 483, a least-squares slope of −0.07259
= **−4.152°**. Fifteen samples beat two corners; the change is named so it is not silent.

**L-2 · the ghost** (`board.css`). `filter: brightness(0.5)` is gone. Brightness multiplies
luminance, so it always moves a colour toward black — receding on a dark ground, **advancing on a
light one**. Replaced by a mix holding the measured fraction:
`color-mix(in srgb, var(--strip) 47.9%, var(--room-deep))` — the light-room's
`(108.7 − 9.6) / (216.7 − 9.6) = 47.9%`, which renders **108.8 in dark against the comp's 108.7**
and **208.7 in light**, a value between bay and paper. The far ghost takes `--ghost`'s own 15%,
because the comp's hand-off band is bimodal (peaks at L 25-35 and 60-80 and a dominant band at
100-125) and the build's two ghosts read as one flat 105.

**The rack tokens now have readers** (M1-35's census, routed here by the orchestrator):
`--bay` 0→9 readers, `--plate` 0→6, `--plate-line` 0→1, `--ghost` 0→1. `.myx-bay`'s ground was
`var(--room-deep)` — three levels off its own floor, 1.06:1 against the room — and is now
`var(--bay)`. The plate was a local derivation (`--strip` mixed toward its mute, 190) and is now
`var(--plate)`, which measures **183 against the comp's 184-186**. In light `--bay` already held
the value that block used as its floor, so the light theme did not move at all.

## 4. Out of this row's fence, reported rather than edited

1. **E-7 · the rule's cell dividers** — `src/widgets/rule/rule.css`. The comp has four thin
   verticals in the top rule at x 140-141, 452-453, 620, 1106-1107; the build has zero. One
   declaration (`border-inline-start: var(--hair) solid …` on each cell after the first), but the
   file is not in this row's fence.
2. **The rail tabs** — `src/widgets/rail/rail.css`. `rail` 0.5379 and `rail-labels` 0.5730,
   unchanged, because the file is out of fence. The comp's tabs carry the same plate edge and the
   same pins-at-both-ends as the strips.
3. **`--strip` in light must move.** E-1's lip is **+13.0 L over the paper** and light's paper at
   L 245.8 has only **9.2 L of headroom**. With the current light `--strip` the lip renders at
   +2.2 instead of +13 and the light theme loses its one lit edge. The remedy is L-4's second
   constraint, **`--strip` light at L ≤ 242 with R − B within a step of +24** — a plane fix, not a
   smaller lip, and `tokens.css` is M1-26's. **This is the one value M1-24 needs routed.**
4. **The look gate's `field-grid` rule has shifted its anchor, and its remedy is two lines in a
   file this row does not hold.** `look-gate.mjs:219` searches each strip row for pixels within 26
   of `#A8A392` — `--strip-field-line`, the strip's outer box line — and reads `[1]` as the first
   field's right edge. E-1 replaces that line by measurement (the spec's own finding is that
   `--strip-field-line`, L 163, cannot carry the comp's `#73716A`, L 113), so the strip's border
   is no longer detected and **`[1]` is now the SECOND field's right edge**. The rule is not
   wrong, it is anchored one field along. The stored captures it read were taken 04:04-04:19,
   **before this row's first edit at 05:26**, so the models/settings/usage triples are not this
   row's doing — but the shift is real and the fix belongs in `look-gate.mjs`.
5. **`comp-check`'s `bay.label-centre` is a checker defect, not a build defect, and it fails on
   all thirteen pages.** Its `comp` is **bay-deepseek's** plate
   (`(0.445 + 0.14/2 − 0.39) / 0.275 = 45.45%`) and its `got` is `m.bays.find(...)` — the
   **first** bay, which is bay-claude. The comp's bay-claude plate centre is
   `(0.17 + 0.12/2 − 0.098) / 0.28 = 47.14%`, and the build renders **47.13%**. The build is
   right to two decimals and the check is comparing two different bays. `comp-check.mjs:224`.
6. **`confirm.tsx`'s `ARM_MS` hot-reload race** was in the fence and is **not done** — see §5.
7. **The team header's height**: comp 35px (y64-98), build 56px (y61-116). Strip geometry,
   measured for whoever owns it.

## 5. Not done

- **`src/shared/controls/confirm.tsx`'s `ARM_MS` race.** Five of M1-25's fifty-two captures are
  the error page it renders, all light, and `doctor` and `mcp` have **no valid light capture at
  either viewport** because of it — so the light audit covers 11 of 13 pages, and the two it is
  missing carry the controls and the fault strips. The row is now too large; the orchestrator has
  cut this and E-7 and the rail into their own row rather than hold the picture for them.
- **E-3's stile face on the hero** is drawn (see §3) but the board's bays still lay out no border —
  correct, and left correct, because adding one moves every strip.
- **No grain, no paper texture.** The comp's bay ground has a 1px-difference std of 1.04, which is
  8-bit quantisation; the grain in a stretched crop is a 6.4x amplification of ±1 level.
- **Type, strip geometry and the palette are untouched**, as the packet requires.

## 6. Regenerating

```
node dev/web-console/capture.mjs 'http://localhost:5173/#/teams?fixture=hero' /tmp/after.png 1536 1024
node ~/.claude/skills/impeccable/scripts/comp-diff.mjs \
  --comp .impeccable/mocks/team-board-a.png --build /tmp/after.png \
  --spec .impeccable/build/spec.json --out-dir /tmp/diff
```
`m1-24-after-report.json` is that report in full; `m1-24-after.png` is the capture it was taken
from; `before-*` and `after-*` are the same regions from the last recorded hero run and from this
one. Where a number here disagrees with a capture, the capture wins.
