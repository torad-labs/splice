# M1 VERIFY AUDIT — every leg in the ledger, and whether it can fail (row M1-27)

Auditor: `code-reviewer` (opus), 2026-09-18. Read-only. **No verify line was edited and no code was
changed.** The only path this row wrote is `dev/web-console/reviews/`.

## The denominator, enumerated from the file

| | |
|---|---|
| ledger | `dev/campaigns/web-console.toml`, sha256 `1811ea08f52b9079…`, 783,688 bytes, read 2026-09-18T05:07-05:00 |
| rows | **68** — 41 `done`, 22 `todo`, 5 `in_flight`, 0 `verified` |
| rows carrying a `verify` string | **68 of 68.** None is missing one |
| legs | **458** |
| legs inside a `done` row | **320** |
| tree the probes ran against | `f5e6bac0` (worktree tip), in a `git clone -s` copy outside the worktree |

A *leg* is a command whose exit status the chain can observe. The splitter respects quotes, `$( )`,
subshells and `for`/`if` compounds, so `for d in …; do …; done` counts once. Every one of the 458
has a DISPOSITION below; **none is left blank** — absence is not a disposition.

The ledger is live: it gained three rows (M1-28, M1-29, M1-30) while this audit ran. The numbers
above are that snapshot.

## DISPOSITION — all 458 legs

| disposition | legs | meaning |
|---|---:|---|
| **CAN FAIL** | 237 | a planted defect makes it exit non-zero; measured, not assumed |
| **CONTEXT** | 91 | `cd`. Changes where later legs run; not evidence of anything |
| **CAN FAIL, NARROWLY** | 41 | it can go red, but only for a fraction of what it appears to check |
| **CAN FAIL, WITH A LAW-SIZED HOLE** | 38 | `npx tsc --noEmit`, which `typecheck-is-tree-wide` lets a row land `done` against while red |
| **WEAK** | 17 | it passes on evidence that does not distinguish a good result from a bad one |
| **UNKNOWN — NOT RUN HERE** | 12 | needs a rendered page over CDP, or belongs to a ledger this seat cannot claim |
| **CANNOT FAIL** | 8 | no state of the world makes it exit non-zero |
| **NOT RE-RUNNABLE** | 7 | reads mutable shared state that later rows move; green once, red later, in either order |
| **LOADS ONLY** | 4 | `--help`. Proves the script parses and nothing else |
| **CANNOT FAIL WHEN THE TOOL DID NOT RUN** | 3 | asserts the ABSENCE of a string; a tool that never runs prints none |

Inside `done` rows: **32 legs across 15 rows** are WEAK, CANNOT FAIL, NOT RE-RUNNABLE or LOADS ONLY.
No `done` row has zero legs that can fail — but four of them have none that can fail *about the
thing the row claims*, which is the list at the end.

## How each disposition was earned

Every classification below rests on a probe run in the clone, not on reading. The probe log:

| probe | result |
|---|---|
| `ast-grep scan -c sgconfig.yml <missing path>` | **exit 0** |
| `ast-grep scan -c sgconfig.yml <dir with a planted `padding: 8px`>` | exit 1 |
| `ast-grep scan -c <missing config> <path>` | exit 6 |
| `npx eslint ../dev/web-console/gate.mjs --no-config-lookup --config eslint.config.mjs` (cwd `webui`) | **exit 0**, `File ignored because outside of base path.` |
| …the same file after appending `undefinedFn(;` (a syntax error) | **exit 0**, same warning |
| `npx eslint src/zz-probe.ts` with an unused const | exit 1 |
| `npx eslint <missing path>` | exit 2 |
| `npx vitest run tests/no-such.test.ts` | exit 1 |
| `npx vitest run <dir with no tests>` | exit 1 |
| `npm run lint -w webui` / `npm test -w webui` from inside `webui` | exit 1 |
| `npx tsc --noEmit` with no tsconfig in cwd | exit 1 |
| `detect.mjs <source dir>` on the real tree | **exit 0, no output at all** |
| `detect.mjs <missing dir>` | **exit 0**, `Warning: cannot access …` |
| `detect.mjs <dir seeded with 8 classic anti-patterns>` | exit 2, **1 of the 8 reported** (`overused-font`) |
| `capture.mjs 'http://localhost:5199/#/fleet' out.png` (nothing listening) | **exit 0**, writes a 25 KB PNG |
| `build-phase.mjs status` from the repo root | **exit 0**, `no state at .impeccable/build/state.json` |
| `look-gate.mjs --selftest` | exit 0, 4 cases incl. negatives, `4 passed, 0 failed` |
| `exit-gate.mjs --selftest` | exit 0, **6/6 incl. `an empty leg set is not a pass`** |
| `gate.mjs m1-preview --dry-run` with the `ADDRESSES` table renamed | exit 1 |

---

# The loud form: `|| true`

Two rows, both `done`, both closing the whole chain.

**M1-14** — `done`. The complete verify:

```
node dev/web-console/comp-check.mjs --help >/dev/null
  && node dev/web-console/comp-check.mjs --list | grep -q rail
  && cd webui
  && npx eslint ../dev/web-console/comp-check.mjs --no-config-lookup --config eslint.config.mjs >/dev/null 2>&1
  || true
```

`A && B && C || true` groups as `((A && B && C) || true)`. Run verbatim in a clone of the review
head where `comp-check.mjs did not exist`: **`M1-14 verify EXIT = 0`**. Leg 4 is a measured no-op on
top of that (see below), and leg 1 is a `--help`. The only leg that observes anything is leg 2,
which proves the constant table contains the word `rail`.

**M2-11** — `done`. Eleven legs; the chain ends `… >/dev/null 2>&1 || true`. Eight of the eleven are
WEAK or CANNOT FAIL. The one leg that can genuinely fail is leg 4,
`test $(grep -c address .../manifest.json) -eq 52`, and even that is satisfied by a manifest of 52
blank captures.

The exit status of both rows' verify is a constant. Nothing about either row was observed.

---

# The quiet forms

## Q1 — a lint run against a directory outside its base path (2 legs, both `done` rows)

```
M1-14 leg 4   npx eslint ../dev/web-console/comp-check.mjs --no-config-lookup --config eslint.config.mjs >/dev/null 2>&1
M2-11 leg 10  npx eslint ../dev/web-console/gate.mjs ../dev/web-console/lib ../dev/web-console/capture.mjs --no-config-lookup --config eslint.config.mjs >/dev/null 2>&1
```

`webui/eslint.config.mjs` scopes its only rule block to `files: ['src/**/*.{ts,tsx}']`. eslint's own
words for anything else, in JSON: `"message": "File ignored because outside of base path."`,
`errorCount: 0`. **Measured: it exits 0 even when the target file contains a syntax error.** This is
the class M1-14's own honesty note already named; the measurement is here so the other one
(M2-11 leg 10, which nobody had flagged) carries the same weight.

*What it was supposed to observe:* that the campaign's own scripts are lint-clean.
*What observed it instead:* nothing. No other leg in either row lints `dev/web-console/**`, and no
other row in the ledger does either.

## Q2 — an assertion made of ABSENCE (3 legs, three `done` sweep rows)

```
M1-08 leg 8   if npx vitest run tests/world.test.ts 2>&1 | grep -E -q 'src/(app|features)/'; then exit 1; fi
M1-09 leg 9   if npx vitest run tests/world.test.ts 2>&1 | grep -E -q 'src/pages/';          then exit 1; fi
M1-10 leg 5   if npx vitest run tests/world.test.ts 2>&1 | grep -E -q 'src/widgets/';        then exit 1; fi
```

These carry the whole claim of the three sweep rows: *this layer no longer reaches into the retired
plate world.* They are satisfied by a string not appearing.

Reproduced: I replaced `tests/world.test.ts` with a file that fails to import. `vitest` exits 1 and
prints a stack trace with no findings in it. All three shapes then report:

```
M1-08 shape: the if did NOT fire -> chain CONTINUES (GREEN)
M1-09 shape: GREEN
M1-10 shape: GREEN
```

Deleting `world.test.ts` outright has the same effect. So "the sweep is complete" was proven by the
silence of a tool that is never required to speak.

The same rows' authors got the opposite shape right one row earlier. **M1-07 leg 6** asserts the wall
IS red — `npx vitest run tests/world.test.ts 2>&1 | grep -q pages/settings` — and with the test file
broken that leg exits **1**. A positive assertion about output is fail-closed; a negative one is not.
The fix is to require the tool to speak first: assert the run's own exit code, or grep for a line the
passing wall always prints (it logs `world wall: N old tokens, M old primitives`).

*What it was supposed to observe:* that `world.test.ts` ran and reported nothing for that layer.
*What observed it instead:* the world wall is in fact green at the tip (2 passed) — so the claim is
true. It was simply never proven by the verify.

## Q3 — a capture of nothing (2 legs, both `done` rows)

```
M1-08 leg 6   node dev/web-console/capture.mjs 'http://localhost:5173/#/fleet' $(pwd)/webui/.impeccable/review/sections/fleet.png
M2-11 leg 7   node dev/web-console/capture.mjs 'http://localhost:5173/#/fleet' $(pwd)/webui/.impeccable/review/gate/m1-preview/capture-smoke.png
```

Reproduced against port 5199, which nothing serves: **exit 0, and a 25 KB PNG is written.** The
follow-on `test -s …` then passes. The pair proves a file exists, not that a console was ever drawn.

Worse, `gate.mjs`'s own blank detector would not flag it either. It measures the fraction of the
frame that is the room colour and calls `>= 0.98` blank. Measured on that dead-port capture with the
campaign's own `lib/png.mjs`: `console-room = 0.000`. A frame showing the browser's error page — or
a different application entirely — scores zero and reads as a page that drew perfectly.

## Q4 — `test -s` on a PNG (10 of the 17 WEAK legs)

```
M1-05 leg 9   test -s webui/.impeccable/review/hero-repro.png
M1-11 leg 7   test $(ls webui/.impeccable/review/gate/light-room/*-light-*.png | wc -l) -eq 26
M1-11 leg 8   test -s webui/.impeccable/review/gate/light-room/sheet-light.png
M1-15 leg 6   test -s webui/.impeccable/review/sweep/teams.png
M1-19 legs 3-5 test -s …/census/sheet-dark.png, sheet-light.png, test $(ls …/census/*.png | wc -l) -ge 52
M2-11 legs 3,5,6,8  the 52-file count, both sheets, and capture-smoke.png
```

A blank capture is a non-empty file. A 52-frame set in which every frame failed to draw satisfies
both `-s` and `-eq 52`. M1-18 reproduced `gate.mjs` reporting `BLANK: 52 of 52 captures are >= 0.98
room colour` and exiting **0**, so the producing leg does not catch it either. The manifest each run
writes already carries the per-capture `blank` fraction — `test $(grep -c '"blank":0\.9' …) -eq 0`
would turn ten weak legs into real ones without a new instrument.

## Q5 — mutable shared state (7 legs), and one `done` row that is red today

```
M1-06 leg 5   node …/build-phase.mjs status | grep -E -q 'hero +closed'
M1-15 leg 8   node …/build-phase.mjs status | grep -E -q 'hero +closed'
M3-02 leg 10  node …/build-phase.mjs status | grep -E 'motion +closed'
M3-03 leg 9 / M3-04 leg 10 / WC-02 leg 6 / WC-09 leg 6   (todo rows, same shape)
```

These read `webui/.impeccable/build/state.json`, which every phase row advances. The state is not
part of any row's fence and no row owns it, so a leg that greps it is a snapshot of a moving object.

**M3-02 is `done`, and its last leg fails right now.** Run verbatim at the tip:

```
node …/build-phase.mjs status | grep -E 'motion +closed'   ->  exit 1
```

because the phase table currently reads `motion pending` (the run is back at `sections`). Either the
phase was closed when M3-02 landed and has since been reset, or it never was. The verify cannot tell
us which, and that is the point: this leg has no fixed answer.

One saving grace, checked rather than assumed: all seven of these legs run with `cwd=webui`, where
the state file actually is. Run from the repo root — as several other legs in the same rows are —
`build-phase.mjs status` prints `no state at .impeccable/build/state.json` and **exits 0**; the grep
would then fail, so even that mistake would be fail-closed. The cwd handling in this ledger is
careful, and it is worth saying so.

## Q6 — a grep that its own file's comments satisfy (1 leg) and word-presence greps (5 more)

```
M1-16 leg 5   grep -q detect.mjs dev/web-console/look.mjs
```

`detect.mjs` appears in `look.mjs` at **line 13** and **line 27 — both inside the header comment** —
before the real use at line 54. The check is satisfied by the file merely *discussing* the detector.
This is exactly the shape predicted: a grep matching its own file's comment. M1-16's substantive
claim was "a real rendered pass"; no leg in that row observes a rendered anything. Legs 3 and 4 are
`--help`, leg 2 is `test -s` on a markdown file, leg 1 is a genuine selftest of a different script.

The other five are `grep -q <WORD> <the document this row wrote>`: M1-18 `DUTY`, M1-19 `OWNER`,
M1-21 `band`, M1-25 `LIGHT`, and this row's own M1-27 `DISPOSITION`. They can fail, and they observe
that the author typed a word. They are honest about being shape checks; they should not be read as
evidence about content. **This row's verify is in that category, and I am not exempting it.**

## Q7 — the detector, 36 rows deep (36 legs)

`node …/detect.mjs <source dir>` is the last leg of almost every build row: M1-01…M1-17, M2-01…M2-12,
M2-D1-D3, M3-00…M3-04, WC-01, WC-10.

Three measurements:

1. On the real tree it prints **nothing** and exits 0. It has never reported anything to anybody.
2. On a directory seeded with eight textbook anti-patterns (glow shadow, 24 px radius, purple
   gradient, `transition: all`, tight letter-spacing, low contrast, generic font stack, inline style
   duplicates) it found **one** — `overused-font` — and exited 2. The rest need the rendered page.
3. On a **missing directory** it prints `Warning: cannot access …` and **exits 0**.

So the disposition is CAN FAIL, NARROWLY: it is a real gate for a handful of source-visible string
patterns, and a no-op for the sixty rendered rules the campaign actually cares about. The
already-recorded finding (`dev/web-console/look-gate.md`) that these verify lines hand a *source
directory* to a *rendered-page* tool is confirmed here with numbers, and M1-16's `look.mjs` is the
right fix. Until the rows' verify lines are switched to it, 36 legs are decoration.

## Q8 — structural scans that exit 0 on a missing path

`ast-grep scan -c sgconfig.yml <path>` exits **0** when `<path>` does not exist (exit 6 only when the
*config* is missing). `detect.mjs` does the same. Today this bites one row:

```
M2-08 [todo] leg 7  ast-grep scan -c sgconfig.yml webui/src/pages/teams webui/src/widgets/team-board \
                      webui/src/widgets/team-chat webui/src/widgets/activity-feed webui/src/features/team-compose …
M2-08 [todo] leg 8  node …/detect.mjs <the same five paths>
```

Three of those five directories do not exist — `widgets/team-chat`, `widgets/activity-feed`,
`features/team-compose`. When M2-08 lands, both legs will pass whether or not the builder creates
them. Its eslint leg (exit 2 on a missing path) and its `npx vitest run tests/teams.test.ts` (exit 1)
would catch it, and its leg 6 `for d in …; do test -e "$d" || exit 1; done` catches it too — so
M2-08 is covered by accident rather than by those two legs. Worth stating before it is claimed.

Across all 458 legs, 66 name a path that does not exist at the tip and 52 name one absent at the
row's own landing commit. Most of those are my parser catching a `$p.png` loop variable or a
gitignored capture directory; after filtering, M2-08 is the only live instance.

## Q9 — the missing class: **no `done` row in this ledger has ever run a build**

Nine rows have a `.css` file in their fence. Seven are `done` and **not one of them has a build leg**:

```
M1-01 done   M1-10 done   M1-11 done   M1-13 done   M1-15 done   M1-17 done   M1-23 done   -> no build leg
M1-24 in_flight   M1-26 in_flight                                           -> npx vite build
```

Across all 41 `done` rows, the count of build legs is **zero**. The ten rows that do carry
`npm run build -w webui` are the WC-* console rows, all `todo`.

That is the structural reason M1-18's F1 survived: `M1-17` edited `webui/src/shared/ui/ui.css`,
left an orphaned `font-variant-numeric: tabular-nums;` outside its rule block at line 350, and its
verify — tsc, eslint on `src/shared/ui`, two vitest files, `test -d`, ast-grep, detect.mjs — passed
every leg, because **none of those tools parses CSS with a CSS parser**. Checked at the tip
`f5e6bac0`, the defect is still there and `npm run build -w webui` still fails with
`Invalid token in pseudo element: WhiteSpace(" ")`.

The new law (`A ROW THAT TOUCHES CSS RUNS A BUILD LEG`) and the two in-flight rows carrying
`npx vite build` are the right fix. The seven `done` rows behind it were never proven to build.

## Q10 — two instruments this campaign has proven blind, still load-bearing

```
M1-09 leg 6   node dev/web-console/fixture-leak.mjs      [done]
M2-12 leg 1   node dev/web-console/fixture-leak.mjs      [done]
M1-20 leg 6   node dev/web-console/fixture-leak.mjs      [done]
M1-11 leg 6   node dev/web-console/gate.mjs light-room   [done]
M2-11 leg 2   node dev/web-console/gate.mjs m1-preview   [done]
```

M1-18 reproduced both: `gate.mjs` exiting 0 on 52-of-52 blank captures, and `fixture-leak.mjs`
reporting a live leak as clean while printing `BLIND` for a second fixture and exiting 0 anyway.

And today all three `fixture-leak.mjs` legs **cannot run at all**: the script builds first, and the
build is red (Q9). Run at the tip: `node dev/web-console/fixture-leak.mjs` → **exit 1** before it
reaches a single fixture.

## Q11 — the law-sized hole: 38 `tsc` legs

`npx tsc --noEmit` appears in 38 legs (32 inside `done` rows) and is a real check. But
`typecheck-is-tree-wide` says a row may count it green "when every remaining error is in files
outside your fence". That is a sound rule for a shared worktree and a hole all the same: the leg's
exit status is not what decides the row — a human reading the error list is. Six of the seven CSS
rows above lean on `tsc` as their strongest structural leg, and `tsc` never looks at a stylesheet.

## Q12 — 32 legs belong to a ledger this one cannot claim

The eight `WD-*` daemon rows (`WD-01`…`WD-08`, all `todo`, 32 legs) were handed to
`dev/campaigns/v0.4.0.toml` by the CROSS-LEDGER FENCES law and are "not claimable here". Their legs
are in this ledger's verify denominator and will never run from it. Disposition: UNKNOWN — NOT RUN
HERE. Their three underlying checks do work when run (`knob-keys-documented.py check .` exit 0,
`schema-keys-consumed.py` exit 0, `gradle-slot.sh` exit 1 with no args); the issue is ownership, not
the checks.

---

# THE ROWS WHOSE `done` IS NOT SUPPORTED

Ranked by how much m1 leans on them. "Unsupported" means: the row's own distinctive claim is carried
only by legs that cannot fail, or by an instrument since proven blind. A row can be unsupported and
still be *correct* — most of these are. The point is that the ledger cannot tell.

### 1. M2-12 — "no fixture byte ships" — UNSUPPORTED AND FALSE

Its sole claim-observing leg is `node dev/web-console/fixture-leak.mjs` (leg 1). M1-18 proved that
wall blind and proved the claim false: `webui/src/pages/models/fixtures/models.ts` is in
`webui/dist/index.html`, 10 needles of 10. Its other seven legs are tsc, eslint, vitest, ast-grep
and detect.mjs on two page directories — none of which can see inside the artifact. This is the only
row in the ledger whose title is contradicted by a measurement.

### 2. M2-11 — the m1 capture set — UNSUPPORTED

Eight of eleven legs WEAK or CANNOT FAIL; the chain ends in `|| true`; its producing instrument
(`gate.mjs`) exits 0 on an all-blank run; its smoke capture passes with nothing serving. The design
review reads this row's output. Nothing here distinguishes 52 good captures from 52 error pages.

### 3. M1-14 — the comp-constants wall — UNSUPPORTED

Exit status is a literal `true`. Reproduced green with its own script deleted. The row's deliverable
was also untracked at the review head. `comp-check.mjs` itself is sound — `process.exit(failures ? 1
: 0)`, `--list` emits 130+ constants — which is what makes this pure verify-line damage.

### 4. M1-08, M1-09, M1-10 — the three world sweeps — CLAIM UNPROVEN

Each row's sweep claim rests on Q2's absence assertion, which passes when `world.test.ts` does not
run. M1-09 additionally leans on `fixture-leak.mjs` and M1-08 on a capture that passes against
nothing. The claim is *true today* (the world wall is green at the tip, 2 passed) — but by
observation made in this audit, not by anything those rows ran.

### 5. M1-17 (and M1-01, M1-10, M1-11, M1-13, M1-15, M1-23) — the CSS rows — UNPROVEN FOR THE BUILD

Seven `done` rows whose fence names a stylesheet and whose verify never builds. M1-17 is the
demonstration: it shipped a CSS syntax error that breaks the production build and silently deletes a
second rule, and every leg of its verify was green.

### 6. M3-02 — motion — VERIFY IS RED TODAY

Leg 10 greps `motion +closed`; the phase table reads `motion pending`. Its other legs (eslint,
vitest on `tests/motion.test.ts`, ast-grep, detect.mjs) are fine, so the row is well supported on
everything *except* the phase claim its last leg makes.

### 7. M1-11 — the light room — PARTLY UNSUPPORTED

Legs 2-4 and 9 are real and cover the token/contrast claim well (`tests/contrast.test.ts` is the
best-built wall in the campaign). The light *room* claim, though, is carried by `gate.mjs
light-room` (CANNOT FAIL) plus a 26-file count and a `test -s` on a sheet — three legs that 26 blank
captures satisfy.

### 8. M1-16 — the look gate — CLAIM UNOBSERVED

Its own acceptance was "the rendered run must emit at least one rule id the static engine
structurally cannot emit". No leg observes a rendered run: two `--help`s, a `test -s` on a markdown
file, a comment-satisfied grep, and a selftest of `look-gate.mjs` (which genuinely passes 4/4 with
negative cases — that leg is sound).

### 9. M1-19 — the punch list — SHAPE ONLY

Four of six legs WEAK: two `test -s` on census sheets, a `>= 52` PNG count, and a `grep -q OWNER` on
the document the row wrote. Nothing observes whether the census is right.

### 10. M1-05, M1-06, M1-15, M1-21, M1-18 — ONE WEAK LEG EACH

M1-05's `test -s hero-repro.png`; M1-06's and M1-15's `hero closed` grep; M1-21's and M1-18's
word-presence greps. Each row has three or more legs that can genuinely fail, so the `done` stands;
these are notes, not findings.

---

# What would fix the class, in the order it costs least

1. **Delete the two `|| true`s** (M1-14, M2-11) and re-run both rows. One character each.
2. **Require the tool to speak.** Replace Q2's `if TOOL | grep -q X; then exit 1; fi` with a form
   that fails when the tool does not run — assert the exit code first, or grep for the line the
   passing wall always prints.
3. **Make `test -s` on a capture mean something.** The gate already writes the per-capture `blank`
   fraction into `manifest.json`; assert on that instead of on file size. Turns ten weak legs real.
4. **Fail `capture.mjs` when nothing served the URL** — a non-2xx or an about:blank result should be
   a non-zero exit, not a 25 KB PNG.
5. **Keep the new CSS build law and backfill it.** The seven `done` CSS rows need one `npx vite
   build` run against them before `verify-phase m1`; that single run is also the fix for M1-18's F1.
6. **Stop handing `detect.mjs` a source directory in 36 rows.** `look.mjs` exists for this.
7. **Own the phase state or stop grepping it.** A leg that reads `build-phase` state is not
   reproducible; record the phase in the row's note instead.
8. **Lint `dev/web-console/**` with a config that covers it**, or drop those two legs rather than
   keep a no-op that reads like coverage.

## What this audit did NOT find, stated so the absence is legible

No leg in this ledger runs the wrong cwd (I traced the cwd through all 458 and checked every path
argument against both the tip and the row's landing commit; the only misses are M2-08's unbuilt
directories). Every `for` loop carries `|| exit 1`, so none of the 25 of them hides a failure in a
non-final iteration. No row is missing a `verify` string. `vitest`, `eslint`, `npm` and `ast-grep`
are all fail-closed on a missing target, a wrong cwd or an empty input — the one exception being
ast-grep's and detect.mjs's silent 0 on a missing *path*. And two instruments written in the last
day, `look-gate.mjs --selftest` (4/4) and `exit-gate.mjs --selftest` (**6/6, including "an empty leg
set is not a pass"**), are mutation-proven in the tree, by their authors, without being asked. That
is the campaign learning this lesson in public, and it belongs in the record next to the rest.

## Reproducing this audit

Scripts in `~/.cache/qgre/scratch/claude-1000/m1-27/`: `split2.py` (leg splitter),
`analyze.py` (cwd tracking + path existence at the tip and at each row's landing commit),
`classify.py` (the disposition rules, each carrying its probe evidence). The probes ran in a
`git clone -s` copy at `f5e6bac0` with `node_modules` symlinked from the worktree; the only files
mutated were inside that clone and each was restored.
