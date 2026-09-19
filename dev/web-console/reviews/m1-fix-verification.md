# M1-33 — the night's three fixes, attacked (row M1-33)

Auditor: `code-reviewer` (opus), 2026-09-18. Read-only. **No code was changed and no verify line was
edited.** The only path this row wrote is `dev/web-console/reviews/`.

Tree: worktree tip `53fe249a` (it moved from `82e37081` while this ran; both are after all three
fixes). Attacks on the leak wall ran in a `git clone -s` copy outside the worktree with
`node_modules` symlinked in; every planted file was removed afterwards. Browser measurements ran
through the campaign's own `lib/cdp.mjs` against the dev server already on 5173, which was left
running.

| fix | verdict |
|---|---|
| ONE · the viewport scalar (M1-26) | **HOLDS on the axis it was built on; three failures off it**, all reproduced |
| TWO · the fixture leak wall (M1-20) | **HOLDS against the three holes it was rebuilt for. Two new ones, both reproduced** |
| THREE · the exit gate runner | **HOLDS** — 7/7, the design is right |
| THREE · the exit gate's coverage | **two gaps of the "nothing parsed CSS" class**, one reproduced |

---

# ONE — THE VIEWPORT SCALAR

## What holds, and the proof it is not vacuous

The claim is that the console at any viewport is the 1536 render scaled. Where an object is sized
off the frame's **width** — the axis `--root-size: max(16px, 1.0417vw)` is written on — it is exactly
true, and the constant is the proof:

```
frame        root    rail.w   tab.fs   rail.w / tab.fs
1536x1024   16.00    138.23   16.00         8.639
1920x1080   20.00    172.80   20.00         8.640
2560x1440   26.67    230.39   26.67         8.639
3840x2160   40.00    345.59   40.00         8.639
3840x2560   40.00    345.59   40.00         8.639     <- aspect changed, ratio did not
```

The rail holds its type to four figures across a 2.5x range of frame AND across an aspect change.
That is a real result and it is what the row set out to do.

**The known counter-example is genuinely fixed.** The hand-off lift, whose geometry was in the
type's units and which ran off the left edge at 3840, now scales: `x = -245.6` at 1536 and `-617.1`
at 3840, a ratio of **2.513** against a frame ratio of 2.500. It is off-frame at every size
including the comp's, in the same proportion — which is what a deliberately offset object looks
like, not a broken one.

## FAILURE A — the rule band is bound to the wrong axis. REPRODUCED.

`webui/src/app/app.css:66` sizes the top rule off the frame's **height**:

```css
  --rule-h: 5.8vh;
  --rule-min: 56px;
```

while everything inside it is sized off `--root-size`, which is `1.0417vw` — the frame's **width**
(`webui/src/shared/tokens.css:81`). The two agree only while the aspect ratio is the comp's. Change
the aspect and the band and its type move apart:

```
frame          root   rule.h  cell.fs   rule.h/cell.fs   cell overflows its band by
1536x1024     16.00    59.39     23.0        2.582                +1px     <- the comp frame
1536x 864     16.00    56.00     23.0        2.435                +2px
3840x2160     40.00   125.27     57.5        2.179                +8px     <- the operator's monitor
3840x2560     40.00   148.47     57.5        2.582                +2px     <- same width, comp aspect
2560x1440     26.67    83.52     38.3        2.179                +6px
3840x 900     40.00    56.00     57.5        0.970               +29px
```

Read the last two columns together. **3840x2560 returns exactly 2.582 — the comp frame's number to
three decimals — at the same width as the row that reads 2.179.** Only the height changed, so this
is the aspect ratio and nothing else: it is not a size effect, not a rounding artifact, and not the
floor. At the operator's own 3840x2160 the rule's type has **15.6% less vertical room** than the
comp gives it, and the cell is cut by 8px.

The last row is the one to look at twice. A wide, short window on a 4K panel — half-height, a
common way to put a console beside an editor — puts **57.5px of type in a 56px band**: the ratio
falls below 1.0, `--rule-min: 56px` (a px floor that does not scale) is doing all the work, and the
cell overflows by 29px. The rule is the console's chrome on every page.

The source names this rule itself, at `tokens.css:78`: *"an object follows the TYPE when it is made
of type, and the FRAME when it is placed on the frame."* The rule band is made of type and placed on
the frame, and it was given the frame's other axis. Seven more containers are the same shape —
`app.css:120` `max-height: 85vh`, `log-tail.css:57` `66vh`, `turns.css:47` `52vh`,
`palette.css:26` `60vh`, `file-view.css:22` `40vh`, `waterfall.css:77` `40vh`,
`conversation.css:27` `34vh` — each a height off `vh` holding type off `vw`. I measured the modal
(`.myx-modal`, 7.99 lines at 1280x800 and 7.81 at 3840x2160) and it is content-sized in the state I
could reach, so its cap does not bind there; **the other six I did not get a page into, and I am not
claiming them — they are READ, and they are the same shape.**

## FAILURE B — below the comp frame the rail is a px floor under a pinned type. REPRODUCED.

The floor is deliberate and argued in `tokens.css:74`: at or below 1536 the type stops shrinking
while the vw layout keeps going. The consequence that was not measured is that the rail's own floor
is written in px, so the constant from the top of this section comes apart:

```
frame        root   rail.w   tab.fs   rail.w/tab.fs
1536x1024    16.0    138.2     16.0        8.639
1366x 768    16.0    122.9     16.0        7.680
1280x 800    16.0    118.0     16.0        7.375   <- --rail-min: 118px is now the whole value
1024x 768    16.0    118.0     16.0        7.375
 768x 600    16.0    118.0     16.0        7.375
```

From 1311px down, `--rail-w: 9vw` is under the floor and the rail is a constant 118px holding type
that is a constant 16px — **14.6% narrower, relative to its own type, than the design gives it**.
And at 720px and below `app.css:195` makes it worse rather than better: `--rail-min` drops to `96px`
while `--rail-w` rises to `14vw`, so a narrowing window makes the rail *narrower still* at the same
type size. Measured at 1280: the active rail tab's label already overflows its box by 21px at the
comp frame and the box stops shrinking here, so the overflow is carried, not resolved.

## FAILURE C — what the scalar cost the narrow band. REPRODUCED, with a counterfactual.

At 1280x800 on the teams hero, I measured the tree as shipped and then set only
`--root-size` to the pre-M1-26 `14px` in the live document — one property, nothing else touched:

```
                                   elements clipping   total clipped   worst single   off-frame
as shipped (root 16px)                   141              3,323px          231px          65
root forced to the pre-M1-26 14px        138              1,430px           61px          64
                                                          ------           -----
                                                           2.3x             3.8x
```

The scalar is 14% more type in the same layout, and it turns into **2.3x the clipped width and 3.8x
the worst single cut**. Sixty-five elements sit outside the frame at 1280 against twenty-one at the
comp frame, and the extra forty-four are strip fields running off the right edge (e.g. `.myx-sfield`
at `x=1239, w=252`, right edge 1491 in a 1280 frame).

The clipping is not all scrollable. `.myx-board` computes `overflow-x: hidden` and is 211px short of
its content at 1280, while its child `.myx-strip-fields` is 231px over with `overflow-x: visible` —
so the strip paints outside its box and the board cuts it. That content is **lost, not scrolled**.
The designed remedy named in `fleet.css:36` (*"the bays scroll now, `.myx-bay-rows { overflow-x:
auto }`"*) is on a different element and does not cover the board widget.

And the one breakpoint every page carries does not address it. Worst single clip by frame:
**1536 → 21px · 1366 → 152px · 1280 → 231px · 1152 → 355px · 1120 → 387px · 1024 → 480px · 768 →
729px.** 1120 is worse than 1152: dropping the detail column at `@media (max-width: 1120px)` does
not touch the object that is actually breaking. That breakpoint's derivation
(`fleet.css:32`, *"a nine-field strip needs about 1206px of value width"*) is a pre-M1-26
measurement, and it is the only breakpoint in the tree between 720 and 1536.

---

# TWO — THE FIXTURE LEAK WALL

## The control first: it can fail

Before attacking it I proved it is not vacuous. In the clone, `webui/src/pages/models/index.tsx:120`
went from the composed specifier back to a literal one:

```js
-    void import(/* @vite-ignore */ `./fixtures/${name}.ts`)
+    void import('./fixtures/models')
```

```
LEAK webui/src/pages/models/fixtures/models.ts: "frontier reasoning, the pinned row" ships …
LEAK webui/src/pages/models/fixtures/models.ts: "fast builder for the cheap tier" ships …
  … 5 literals for models, exit 1
```

The three holes it was rebuilt for are closed. The corpus no longer contains fixtures, attribution
is single-owner, the character scanner reads seventeen strings where the regex read two, and BLIND
is a named failure that exits non-zero. **On its own terms the wall HOLDS.**

## HOLE 1 — the denominator is a path spelling. REPRODUCED, three ways.

```js
const fixtures = readdirSync(pagesDir).flatMap((page) => {
  const dir = join(pagesDir, page, 'fixtures');
  if (!existsSync(dir)) return [];
  return readdirSync(dir).filter((name) => name.endsWith('.ts')).map(...);
});
```
— `dev/web-console/fixture-leak.mjs:134-142`

A fixture is exactly `webui/src/pages/<page>/fixtures/<name>.ts`. Off that spelling it is invisible
in **both** directions: not enumerated as a fixture, and excluded from the evidence corpus anyway by
`isFixture = (path) => path.includes('/fixtures/')` at line 128 — so it cannot even veto a sibling's
evidence. Three plants, each statically imported and rendered so the bundler could not shake it:

| plant | shipped in dist | wall said |
|---|---|---|
| `pages/usage/fixtures/panel.**tsx**` | 3 of 3 strings | `11 fixture(s), 104 literal(s) checked, 0 in dist` · **exit 0** |
| `pages/usage/fixtures/**heavy/**panel.ts` | 2 of 2 strings | identical line · **exit 0** |
| `**widgets/rail**/fixtures/sample.ts` | 1 of 1 string | identical line · **exit 0** |

```
$ grep -c "unreleased model codename halcyon-2" webui/dist/index.html
1
$ node dev/web-console/fixture-leak.mjs; echo $?
fixture-leak: 11 fixture(s), 104 literal(s) checked (31 set aside as shared between fixtures), 0 in dist
0
```

The fixture **count did not move** — still 11, still 104 literals. Nothing in the output says a
twelfth fixture exists, so there is no number a reader could notice. `readdirSync` is not recursive
and `.tsx` is not `.ts`; either is one keystroke, and a widget owning its own sample is ordinary FSD.
The wall's own BLIND mechanism cannot help here, because BLIND is a verdict about a fixture the wall
found.

## HOLE 2 — the evidence is single quotes; what ships is decided per export. REPRODUCED.

Two independent facts meet. The scanner keeps only **single-quoted** literals of 10+ characters
(`literalsOf`, lines 62-105) — by design, so the corpus comparison is sound. And rolldown shakes
**per export**, so one file can half-ship. Put a pasted API response (double quotes, as JSON comes)
next to one hand-written caption (single quotes, as this repo writes):

```ts
// webui/src/pages/usage/fixtures/panel.ts — in the right directory, with the right extension
export const panelRows = {
  "operator": "marcos at torad, the paying account",
  "burn": "internal burn rate 41200 usd per month",
};
export const panelCaption = 'sample economics for the capture';
```

with only `panelRows` statically imported:

```
webui/src/pages/usage/fixtures/panel.ts: checked 1 literal(s) this fixture alone owns — 0 in dist
fixture-leak: 12 fixture(s), 105 literal(s) checked (31 set aside as shared), 0 in dist
exit 0
$ grep -c "internal burn rate 41200 usd per month" webui/dist/index.html
1
```

It is counted, named, and reports a clean check while both values ship. **The boundary is exact and
worth stating, because the wall gets the neighbouring cases right:**

- the same file with **no** single-quoted literal → `BLIND — 0 literal(s) are this fixture's own`,
  **exit 1**. Correct, and loud.
- the same split written entirely in this repo's single-quote style → **exit 1**, both literals
  named. Tree-shaking alone does not defeat it.

So the hole is precisely the **mixed** shape, and one hand-written caption is the whole of it: it
lifts a fixture out of BLIND into "checked", and it is the one string that does not ship because it
sits in the export nothing imported. Nothing lints quote style here — `webui/eslint.config.mjs`
carries no `quotes` rule — and a pasted response is how sample payloads are normally made.

## An accuracy note, not a hole

`dist.includes(literal)` is a substring test, so a leak in one fixture raises `LEAK` against another
whose shorter literal it contains. In the control above, models leaking
`'frontier reasoning, the pinned row'` produced three `LEAK webui/src/pages/usage/fixtures/usage.ts`
lines for `'frontier reasoning'`, `'fast builder'` and `'cheap builder'` — usage had shipped nothing.
This over-reports and cannot hide a leak, so it costs a debugging session rather than an artifact.

---

# THREE — THE EXIT GATE

## The runner HOLDS

`node dev/web-console/exit-gate.mjs --selftest` → **7/7 PASS**, including the two that matter most:
*a tool that exits 0 while its output reports failure is FAILED*, and *an empty leg set is not a
pass*. The `probe` / `probeProof` / `proof` / `failIf` / `after` shape is the right decomposition,
and M1-23's corrections are visibly the product of reading the tools rather than trusting them —
the `look` probe was tightened off `/./` (which the usage text matched, so it could not fail), and
the `suite` proof accepts `Tests N failed` so a failing suite reads FAILED rather than DID NOT RUN.
I found nothing wrong with the runner. The question the row asks is coverage, so:

## THE DENOMINATOR: what nine legs observe

Enumerated from `LEGS` in the file, not from memory.

| leg | what it can see |
|---|---|
| typecheck | types, tree-wide |
| lint | FSD boundaries, `src` and `tests` |
| suite | `tests/**` — the world, label, coverage and contrast walls |
| build | `npm run build -w webui`, plus a freshness check on `dist/index.html` |
| bundle | **CSS, parsed** — the only real CSS parser in the repo |
| fixture-leak | fixture literals in dist, and the marker declaration |
| scan | the ast-grep walls, through the wrapper that refuses an unread path |
| comp-check | the comp's constants on live pages |
| look | the rendered rule pass and the look gate, one URL |

Two rendering legs. Both of them run at one frame, in one room, and `look` on one page.

## GAP 1 — **nothing renders at a second frame.** This is the next "nothing parsed CSS".

```js
const FRAMES = [[1536, 1024]];                                  // comp-check.mjs:57
const width  = Number(flag('width', '1536'));                   // look.mjs:161
const height = Number(flag('height', '1024'));                  // look.mjs:162
run: () => sh('node', ['dev/web-console/look.mjs', LOOK_URL], ROOT),   // exit-gate.mjs:170 — no size
```

`comp-check`'s frame list is a one-element literal. `look` defaults to 1536x1024 and the gate passes
no size flag. **Every leg in the exit gate observes the console at exactly 1536x1024 — the frame the
operator does not use.**

So the whole of section ONE is invisible to this gate. M1-26 exists because of what the console
looks like at 3840x2160; the rule band losing 15.6% of its type's room at that frame, the cell being
cut by 8px there and 29px at 3840x900, the rail floor, the 2.3x clipping at 1280 — all nine legs
pass on every one of them, because none of them ever changes the window. `scale.mjs` is the
instrument that would see it and it is **not a leg**; neither are `capture.mjs`, `snapshot.mjs`, or
`gate.mjs`, whose contact sheets are what the design review reads.

This is the same shape as the CSS gap, one level up: the campaign built the instrument, proved it,
and left it out of the gate. One leg — `look.mjs --width 3840 --height 2160`, or `FRAMES` gaining a
second pair — closes it.

## GAP 2 — the bundle leg PARSES CSS. Nothing EVALUATES it. REPRODUCED.

Law 25 and the `bundle` leg fixed the class where a stylesheet does not parse. The next class is a
declaration that parses perfectly and is thrown away by the browser at computed-value time. Four in
the tree, enumerated from the source — every `var()` in the colour slot of a `border`/`outline`
shorthand whose property is length-valued or undefined, across 39 sheets, 183 defined properties and
1,429 `var()` references:

```
webui/src/features/head-edit/head-edit.css:9   border-bottom: 1px solid var(--hair);          --hair is the LENGTH 1px
webui/src/pages/settings/settings.css:66       border-bottom: 1px solid var(--hair);          --hair is the LENGTH 1px
webui/src/widgets/knob-form/knob-form.css:15   border-bottom: 1px solid var(--hair);          --hair is the LENGTH 1px
webui/src/widgets/toml-editor/toml-editor.css:10  border: 1px solid var(--hair-strong);       --hair-strong is NEVER DEFINED
```

`tokens.css:54` says it in as many words — *"a hairline is a width here, and a color in the two
theme blocks below"* — and gives the correct idiom, `border-top: var(--hair) solid var(--hairline)`,
which 22 other declarations use. These four put the width token in the colour slot.

Measured in the browser on the settings page, at 1536x1024:

```
.myx-settings .myx-bay-rows .myx-fbox   border-bottom: 0px none rgb(201,195,180)
                                        border-top:    1px solid rgb(168,163,146)     <- written correctly
CONTROL  .myx-rule                      border-bottom: 1px solid rgba(236,234,226,0.1)
```

The stylesheet asks for a bottom hairline on every field box in the settings rack and the browser
computes `0px none`: the whole property is dropped, the way an invalid-at-computed-value-time
declaration is. The control on the same page proves the instrument can tell the difference. The
settings one is **REPRODUCED**; the other three are **READ** — their widgets were not mounted on the
pages I could reach, and I am not claiming a render I did not take.

`bundle` cannot see any of this: lightningcss parses and minifies them happily, because they are
valid CSS. A computed-style assertion is what reads them, and the gate already drives a browser
twice.

## GAP 3 — one page and one room

`look` runs `LOOK_URL = 'http://localhost:5173/#/teams?fixture=hero'` — one of thirteen addresses.
And neither rendering leg seeds a theme: `look.mjs:232` and `comp-check.mjs:380` pass only
`{ 'myx-mgmt-key': ... }` to `withChrome`, while `gate.mjs:177` is the only caller that sets
`splice.theme` — and `gate.mjs` is not a leg. **No leg in the exit gate observes the light room**,
which is the subject of M1-11 and M1-25. `comp-check` does cover all thirteen addresses, so the page
gap is `look`'s alone.

## GAP 4 — no leg asserts the fixture actually loaded

`data-sample` appears **0 times** in `look.mjs`, `comp-check.mjs` and `exit-gate.mjs`.
`fixture-leak` checks that every fixture page *declares* the marker in source and that the name is
absent from dist — both static. M1-32 put the runtime claim in `capture.mjs` (*"a page that never
loaded its fixture cannot carry the marker"*), and `capture.mjs` is not a leg. So the gate's own
`look` leg navigates to `?fixture=hero`, and if that fixture silently failed to resolve the page
would render **live data**, `look` would measure it, and all nine legs would pass — which is the
exact failure M1-32 exists to prevent, one layer up from where the fix was made.

## GAP 5 — nothing reads the ledger

Law 25 (*a row that touches CSS runs a build leg*) and law 27 (*a verify leg asserts what must be
present, never what must be absent*) are properties of verify strings. No leg opens
`dev/campaigns/web-console.toml`. Both are mechanically checkable — a row whose `files` match
`*.css` and whose `verify` has no build leg; a `verify` containing `|| true` or the
`if TOOL | grep -q X; then exit 1; fi` shape — and M1-27 found both classes by hand. The gate is the
declared remedy for unsupported rows; it does not yet check the rule that made them unsupported.

---

# Summary

**ONE HOLDS on the width axis and is genuinely proven there** — a four-figure constant across a 2.5x
range and across an aspect change, with the known counter-example fixed. It fails on the height
axis (the rule band, aspect-isolated, worst at the operator's own frame), at the px floors below the
comp frame, and in the narrow band, where it multiplied clipped width by 2.3 and the worst single
cut by 3.8.

**TWO HOLDS against everything it was rebuilt for**, and the control proves it fails loudly on a real
leak. Two new holes, both reproduced: a denominator that is a path spelling, and evidence that is
single-quoted while shipping is decided per export — the second reporting a clean per-fixture check
while the bytes ship.

**THREE's runner HOLDS at 7/7 and I could not break it.** Its coverage has the same shape of gap the
CSS one had: nothing renders at a second frame, so the row that landed tonight to fix what the
operator sees on a 4K monitor is observed by no leg at any frame but the one it was designed away
from; and nothing evaluates CSS, so four declarations that parse cleanly and are dropped by the
browser pass every leg — one of them measured on the settings page.

## Every attack run, including the ones that found nothing

| # | attack | result |
|---|---|---|
| 1 | every element's geometry vs the 1536 render scaled, 9 frames, 629 elements | rule band and floors deviate; rail exact |
| 2 | aspect isolated from size (same width, 3 heights) | **confirmed aspect, not size** |
| 3 | the hand-off lift, the known counter-example | **fixed** — scales 2.513 vs 2.500 |
| 4 | wide-and-short frames (3840x900, 2560x720) | rule band ratio below 1.0 |
| 5 | counterfactual: root forced to the pre-M1-26 14px at 1280 | 2.3x clipping attributable |
| 6 | 1120px breakpoint — does it help? | no: 1120 is worse than 1152 |
| 7 | leak wall control: literal specifier | **fails correctly**, exit 1 |
| 8 | `.tsx` fixture | **ships, exit 0** |
| 9 | fixture one directory deeper | **ships, exit 0** |
| 10 | widget-owned fixture | **ships, exit 0** |
| 11 | two exports, single quotes throughout (tree-shaking alone) | **caught**, exit 1 |
| 12 | all double quotes | **caught** as BLIND, exit 1 |
| 13 | mixed: pasted JSON + one caption | **ships, exit 0** |
| 14 | exit-gate `--selftest` | 7/7, nothing found |
| 15 | every `var()` in a colour slot, 39 sheets / 1,429 references | 4 invalid-at-computed-value-time |
| 16 | computed border on the settings rack, with a control | **dropped**, control renders |
| 17 | frames, themes and pages the gate's rendering legs cover | 1 frame, 1 room, 1 page |
| 18 | `data-sample` in any leg | 0 occurrences |

Scripts in `~/.cache/qgre/scratch/claude-1000/m1-33/`: `scaled.mjs`, `aspect.mjs`, `narrow.mjs`,
`settle.mjs`, `counter.mjs`, `borders.mjs`, `analyze.py`. The leak clone is `leak/`; its working
tree holds only the restored files and the dist the last build wrote.
