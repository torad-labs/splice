# P3-LIVE receipt — live codex head against the real ChatGPT backend

**Recorded 2026-08-10.** Closed on PRODUCTION evidence, not a side-port smoke. Read the premise
note first — the item as written is describing a world that no longer exists.

## The item's premise is obsolete

P3-LIVE specifies a `CODEX_PROXY_PORT=3097`-style side-port launch "with production :3099 staying
Node throughout" (PARITY LAW). Measured on this machine:

```
$ ss -ltnp | grep 309
LISTEN 127.0.0.1:3096   users:(("java",pid=582439))
LISTEN 127.0.0.1:3099   users:(("java",pid=582439))
$ pgrep -af 'node.*server/|codex-proxy'      # -> nothing
$ ps -o lstart= -p 582439                    # -> Fri Aug  7 23:48:25 2026
```

The Kotlin daemon (`~/.local/share/splice/splice.jar`) has owned **:3099 and :3096** since
**2026-08-07 23:48**. No Node `server/` process is running, and none has been for three days. The
runtime cutover already happened de facto.

A side-port smoke run now would be a *weaker* version of what has been in production for three
days. This receipt therefore records the production evidence, which dominates the smoke test on
every field the item asks for.

## Receipt fields

Window: daemon start (2026-08-07 23:48) → 2026-08-10 23:02. Source: `claudex-perf.jsonl{,.1}`,
`claudex-compact-stats.jsonl`, `logs/daemon.log{,.1}`.

| Field the item asks for | Measured | Verdict |
|---|---|---|
| Turns served | **32,326** | ✅ |
| Outcome split | 32,047 ok · 135 upstream-failed · 64 overloaded · 32 api_error · 23 client_abort · 20 empty_model | ✅ 99.14% clean |
| Tool calls round-tripped | **32,204** turns carried a tool surface | ✅ |
| Compaction observed | **27** compactions: 26 `model_text`, 1 `stream_error`, **0 `empty_model`** | ✅ |
| Cache-warm evidence | **95.7%** hit (3,942,006,400 / 4,120,768,788 tokens) | ✅ vs the 2026-07-31 89–90% baseline |
| count_tokens counter | **885** hits in the log window | ✅ non-zero, the counter is live |
| Watchdog false-fires | **0** | ✅ |

Historical contrast worth keeping: `claudex-compact-stats.jsonl` carries 666 `empty_model` rows in
total, and **every one of them is dated 07-13** — the pre-Kotlin incident. Zero since the Kotlin
daemon took the port. That is the honesty-gate work landing, visible in production telemetry.

## NOT covered by this receipt

- **The reasoning mirror was never exercised on this head.** `grep -c 'reasoning summary'` over
  both daemon logs (~88 MB, three weeks) returns **0**, and `[heads.claudex]` sets no
  `show_reasoning`, so `mirrorInto` is a no-op here. The item lists "mirror visible" as a field;
  it cannot be claimed from this head's traffic. It is covered by unit tests
  (`TurnPipelineTest`, `ReplayMirrorTest`) and by the grok/kimi heads, not by this receipt.
- The window includes the WS-5 canary (`websocket = true` on the codex head from 2026-08-07), so
  these numbers describe the **WebSocket** transport, not the SSE path.
