# splice 0.4.0 — draft feature list

- Status: **discussion draft — not an approved campaign**
- Created: 2026-09-05
- Rechecked: 2026-09-13 against `main` at `ef6db47c` (the v0.3.2 cut); every evidence pointer
  below was re-read at that commit
- Evidence baseline: `ef6db47c` (was `a1bdcb08` at creation)
- Amended: 2026-09-13, section 11 (automatic account switching) added as user-required
- Questions closed: 2026-09-13. Every "To resolve" block below now carries a "Resolved" block:
  product forks were answered by Marcos in session, the rest are engineering defaults chosen
  from the code and from the sources listed at the end, each reversible at campaign time

This folder holds the feature discussion for 0.4.0. We will iterate on this document before
creating a campaign ledger, implementation tasks, or dispatching builders. Candidates remain
proposals unless explicitly marked as user-required for 0.4.0. Detailed scope and acceptance
criteria remain open for iteration; no implementation is authorized by this document.

## Proposed release direction

**Make splice's existing power easier to access and trust, rather than simply adding more
providers.**

The project already has model switching, per-model context windows, plan usage, recovery,
doctor, a management dashboard, and cross-head messaging. These proposals build on those
foundations instead of treating them as missing features.

The ranking below is an engineering/product judgment grounded in the repository, not a
measured customer-demand ranking. Source references describe the baseline above and should
be rechecked before campaign creation.

Custom compaction instructions add a model-quality goal: let users tune what survives
compaction for the model and project, rather than assuming one instruction fits every session.
Shared MCP hosting adds resource efficiency across parallel sessions; a redesigned console
makes the full configuration and usage surface accessible; local-model support adds a
local inference option alongside hosted providers. Automatic account switching keeps a
session working when one account on a provider hits its limits, which is what makes the
console's multiple-logins-per-provider requirement necessary rather than cosmetic.

## What landed between the first draft and this recheck

v0.3.1 (2026-09-06) and v0.3.2 (2026-09-07) shipped after the draft was written. The items
that bear on the proposals below:

- **Code mode beta** (`code_mode = true` on a `chatgpt-oauth` + `openai-responses` provider):
  a splice-bundled JavaScript runner in child JVMs, four workers per daemon, exclusive to
  Claudex-compatible providers (`README.md:219`, `config/splice.example.toml:65`). 0.3.2 added
  idle reaping, eviction at capacity, and evidence bounded by the upstream ceiling after parked
  cells held every slot for an hour on 2026-09-07 (`CHANGELOG.md`, v0.3.2). Whether 0.4.0
  graduates or widens the beta is not decided here.
- **Every OAuth head signs in on its own credential file** under `~/.config/splice/auth/`
  (`README.md:193`). Guided setup now has a fixed target for the auth step.
- **A context window edited in `splice.toml` reaches running sessions**; every other topology
  edit is still boot-only and needs `splice restart` (`README.md:234`). The console proposal
  must keep that distinction visible.
- **Compaction outlives its client, is built byte-identical to a turn for the prompt cache,
  and scales counts against the session's own window.** Custom compaction instructions must
  not undo the cache hit that byte-identity buys.
- **Silent streams send real SSE pings** (Claude Code aborts after 600 s without a yielded
  event) and **a failure no retry can change ends the turn in words** (Claude Code re-sends an
  `api_error` identically before content and hides its message after content). Both are
  client-behavior discoveries made by users mid-session, which is the case for section 2.
- Perf rows now carry 36 fields (`PerfKeys.kt`), including `attempts`, `retries`, `refreshes`,
  `backoff_ms`, `cached_tokens`, `inflight`, `async_io_drops`, frames and bytes in each
  direction. Section 3 has more raw material than the draft assumed.
- The fresh-machine Docker e2e ran against the published assets for both releases
  (`checks/e2e/receipts/docker-*.json`); the live-provider probe schedule is still commented
  out (`.github/workflows/live-probe.yml:20`).

## Feature shortlist

| Rank | Feature | Primary value | Release status / recommendation |
|---|---|---|---|
| Required | Custom compaction instructions | Continuity: tune compaction globally, per model, and per project/model | User-required for 0.4.0 (2026-09-05) |
| Required | Shared MCP hosting across sessions | Efficiency: reuse one server instance per compatible MCP configuration across parallel sessions | User-required for 0.4.0 (2026-09-05); target >50% memory reduction, not yet measured |
| Required | Redesigned, complete splice console | Control: every knob, effective configuration, usage, and subscription data in one console | User-required for 0.4.0 (2026-09-05) |
| Required | Local-model support | Choice: use locally hosted models through splice | User-required for 0.4.0 (2026-09-05) |
| Required | Automatic account switching on provider limits | Continuity: a session keeps working when one account's quota or rate limit is hit; the console holds several logins per provider | User-required for 0.4.0 (2026-09-13) |
| 1 | Guided provider setup | Adoption: connect another provider without editing TOML | Selected for 0.4.0 (2026-09-13) |
| 2 | Compatibility early warning | Reliability: warn when the client is newer than the version this release was tested with | Selected for 0.4.0 (2026-09-13); reshaped, no scheduled job |
| 3 | Performance visibility | Diagnosis: explain slow and failing turns | Selected for 0.4.0 (2026-09-13) |
| 4 | First-class session visibility | Differentiation: make cross-head coordination discoverable | Selected for 0.4.0 (2026-09-13), read-only first |
| 5 | Safe, straightforward upgrades | Maintenance: update confidently with a recovery path | Selected for 0.4.0 (2026-09-13) |
| 6 | Shareable, redacted diagnostics | Support: make useful bug reports easier | Selected for 0.4.0 (2026-09-13) |

Ranks express the initial recommendation, not implementation order. All eleven items were
selected for 0.4.0 on 2026-09-13; the sections below are still proposals, not campaign items.

## 1. Guided provider setup

**User outcome:** connect a second provider without understanding the topology format.

Today, `splice setup` materializes only the supported OpenRouter API-key starter
(`SetupCommand.kt:21`); the CLI has no `add` verb (`Command.kt` lists doctor, version,
shim-version, init, install, uninstall, login, setup, status, restart, dashboard, key, logs).
Broader configurations still require manual TOML editing. Topology validation
(`Topology.kt:111` onward: auth-kind and dialect pairing, positive context windows, non-empty
and duplicate-free model lists, known Claude slots, pinned model present) and the per-head
credential files (`README.md:193`) are the foundation for a guided flow.

Proposed scope:
- Add `splice add`, or extend `splice setup`; the command shape is not decided yet.
- Select a provider, authenticate, select or enter models, and configure context windows.
- Validate configuration and credentials; explicitly offer any quota-consuming live checks.
- Save topology atomically, install the wrapper, and apply changes with clear restart handling.
- Finish with doctor and a concrete launch instruction.

**Proposed acceptance:** a fresh installation can add a second supported provider through the
public CLI without editing TOML; existing heads and settings remain intact. Failed or
cancelled setup leaves the previous configuration usable.

**Resolved 2026-09-13:**
- Profiles are exactly the auth-kind/dialect pairs `Topology.kt` already validates, no new
  provider: `chatgpt-oauth` + `openai-responses` (Claudex), `claude` passthrough, `kimi-oauth`,
  `muse-oauth` + `anthropic-passthrough` (claude-muse), `grok-oauth`, `api-key` + `openai-chat`
  (OpenRouter and any OpenAI-compatible endpoint, which is also the local-model path of section 10).
- Local checks run always and spend nothing: TOML validation, credential file present and
  unexpired, base URL reachable, model list fetched where the dialect has one. The only live
  check is one short turn on the chosen model, offered as a yes/no step and skipped by default.
- Command shape: `splice add <profile>`, with `splice setup` kept as the OpenRouter starter.

Evidence (rechecked 2026-09-13): `gateway/app/src/main/kotlin/splice/app/cli/SetupCommand.kt:21`,
`gateway/app/src/main/kotlin/splice/app/cli/Command.kt:20`, `config/splice.example.toml:1`,
`gateway/core/src/main/kotlin/splice/core/topology/Topology.kt:111`, `README.md:193`.

## 2. Compatibility early warning

**User outcome:** learn about client/provider incompatibilities before a working session breaks.

The two client-behavior discoveries of this cycle (the 600 s stall abort, the mid-stream
`api_error` rendering) were both found by users mid-session and read from the client binary
afterwards. Both arrived with a Claude Code update. The Docker e2e already proves each release
against one pinned Claude Code version (`checks/e2e/receipts/docker-*.json`), but that version
is not recorded where the running daemon can compare it with the client actually installed.

**Reshaped 2026-09-13 (Marcos: no scheduled job).** The first draft proposed scheduled checks
against the latest client and budget-capped live-provider probes. That needs a place to run and
a logged-in account for nothing the user asked for. The useful part is a version comparison:

Proposed scope:
- The release records the Claude Code version its Docker e2e ran against (release asset and
  `Versions.kt`), and the receipt carries it.
- The client version is on every request (verified 2026-09-13 against a local dump server:
  `User-Agent: claude-cli/2.1.257 (external, sdk-cli)`), so the daemon compares it per
  session with no launch-time hook; the sessions registry carries the same `version`. When that version is newer
  than the tested one, doctor, `splice status` and the status line say so once: "Claude Code X
  is newer than the version splice Y was tested with (Z)". Older or equal is silent.
- No live-provider checks. Provider drift already surfaces as the honest failure text of 0.3.2;
  `live-probe.yml` stays commented out and is not part of this feature.

**Proposed acceptance:** a client version above the recorded one produces the warning in all
three surfaces; an equal version produces nothing; the recorded version matches the receipt.

**Resolved 2026-09-13:** cadence, budget and credentials do not apply; results are shown in
doctor, `splice status` and the status line, from the version recorded in the release.

Evidence (rechecked 2026-09-13): `.github/workflows/live-probe.yml:20`, `checks/e2e/README.md:93`,
`checks/e2e/receipts/` (docker receipts for v0.3.1 and v0.3.2), `README.md:22` (the
unsupported-gateway note that makes drift the expected failure mode).

## 3. Performance visibility

**User outcome:** understand why a session is slow or failing without reading raw logs.

The control plane already exposes `/api/perf` (per head: count, p50, p95, max per field,
`PerfPayloads.kt:23`), and the gateway records 36 fields per turn, marks first then counters
(`PerfKeys.kt:7`): stage marks from `recv` to `total`, `attempts`, `retries`, `refreshes`,
`backoff_ms`, tokens in, out and cached, `inflight`, `async_io_drops`, eager and deferred tool
counts, frames and bytes in each direction. The dashboard's tabs are fleet, auth, config, logs
and compaction (`App.tsx:12`); none is a performance view. Per-turn `perf outcome=` lines and
`[<head>][code-mode]` lines in the head log are the only current reading surface.

Proposed scope:
- Show per-head latency distributions, retries, cache behavior, and load where data exists.
- Distinguish queue pressure, upstream delay, and failure outcomes where instrumentation supports it.
- Show the observation window and missing or dropped telemetry explicitly.
- Reuse the current performance API; add instrumentation only for a specifically identified gap.

**Proposed acceptance:** controlled slow, retried, and failed requests produce distinguishable
results in the dashboard. Empty or stale data is not presented as healthy zero-latency traffic.

**Resolved 2026-09-13:**
- Smallest useful view: one table per head over a selectable window (last hour, 24 h, 7 d):
  p50/p95 of `first_byte` and `total`, failure share by outcome tag, `retries` and
  `refreshes` totals, cache hit ratio (`cached_tokens` / `in_tokens`), peak `inflight`, and
  the count of rows with `async_io_drops` > 0 shown as "telemetry dropped".
- Attribution available today: head, model, outcome tag, and turn kind (turn, compaction,
  activity side query). Nothing attributes a slow turn to a cause; the view labels
  "time before first byte" and "time streaming" and stops there.
- Retention: the perf lane rotates at 64 MiB with one previous file (`JsonlSink.kt:166`). On
  this machine that is 142k rows per 12 days, so the two files cover about 20 days. Enough for
  the 7-day window; a 30-day view needs a daily roll-up written by the daemon, not longer raw
  retention.

Evidence (rechecked 2026-09-13): `gateway/control/src/main/kotlin/splice/control/api/PerfPayloads.kt:23`,
`gateway/core/src/main/kotlin/splice/core/perf/PerfKeys.kt:7`, `console/src/app/App.tsx:12`.

## 4. First-class session visibility

**User outcome:** see which sessions are working on which heads and deliberately connect them.

Cross-head discovery and messaging already ship through `ListAgents` and `SendMessage`.
The opportunity is to make that capability visible and approachable, not invent another
orchestration system.

Proposed scope:
- Add a `splice sessions` listing and a dashboard session view.
- Show session identity, head/model, age, and activity where the registry provides reliable data.
- Expose explicit messaging or copyable coordination instructions through existing mechanisms.
- Distinguish active sessions from stale or unavailable registry entries.

**Proposed acceptance:** sessions on different heads are discoverable and addressable without
manual registry inspection; unavailable sessions are not represented as active.

**Resolved 2026-09-13:**
- Registry metadata (read 2026-09-13 from `~/.claude/sessions/<pid>.json`, Claude Code
  2.1.257): `pid`, `sessionId`, `cwd`, `name` and its source, `kind` (interactive), `entrypoint`,
  `version`, `status` (for example `shell`) with `statusUpdatedAt`, `startedAt`, `updatedAt`,
  `messagingSocketPath`, `peerProtocol`, `peerFeatures`, `bridgeSessionId`. Head and model are
  not in the registry; splice knows them because it launched the client, so the listing joins
  registry rows to heads by the launch it made, and shows "unknown head" otherwise.
- Headless `claude -p` runs do not appear in the registry (verified 2026-09-13); the listing
  says so rather than count them.
- The first version is read-only: `splice sessions` and a console page list the rows, mark
  entries whose pid is gone or whose `updatedAt` is stale as unavailable, and print the
  copyable `SendMessage` instruction for each live session.
- No socket writes from splice. The messaging socket is Claude Code's internal protocol,
  gated by the per-session `.key` files, so sending stays inside Claude Code where the user
  confirms it. Revisit only if the protocol is documented.

**Boundary:** no automatic transcript migration, opaque provider switching, or new campaign
orchestration engine in this proposal.

Evidence (rechecked 2026-09-13): `README.md:170` (the shared `~/.claude/sessions` registry,
`README.md:172` to `README.md:176`), `console/src/app/App.tsx:12`; no `sessions` verb exists in
`gateway/app/src/main/kotlin/splice/app/cli/Command.kt`.

## 5. Safe, straightforward upgrades

**User outcome:** upgrade with one command and recover safely if verification fails.

Release installation already verifies checksums and attestations, but requires Java 21+
(`README.md:72`) and an authenticated GitHub CLI (`README.md:77`). The current release path is
reinstall-oriented rather than a dedicated upgrade workflow: this cycle's three local installs
were jar copies with dated `.bak` files and a manual `systemctl --user restart`, and the
release `install.sh` replaces `bin/splice-launch` wholesale. A restart drops in-flight turns;
Claude Code re-sends them as transport retries once the daemon is back (observed 2026-09-07),
which bounds the "active sessions" cost but does not remove it.

Proposed scope:
- Add `splice upgrade` with version selection/checking, verified download, and preflight.
- Preserve configuration and credentials; handle active sessions explicitly before replacement.
- Keep a verified recovery path and run doctor after the upgrade.
- Preserve existing supply-chain verification guarantees.

**Proposed acceptance:** exercise a successful upgrade and a failed-upgrade recovery without
losing configuration or credentials. Verification failure prevents activation of the candidate.

**Resolved 2026-09-13:**
- Layout is the one `install.sh` already writes: jar and assets under
  `~/.local/share/splice/`, wrappers in `~/.local/bin/`, state in `~/.splice/state/` (a pre-0.4
  install keeps `~/.claude-codex/state/`, adopted in place — V4-177),
  config and credentials in `~/.config/splice/`. Upgrade keeps each release in its own
  versioned directory under the share dir and points the wrapper at the current one; config and
  credentials are never touched because they live elsewhere.
- Locally modified wrappers are respected: the hostshield launcher patch edits
  `~/.local/bin/splice` after install. Upgrade compares the wrapper against the previous
  release's copy and, if it differs, keeps the edited file and prints the diff instead of
  overwriting.
- Active sessions: upgrade stages and verifies the candidate, then waits for every head's
  `inflight` on `/api/heads` to reach zero before restarting the unit; `--now` skips the wait.
  Claude Code re-sends the dropped turn as a transport retry either way.
- Rollback: `splice upgrade --rollback` repoints the wrapper at the previous directory and
  restarts. The previous release is kept until the next successful upgrade.
Bundled minimal JVMs or native executables are later packaging possibilities, not an approved
architecture change or a prerequisite for this feature.

Evidence (rechecked 2026-09-13): `README.md:63`, `README.md:72`, `README.md:77`, `README.md:83`,
`gateway/app/src/main/kotlin/splice/app/cli/Cli.kt:13`.

## 6. Shareable, redacted diagnostics

**User outcome:** produce a useful bug report without exposing secrets or conversation content.

Doctor already composes prerequisite, configuration, daemon, auth, and runtime checks.
This proposal packages those findings for repeatable support rather than introducing a
second diagnostic engine.

Proposed scope:
- Add `splice doctor --json` and consider a separate report command if needed.
- Include versions, sanitized configuration shape, health findings, and bounded diagnostic events.
- Exclude credentials, conversation content, and sensitive local identifiers by default.
- Generate locally with a preview; never upload automatically.

**Proposed acceptance:** reports are machine-readable and useful for an injected failure;
synthetic secrets and conversation content do not appear in the output.

**Resolved 2026-09-13:**
- Schema (`splice doctor --json`, `schema_version` 1): splice and Claude Code versions, OS and
  JVM, the topology shape (provider kind, dialect, model ids, context windows, quirks; no URLs
  with credentials), the doctor checks as `{id, status, detail}`, and the last 200 perf rows
  restricted to the 36 numeric fields plus head, model and outcome tag.
- Redaction is an allowlist: only named fields are emitted, so a new field is absent until
  someone adds it. Account identifiers, emails, tokens and the session cwd never appear;
  paths under the home directory are shown relative to `~`.
- Raw logs are excluded. `--with-logs` appends the last 500 lines of the daemon log after the
  same redaction pass used by `checks/config/safe-failure-render.py`, and prints the result to
  the terminal before writing it so the user sees what leaves the machine.

Evidence (rechecked 2026-09-13): `gateway/app/src/main/kotlin/splice/app/cli/DoctorCommand.kt:39`
(no `--json` today; secrets reported by presence only, `DoctorCommand.kt:3`),
`gateway/app/src/main/kotlin/splice/app/cli/DoctorRuntime.kt:17`,
`gateway/app/src/main/kotlin/splice/app/cli/Command.kt:20`.

## 7. Custom compaction instructions

**Release status:** user-required for 0.4.0, added 2026-09-05. Section number is not priority.

**User outcome:** tune how a model summarizes a session so that the information needed to
continue good work survives compaction, with different instructions for different models
and projects.

**Motivation:** Marcos cites the new GPT Astra model's AGI benchmark results as evidence that
well-tuned compaction can substantially improve model quality. Source verified 2026-09-13:
ARC Prize ran GPT-6 Astra on ARC-AGI-3 Semi-Private twice; its own Standard harness scored
62.7%, and OpenAI's Provider Adapter harness, which "preserves opaque reasoning state between
requests and uses compaction for longer conversations", scored 99.9% (arcprize.org/blog/astra,
2026-09-03). OpenAI's 2026-07-29 post on GPT-5.6 Sol attributed a tripled score to the same two
settings. The evidence is that compaction quality moves outcomes by tens of points; it is not a
measurement of user-written instructions, which is what this feature adds and must measure
itself.

Required configuration scopes:
- **Global:** instructions used generally when no more-specific override applies.
- **Per model:** instructions tailored to a particular model across projects.
- **Per project/model:** instructions tailored to that model's work in a particular project.

Proposed scope:
- Resolve instructions with explicit precedence: project/model → model → global → existing
  default behavior. This is the proposed resolution order; composition semantics remain open.
- Apply the resolved instructions to actual compaction requests, not ordinary conversation turns.
- Resolve against the session's selected model and project; do not leak settings between
  concurrent sessions, projects, or models. Make model-switch behavior testable.
- Make the effective instructions and their configuration source inspectable.
- Preserve current behavior when no custom instructions are configured.
- Support instructions about what to retain: project constraints, decisions and rationale,
  unresolved work, evidence, and references needed to resume. These are examples, not a
  mandatory universal template or claims that any particular wording improves quality.

**Proposed acceptance:**
- Tests cover all three scopes, their precedence, and fallback to unchanged default behavior.
- The resolved instructions reach the actual compaction path; ordinary requests are unaffected.
- Concurrent projects/models and a model switch select the correct instructions without leakage.
- Instructions do not cause normal turns to be misclassified as compaction. Existing detection,
  model/effort continuity, tool stripping, honest failures, and no-fabricated-summary contracts hold.
- Compare default and tuned instructions on the same long-session tasks for each evaluated
  model, recording instruction version, model, context/summary budget, and repeated-run results.
  Evaluate retained constraints, factual fidelity, and downstream task success—not just summary
  appearance or prompt application. Report regressions as well as gains; do not invent an
  improvement threshold before establishing a baseline.

**Resolved 2026-09-13:**
- The most specific scope replaces the text; there is no layering. Precedence:
  project/model, then project (all models), then model, then global. A project-wide default
  is therefore included at no extra cost.
- Three states: key absent inherits the next scope; `instructions = ""` opts out of every
  inherited text and runs the client's compaction unchanged; any other string is the text.
- Custom text never replaces Claude Code's own summarizer prompt (detection stays the verbatim
  marker, `AGENTS.md:191`). It rides as an addition at the tail of the request, after the
  client's prompt, so the cached prefix is byte-identical to the turn before it. Claude Code
  itself accepts `/compact <focus>` per invocation and a "Compact Instructions" section in
  CLAUDE.md (project-wide, all models); splice adds the per-model and per-project/model axes
  that neither has.
- Configuration lives in `splice.toml`: `[compaction]` for global, `[[compaction.model]]`
  with `model = "<wire id>"`, `[[compaction.project]]` with `path = "/abs/dir"` and an optional
  `model`. Text is inline or `file = "..."`. The console edits the same table.
- Session-to-project identification, verified 2026-09-13 by capturing Claude Code 2.1.257
  requests on a local dump server: every `/v1/messages` request carries
  `metadata.user_id` as a JSON string `{"device_id", "account_uuid", "session_id"}` and the
  same id in the `X-Claude-Code-Session-Id` header. Interactive sessions map that id to a
  `cwd` through `~/.claude/sessions/<pid>.json`; headless `claude -p` runs do not register
  there, so the fallback is the transcript directory `~/.claude/projects/<encoded cwd>/<id>.jsonl`.
  A project matches when `cwd` is inside `path`, longest path wins; an unknown session means
  global scope.
- Model identity is the wire model id after suffix stripping (the pinned row's id), so head
  aliases and `[1m]` spellings collapse to one entry; a model switch is picked up on the next
  compaction because resolution happens per request.
- Evaluation set: ten of this machine's recorded long sessions with a compaction in them,
  replayed through the gateway with default and tuned text, graded on retained constraints and
  on the next turn's task success, per the acceptance above. No external benchmark.
- One seam per dialect, each appending the tail item in that dialect's shape: a user input item
  on `openai-responses` (the lite developer item at `ResponsesQuirks.kt:19` is untouched), a
  text block on the last user message for the Anthropic passthrough, a user message on
  `openai-chat`.

**Boundary:** this configures model-generated compaction. It does not authorize fabricated
summaries, a separate transcript-shrinking mechanism, reasoning replay changes, or an
implementation before the campaign is approved.

Requirement source: user discussion, 2026-09-05. Existing contracts (rechecked 2026-09-13):
`AGENTS.md:43` (compact turns inherit the session's model and effort), `AGENTS.md:191`
(detection is the verbatim summarizer-prompt marker, never a heuristic), `AGENTS.md:200`
(no transcript shrinking, no fake summaries). Seams: `ResponsesQuirks.kt:19` and the
byte-identical compaction build from 0.3.1. Precedence resolution and the configuration
surface still need investigation.

## 8. Shared MCP hosting across parallel sessions

**Release status:** user-required for 0.4.0, added 2026-09-05.

**User outcome:** parallel sessions share MCP server processes rather than each launching
another copy, reducing aggregate memory use without losing tools or session isolation.

**Baseline (rechecked 2026-09-13):** splice hosts no MCP server today. The launcher shares the
operator's MCP configuration into each head's `.claude.json` (`ClaudeConfigMaterializer.kt:15`,
`ClaudeConfigMaterializer.kt:109`; `mcps` is a share/isolate policy item), so every session on
every head still launches its own copies. The nearest precedent is code mode's worker pool: one
daemon-owned pool of four child JVMs handed to sessions on demand. Its 0.3.2 incident is the
design lesson: a session that never came back held a slot for an hour, so a shared instance
needs idle reaping, eviction under pressure, and per-session ownership visible from day one.

**Product goal:** improve on Claude Code's lazy MCP handling by sharing server instances
across sessions, not merely delaying their startup. Target **more than 50% lower memory use**
for the agreed parallel-session workload; the saving and comparison are not yet measured.

Proposed scope:
- Run one shared instance per compatible MCP server configuration, with sessions connected
  through a splice-managed sharing layer. This does not mean combining unrelated integrations
  into one server or sharing credentials across incompatible configurations.
- Preserve per-session request/result routing, cancellation, subscriptions, tool discovery,
  and existing client permission checks. One session exiting must not stop another's server.
- Make sharing eligibility explicit for servers with project roots, working directories,
  environment, credentials, or mutable session state; do not silently merge incompatible contexts.
- Manage startup, idle shutdown, crashes, and reconnection without replaying tool operations
  whose execution status is unknown. Expose instance ownership, attached sessions, and memory.

**Proposed acceptance:**
- Multiple compatible sessions demonstrably use one server process; incompatible contexts
  remain isolated. Concurrent calls and cancellations return only to their originating session.
- Disconnect and crash tests preserve other sessions' access and report failures honestly.
- Compare the same tools and task workload against per-session Claude Code lazy MCP handling,
  recording client/server versions, session count, activated servers, idle/active phases, and
  peak/steady-state memory. Include the sharing layer's overhead and report MCP-process and
  whole-workload memory separately; define which denominator the >50% target applies to before
  measuring. Schema or startup savings alone do not establish memory savings.

**Resolved 2026-09-13:**
- Only stdio servers are hosted. They are the ones that duplicate (one process per client);
  `http`, `sse` and `ws` entries already serve many clients and pass through untouched.
- Sharing identity is the full launch tuple (command, args, env after expansion). Servers whose
  configuration names a project directory or depends on the client's roots are ineligible and
  keep launching per session; eligibility is shown per server in the console.
- Isolation: splice exposes each hosted server over Streamable HTTP on loopback with one MCP
  session per client session (`Mcp-Session-Id`, protocol 2025-11-25, which Claude Code speaks
  as `--transport http`), remaps JSON-RPC ids per client, fans notifications to every session,
  and caches `tools/list`. The MCP 2026-07-28 revision is sessionless and needs no mapping at
  all once Claude Code adopts it.
- Client integration is `ClaudeConfigMaterializer`: an eligible stdio entry is rewritten into
  `{"type": "http", "url": "http://127.0.0.1:<port>/mcp/<name>"}` in each head's
  `.claude.json`; the operator's file is never edited.
- Lifecycle copies code mode: start on first use, idle reap, eviction under pressure, ownership
  visible per session; a crash fails the sessions' pending calls honestly and restarts on the
  next call, never replaying tool operations.
- Benchmark workload: four parallel sessions on the operator's real `mcps` set, RSS of the
  MCP server processes summed with and without hosting (the denominator for the >50% target),
  whole-workload RSS and startup time reported beside it.

Requirement source: user discussion, 2026-09-05. Architecture and baseline need investigation.

## 9. Redesigned, complete splice console

**Release status:** user-required for 0.4.0, added 2026-09-05.

**User outcome:** configure every splice knob and understand usage and subscriptions from a
single coherent console, without hunting through TOML, APIs, and logs.

**Baseline (rechecked 2026-09-13):** the dashboard has five tabs (fleet, auth, config, logs,
compaction; `App.tsx:12`) over eleven control routes (`/api/auth`, `/api/auth/{head}/{action}`,
`/api/compact`, `/api/config`, `/api/daemon/shutdown`, `/api/heads`,
`/api/heads/{head}/{action}`, `/api/logs/{head}`, `/api/perf`, `/api/status`, `/api/usage`).
Since 0.3.1 one knob (a model's context window) is live for running sessions while every other
topology edit is boot-only (`README.md:234`); `code_mode` is a provider-level quirk shared by
every head on that provider (`README.md:221`). The coverage check must carry the live-versus-
restart disposition per knob, not just editable-versus-read-only.

Proposed scope:
- Redesign the existing management console rather than introduce a competing control plane.
- Cover every supported knob across global, provider, head, model, and session/project scopes
  where those scopes exist. Show effective values, inheritance, defaults, and configuration source.
- Provide validated editing, explicit save/apply feedback, and clear distinctions between
  live changes and changes requiring restart. Protect active sessions and keep secrets masked.
- Integrate usage, token/cache accounting, subscription/plan information, quota remaining and
  reset windows where providers expose them. Distinguish measured, estimated, unavailable, and
  stale values; never display missing subscription data as zero usage or unlimited capacity.
- Integrate performance and session visibility from sections 3 and 4 into this design rather
  than build duplicate dashboards. Include shared-MCP and local-model controls as they land.
- Hold several logins per provider (section 11): add, label, remove and re-authenticate
  accounts from the console, show each account's plan and quota windows, show which account
  every head and session is currently on, and offer a manual switch. Today the auth page is
  one card per head with a refresh action only, and browser or device login must run in a
  terminal (`console/src/pages/auth/index.tsx:1`, `AuthRoutes.kt:42`).

**Proposed acceptance:** enumerate the configuration surface from its authoritative schemas
and registries, then require a console disposition for every knob: editable, or explicitly
read-only with a reason. An unrepresented knob fails the coverage check. Tests verify
scope/precedence, validation, persistence, restart requirements, secret handling, and accurate
usage/subscription presentation for available, missing, and stale data.

**Resolved 2026-09-13:**
- Navigation: a left rail with Fleet, Sessions (section 4), Performance (section 3), Accounts
  (auth pools, section 11), Settings, Logs. The existing tabs are reused as the first five
  pages; visual design stays the current console's and is iterated in review, not decided here.
- Advanced settings sit behind a per-section "Advanced" disclosure; every knob shows its
  source scope, effective value and live-versus-restart disposition inline.
- Subscription data by provider (`QuotaProbes.kt:40`): ChatGPT reports plan plus five-hour and
  seven-day windows, Kimi a weekly quota plus rate windows, Grok a weekly credit period; the
  Claude passthrough and API-key providers report nothing and the console says "not reported
  by provider", never zero.
- Inspectable but not editable: anything computed (effective windows, versions, paths),
  secrets (masked, replace-only), and the daemon's own runtime state. Boot-only topology stays
  editable but is labelled "applies on restart".

Requirement source: user discussion, 2026-09-05. Detailed design remains open.

## 10. Local-model support

**Release status:** user-required for 0.4.0, added 2026-09-05.

**User outcome:** use locally hosted models through the same splice setup, model selection,
and session workflows used for hosted providers.

**Baseline correction (rechecked 2026-09-13):** an OpenAI-compatible local endpoint already
works as pure TOML through the `openai-chat` dialect: swap `base_url` and the key env on the
OpenRouter-shaped provider for Ollama (`http://localhost:11434/v1`), LM Studio or similar
(`config/splice.example.toml:155`). Nothing validates that the runtime actually provides the
context limit, tool calling, streaming or reasoning behavior the row declares, nothing surfaces
it as local in doctor or the console, and code mode is exclusive to `chatgpt-oauth` providers
(`README.md:221`). The feature is therefore "make local models first-class", not "connect
them".

Proposed scope:
- Expose local inference endpoints through splice's normal heads and model picker with a
  guided path (section 1) instead of hand-edited TOML. Reuse the `openai-chat` dialect seam
  rather than fork the gateway; a dedicated dialect only for a runtime that cannot speak it.
- Configure and validate endpoint, model identity, actual context/output limits, and supported
  tool, streaming, and reasoning behavior. Do not advertise capabilities a runtime cannot provide.
- Surface local availability, usage, and relevant runtime information in the redesigned console;
  distinguish local inference from subscription-backed usage without inventing pricing or quotas.
- Preserve client permission checks, cancellation, honest failures, and existing model/effort
  contracts. Local inference must not silently fall back to a hosted provider.

**Proposed acceptance:** complete representative coding/tool tasks through an actual supported
local runtime, with versioned model/runtime evidence; verify streaming, cancellation, context
limits, unavailable-model failures, and tool-result continuity. Setup and console accurately
identify local models and do not imply unsupported cloud billing or subscription semantics.

**Resolved 2026-09-13:**
- User-managed runtimes only (Marcos, 2026-09-13): splice discovers the endpoint, lists the
  models the runtime reports, validates what the row declares, and routes. No model downloads,
  no runtime lifecycle, no bundled engine or weights.
- Initial runtime set: Ollama (`/v1`, tool calls stream since its May 2025 parser, context
  length from `/api/show` and the `num_ctx` option), LM Studio (`/v1/chat/completions`, tool
  calls streamed as deltas, native tool support only for the models it lists as such), and
  vLLM (needs `--enable-auto-tool-choice` and a `--tool-call-parser`; documented, not tested
  in 0.4.0). All three speak the `openai-chat` dialect already in the tree.
- Model set: one evaluated model per tested runtime, chosen at campaign time from what the
  runtime lists, with the exact model and runtime versions in the receipt. No hardware
  requirement is stated; doctor reports the runtime's own context and tool-support answers and
  refuses a row that declares more than the runtime returns.

Requirement source: user discussion, 2026-09-05. Runtime selection remains open. Evidence
(rechecked 2026-09-13): `config/splice.example.toml:155`, `README.md:221`,
`gateway/dialect-openai-chat/`.

## 11. Automatic account switching when a provider's limits are hit

**Release status:** user-required for 0.4.0, added 2026-09-13.

**User outcome:** when the account a session is running on exhausts its plan window or is
rate-limited, the next turn goes out on another account of the same provider and the session
keeps working; the operator sees which account is in use and why it changed.

**Baseline (2026-09-13):** splice holds exactly one credential per OAuth kind: the head's
`auth` block names a kind, and the credential lives in one file, `~/.config/splice/auth/<kind>.json`
(`README.md:193`, `config/splice.example.toml:35`). One upstream client per head is one
upstream account, and the 429 cooldown is armed per head on that assumption
(`RateLimitCooldown.kt:3`, `RateLimitCooldown.kt:30`). Plan usage is probed per kind into
`<kind>-quota.json` with the seven-day window's `used_percent`, `resets_at` and `plan`
(`gateway/app/src/main/kotlin/splice/app/quota/QuotaProbes.kt`), and the five-hour and
seven-day windows ride every claudex response and its status line. The console's auth page is
one card per head whose only action is a token refresh; adding a login is `splice login <head>`
in a terminal (`AuthRoutes.kt:21`, `Command.kt:34`). Nothing today can hold a second account
for a provider, let alone choose between two.

Proposed scope:
- **A credential pool per provider.** Several accounts per auth kind, each with a label, its
  own credential file, its own plan and quota state, and its own cooldown. The pool is a
  provider concept; heads and sessions reference an account, never a raw file.
- **Switch triggers that are measured, not guessed.** A window reported exhausted by the
  provider (or above a configurable threshold), or a 429 whose pushback exceeds what a turn
  can wait. The trigger reads the same quota and cooldown state doctor and the status line
  already show; it does not add a second limit detector.
- **Switch between turns, never inside one.** A turn already streaming finishes or fails on
  the account it started on; the switch applies to the next turn. The client sees a normal
  turn either way. The conversation lives in Claude Code, so the upstream holds no transcript,
  but the prompt cache is per account: a switched session pays one cold prefix read, and the
  perf row must say so rather than let it look like a slow upstream.
- **Honest exhaustion.** When every account on a provider is out, the turn fails the way
  limits fail today, naming the earliest reset time across the pool, never a silent hang.
- **Visible everywhere the account matters.** The status line, `splice status`, doctor and the
  console name the account a head or session is on and the last switch and its reason. A
  switch is logged once under `[<head>]`.
- **Console support for multiple logins** (section 9): add, label, remove and re-authenticate
  accounts, see per-account quota windows, pin a head or session to an account, switch by hand.
  A console-initiated OAuth login needs a flow that today exists only in the terminal.

**Proposed acceptance:**
- Two accounts on one provider; exhaust the first with the provider's own quota signal (a
  synthetic `used_percent` at the threshold, `resets_at` in the future) and separately with a
  synthetic 429 carrying a long `Retry-After`. The next turn goes out on the second account
  with no client-visible error, and the status line and console show the switch and its reason.
- A turn in flight when the trigger fires completes on its original account.
- With every account exhausted, the turn fails honestly and names the earliest reset.
- Accounts of different providers can never be confused: a credential file is only ever used
  by the provider whose kind it carries, and a test proves a mislabeled file is refused.
- Per-session and per-head pinning select the intended account under concurrent sessions,
  and a switch on one session does not move another.
- The first turn after a switch is accounted as a cache-cold turn, and the perf row carries
  the account identity so the cost is attributable.
- The pool survives a daemon restart without re-login, and secrets stay masked in every
  surface that lists accounts.

**Resolved 2026-09-13:**
- Granularity: decided per turn, sticky per session. A session keeps the account it last used
  until that account is exhausted; the next turn picks the pool account with the lowest
  seven-day `used_percent` whose five-hour window is open. A switch on one session never moves
  another. The session returns to its primary account at the first turn after that account's
  `resets_at` has passed, so a reset is used as soon as it exists.
- Windows: both count and the trigger is the provider's own signal, no threshold of ours: a
  window reported at or above 100% `used_percent`, or a 429 whose reset is later than the
  turn can wait. OpenAI documents that Codex finishes the active turn after a limit is hit,
  which matches "switch between turns, never inside one".
- codex-rs parity (read 2026-09-13 from `codex-rs/core/src/client.rs`): `session-id` and
  `thread-id` are routing hints for the prompt cache, the account is the `ChatGPT-Account-ID`
  header, and the per-turn `x-codex-turn-state` token must never cross turns. Nothing about the
  thread is stored server-side; the full input is sent every turn. So a switch at a turn
  boundary keeps the same session and thread ids, changes only the account header, and costs
  exactly the one cache-cold turn already in the acceptance list.
- Console login: the daemon runs the same device flow the terminal runs and the console shows
  the verification URL and code; the browser flow stays terminal-only. Labels are chosen by the
  user at login and default to the plan name plus a short hash of the account id; the email
  and account id are never displayed.
- OAuth kinds only (Marcos, 2026-09-13); API keys stay single per provider.
- Terms, verified 2026-09-13: the ChatGPT Terms of Use forbid sharing an account or making it
  available to someone else, not holding several personal subscriptions; the OpenAI Services
  Agreement for business and API customers (§3.3(i)) forbids configuring the service "to avoid
  Usage Limits". The README's unofficial-route warning gains one sentence saying that the
  pool only holds accounts the operator owns, each on its own subscription, and that using it
  to work past a business plan's limits is on the operator.

**Boundary:** switching is between accounts of ONE provider. Cross-provider failover, which
changes model, reasoning, cache and protocol semantics under a session, stays a deferred
direction (see Boundaries). No credential is ever shared across providers or written anywhere
but its own file.

Requirement source: user discussion, 2026-09-13. Evidence: `README.md:193`,
`config/splice.example.toml:35`, `gateway/provider-spi/src/main/kotlin/splice/spi/RateLimitCooldown.kt:3`,
`gateway/app/src/main/kotlin/splice/app/quota/QuotaProbes.kt`,
`gateway/control/src/main/kotlin/splice/control/api/AuthRoutes.kt:21`,
`gateway/control/src/main/kotlin/splice/control/api/UsagePayloads.kt`,
`console/src/pages/auth/index.tsx:1`, `gateway/app/src/main/kotlin/splice/app/cli/Command.kt:34`.

## 12. Head-bounded sessions, cross-head resume on demand

**Release status:** user-required for 0.4.0, added 2026-09-17 (ledger row V4-115).

**User outcome:** a session belongs to the head that started it. The vanilla `claude` binary and
every other head see only their own sessions in `-c` and the resume picker, so `claude -c` can
never restore a claude-deepseek transcript and warn that its model is unknown. When a session
must move heads (an account runs dry, a provider is down), `claude-kimi -r <session-id>` pulls
it over on demand: the transcript is copied into the calling head's own tree with its model
fields rewritten to that head's pinned model, so the client restores exactly what the head
serves.

**Baseline (2026-09-17):** the 2026-09-16 cross-head resume (V4-64/V4-65, commit `91d68f3e`)
symlinked every head's `$CLAUDE_CONFIG_DIR/projects` at the operator's global
`~/.claude/projects` so `--resume` on any head could list every session. Measured cost: three
heads link there today, 95 transcripts in the vanilla tree carry head model ids (gpt-6-astra 36,
gpt-5.6-sol 33, deepseek-flash 24, k3-256k 8, gpt-5.6-luna/terra 4), and the vanilla client
prints `Session model deepseek-flash could not be restored (not a model this version of Claude
Code recognizes)` whenever `-c` lands on one. Operator ruling: head configuration and details
never leak into other heads, their wrappers, or the core binary's sessions.

Proposed scope:
- **Bounded by head.** Each head owns a real `projects/` tree under its own `CLAUDE_CONFIG_DIR`;
  the shared-tree link is retired with a migration that removes the symlink and never touches
  the vanilla tree's content. The live-session registry (`sessions/`, what cross-session
  messaging depends on) is the one dispositioned exception and is asserted as such.
- **Cross-head `-r` on demand.** A head launched with `-r <id>` it does not own resolves the id
  across the other heads' trees (same encoded cwd first), copies `<id>.jsonl` and its `<id>/`
  subdir into its own tree (a copy, never a link; the source stays byte-identical), rewrites the
  assistant messages' `model` to the head's pinned model, then launches. An id that exists
  nowhere refuses with a clear message. The picker without an id stays head-bounded.
- **Model follows the head.** Whether writing the head's roster into the per-head
  `settings.json` `availableModels` also makes the restore succeed for the head's own sessions
  is settled by reading the client, and the materializer writes it if so.
- **Walls before the fix.** A core test materializes a head into a temp HOME and asserts nothing
  under it links or writes outside the head dir (red on the current code); an ast-grep rule
  forbids naming the vanilla config dir in main sources outside one dated site.
- **Operator-side repair after install:** the 95 leaked transcripts move from `~/.claude/projects`
  into their heads by model-id attribution, with a manifest; alias-named sessions stay.

**Boundary:** no shared tree of any kind between heads or with the vanilla client; no
background sync. Cross-head resume is explicit and per session.

Requirement source: user rulings 2026-09-17 ("Head configurations and details should NEVER leak
into other heads binary wrappers or the core original claude binary sessions"; "sessions bounded
by the head, BUT available to be resumed by another head if necessary via -r"). Evidence:
`gateway/core/src/main/kotlin/splice/core/launch/ProjectsLink.kt:1-30`,
`gateway/control/src/main/kotlin/splice/control/LaunchService.kt:71,124,128`, the
`~/.claude-claude-{deepseek,kimi}` and `~/.claude-claudex` `projects` symlinks, and the transcript
census over `~/.claude/projects` (95 sessions with non-`claude-*` model ids).

## 13. Per-project system prompt, and per head of that project

**Release status:** user-required for 0.4.0, added 2026-09-18 (ledger row V4-124).

**User outcome:** a repo can carry its own standing instructions, or replace the system prompt
outright, and a head can carry different ones inside that repo. The operator writes them in
`splice.toml` — a `[projects.<repo root>]` table with `system_prompt`, `system_prompt_file`
and `system_prompt_mode`, and a `[projects.<repo root>.heads.<key>]` table with the same three
keys — and every turn of a session started in that repo carries them. A prompt file may live in
the repo itself (`system_prompt_file = ".splice/prompt.md"` resolves against the project root).

**Baseline (2026-09-18):** the per-head layer exists since V4-36 (`[heads.<key>]`, append or
replace at the provider's system seam, applied on every turn by `TurnPreparation`). Nothing is
keyed by the repo: two projects on one head share one prompt.

Proposed scope:
- **Resolution.** The turn's session id maps to its working directory the way compaction
  scaling already does (`SessionProject`: the live registry, else the transcript); the deepest
  configured project root that contains the cwd wins; a session with no resolvable cwd gets the
  head layer only and the perf row says so.
- **Composition.** Layers apply in the order head, project, project-head. `append` layers stack
  as separate text blocks beside the client's own system field, so cache breakpoints and the
  prompt cache are untouched; a `replace` at any layer substitutes the client's field and every
  earlier layer, and later appends still follow it. No `[projects]` table means today's bytes,
  pinned.
- **Doctor.** Every project layer is listed with its root, mode and source; every `replace` is a
  WARN, as for heads; an unreadable file or a table with both text and file is a load error.

Non-goals: a per-directory prompt below the repo root, or reading the prompt from the repo
without the operator naming the repo in `splice.toml` (the config file stays the source of truth
for what splice injects).

## Boundaries and deferred directions

- Do not add providers merely to increase the provider count; establish the user need first.
- Defer opaque automatic cross-provider failover/load balancing. Reasoning, cache, quota,
  and protocol semantics differ; explicit coordination is the current recommendation.
  Automatic switching between accounts of the same provider (section 11) is in scope
  precisely because none of those semantics change with the account.
- Preserve the single-user, loopback product boundary. Remote/multi-user hosting is not a
  missing feature to quietly fold into this release.
- Preserve existing reasoning, honest terminal-state, configuration, and verification contracts,
  including the 0.3.2 rule that a failure no retry can change ends the turn in words rather
  than an error event.
- User-required features record release intent, not authorization to implement. No campaign
  ledger, builder dispatch, or implementation is authorized by this draft.
- Deferred from the 2026-09-13 close-out: scheduled live-provider probes (`live-probe.yml`
  stays commented out), splice writing to Claude Code's session messaging socket, managed
  local-model downloads and runtime lifecycle, API-key pools, widening code mode beyond
  Claudex-compatible providers, and a 30-day performance roll-up.

## Next discussion

Decided 2026-09-13 (Marcos, in session):
- Release theme and ranking stand as written above.
- All eleven items ship in 0.4.0: the five user-required features and ranks 1 to 6, with
  section 2 reshaped to a version-drift warning and no scheduled job.
- Code mode graduates in 0.4.0, Claudex-only: the beta label is dropped and `code_mode`
  defaults on for `chatgpt-oauth` + `openai-responses` providers; no other dialect gets it.
- The single tool result over 64 KiB that code mode still rejects is truncated at admission in
  0.4.0, with the truncation marked in the evidence the same way the 0.3.2 `[truncated N chars]`
  marker does it; no separate patch release unless it recurs live first.

Still open before a campaign is created:
- Recheck source assumptions and refine acceptance criteria before converting approved scope
  into a campaign through the existing campaign CLI. This recheck holds for `ef6db47c`; repeat
  it at campaign creation.

## Sources consulted for the 2026-09-13 close-out

- Claude Code docs, context window and memory pages (code.claude.com/docs/en/context-window,
  /memory): `/compact <focus>`, what survives compaction, `autoCompactWindow`.
- ARC Prize, "OpenAI's GPT-6 Astra on ARC-AGI-3" (arcprize.org/blog/astra, 2026-09-03) and the
  results page (arcprize.org/results/openai-gpt-6-astra).
- Claude Code docs, MCP servers and quickstart (code.claude.com/docs/en/mcp-servers):
  `claude mcp add --transport http`, `streamable-http` alias, reconnect behavior.
- MCP TypeScript SDK v2, "Sessions, state, and scaling"; MCP C# SDK, "Stateless and stateful
  mode": `Mcp-Session-Id`, the sessionless 2026-07-28 revision.
- Ollama tool-calling docs and the 2025-05-28 streaming post; LM Studio "Tool Use"; vLLM
  "Tool Calling".
- OpenAI Terms of Use (Registration), OpenAI Services Agreement §3.3, Help Center "Using Codex
  with your ChatGPT plan" and "Paid weekly Work and Codex rate limit resets".
- openai/codex `codex-rs/core/src/client.rs` and `responses_metadata.rs`; commit 7c7b486
  (hyphenated `session-id`/`thread-id` headers).
- Local reads: `~/.claude/sessions/*.json`, `~/.claude-codex/state/claudex-perf.jsonl*`,
  `JsonlSink.kt`, `QuotaProbes.kt`, `install.sh`.
