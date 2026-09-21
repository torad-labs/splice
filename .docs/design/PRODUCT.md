# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Register

product

## Users

A developer who installed splice on their own machine to run Claude Code against backends other
than Anthropic (ChatGPT Codex, Grok, Kimi, Muse, OpenRouter, DeepSeek, Fireworks, local runtimes
such as Ollama or vLLM), or against Anthropic through the same gateway. One operator per daemon;
no second human ever uses the console. Situation: mid-session, with several Claude Code sessions
in flight on several heads, they open the console for one answer (which head needs me, why is this
turn slow, how much plan is left, what is this knob and where does its value come from, which
account is this session on) and go back to the terminal.

## Product Purpose

splice is a loopback-only proxy stack: one Kotlin daemon (spliced) with one head per backend, each
a Claude Code wrapper on its own port, translating Anthropic's Messages API into each backend's
wire dialect. The console is the daemon's single operator surface: fleet status and lifecycle,
turns and performance, sessions, accounts and plan windows, usage and cost, every configuration
knob with its provenance, models, logs, compaction, hosted MCP servers, and the doctor report.
Success is that the operator trusts the fleet at a glance and can act on any head, account or
knob without reading logs, editing TOML by hand, or restarting blind, and that every number on
screen is measured or says plainly that it is not.

## Positioning

Portkey, LiteLLM and their peers sit between many applications and many providers on behalf of a
team. splice sits between one human's coding agent and the subscriptions that human owns. That
inversion is the claim no gateway console can copy: the console can show the turn that is in
flight right now, tie cache hits and cost to the session on screen, explain what compaction did
and with which instructions, show which login a head is on and why it switched, and show for every
knob which layer decided its value. The OAuth heads reuse vendor CLI identities and are
unsupported by those vendors; the console carries that honesty without shouting it.

## Operating Context

- The terminal is the primary environment. Wrapper commands per head (claudex, claude-grok,
  claude-kimi, claude-muse, claudeor, claude-splice, ...), the `splice` CLI (doctor, status,
  sessions, perf, add, add-model, upgrade, login, key, logs, restart, dashboard, setup, init,
  install, uninstall, version), and the per-tick status line inside Claude Code showing model,
  context used, cache hit rate, plan windows and cost.
- The console runs at http://127.0.0.1:3096, bearer-guarded with a key pasted once from
  `~/.splice/state/mgmt-key`, served as one inlined HTML file from the daemon jar. It makes
  no external requests and polls the daemon's `/api/*` routes.
- Files of record: `~/.config/splice/splice.toml` (topology, boot-only except a model's context
  window), `keys.toml`, `auth/<kind>/<label>.json` account pools, the state `config.json`,
  per-head perf, usage, economics and compaction JSONL, `daemon.log`.
- Rituals: a topology edit is followed by `splice restart`, which drains in-flight turns; `/login`
  inside a head starts over or names an account; `splice doctor` prints the exact fix for every
  failing check; releases are checksummed and provenance-attested before the installer activates
  them.
- Feature inventory for the console, enumerated from the daemon's source and from competing
  consoles: `.dev/campaigns/web-console/FEATURES.md` (2026-09-17).

## Capabilities and Constraints

Confirmed capabilities the console must cover (details and file citations in the inventory):
head lifecycle with a gate snapshot and live turns; layered runtime config (33 knobs, three of
them hot, the rest applied on restart) with default, TOML, per-head override, state file, env and
runtime layers; the topology surface (daemon, claude sharing, compaction scopes, providers with
dialect, auth kind, quirks, models, windows and rates, heads with slots, overrides, system prompt
and rates); per-turn telemetry with 37 fields per turn; usage and plan windows per provider;
hourly token economics; account pools with automatic switching; the Claude Code sessions
registry; shared MCP hosting; compaction outcomes and instructions; code mode; the doctor report
and upgrade status.

Constraints:
- Loopback only, bearer-guarded, one HTML file, no external resources.
- No request or response bodies are recorded by default; only sizes, counts and timings. Body capture exists as an explicit opt-in per head, off until the operator turns it on, stored on local disk only, and the console says when it is on. Nothing is recorded that the operator did not ask for. Message edges between the operator's own sessions (which session messaged which address, when) are metadata like timings and counts, kept by default (operator ruling 2026-09-17); the message text stays in the sessions' transcript files on disk and is read only when the operator opens it. Activity labels (a file name, the first words of a shell command, the first characters of a search pattern; never file contents) are kept the same way, on by default, because the daemon log already carries them; the store is plain per-day files the operator can read and delete, bounded by a retention knob, and switchable off per head (seat ruling under operator delegation, 2026-09-17).
- Compaction runs on the session's own model and effort by law; no compaction model knob exists.
- Failures are honest (a failed stream never reads as a clean stop) and nothing is summarized
  that the model did not write.
- Topology is boot-only; the daemon reports when the file on disk no longer matches what booted.
- Secrets are masked and replace-only; account ids and emails are never displayed unmasked.
- Missing provider data reads "not reported by provider", never zero and never unlimited.
- The console architecture is lint-enforced: Feature-Sliced layers, HTTP only inside entity api
  segments, spacing and font sizes only from the token scales, zero em-dashes in UI text.

Terminology: head, provider, dialect, quirk, slot (opus, sonnet, haiku, fable), pinned model,
turn, gate (inflight, queued), compaction, account pool, primary account, plan window (five-hour,
seven-day), topology, knob, hot versus restart, session, status line, doctor, code mode, hosted
MCP server.

Undecided product facts, to be settled in shape: whether account add, remove and relabel ship in
the first console cut or the console shows the CLI command until a login route lands; the default
window and columns of the turns view; whether usage and turns share one explore view; whether the
topology editor or the forms are the primary settings surface.

## Brand Commitments

- Name: splice, always lowercase, wordmark "splice". Not affiliated with, endorsed by, or
  sponsored by Anthropic, OpenAI, xAI, Moonshot, Meta or OpenRouter; product names are theirs.
- Voice: terse, lowercase, data-first. It never shouts; the loudest thing on screen is a real
  warning. Functional labels only. Zero em-dashes in UI text (locked copy gate). Absent data reads
  "n/a" or states its reason, never a dash placeholder.
- Released 2026-09-17 by the operator: the Torad plate system (paper and ink, cinnabar accent,
  serif numerals) is no longer binding for the console. The visual world is chosen in new-work.

## Evidence on Hand

- A running daemon is always the data source. On the operator's machine it serves seven heads
  with real perf rows, economics, quota windows and account pools at :3096; the console is
  designed and verified against live data, never against mock data.
- `docs/assets/doctor.svg`: the doctor output the README leads with.
- `README.md`, `CHANGELOG.md`, `config/splice.example.toml`: the source of copy, terminology and
  the reasons behind each knob.
- `.dev/campaigns/web-console/FEATURES.md` §3: competitor console research, 2026-09-17.
- Absent, and not to be fabricated: testimonials, customer logos, adoption numbers, benchmarks
  beyond the repo's own measurements.

## Product Principles

1. Every number on screen comes from the daemon and says whether it is measured, estimated,
   unavailable or stale.
2. Read-only by default. A mutation confirms first and names the live sessions it will interrupt.
3. Every knob shows where its value came from and whether a change is live or needs a restart.
4. Every surface designs its whole state cycle: loading, empty, nominal, warn, error, stale.
5. N heads read as N identical instruments. Consistency is the feature, and a head that is down
   stays visible.

## Accessibility & Inclusion

WCAG AA for text, controls, focus and data inks in both light and dark, verified by the contrast
test in `console/tests`. Focus is always visible. Motion is functional only and respects
prefers-reduced-motion. Color is never the sole signal: every status carries a text label. The
console is fully operable from the keyboard.
