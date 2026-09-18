# m1 PUNCH LIST — measured, ordered by distance from the comp, no fixes

Generated 2026-09-18 by .dev/web-console/census/build-punch-list.mjs from three artefacts this row produced in one run:

- `comp-check.txt` — the comp-constant table, 13 addresses, `.dev/web-console/comp-check.mjs`
- `tonal-census.txt` — the per-frame flat/mid-tone census, comp measured by the same code (`census/tonal-census.mjs`)
- `comp-diff-teams.txt` — impeccable's region diff, comp against the fresh teams capture

ORDER: by how far the build is from the comp, never by how easy the fix looks. Distance is
`|delta| / |comp value|` (a relative miss), or `|delta|` in points/pixels where the comp value is 0.

OWNERSHIP is derived from the ledger at generation time: each constant names the file that
produces it, and that file is matched against every in-flight row's fence.

**NO FIXES ARE IN THIS TABLE.** Four seats are live in `webui/src`; every row below belongs to one of them or to a row cut from this table.

## 1. comp-check — the constants, worst first

| # | constant | address | measured | comp | delta | how far | owner |
|---|---|---|---|---|---|---|---|
| 1 | `rule.window.x` | all 13 | 59.77% | 41% | +18.77% | 45.8% | webui/src/widgets/rule/rule.css — NO LIVE ROW |
| 2 | `strip.h` | 6: turns sessions projects usage models doctor | 4.1% | 6.5% | -2.40% | 36.9% | M1-24 @design-builder (webui/src/shared/ui/ui.css) |
| 3 | `rule.none.x` | all 13 | 90.68% | 72% | +18.68% | 25.9% | webui/src/widgets/rule/rule.css — NO LIVE ROW |
| 4 | `text.window.cap` | all 13 | 14px | 11.7px | +2.30px | 19.7% | webui/src/widgets/rule/rule.css — NO LIVE ROW |
| 5 | `text.bay.label.cap` | all 13 | 9px | 10.9px | -1.90px | 17.4% | M1-24 @design-builder (webui/src/shared/ui/ui.css) |
| 6 | `bay.label-centre` | all 13 | 37.71% | 45.45% | -7.74% | 17.0% | M1-24 @design-builder (webui/src/shared/ui/ui.css) |
| 7 | `text.clocks.cap` | all 13 | 14px | 12px | +2.00px | 16.7% | webui/src/widgets/rule/rule.css — NO LIVE ROW |
| 8 | `text.none.cap` | all 13 | 14px | 12px | +2.00px | 16.7% | webui/src/widgets/rule/rule.css — NO LIVE ROW |
| 9 | `text.health.cap` | all 13 | 11px | 12.4px | -1.40px | 11.3% | webui/src/widgets/rule/rule.css — NO LIVE ROW |
| 10 | `text.wordmark.cap` | all 13 | 17px | 15.9px | +1.10px | 6.9% | webui/src/widgets/rule/rule.css — NO LIVE ROW |

**7 of 10 ranked constants have NO LIVE OWNER** (`rule.window.x`, `rule.none.x`, `text.window.cap`, `text.clocks.cap`, `text.none.cap`, `text.health.cap`, `text.wordmark.cap`). Their files are `webui/src/widgets/rule/rule.css`, and no in-flight row fences those paths as this list was generated — so the worst rows by distance are the ones nobody is currently able to fix.

Full table: `comp-check.txt` (133 rows outside tolerance across the 13 addresses: 123 with a comp value to rank, 10 rule-based).

**Rule-based failures — the comp carries the shape, not a number, so there is no delta to rank by:**

| constant | addresses | measured | what the comp shows | owner |
|---|---|---|---|---|
| `field.label-rule` | 7: turns sessions projects usage settings models doctor | 0.00% | the rule under the label divider (CSS cites the comp crop at row y=229) | M1-24 @design-builder (webui/src/shared/ui/ui.css) |
| `strip.inset-x` | 1: settings | n/a | a strip inside every bay to measure against | M1-24 @design-builder (webui/src/shared/ui/ui.css) |
| `strip.h` | 1: settings | n/a | a strip in the rack (the address renders none) | M1-24 @design-builder (webui/src/shared/ui/ui.css) |
| `field.divider` | 1: settings | n/a | a vertical divider between field boxes | M1-24 @design-builder (webui/src/shared/ui/ui.css) |

**CAVEAT THAT TRAVELS WITH EVERY ROW ABOVE.** `comp-check.mjs` carries its own fixture table and it is the STALE one: `turns`, `sessions`, `projects`, `logs`, `accounts` and `doctor` were measured against LIVE daemon data rather than their fixtures, because that table still names `demo` (see section 4). The chrome constants (rail, rule, the text roles) are data-independent and stand; the rack constants for those six addresses do not.

## 2. comp-diff — per-region detail, the floor is 65

comp `team-board-a.png` vs `review/census/teams-dark-1536x1024.png`: overall **85%** (match), structure 87%, color 74%, detail 85%.

**The aggregate is not a gate.** 85% is what this tool calls a match, and the campaign never gates on it: it dilutes the defect column away. The floor is per region.

| region | verdict | overall | structure | color | detail | vs floor 65 |
|---|---|---|---|---|---|---|
| band-1 | match | 86% | 91% | 79% | **83%** | above by 18 |
| band-2 | match | 82% | 92% | 72% | **80%** | above by 15 |
| band-3 | match | 81% | 92% | 70% | **84%** | above by 19 |
| band-4 | drift | 77% | 89% | 67% | **75%** | above by 10 |
| band-5 | contradicted | 58% | 64% | 68% | **51%** | **BELOW by 14** |
| band-6 | drift | 66% | 85% | 66% | **44%** | **BELOW by 21** |
| band-7 | drift | 69% | 92% | 69% | **40%** | **BELOW by 25** |
| band-8 | drift | 70% | 87% | 79% | **51%** | **BELOW by 14** |

**4 of 8 regions are below the floor** (band-5 51%, band-6 44%, band-7 40%, band-8 51%). The shape is the one the review described: structure holds high across the same regions (91, 92, 92, 89, 64, 85, 92, 87) while detail collapses down the page — composition present, material absent.

**PROVENANCE OF THE 65 FLOOR, corrected 2026-09-18:** it is NOT `build-phase.mjs:598`'s number. That 0.65 is a threshold on OVERALL scoped to control regions already at verdict drift, and the file carries no per-region detail floor at any value. 65 is CHOSEN by the orchestrator from this single teams measurement, because the split has margin on both sides (83/80/84/75 pass, nearest clears by 10; 51/44/40/51 fail, nearest misses by 14), and it is PROVISIONAL until `comp-spec.mjs --regions` makes these rows semantic instead of horizontal slices.

**CAVEAT, and it is the reviewer's:** comp and capture show different data, and comp-diff's auto-bands are horizontal slices of the frame rather than semantic regions. The SHAPE of the result is not something a content difference produces, and the region crops confirm it, but the caveat travels with every number.

**WHY THE FLOOR EXISTS AT ALL:** `build-phase.mjs`'s hero gate is `overall >= 0.72` with no region missing, and comp-diff decides `missing` for a non-painted region with a CONJUNCTION (`detail < 0.35 AND structure < 0.6`, comp-diff.mjs:147/:155). band-7 measures detail 40 and structure 92, so it is not missing — it scores drift. The hero gate therefore passes on both clauses with the material gone: high structure launders absent detail. The detail floor is the veto neither clause performs.

## 3. The tonal census — how much of each frame is two flat values

Binning rule, applied to every frame including the comp's, in one run: a pixel is FLAT when the ground it is NEAREST to is within 8 of 255 of it on every channel; the grounds are the frame's room and strip — the build's from `tokens.css`, the comp's from `build/spec.json`'s own palette. Mid-tone share is the rest.

- comp `team-board-a.png`: **35.5%** mid-tone (grounds #0c0e0e / #ddd8c6)
- build, the frame furthest from the comp per address:

| address | frame | mid-tone | delta vs comp |
|---|---|---|---|
| logs | logs-light-1536x1024.png | 83.6% | +48.1 points |
| turns | turns-light-1536x1024.png | 83.3% | +47.8 points |
| mcp | mcp-light-1536x1024.png | 73.9% | +38.4 points |
| sessions | sessions-light-1280x800.png | 71.6% | +36.1 points |
| settings | settings-light-1536x1024.png | 70.1% | +34.6 points |
| usage | usage-light-1280x800.png | 64.8% | +29.3 points |
| doctor | doctor-light-1536x1024.png | 8.9% | -26.6 points |
| teams | teams-light-1280x800.png | 59.9% | +24.4 points |
| accounts | accounts-light-1536x1024.png | 58.7% | +23.2 points |
| compaction | compaction-light-1536x1024.png | 57.2% | +21.7 points |
| fleet | fleet-light-1280x800.png | 56.8% | +21.3 points |
| models | models-light-1536x1024.png | 56.1% | +20.6 points |
| projects | projects-dark-1536x1024.png | 15.2% | -20.3 points |

**INSTRUMENT DISAGREEMENT, reported rather than resolved** (the row asks for both): this per-pixel rule reads the comp at 35.5% where the design review's hand census says **11.9%**. The review's number is a clustered palette share — `spec.json`'s own coverage of its top two entries, which reproduces exactly as `1 - 0.6407 - 0.24 = 11.93` — and this file's is a per-pixel distance at a stated tolerance. They are different measurements; neither is the other's check. The gap is the comp's GRAIN: its dark ground spreads over more than 8 steps, so a tight per-pixel rule under-counts it, while a loose one cannot be used at all because the light room's two grounds are only 22 steps apart and their discs overlap (at tolerance 32 in the light theme the flat share exceeds 100%).

**What the census does say, under its own rule and comparable across frames:** the light frames sit far above the comp (up to +28 points) and the dark frames straddle it. The light excess is the light room having a THIRD large flat plane — the bay floor M1-11 deepened to #ABB0AC — which a two-ground metric scores as mid-tone. That is a finding about the metric, not about the light room, and it is why the census is not a gate yet.

## 4. The fixture defect this row found, and the two it corrected

`gate.mjs` named the wrong fixture for four addresses and would have made every capture in this row a lie:

| address | was | is (off disk) | why the wrong name is silent |
|---|---|---|---|
| turns | `demo` | `board` | `turns/index.tsx:337` interpolates the name into a dynamic import and `.catch(() => undefined)` swallows the failure |
| sessions | `demo` | `board` | `sessions/index.tsx:265`, same shape |
| projects | `demo` | `list` | `projects/index.tsx:240`, same shape |
| logs | `demo` | `tail` | `logs/index.tsx:154`, same shape |
| accounts | `demo` | `accounts` | the module is imported statically, but `accounts/fixtures/accounts.ts:100` re-checks the name and returns null for anything else |
| doctor | `demo` | `doctor` | `doctor/fixtures/doctor.ts:46`, same shape |

The last two were found by the ASSERTION rather than by reading: the module URL answered 200 for both (the file exists) while the page rendered live data, which is why the gate now checks the DOM as well as the module. `gate.mjs` now asserts per capture — the fixture module URL must answer 200, and where the page prints its `sample data` label the label must be on screen (or, once M1-20 lands its marker, `data-fixture` must equal the file name). A capture failing either is recorded FAILED, marked on the contact sheet, and the gate exits 1.

## 5. What is NOT in this table

- Any fix. Four seats are live in `webui/src` and every row above names its owner.
- Any gate on an aggregate score. See section 2.
- Any claim that the census is calibrated. See section 3: it is not, and it says so.

