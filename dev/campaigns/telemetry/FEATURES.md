# splice telemetry — draft feature discussion

- Status: **discussion draft — not an approved campaign**
- Created: 2026-09-15
- Evidence baseline: `feat/v0.4.0` at `7fa98534`; every local pointer below was read at that commit
- External sources: read 2026-09-15, listed at the end with the confidence each one carries
- Requirement source: user discussion, 2026-09-15 (Marcos). Two decisions are his and are recorded
  as decisions, not as defaults that drifted in: **the product channel is opt-out**, and **the
  self-hosted channel is modelled on what Codex offers**. The first overrides the opt-in
  recommendation made in that discussion and is settled.

This folder holds the telemetry feature discussion. We will iterate on this document before
creating a campaign ledger, implementation tasks, or dispatching builders. Nothing below is
authorized for implementation. Scope and acceptance criteria remain open.

The audience for this document is wider than usual: Marcos is building splice's website, Terms of
Use and Privacy Policy with torad-main. **This document is the artifact those are derived from.**
Everything here must therefore describe what the binary would DO, never what we intend it to do —
which is the entire lesson of the research in the last section.

## Proposed direction

**Two channels that never touch each other, and a wall that stops the published claim from
drifting away from the code.**

- **Channel A — observability export.** The operator points splice at *their own* OTLP collector.
  splice never receives any of it. Off unless an endpoint is configured. This is the Codex-shaped
  feature for self-hosted users.
- **Channel B — product counts.** A small daily payload of bucketed counters to a splice-owned
  sink, so we can answer how many installs exist, which heads and models are actually used, and
  whether volume is growing. **Opt-out** (Marcos, 2026-09-15).

The design effort does not go into minimising the field list. It goes into two places the research
says actually fail: making the off switch really work, and making the published description
mechanically impossible to drift from the code.

## What splice does today — the baseline

**splice has no telemetry of any kind, and makes no privacy claim of any kind.** Both halves of
that sentence matter.

- No product analytics, no crash reporting, no usage beacon, no vendor analytics SDK in any
  module. Nothing in the tree contacts an endpoint that is not a model backend the operator
  configured. The complete set of hard-coded hosts in `gateway/*/src/main` is:
  `api.anthropic.com`, `api.kimi.com`, `api.meta.ai`, `api.openai.com`, `api.x.ai`, `auth.kimi.com`,
  `auth.meta.com`, `auth.openai.com`, `auth.x.ai`, `chatgpt.com`, `cli-chat-proxy.grok.com`,
  `openrouter.ai`, plus `docs.anthropic.com`, `github.com` and `nodejs.org` in documentation and
  install paths. Every one is a backend, an auth endpoint for a backend, or a link.
- There is no `PRIVACY.md`, and `README.md` says nothing about telemetry, analytics or data
  collection. We currently have no claim to contradict — which is a clean start, and also means
  **the first thing this campaign ships is a claim, and from that moment the claim can be wrong.**

What already exists that this work builds on, rather than reinvents:

- **A per-turn observability plane.** `PerfStats.record` (`gateway/gateway/src/main/kotlin/splice/gateway/perf/PerfStats.kt:57`)
  writes one JSONL row per finished turn: `ts`, `model`, `outcome`, `compact`, the session tag, the
  account label, `cache_cold`, plus every mark and counter. The lane is asynchronous and
  best-effort by design, because I/O failure must never kill a turn. Channel A is a **second sink
  on those same rows**, not a new instrument.
- **The counter vocabulary, including tokens.** `PerfKeys` (`gateway/core/src/main/kotlin/splice/core/perf/PerfKeys.kt:6`)
  already defines 36 fields, among them `in_tokens`, `out_tokens` and `cached_tokens`
  (`:44`–`:46`), `attempts`, `retries`, `refreshes`, `backoff_ms`, `inflight`, `async_io_drops`,
  and frames and bytes in each direction. Marcos asked whether token counts could be included: they
  are already measured, per turn, by code that shipped.
- **An allowlist-of-structure redactor.** `DoctorRedaction` (`gateway/app/src/main/kotlin/splice/app/cli/DoctorRedaction.kt:27`)
  emits a daemon log line as STRUCTURE only: it drops any line without one of the daemon's own
  event heads, admits `key=value` pairs only where the KEY is splice's own vocabulary and the value
  is a number, a boolean or a safe token, and lets paths survive only under splice's own or the
  system's directories (`:22`). This is exactly the doctrine channel B needs, already written and
  already tested.
- **A real interactive prompt.** `AdminSupport.confirm` (`gateway/app/src/main/kotlin/splice/app/cli/AdminSupport.kt:172`)
  prints a `[Y/n]` question, reads a line, treats empty as the default — and returns the default
  without prompting when `System.console()` is null. See section 6.

Four assets, one for each phase of the work below.

**Correction owed to this record.** Earlier in the 2026-09-15 discussion I told Marcos splice was
"sending data to a third party". He challenged it, and reading the code showed the claim was too
strong. `ResponsesClientHints.clientMetadataBlock` emits, on lite turns only, `client=splice` plus
`session_id` and `thread_id`. That is protocol metadata for the prompt cache: no prompt, no
completion, no token count, no credential. "Data" was the wrong word. It is recorded here because
this campaign exists to close the gap between what we say and what the binary does, and the first
instance of that gap in this discussion was mine.

## The governing question

Marcos asked it directly: **is the problem sending analytics by default, or not disclosing it
properly?**

The answer from the research is **not disclosing it properly**, and the evidence is one-sided. In
every case examined, what produced the complaint was a divergence between the stated behaviour and
the actual behaviour. Not one case was "you disclosed X, and X was too much."

The sharpest proof is the counter-example. The one project in the set whose payload was genuinely
clean — counters, token totals, latency percentiles, a hashed model name, an anonymous install
UUID, verified by packet capture to contain no message content, with an off switch verified to
produce zero requests — was still written up. Its failure was that its `docs/telemetry.md` opened
by promising to explain how to opt out and then contained no opt-out section, and named a
`reset-id` command that does not exist in the binary. Perfect payload, working switch, wrong
documentation, bad outcome.

Default-on is not the sin. It is a **risk multiplier on an inaccurate claim**, because it widens
the population the inaccuracy applies to. That is why opt-out is defensible here and why the
walls in section 5 are the load-bearing part of this proposal rather than a nice-to-have.

## 1. Channel A — observability export for self-hosted operators

**User outcome:** an operator running splice for themselves or their team points it at their own
OpenTelemetry collector and sees per-turn latency, retries, token usage and failure outcomes in
their own Grafana, with nothing leaving their infrastructure.

**What Codex actually offers** (read 2026-09-15 from the openai/codex event catalogue in PR #2103,
the published config reference, and `codex-rs/otel/src/events/session_telemetry.rs`):

- An `[otel]` table with `environment` (default `dev`), an `exporter` that defaults to `none`, and
  separate `trace_exporter` and `metrics_exporter`. `otlp-http` and `otlp-grpc` take an
  `endpoint`, a `protocol`, and static `headers` (which support `${ENV_VAR}` expansion).
- `service.name`, the CLI version and an `env` attribute on every exported event.
- Events: `codex.conversation_starts`, `codex.api_request`, `codex.sse_event`,
  `codex.user_prompt`, `codex.tool_decision`, `codex.tool_result`.
- Export disabled by default, so local runs stay self-contained.

**The part worth copying is the shape: the operator's own collector, off unless configured.** The
part worth *not* copying is how content is kept out, and this is a correction to what I told Marcos
earlier in the discussion. I had it as "Codex traces carried prompts". The verified situation is
more instructive than that:

- `otel.log_user_prompt` defaults to false, and when false the `prompt` field of
  `codex.user_prompt` is replaced with `[REDACTED]`. That flag does its job.
- But it gates **one field on one event**. `codex.tool_result` carries `arguments` and `output` —
  the inputs and the results of tool calls — and no flag gates them. In a coding agent, tool
  arguments and tool output are file paths, file contents, and shell output.
- The shared session metadata carries `user.account_id`, and the source struct also carries
  `account_email`.

So the lesson is not "Codex had a bug". It is structural: **a flag-based guard protects exactly
what someone remembered to gate, and the set of things worth gating grows every time an event is
added.** A reader of `log_user_prompt = false` would reasonably conclude their content is not being
exported. That conclusion would be wrong, and nothing in the configuration says so.

**splice's answer is to make the guarantee a property of the type, not of a flag.** The exporter's
entire input is `PerfRowMeta` plus `PerfSnapshot` — the two values `PerfStats.record` already
receives. Those types contain no message, no prompt, no tool argument, no tool output, and no file
path. There is no field to gate, because there is no content in the type. Consequently **there is
no `log_user_prompt` knob in this proposal, and adding one would be a design regression, not a
feature.**

Proposed configuration, in the topology TOML the operator already owns:

```toml
[telemetry.otlp]
endpoint = "http://127.0.0.1:4318/v1/logs"
protocol = "http/protobuf"                      # or http/json
headers  = { "x-otlp-api-key" = "${OTLP_TOKEN}" }
```

**The absence of an endpoint is the off state.** There is deliberately no `enabled` boolean: a
separate flag creates two reachable nonsense states (`enabled = true` with no endpoint, an endpoint
with `enabled = false`) and one more place for an off switch to be misread.

Proposed scope:

- Export the existing per-turn row as OTLP log records to the configured endpoint, on the existing
  asynchronous best-effort lane. A collector that is down must be indistinguishable, from the user's
  seat, from one that is up.
- Resource attributes `service.name = "splice"` and the splice version. No account, no email, no
  hostname.
- `${ENV_VAR}` expansion in `headers`, because a collector token does not belong in a config file
  that `splice doctor --json` may render. **The header values must never be echoed by any surface
  that prints configuration** — doctor, the console, `splice status`. This is an acceptance
  criterion, not a note.
- Channel A carries slightly *more* than channel B — the session tag and the account label are in
  the perf row and are useful to the operator debugging their own fleet. That is correct precisely
  because it never crosses the operator's boundary. The documentation must say so plainly rather
  than let a reader assume the two payloads are the same.

**Proposed acceptance:** with an endpoint configured, a real collector receives records whose field
set equals `PerfKeys` plus the `PerfRowMeta` strings and nothing else. With no endpoint, a fake
transport records zero requests. A wall proves no content-bearing type is reachable from the
exporter package (section 5). The documented field list is generated from `PerfKeys` rather than
hand-typed, so a new perf key cannot silently go undocumented.

**To resolve:** whether metrics and traces follow the log records, or whether logs alone are the
0.4.0 scope. Codex ships all three through separate exporters. Logs alone cover every question the
existing perf row can answer, and are the smaller claim.

Evidence: `gateway/gateway/src/main/kotlin/splice/gateway/perf/PerfStats.kt:57`,
`gateway/core/src/main/kotlin/splice/core/perf/PerfKeys.kt:6`, `gateway/core/src/main/kotlin/splice/core/util/JsonlSink.kt:51`
(the bounded lane this rides). No OpenTelemetry dependency exists in the Kotlin tree today; the only
match in the repository is a transitive `@opentelemetry/api` entry in `package-lock.json`.

## 2. Channel B — product counts, opt-out

**User outcome (ours):** we can answer how many installs exist, which heads and models people
actually use, and whether usage is growing, without ever holding anything that describes a person
or their work.

**User outcome (theirs):** one command shows the exact payload their machine would send, and one
command stops it.

**Release status:** opt-out, decided by Marcos on 2026-09-15. I recommended opt-in in that
discussion; he chose opt-out and that is settled. The research supports it being defensible, on
the strict condition that section 5 ships with it.

Proposed payload — the complete list, as it would be published:

```json
{
  "schema": 1,
  "install_id": "9f1c4e2a-…",
  "day": "2026-09-15",
  "splice_version": "0.4.0",
  "client_version": "2.1.257",
  "os": "linux",
  "arch": "x86_64",
  "heads": 3,
  "auth_kinds": ["chatgpt-oauth", "muse-oauth"],
  "api_providers": ["openrouter"],
  "dialects": ["openai-responses", "anthropic-passthrough"],
  "models": ["openai/gpt-6", "other"],
  "turns": "200-999",
  "compactions": "10-49",
  "tokens_in": "1e7-1e8",
  "tokens_out": "1e6-1e7",
  "tokens_cached": "1e7-1e8",
  "outcomes": { "ok": "200-999", "error:upstream": "1-9", "client_abort": "1-9" },
  "code_mode": true
}
```

Every value in that object comes from one of three places, and this is the rule that makes the
payload reviewable:

1. **A splice-owned registry.** `auth_kinds` from `AuthKindRegistry.knownKinds()`
   (`gateway/core/src/main/kotlin/splice/core/topology/AuthKind.kt:137`, over the `KNOWN` list at
   `:127`), `api_providers` from `ApiKeyProviderRegistry.rows()` (`:119`, over `ROWS` at `:106`),
   outcome tags from the daemon's own outcome vocabulary. Both accessors exist precisely so that a
   consumer takes its denominator from the registry rather than keeping a second list that can
   silently omit a new entry — the comments on them say so.
   **An operator's custom auth kind is a free-form string in their TOML — `AuthKind.from()` returns
   null for it by design — and it renders as `other`, never verbatim.**
2. **A bucket.** Counts as `0`, `1-9`, `10-49`, `50-199`, `200-999`, `1000+`. Token totals by order
   of magnitude. Never an exact number.
3. **A closed enum.** `os`, `arch`, `code_mode`, `schema`.

**Why buckets rather than exact counts.** An exact daily turn count, an exact token total and an
exact model mix are, taken together, close to a fingerprint: a heavy user with an unusual model set
is identifiable in a small population even behind a rotating id. Buckets answer every product
question we actually have — how many installs, which heads, is volume growing, which models matter
— while removing the join key. This is cheap to do now and expensive to retrofit.

**Model ids get the strictest treatment, and it is the sharpest leak in the payload.** A model id
is either a public name (`openai/gpt-6`, `anthropic/claude-sonnet-4`) or an operator's private
string: an internal deployment name, a fine-tune slug, a hostname-shaped local model id from the
Ollama or LM Studio path. The first is product signal; the second is operator information that must
never leave. **Rule: a model id is sent only when it appears in the defaults splice itself ships,
and otherwise renders as `other`.** The denominator therefore comes from splice's own shipped
config rather than a hand-typed allowlist of vendor names — which is exactly the completeness
failure that hid two real defects in the v0.4.0 campaign, where an audit's denominator was a list of
vendor *names* and the defects were in model *ids*.

**Never sent, and this list is published verbatim:** prompts, completions, reasoning, tool names,
tool arguments, tool results, system prompts, file paths, working directories, repository or
project names, hostnames, usernames, home directory, e-mail addresses, account labels or ids,
session ids, thread ids, API keys, tokens, credentials, configured endpoint URLs, custom auth kind
strings, non-shipped model ids, IP address (dropped at the edge, section 7), geolocation, timezone,
locale, and exact counts of anything.

Proposed scope:

- One send per 24 hours at most, on daemon start and on a daily tick, built from the perf rows
  already on disk. Never on a turn path: telemetry must not add a millisecond to a request.
- Fire-and-forget on the existing asynchronous lane, 5 second timeout, no retry. A failure is one
  debug log line.
- When consent resolves to disabled, **no payload is built and no install id is created.** An
  opted-out machine leaves no telemetry state on disk at all.
- **No analytics SDK, at any tier, in any module — but see section 7 on the sink.** The payload is
  built by our own code and sent as a plain HTTP POST. This is narrower than "no PostHog": the
  objection is to an SDK's *behaviour* (autocapture, its own identity model, its own extra
  endpoints, config fetches), not to a vendor. Sending our own JSON to a documented capture
  endpoint imports none of that.
- **The payload is one event with one property bag, which is exactly PostHog's event model.**
  `event` is the daily rollup name, `distinct_id` is the install id, and every field above is a
  property. That mapping is deliberate, so the choice of sink in section 7 stays open without the
  payload changing shape.

**Proposed acceptance:** a test feeds an operator-authored custom auth kind and a private model id
and asserts both render as `other`. Disabled by each of the four off switches in turn, a fake
transport records exactly zero requests and no install-id file appears; enabled, exactly one. No
turn latency mark moves with telemetry enabled. The 24-hour floor survives a daemon restart.

**To resolve:** the retention period for the received data. The Privacy Policy has to state a
number, so this is Marcos's decision rather than an engineering default, and it should be decided
before the sink is written rather than after.

## 3. The off switch

**The single largest failure mode in the research is not the payload. It is an off switch that does
not reach every sender.** Cline shipped one: a `logRequired()` method on its PostHog provider calls
`client.capture` directly with no `isEnabled()` check at all, and separately its webview initialised
PostHog unconditionally so that disabling telemetry in settings still produced `$pageview` uploads
and config fetches. Kilo shipped one: its VS Code extension never subscribed to
`onDidChangeTelemetryEnabled`, so an already-open webview kept sending feedback events to PostHog
until it was reloaded (issue #9872).

Both are the same defect: **more than one sender, and a switch read in only some of them.**

Proposed design: **one resolution function, one sink, no exceptions.** A single pure function in
`:core` returns a decision, and exactly one caller in the daemon reads it. Precedence, in order:

| # | Input | Effect |
|---|---|---|
| 1 | `DO_NOT_TRACK=1` | **Disabled. Unconditional, no override, by config or wizard or anything else.** |
| 2 | `SPLICE_TELEMETRY=0` / `=1` | Disabled / enabled |
| 3 | The consent file written by `splice telemetry off` / `on` | Disabled / enabled |
| 4 | `[telemetry] product = false` in the topology | Disabled |
| 5 | Default | **Enabled** (this is the opt-out decision) |

Rule 1 is absolute on purpose. Every additional precedence rule is one more place an off switch
silently fails to propagate, and the honest price of that simplicity is that a user who sets
`DO_NOT_TRACK` globally and then wants splice specifically to report cannot have it. That is the
right trade.

**`DO_NOT_TRACK` does not affect channel A.** Channel A is the operator's own collector, configured
deliberately by the operator, and it is not third-party tracking. Suppressing an operator's own
observability because of a variable about third-party tracking would be a surprise, not a courtesy.

The decision value carries **which input decided it**, so `splice telemetry status` can print a
reason instead of a bare word. A misconfigured off switch should be visible to the person who set
it, which is the thing none of the studied projects offered.

**Proposed acceptance:** every precedence pair is covered by a test, including `DO_NOT_TRACK`
beating an explicit `on`. A test asserts there is exactly one call site that reads the decision.

## 4. Identity

**Proposed:** a random v4 UUID, generated once, written to `~/.local/share/splice/install-id` at
mode 0600, and created only when telemetry is enabled.

**Never derived from the machine.** A hash of hostname, MAC address or `machine-id` survives
reinstallation and survives a deliberate delete, which makes it an identity rather than a counter
and leaves the person it identifies with no way to revoke it. A random file gives the same
install-counting power with a property the derived form does not have: **deleting it makes the next
id unlinkable to the last.**

**Never aliased to an account.** Cline's `PostHogTelemetryProvider.identifyUser` calls
`client.identify` with the account id as the new `distinctId` and the previous anonymous id as
`alias`, permanently joining the anonymous history to the identified user. Kilo's CLI does the
equivalent, identifying to e-mail or organisation id on auth. **splice never calls anything of the
kind, because signing in to a head is not a telemetry event and the account is not in the payload.**

**Proposed acceptance:** the test asserts a negative — that no machine-derived input is *read* — by
injecting a fake environment and filesystem that fail the test if hostname or username is queried.
Asserting that the output merely looks random would not catch a hash.

## 5. Disclosure, and the wall that keeps it true

This is the load-bearing section. Given the governing question's answer, **the published
description is the part most likely to fail**, and it is the only part with no test today.

Proposed surfaces:

- **`PRIVACY.md` in the repository**: both channels, field by field, the never-sent list verbatim,
  the four ways to turn channel B off, and where the data goes. Written as the source of truth the
  website's Privacy Policy is derived FROM, so the site and the binary cannot say different things.
- **A `README.md` section** pointing at it, near the install instructions rather than at the bottom.
- **`splice telemetry show`**: prints the real payload for *this* install, built by the *real*
  builder from the *real* perf rows. Not a sample, not a copy of the documentation. This is the one
  command that lets the person running splice audit the claim themselves, on their own machine,
  without a packet capture.
- **The explainer page on the splice website** (Marcos + torad-main), which the first-run notice
  and the wizard link to.

**And the wall.** A gate leg runs the real schema emitter and byte-compares its output against the
fenced payload block in `PRIVACY.md`. **Adding a field to the payload without publishing it fails
the build, by name.**

The failure mode this defends against is not malice. It is a field added in a hurry eighteen months
from now by someone who did not think to open `PRIVACY.md`. Every project in the research had
honest people and a documentation file; none had a mechanical link between the two. Per the
walls-first rule the leg is authored and red-green proven against a synthetic undisclosed field
*before* the sender it guards exists — a wall that has never failed is not known to be able to.

A second wall takes its denominator from the resolved dependency set (the Gradle version catalog
and verification metadata, plus `package.json`) rather than a hand-typed module list, and fails by
name on any `posthog`, `segment`, `amplitude`, `mixpanel`, `heap`, `rudderstack`, `sentry` or
`datadog` coordinate appearing anywhere in the build. It must refuse to pass vacuously: a scan that
found zero files to read is a failure, not a pass.

**Proposed acceptance:** each wall's selftest proves it goes RED on a synthetic violation and GREEN
without it, and the live wall is run against the real tree — not just its selftest. That distinction
is not pedantry: during the v0.4.0 campaign a wall's selftest was green while the live wall was red,
twice.

## 6. The setup wizard, and the consent notification

**Did we implement the wizard? Yes — and it is a genuine interactive one, not a scaffold.**

`SetupCommand.setup()` (`gateway/app/src/main/kotlin/splice/app/cli/SetupCommand.kt:20`) runs a
guided flow: it materialises the topology, installs the wrapper commands and the `splice` command
itself, then walks every OAuth head that is not yet authenticated and, for each one, asks
`AdminSupport.confirm("Sign in to <command> now?", default = true)` (`:70`) before running the
login. It finishes by printing the launch, dashboard, status and doctor commands. It is registered
at `InstallCommand.kt:82`, dispatched at `Command.kt:39`, and advertised in the usage string at
`Cli.kt:16`.

The prompt behind it is real:

```kotlin
fun confirm(prompt: String, default: Boolean = true): Boolean {
    if (System.console() == null) return default
    …
}
```

`AdminSupport.kt:172`. There is a second prompt seam for `splice add` — `ConsolePrompter` in
`AddSeams.kt:19` — with the same null-console fallback.

So the consent notification Marcos described has a real home, with a real prompt, and a headless
behaviour that is already proven: **in Docker, CI, or a `systemctl` start there is no console, the
default is returned, and nothing hangs.**

There is **no `telemetry` verb today.** The CLI is `setup`, `add`, `upgrade`, `status`, `sessions`,
`perf`, `restart`, `dashboard`, `login`, `key`, `logs`, `install`, `uninstall`, `init`, `doctor`,
`daemon`, `version` (`Cli.kt:16`). Adding one is part of this work.

### The flow today

`splice setup` runs five steps, in this order:

1. **Header.** `splice setup` in bold, then a dim line naming the OpenRouter API-key starter.
2. **Install.** `init()`, then `install("--all")`, then `installSelf()` — topology, wrapper
   commands, and the `splice` command itself.
3. **Sign-in.** Every OAuth head with no credential yet is walked, each asking
   `Sign in to <command> now? [Y/n]` with default yes. Declining prints
   `skipped — sign in later with: <command> login`. With nothing pending it prints
   `✓ wrapper installed. Set OPENROUTER_API_KEY before launching.` A dim paragraph first warns
   that subscription heads reuse each vendor CLI's public OAuth client identity and are
   unofficial.
4. **You're set.**
5. **The four next steps**, as an aligned block: `Launch`, `Dashboard`, `Status`, `Checkup`, each
   command in cyan, `Checkup` carrying `— anything wrong prints its fix`.

The house idiom is consistent and easy to match: a bold title with a dim `—` subtitle, two-space
indentation, a coloured glyph (`✓` green, `✗` red, `!` yellow), a label padded to a fixed width,
then the detail. Commands are cyan. Copy is lowercase, terse, and every failure line names its
fix.

### The proposed telemetry step

**It goes between step 3 and step 4** — after the work, before "You're set." Two reasons: the
user's attention is already on "what did this just do to my machine", and **`setup` never starts the
daemon**, so the answer is always recorded before the first send can happen. Verified 2026-09-15:
none of `init()`, `install()` or `installSelf()` starts or restarts the daemon — the only match for
`restart` in `InstallCommand.kt` is the command-registry entry at `:86` mapping the string to
`Command.Restart`. The daemon comes up on first launch, which is after `setup` returns. **This is a
standing constraint, not a one-off check: if any step of `setup` ever starts the daemon, the notice
has to move ahead of it**, and the acceptance test below is what would catch that.

Proposed copy, in the existing idiom:

```
Anonymous usage counts — on
  One small payload a day: splice and OS version, which head kinds and which
  shipped model ids you use, and bucketed turn and token counts.
  Never your prompts, your code, file paths, your account, or your IP address.

  See exactly what this machine would send   splice telemetry show
  The full list, in writing                  https://splice.sh/privacy

Keep sending anonymous usage counts? [Y/n]
```

Answering yes:

```
  ✓ telemetry   on — turn it off any time with: splice telemetry off
```

Answering no:

```
  ✓ telemetry   off — nothing is sent, and no install id is stored
```

Note that **declining is also a green tick.** Opting out is not a degraded state and must not be
rendered as a warning; the `!` glyph in this CLI means "something needs your attention", and this
does not.

- The same notice prints once on first daemon run, for installs that never ran `setup`.
  `TopologyLoader`'s first-run claim is the existing "exactly once" seam.
- **The prompt only ever removes, never adds.** Under opt-out the channel is already on, so a
  defaulted headless answer leaves the state exactly where it already was. A prompt whose default
  answer silently *enables* something would turn every non-interactive install into a consent
  nobody gave — and that, not the field list, is what would make an opt-out design indefensible.
- **`splice telemetry show` is named in the prompt on purpose.** It is the difference between
  asking someone to trust a sentence and handing them the command that prints the actual bytes.

**Proposed acceptance:** the notice prints exactly once; declining writes the off state; a headless
run neither hangs nor enables anything the default did not already allow; and the notice precedes
any possible first send.

**To resolve:** the explainer URL, which depends on the website work with torad-main. The notice
cannot ship pointing at a 404, so this gates section 6 and part of section 5.

## 7. The sink — PostHog, and a correction to this document's first draft

The first draft of this section proposed a Cloudflare Worker on a splice-owned domain, on the
reasoning that a vendor SDK is what produced the failures in the research. **Having checked
PostHog's actual documentation rather than reasoning from the incidents, I now recommend PostHog,
and the earlier position was a generalisation from other projects' SDK usage to a vendor.** The
three things that changed it:

1. **PostHog has a documented raw capture endpoint, so no SDK is needed at all.**
   `POST https://us.i.posthog.com/i/v0/e/` (or `/batch/`) takes
   `{api_key, event, distinct_id, properties, timestamp}` as plain JSON. splice can send its own
   hand-built payload with the HTTP client already in the tree.
2. **Autocapture is a `posthog-js` (browser) feature and does not exist server-side.** Cline's
   incident was in its **webview**, which is a browser. splice's daemon is a JVM and ships no
   browser analytics anywhere, so that entire defect class is structurally unreachable for us —
   not configured off, absent.
3. **`disableGeoip` defaults to `true` in `posthog-node` v3.0+.** This corrects a claim I made
   earlier today: I described Kilo's `disableGeoip: false` as "the default". It is not — PostHog
   changed the default to disregard the server IP in v3.0, and **Kilo explicitly opted back in**.
   That is a worse fact about Kilo and a better one about PostHog, and I had it backwards.

**Recommendation: PostHog as the sink, addressed over raw HTTP with no SDK.** It deletes the
Worker, the storage and the query layer from this campaign, and gives us their analysis UI for
the questions Marcos actually asked (how many installs, which heads, which models, is volume
growing). Every failure mode in the research stays out by construction rather than by
configuration: no SDK means no autocapture and no config-fetch chatter, and we never call
`identify` or `alias` because we only ever POST a capture.

**The honest cost, stated plainly because the Privacy Policy has to state it too.** Any HTTP
endpoint receives the connecting IP address; that is true of a Cloudflare Worker as much as of
PostHog. The difference is provability. With our own Worker, "we do not log your IP" is a claim we
can prove with a test over our own source. With PostHog it is a claim we **inherit from a vendor**
and can only pass along. Given that this campaign's whole thesis is that a claim must be backed by
the binary, that distinction is real and should not be waved away — it is the one place the
recommendation trades a provable claim for a large reduction in work.

The mitigation is precision rather than silence: `PRIVACY.md` and the Privacy Policy **name PostHog
as the processor** and say what it receives, which is what a privacy policy has to do anyway.

Proposed scope:

- One POST per day to the capture endpoint, project token compiled in, payload as section 2.
- `$geoip_disable: true` set on the event so no location is derived from the connecting address.
  **Verify the exact property name against PostHog's raw-API docs before relying on it** — the
  option is documented for the SDKs; the raw-API spelling was not confirmed in the 2026-09-15 read,
  and an unverified privacy control is worse than none.
- Never `identify`, never `alias`, never `$set` on a person. The install id is a `distinct_id` and
  nothing else is ever joined to it.
- EU vs US region is a decision that belongs with the Privacy Policy, not with the code.

**Proposed acceptance:** a wall greps the tree and fails on any `identify`, `alias` or person-
properties call to the capture host; a test asserts the POST body equals the builder's output
byte-for-byte, so the sink cannot enrich what the builder produced.

**Alternative, if the inherited claim is not acceptable:** the original Worker design, which keeps
"we never log your IP" provable, at the cost of building the sink, the storage, the retention job
and every dashboard ourselves. That is a real product tradeoff and it is Marcos's call, not an
engineering default — it is listed in "Next discussion".

**To resolve either way:** the explainer URL still depends on the website work, because the wizard
and the first-run notice link to it.

## Boundaries and deferred directions

- **No content, ever, on either channel, and not behind a flag.** A `log_user_prompt`-shaped knob
  is explicitly out of scope; adding one later would be a design regression.
- **No crash or error reporting in this campaign.** A stack trace from a coding gateway is the
  payload most likely to carry a file path or a fragment of the thing the user was editing. If we
  ever want it, it is its own document with its own redaction design, not a field appended here.
- **No session recording, no autocapture, no funnels, no analytics SDK**, at any tier. The sink may
  be a vendor (section 7); the *client* is always our own code sending our own payload.
- **Never `identify`, `alias`, or person properties**, whichever sink is chosen. The install id is a
  `distinct_id` and nothing is ever joined to it. This is the single line that separates an install
  counter from a user profile, and it is one line in both directions.
- **No second sink.** If a future need cannot be met by the one payload and the one switch, that is
  a reason to reopen this document, not to add a sender.
- **Channel A is not a route to us.** There is no splice-hosted collector option, because the moment
  one exists the two channels stop being separable in a user's mind and the claim gets harder to
  state honestly than it is worth.
- This draft authorizes no implementation, no campaign ledger and no builder dispatch.

## Next discussion

Open for Marcos, in rough order of what blocks what:

1. **PostHog as the sink, or our own Worker** (section 7). My recommendation is PostHog over its
   raw HTTP endpoint with no SDK: it deletes the Worker, the storage and the dashboards from this
   campaign. The cost is that "we never log your IP" becomes a claim inherited from a vendor
   rather than one provable from our own source. That is a product tradeoff, not an engineering
   default, so it is yours — and it is the decision that most changes the size of the campaign.
2. **The explainer URL** (with torad-main). Section 6 and part of section 5 cannot finish without
   it; everything else can proceed.
3. **The retention period** for received product data. The Privacy Policy has to state a number.
4. **Whether this folds into 0.4.0** or takes its own release. Marcos said on 2026-09-15 that
   telemetry should fold into 0.4.0; that predates this document's scope, which is larger than the
   discussion assumed — fifteen rows including three walls and a Worker.
5. **Logs only, or logs plus metrics and traces**, for channel A (section 1).
6. Whether `PRIVACY.md` should also carry channel A, given that channel A never sends us anything.
   My recommendation is yes: a user asking "what does splice send" wants one page, and "this one
   goes to your own collector and never to us" is a good sentence to be able to point at.

When these close, this document converts to a campaign ledger through the existing CLI
(`dev/campaigns/manifest.py <new>.toml init`), with the rows sketched here as its items and this
document's laws as the ledger header. **Recheck every evidence pointer above at campaign creation**;
this draft holds for `7fa98534`.

## Sources consulted, 2026-09-15

Local reads, all at `feat/v0.4.0` `7fa98534`: `PerfStats.kt`, `PerfKeys.kt`, `JsonlSink.kt`,
`DoctorRedaction.kt`, `AdminSupport.kt`, `SetupCommand.kt`, `AddSeams.kt`, `Command.kt`, `Cli.kt`,
`AuthKind.kt`, `ResponsesClientHints.kt`, `README.md`, `package-lock.json`, and a sweep of every
hard-coded `https://` host in `gateway/*/src/main`.

External, with the confidence each carries:

- **openai/codex** — the OTEL event catalogue in PR/issue #2103, the published Codex configuration
  reference (`developers.openai.com/codex/config-reference`), and
  `codex-rs/otel/src/events/session_telemetry.rs`. *Read directly; high confidence.* Source of the
  `codex.tool_result` `arguments`/`output` finding and of `account_email` in the session metadata.
- **cline/cline issue #3361** (2025-05-07) — sensitive user content transmitted despite telemetry
  disabled: PostHog autocapture left on in the SDK constructor put clicked UI text, task text, file
  names and MCP server names into `$el_text` and `$elements_chain`. Fixed in #3364; a follow-up
  round of `$pageview` and config fetches with telemetry disabled fixed in #3381; see also #7068.
  Plus `src/services/telemetry/providers/posthog/PostHogTelemetryProvider.ts` for the ungated
  `logRequired()` and the `identifyUser` alias. *Issue thread and source read directly; high
  confidence.*
- **Kilo-Org/kilocode issue #9872** — telemetry opt-out does not propagate to open webviews, with
  the missing `onDidChangeTelemetryEnabled` subscription named in the issue. Plus
  `packages/kilo-telemetry/src/client.ts` for the hard-coded PostHog key and `disableGeoip: false`
  — which is **not** the SDK default: `posthog-node` has disregarded the server IP since v3.0, so
  that line is a deliberate opt-in to geolocation.
  *Read directly; high confidence.*
- **opencode** — GitHub issue #14281 reports that with the opencode provider (Zen proxy) full
  request and response bodies were written to an R2 bucket, undocumented; a maintainer replies that
  this applies only to free Zen models and is documented. Related: #10416, #5554. Separately, a
  third-party inspection (stridenote.net, 2026-07-08) reports a hard-coded Sentry DSN initialised
  unconditionally with no environment check and none of the 41 `OPENCODE_DISABLE_*` flags touching
  it. **Lower confidence, and deliberately so:** the maintainer contests the first, and the author
  of the second states plainly that they read the shipped bundle but did not capture packets. Do
  not repeat either as established fact in public copy. *This is exactly the evidence bar this
  document exists to enforce; it applies to our claims about others too.*
- **PostHog docs**, read 2026-09-15: the capture and batch API endpoints
  (`posthog.com/docs/api/capture`, `/i/v0/e/` and `/batch/`, project token, plain JSON body), the
  Node library reference (`disableGeoip` **defaults to true** since v3.0; autocapture is a
  `posthog-js` browser feature and has no server-side equivalent), and the capture-events guide.
  *Read directly; high confidence.* The `$geoip_disable` spelling for the RAW api was not
  confirmed and is flagged in section 7 as needing verification before it is relied on.
- The same inspection describes the clean-payload counter-example in "The governing question" —
  counters only, verified by packet capture, off switch verified, documentation wrong. *Single
  source; treated as illustrative, and it is the argument's weakest link by provenance even though
  it is its clearest example.*
