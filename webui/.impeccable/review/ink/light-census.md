# THE LIGHT ROOM, GOVERNED INTRINSICALLY (M1-98)

Every ink-on-ground pair the light room actually RENDERS, against the campaign's own floor of
**4.5:1**, with every pair below it named by page and class. **Nothing was fixed.**

The floor is not chosen here: M1-11 writes it verbatim - 4.5:1 for the ink tokens on the planes
the contract names, both themes - and it is FLAT, with no large-text exemption. M1-47 graded
against WCAG AA *with* that carve-out, which is the weaker bar. Both are reported below.

## What was measured

| frame | theme | light pairs | pages | distinct classes | worst pair | under 4.5 |
|---|---|---:|---:|---:|---|---:|
| 1536 | light | 2297 | 13 | 64 | 4.55:1 `myx-sfield-label` | **0** |
| 3840 | light | 2297 | 13 | 64 | 4.55:1 `myx-sfield-label` | **0** |
| 1536 (M1-47) | light (M1-47 record) | 2293 | 13 | 64 | 4.55:1 `myx-sfield-label` | **0** |

The 3840 row is this row's own run: no ink pass had used that frame, and M1-47 measured only 1536
before writing in prose that the other frame would read the same - a sentence it later withdrew.
MEASURED, the two frames agree exactly (below). What moves in the table is not the frame: the
1536 rows counted twice are the same frame measured two hours apart, and they differ by 4 pairs
because M1-80 landed in between. The third column is a control, not a third frame.

## Every light pair below the floor

**None.** The light room renders **324 distinct (page, class, ink, ground) pairs**, and not one
falls under 4.5:1. Three runs stand behind that - the 1536 frame twice (2297 and 2293 rows, two
hours apart) and the 3840 frame once (2297 rows) - so what was measured is 324 distinct
pairs 6887 times over, not 6887 pairs. The light room is legible on its own terms, which is
the question this row was cut to ask.

## The pair that governs the answer

Everything in the light room clears the floor, and the margin is not uniform. The closest pair
is `myx-sfield-label` on **teams**: **4.55:1** (`#5A5749` on `#CECDC3` via
`myx-sfield`, 12px at 1536). It clears 4.5 by **0.05**.
The same pair is the worst at 3840 as well, at the same 4.55:1 - the colours do not
change with the frame, so the closest pair in the room is the closest pair in either frame.
(Recomputed from the rounded hex rather than the probe's unrounded colours this pair reads 4.54;
either way the margin over the floor is under a twentieth of a ratio point, and that is the fact.
It is not a defect and nothing is fixed here. It is the number a future colour change has to beat.)

## The 69-of-181 gap: what this census cannot see, and what now fills part of it

**The tree this was read from: HEAD e344f94a, dirty — 1 css file(s) differ, 2 other file(s) differ.**
The sweep's own stamp, which is the denominator below: HEAD e344f94a, dirty — 1 css file(s) differ, 2 other file(s) differ.


The census is over pairs that RENDER. A rule that never renders contributes no pair, so it is
invisible here by construction - which is not the same as being safe, and the row says so. The
denominator is M1-52's, re-derived here by re-running `sweep-d7.mjs` rather than restating it:

| kind | rules |
|---|---:|
| UNRESOLVED | 69 |
| RENDERED | 66 |
| SAME-RULE | 28 |
| NOT-AN-INK-ROLE | 18 |

**THE DENOMINATOR IS NOT THE ONE THE ROW NAMES, and it is not stable within this session either.**
The row - and M1-52, and M1-80's own notes - all say *73 of 187*. Enumerated from the source
tonight, this tree carries **181** `color: var(--token)` rules across the same 38 stylesheets, of
which **69** are unresolved. So the true sentence is 69-of-181, and the drift runs in
BOTH directions: this row watched the same sweep return 189 rules / 73 unresolved and then
181 / 69 minutes later, because a live seat is editing CSS while the census runs.
That is law 24 in the small - a denominator quoted from a list rather than re-derived from the
source, staying plausible while the source moves under it.

69 rules are UNRESOLVED - they have never rendered in any capture, so no light
census can reach them. M1-80 dispositioned the gap it found, 73 rules, rather than leaving
any of them absent:

| disposition | rules | what a light census can say |
|---|---:|---|
| EXERCISED | 39 | measured against a real composited ground; **9 of them in LIGHT** |
| DEFERRED | 33 | reached by nothing yet - no light measurement exists |
| DEAD | 1 | nothing builds it - no light measurement is possible |

**AND THE TWO NUMBERS NO LONGER AGREE: 73 dispositioned, 69 unresolved today.** That is
not an error in either record, and it is the sharpest form of the point above. M1-80 closed
against a denominator of 73; the tree has moved since. This row watched the SAME sweep
return 189 rules / 73 unresolved and then 181 / 69 minutes apart in one session, while a live
seat edited CSS - so 4 of the rules M1-80 dispositioned are simply gone from the
source, or have changed kind. A disposition record is a photograph of a moving denominator, and
the only honest way to quote one is beside its date. **The reconciliation is written down once,
with the tree it was read from, in `reconcile-m1-80.md`** - it names the 4 rules that
moved, what happened to each, and which of them are landed rather than merely uncommitted.

**The 9 exercised-in-light rules: none below the floor.** The worst is
`.myx-reveal-btn:hover` at 8.91:1 (`#0A0C0B` on `#ABB0AC` via
`myx-bay`). So the honest size of the light gap is 34 rules -
33 deferred and 1 dead - and NONE of them is a measured light defect, because none of them has
ever been measured in light at all. That is a fixture gap and a deletion, not a contrast finding.

## Room-to-room divergence, reported as divergence

29 (page, class) pairs read differently by a factor of 1.25 or more between the two
rooms. **This is an expected finding and not automatically a defect** (M1-82 found the same
separator reading nearly twice as strongly in one room as the other). A pair is called a LIGHT
defect only where the light side *also* falls below the floor, which the table above settles.

Of those 29, **0 have a light side that fails the floor** - so the divergence is
interesting rather than wrong in 29 of them. The widest:

| page | class | light | dark | factor | light under floor? |
|---|---|---:|---:|---:|---|
| accounts | `myx-accounts-order` | 7.18 | 3.63 | 1.98x | no |
| settings | `myx-knob-num` | 4.68 | 6.99 | 1.49x | no |
| fleet | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |
| turns | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |
| sessions | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |
| teams | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |
| teams | `myx-board-footer-cell` | 12.83 | 9.23 | 1.39x | no |
| projects | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |
| accounts | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |
| usage | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |
| settings | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |
| models | `myx-bay-label` | 12.83 | 9.23 | 1.39x | no |

**Between the two light frames: no divergence at all.** 324 distinct (page, class, ink,
ground) pairs at 1536, 324 at 3840, and the two sets are IDENTICAL - no pair renders only at
one frame, none renders with different colours at the two, and the raw row counts agree (2297
against 2297). Measured, this frame pair is the same room twice; why, and what is
actually moving in the table above, is the next section.

### The second frame found nothing - and the control that says so rather than assuming it

A pair is identified by what it IS - page, class, ink, ground - so a class painting the same ink
on the same ground in both frames is ONE pair no matter how many times it appears, and a class
whose colour differs between frames is TWO. By that identity, and measured rather than argued:

| | distinct light pairs |
|---|---:|
| in the 1536 frame | 324 |
| in the 3840 frame | 324 |
| **renders only at 3840** | **0** |
| renders only at 1536 | 0 |
| renders in both but with different colours | 0 |


**That is the measured answer to whether light needed a second frame for ink, and it did not.**
The reason is mechanical. The type scale is a function of the viewport: `html { font-size:
var(--root-size) }` with `--root-size: max(16px, 1.0417vw)` (app.css:28, tokens.css:81), so the
root is 16px at 1536 and 40px at 3840, and the same six sizes run 12/14/16/17/20/23 against
30/35/40/42.5/50/57.5 - exactly 2.5x. The 3840 frame is the 1536 frame enlarged, so it holds the
same elements and yields the same pairs. This does not contradict M1-40's finding that coverage
moves up to 15.43 points between frames: that measures the frame, this counts the ink inside it.
But no contrast verdict was owed to the second frame, and this census will not dress a
formality up as a discovery.

**WHAT THE TABLE DOES SHOW IS THAT THE PAIR SET MOVES WITH THE TREE, NOT THE FRAME.** M1-47's
committed 1536 record and tonight's 1536 run are the SAME FRAME, same probe, same 13 addresses,
about two hours apart - and they differ. That difference is the control that makes the frame
comparison readable, and it is why the two are reported separately:

| tonight (09:34) | M1-47 (07:54) |
|---|---:|
| 324 distinct pairs | 322 distinct pairs |
| **3** render only tonight | **1** render only in the record |

- only tonight: `accounts|myx-accounts-order|#454034 on #D6D8D3 7.18:1`
- only tonight: `usage|myx-empt-text|#141414 on #F5EDDD 15.83:1`
- only tonight: `usage|myx-empt-source|#5A5749 on #F5EDDD 6.23:1`
- only in M1-47's record: `accounts|myx-accounts-order|#5A5749 on #D6D8D3 5.05:1`

Both move because M1-80 landed between the two runs. Neither is a frame effect and neither is a
defect; they are the tree changing under a fixed measurement, and they are exactly the drift that
would be misread as a frame finding if only the two FRAMES had been compared.

## What this census does NOT cover, stated rather than implied

- **The edge floor.** The contract asks 3:1 for each `--edge-*` on `--room` and `--strip`. Edges
  are marks, not text; this probe walks text nodes. Not measured here, and not claimed.
- **Plane separation.** "Every adjacent plane pair at or above 1.6:1" is its own floor on its own
  axis (M2-11). This census is ink on ground and says nothing about two grounds.
- **Hover, focus and armed states.** Every number is the resting state.
- **The ghosted hand-off strip.** Ruled decorative, with its own wall (a text node inside the
  ghost must have a sibling outside it that clears AA) - which is a different assertion from this
  one and is evaluated on the live page, not here.
- **A pair the probe could not parse refuses the page** rather than skipping the ground, so a
  page that contributed pairs here contributed all of them.

## Regenerating this

```
# the two light frames (M1-98 added the frame arguments; no arguments = the M1-47 run)
node webui/.impeccable/review/ink/probe-ink.mjs --theme light --width 3840 --height 2160 \
  --tag 3840x2160 --settle 7000 --out webui/.impeccable/review/ink/light-census-3840.json \
  --shots webui/.impeccable/review/ink/census-3840
node webui/.impeccable/review/ink/probe-ink.mjs --theme light \
  --out webui/.impeccable/review/ink/light-census-1536.json \
  --shots webui/.impeccable/review/ink/census-1536
bun webui/.impeccable/review/ink/sweep-d7.mjs /tmp/d7-m198.json
node webui/.impeccable/review/ink/census-light.mjs /tmp/d7-m198.json
```
