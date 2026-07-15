# cache-replay: does encrypted-reasoning replay bust the Codex prompt cache?

A controlled A/B that isolates a single variable: **whether prior encrypted
reasoning items are present in the input** of a multi-turn Responses request.

## The hypothesis

On the ChatGPT / Codex Responses backend, the model keeps prior encrypted
reasoning items when they are useful for the current turn and drops them when
they are not. That conditional keep/drop destabilizes the cacheable prefix.
OpenAI's own cookbook states the core of it:

> "the API will simply discard any reasoning items that aren't relevant for the
> current turn… the call cannot achieve a full cache hit, because those reasoning
> items are missing from the prompt."

Practical consequence for a long agent session: **not sending reasoning back at
all keeps the prefix stable and the cache warm**, so far fewer *uncached* input
tokens are billed. Uncached input is what drains the 5-hour / weekly quota, which
lines up with the widespread "GPT-5.6 burns tokens" reports.

## What the script does

One fixed, context-building conversation, run twice, back to back:

- **Pass A (replay ON)** — each turn echoes the prior turns' encrypted reasoning
  items back into `input`.
- **Pass B (replay OFF)** — identical user text and identical assistant text
  (captured once in Pass A), but the reasoning items are dropped.

Everything else is held constant: same `model`, same `reasoning.effort`, same
`instructions`, `include: ["reasoning.encrypted_content"]` in *both* passes (so
the request param is identical), and a fresh, non-colliding `prompt_cache_key`
per pass. It prints cached vs uncached input tokens per turn and a total.

## Run it

Against your ChatGPT/Codex subscription (uses `~/.codex/auth.json`, spends a
little quota):

```bash
node experiments/cache-replay/run.mjs
# knobs: CACHE_EXP_MODEL=gpt-5.6-sol CACHE_EXP_EFFORT=medium
```

For third-party reproduction on the public API (pay-as-you-go, no subscription):

```bash
OPENAI_API_KEY=sk-... CACHE_EXP_MODEL=gpt-5.6 CACHE_EXP_BASE=https://api.openai.com/v1 \
  node experiments/cache-replay/run.mjs
```

## Reading the result

The line that matters:

```
total UNCACHED input tokens (this is what drains quota):
  replay ON  = <bigger if the theory holds>
  replay OFF = <smaller>
```

If `replay ON` bills materially more uncached input for the same conversation,
the cache penalty is real and dropping encrypted reasoning is the fix. A full
`trace-<ts>.json` is written for auditing.

## Honest caveats

- Non-determinism: the assistant's text differs run to run, so Pass B replays
  Pass A's captured text to keep the two histories identical except for reasoning.
- This tests *consistent* replay vs none (mirroring a proxy toggle). Real agents
  replay reasoning *inconsistently* (only around tool calls), which should be at
  least as bad for cache; a tool-use variant is a natural follow-up.
- Cache is server-side and time-sensitive; run the two passes back to back.
