# Claude head — Anthropic passthrough on the client's native auth

2026-08-15. Reviewed by the Eli subagent (parallax pass, findings integrated); revised three
times on operator direction: (1) Claude gets a first-class path like codex/grok/kimi; (2) no
shortcuts — the head surface itself becomes properly compositional; (3) the harness's native
auth lifecycle is never overridden — splice forwards, never holds.

## The contract: adding a head is one of three moves

1. **Reuse a schema** — an existing dialect + auth kind fits: the head is PURE TOML
   (base_url, auth, models, port, prefix, command). Proven today for openai-compatible
   vendors; this design makes it equally true for anthropic-compatible vendors.
2. **Declare a new one** — a genuinely new wire grammar (dialect) or token grammar (auth
   refresh flow). Real code, rare, bounded: one module / one AuthProvider + one dispatch arm.
3. **Reuse but reshape** — same schema, different knobs: quirks overrides in TOML, never code.

Everything below is the claude head executed as these moves, and the refactor that makes
move 1 and 3 honest for the passthrough dialect (today it hardcodes Kimi's shape).

## Goal

A fourth head, `claude-max`, that serves Claude Code against `api.anthropic.com/v1/messages` on
the operator's Max subscription, through the same gateway machinery as every other head: unified
telemetry, knobs, model switching, dashboards.

## Why the shortcuts failed (and what changed)

- **Forwarding client auth under the EXISTING launch recipe is impossible** — the launcher
  replaces the client's credential with splice's mgmt key (`LaunchService.kt:111`), strips OAuth
  env (`:83-87`), and disables `/login` (`:131`), so the inbound header is never an Anthropic
  credential (`HeadServer.kt:428-445`). The recipe is our code and is correct only for
  FOREIGN-vendor heads; §3 changes it per head, which is what makes native forwarding sound
  rather than a shortcut.
- **Reusing the passthrough builder as-is damages real Anthropic traffic**: it strips every
  `cache_control` block (kills prompt caching — a silent cost multiplier), rewrites tool schemas
  through the Moonshot sanitizer (`MfjsSanitizer`), drops block types outside Kimi's allowlist
  (`redacted_thinking` included), rewrites `thinking` into Moonshot vocabulary, and synthesizes
  thinking-block signatures on truncation that a verifying upstream later rejects
  (`PassthroughRequestBuilder.kt:90-308`, `PassthroughStreamTranslator.kt:199-215`).

## Design

### 1. Passthrough dialect becomes neutral-base + opt-in quirks

`PassthroughQuirks` grows explicit knobs; the NEUTRAL value is a faithful passthrough, and
Kimi's provider config opts INTO its deformations (inverting today's hardcoding):

| knob | neutral (claude) | kimi |
|---|---|---|
| `mfjsSanitize` | false — full JSON Schema rides | true |
| `blockAllowlist` | null — all block types pass | Kimi's list |
| `stripCacheControl` | false — `cache_control` preserved | true |
| `mapThinkingToAdaptive` (exists) | false — verbatim `thinking` | true |
| `synthesizeSignatures` | false — upstream signatures only | true |
| `providerTag` (exists) | "claude-max" in errors | "kimi" |

The compact directive is GONE (2026-09-05): a compaction is built byte-identical to a turn on
every head — parity law, not a quirk — or the upstream prompt cache misses the transcript. Error
strings currently hardcoding `kimi:` become `providerTag`-driven (they already carry the field;
the literals are the bug).

**Acceptance for this refactor: kimi's wire bytes are byte-identical before/after.** The
existing kimi passthrough tests stay green UNMODIFIED; a golden-request test pins one
representative built request per deformation knob.

### 2. One generic passthrough provider; vendor classes only where computed state exists

`KimiProvider` and the would-be `AnthropicProvider` differ only in headers — so the class
collapses into ONE generic `PassthroughProvider` (in the passthrough dialect module):
- `upstreamUrl = "${baseUrl}/v1/messages"` (invariant of the dialect).
- Static vendor headers come from TOML: `extra_headers = { "anthropic-version" = "2023-06-01" }`
  — operator-owned, so a new anthropic-compatible vendor is **move 1: pure TOML, zero code**.
  On a `client`-auth head the forwarded inbound `anthropic-*` headers win over these defaults
  (§3); on every other auth kind nothing inbound crosses, and `extra_headers` is the whole story.
- `showReasoning = OFF`, `replayReasoning = false` (dialect invariants — real thinking blocks).
- Computed state stays a plug: Kimi's device identity (persisted device id + X-Msh-* headers +
  UA) becomes an optional identity supplier the generic provider takes; Kimi is the only user.
  KimiProvider the CLASS dissolves into config + that supplier (move 3 for kimi).
- The claude head needs NO provider class at all: config + neutral quirks.

### 3. Auth: `client` — native harness auth, forwarded, never held

The client's native auth path stays untouched. Claude Code already owns credentials, keychain,
single-flight refresh, and /login; splice must not re-implement any of it. The only reason the
native path looked unusable is our OWN launch recipe, which strips client credentials and plants
the mgmt key — correct for foreign-vendor heads, wrong for a claude head. So `kind = "client"`
is a declared auth kind (move 2) whose grammar is "the caller brings its own":

- **Launch recipe variant** (`LaunchService`): for a `client`-auth head, do NOT unset
  `ANTHROPIC_API_KEY`/`CLAUDE_CODE_OAUTH_*`, do NOT set `ANTHROPIC_AUTH_TOKEN`, do NOT set
  `DISABLE_LOGIN_COMMAND`/`DISABLE_LOGOUT_COMMAND`. Set only `ANTHROPIC_BASE_URL` (the head's
  loopback port) and the model/window surface. Claude Code authenticates natively; 401s heal
  natively.
- **Front door** (`HeadServer.authorize()`): the mgmt-key comparison is bypassed for
  `client`-auth heads. Justification: loopback-only listener, and splice holds NO credential on
  this head — an unauthenticated local request forwards without valid Anthropic auth and the
  upstream 401s it; there is no splice-held quota to steal. The bypass is gated strictly on the
  head's auth kind, with a wall test proving every other head still enforces the mgmt key.
- **Forwarding seam**: the inbound `Authorization`, `x-api-key`, `anthropic-version`, and
  `anthropic-beta` headers ride to the upstream through the same channel that already carries
  `x-claude-code-session-id` into `buildTurn` (`HeadServer.kt:370`) → `BuiltTurn.extraHeaders` →
  `TurnDriver` merge. Two hardening points from review: header-name case is normalized at the
  seam (the Ktor builder APPENDS case-insensitively; a case-variant duplicate must be
  impossible), and `UpstreamClient`'s non-null `credentials()` requirement gets a
  `client`-kind path that carries no daemon credential (its AuthProvider stub describes
  "client-native" for doctor/dashboard; the 401-refresh machinery is inert for this kind).
- Forwarding client `anthropic-*` headers is CORRECT here, unlike the earlier design: they are
  exactly what Claude Code would send to Anthropic directly; the head's job is to not get in
  the way. For non-client heads nothing changes: no inbound header crosses the boundary.
- API-key alternative stays available as today (`kind = "api-key"`, env `ANTHROPIC_API_KEY`,
  x-api-key header shape) for headless/CI use of the same provider entry.

**Rejected alternative — splice-held Max OAuth** (`ClaudeAuthProvider` reading
`~/.claude/.credentials.json` + refresh via Anthropic's undocumented token endpoint): it
duplicates a working native lifecycle, adds credential custody and an unofficial refresh flow
splice must chase, and breaks on macOS keychain-held credentials. Native forwarding does none
of that. If a future non-Claude-Code client needs this head without its own credentials, that
is the day this alternative gets revisited.

### 4. Topology

```toml
[providers.anthropic]
dialect = "anthropic-passthrough"
base_url = "https://api.anthropic.com"
auth = { kind = "client" }             # caller brings its own — splice holds nothing
extra_headers = { "anthropic-version" = "2023-06-01" }   # default when the inbound doesn't carry it
# quirks: neutral passthrough is the default — nothing to declare. Kimi's entry instead opts
# into its deformations: quirks = { mfjs = true, block_allowlist = [...], strip_cache_control = true, ... }
[[providers.anthropic.models]]  # fable-5, opus-5, sonnet-5, haiku-4.5 — 200k windows
# no [1m] entry until its beta header is deliberately wired (the [1m] suffix strips silently)

[heads.claude-max]
provider = "anthropic"
port = 3104            # 3103 is fireworks in the example
discovery_prefix = "claude-max--"
pinned_model = "claude-fable-5"
[heads.claude-max.claude]
command = "claude-max"  # must not shadow the real `claude` binary
```

Double-wrapping (`claude-max--claude-fable-5`) is proven symmetric in `ModelCatalog`
(wrap/unwrap/stripSuffixes); keep the `--` convention.

### 5. Deliberately not doing (v1)

- No exact `count_tokens` proxying — the local estimate is the proven parity behavior
  (`HeadServer.kt:375-406`, deliberate since the Node port). Exact proxy is a possible later
  upgrade, noted, not built.
- No reasoning cache (Responses-dialect concern), no launcher isolation, no `[1m]` tier.

### 6. Tests / acceptance

1. Quirks refactor: kimi golden-request byte-identity + existing kimi tests green unmodified.
2. Generic `PassthroughProvider` unit tests: URL shape, TOML `extra_headers` applied and
   yielding to forwarded inbound equivalents on client-auth heads, identity-supplier absent →
   NO `X-Msh-*`/`KimiCLI` UA, model wrap/strip round-trip; kimi shape reproduced exactly when
   the supplier and quirks are plugged.
3. Client-auth seam: wall test that the `authorize()` bypass exists ONLY for `kind = "client"`
   heads (every other head still rejects a non-mgmt-key caller); forwarding test that inbound
   `Authorization`/`x-api-key`/`anthropic-*` reach the upstream request exactly once with
   case-variant duplicates impossible; launch-recipe test that a client-auth head keeps native
   env (no stripping, no `ANTHROPIC_AUTH_TOKEN`, login enabled) while foreign heads are
   byte-identical to today.
4. Neutral-builder tests: `cache_control` preserved, full schema untouched, unknown block types
   pass, `thinking` verbatim, no signature synthesis on truncation.
5. Daemontest scenario: claude head against the mock upstream.
6. Live probe (operator-run): one real turn on the Max subscription before "done" — whether
   Anthropic accepts its native client through a proxied base URL is only provable live.

## Risks

- The `authorize()` bypass is a deliberate trust-boundary change: a `client`-auth head accepts
  unauthenticated loopback callers and forwards whatever auth they carry. Contained by: loopback
  bind, no splice-held credential on the head, the wall test in §6.3, and the bypass being
  auth-kind-gated. This is the one security decision in the design — named, not buried.
- Anthropic-side behavior with a proxied base URL (UA/beta gating) is unproven until the live
  probe; the probe gates "done". No refresh-flow risk exists — the client owns refresh.
