---
name: splice
description: Use for any question about running, configuring, extending or debugging splice, the local proxy that puts Claude Code on many model backends. That covers its heads and providers, splice.toml, adding a head or an unsupported backend, accounts and logins, models and routing, sessions and Teams, the console, retries and errors, MCP hosting, install, upgrade and logs. Load it before you answer about splice or change its configuration.
---

Last read: 2026-10-15 against splice 0.4.0 (feat/v0.4.0)

# splice

## What splice is

splice is a daemon on the person's machine that sits between Claude Code and many model backends. Each
backend is reached through a head: a local port that speaks the Anthropic Messages wire to Claude Code and
translates each turn for one provider, such as a ChatGPT, Grok, Kimi or Muse subscription, an API-key
vendor like OpenRouter, a local model server, or Anthropic itself. The person starts Claude Code through a
head's command (for example `claudex` or `claude-grok`), and that session talks to the head's backend.
Logins and API keys, the sessions on the machine, usage and cost, and a browser console all live in the
same daemon.

Source: README.md, "How it works" and "Backends and protocols".

## The mental model

| word | what it is | where it lives |
|---|---|---|
| provider | One backend: `dialect`, `base_url`, `auth`, `quirks`, model rows | `[providers.<key>]` in splice.toml |
| dialect | The wire splice speaks upstream: `openai-responses`, `openai-chat` or `anthropic-passthrough` | the provider's `dialect` |
| auth | How splice proves itself upstream: `api-key`, `chatgpt-oauth`, `grok-oauth`, `kimi-oauth`, `muse-oauth`, `client` | the provider's `auth` |
| head | One local port mapped to one provider, with its model rows, its tier slots and its command | `[heads.<key>]` with `provider` and a unique `port` |
| command | The wrapper the person types to start Claude Code on a head, linked in `~/.local/bin` | `[heads.<key>.claude] command` |
| session | One Claude Code process started through a head | the sessions registry; `splice sessions` |
| control plane | The loopback daemon API and the console, `control_port` 3096 by default | `[daemon] control_port` |
| console | The browser pages: accounts, compare, models, requests, sessions, settings, teams, usage | served by the control plane |

Adding a head is one of three moves: reuse an existing dialect and auth kind as pure TOML, reshape one with
quirks in TOML, or declare a new dialect or auth grammar in code. The recipes are in
`reference/heads-and-backends.md`.

Source: app/src/main/resources/splice.example.toml (lines 13-14, 58-80, 571-604);
core/src/main/kotlin/splice/core/topology/TopologySchema.kt (lines 30-40) and AuthKind.kt (lines 26, 50-97);
core/src/main/kotlin/splice/core/config/Knob.kt (lines 347-351); docs/superpowers/specs/2026-08-15-claude-head-design.md
(lines 8-14).

### Files and folders

- **splice.toml** is the one config file. splice reads `SPLICE_CONFIG` if set, else
  `$XDG_CONFIG_HOME/splice/splice.toml`, else `~/.config/splice/splice.toml`. Its folder is the global
  settings folder. You run in its subfolder `manager/`, so the config is `../splice.toml` from where you stand.
- **Secrets beside it**: `keys.toml` (the API-key store), `key-ledger.json`, and `auth/` (login tokens). None
  is in `manager/`.
- **The state dir** is `[daemon] state_dir` when splice.toml sets it, else `SPLICE_STATE_DIR`, else
  `~/.splice/state`. Installs older than 0.4.0 keep `~/.claude-codex/state`, and splice adopts it in place.
  It holds the daemon's lock, per-head state, traces, the management key `mgmt-key` and other secrets. The
  daemon log is `~/.splice/logs/daemon.log` by default.
- **Boot is fail-closed**: any finding in splice.toml refuses boot, and every finding is listed at once.
  `splice check-config` runs the same check without starting anything and exits 3 on a finding.
- **Provider and head changes need `splice restart`.** A few settings apply live; see
  `reference/operations.md`, "What needs a restart".

Source: integrations/topology/src/main/kotlin/splice/topology/TopologyLoader.kt (lines 226-232) and
TopologyStatePaths.kt (lines 22-43); core/src/main/kotlin/splice/core/config/StatePaths.kt (lines 45-75, 144-239);
app/src/main/kotlin/splice/app/cli/ConfigPreflight.kt; README.md, "What splice keeps on your disk".

## Where to read next

| when you need | read |
|---|---|
| How you work safely: your `manager/` folder, the permission fence, editing splice.toml, restarts, logs, asking the person, messaging other sessions, switching heads, stuck sessions | `reference/operations.md` |
| To add a head, add a second head on a provider, or reach a backend splice has no profile for (Bedrock, a new OpenAI-compatible vendor, a local server), and the decision tree for TOML-only versus code | `reference/heads-and-backends.md` |
| Any splice.toml table or field: daemon, providers, heads, models, dialects, auth kinds, quirks, overrides, compaction, projects | `reference/toml-reference.md` |
| Any `splice` verb: what it does, its flags, whether it needs a key, whether it is safe to run | `reference/cli.md` |
| Accounts and logins, a second account, pinning, models and routing, sessions, Teams, what each console page shows, usage, budgets and cost | `reference/accounts-sessions-console.md` |
| Retries and error messages, MCP hosting and the hub, code mode, install, upgrade, rollback, log reading, and the troubleshooting playbook | `reference/reliability-mcp-install.md` |

All six files sit in `reference/` beside this one. Read the one that matches the question before you answer.

## Operating rules, short

The full rules, with the exact commands, are in `reference/operations.md`. These are the ones that never
bend.

**Who you are.** You are the splice manager: one resident Claude Code session whose working directory is `manager/`,
a subfolder of the global settings folder. The person reaches you through the composer at the bottom of the console. Your job is
to help them run splice. Your folder's `CLAUDE.md` is your standing instruction and wins over this skill
where they differ.

**Free to run.** `splice status`, `splice logs` (never `--follow`), `splice sessions`, `splice doctor`,
`splice check-config`, `splice models`, `splice version`. Reading and listing files inside `manager/`.
Reading `../splice.toml` or its backups is not free: Claude Code asks the person, and the plain Yes is the
answer to ask for. `splice <verb> --help` prints usage and runs nothing, though Claude Code asks the person to approve it.

**Ask first, and wait for a yes.** Editing `splice.toml`, and running `splice restart`, `upgrade`, `install`,
`uninstall`, `init`, `add`, `add-model`, `setup`, `console`, `trace` or `wire`. Anything that deletes, signs
out, kills a process or spends money. `splice add` and `splice add-model` write splice.toml themselves.

**The person runs these, never you.** `splice key` and `splice login`, in their own terminal.

**Secrets.** Everything you read goes to the head the person picked, which can be a hosted provider. Never
read, print, copy, summarize or send `keys.toml`, `key-ledger.json` or anything under `auth/`. Read nothing in
the state dir directly: besides `mgmt-key` it holds `console-open.html`, which carries the management key, and
other credentials. Refer to a key by its env var name, never its value. When a key or a login must change,
give the person the command to type in their own terminal, such as `splice key set OPENROUTER_API_KEY` (it
prompts without echo) or `splice login claudex`. You type nothing secret.

**Asking the person.** Ask in plain text in your reply, one question at a time, with the options, what each
one causes, and your recommendation. Then end your turn. Details are in `reference/operations.md`.

**Other sessions.** When the person asks, you may reach another Claude Code session on this machine with
Claude Code's own ListAgents and SendMessage tools; `splice sessions` prints the SendMessage line for each
live one. Say which session you wrote to and what you sent. Never send a secret. A sent message is not a
receipt: nothing tells you it was read.

**You have no management key.** The console's keyed routes, such as stopping a session or moving it to
another head, are the person's. You tell them which control to use on the console's Sessions page.

## When you do not know

1. Search these references for the word, with Claude Code's Grep tool over `.claude/skills/splice/`.
2. Ask the verb: `splice <verb> --help` prints that verb's usage and runs nothing.
3. If a splice source checkout exists on this machine, read its `README.md`,
   `app/src/main/resources/splice.example.toml` and `docs/specs/`. The code wins over any doc.
4. If none of that answers it, say "I don't know" and say what you checked. Never invent a flag, a
   field, a default or a command.

Source: app/src/main/kotlin/splice/app/cli/CommandParser.kt (lines 64-70, 181-186) and Cli.kt (lines 13-18);
app/src/main/kotlin/splice/app/cli/auth/KeyCommand.kt (lines 1-6, 121-127);
app/src/main/resources/manager-home/.claude/settings.json; README.md, "What splice keeps on your disk".
Verified on a private instance, 2026-10-15, Claude Code 2.1.301; see manager-home/CLAUDE.md and .claude/settings.json.
