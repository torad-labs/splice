# cache-replay A/B results (live path)

Captured: 2026-07-15 via `real-ab.sh` on isolated `:3097` (v35).
Model: `gpt-5.6-sol`, effort: `medium`, 10 turns.
Method: one live `claudex` capture (replayReasoning ON so `redacted_thinking`
lands in the transcript), then the **same** Anthropic request bodies POSTed
back through the proxy twice — Arm A ON / Arm B OFF. First-user text salted
`[ArmA]` / `[ArmB]` for distinct `prompt_cache_key` shards.

## Hit% by turn

| turn | Arm A (replay ON) | Arm B (replay OFF) |
|-----:|------------------:|-------------------:|
| 1 | 53% | 53% |
| 2 | 53% | 53% |
| 3 | 52% | 97% |
| 4 | 93% | 97% |
| 5 | 93% | 51% |
| 6 | 95% | 97% |
| 7 | 94% | 97% |
| 8 | 84% | 92% |
| 9 | 90% | 87% |
| 10 | 95% | 95% |

## Aggregates

| | Arm A (ON) | Arm B (OFF) |
|---|---:|---:|
| avg hit% | 80% | 82% |
| COLD (>1024 & <20%) | 0 | 0 |
| WARM (≥80%) | 7 | 7 |
| total uncached input tokens | 94,974 | 85,511 |

## Reading

- **No clear cache bust.** Same Anthropic bodies; only `replayReasoning` differed.
  Avg hit% is essentially tied (80 vs 82). Zero cold turns either arm.
- Turn 3 is the only ON-colder outlier (52% vs 97%). Turn 5 goes the other
  way (OFF colder at 51%). One-run noise, not a systematic penalty.
- The ~10k uncached-token gap is mostly **volume** (ON ships encrypted
  reasoning blobs as extra input), not a lower hit rate — same confound
  `run.mjs` now demotes to secondary.
- Consistent with production default `replayReasoning: false` being driven by
  **reasoning-depth** cost (config.mjs note: replay makes the model reuse
  prior thinking, thinning the wall), not by a measured cache penalty on this
  path.

Raw run log and per-turn request bodies were captured locally for this run but are not tracked in the repo (removed post-review — they carried operator-local paths and private instruction text).
