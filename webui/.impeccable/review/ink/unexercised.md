# THE SEVENTY-THREE THAT NEVER RENDERED (M1-80)

M1-52 enumerated 187 colour rules **from the source** and dispositioned 114 of them from a
measurement. The other 73 had never rendered in any capture, so nothing could be said about them.
A rule that never renders is not thereby safe: both known D7 instances were found **by eye** before
any instrument caught them, and instance 9 was *predicted* by D7's own comment in `controls.css:91`
— *"Nothing had put an Input on a strip yet; the page sweep will."*

Every one of the 73 now ends in one of three states with a reason written down. **Absence is not a
disposition**, and the generator fails the row by name for any rule in none of the three.

| state | count | meaning |
|---|---:|---|
| EXERCISED | 39 | something reaches it; its measured ratio against its real composited ground is on the record |
| DEFERRED | 33 | reachable, but what it needs is a real piece of work, and that work is named |
| DEAD | 1 | nothing builds it, with the evidence written down |

The single DEAD rule is `.myx-acct-kind` (`account-login.css:24`). It has **zero** literal
occurrences in any `.ts`/`.tsx` under `src` or `tests`; it is not producible by any of the eight
prefixes this tree assembles (none of them is `myx-acct-*`); and `account-login/index.tsx` spells
every sibling class literally — `myx-acct-btn`, `-ticket`, `-link`, `-code`, `-form`, `-row` — so
that file does not assemble names at all. It is a rule the component was refactored away from. The
deletion is outside this row's fence, so it is recorded as a note rather than taken.

The record is `unexercised-dispositions.json`; the instrument is `exercise.mjs`; its raw output is
`exercised.json`.

## The result that matters: nothing new fails the bar

**Zero of the 39 newly-exercised rules fall under their WCAG bar.** The worst are
`.myx-palette-group [cmdk-group-heading]` and `.myx-palette-mark` at 5.13:1 and
`.myx-palette-input::placeholder` at 5.49:1, all clear of 4.5. No tenth D7 instance appeared among
the rules that reached the sweep for the first time.

One pair is worth naming even though it passes. `.myx-field-label` (`ui.css:113`) puts the **room**
ink `--ink-mute` on the modal's **paper** at 5.13:1. That is D7's exact shape — an ink measured
against a plane it does not belong to — passing the bar rather than failing it. It is recorded here
rather than fixed, because a passing pair is not a defect and the plane mismatch is still a fact
about the sheet.

## Why a probe by selector, and not the text-node sweep

`probe-ink.mjs` walks text nodes and attributes each to a rule through its class chain. That is the
right instrument for *what does the operator read on this page*, and the wrong one here, because it
can only see a rule that already renders. This asks the opposite question — **given a rule, can
anything in the console make it paint?** — so it starts at the selector, finds the element, forces
whatever state the selector demands, and measures.

Two corrections were needed before its numbers could be trusted, and both are the failure this row
exists to remove:

1. **Attribution.** The first run reported the bare `a` rule at **1.21:1** on a rail tab, which read
   like a tenth D7 instance. It was the probe, not the console: `a { color: var(--ink) }` does win
   on `.myx-rail-tab`, but the only text beneath it lives in `.myx-edge-label`, which overrides to
   `--strip-ink` — near-black on the plate, exactly as M1-36's crops show. The probe was measuring
   `textContent`, which reaches into descendants painted by a different rule. A rule is now credited
   only for a text node whose parent still computes the **same** colour; one that paints none is
   recorded `inherited-then-overridden`, which is neither fine nor dark.
2. **Form controls** paint their own value, and that value is not a text node, so `.myx-fbox-input`
   and `.myx-lt-field input` were being dropped silently. The control is now its own painter.

## The opener flag, withdrawn

An earlier pass reported that four openers `NEVER OPENED`. That was a false alarm and is withdrawn.
The flag read the DOM synchronously on the line after `.click()`, before React re-rendered; the
gestures had worked, and rules reachable only through them were being measured moments later with
those openers' names attached. The gesture and its proof are now separate and the proof is polled.
The flag would have printed the same words whether a gesture missed or the wait was too short —
law 34 in miniature, which is why it is polled rather than sampled.

## The management key was a fixture axis nobody had turned

`features/unlock-mgmt` renders its modal **only when the console has no management key**. Every
instrument in this campaign seeds a valid one before boot, so `.myx-modal-title`,
`.myx-modal .myx-field-label`, `.myx-field-label` and `.myx-fault-message` could not have rendered
in *any* capture ever taken here. Not a dead rule and not a missing fixture — a fixture that was
always seeded past. The unkeyed pass exists to reach exactly that surface, and all four pass.

## What did not turn out to be dead

The first cut of this row proposed ten rules as DEAD. **Eight of them were wrong**, and the reason
is a blind spot worth recording, because it is this campaign's own law turned on itself.

- **alerts (4) and budgets (4)** are finished features that nothing mounts. `AlertsPanel` and
  `BudgetsPanel` are each defined once and referenced nowhere — which reads exactly like dead code.
  They are not: M2-07's mounting is a one-line handoff on M2-06, M2-06 is done, and the note was
  never written. Two complete features have been invisible because a handoff nobody owned did not
  happen. M1-96 writes the mount; the census drops by eight for an honest reason instead of by
  deletion.
- **`.myx-pill-mute`** is **assembled**, not spelled: `shared/ui/index.tsx:29` builds
  ``cx('myx-pill', `myx-pill-${tone}`)``. A grep for a literal class name cannot see a class name
  this tree constructs, and that hole would have miscounted every composed rule the same way.

**So the denominator was audited for that hole.** Every prefix this tree can assemble was
enumerated — the literal token before *any* interpolation in a template literal, not only one
anchored at the backtick, which is how the first pass missed `myx-schart-` and `myx-swatch-`. There
are **eight** such prefixes (`myx-board-strip-`, `myx-btn-`, `myx-edge-`, `myx-ink-`,
`myx-meter-fill-`, `myx-pill-`, `myx-schart-`, `myx-swatch-`), and **exactly one of the 73** names a
class any of them can produce: `.myx-pill-mute`. No class in this tree is built suffix-first. **The
hole is one rule wide, and the 73 stands.**

Following that assembly to its producer sharpens the verdict rather than reversing it:
`head-plate/index.tsx:16` is the only site in the tree that returns `'mute'`, and `HeadPlates` is
placed nowhere — its one mention outside its own directory is a directory *name* in
`world.test.ts:88`. So `.myx-pill-mute` is dark for the same reason alerts and budgets are: an
unmounted component, not an absent rule.

## The honest shape of the remainder

The 33 DEFERRED are not mostly a fixture gap. Most are components the console **builds and never
places**, which no fixture can reach:

| what | rules | evidence |
|---|---:|---|
| finished but unmounted (alerts, budgets, head-plate) | 12 | zero importers / zero JSX sites |
| exported and placed nowhere (`Panel`, `EmptyState`, `Input`, `Choice`) | 8 | zero JSX sites in src or tests |
| placed, but needs a state or payload no fixture supplies | 13 | named per rule in the JSON |

`Input` and `Choice` are the ones to read twice. D7 wrote in 2026 that *"Nothing had put an Input on
a strip yet; the page sweep will."* The page sweep has now run, and the answer is that **still
nothing does** — both are exported by the controls barrel with zero JSX sites anywhere. The
prediction was right about the danger and wrong about the timing, and the rule is still waiting.

## What this row does not do

It does not chase 73 to 0, and it deletes nothing. Every deletion candidate here turned out to be an
unperformed handoff or an unplaced component, and both are decisions above this row: routing them as
rows is what made the correction possible at all. Had the first cut deleted, two finished features
would be gone and nobody would have known why the coverage wall broke.
