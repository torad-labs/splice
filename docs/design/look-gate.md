# A look gate: making design failures mechanical

Written 2026-09-18 by the design-review seat, at the operator's request: *"can we make these
kind of failures a gate to be passed, so the agents have at least a minimum quality control
requirements to pass, as a script they can call, before calling a work done?"*

Short answer: **yes for most of them, no for the last one, and this repo already owns more of
the gate than it is running.**

---

## 1. What the field actually does (researched 2026-09-18, sources at the end)

The practitioner consensus is a four-layer stack, and the layers are not substitutes.

| Layer | What it is | What it catches | Cost |
|---|---|---|---|
| **1. Lint + token validation** | Deterministic checks on source and on computed style: contrast, token bypasses, type-scale escapes, spacing off-grid, axe-core | Everything that should never reach a human eye | Seconds, no flakiness |
| **2. Diff against a baseline** | Render, screenshot, compare. Playwright + Pixelmatch, or Chromatic / Percy | *Unintended* change | Minutes; needs baseline hygiene |
| **3. Rubric judge** | A written rubric scored by a model before a human sees the candidate | Candidates not worth a human's time | Cheap, needs a versioned rubric |
| **4. A named human** | One person with taste, ten minutes, two questions | Everything the spec did not think of | Ten minutes |

Three findings from the research matter more than the tool names:

- **The single highest-value check needs no screenshots.** The resolved-value diff — load the
  built artifact in a headless browser, read every custom property off the root, diff against
  the base branch — "takes seconds, has no flakiness, and turns *somebody changed a colour*
  from a discovery into a line in the pull request description." Teams that add only this one
  check catch enough that the screenshot suite can be scoped down to a handful of surfaces.
- **The blocking set must be small: three to five checks.** Everything else warns. A gate that
  blocks on everything becomes noise, and a noisy gate gets bypassed, which is worse than no
  gate. Tune the tolerance *downward until false positives stop*, never upward until failures
  stop.
- **Layer 4 cannot be automated and pretending otherwise is the failure mode.** The strongest
  piece in the research is blunt about it: *"We build elaborate rubrics and we pretend the
  rubrics replace the judgment. They don't. They just slow the judgment down."* The fix is to
  name the gate and staff it. The two questions that team uses, ten minutes per feature:
  **(1) Is this clearly the work of one team?** **(2) Is this the version we'd rather other
  people copy?**

Two rubrics worth stealing for layer 3, both from design engineers whose work this project
already references: Rauno Freiberg's six words — **fast, beautiful, consistent, careful,
timeless, soulful**, where *"a product is only as good as the weakest of the six"* — and Emil
Kowalski's animation checklist, which is already installed on this machine as the
`emil-design-eng` skill and ships a table of exact before/after rules.

---

## 2. The finding that matters most here

**This repo already has layer 1 and half of layer 2, and has been pointing them at the wrong
target.**

`~/.claude/skills/impeccable/scripts/detect.mjs` carries **60 rendered-page rules** and a full
Puppeteer URL engine (`detect-antipatterns.mjs` line 34, `cli/main.mjs` line 300: any
`http(s)://` or `file://` argument gets a rendered pass). Among its rules, by id:

```
flat-type-hierarchy   monotonous-spacing     tiny-text            undersized-ui-text
cramped-padding       text-occlusion         text-overflow        clipped-overflow-container
low-contrast          gray-on-color          line-length          tight-leading
design-system-color   design-system-font     design-system-font-size   design-system-radius
nested-cards          edge-flush-cards       numbered-section-labels   kicker-above-heading
cream-palette         first-viewport-column-overflow   content-hidden-at-rest   layout-transition
```

The campaign's verify lines run it as `detect.mjs webui/src/shared/controls` — **a source
directory**. Run against the rendered console it would have caught, without a human:
`cramped-padding` (board.css's zero horizontal padding in bordered cells, B20),
`text-occlusion` (the rail labels overflowing onto the dark room, B20),
`clipped-overflow-container` / `text-overflow` (turns and logs, B7), `low-contrast` (D7's
1.98:1), and the type floors.

**Fix one, cost zero:** point the existing detector at the running console.

```
node ~/.claude/skills/impeccable/scripts/detect.mjs http://localhost:<port>/#/fleet
```

---

## 3. The delta this repo still needs, and a working prototype of it

Nine findings from the m1 review are **not** reachable by any off-the-shelf rule, because they
are either specific to this world or structurally invisible to a computed-style check.

| Check | Why nothing else catches it | Blocking |
|---|---|---|
| `field-grid` | A rack's legibility is that field N sits at the same x on every strip. No general tool knows what a strip is. | **yes** |
| `ladder-steps` | A rendered page can pass `flat-type-hierarchy` while the ladder it draws from has no step in it. This checks the sheet, not the page. | **yes** |
| `no-type-transform` | **The laundering hole.** A scale transform changes rendered glyph size and leaves `font-size` untouched, so every type check in every tool passes while the type is deformed. | **yes** |
| `type-distribution` | "Every rung is legal" and "the ladder is used" are different sentences. | warn |
| `spacing-distribution` | `monotonous-spacing` fires on *few distinct values*; this world has eight, all clustered at the floor. | warn |
| `tonal-drift` | A distribution diff against the comp, not a pixel diff: survives content changing, still catches a world losing its middle register. | warn |
| `absence-vocabulary` | Thirteen phrasings for *empty* is a copy defect no linter has a rule for. | warn |

`look-gate.mjs` beside this file implements all seven. It runs in **4.2 s**, needs no browser
and no dev server (it reads the captures the campaign already produces), and carries a
mutation proof — `--selftest` runs every check against a synthetic violation and against the
compliant form, and fails if either verdict is wrong. A gate that has never failed is a
tautology; this one is proven able to fail before it is trusted to pass.

Against the tree on 2026-09-18 it reproduced the review's arithmetic from the artifacts alone,
with no human in the loop, and found three captures the hand pass had missed:

```
FAIL  ladder-steps          rungs 12/14/16/18/20/23 · steps 1.167 1.143 1.125 1.111 1.15 · 0 of 5 reach 1.25x
FAIL  field-grid            7 captures stagger; worst teams.png 436px, compaction.png 200px, usage.png 181px
warn  type-distribution     109/154 (71%) of font-size declarations on the two smallest rungs
warn  spacing-distribution  240/385 (62%) of spacing uses at <=8px
warn  absence-vocabulary    14 distinct absence phrasings
warn  tonal-drift           13/14 captures below half the comp's mid-tone
ok    no-type-transform     no scale transform on a font-size rule
```

That last line is the argument for the whole exercise: `no-type-transform` passes **because the
widget row fixed B2 by sourcing Archivo's `wdth` axis instead of scaling**. The gate now holds
that fix in place. Nobody has to re-find it.

---

## 4. Where it goes

```
1. impeccable detect <rendered url>     per page, per theme   — layer 1 + 2, already owned
2. look-gate.mjs                        per row               — layer 1 delta, 4s
3. resolved-value token diff            per PR                — cheapest high-value check
4. a Playwright screenshot of ONE surface in both themes      — layer 2, start small
5. the rubric judge                     per milestone         — layer 3
6. the operator, ten minutes            per milestone         — layer 4, named
```

Steps 1 and 2 belong in every look-bearing row's `verify` line. Step 6 belongs to the operator
and nothing above it should reach him: the point of layers 1-3 is that he only ever sees the
things no script could have seen.

**Rules for keeping it honest**, from the research and from §24:
- Blocking stays at three checks. Everything new starts as a warn and is promoted only after it
  has run clean for a milestone.
- Every check ships with its mutation proof in `--selftest`. No proof, no promotion to blocking.
- Tolerances tune downward until false positives stop, never upward until failures stop.
- One documented override, logged, and reviewed at the milestone. A gate with no override path
  gets bypassed instead of fixed.
- Audit what got through after each milestone; the escapes become new checks. This document and
  the m1 review are the first such audit.

---

## 5. What a gate will never catch

The m1 review's B3 ("one cream for every strip"), E4 ("no wow"), and every copy judgment were
found by a person looking at the thing. `tonal-drift` can now measure the *symptom* of E4 —
13 of 14 captures below half the comp's middle register — but only because a human first
worked out that the middle register was where the world lived. **The gate encodes answers; it
does not find them.** That is layer 4, it is the operator, and the correct goal is not to
replace him but to make sure nothing reaches him that a script could have caught first.

---

### Sources

- *Design QA as a Release Gate*, uxuiprinciples.com, 2026-05 — the release-gate framing; Curtis
  (EightShapes) on tiered component QA; block-on-fail kept to 3-5 checks.
- *Visual Regression & Token Drift*, css-architecture.com — the resolved-value diff as the first
  gate; snapshot tolerances; the three sources of snapshot noise (font loading, scrollbars,
  unsettled animation).
- *The Designer's Eval Stack*, brainy.ink, 2026-04 — the four-layer pyramid and the ten-point
  readiness checklist.
- *Taste as a deploy gate*, Jason Teixeira, sageafterdark.com, 2026-05 — why layer 4 cannot be
  automated; the two questions; naming and staffing the gate.
- *Testing Design Systems with Storybook*, helpmetest.com, 2026-05 — per-variant stories as the
  unit of visual test; axe in the test runner; token assertions as unit tests.
- Rauno Freiberg (Vercel) interviews — the six-word standard, weakest-link scoring.
- `emil-design-eng` skill (Emil Kowalski) — already installed here; the before/after review table.
- Katie Dill (Stripe) on friction logs, "walk the store", and quarterly essential-journey scoring
  — the qualitative loop that sits beside the mechanical one.
