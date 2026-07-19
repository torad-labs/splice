# Gateway Gaps Tracker

Working checklist for the 27 gaps from `gateway-best-practices-2026-07-18.md` (full evidence,
practice sources, and fix detail live there — this file is the plan-and-status view) plus the
four error-discipline levels. One gap per PR-sized unit unless batched below. Update the Status
column as work lands (`todo` / `in-progress` / `done <commit>` / `wontfix <reason>`).

## Error-discipline ladder (borrow-checker-for-errors)

| Level | Item | Status |
|---|---|---|
| L1 | ast-grep wall `kt-no-silent-result-collapse` (provider-*+spi scope; 18 sites swept: 11 now log, 7 annotated). Widen to app/core/control next | done 097e750 |
| L2 | `-Xreturn-value-checker` on; 19 sites triaged via `Result.discard(why)`. Promote to error later | done 890ef35 |
| L3 | Sealed `RefreshOutcome` on grok/kimi/codex doRefresh; `credentialsOrNull(tag)` single flatten, per-branch logging pinned by test. SPI-wide consumption with G7 | done (phase 1) |
| L3-phase-2 | 2026-07-18: evaluated during G7 and deliberately deferred, NOT a fallout of the classify/retry extraction — widening `refreshCall` to carry `RefreshStep`/`RefreshOutcome`-level granularity means changing that constructor param's TYPE across `GrokAuthProvider.kt`/`CodexAuthProvider.kt`/`KimiAuthProvider.kt` AND their existing test fakes (`refreshCall = { null }` style), a breaking 3-module signature change, not code motion. Left as a follow-up (see G15, which already notes "needs G7"). | todo |
| L4 | FIR compiler plugin: `@MustConsume` discard = compile error (build AFTER L3 types exist) | todo |

## HIGH

| ID | Gap | Fix (short) | Status |
|---|---|---|---|
| G1 | Cross-process credential-file races (no lock, no re-read-before-refresh, no re-read-on-invalid_grant) — likely killed the kimi-code token 2026-07-18 | re-read before POST; re-read-once on invalid_grant; then FileLock on `<authPath>.lock` | done e970691 |
| G2 | Zero-event 200 stream undiagnosable, hardcoded OVERLOADED → Claude Code retries a dead head forever | buffer first ~1KB; on zero-event end log snippet + classify via UpstreamFailureClassifier (auth-shaped → AUTHENTICATION + login hint) | done a207e2f |

## MEDIUM

| ID | Gap | Fix (short) | Status |
|---|---|---|---|
| G3 | Retry-After / server pushback never read | floor + absurd-pushback give-up | done (retry batch) |
| G4a | RETRYABLE_STATUSES misses 500/504/408 | widened | done (retry batch) |
| G4b | Default budget 2 attempts / ~200ms | 4 attempts, 10s cap | done (retry batch) |
| G4c | No jitter | ±10% | done (retry batch) |
| G4d | No cross-attempt wall-clock deadline | t0 in `post()`, budget check before each attempt/sleep | todo |
| G5 | Stream-reconnect: handed-off ≠ client-saw-output; torn-before-first-frame fails turn needlessly | gate on `clientFrameEmitted()`; small reissue budget; hard no-retry after any frame | todo |
| G6 | Codex head: zero expiry awareness (grok bug's latent twin) | JWT `exp` via existing `decodeJwtClaims` + grok proactive-window block | todo |
| G7 | Grok/codex refresh: no error classification / transient retry (kimi has the right loop) | extract KimiRefresh classify/retry to shared `:app` helper | done 66befaf |
| G8 | No pre-traffic auth/health probe loop | per-head coroutine ~60s, cheap auth probe, log transitions, trigger single-flight refresh | todo |
| G9 | Malformed SSE frames dropped, zero telemetry | `onMalformed` hook → FRAMES_SKIPPED perf key + first-snippet log | todo |
| G10 | Installed launch shim stale — no JVM opts on cold start | synced 2026-07-18; TODO: shim version marker verified at startup | done (sync) / marker todo |
| G11 | TCP connect timeout = 5-min firstByteTimeout | `connectTimeoutMillis = 10_000` own knob | todo |
| G12 | SSE parser incident-driven, not WHATWG-derived (multi-line data drops; lone-CR; BOM; EOF-pending) | blank-line event assembly, `\n` join, CR terminator, BOM strip, discard-pending-at-EOF | todo |
| G13 | Pre-stream rejections return HTTP 200 + error JSON | real 4xx status, keep Anthropic-shaped body | todo |

## LOW

| ID | Gap | Fix (short) | Status |
|---|---|---|---|
| G14 | Transport backoff (200/400ms) shorter than real resolver blip | DNS-class failures → 1s/2s/4s schedule | todo |
| G15 | No terminal invalid_grant latch (dead token re-POSTed every turn) | latch until file content changes (needs G7) | todo |
| G16 | Post-send SocketException retried like connect-phase (double token burn risk) | distinct "possible-duplicate" log class on post-send retries | todo |
| G17 | Proactive refresh blocks request path | two-tier: async prefetch above stale floor, blocking below | todo |
| G18 | Grok file without `expires` = never-expiring | synthesize mtime + 4h TTL | todo |
| G19 | AUTHENTICATION errors lack "run: <head> login" hint | append per-head instruction when classified AUTHENTICATION | todo |
| G20 | No per-head passive health counters / cooling state | local-origin vs provider-error split counters | todo |
| G21 | Unbounded admission queue; shed load invisible | bound waiters; 529 "gateway at capacity" | todo |
| G22 | No aggregate retry budget across turns | only if fan-out grows | todo |
| G23 | CIO selector/writer-spin class — **FIRED LIVE 2026-07-18 (~700% CPU, daemon killed)**; the write-side twin of the read-side spin | ROOT FIX: client engine CIO → JDK HttpClient so the class can't occur — streaming `0e0dccb` + all 5 auth/refresh/login clients via `authHttpClient()`. The proposed detect-and-recycle watchdog is now moot (a generic wedged-client sampler remains possible defense-in-depth, separate concern) | done (root) 2026-07-18 |
| G24 | Positive JVM DNS TTL not pinned | one-liner next to negative-ttl in Main.kt | todo |
| G25 | No idle heap uncommit | `-XX:G1PeriodicGCInterval=60000` in shim + AdminSupport | todo |
| G26 | TCP_NODELAY unverified on either hop | one-time verification | todo |
| G27 | Non-goals (documented, not built): stream resumption cursor, cross-head mid-session failover, hedging, multi-endpoint redispatch, GraalVM/KMP/Bun migration | — | wontfix (by design) |

## Suggested batches (payoff-per-effort, from the report)

1. Retry-policy batch: G3 + G4a-c (one file: `UpstreamClient.kt` + Knob default)
2. G2 zero-event classifier (highest remaining incident-class payoff)
3. G1 credential race closure (re-read steps first, FileLock second)
4. G6 codex JWT expiry (~30 lines, reuses grok pattern)
5. G7 shared refresh helper (code motion; unlocks G15)
6. One-liners: G11, G24, G25, G4d
7. SSE WHATWG batch: G12 + G9 (same file)
8. G13, G5, G8, then lows opportunistically
