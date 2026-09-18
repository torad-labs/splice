# How the skills assert wow, and what the field actually does

Design-review seat, 2026-09-18. Companion to `m1-design-review.md` and `gate/look-gate.md`.

Read: `taste-skill` (87 KB, 14 sections), `impeccable` (SKILL.md + 36 references + 30 scripts),
`emil-design-eng` (27 KB), `design-control-loop` (6.5 KB), `torad-design` (10 KB + a Python gate).
Then an Exa pass on how teams that ship consistent, good-looking work actually enforce it, and on
why model-written UI converges. Sources at the end, with their conflicts of interest named.

This is not a summary of five skills. It is an attempt to answer one question: **what kinds of
machinery exist for asserting design quality, what can each kind actually catch, and which ones is
this campaign missing.**

---

## 1. There are nine mechanisms, and every rule in every skill is one of them

Once you stop reading these skills for their content and read them for their *machinery*, the same
nine devices appear over and over. They are not interchangeable — each catches a different class of
defect, and each fails at the others.

| # | Mechanism | What it is | Catches | Blind to |
|---|---|---|---|---|
| 1 | **Declare before building** | A forced stance, written down before code | Sampling the average by default | Bad execution of a good stance |
| 2 | **Nevers** | An enumerated ban list | The category's defaults | Anything not on the list |
| 3 | **Counted rules** | A rule with an integer in it | Density, repetition, over-decoration | Whether the count was the right one |
| 4 | **Rendered detectors** | Scan the painted page | Overlap, clipping, contrast, tiny type | Anything that needs a target to compare against |
| 5 | **Authority diff** | Measure the build against an approved image | Material that went missing; drift from the world | Whether the authority was any good |
| 6 | **Rubric judgment** | A scored heuristic set, by model or person | Task failure, cognitive load, states | Beauty; "ugly" is not a Nielsen heuristic |
| 7 | **Ordering discipline** | Rules about the *sequence* of looking | Anchoring; a green gate laundering an optical defect | Nothing — it protects other checks, it is not one |
| 8 | **Rotation** | Don't repeat your last project's choices | Self-similarity across work | Anything inside a single project |
| 9 | **Review format contract** | The review's own output shape is mandated | Mushy prose passing as a review | Whether the rows are true |

Where each skill puts its weight:

| | taste-skill | impeccable | emil | design-control-loop | torad-design |
|---|---|---|---|---|---|
| 1 Declare | **§0.B design read + §1 three dials** | Mode + direction contract | — | — | The seat |
| 2 Nevers | **§9 AI Tells, ~40 items** | **craft-floor "Refuse", 18 items** | Checklist's left column | — | "No outline soup…" |
| 3 Counted | **the deepest of any skill** | measure 65–75ch, display ≤6rem | 150–250 ms, 30–80 ms stagger | 44 pt targets | contrast ≥4.5 |
| 4 Detector | — | **`detect.mjs`, 60 rule ids, two engines** | — | — | structural only |
| 5 Authority diff | — | **`comp-spec` → `comp-diff` → `build-phase`** | — | wireframe transfer boundary | — |
| 6 Rubric | — | **`critique.md`: Nielsen 0–4 ×10 + personas** | — | the evidence table | reflex check |
| 7 Ordering | Pre-flight runs last | **A/B isolated sub-agents; A before B** | "review it the next day" | **the whole skill** | — |
| 8 Rotation | **only skill that has it** | — | — | — | — |
| 9 Format | §14 checkbox matrix | report header + degraded banner | **mandatory Before/After/Why table** | — | — |

### What each one uniquely contributes

**taste-skill** owns **counted rules** and is the only skill anywhere with **rotation**. Its best
work is converting taste into arithmetic: eyebrows `≤ ceil(sectionCount / 3)`, hero ≤ 4 text
elements, subtext ≤ 20 words and ≤ 4 lines, max 2 consecutive same-family sections, one marquee per
page, at least 4 different layout families across 8 sections. Every one of those is a number an
argument cannot move. That is the same instrument section E used on this console — "96.7 % of the
frame is two flat values" beats "it feels flat" — and taste-skill got there first and went further.

Rotation is the one mechanism that reaches outside the current repo: *don't use the same serif as
your last project; if the last premium-consumer palette was beige+brass, this one must not be.*
Nothing else in any of these skills can see self-similarity across work. It does not apply here (one
locked world, one comp of record) and I note it only because it is the mechanism nobody else has.

**impeccable** owns the two mechanisms with real machinery behind them — a **rendered detector** and
an **authority diff** — plus the only **rubric** with a scoring scale. Its detector carries 60 rule
ids including `text-occlusion`, `clipped-overflow-container`, `flat-type-hierarchy`, `tiny-text`,
`undersized-ui-text`, `monotonous-spacing`, `line-length`, `low-contrast`. Six of those name defects
I found in this console by hand.

**emil-design-eng** owns the **format contract**, and does it more strictly than anyone: reviewing UI
code *must* produce a `| Before | After | Why |` markdown table, one row per issue, and the skill
spells out the wrong format explicitly to close the escape hatch. Everything else in it is 700 lines
of counted motion rules. Its two philosophical claims are worth keeping: *unseen details compound*,
and *review your work the next day* — the only fresh-eyes rule in the set.

**design-control-loop** is almost entirely **ordering discipline**, and it is the most valuable
document of the five for this campaign. It contains the sentence this milestone learned the hard way:

> **"A green structural gate cannot override any optical defect."**

Its sequence is the point: render at the exact viewport **first**; inspect dense components at 1:1;
**inventory every visible defect before receiving builder context**; check ink and gaps, not outer
coordinates ("outer-coordinate parity is insufficient: it can hide compressed groups"); use structure
**second**; use exactly **one blind peer**, who receives renders and not the builder's rationale; fail
closed; only explicit user approval locks a screen.

**torad-design** is the most honest about scope. Its gate's docstring: *"This gate checks promises the
skill makes about its own shape. It does not score taste, beauty, or compliance with one preferred
composition."* Its reflex check carries the single best unautomatable question in any of these files:

> **Reskin: could another company ship it unchanged?**

---

## 2. What the research says

### Why model-written UI converges, and what actually moves it

The convergence is measured, not felt. Goree et al. (CHI 2021) ran computer vision over ~227,000
screenshots of ~10,000 sites, 2003–2019: diversity rose until about 2007, then **average layout
distance between sites fell 44 % from 2010 to 2019**, correlated with shared library adoption. That
happened with no model involved — templates, frameworks and responsive stacking did it.

The model stage adds two more compressors. Kirk et al. (ICLR 2024) showed RLHF substantially reduces
output diversity versus supervised fine-tuning — alignment buys generalisation and pays in variety. A
2025 Stanford/Northeastern paper names the cause **typicality bias**: human annotators systematically
prefer the more familiar, more fluent, more prototypical option, that bias flows into the reward
model, and the sharpening persists even under a perfect reward signal. Underneath both is ordinary
processing fluency — prototypical things are easier to parse and therefore rated more beautiful.

The operational consequence, and the reason prompts do not fix it:

> **"A prompt does not extend the model's distribution. It conditions it."** A screen is thousands of
> micro-decisions; a design doc pins perhaps thirty. The rest come from the mode. Worse, the words
> used to pin them — clean, modern, minimal, premium — are themselves the densest region of all design
> writing on the internet. *"If you asked the model to write the design.md in the first place, you have
> conditioned the mode on the mode."*

And the strongest claim in the whole search, which I would put above everything else here:

> **"The strongest signal you can give an agent is not prose, it is artifact."** Decide the design
> language before the agent arrives — palette as tokens, two typefaces, spacing scale, radii, motion
> rules, in a theme file. Hand-build the first screen yourself and let the agent extend it. **You are
> seeding the local distribution.**

The five countersignals the other source lists agree and add detail: (1) tokens all the way down,
including every state of every interactive element, because the model fills in what the brief omits;
(2) **nevers** — five to seven bans eliminate a large swath of the corpus mean before sampling starts;
(3) principles short enough to be quoted back; (4) **references with a reason attached** — "Linear: the
quiet confidence of their spacing" is worth more than a mood board, because *the reason is the part
the model uses*; (5) a persistent file the tool loads automatically, because without it the other four
reset every Monday. The framing: **"Prompts are interpretation. Specs are contract."**

*(That second source is a vendor blog for a product that sells exactly the artifact it recommends.
Discount the conclusion; the mechanisms it describes match the peer-reviewed work the first source
cites, and match what the skills independently do.)*

### How teams enforce consistency

Adoption is the whole game and it is mostly lost. Sparkbox's 2024 survey: **only 28 % of
organisations report widespread adoption** of the design system they paid to build. An audit across
375 sites found **average design-token coverage of 40.4 %**, against a recommended component-coverage
target of 80 %+.

The mechanisms that hold, in rough order of cost-effectiveness:

- **Three token layers with a linter between them.** Primitives hold raw values, semantics hold
  *references*, components consume only semantics. The enforcing rule is one line: **flag any semantic
  token whose `$value` is a literal instead of a `{reference}`.** Without it, a dark theme means
  grepping every component for a hex.
- **The token name is an API surface.** Renaming one is a breaking change: major bump, changelog,
  deprecation window where the old name still resolves and warns. Ship machine-readable status
  (`active` / `deprecated` / `deleted`) so consumers can lint against deprecations.
- **A contrast test on semantic pairs in CI.** "Token changes can quietly break accessibility across
  every screen." Cheap, and nobody does it.
- **Generate every platform output from one source on every merge, and fail the build if any output is
  stale.** That is how you know the change reached every platform.
- **Tiered triggers**, not one big check: pre-commit (fast), CI (comprehensive), scheduled (health
  audits), on-demand (deep dives).
- **Measure visual drift, not just imports.** Quantitative metrics say the system is being *imported*;
  visual comparison against the spec says it is being used *correctly*. The recommended cadence is
  automated token compliance per PR, **manual visual audit quarterly** — the human pass is scheduled,
  not hoped for.

### How the bar gets held by people

Two rituals recur, and both are about *when* and *how* work is shown rather than what is checked.

**Show it ugly.** Koolhaas's "premature sheen": a mockup that looks more finished than it is drags
review onto colour and type while the real question is structure. One studio deliberately greyscales
comps, swaps in an off-brand handwriting face, and scribbles notes over them before a client sees
them. The point generalises: **fidelity should match the decision being made**, and a beautiful
artifact shown at the wrong moment buys agreement it has not earned.

**The Braintrust, two rules.** A director shows the rough version to trusted peers. (1) *The feedback
has no authority* — the director is under no obligation to act on any of it, which is what removes
defensiveness. (2) *Feedback identifies problems, never prescribes solutions* — "the second act drags"
is useful, "add a chase scene" is not. When peers prescribe, the author becomes an executor; when they
diagnose, the author stays the owner. *(Read on LinkedIn, sourced from Catmull's account of Pixar;
treat the framing as second-hand.)*

---

## 3. What this is worth to us

### The strongest finding: three independent sources state the same law

The research says the only thing that moves a model off the corpus mean is an **artifact**, not prose,
and that a design doc written *by* the model conditions the mode on the mode. `impeccable` encodes the
same law as a state machine — `comp-spec` measures an approved comp into numbers, `comp-diff` scores
the build against it, and `build-phase` will not let the build advance past `hero` until
`comp-diff overall ≥ 0.72` with no region `missing`. And splice-design arrived at it independently
this week, ruling that `team-board-a.png` is the authority and CONTRACTS prose is not — the ruling that
overturned my own B12.

Three sources, no contact between them, one law: **the comp is the spec; prose about the comp is an
intention.** That is already campaign law here. It is the reason this console is not slop, and it is
worth saying plainly because it is the thing we got right.

### The gap: we own mechanism 5 and do not use it

This is the second instance of the `detect.mjs` finding, and it is worse than the first.

`comp-diff.mjs` takes a comp and a build capture and returns four scores — **structure** (blurred SSIM:
is the composition the same), **color** (histogram + dominant palette at coverage), **detail**
(high-frequency energy ratio: *"did the material survive, or did an illustration become a gradient?"*),
and **bands** — weighted 0.35 / 0.25 / 0.25 / 0.15, per region, with the verdict words **match / drift
/ missing / contradicted**, `--threshold`, and exit code 3 below it.

**E4 is that `detail` score.** "No wow" is high-frequency energy the comp has and the build does not —
the bays, slot lines, holder plates and ghosts the comp draws and the build dropped. I hand-rolled a
crude proxy for it (`look-gate`'s `tonal-drift`, a mid-tone census) while a better, region-aware,
thresholdable version sits in the skills directory with a CLI. And `build-phase`'s plates gate explains
*why* its metric is built the way it is, which is the part I would not have thought of:

> *"Structure is the floor because it is what a wrong-but-busy plate cannot fake: noise, a mirror, a
> mosaic, another region all keep the palette and the energy and lose structure."*

That is anti-gaming design of a metric — the same discipline as mutation-proving a gate. My
`tonal-drift` check has no such property: a build could pass it by adding noise.

**Proposal.** Replace `look-gate`'s `tonal-drift` with `comp-diff`'s per-region `detail`. It does not
wait on M1-16 — it runs today against the captures already in this lane (measured below); M1-16's
rendered target only makes the captures current. Add `comp-spec.mjs --regions` so the rows are the
world's own regions (bay, strip, rail, rack) instead of horizontal slices. Acceptance is the one that matters: **E4 must come back as a number and a region
list.** If it does not, the metric is wrong for this world and we say so rather than tuning until it
agrees.

### I ran it. E4 comes back as a number, and the aggregate hides it.

Not a proposal — a measurement, taken before writing this section:

```
$ node ~/.claude/skills/impeccable/scripts/comp-diff.mjs \
    --comp webui/.impeccable/mocks/team-board-a.png \
    --build webui/.impeccable/review/sections/teams.png --label m1-teams

COMP-DIFF [m1-teams] overall 86% (match) structure 87% color 78% detail 83% bands 100%
PALETTE comp  #0c0e0e(63%) #ddd8c6(24%) #b6ac97(5%) #736f63(4%) #413f3b(3%)
PALETTE build #080808(67%) #d5d4c4(26%) #646461(4%) #a8a898(1%) #182820(1%)
REGION band-1  match  84%   structure 90%  color 75%  detail 83%
REGION band-2  match  82%   structure 91%  color 75%  detail 78%
REGION band-3  match  81%   structure 92%  color 72%  detail 82%
REGION band-4  drift  79%   structure 87%  color 76%  detail 71%
REGION band-5  drift  61%   structure 64%  color 81%  detail 50%
REGION band-6  drift  71%   structure 86%  color 82%  detail 45%
REGION band-7  drift  70%   structure 93%  color 84%  detail 31%
REGION band-8  drift  70%   structure 87%  color 79%  detail 52%
```

Three things fall out, and the third is the one I did not expect.

**1. It reproduces the tonal census from a different code path.** The comp's three mid-tone entries
sum to **12 %** of the frame (`#b6ac97` 5 + `#736f63` 4 + `#413f3b` 3); the build's sum to **6 %**
(`#646461` 4 + `#a8a898` 1 + `#182820` 1). Section E's hand-rolled census said comp mid-tone
**12.3 %**. Two implementations that share no code agree to a tenth of a percent. The build also
concentrates harder on one value — 67 % at `#080808` against the comp's 63 % at `#0c0e0e`.

**2. `detail` collapses down the page while `structure` holds.** 83 → 78 → 82 → 71 → 50 → 45 → **31**
→ 52, against structure that never leaves 87–93 % except in one band. **Composition present, material
absent.** That is E4 stated as eight numbers, and it is the same defect as the restated B11: I opened
`regions/band-7.png` and `band-5.png` before writing this — the comp's bays carry slot lines, rack
side-walls and bolt marks; the build's bays are flat grounds with strips floating on them.

**3. The aggregate launders the failure.** `overall` is **86 %**, which comp-diff labels **`match`**,
and which would sail past `build-phase`'s own hero threshold of 0.72. A gate wired to `overall` would
have gone green on the console the operator called ugly. The finding lives entirely in the per-region
`detail` column, which the weighted mean dilutes to nothing.

**So: do not gate on `overall`. Gate on a per-region `detail` floor, with no region below it.** This
is the completeness rule again — an aggregate whose denominator averages the failure away cannot fail
for it. `build-phase` already knows this and says so in its own gate ("comp-diff overall ≥ HERO_MIN
**with no region `missing`**"); the per-region condition is the half that does the work.

*Caveat, stated because it bounds the claim:* the comp and this capture show different data, so some
band divergence is legitimate content difference, and the auto-bands are horizontal slices rather than
semantic regions. That is what `comp-spec.mjs --regions` fixes, and it is the setup cost of doing this
properly. The **shape** of the result — detail falling while structure holds — is not something content
difference produces, and it is confirmed in the crops.

Artifacts: `webui/.impeccable/review/gate/compdiff/` (side-by-side, heatmap, 8 region pairs).

### The second gap: we have no ordering discipline, and that is what failed

Every mechanism in look-gate is a check. None of them is a rule about *when* to look. The campaign's
verify lines read the builder's receipt and then look at the render, which is backwards, and
`design-control-loop` had already written down both the fix and the failure:

- Render at the exact viewport **before** reading builder measurements or gate output.
- **Inventory every visible defect before receiving builder context** — blind, then contextual.
- Structure **second**. Inspect rendered ink and nearest-contact, not containment: *"outer-coordinate
  parity can hide compressed groups."*
- **A green structural gate cannot override any optical defect.**
- Exactly **one** blind peer, receiving renders and not rationale. Not a loop.

`impeccable`'s critique enforces the same principle differently — Assessments A and B run as isolated
sub-agents, A must finish before detector findings enter the synthesis context, *"detector output is
deterministic, but it still anchors judgment"*, and a run that degrades to one context must print
`⚠️ DEGRADED` as its first line. **A silent degraded critique is a failed critique.**

This costs nothing to adopt and is the cheapest thing in this document. It is four sentences in the
review lane's own procedure, not a script.

### What no mechanism here can do

splice-design's correction stands, and the research reaches the same place from the other side. Nine
mechanisms, two hundred counted rules, sixty detector ids, a scored rubric and a thresholded authority
diff, and **not one of them emits the sentence "it's ugly as fuck, there's no wow to it."** The rubric
scores task success, not beauty. The detector finds occlusion, not absence of presence. The authority
diff finds drift from a comp, which only helps if the comp is good. The nevers only ban what someone
already noticed.

The operator did not find B3 and E4 because our scripts were weak. He found them because he looked at
the thing and said it was ugly. The nine mechanisms exist to make sure his attention is spent on *that*
sentence and never on a clipped label — which is a real and large saving, and is all they are for.

The one question to hand him with each capture, borrowed verbatim from `torad-design`, because it is
the only check that reaches the thing the others cannot:

> **Could another company ship this unchanged?**

---

## Sources

Model output and convergence:
1. *Same Same but Different: The Anatomy of AI Design Sameness* — saschb2b.com/blog/same-same-but-different. Carries the citations below and the "artifact, not prose" claim.
2. Goree et al., CHI 2021 — 227k screenshots / ~10k sites; layout distance −44 %, 2010–2019.
3. Kirk et al., ICLR 2024 — RLHF reduces output diversity vs SFT.
4. Stanford / Northeastern 2025 — typicality bias in preference data; persists under a perfect reward.
5. *Why AI-generated UI looks generic, and what fixes it* — tasteprofile.io. The five countersignals; "prompts are interpretation, specs are contract". **Vendor blog for a product that sells the artifact it recommends** — mechanisms corroborated, conclusion discounted.

Consistency enforcement:
6. *A Comprehensive Approach to Auditing Design System Adoption* — overlayqa.com. Sparkbox 2024: 28 % widespread adoption; 375-site audit: 40.4 % token coverage; 80 % component-coverage target; visual drift score; automated per-PR + manual quarterly.
7. *How to Scale Design Tokens* — designtokens.substack.com. Primitive / semantic / component layering; the literal-vs-reference lint; deprecation windows; contrast test in CI.
8. *Design System Governance* — stevekinney.com. Token names as API surface; machine-readable status metadata.
9. *Automating Design System Maintenance* — framingui.com. Five monitoring categories; pre-commit / CI / scheduled / on-demand tiers.

Human bar-holding:
10. *Make It Ugly, for Clients* — oddbird.net, 2025-12-11. Koolhaas's "premature sheen"; deliberate de-polishing before review.
11. Pixar Braintrust — two rules: feedback has no authority; identify problems, never prescribe solutions. Read second-hand via LinkedIn from Catmull's account.

Skills read, on this machine: `~/.claude/skills/{taste-skill,impeccable,emil-design-eng,design-control-loop,torad-design}`.
