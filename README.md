# splice

**Run [Claude Code](https://docs.anthropic.com/en/docs/claude-code) against non-Anthropic model backends — locally, on loopback — and keep each model's reasoning warm, legible, and cheap across the whole agent loop.**

splice is a local, loopback-only proxy stack. A single Kotlin daemon (**spliced**) sits between Claude Code and one or more model backends, translating Anthropic's Messages API into each backend's own wire dialect. Each backend is exposed as a **head** — a thin Claude Code wrapper on its own loopback port (`claudex`, `claude-grok`, `claude-kimi`, `claudeor`, …). Its load-bearing feature is the **mirror**: the backend's reasoning summary is written back into the transcript as visible text, so conclusions persist and stay legible turn after turn instead of evaporating.

Adding a backend is a TOML edit, not code: the daemon dispatches on `(dialect, auth.kind)` to a built-in provider. See [`config/splice.example.toml`](config/splice.example.toml) for the full sample topology.

> Status: personal project, run locally on loopback. No warranty — see [License](#license).

## Not affiliated

splice is an independent, personal project. It is **not affiliated with, endorsed by, or sponsored by** Anthropic, OpenAI, xAI, Moonshot, or OpenRouter. All product names and trademarks belong to their respective owners.

Anthropic identifies routing Claude Code to non-Claude models through a custom gateway as **unsupported**. splice is exactly that kind of gateway; use it with that in mind, at your own risk.

## Why it exists

Long coding-agent sessions bleed tokens and lose the thread. splice goes after both:

- **Cache warmth is engineered, not hoped for.** A stable cache key, opaque encrypted reasoning-item replay, and — critically — **compaction that runs on the session's own model and reasoning effort** keep the prompt cache warm across a long session. A mismatched compaction model *or* effort silently invalidates the cache and re-reads the entire transcript uncached; getting this right is the difference between a session that sips your quota and one that drains it.
- **Reasoning is load-bearing.** The mirror writes the backend's reasoning summary back into the transcript as readable text, so the agent — and you — can see *why* it did what it did, and that context survives compaction.
- **One instrument panel for the fleet.** The daemon serves a single dashboard over every head: live status, start/stop/restart, layered config with provenance, per-head 5-hour usage soft-warnings, auth, and logs.

## Prerequisites

- **Node 24** — Claude Code's own runtime (`claude` must be on your PATH).
- **Java 21** — the spliced daemon ships as a fat jar and runs on the JVM.
- **Python 3** — the launch shim uses it to parse the daemon's JSON launch recipe.
- **curl** — the launch shim's health check and control-plane calls.
- **bash** — the launch shim (`bin/splice-launch`) and `install.sh`.
- **Claude Code on PATH** — splice wraps it; the `claude` binary must resolve.

## Quick start

```bash
./install.sh        # build/fetch the jar → ~/.local/share/splice/, link wrapper commands
splice setup        # materialize the topology, install wrappers, sign in to each backend
claudex             # Claude Code on a ChatGPT Codex backend (:3099)
```

`install.sh` builds the fat jar from a checkout (or fetches a release), installs the shared launch shim `bin/splice-launch`, and links the wrapper commands into `~/.local/bin`. Each wrapper is an `argv[0]` symlink to `splice-launch`, which cold-starts the daemon, asks it for an exec recipe over the loopback control plane, and execs `claude`.

Admin verbs go through the `splice` command:

```bash
splice status         # per-head status
splice dashboard      # open the control dashboard (loopback :3096)
splice init           # write the codex-only starter topology
splice install --all  # (re)link the wrapper commands
<head> login          # OAuth sign-in for one head, e.g. `claudex login`, `claude-grok login`
```

The dashboard and every control endpoint are bearer-guarded and loopback-only. The unlock key lives at `~/.claude-codex/state/mgmt-key`.

## Credential locations

Each of these is a **password-equivalent secret** — anything that can read the file (or the environment variable) can spend against your account. Keep files `600`, never commit them, never paste them.

| Backend / route | Auth kind | Location | Notes |
| --- | --- | --- | --- |
| codex (ChatGPT) | `chatgpt-oauth` | `~/.codex/auth.json` | OAuth tokens — password-equivalent |
| grok (xAI) | `grok-oauth` | `~/.grok/auth.json` | OAuth tokens — password-equivalent |
| kimi (Moonshot) | `kimi-oauth` | `~/.kimi/credentials/kimi-code.json` | device-flow token — password-equivalent |
| OpenRouter | `api-key` | `$OPENROUTER_API_KEY` (env) | API key — password-equivalent |
| Moonshot (pay-per-token) | `api-key` | `$MOONSHOT_API_KEY` (env) | API key — password-equivalent |
| splice control plane | — | `~/.claude-codex/state/mgmt-key` | dashboard/API unlock key — password-equivalent |

## Provider support

| Route | Auth | Status |
| --- | --- | --- |
| OpenRouter | `api-key` (`OPENROUTER_API_KEY`) | **Supported** — pay-per-token, any OpenAI-compatible vendor |
| Moonshot | `api-key` (`MOONSHOT_API_KEY`) | **Supported** — pay-per-token Anthropic base |
| codex (ChatGPT) | `chatgpt-oauth` | **Experimental**, pending vendor clarification |
| grok (xAI) | `grok-oauth` | **Experimental**, pending vendor clarification |
| kimi (Moonshot) | `kimi-oauth` | **Experimental**, pending vendor clarification |

The **api-key** routes are ordinary pay-per-token API access and are supported. The **OAuth-identity** routes are **experimental**: they authenticate by reusing the public OAuth client identities of each vendor's own CLI, not a documented third-party integration. Whether that reuse is permitted is not settled — treat these routes as experimental pending clarification from each vendor, and use them at your own risk.

## Backends and protocols

splice speaks several upstream wire dialects (`openai-responses`, `openai-chat`, `anthropic-passthrough`), selected per provider in the topology.

The codex backend at `https://chatgpt.com/backend-api/codex` is a **ChatGPT / Codex backend that speaks a Responses-STYLE protocol** — the internal endpoint the ChatGPT Codex product itself uses. It is **not the public OpenAI Responses API**, and nothing here should be read as targeting that public API.

## Reasoning

"Reasoning" here means one of three concrete, narrow things — never the model's raw private chain-of-thought:

- **Provider-generated reasoning summaries** — a short summary the backend itself produces and returns.
- **Readable reasoning fields explicitly supplied** by the backend on the wire (e.g. `reasoning_text` / summary fields).
- **Opaque encrypted reasoning-item replay** — carrying the backend's own encrypted reasoning items forward into a later request, verbatim and unread.

splice never has, exposes, or reconstructs the model's raw chain-of-thought or exact reasoning. The **mirror** writes only the provider-generated summary text back into the transcript.

## The cache-replay experiment

`experiments/cache-replay/` is a self-contained A/B that probes one question: **does replaying opaque encrypted reasoning items back into a request bust the prompt cache?** It runs a fixed multi-turn conversation twice — once carrying the encrypted reasoning items forward, once dropping them — and reports cached vs. uncached input tokens per turn.

- `real-ab.sh` — two isolated real Claude-Code sessions on a side-port, same turns, only the replay toggled.
- `run.mjs` / `replay-captured.mjs` — dependency-free Node harnesses; `replay-captured.mjs` replays a captured, sanitized transcript so the A/B is reproducible without live credentials.

Findings so far are **directional, not conclusive** — single runs are noisy and part of the token delta is simply that replay ships the reasoning items as extra input. Read `experiments/cache-replay/README.md` for the caveats and run it yourself.

## Layout

```
gateway/       Kotlin daemon (spliced) — Gradle multi-module, JDK 21; the PRIMARY stack
config/        splice.example.toml — the sample multi-provider topology
bin/           splice-launch (the installed wrapper) + legacy Node shims (claudex, claudex-next)
install.sh     fetch/build the jar, install the shim, link wrapper commands
webui/         React 19 + Vite + Zustand dashboard, single-file build
experiments/   cache-replay A/B reproducer
server/        LEGACY Node proxy stack — still runnable during cutover, not the primary path
.rules/        ast-grep "walls" enforced write-time AND at the commit gate (same rules twice)
```

The **gateway/** Kotlin daemon is the primary stack. The **server/** Node stack (and the `bin/claudex` / `bin/claudex-next` shims that drive it) is legacy, kept runnable during cutover but no longer the documented entry point.

## Development

```bash
cd gateway && ./gradlew check                # module-law + behavior suites (JDK 21)
npm run lint -w webui && npm test -w webui && npm run build -w webui
npm test -w server                           # the legacy Node stack's suites
```

Contracts and invariants live in `AGENTS.md`; the change log in `CHANGELOG.md`; the wall doctrine in `.rules/README.md`.

## License

[MIT](LICENSE).
