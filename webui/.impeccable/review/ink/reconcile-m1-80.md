# RECONCILING M1-80'S 73, ONCE, WITH A DATE ON IT (M1-104)

M1-80 dispositioned the gap as **73 rules: 39 EXERCISED / 33 DEFERRED / 1 DEAD**. That record is
correct and it is a *reading of a tree*. This is the one reconciliation, and it exists because the
tree has moved under it — not because any of its numbers were wrong.

**The tree this reconciliation was read from:** HEAD `fdfe5bdd`, **dirty** — 2 CSS files differ from
HEAD (`pages/fleet/fleet.css`, `widgets/team-board/board.css`) and 2 other files differ
(`pages/fleet/index.tsx`, `pages/usage/index.tsx`). `fleet.css` and `fleet/index.tsx` are M1-102's,
uncommitted because that row closed minutes ago; the other two are another seat's work in flight.

**This document has already been overtaken once, and that is recorded rather than tidied.** The first
reading was taken at HEAD `98fdba6b` (14:47Z), where **five** CSS files were dirty and the fourth row
of the table below read *"deleted in the working tree only"*. Between that reading and this one the
deletion landed at `fdfe5bdd`. A claim that was true for ten minutes is now false, and it changed
without anyone deciding anything — which is the entire argument for the stamp, demonstrated on this
file before it was even finished.

## What the denominator is today

    bun webui/.impeccable/review/ink/sweep-d7.mjs /tmp/d7-m1104.json

    DENOMINATOR: 181 `color: var(--token)` rules across 38 CSS files
    TREE: HEAD 98fdba6b, dirty — 5 css file(s) differ, 5 other file(s) differ
      UNRESOLVED       69
      RENDERED         66
      SAME-RULE        28
      NOT-AN-INK-ROLE  18

**So the gap is 69, not 73.** And it was not 73 at any two moments this session: the same sweep
returned **189 / 73**, then **186 / 70**, then **181 / 69**, minutes apart, because live seats were
editing CSS. Every one of those readings was correct. Only the first two are unreadable now, because
neither said which tree it read — which is the whole reason `tree-state.mjs` exists.

## The four rules that moved, and what happened to each

Identified by `file` + `selector` + `ink` rather than by `file:line`, because a line number moves
when anyone edits above it and would read as a rule vanishing when it had only shifted.

| rule (M1-80's line) | selector | ink | M1-80's state | at HEAD? | now | what happened |
|---|---|---|---|---|---|---|
| `features/account-login/account-login.css:24` | `.myx-acct-kind` | `--ink-mute` | **DEAD** | gone | gone | **deleted and landed.** M1-87 took it — the row routed from M1-80 because the deletion was outside M1-80's fence. |
| `features/views/views.css:56` | `.myx-views-field-label` | `--ink-mute` | EXERCISED | gone | gone | **deleted and landed** — but at 14:47Z, ten minutes before this reading, it was deleted in an UNCOMMITTED working tree only. See the note above. |
| `shared/ui/ui.css:145` | `.myx-empty` | `--ink-mute` | DEFERRED | gone | gone | **deleted and landed.** `ui.css` is in M1-93's fence. |
| `shared/ui/ui.css:19` | `.myx-panel-title` | `--ink-mute` | DEFERRED | gone | gone | **deleted and landed.** M1-93. |

**Zero rules changed kind.** 69 of M1-80's 73 are still UNRESOLVED exactly as it left them; 0 moved
from UNRESOLVED to RENDERED, 0 to SAME-RULE, 0 to NOT-AN-INK-ROLE. Every movement is a deletion.

Reproduce each row:

    git show HEAD:webui/src/features/account-login/account-login.css | grep -c '^\s*\.myx-acct-kind[ ,{]'   # 0
    git show HEAD:webui/src/features/views/views.css                 | grep -c '^\s*\.myx-views-field-label[ ,{]'  # 0 today; 1 at 98fdba6b
    git show HEAD:webui/src/shared/ui/ui.css                         | grep -c '^\s*\.myx-empty[ ,{]'         # 0
    git show HEAD:webui/src/shared/ui/ui.css                         | grep -c '^\s*\.myx-panel-title[ ,{]'    # 0

## The finding that only the stamp could produce

**All four are landed as of `fdfe5bdd` — and at `98fdba6b`, ten minutes earlier, one of them was
not.** `.myx-views-field-label` read "deleted" only in a working tree that had not been committed.
A reader comparing EITHER output to M1-80's record would have drawn the same conclusion — the rule
has been removed — when at one of the two trees what had actually happened is that a seat had it
half-edited. Those two call for different responses: a landed deletion is a fact about the console,
an uncommitted one is a fact about an afternoon, and only one of them is safe to build on.

The stamp is what makes that difference readable, and this file is its own evidence: written at one
tree and overtaken by another before it was finished. Two readings of the same sweep, minutes apart,
identical in every number that mattered and different in the one fact a reader would have acted on.

## What this does and does not say about M1-80's record

- M1-80's dispositions are **not** amended. Its 39/33/1 was true at its tree; this file is an
  addendum with a date, not a correction of its arithmetic.
- Three of the rules it dispositioned no longer exist, so its counts cannot be quoted as a property
  of the console. Quoted beside this stamp, they can.
- **The DEAD call M1-80 could not take was right.** It found `.myx-acct-kind` unreachable, said the
  deletion was outside its fence, and routed it rather than taking it. M1-87 deleted it. Its single
  DEAD disposition was correct and is now acted on.
- The remaining 69 are unchanged, so the honest size of the light gap is still 34 rules
  (33 DEFERRED + 1 DEAD) — see `light-census.md`, which was read at the same tree state.
