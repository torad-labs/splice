# Local models e2e (FEATURES.md §10)

Live proof that a user-managed local runtime is first-class on the openai-chat dialect. splice
never downloads a model or manages the runtime: the operator starts Ollama, LM Studio or vLLM,
declares rows, and splice validates the rows against what the runtime reports.

What `e2e.py` proves, against a daemon it starts itself from a jar and a config:

| check | how |
|---|---|
| unavailable model refused at boot | a head whose row names a model the runtime does not list is DEGRADED, absent from `/api/heads`, with the reason in the daemon log |
| context limit refused at boot | a head whose row declares more than the runtime serves for the loaded model is DEGRADED the same way |
| context limit recorded | the good row's `context_window` equals the served window the runtime reports (Ollama `/api/ps`, LM Studio `loaded_context_length`); the model card is only a ceiling |
| streaming | `checks/e2e/stream_probe.py` validates the Anthropic SSE contract and incremental delivery at the head port |
| cancellation | a streaming turn is dropped mid-answer; the next turn succeeds and the head's perf log records a client-gone row (`client_abort` or `error:conn-reset`); on Ollama the journal's `cancel task` line is recorded too |
| tool-result continuity | a turn with one tool yields a `tool_use` block; the follow-up turn carrying the `tool_result` yields text that uses it |
| doctor | `splice doctor --json` names the runtime and version, marks the good row OK and each refused row FAIL |

One run proves one runtime; run it once per runtime, each against an isolated state dir and a
config with one good head and two deliberately bad heads:

```bash
python3 checks/local-models/e2e.py --runtime ollama \
  --jar gateway/app/build/libs/app-all.jar \
  --config /path/to/splice-local.toml --home /path/to/isolated-home \
  --good-head ollama --bad-heads ollama-unlisted,ollama-overclaim \
  --out checks/e2e/receipts/local-models-ollama.json
python3 checks/local-models/e2e.py --runtime lmstudio \
  --jar gateway/app/build/libs/app-all.jar \
  --config /path/to/splice-lmstudio.toml --home /path/to/isolated-home \
  --good-head lmstudio --bad-heads lmstudio-unlisted,lmstudio-overclaim \
  --out checks/e2e/receipts/local-models-lmstudio.json
```

The receipt is only written when every check passes. It records the runtime and its version, the
model (digest on Ollama, quantization on LM Studio), the card and served context lengths, and the
jar the daemon ran.

## Runtimes

**Ollama** (`base_url = "http://localhost:11434/v1"`): splice reads `GET /api/version`,
`GET /v1/models`, `POST /api/show` (model card `context_length` and the modelfile `num_ctx` when
set) and `GET /api/ps` (the window the server allocated for a loaded model). A loaded window beats
`num_ctx` (the server may cap or override what the modelfile asks for); without either the served
window is Ollama's own default, which is only known once the model is loaded — until then only the
model card can refuse a row, and `doctor --live` loads the model with its one tiny request so the
verdict is exact. Tested 2026-09-13: Ollama 0.30.5, qwen3:4b.

**LM Studio** (`base_url = "http://localhost:1234/v1"`): splice reads `GET /api/v0/models`,
taking `loaded_context_length` when the model is loaded and `max_context_length` otherwise.
Detection reads the body, not the status: LM Studio answers `200` with an error object on every
unknown path (so a `200` on Ollama's `/api/version` is Ollama only when it carries a `version`).
LM Studio serves whichever model is loaded when a request names an unknown id, so `doctor --live`
probes only rows the runtime lists. Tested 2026-09-13 with the headless daemon (llmster 0.0.24,
`curl -fsSL https://lmstudio.ai/install.sh | bash`, `lms daemon up`, `lms get qwen/qwen3-4b --gguf`,
`lms load qwen/qwen3-4b`, `lms server start --port 1234`): qwen/qwen3-4b Q4_K_M, max 32768,
loaded 8192.

**vLLM** (`base_url = "http://localhost:8000/v1"`): `vllm serve <model> --max-model-len N`; splice
reads `GET /v1/models` and takes `max_model_len` as the served window. Documented, not tested.

Any other OpenAI-compatible server on a loopback address is treated as local too; it lists models
but reports no context, so a declared window is trusted and doctor says so.

What is checked is each HEAD's effective rows (the head's `context_window` override applied, picker
suffixes such as `[64k]` stripped), never the raw provider table, and the probe carries the
provider's `extra_headers` and api-key bearer, so a runtime that guards `/v1/models` (vLLM
`--api-key`) answers the probe exactly as it answers a turn.

To opt out of the loopback rule set `local = false` on the provider; to force it on a non-loopback
address set `local = true`.
