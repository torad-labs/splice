# P7-PAR receipt — parity gate, re-scoped

**Recorded 2026-08-11.** Read the re-scope note first: this item's original gate cannot be run,
and the reason is permanent.

## Why the original gate is gone

P7-PAR's verify was `node gateway/tools/golden-diff.mjs --all` — a live Node-vs-Kotlin differential.
Two facts killed it:

1. **The harness never existed.** `gateway/tools/` has never been a directory, and
   `git log --all -- gateway/tools/golden-diff.mjs gateway/tools/normalize-sse.mjs` is empty on
   every ref. P2-GOLD was marked `verified` claiming those files (reopened 2026-08-10).
2. **The comparand is deleted.** P8-CUT removed `server/` on 2026-08-10. A live differential
   against Node is now impossible, and will stay impossible.

Under FIX-THE-GATE vs FIX-THE-CODE this is a *wrongly-blocking* gate — it blocks on a missing
instrument, not on broken code — so it is fixed with a red/green proof and this note, not bypassed.

## What the parity claim now rests on

### 1. Byte-exact wire comparison — the migration oracle

11 scenarios recorded byte-exactly **from the Node stack while it still ran**, replayed against the
real Kotlin fat jar in both wire directions (`npm run oracle:replay`): **11/11 byte-match**.

```
basic  bigout  compactish  failed  multipart  nonstream_tool
overflow_sse  prefill  replaystream  toolcall  truncated
```

This is the same guarantee the differential would have given for these scenarios, captured at a
moment when the comparand existed. It is not a re-authored expectation: the fixtures came from the
implementation being replaced, and `capture.mjs` is retained as their provenance.

**Red/green proof that this gate discriminates** (2026-08-11): changing one token in a recorded
fixture (`end_turn` → `end_turnX`) with its sha re-pinned so the tamper guard could not mask it
produced `✗ basic`, `10/11`, exit 1. Restored: 11/11, exit 0. It also caught a real defect in
production code this week — the JS-number `used_percentage` serialization bug — which no unit test
saw.

### 2. Full workday — satisfied by production, not a rehearsal

The item asked for "one FULL real workday on Kotlin side ports with Node still owning :3099". What
happened instead is stronger: the Kotlin daemon has owned **:3099 and :3096** since 2026-08-07
23:48 with no Node process at all.

| | |
|---|---|
| Turns served | 32,326 |
| Clean | 99.14% (32,047 ok) |
| Turns with a tool surface | 32,204 |
| Compactions | 27 — 26 `model_text`, 0 `empty_model` |
| Cache hit | 95.7% |
| Watchdog false-fires | 0 |

Detail in [p3-live.md](p3-live.md).

### 3. Dashboard — automated only

`webui/tests/` (api-client, config-entity, contrast, stores) plus `WebuiContractTest.kt` on the
Kotlin side, all green in `npm run gate`. **The item asked for a human click-through; that has not
happened and is not claimed here.**

## What is NOT proven, and never will be

- **Scenario coverage beyond the 11.** The capture deliberately excluded three: `idle` and `drip`
  (timing-dependent — wall-clock races make a byte-exact fixture unstable) and `refresh` (mutates
  auth state across runs). Their Node behaviour is now unrecoverable.
- **Any scenario nobody thought to record.** The window for capturing more closed with `server/`.
- **A human dashboard click-through** (see above).

The honest summary: parity is evidenced by 11 byte-exact recordings plus three days of unattended
production, not by the live differential this item was written to require.
