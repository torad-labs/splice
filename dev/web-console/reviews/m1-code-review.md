# M1 CODE REVIEW — the walls first, then the code (row M1-18)

Reviewer: `code-reviewer` (opus), 2026-09-18. One pass, complete. **Nothing in the tree was
changed by this row.** Every edit this review made was made to a throwaway clone outside the
worktree and reverted; the tree the builders are working in was never touched.

## Scope, and the two SHAs

| | |
|---|---|
| base | `0545f870` |
| review head (HEAD when I claimed the row) | `58e5802d` |
| committed tip when I finished | `f454037d` (30 further commits landed while I worked) |
| diff reviewed | 465 files, 30,428 insertions, 1,848 deletions; 298 files under `webui/` |
| tests at the tip | 29 files, **537 tests, all green** |
| `ast-grep scan -c sgconfig.yml webui/src` at the tip | **clean** |
| `npm run lint -w webui` at the tip | **clean** (2 plugin deprecation warnings, F15) |
| `npm run build -w webui` at the tip | **RED — see F1** |

Every finding below was re-checked against `f454037d` before filing. Where a finding was fixed
in flight while I worked, it says so and is not filed as live. Where the file is byte-identical
between the review head and the tip, the finding is live now.

## Method, so this is repeatable and not a reading

Three clones of the repo were made with `git clone -s` into
`~/.cache/qgre/scratch/claude-1000/m1-18-review/` (`head` = `58e5802d`, `tip` = `f454037d`,
`tree` = tip + the live working tree), with `node_modules` symlinked from the worktree. Each
wall was then handed a **synthetic violation of the thing it claims to protect**, run, and
recorded as RED (caught) or GREEN (missed). The harness scripts are listed in the appendix.

A wall that stays green on a planted violation is reported as a hole whether or not anything
currently exploits it. Where a hole is latent rather than live, it says so.

---

# DUTY ONE — the walls, mutation-proven

## Summary table

| wall | mutations planted | caught | missed |
|---|---|---|---|
| `webui/tests/world.test.ts` | 10 | 4 | **6** |
| `webui/tests/labels.test.ts` | 7 | 3 | **4** |
| `webui/tests/coverage.test.ts` | 2 | 2 | 0 |
| `webui/tests/contrast.test.ts` | 4 | 3 | 1 (a `var()` ground, F10) |
| ast-grep `webui-no-emdash-ui-text` | 3 | 1 | **2** |
| ast-grep (all four webui rules, `ast-grep test`) | 62 in-repo cases | 62 | 0 |
| `dev/web-console/fixture-leak.mjs` | 3 | 1 | **2** |
| `dev/web-console/gate.mjs` | 2 | 0 | **2** |
| `dev/web-console/comp-check.mjs` | not runnable here (needs Chrome + dev server); exit path read |

The four ast-grep rules are the only walls in this tree that were already mutation-proven, by
their own `.rules/rule-tests/*.yml` cases, and the 2026-07-26 audit headers in each rule file
record the four bypasses that audit found and closed. They are in good shape. Everything this
campaign added is proven here for the first time.

---

## F1 — `webui/src/shared/ui/ui.css:350` — a declaration escaped its rule; the production build is RED and two rules are silently deleted

**REPRODUCED.** Cost to ship: **the artifact cannot be built.** Highest.

```css
349  .myx-sfield-figure { font-family: var(--font-figure); }
350  font-variant-numeric: tabular-nums;                       <-- outside any rule block
351  /* A prose value keeps the label face; the box's figure face exists for the ch unit. */
352  .myx-sfield-value:not(.myx-sfield-figure) { font-family: var(--font-label); }
```

Landed in `10a3c53f feat(webui): M1-17 split the figure face from the code face, and rib the
rack floor`. The declaration was meant to be inside `.myx-sfield-figure` on line 349.

Three separate consequences, each measured:

1. **The build fails.** `npm run build -w webui` at `f454037d`:
   `SyntaxError: [lightningcss minify] Invalid token in pseudo element: WhiteSpace(" ")`.
   I bisected every `webui/src/**/*.css` through `lightningcss.transform` to name the file and
   the line: `webui/src/shared/ui/ui.css`, line 350 col 23. Nothing else in the tree fails.
2. **`fixture-leak.mjs` cannot run at all,** because step 1 of that script is the build. So the
   fixture wall is not merely blind at the tip (F2/F3) — it is dead.
3. **A second rule is destroyed, silently.** A recovering parser — the dev server and the
   browser — reads `font-variant-numeric` as the start of a selector and consumes everything up
   to the next `{`, swallowing the comment AND the `.myx-sfield-value:not(.myx-sfield-figure)`
   selector with it. Measured with `errorRecovery: true`: of the three rules in that snippet,
   the parser keeps `.myx-sfield-figure` and `.myx-sfield-basis` and **drops
   `.myx-sfield-value:not(.myx-sfield-figure)` entirely**. So in dev, right now, tabular
   figures are not applied AND prose strip-field values render in the figure face instead of the
   label face — which is the exact defect M1-17 was cut to fix.

Reproduce:

```bash
npm run build -w webui                                    # red
node -e 'const c=require("lightningcss");c.transform({filename:"x",\
  code:require("fs").readFileSync("webui/src/shared/ui/ui.css"),minify:true})'
```

**No wall in this tree can see it.** 537 tests pass, `ast-grep scan` is clean, `eslint` is
clean, `tsc` is clean. Nothing parses page CSS with a real CSS parser. That is the single
cheapest wall this campaign is missing: run `lightningcss.transform` over every
`webui/src/**/*.css` in a vitest file, and it would have failed the row that introduced this.

---

## F2 — the `models` fixture ships in `webui/dist/index.html`, and the wall that exists to stop it reports it clean

**REPRODUCED.** Cost to ship: a contract violation in the artifact the operator downloads.

`webui/src/pages/models/index.tsx:20` statically imports `./fixtures/models`, and
`webui/src/pages/models/fixtures/models.ts:17` exports `fixtureCatalog` as a **plain module
const with no `import.meta.env.DEV` guard inside it**. The other ten fixtures either load
dynamically or put the guard inside the fixture module (the pattern `accounts.ts:95-103`
documents in its own header), so the bundler drops them. This one has no guard to drop.

Built from `58e5802d` and grepped independently of any wall — **10 of 10 needles present**:

```
IN DIST "gpt-5.6-sol"        IN DIST "frontier reasoning, the pinned row"
IN DIST "deepseek-flash"     IN DIST "cheap builder on the local runtime"
IN DIST "kimi-k2.5"          IN DIST "window-only id the picker does not list"
IN DIST "gpt-5.6-mini"       IN DIST "extra window"
IN DIST "claude-deepseek"    IN DIST "prefix rule gpt-5.5"
```

Each of those strings appears in **no non-fixture source file anywhere under `webui/src`**
(checked with `grep -rl`), so the only path into the artifact is the fixture module.

And `fixture-leak.mjs` on the same tree printed, and exited 0:

```
webui/src/pages/models/fixtures/models.ts: checked 2 literal(s) of 10+ characters — 0 in dist
fixture-leak: 11 fixture(s), 91 literal(s) checked, 0 in dist
```

Still live at `f454037d`: `models/fixtures/models.ts` is byte-identical to the review head and
`models/index.tsx` still carries the static import at line 20.

Two other pages also import their fixture statically — `accounts/index.tsx:24` and
`doctor/index.tsx:19` — and are safe **only** because the guard sits inside the fixture module.
They pass the wall while violating the rule the contract states ("the fix at the source is
`await import(...)` inside the DEV branch"). They are one refactor away from F2.

---

## F3 — why the wall could never see it: its evidence corpus contains the files it is policing, and its needle regex is wrong in both directions

**REPRODUCED.** Cost to ship: the wall's green is worth nothing until both halves are fixed.

`dev/web-console/fixture-leak.mjs:88-96`. A fixture literal counts as evidence only if no
**other source file under `webui/src`** contains it — and that corpus includes **the other ten
fixtures**. Fixtures are copies of one another's sample data, so they cancel each other's
evidence. Every real string in `models.ts` was vetoed by a sibling fixture, and every one of
them is in dist:

| literal | vetoed as evidence by | in dist |
|---|---|---|
| `gpt-5.6-sol` | `settings/fixtures/settings.ts`, `usage/fixtures/usage.ts` | **yes** |
| `gpt-5.6-luna` | `settings/fixtures/settings.ts`, `usage/fixtures/usage.ts` | **yes** |
| `gpt-5.6-mini` | `usage/fixtures/usage.ts` | **yes** |
| `deepseek-flash` | four other fixtures | **yes** |
| `claude-kimi` | `compaction/fixtures/compaction.ts` | **yes** |
| `kimi-oauth` | `accounts/fixtures/accounts.ts` + two entity files | **yes** |

Change one line — exclude every `*/fixtures/*` file from the disqualifying corpus rather than
only the fixture under test — and the same script reports **13 leaked literals across 6
fixtures** instead of 0, on a checked denominator that rises from 91 to 128. (All 13 resolve to
the one module in F2; the other five fixtures are credited because they hold the same strings.)

**Second half, and it is worse than a count.** The needle regex
`/'((?:[^'\\\n]|\\.){10,})'/g` scans left to right without knowing which quote opens a string,
so on the dense one-line records these fixtures use, it pairs the **closing** quote of one
literal with the **opening** quote of the next. Two effects:

- It manufactures needles that are code, not data: 18 of the 91 reported as "checked" are
  strings like `", slot: null, context_window: 128_000, context_window_source: "`. For
  `models.ts` **both** of its two "checked" needles are of this kind — nothing real was ever
  searched, while the log line reads like a pass.
- It **swallows real literals**. An independent scanner that tracks open/close finds **at least
  44 literals in 7 fixtures that the regex cannot see** (12 in `models.ts`, 10 in `hero.ts`,
  9 in `doctor.ts`, 8 in `usage.ts`). Proof that this is not cosmetic: when I planted a needle
  in `label: 'sol'`, the quote pairing re-aligned and the wall immediately reported a
  **pre-existing** leak it had never been able to see — `"frontier reasoning, the pinned row"`.

**BLIND is reachable, is live today, and is not distinguishable from clean to a machine.**
`usage.ts` reports `BLIND — 0 literal(s) ... nothing could be checked` on the real tree right
now, because all 13 of its literals are shared with sibling fixtures. The script's own comment
says "a wall that cannot fail must never be read as a wall that passed" — and then **exits 0**.
The final summary line, `11 fixture(s), 91 literal(s) checked, 0 in dist`, does not mention that
one fixture contributed nothing. A gate leg that checks the exit status cannot tell BLIND from
clean; only a human reading the log can. The fix is one line: a non-empty-evidence failure, the
way the working-tree marker check added on 2026-09-18 already does for its own subject.

The red path does work, once a needle is genuinely unique AND reachable: planting
`zzz-unique-leak-needle-qq` inside `fixtureCatalog` gave `exit=1` and
`LEAK webui/src/pages/models/fixtures/models.ts: "zzz-unique-leak-needle-qq" ships in
webui/dist/index.html`. Note the first attempt — the same literal as a fresh unused `export
const` — stayed green and correctly so: rollup shakes an unused binding out, so the needle must
sit in a value shipped code reads.

---

## F4 — `dev/web-console/gate.mjs` reported 52 of 52 captures BLANK and exited 0

**REPRODUCED. Fixed in the working tree, not yet committed** — the live `gate.mjs` has gained a
`process.exit(1)`. Filed because two `done` rows were signed off against the version that could
not fail, and because the reproduction is the template for the next one.

At `58e5802d` the script ends (lines 217-219) by printing
`BLANK: N of M captures are >= 0.98 room colour:` and the file list to stderr, with no
`process.exit` and no `process.exitCode`. The header calls a blank capture "the failure this
gate exists to find".

I reproduced it without Chrome by copying `gate.mjs` into a scratch tree beside stubbed
`lib/cdp.mjs` and `lib/png.mjs` whose `colorFraction` returns `1.0`:

```
gate m1-probe: 52 captures in 0s -> .../gate/m1-probe
  BLANK: 52 of 52 captures are >= 0.98 room colour:
EXIT CODE = 0
```

Every page in the console failed to draw, in both rooms, at both frames, and
`node gate.mjs m1 && echo PASS` prints PASS.

---

## F5 — two `done` rows are verified by a command that cannot fail

**REPRODUCED.** Cost to ship: two milestone rows carry no evidence at all.

Both verify lines end `... >/dev/null 2>&1 || true`. In POSIX shell `A && B && C || true` groups
as `((A && B && C) || true)`, so the whole chain's status is discarded.

| row | status | verify |
|---|---|---|
| `M1-14` | `done` | `node dev/web-console/comp-check.mjs --help >/dev/null && ... \|\| true` |
| `M2-11` | `done` | `node dev/web-console/gate.mjs m1-preview --dry-run >/dev/null && ... \|\| true` |

Run verbatim in the pristine `58e5802d` clone, where `comp-check.mjs` **does not exist**:

```
ls: cannot access 'dev/web-console/comp-check.mjs': No such file or directory
M1-14 verify EXIT = 0
```

M2-11's chain includes the `gate.mjs` invocation from F4, so that row had two independent
reasons it could not fail. Both rows are still `done` with the same verify text at the current
ledger. A sweep of all 60 verify lines in `dev/campaigns/web-console.toml` found exactly these
two; nothing else in the campaign has this shape.

---

## F6 — the world wall catches the plain spelling of a violation and misses six others

**REPRODUCED**, latent (nothing in the tree uses the missed shapes today). Cost to ship: the
wall's green does not mean what M1-07..M1-10 were graded on.

`webui/tests/world.test.ts:141-151` matches imports with a **per-line** regex,
`/import\s*\{([^}]*)\}\s*from\s*'@shared\/ui'/`, and tokens with `/var\((--[a-z0-9-]+)/`.
Planted one at a time against a green baseline (the live tree, where M1-09 has landed):

| # | planted violation | wall |
|---|---|---|
| W1 | `var(--paper-0)` in a page stylesheet | RED |
| W2 | `import { Btn } from '@shared/ui';` | RED |
| W9 | `var(--paper-0)` inside a page `.ts` | RED |
| W10 | old primitive on a line that also holds other code | RED |
| **W3** | the same import written across **three lines** | **GREEN** |
| **W4** | `import { Btn as Button } from '@shared/ui';` | **GREEN** |
| **W5** | `import { Btn } from "@shared/ui";` (double quotes) | **GREEN** |
| **W6** | `import { Btn } from '../../shared/ui';` (relative path) | **GREEN** |
| **W7** | `var( --paper-0 )` (one space after the paren) | **GREEN** |
| **W8** | `import * as UI from '@shared/ui'; const x = UI.Btn;` | **GREEN** |

**Nothing else covers them.** I planted W3 as a *used* import (`export const MUT = Btn;`, so
`no-unused-vars` cannot take credit) and ran the other walls at the tip: `eslint src tests`
exits 0, and the world wall stays green. `pages → shared` is a legal FSD edge and
`src/shared/ui/index.tsx` is the sanctioned entry point, so `boundaries` has nothing to say.
Both quote styles and multi-line braces are what a formatter produces the day the import list
grows past the print width, which makes W3 the one to fix first.

The two denominators are sound and are correctly guarded: `oldTokens()` derives 124 names from
`git show main:webui/src/shared/tokens.css` minus what section 1 sanctions, `oldPrimitives()`
derives 12 from the barrel minus its world re-exports, and both have `expect(...).toBeGreaterThan(0)`
so a broken parse fails rather than passes. That part is right. It is only the *scan* that is
narrower than the thing it scans for.

One time bomb worth a line: `oldTokens()` shells out to `git show main:...`. The day `feat/v0.4.0`
merges to `main`, that command returns the **new** sheet, the old-token set collapses, and the
wall fails loudly on its own non-empty guard rather than passing silently. Loud is the right
failure, but someone will meet it at exactly the wrong moment.

---

## F7 — `webui/tests/labels.test.ts` passes while scanning zero files

**REPRODUCED.** Cost to ship: cheap to fix, and it is one rename away from being live.

`labels.test.ts:10` builds its denominator with
`import.meta.glob('../src/**/strings.ts', { eager: true })`, prints
`console.log(\`label wall: ${files.length} strings.ts file(s)\`)`, and then asserts only that
the findings array is empty. **There is no assertion that any file was found.** Point the glob
at a pattern that matches nothing and the wall is green with 0 of 33 files scanned.

Its sibling walls do guard this — `coverage.test.ts:63-65` asserts three denominator lengths
are `> 0`, `world.test.ts:130-131` asserts both sets are non-empty, `contrast.test.ts:241`
asserts `bothRooms.length > 20` — so this is an inconsistency with a named fix, not a design
choice. Breaking the coverage wall's page glob the same way correctly turns it **RED**.

| # | planted violation | wall |
|---|---|---|
| L1 | `tooLong: 'four words in here'` in a real `strings.ts` | RED |
| L2 | a literal em-dash in a real `strings.ts` | RED |
| L3 | `Cap: 'Capitalised'` in a real `strings.ts` | RED |
| **L4** | **the glob matches nothing** | **GREEN** |
| **L5** | a 5-word label produced by a **function** | **GREEN** |
| **L6** | an em-dash written as `&mdash;` | **GREEN** |
| **L7** | a literal em-dash in a page `.ts` that is not `strings.ts` | **GREEN** |

L5 is latent: `collectStrings` (`webui/src/shared/coverage/labels.ts:20-33`) returns early for
anything whose `typeof` is not string/object, so a function-valued label is invisible. No
`strings.ts` currently holds one (checked across all 33). The first `count: (n) => ...` label
will walk straight through.

Two further notes on the checker, both read: `STARTS_UPPERCASE` tests only the first character,
so `one Head running` is not "capitalised"; and the in-tree "the wall fails on its four planted
violations" test calls `checkLabels(badStrings)` **directly**, so it proves the checker and not
the glob. That is precisely the gap L4 walks through — the mutation proof and the wall under
test do not share a path.

---

## F8 — the em-dash copy gate has a seam exactly where the copy now lives

**REPRODUCED.** Cost to ship: low, but the gate is called "zero em-dashes in UI text".

`.rules/rules/webui-no-emdash-ui-text.yml` scans `webui/src/**/*.tsx` only. The campaign moved
every user-visible label into `strings.ts` — a `.ts` file. Coverage therefore lands like this:

| where the em-dash is | ast-grep | label wall | covered |
|---|---|---|---|
| JSX text or a string in a `.tsx` | RED | n/a | yes |
| a literal `—` in `strings.ts` | GREEN | RED | yes (by the label wall) |
| `&mdash;` in `strings.ts` | GREEN | GREEN | **no** |
| a literal `—` in any other page `.ts` (`model.ts`, fixtures, `coverage.ts` reasons) | GREEN | GREEN | **no** |

The ast-grep rule already handles `&mdash;`/`&#8212;`/`&#x2014;` in `.tsx` (added by the
2026-07-26 audit); the label wall's `EM_DASH` is the bare character only. Widening the rule's
`files:` to `webui/src/**/*.{ts,tsx}` closes rows 3 and 4 at once.

---

## F9 — the AA wall judges 11 of 25 colour tokens, and cannot follow a token a widget shadows

**REPRODUCED**, and — importantly — **no live AA failure was found.** Cost to ship: low; this is
a coverage statement, not a defect report.

`webui/tests/contrast.test.ts` is the best-built wall in the campaign: source-parsed token set,
a non-empty denominator assertion, and three in-file "the wall can fail" tests including a
threshold control. Three of four planted mutations go RED: dark `--ink` dropped to `#1a1a1a`,
light `--ink-mute` raised to `#F4F1E8`, and renaming the dark room selector so the parse target
vanishes (it throws, which is the right failure).

But `TEXT_ON` (lines 169-176, six pairs) and `PLANES` (lines 229-235, five pairs) **are hand
lists**, while the file's header says the token list is not one. Both statements are true of
different things: the *existence* check is source-derived; the *contrast* check is not. Derived
from the sheet, 14 of the 25 colour tokens declared in each room are named by no pairing:

```
--hairline  --hairline-strong  --strip-field-line  --bay  --bay-slot  --plate  --plate-line
--ghost  --edge-green  --edge-amber  --edge-red  --edge-grey  --scope-grid  --focus
```

`--plate` is the one that matters: `webui/src/widgets/rail/rail.css:46` paints the navigation
tabs with it and `rail.css:70-71` prints their labels in `var(--strip-ink)`, and no pairing
checks any ink against `--plate` in either room.

And a second, sharper problem found while measuring it: **`rail.css:24` redefines `--plate`**
as `color-mix(in srgb, var(--strip) 70%, var(--strip-ink-mute))`, shadowing the sheet's token of
the same name inside `.myx-rail`. So even adding a `--plate` pairing would judge a colour the
rail does not render. The wall parses `tokens.css` and evaluates hex only (`luminance` throws on
anything else), so a `color-mix()` ground is structurally outside it.

Measured both, to be sure this is coverage and not a live failure:

| | sheet `--plate` | `--strip-ink` on it | rail's shadowed `--plate` | `--strip-ink` on it |
|---|---|---|---|---|
| dark | `#C3B6A0` | 9.23:1 | `#b6b2a1` | **8.66:1** |
| light | `#D6D8D3` | 12.83:1 | `#c7c6c0` | **10.76:1** |

Both clear AA comfortably. The finding is that nothing in the tree knows that.

Three colour tokens are defined outside `tokens.css` and are therefore outside every wall's
denominator: `rail.css:24 --plate`, `rail.css:25 --plate-hover`, `board.css:20 --plate-deep`.
Each is documented in place with the comp measurement that justifies it, so the intent is sound;
the gap is that the contract's token census cannot see them.

---

## F10 — `gate.mjs` derives its addresses from the source and its fixtures from a hand list

**REPRODUCED.** Cost to ship: low today, certain the day a fourteenth address lands.

`addresses()` (`gate.mjs:56-60`) parses `ADDRESSES` out of `webui/src/app/rows.ts` — the header
says so, and says why. `FIXTURES` (`gate.mjs:35-49`) is a 13-key object literal, and line 180
does `urlFor(capture.address, FIXTURES[capture.address])`. Add a fourteenth address to `rows.ts`
and the dry run says:

```
addresses (14): fleet playground turns sessions teams projects accounts usage settings models logs compaction mcp doctor
fixtures: compaction demo hero models settings usage   live (no fixture): fleet mcp
```

The new address is captured — at `http://localhost:5173/#/playground?fixture=undefined` — and
appears in neither the fixtures line nor the live line, so the run's own summary does not show
that anything is unaccounted for. The denominator is half source-derived; the half that decides
what each capture actually renders is the hand list.

The working-tree edit to this file (2026-09-18, uncommitted) rewrites the FIXTURES values after
discovering that four of them named a fixture that does not exist on disk, which the dynamic
import's `.catch` swallowed, leaving those captures showing **live daemon data that looks
exactly like a working capture**. That is the same class of defect found independently, and it
is the strongest argument for deriving the map from the `fixtures/` directory listing rather
than maintaining it.

---

## F11 — `comp-check.mjs`

Read only; not runnable here (it needs Chrome over CDP and a dev server). Two observations:

- Its exit path is correct: `comp-check.mjs:421` is
  `process.exit(failures.length === 0 ? 0 : 1)`, and it prints `FAIL <name>` per failure. It can
  fail. `--list` runs and emits 130+ constant names starting `rail.x`, so the binary works.
- It was **untracked** at the review head `58e5802d` — M1-14 is `done` and its deliverable was
  in no commit, which is exactly what F5's verify line made possible. It is tracked at the tip.

---

# DUTY TWO — the seams a design review cannot see

## F12 — the live event stream never reconnects after the operator unlocks

**REPRODUCED.** Cost to ship: on a fresh install, the console's live feed is dead until the
operator reloads the page, and nothing says so.

`webui/src/entities/events/api/index.ts:88-96` — with no key in storage, the loop sets
`running = false`, `status: 'off'` and returns. Its comment says "a caller connects again after
the operator pastes one." **There is no such caller.** `connect()` is exported and has exactly
one call site: `webui/src/widgets/rule/index.tsx:163`, inside `useEffect(..., [])`. The Rule is
the shell chrome that mounts once and outlives every page, so that effect never runs again.
`storeKey()` (`webui/src/shared/api/index.ts:26-33`) clears the lock and re-arms the pollers;
it does not touch the stream.

Driven directly, with `fetch` and `localStorage` stubbed:

```
first boot, no key:      after connect()   status=off,  fetches=0
                         after storeKey()  status=off,  fetches=0      <- expected 'live'
stale key, stream 401s:  after the 401     status=off,  fetches=1
                         after storeKey()  status=off,  fetches=1      <- expected 'live'
```

Both paths end with `running === false` and no further network activity. The pollers recover;
the stream does not, so the rule prints `off` and every entity wired to live frames
(`widgets/rule/wire.ts`) silently stops refetching while the rest of the console looks healthy.
The second path is the answer to the packet's question directly: **`noteUnauthorized()` sets a
lock that `storeKey()` clears for `request<T>` but not for the stream that set it.**

Cheapest fix: have `storeKey()` (or the session's `unlock`) call `connect()` — it is already
idempotent and documented as such.

## F13 — `canonicalHash` lets a malformed hash through, via a prototype key

**REPRODUCED.** Cost to ship: low — a garbage address bar, no crash and no injection — but the
packet asked whether a hostile hash can get through, and it can.

`webui/src/app/rows.ts:95` does `LEGACY_ADDRESS[slug]` on a plain object literal with a
user-controlled `slug`. `Object.prototype` members answer:

```
canonicalHash("#__proto__")              = "#/[object Object]"
canonicalHash("#constructor")            = "#/function Object() { [native code] }"
canonicalHash("#/__proto__?fixture=demo") = "#/[object Object]?fixture=demo"
addressOf("__proto__")                   = [object Object]      (used as an Address)
```

`webui/src/app/index.tsx:16-17` feeds `window.location.hash` straight in and then
`window.location.replace(canonical)`, so `#__proto__` writes that into the address bar.
`addressOf` (`rows.ts:100-104`) has the identical lookup and returns `Object.prototype` where an
`Address` is declared, which then indexes `PAGE_ROW` and `pageModuleKey`. It converges back to
`fleet` on the next pass and does not loop, because `canonicalHash` runs once at boot and a
fragment-only `location.replace` does not reload. `tostring`, `valueof` and `hasownproperty`
are safe — only the properties whose own-name matches survive `toLowerCase()`.

The slug side is a closed allowlist and is correct. **The query side is not validated at all:**
`rows.ts:91-92` takes `rawHash.slice(rawHash.indexOf('?'))` verbatim and concatenates it onto
the canonical address, including onto a *rejected* slug — `#/nowhere?anything` becomes
`#/fleet?anything`. Nothing currently treats the query as anything but a fixture name, so this
is a robustness note rather than a live defect, but it is the half with no check in it.

One line fixes the reachable half: `Object.prototype.hasOwnProperty.call(LEGACY_ADDRESS, slug)`,
or make the two tables `Map`s. `webui/tests/router.test.ts:52-84` covers 16 hash shapes and none
of them is hostile; that is where the regression test belongs.

## F14 — `app → widgets` was necessary; the misplaced file is `widgets/rail`, and harmlessly so

Read, with the imports enumerated. **Not a defect — this is the answer to the question asked.**

`cf37417b chore(webui): let the app layer import widgets, the FSD shell needs rail and rule`
added `'widgets'` to the `from: 'app'` allow list in `webui/eslint.config.mjs:50`. In canonical
FSD the layer order **is** app > pages > widgets > features > entities > shared, so the edge is
standard and its absence was the bug, not its presence. `src/app/` uses it for exactly two
imports, `App.tsx:16-17` (`Rail`, `Rule`), and for nothing else.

`widgets/rule` earns the edge on its own: it imports nine entities (`account`, `auth`, `config`,
`control-status`, `events`, `heads`, `perf`, `session`, `usage`). `widgets/rail` does not — it
imports only `@shared/lib` and `@shared/ui`, which by the layer's own definition makes it a
shared component, not a widget. So the honest answer is: the edge was needed regardless, `rule`
justifies it, and `rail` is filed one layer too high without consequence. Nothing else in the
tree reaches through the new edge.

## F15 — `exactOptionalPropertyTypes`: five props opt out of it deliberately, and the tree carries two idioms for the same problem

Read, both sides enumerated. **Not a defect; a consistency note.**

`tsc --noEmit` is clean at the tip, so no caller passes `undefined` to a prop that forbids it.
The props that *accept* `undefined` are declared `?: T | undefined`, which is the shape that
turns EOPT off for that prop — five of them, all in the new controls, all with the reason
written above them (`key.tsx:35-39`: "a composing control must be able to forward its own
optional prop through"):

| declaration | callers that pass `undefined` |
|---|---|
| `shared/controls/key.tsx:36-39` `disabled`, `busy`, `ariaLabel`, `className` | `shared/controls/confirm.tsx:45` |
| `shared/controls/choice.tsx:43` `listId` | `shared/controls/choice.tsx:206` |
| `shared/controls/confirm.tsx:31,59` `busy` | forwarding |

`shared/ui` has none. The alternative idiom — which keeps the guarantee — is already used five
times in the tree: `{...(onOpen === undefined ? {} : { onOpen })}` at
`widgets/account-strip/index.tsx:70`, `widgets/head-strip/index.tsx:92`,
`pages/logs/index.tsx:112`, `pages/sessions/index.tsx:179`, `pages/usage/index.tsx:248`.
Worth picking one, since the declared-`| undefined` form will also compile the accidental
`undefined` it was not written for.

---

# DUTY THREE — checks that cannot fail

A mechanical scan of all 29 test files (`weak-tests.mjs`: bodies with no `expect`, bodies whose
every assertion is `toBeDefined`/`toBeTruthy`/`toBeFalsy`/`not.toThrow`, and assertions whose
expected side is the component's own strings table) found **almost nothing**: zero tests with no
assertion, zero label tautologies, and one test whose only assertion is `not.toThrow` —
`webui/tests/theme.test.ts:97 'a storage that refuses writes does not throw'`, where that is the
correct assertion for the behaviour. There are no snapshots in the tree. By the usual measures
this suite is in good shape.

The checks that cannot fail here are structural, and each is filed above:

1. **F5** — `M1-14` and `M2-11`: the verify line's exit status is discarded by `|| true`.
2. **F4** — `gate.mjs`: 52 of 52 blank, exit 0.
3. **F3** — `fixture-leak.mjs`: BLIND exits 0; two fixtures check nothing real, one of them
   silently.
4. **F7** — `labels.test.ts`: green on an empty denominator.
5. **The mutation proofs prove the checker, not the wall.** `coverage.test.ts:70` and
   `labels.test.ts:24` are named "the wall fails on its N planted violations" and they call
   `checkCoverage` / `checkLabels` **directly on a fixture**. The real test reaches the same
   function through `import.meta.glob`. A green proof therefore says nothing about the glob —
   which is exactly the path F7 walks through. Both of those proofs would keep passing with the
   denominator empty.

And one finding of its own:

## F16 — the static-import rule is enforced for one page, by asserting on that page's source text

**READ**, and it is the direct cause of F2.

`webui/tests/team-board.test.ts:151-173`, `describe('the fixture stays out of a production bundle')`:

```js
const source = read('webui/src/pages/teams/index.tsx');
expect(source).not.toMatch(/^import .*from '\.\/fixtures\/hero'/m);
expect(source).toContain("import('./fixtures/hero')");
expect(source).toContain('if (!(import.meta.env.DEV &&');
```

Its own comment records the history: "Measured: the comp's words were in dist/index.html until
this import became a dynamic one." The rule was learned, the page was fixed, and the check was
written **against that one page by name**, in a test file named after a widget. Three other
pages import their fixture statically (`models`, `accounts`, `doctor`) and no equivalent
assertion exists for any of them — which is how F2 shipped.

Two problems, in order of cost:

- The denominator is one hand-named page. The `fixtures/` directory listing is right there and
  is what `fixture-leak.mjs` already enumerates from; this check should iterate it.
- The mechanism is a source-text match on a string the page is free to change. Double quotes, a
  `/* @vite-ignore */` comment between the tokens, or prettier moving the parenthesis all break
  it without any behaviour changing — and none of it proves anything about the bundle. The
  assertion that means something is the one `fixture-leak.mjs` makes about `dist`, once F3 is
  fixed.

---

# DUTY FOUR — what the sweep left behind (the denominator for M3-04)

Enumerated from the source with an independent parser (`census2.mjs` + `reach.mjs`), not with
the world wall's regexes: whole-file import matching, multi-line braces, aliases, both quote
styles, relative paths and namespace imports. **Nothing here is fixed by this row.**

**Old primitives.** 12 in the barrel: `Panel`, `StatusPill`, `Metric`, `Btn`, `Field`, `Well`,
`EmptyState`, `ErrorNote`, `SkeletonRows`, `Stale`, `MeterBar`, `ConfirmBtn`. **21 import sites,
all 21 inside the CONTRACTS.md section 7 deletion list**, in six files:

```
src/features/edit-config/index.tsx:4    ErrorNote, Field, StatusPill
src/features/unlock-mgmt/index.tsx:3    Field, Well          (+ Btn)
src/features/refresh-auth/index.tsx     Btn
src/pages/auth/index.tsx:3              ErrorNote, Metric, Panel, SkeletonRows, StatusPill, EmptyState
src/pages/burn/index.tsx:14             EmptyState, Stale, ErrorNote
src/pages/config/index.tsx:3            Panel, SkeletonRows, ErrorNote
src/widgets/head-plate/index.tsx:5      ConfirmBtn, ErrorNote, Metric, MeterBar, SkeletonRows, Stale, StatusPill
```

Zero outside the list. None of the twelve is dead — each still has at least one importer, all of
them retired files — so all twelve die with those six files and not before.

**Old tokens.** 124 names `main`'s sheet defines that section 1 does not sanction. **72 uses,
every one of them in `src/shared/ui/ui.css` between lines 6 and 217**, which is the wall's
documented exemption (above the first `.myx-strip` rule). Zero old-token uses anywhere else in
`webui/src`. The sweep is genuinely complete outside its stated exemptions.

**The one retired module that still renders.** Of the eight retired directories, exactly one is
imported by code that is not itself retired:

```
src/app/App.tsx  imports  @features/unlock-mgmt
```

That is the management-key modal — the first surface an operator sees on a fresh install, before
any data loads — and it is built from `Btn`, `Field` and `Well`, three old-world primitives
(`features/unlock-mgmt/index.tsx:5`). The world wall exempts it by name, so no wall will ever
report it, and a design review that looks at the console after unlocking will never see it.
**M3-04 cannot simply delete this one; it has to be rebuilt on the world primitives.** The other
seven are reachable from nothing and can be deleted outright.

**What the retired surfaces still cost in the artifact.** Built from `58e5802d` and probed with
literals unique to each retired module (corpus excluding all retired files, so siblings cannot
veto one another): six of the eight still ship in `webui/dist/index.html`.

| retired module | unique literals | in dist | example |
|---|---|---|---|
| `src/pages/burn` | 7 | **6** | `"myx-burn-ceiling"` |
| `src/pages/auth` | 2 | **2** | `"credentials present"` |
| `src/features/refresh-auth` | 2 | **2** | `"token refreshed"` |
| `src/features/edit-config` | 1 | **1** | `"no changes to apply"` |
| `src/pages/config` | 2 | 1 | a fragment, weak evidence |
| `src/widgets/fleet-banner` | 1 | 1 | `"myx-ink-amber"`, may come from CSS |
| `src/widgets/head-plate` | 2 | 0 | not in dist |
| `src/features/unlock-mgmt` | 0 | — | no evidence left; it ships, it is imported by the shell |

They ride in because `src/app/pages.ts:20` globs `../pages/*/index.tsx` and
`vite-plugin-singlefile` inlines every chunk into the one HTML file, so "lazy" does not mean
"absent". Expected before M3-04, and worth the number.

## F17 — the operator's real home paths are committed in fixtures

**REPRODUCED**, low cost, easy to forget. 26 occurrences of `/home/marcos/...` in tracked source:

```
webui/src/pages/projects/fixtures/list.ts   15   (also names qgre, grailseeker-bot, hostshield)
webui/src/pages/sessions/fixtures/board.ts   9
webui/src/pages/logs/fixtures/tail.ts        1
webui/src/pages/projects/index.tsx:87        1   (a comment; harmless)
```

plus four test files and one Kotlin test. None of it reaches `dist` (0 occurrences in the built
artifact), so this is a source-tree concern for a repo with a public remote, not an artifact
leak. A `/home/operator/...` placeholder costs nothing.

## F18 — `eslint-plugin-boundaries` is running on a deprecated config shape

**REPRODUCED**, informational. `npm run lint -w webui` is clean but prints, twice:

```
[boundaries][warning]: [boundaries/entry-point] The 'rules' option is deprecated. Please use 'policies' instead.
[boundaries][warning]: Detected legacy string element selectors in 4 policies at indices: 0, 1, 2, 3.
```

The architecture wall — the one the config header calls "toolchain-enforced, not documented" —
is on a v5/v6 compatibility path in a v7 plugin. Warnings today; a silently-not-applied policy
the day the compatibility shim goes. Cheap to migrate now, while the file is small.

## F19 — the world wall was red at the review head; it is green at the tip

Not a defect, recorded so the ledger's account and the tree agree. At `58e5802d`,
`webui/tests/world.test.ts` **failed** with 24 findings, all in `src/pages/*` — the pages sweep
had not landed. The orchestrator knew (`bb91cf34 chore(ledger): keep the still-red world wall
out of M1-09's suite leg`). `cac2adce feat(webui): M1-09 sweep the thirteen pages into the strip
world` closed it, and the wall is green at `f454037d`. Worth stating plainly: **there is no
commit between `0545f870` and `58e5802d` in which the world wall was green**, so every row
graded before `cac2adce` was graded with that leg excluded.

---

# The fix list, ordered by what it costs to ship

| # | fix | why it is where it is |
|---|---|---|
| 1 | **F1** `ui.css:350` — move `font-variant-numeric` inside `.myx-sfield-figure` | the build is red; two rules are silently dropped in dev |
| 2 | **F1b** add a CSS-parse wall: `lightningcss.transform` over every `webui/src/**/*.css` in a vitest file | nothing in the tree parses page CSS; it is the wall that would have caught #1 |
| 3 | **F2** guard `models.ts`'s `fixtureCatalog` the way `accounts.ts:95` guards its own, or make the import dynamic | fixture bytes are in the artifact today |
| 4 | **F3a** exclude every `*/fixtures/*` file from `fixture-leak.mjs`'s disqualifying corpus | one line; turns 0 leaks into 13 and would have caught #3 |
| 5 | **F3b** make BLIND (and a fixture with no real evidence) exit non-zero | a gate cannot currently tell BLIND from clean |
| 6 | **F3c** replace the needle regex with an open/close scanner | it manufactures 18 fake needles and hides at least 44 real ones |
| 7 | **F5** delete `\|\| true` from `M1-14` and `M2-11`, re-run both | two `done` rows carry no evidence |
| 8 | **F4** keep the working-tree `process.exit(1)` in `gate.mjs` and commit it | already fixed, not yet landed |
| 9 | **F16** iterate the `fixtures/` listing instead of naming `teams`; assert on `dist`, not on source text | the per-page check is why #3 shipped |
| 10 | **F6** scan whole files in `world.test.ts`: multi-line braces, aliases, both quotes, relative paths, namespace imports, `var(\s*--` | six shapes no wall in the tree can see |
| 11 | **F7** add `expect(files.length).toBeGreaterThan(0)` to `labels.test.ts:22` | one line; its three siblings all have it |
| 12 | **F12** call `connect()` from `storeKey()` / `unlock()` | the live stream is dead after every first-boot unlock |
| 13 | **F8** widen `webui-no-emdash-ui-text` to `*.{ts,tsx}`; add the entity forms to `labels.ts` | the copy gate does not cover where the copy lives |
| 14 | **F13** `hasOwnProperty` guard (or `Map`) in `rows.ts:95` and `rows.ts:103`; a hostile-hash case in `router.test.ts` | `#__proto__` reaches the address bar |
| 15 | **F10** derive `gate.mjs`'s `FIXTURES` from the `fixtures/` listing; fail on an address it has no entry for | a 14th address is captured at `?fixture=undefined` |
| 16 | **F9** add `--plate`/`--bay` pairings, and either stop shadowing `--plate` in `rail.css:24` or give the shadow its own name | 14 of 25 colour tokens unjudged; no live failure |
| 17 | **F17** placeholder paths in the four fixtures | 26 real home paths in tracked source |
| 18 | **F15** pick one EOPT idiom | the tree carries both |
| 19 | **F18** migrate `rules:` → `policies:` in the boundaries config | compatibility path under the architecture wall |
| 20 | **F14** `widgets/rail` belongs in `shared/ui`; the `app → widgets` edge stays | answer to the question, no action required |

Not filed as defects, stated because the packet asked: the `app → widgets` edge was necessary
(F14); `exactOptionalPropertyTypes` has no violating caller (F15); the AA pairings that exist
all pass in both rooms (F9); the sweep left nothing outside its stated exemptions (Duty Four);
and the test suite has essentially no weak assertions (Duty Three).

The one sentence this review comes down to: at `f454037d`, **537 tests pass, ast-grep is clean,
eslint is clean, tsc is clean, and `npm run build -w webui` does not complete** — while the wall
written to keep fixture bytes out of the artifact reports clean on an artifact that contains
them. Every one of those greens is true. None of them was measuring the thing that broke.

---

## Appendix — the harness

Written to `~/.cache/qgre/scratch/claude-1000/m1-18-review/harness/`, run against clones outside
the worktree. Nothing in the tree was modified.

| script | what it plants / measures |
|---|---|
| `runner.py` | snapshot / plant / run / restore, for any wall |
| `mutate_world.py` | the 10 world-wall mutations (F6) |
| `mutate_labels_coverage.py` | the 7 label, 3 em-dash and 2 coverage mutations (F7, F8) |
| `mutate_contrast2.py` | the 4 contrast/plane mutations (F9) |
| `mutate_fixture_leak2.py` | the LEAK red-path proof (F3) |
| `evidence2.mjs`, `why-models.mjs`, `pairing.mjs` | the fixture-leak denominator, the veto chain, the swallowed literals (F2, F3) |
| `leak-corrected.mjs` | the same wall with the corpus fixed: 13 leaks, not 0 |
| `retired-in-dist.mjs` | which retired surfaces still ship (Duty Four) |
| `census2.mjs`, `reach.mjs` | the old-world census and reachability (Duty Four) |
| `contrast-coverage.mjs`, `ratio.mjs`, `mix.mjs` | which colour tokens are judged, and the shadowed `--plate` (F9) |
| `weak-tests.mjs` | the Duty Three scan across all 29 test files |
| `ink-on-unjudged.mjs` | rules painted with tokens no pairing judges (F9) |
| gate probe | `gate.mjs` beside stubbed `lib/cdp.mjs` / `lib/png.mjs`, every capture blank (F4) |

Two harness bugs of my own, recorded so the numbers can be trusted: the first clone symlinked
the **main checkout's** `node_modules`, which lacks this branch's dependencies and produced 22
phantom `TS2307` errors — repointed at the worktree's, after which `tsc` is clean at both SHAs;
and the first contrast mutation pass edited the token sheet's header **comment** instead of the
rule, and its three GREENs were meaningless. Both were re-run after the fix and only the
corrected runs are reported above.
