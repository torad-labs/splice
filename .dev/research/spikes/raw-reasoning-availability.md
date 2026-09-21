# Can splice get RAW chain-of-thought instead of summaries? (2026-07-30)

**Verdict: no. The ChatGPT backend never streams raw reasoning to this client. Omitting the summary
key yields NOTHING — not raw CoT, not a summary. Do not "fix" splice by dropping the summary field.**

Operator report: "the thinking tokens are super short and summarized compared to how long and
detailed they used to be — I want to see all verbose robust reasoning."

A reading of codex-rs suggested codex OMITS `reasoning.summary` (it does — the `null` seen in an
intercepted frame was the server normalising an absent key, not codex sending null). The obvious
inference was that splice sends `summary: detailed` where codex sends nothing, and that raw
reasoning would arrive if splice matched. That inference is WRONG, and the change it implies would
have deleted reasoning output entirely.

## The probe

Two live streamed requests to `https://chatgpt.com/backend-api/codex/responses`, identical except
for the `reasoning` object. Same model (`gpt-5.6-sol`), same headers splice sends (`Accept:
text/event-stream`, `ChatGPT-Account-ID`, `originator: codex_cli_rs`, `OpenAI-Beta:
responses=experimental`), `store: false`, `include: ["reasoning.encrypted_content"]`.

| request | `reasoning` sent | summary delta chars | **raw CoT delta chars** |
|---|---|---|---|
| A (what splice sends today) | `{"effort":"high","summary":"detailed"}` | 44 | **0** |
| B (codex parity) | `{"effort":"high"}` | **0** | **0** |

`response.reasoning_text.delta` — the raw chain-of-thought event — arrived **zero** times in both.
Only `response.reasoning_summary_*` events are ever emitted.

## What this means

1. **Raw CoT is not available over ChatGPT-backend auth.** splice already maps
   `response.reasoning_text.delta` to thinking blocks (`ResponsesStreamTranslator`), so if the
   backend ever sent it, it would already render. It does not send it.
2. **Omitting `summary` removes reasoning output entirely.** Request B produced no reasoning of any
   kind. `resolveSummary` returns null before it reads `opts.configSummary`, so a quirk-level change
   here silently zeroes the operator's setting.
3. **`summary: detailed` is already the most verbose available setting.** There is no untapped lever
   in the request shape.

## The one remaining lever, deliberately not touched

`summaryDelivery = "sequential_cutoff"` drives `dedupeRepeatedSummaryParts`, which suppresses
byte-identical summary parts sharing an output-index slot across continuation rounds. That is a
known over-suppression, recorded in `ResponsesStreamTranslator` as an explicit operator call
("an exact identity is overwhelmingly a restatement, and no-duplicates wins"), with a standing
warning that re-keying the sets by round or item id is "the one fix NOT to make".

It is the only remaining mechanism that could make delivered thinking shorter than what the backend
sent. Changing it is an operator decision, not a defect fix, and it is left alone here.

## Scope

Two requests, one model, one prompt shape, one account. Establishes that raw CoT is absent on this
path; does not establish what a different auth tier (e.g. a platform API key rather than ChatGPT
backend) would return. Auth tokens were read from `~/.codex/auth.json` and never logged.
