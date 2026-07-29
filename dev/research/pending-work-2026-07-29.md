# Pending work across splice — consolidated inventory, 2026-07-29

Nothing in this repo is **blocked**. `main` is green at `2fe9190`, and there are zero open PRs, zero
open issues, and zero open Dependabot alerts. What follows is what remains *to do*, aggregated
because until now no single place gave a cross-ledger view: each campaign ledger knows its own
items, and three real items lived in no ledger at all.

## Do not trust the numbers in this file

Every count below is **computed**, and the commands that compute it are given so you can re-derive
rather than believe. This file will rot; the ledgers will not. That is the same lesson
`proxy-hardening/walls/wall_registry.toml` learned the hard way when its header said 83/80/3 while
the rows said 88/81/7 — a summary that cannot be recomputed is a summary that lies eventually.

```bash
# per-ledger status counts
for f in dev/campaigns/*.toml; do
  echo "$f: $(grep -c 'status = "todo"' $f) todo, $(grep -c 'status = "done"' $f) done, \
$(grep -c 'status = "verified"' $f) verified"
done
npm run gate:campaign:census      # proxy-hardening walled/unwalled census
```

## 1. `reasoning-cache` — 6 items claimed, 0 audited  ← the only integrity gap

| | |
|---|---|
| ledger | `dev/campaigns/reasoning-cache.toml` |
| state | **6 `done`, 0 `verified`** |
| why it matters | `done` is a *builder's claim*. `verified` means the orchestrator independently re-ran the gate, read the diff, and worked the checklist — and only the orchestrator may set it (concept #945). |

This is the one place the repo's own records currently **overstate what has been proven**. Every
other closed ledger ends at `verified`: bug-sweep 6/6, ci-hardening 8/8, oss-release 13/13. This one
is the outlier, and it covers shipped code — RC-2's gateway-held `ReasoningCache` bounded store and
RC-5's "cache ON by default for the responses dialect (codex head)".

Each item names a runnable verify, so this is executable rather than a judgement call:

| item | verify |
|---|---|
| RC-1 | `cd gateway && ./gradlew :dialect-openai-responses:test` |
| RC-2 | `cd gateway && ./gradlew :dialect-openai-responses:test` |
| RC-3 | `cd gateway && ./gradlew :dialect-openai-responses:test` |
| RC-4 | `cd gateway && ./gradlew :gateway:test :dialect-openai-responses:test` |
| RC-5 | `cd gateway && ./gradlew check` |
| RC-6 | `bash checks/e2e/reasoning-cache-probe.sh && cd gateway && ./gradlew check` |

A green verify alone is **not** sufficient to set `verified` — the status also requires reading the
actual diff for the item's claim. A passing suite that never exercised the claim is precisely the
failure `DaemonLogWiringTest` was written for in #62.

## 2. `kotlin-gateway` — 5 of 43 left, and they are the endgame

Ordered; each gates the next.

| item | phase | what it is |
|---|---|---|
| `P0-XAI` | P0 | Spike: does `api.x.ai /v1/messages` stream faithful Anthropic passthrough? |
| `P2-GOLD` | P2 | Golden differential harness — one scenario through Node and Kotlin, normalized SSE byte-compare |
| `P3-LIVE` | P3 | Live side-port smoke: real claudex session on `:3097` with an observed real compaction |
| `P7-PAR` | P7 | Full parity gate: golden differential across all scenarios+heads, webui click-through |
| `P8-CUT` | P8 | The cutover commit — delete `server/`, rewire bin to `:3099`, update package.json/CI/README |

**`P8-CUT` deletes the legacy stack the frozen migration oracle was captured from.** That makes
`oracle:check` load-bearing for the cutover rather than incidental — it was a vacuous proof until
#62 gave it a real byte-for-byte diff, so the oracle can now actually arbitrate the parity claim
`P7-PAR` has to make.

## 3. `proxy-hardening` — 88 items, none started

The largest body of work, and the newest: its ledger, wall registry and frozen oracle landed during
the 2026-07-26/27 sessions; the implementation is entirely ahead.

By phase (all `todo`):

| phase | n | | phase | n |
|---|---|---|---|---|
| W8-operator-surface | 13 | | W2-auth-lifecycle | 7 |
| W7-auth-on-the-wire | 11 | | W1-bounded-failure | 6 |
| W4-correctness-walls | 9 | | W10-deferred-gated | 6 |
| W3-see-the-daemon | 9 | | W-INFRA | 5 |
| W6-shed-backpressure | 8 | | W5-overflow-skew | 5 |
| W12-unscheduled | 4 | | W9-tracker-truth | 3 |
| W11-operator-decision | 1 | | W0-net | 1 |

Census: **88 items, 7 walled, 81 UNWALLED.** Under the campaign's own polarity law a wall whose
item is unfinished must FAIL, so most items need their wall built and red-proven *before* the fix.
The ledger's PREMISE RE-CHECK law also applies: verification killed zero of 85 generated candidates,
which the research document itself flags as a generator-calibration result rather than proof of
correctness — so each item's `splice today` file:line must be re-read before implementing it.

## 4. Three items in no ledger

Recorded here because nothing else tracks them.

1. **`kt-no-silent-result-collapse` return-position gap.** Deliberately left open in the rule header
   with the failed experiment recorded: relaxing the anchor to `jump_expression` false-positives on
   a `Result` handled by a standalone `onFailure` statement, and binding the receiver in a
   `not: follows` arm does not suppress it. Closing it needs failure-consumption tracking the
   pattern layer does not have.
2. **detekt version skew.** `detekt` is in the version catalog; `detekt-formatting:1.23.8` is
   hardcoded in `gateway/build-logic/src/main/kotlin/splice.kotlin-common.gradle.kts`. A catalog
   bump silently leaves formatting behind.
3. **14 stale agent worktrees** under `.claude/worktrees/`. Last touched 2026-07-19 and
   2026-07-22/23; no open file handles, every tree clean, and both commits they sit on are ancestors
   of `main`. They hold nothing. Awaiting an operator call on removal, since `.claude/` tooling may
   expect the directory to exist.

## Suggested order, and why

1. **`reasoning-cache` verification (§1)** — first, because it is the only item where the repo's
   records claim more than has been shown. Everything else is honestly-labelled backlog.
2. **detekt skew (§4.2)** — one line, removes a silent-skew trap from the build.
3. **`kotlin-gateway` P0-XAI → P8-CUT (§2)** — sequential, and it retires `server/`, which shrinks
   the surface every later item has to reason about.
4. **`proxy-hardening` (§3)** — largest and least urgent; wants walls-first per item, so it is
   naturally incremental rather than a push.

`kt-no-silent-result-collapse` (§4.1) is deliberately *not* in this order: it is documented as open
because the honest fix does not exist at the pattern layer, not because nobody got to it.
