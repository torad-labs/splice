# VENDORED — the campaign-ledger CLI

Vendored 2026-09-18 by V4-143 into `.dev/campaigns/` from:

- **Source:** `/home/user/Documents/dev/infra/grailseeker-bot/repo/.dev/campaigns/` at commit
  `eab668e0b409034992b893f31168e90cd10b9353` (2026-09-18, `MOD.45: the wall stops refusing findings,
  the gate stops judging the previous run`). Exported with `git archive eab668e`, never copied from a
  working tree: that tree carried ~430 lines of another session's uncommitted edits, which are neither
  citable nor reproducible. Those edits (a `cancelled` status among them) arrive at the next re-vendor.
- **Why this source:** it is the only implementation of the 2026-09-18 canonical surface — `plan`,
  `deliver`, `review`, `followup`, receipt-as-done. torad-fleet's `manifest.py` has none of it, and the
  operating rules say to vendor the canonical CLI rather than fork its logic.
- **Upstream of source:** a doctrine port of torad-fleet `.dev/campaigns/manifest.py` (see the header of
  `ledger-core.ts`), by way of compose-flow.

## Files

| file | source sha256 (12) | vendored sha256 (12) | state |
|---|---|---|---|
| `ledger.ts` | `5ad4e6117f50` | see `sha256sum` | deltas 4 to 11 + paths |
| `ledger-core.ts` | `4240cbdf28a8` | see `sha256sum` | deltas 1, 2, 3 |
| `ledger-earn.ts` | `ac39fa88ea0a` | `ac39fa88ea0a` | untouched |
| `earn-core.ts` | `8a4d4ad26bdb` | see `sha256sum` | paths only |
| `review.ts` | `20f5db0a23cd` | see `sha256sum` | paths only |
| `manifest.ts` | — | — | splice's entry: runs `ledger.ts`'s `main` |

Not vendored: `idle-watch.ts` (this repo has its own), `hydrate.ts`, the matrix plane, and the source
repo's hooks. `manifest.py` stays until the cutover (V4-143 phase D) deletes it.

## Deltas — every one recorded, every one proved

Deltas 1, 2, 3 and 5 exist ONLY because `manifest.py` still writes the same ledgers. They switch on for a
ledger that sits beside `manifest.py` while that file exists (`coexistsWithPython` in `ledger-core.ts`),
so deleting `manifest.py` retires them with no code change. A ledger anywhere else — the selftest's
temporary ledgers included — keeps the canonical behaviour.

1. **flock on the ledger file (bun:ffi).** `manifest.py` takes `fcntl.flock` on the ledger file itself;
   the canonical O_EXCL `.lock` sidecar and an flock do not exclude each other. Proved in two processes
   against a control: bun holds -> manifest.py BLOCKED; manifest.py holds -> bun BLOCKED; nobody holds
   -> both acquire.
2. **Write in place, never rename, while `manifest.py` exists.** A flock belongs to an inode; a rename
   puts a new inode at the path. Measured: manifest.py holding its lock, a rename over the path, and a bun
   probe on the path ACQUIRES — exclusion silently gone.
3. **No provenance proof on a coexisting ledger.** `manifest.py` never maintains `.cli-sha256`, so a
   proof born here goes stale at its next write and every later mutation here refuses. The cutover's
   `reattest` binds the first proof.
4. **A law line is `# LAW:` or `# LAW [date]:`.** 58 of the splice ledger's 59 laws are dated by
   `manifest.py`'s add-law. The canonical test matched only the undated form, so `laws` — the text
   SessionStart injects into every seat — printed ONE law. Now byte-identical to `manifest.py laws`.
   Selftest arm `laws reads a dated law too`; a mutant restoring the old test fails it.
5. **Claims stay on `manifest.py` during coexistence.** It records a claim as a dated `CLAIM` note and
   refuses one whose fence meets a live row's; the canonical CLI records `claimed_by`/`claimed_at` and
   has no such guard. Each is blind to the other's claims (measured: a py claim landed on top of a ts
   claim), so `claim`, `release`, `release-stale` and `next --claim` refuse on a coexisting ledger.
   Orchestrator ruling 2026-09-18. THE CUTOVER must port the fence-disjointness guard into `claim` and
   migrate every in-flight row's latest `CLAIM` note into `claimed_by`/`claimed_at`, or those rows read
   as unclaimed.
6. **An amend note names who made it** (`amend by=<seat>: …`, from `--seat`, `$LEDGER_SEAT`, else
   `orchestrator` under the grant, else `unattributed`). Orchestrator ruling 2026-09-18: notes are the
   past and fields the present; the field holds the new value, but no field says who changed it.
   Selftest arm `amend names who made it`; a mutant dropping `by=` fails it.
7. **`main` is exported** so `manifest.ts` runs the CLI in-process under the name every seat already uses.

Deltas 8 to 11 are PHASE C (2026-09-18): what the cutover needs so that deleting `manifest.py` loses no
behaviour a seat or a caller depends on. Unlike 1, 2, 3 and 5 they are permanent, not coexistence-only.

8. **Fence normalizer (`fencePrefix`), ported from `manifest.py:4383`.** A glob fence and a file beneath
   it are one fence: `src/g/**` and `src/g/deep.ts` share no equal string and neither ends in `/`, so the
   canonical string comparison found no overlap. V4-139's own fence carries three such globs. Arm `a glob
   fence and a file beneath it are ONE fence`; the glob-blind mutant fails it.
9. **Refuse where `manifest.py` refuses; warn where only canonical had an opinion.** A claim whose fence
   meets an **in_flight** row is refused, naming the row and the shared path (`manifest.py:4105`, the
   status quo, and NEVER-BELOW-STATUS-QUO governs a cutover). A claim meeting a row that is only
   **claimed, still todo** keeps the canonical warning under the operator ruling that ceremony never
   stalls a row; `manifest.py` cannot see such rows at all. Refusing both was tried first and broke seven
   proven arms, which showed the union was a third policy neither CLI implements. Arm `claim is REFUSED
   on a fence that intersects an in_flight row`; the warn-instead mutant fails it.
10. **`migrate-claims [--dry-run]`, the cutover migration.** Writes each in_flight row's last `CLAIM:
    owner=` note into `claimed_by`/`claimed_at`. Idempotent and safe while `manifest.py` is live (the
    fields are inert to it), so the cutover re-runs it as its last act. A field that disagrees with the
    note is reported as `CONFLICT` and exits 1, never overwritten. **`claimed_at` is the migration time,
    not the note's:** `manifest.py`'s release-stale asks whether the owner is ALIVE, while this CLI's is
    age-only, batch, 60 minutes by default. On a copy of the live ledger all five in_flight claims were
    over an hour old, so carrying the note's time across would make the first release-stale after the
    cutover free every live row. The original time stays in the diary, byte for byte. Five arms; four
    mutants (writes nothing, overwrites a conflict, not idempotent, note-time lease) each fail theirs.
11. **`list --plain` and `get <ID> --raw`, the shapes callers parse.** `law-check.mjs` and
    `build-punch-list.mjs` take the first whitespace token of a `list` line as the id and regex the raw
    TOML that `get` prints. The canonical human `list` leads with a status glyph, so those callers would
    see ZERO rows: build-punch-list throws, and law-check stops at its own zero-rows guard (DID NOT
    RUN, exit 2, `law-check.mjs:39`, measured with the flags removed) — an outage of both callers at
    the cutover, loud rather than silent. (First reported as a silent pass; that was inferred from
    law-check's reader without reading its guard, and is corrected here.) These flags are
    additive and byte-identical to `manifest.py` (see verification). The cutover swaps each caller's argv
    and leaves its parser alone. Two arms; the glyph mutant and the rendered-get mutant fail them.

**Paths:** help and usage text now names `bun .dev/campaigns/manifest.ts` and `bun .dev/campaigns/review.ts`;
`review.ts` defaults to `.dev/campaigns/v0.4.0.toml`; earn artifacts live under `dev/earn-artifacts/`.

**Deliberately NOT changed — the MOD.45 machinery wall.** `ledger.ts` carries grailseeker's own ruling
that rows fenced entirely inside its apparatus roots (`.claude/`, `.dev/campaigns/`, `.github/`, …) belong
to one seat, `scout-campaign-mod`. The tables are left verbatim, and on the splice ledger they classify
**0 of 156 rows** as machinery, so the wall is inert here. Renaming its `.dev/campaigns/` root to this
repo's `.dev/campaigns/` would have silently switched it on for V4-143 and V4-154. Whether splice wants a
wall of its own is a cutover question for the orchestrator, not a vendoring side effect.

> **RE-VENDOR WARNING: do not "tidy" the `.dev/` paths in `MACHINERY_ROOTS`.** A rename of that table's
> `.dev/campaigns/` root to `.dev/campaigns/` ARMS a wall as a side effect — every splice row fenced only
> inside `.dev/campaigns/` would then refuse `receipt`, `set-status` and `note` for any seat but
> `scout-campaign-mod`, mid-campaign. The paths delta above deliberately stops at help text and
> defaults. Orchestrator ruling 2026-09-18: MOD.45 stays verbatim and inert; seat-scoped machinery
> rows in splice, if ever wanted, are a deliberate decision with their own row.

**Surface gap at `eab668e`: two pull paths, one of them missing.** A seat pulls its next row today with
`manifest.py`'s `next-packet --session <seat>` verb (claims it, sets in_flight, prints the packet). The canonical equivalent, `next --claim <seat>`, is NOT implemented at this sha.
Delta 5 already names it, so it refuses correctly on a coexisting ledger the day a re-vendor brings it.
**Whoever re-vendors next must wire the two together** — the canonical verb replaces `next-packet` at
the cutover — rather than discover two pull paths with different claim representations (see delta 5).

## Verification at vendoring

- `bun .dev/campaigns/manifest.ts <ledger> selftest` -> 148/148 (146 at the source commit, plus the
  delta 4 and delta 6 arms; each arm's mutant fails it, 147/148).
- `bun .dev/campaigns/review.ts selftest` -> ok.
- Reading the live ledger (on a copy): `validate` passes all 156 rows; `list` gives the same id and
  status as `manifest.py` for every row; note counts match; `laws` is byte-identical to `manifest.py laws`.
- Writes compared with `manifest.py`, each on its own copy: every field line (`status`, `title`, `verify`)
  is byte-identical. Only the one added audit note differs, by design (date bracket, `LAW` prefix,
  `manifest.py`'s `ATTEST-START`, amend wording); the full diffs are recorded on V4-143.
- Coexistence stress test: 25 `manifest.py` plus 25 bun writers on one ledger at once -> 25/25 + 25/25,
  valid, no lock/temp/proof debris, in 3 of 3 rounds (4 of 4 before the re-vendor onto HEAD). With the
  deltas off -> 24+1 of 25+25 in 2 of 2 rounds.

## Verification at Phase C (2026-09-18)

- `selftest` -> 163/163. Eight mutants, each red on the arm it targets and none hung: glob-blind,
  inflight-warns, migrate-writes-nothing, conflict-overwrites, not-idempotent, note-time-lease,
  plain-has-glyph, raw-is-rendered.
- Machine shapes against `manifest.py` on copies of ALL 13 `.dev/campaigns/*.toml` ledgers (the directory
  is the denominator, not the three ledgers the callers name): 757 rows, `list --plain` unfiltered and
  `--status in_flight`, `get --raw` on every row -> **0 mismatches**.
- `migrate-claims --dry-run` on a copy of the live ledger: 5 in_flight rows, 5 carry a `CLAIM` note, 5
  would migrate, 0 without a note, 0 conflicting; the copy's sha256 unchanged by the dry run.
- **No typecheck covers `.dev/campaigns/`:** the tree has no tsconfig for it and no TypeScript toolchain,
  so bun runs these files untyped. That predates this vendoring; the evidence above is behavioural.
