# Request-byte contracts (#924 Phase 1)

The direct defense against the failure mode that shipped **two** production incidents this codebase
hit — `stream_options` 400 and request-body-gzip 400 — where the mock tests were green but the live
upstream rejected the bytes. The defense binds the **exact request bytes** a builder emits to a
golden file, and (live half) to a receipt proving a real upstream returned `200` on those bytes.

## What is enforced today (the offline half — GREEN in `./gradlew check`)

Every `*RequestBuilder` has a committed golden of the **exact upstream request** it emits for a
canonical turn, and a `*ContractTest` that asserts byte-identity against it:

| dialect | builder | golden | test |
|---|---|---|---|
| OpenAI Responses | `ResponsesRequestBuilder` | `dialects/openai-responses/src/test/resources/contract/responses-canonical.json` | `ResponsesContractTest` |
| OpenAI Chat | `ChatRequestBuilder` | `dialects/openai-chat/src/test/resources/contract/chat-canonical.json` | `ChatContractTest` |
| Anthropic passthrough | `PassthroughRequestBuilder` | `dialects/anthropic/src/test/resources/contract/passthrough-canonical.json` | `PassthroughContractTest` |

A drift in **any** field of the request — not just the fields an individual scenario test happens to
assert — fails the contract test. So re-adding `stream_options`, flipping `store`, or changing the
`prompt_cache_key` algorithm is a failing unit test.

**Coverage-by-law:** `ArchitectureLawsTest."every RequestBuilder module ships a request-byte
contract fixture"` fails the build if a module grows a `*RequestBuilder` without a
`src/test/resources/contract/*.json`. A new dialect therefore arrives *with* its request-byte
contract — the coverage is structural, not opt-in.

### Regenerating a golden (deliberately)

The contract tests **record on missing**: delete the golden, run the dialect's `*ContractTest`, and
it re-records the current builder output to `src/test/resources/contract/` and fails with
`RECORDED …`. Review the diff, then re-run to enforce. Never regenerate to make a red test green
without reading the byte diff — that blind-regenerate path is exactly what the live half below
closes.

## What is NOT yet active (the live half — needs a head-side tap)

The plan's marquee binding is: *for any changed golden, require `sha256(builderOutput) ==
receipt.hash`, where the receipt was written by a run that observed `200` on those exact bytes.* That
turns "golden changed" into un-green-able without a live success.

`bun tools/e2e heads --tier 1` now **emits a receipt** at `tools/e2e/receipts/<head>.json` on a
tier-1 `200` (head, model, http_status, observed_at). It is marked `contract_bound: false` because
the load-bearing field, the `sha256` of the exact **upstream** request bytes the head sent, is not
read into the receipt yet:

- the golden is `builderOutput` = the request the head's `RequestBuilder` produced for the upstream;
- the head **does** record the bytes it sent upstream: a head's trace, on by default (the `trace` knob), writes one
  `attempt` record per upstream send with that send's request body (`TurnTrace.kt`), cut and flagged
  `truncated` past `traceMaxBodyChars` (16,777,216 characters by default);
- `e2e heads` reads only the client side of the probe, so it never picks those bytes up.

**The one missing piece:** `e2e heads` reading the `200` attempt's request body from the head's trace for the
probe turn. Once it does, it fills `receipt.hash = sha256(upstream bytes)` and sets `contract_bound: true`, and the
`*ContractTest`s gain a check: *a changed golden must match a `contract_bound: true` receipt hash.*
That is a localized change: the receipt file, its emission point, the goldens, the byte-identity
assertions and the trace's per-send body all already exist; only the trace read and the two-line receipt-hash check
remain. A body the trace cut at `traceMaxBodyChars` cannot be hashed, so that probe stays unbound.

Until then the offline byte-identity half stands on its own: it catches the incident **class**
(any request-field drift) at unit-test time, which is where both production incidents would have been
caught before shipping.
