<div align="center">

<h1><picture><source media="(prefers-color-scheme: dark)" srcset="docs/assets/splice-horizontal-night.svg"><img src="docs/assets/splice-horizontal-day.svg" alt="splice" height="72"></picture></h1>

**Use [Claude Code](https://docs.anthropic.com/en/docs/claude-code) with the models and subscriptions you already use.**

ChatGPT · Grok · Kimi · Muse · API backends · Claude

[Why splice](#why-splice) · [Install](#install) · [Quick start](#quick-start) · [Docs](docs/wiki/README.md) · [Providers](#supported-providers) · [Trade-offs](docs/wiki/architecture-and-troubleshooting.md#why-you-might-not-want-splice) · [Changelog](CHANGELOG.md) · [Security](.github/SECURITY.md)

[![ci](https://github.com/torad-labs/splice/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/torad-labs/splice/actions/workflows/ci.yml)
[![release](https://img.shields.io/github/v/release/torad-labs/splice)](https://github.com/torad-labs/splice/releases/latest)
[![releases attested](https://img.shields.io/badge/releases-attested-1f6feb)](https://github.com/torad-labs/splice/attestations)
[![license](https://img.shields.io/github/license/torad-labs/splice)](LICENSE)

</div>

```text
splice puts Claude Code in front of the backend you choose.

  Requests are translated for your chosen backend.
  Interrupted compactions can resume from their saved answer.
  Retries stop at a set limit, and keys and sign-ins are never saved half-written.
  Your tools, permissions and shared Claude Code configuration stay yours.
  You see usage and what priced turns would cost at API rates.
  Doctor gives a remedy for failing checks.
  Sessions on different heads can discover and message each other.
```

## Why Splice

Choosing a different model shouldn't mean rebuilding your coding workflow around a different client. splice is one local gateway between Claude Code (and Claude Desktop) and every backend you use: ChatGPT, Grok, Kimi and Muse subscriptions, pay-per-token APIs such as OpenRouter or DeepSeek, a local runtime, or your own Claude plan. Type `claude` as you always have, pick a model with `/model`, and keep Claude Code itself: its tools, permission checks and terminal, your hooks, skills, agents, commands, CLAUDE.md and permissions.

The gateway runs on your machine. Model requests go to the provider you chose, signing in happens on each company's own page, and credentials stay on your machine. Subscription connections run on the plan you already pay for; API-key connections use ordinary pay-per-token access.

OAuth logins live in `~/.config/splice/auth/`, API keys in `~/.config/splice/keys.toml`, and Claude Code's own login in `~/.claude`. Splice uses `splice.torad.ai` for release downloads and update checks, and GitHub for release attestation or the rig installer if you choose a local model in `splice setup`. Features you configure, such as MCP, exports and alerts, can contact other destinations. [Credential locations](docs/wiki/providers-and-accounts.md#credential-locations) and [What splice keeps on your disk](docs/wiki/disk-and-privacy.md#what-splice-keeps-on-your-disk) have the details.

| Pillar | What you get | Read more |
| --- | --- | --- |
| **One model list** | Every provider in `splice.toml` is served at the door as `claude-<provider>--<model id>`; Claude Code, Claude Desktop, a LAN door and a team server read the same catalog | [Door and launchers](docs/wiki/door-and-launchers.md) |
| **Your subscriptions** | ChatGPT, Grok, Kimi and Muse sign-ins, account pools with automatic switching, API keys shown as accounts | [Providers and accounts](docs/wiki/providers-and-accounts.md) |
| **Claude Desktop** | `splice desktop on` puts Chat, Cowork and the Code tab on splice, with the reasoning picker and Gemini through Antigravity | [Claude Desktop](docs/wiki/claude-desktop.md) |
| **Models that work together** | Sessions on different models find each other with `ListAgents` and talk with `SendMessage`; Codex threads join as peers | [Peers and harnesses](docs/wiki/peers-and-harnesses.md) |
| **Long sessions that recover** | Compaction resumes from its saved answer, identical retries pick up work already underway, restarts hand over without cutting turns | [Architecture and troubleshooting](docs/wiki/architecture-and-troubleshooting.md#long-session-reliability) |
| **Usage you can see** | Plan usage in Claude Code's status line, daily budgets, a web console with sessions, projects and model comparison | [Console and sessions](docs/wiki/console-and-sessions.md) |
| **Shared MCP servers** | One copy of each stdio MCP server for every session, imported from and synced to your other harnesses | [MCP hub and sync](docs/wiki/mcp-hub-and-sync.md) |
| **A team on one machine** | Keys per person, TLS, identity-provider sign-in, audit log, metrics and cost meters | [Team server mode](docs/wiki/team-server-mode.md) |
| **Extensions** | Modes, door hooks, pre-turn capabilities and code mode for ChatGPT | [Modes and extensions](docs/wiki/modes-and-extensions.md) |
| **A fix, not just an error** | `splice doctor` checks install, configuration and sign-ins and prints the remedy for each failing check | [Troubleshooting](docs/wiki/architecture-and-troubleshooting.md#troubleshooting) |

**Custom heads are deprecated and will be removed in a coming release.** `claudex`, `claude-grok`, `claude-kimi`, `claude-muse` and the other commands `splice add` links each start Claude Code on one backend in a Claude folder of its own. One `claude` through the Splice gateway replaces them: its `/model` is the gateway's one list, and every model name of today, such as `claude-codex--gpt-6.2-sol`, keeps working. `splice claude retire --all` (or `splice claude retire <command>`) retires them with a dated backup, `splice claude restore` brings one back exactly as it was, and `splice claude check` says whether `claude` on the gateway is the one way Claude Code starts on the machine.

## What's new in v0.7.0

> **One gateway, one model list.** Every provider in `splice.toml` is served at the door, with no head needed, and every client reads the same catalog.
>
> **Side-by-side launchers.** `splice launch claude` and `splice launch claude-desktop` run splice beside your own `claude` and Desktop, which stay as they are; `[launch.profiles.<name>]` filters what a launch sees.
>
> **Claude Desktop on splice.** `splice desktop on` puts Chat, Cowork and the Code tab on splice with nothing to paste, and Gemini models arrive through Antigravity (`agy`).
>
> **MCP import and sync.** Bring your existing MCP servers into splice's registry and keep agy, Codex and Desktop in step, with safe pruning of stale entries.
>
> **Team Server Mode grows up.** A team door at `/door/v1`, forward auth with browser sign-in, an audit log, state snapshots, an OTLP relay and external cost meters.
>
> **Modes and a rebuilt console.** `[modes.<name>]`, door hooks, live replies and a Server page.
>
> **Coming from 0.6.4?** Run `splice upgrade`, then `splice setup --update`. 0.6.4's upgrader has no `--wait` and runs no aftercare, so the second command is yours to run. [Upgrading to v0.7.0](docs/wiki/upgrading-to-0.7.0.md) walks through every change, the configuration warnings and the way back.

## Install

Linux and macOS run splice natively; on Windows, run it inside WSL2. You need `bash`, `curl` and Claude Code. Java 21 is taken care of: the installer uses any Java 21 or newer on the machine, and on a machine with none it downloads a Java 21 runtime for splice alone.

```bash
curl -fsSL https://splice.torad.ai/download/install.sh | bash
```

The installer checks every prerequisite and prints the fix for your machine, verifies the release's checksums (and its build provenance when the GitHub CLI is signed in), and puts the jar and the `splice-launch` shim in `~/.local/share/splice/`. Every command splice installs (`splice`, `claude-splice`, a head's command) is a link to that shim, which starts the daemon when needed and then runs the real Claude Code against it. [Getting started](docs/wiki/getting-started.md) covers pinning a version, installing from source and letting your agent do it.

## Quick start

```bash
splice setup            # find your sign-ins and keys, connect plans, choose options
splice launch claude    # Claude Code through the Splice gateway, beside your usual claude
splice doctor           # check everything and print the fix for anything failing
```

`splice setup` says what it finds on the machine, asks which plans to connect (a ChatGPT, Grok, Kimi or Muse sign-in, a DeepSeek or OpenRouter key), shows a summary and writes nothing until you confirm. Inside a session, `/model` lists every model the gateway serves and `/login` opens splice's Accounts page. `splice status` shows the daemon, ports and accounts; `splice console` opens the web console.

### API-key starter: OpenRouter

With an `OPENROUTER_API_KEY` exported (or saved with `splice key set`), OpenRouter is a zero-config path on pay-per-token billing. To keep a discovered roster while choosing which models fill Claude Code's tiers, use `model_slots`:

```toml
[heads.example]
provider = "example"
port = 3105
discovery_prefix = "claude-example--"
pinned_model = "vendor/model-a"
model_slots = { opus = "vendor/model-a", sonnet = "vendor/model-b" }
```

Slot names are opus, sonnet, haiku and fable, with one model per slot. The [configuration reference](docs/wiki/configuration-reference.md) lists every key, and [Getting started](docs/wiki/getting-started.md#api-key-starter-openrouter) the rest of the OpenRouter path.

### A workflow across models

After signing in to the matching accounts, open `claude` in two terminals and pick a different model in each with `/model`: a ChatGPT model in one, a Grok model in the other. Ask one session to implement a change and the other to review it. They discover each other with `ListAgents` and exchange findings with `SendMessage`, so you don't copy messages between terminals, and each session keeps Claude Code's own tools and permissions. [Peers and harnesses](docs/wiki/peers-and-harnesses.md) covers teams, named peers and isolation.

## Everyday commands

```bash
splice status                 # daemon, ports, accounts, and the one command that makes each ready
splice console                # the web console: sessions, accounts, usage, settings
splice login                  # the console's Accounts page, to sign in or add a key
splice upgrade                # fetch, verify and activate the latest release
splice setup --update         # offer what a new release adds
splice desktop on             # put Claude Desktop on splice; splice desktop off undoes it
splice logs --tail 50         # the daemon's recent log
```

The [CLI reference](docs/wiki/cli-reference.md) lists every command and HTTP route, and the [console page](docs/wiki/console-and-sessions.md) covers usage, budgets and the splice manager.

## How it works

A single Kotlin daemon (**spliced**) runs on your machine between Claude Code and the configured backends. It serves Anthropic's Messages API on loopback, translates each request into the chosen provider's wire dialect (OpenAI Responses, OpenAI chat completions or Anthropic passthrough) and streams the answer back; Claude Code remains the tool executor. The `splice-launch` shim asks the daemon for a launch recipe and starts the real `claude` against the gateway. Nothing listens beyond loopback unless you turn on a LAN door or a team server. [Architecture and troubleshooting](docs/wiki/architecture-and-troubleshooting.md) has the diagram, the module layout and how long sessions recover, and [Disk and privacy](docs/wiki/disk-and-privacy.md) lists every file splice writes.

## Documentation

The full documentation lives in the [wiki](docs/wiki/README.md).

| Page | What it covers |
| --- | --- |
| [Wiki home](docs/wiki/README.md) | Find the page for what you want to do |
| [Getting started](docs/wiki/getting-started.md) | Requirements, install, guided setup, first launch |
| [Upgrading to v0.7.0](docs/wiki/upgrading-to-0.7.0.md) | What changed, the upgrade steps, rollback, compatibility |
| [Providers and accounts](docs/wiki/providers-and-accounts.md) | Provider support, sign-in, credential locations, account pools |
| [Door and launchers](docs/wiki/door-and-launchers.md) | `claude` on the door, side-by-side launchers, retiring heads, remote doors |
| [Claude Desktop](docs/wiki/claude-desktop.md) | Desktop on splice, the model catalog, managed launcher, Antigravity |
| [Console and sessions](docs/wiki/console-and-sessions.md) | The web console, plan usage, budgets, prices, the splice manager |
| [MCP hub and sync](docs/wiki/mcp-hub-and-sync.md) | Shared MCP hosting, import, sync and pruning |
| [Peers and harnesses](docs/wiki/peers-and-harnesses.md) | Sessions that message each other, Codex peers, LAN sharing |
| [Team server mode](docs/wiki/team-server-mode.md) | Serving a team: keys, sign-in, audit, metrics |
| [Modes and extensions](docs/wiki/modes-and-extensions.md) | Modes, door hooks, capabilities, code mode |
| [Configuration reference](docs/wiki/configuration-reference.md) | Every `splice.toml` table and environment variable |
| [CLI reference](docs/wiki/cli-reference.md) | Every command and HTTP route |
| [Disk and privacy](docs/wiki/disk-and-privacy.md) | Every file splice writes, credentials, clean uninstall |
| [Architecture and troubleshooting](docs/wiki/architecture-and-troubleshooting.md) | How it works, reliability, trade-offs, doctor and common problems |

## Supported providers

| Route | Auth | Status |
| --- | --- | --- |
| codex (ChatGPT) | ChatGPT sign-in | **Primary** |
| grok (xAI) | xAI sign-in | **Primary** |
| kimi (Moonshot) | Kimi sign-in | **Primary** |
| muse (Meta) | Meta sign-in | **Primary** |
| Claude | Claude Code's own sign-in | **Primary** |
| OpenRouter, Moonshot, DeepSeek | API key | **Supported**: pay-per-token |
| Local runtimes (Ollama, LM Studio, vLLM) | API key on a loopback address | **Supported**: user-managed |
| Your own provider | API key or credential command | **Supported**: declared in `splice.toml` |

[Providers and accounts](docs/wiki/providers-and-accounts.md) has the details, selector prefixes and account pools.

## Compatibility

splice follows SemVer and is still `0.x`: a patch release never breaks a stable contract, and a minor release that does says so in the [CHANGELOG](CHANGELOG.md) with the steps. Each release is tested against one Claude Code version; `splice doctor` and the status line say when a session runs a newer one. The contracts and their checks are listed in [Upgrading to v0.7.0](docs/wiki/upgrading-to-0.7.0.md#compatibility), and the trade-offs in [Why you might not want splice](docs/wiki/architecture-and-troubleshooting.md#why-you-might-not-want-splice). Report a vulnerability privately, as [SECURITY.md](.github/SECURITY.md) describes.

## Development

```bash
bun install --frozen-lockfile
npm run gate   # Gradle, walls, hook tests, oracle/conformance replay, release rehearsal
```

Contracts and invariants live in [AGENTS.md](AGENTS.md), prerequisites and PR rules in [CONTRIBUTING.md](.github/CONTRIBUTING.md), and the change log in [CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE).
