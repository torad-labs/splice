# proxy-hardening — campaign assets

Everything this campaign owns, in one place. The goal it serves, in the operator's words:
**a proxy that never fails, self-heals, and properly just works.**

```
dev/campaigns/
  proxy-hardening.toml          ← THE LEDGER (stays flat — see "Why the ledger is not in here")
  proxy-hardening/
    README.md                   ← you are here
    research/                   provenance: the audit the 83 items derive from
    walls/                      enforcement: the gate, the registry, the wall scripts
    oracle/                     verification: the frozen migration oracle
```

## Why the ledger is not in here

`manifest.py` finds sibling ledgers with `Path(ledger).resolve().parent.glob("*.toml")`. Moving
`proxy-hardening.toml` into this folder would make its only "siblings" the files in here — silently
killing cross-ledger id-reuse checking and the aggregated `laws` view across all campaigns. The
same rule in reverse: **never put another `.toml` flat in `dev/campaigns/`**, or it gets parsed as
a ledger. `wall_registry.toml` and `oracle/expectations.toml` are safe here because `glob` is
non-recursive.

`linkedin-gateway-audit/` is the precedent: flat ledger, same-named folder for assets.

## research/

`proxy-landscape-improvements-2026-07-26.md` — 4,228 lines, produced by workflow `wf_8a65d1ed-109`
(46 agents): 94 projects swept across 5 blind angles, 12 deep-dived with their GitHub issue
trackers mined, 7 splice subsystems inventoried from source, 85 candidates, adversarially verified.

Every ledger item's title ends with a pointer into this document. `get <ID>` for the brief;
the document for the full evidence.

**Read §11 before trusting any item.** Verification killed *zero* candidates outright — a
generator-calibration finding, not proof of correctness. Seven items had a load-bearing premise
corrected mid-verification. Re-check each item's `splice today` anchor before implementing it.

## walls/ — enforcement

```bash
npm run gate:campaign            # BLOCKING half — runs inside `npm run gate`
npm run gate:campaign:strict     # blocking + advisory (the full worklist)
npm run gate:campaign:census     # census only, no wall execution — exits 2, never 0
npm run gate:campaign:selftest   # the gate's own C1–C10 red/green fixtures
```

`campaign_wall_gate.py` cross-checks the ledger against `wall_registry.toml` and
`law_registry.toml` in both directions, then **runs** every registered wall and law enforcer.

**Severity is split**, so the half that can never be acceptable runs in the main ladder today
while the worklist stays advisory:

| | check | red when |
|---|---|---|
| **BLOCK** | C1 | an item has zero or >1 registry rows |
| **BLOCK** | C2 | a registry row names no live item (a row may never be deleted to silence the gate) |
| **BLOCK** | C3 | a named wall is not on disk |
| **BLOCK** | C5 | **polarity** — a `todo` item's wall PASSES (vacuous), or a `done` item's wall FAILS |
| **BLOCK** | C6 | **positive control** — a wall fails its own `--selftest` |
| **BLOCK** | C9 | a law enforcer FAILS — the law is being broken right now |
| **BLOCK** | C10 | two **in_flight** items fence the same file — real concurrent writers |
| advisory | C4 | an item has no wall yet — **the standing worklist** |
| advisory | C7 | two `todo` items in one phase share a *derived* fence (provisional; narrow at claim time) |
| advisory | C8 | a law has no mechanical enforcer |

**C5 polarity** makes this more than a checklist: a wall that passes while its item is unfinished
doesn't detect the gap it claims to guard. **C6 positive control** closes polarity's own blind
spot — a wall that is merely `sys.exit(1)` *also* "fails on a todo item", so it looks honest for the
campaign's whole life and betrays you exactly when you mark the item done. That was proven by
registering a do-nothing wall: it counted as `walled` and produced zero findings. So a wall must
also prove it *can* go green, against synthetic open-gap and closed-gap inputs.

That control immediately caught three real bugs in the first four walls — including NF-01's
400-char lookbehind counting a constant *declaration* as the clamp being *applied*.

**Item walls and law enforcers have opposite polarity.** An item wall must FAIL until its gap is
closed. A law enforcer must PASS today, because the law is supposed to hold right now; red means
the law was broken. Mixing them is a category error.

You cannot land a wall that was never red, you cannot mark an item done until its wall flips, and
you cannot register a wall that has never been shown to go green. Green is earned, never
protected (#954).

Thresholds derive from a committed measurement or a spec, never a literal in the wall
(`nf_02` reads its ceiling from `config/splice.example.toml`'s own measurement line), so editing a
wall alone cannot buy headroom.

## oracle/ — verification

```bash
npm run oracle:capture           # re-freeze from server/  (ONLY works while server/ exists)
npm run oracle:check             # re-capture to temp, compare
```

`fixtures/` holds both wire directions of the **legacy Node stack** (`server/`), recorded
byte-exactly while its own suite was green (104/104, `cd server && node --test`). It is the
reference implementation the Kotlin gateway was ported from — 51 files still carry
`// PORT-OF: server/src/...` headers.

Bun can vendor Node's test suite forever because Node is independent and alive. **Ours is our own
legacy stack, marked for deletion. One `git rm` destroys this oracle permanently** — which is why
it is frozen into fixtures now rather than merely run.

**The oracle is a recording, not a spec.** It encodes the reference's bugs as faithfully as its
correctness, and the Kotlin stack deliberately fixed several (the G-series). So every replay
divergence is classified in `expectations.toml` as `not-yet-replayed` / `kotlin-wrong` /
`sanctioned` (which must cite its authority *and* pin the new bytes). A row never leaves by
deletion, only by passing — deleting it un-protects the scenario, the exact failure bun#34441 names.

11 scenarios frozen; 3 (`idle`, `drip`, `refresh`) excluded with recorded reasons and recovery
notes. Byte-stable across runs under one declared canonicalization rule (`msg_<digits>`); widening
that rule requires an observed diff, never anticipation.

## Graduation

Walls and oracle are campaign-**born** but repo-**durable**. When a wall goes green it moves into
`npm run gate`; a green wall only a campaign runs is protecting nothing. The ledger, registry and
research die with the campaign — the walls and the oracle should outlive it.
