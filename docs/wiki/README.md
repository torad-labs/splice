[README](../../README.md) · [Wiki Home](README.md)

# Splice Wiki

splice is one local gateway between Claude Code (and Claude Desktop) and the models and subscriptions you already use. These pages hold everything the project README points to: how to install and upgrade, how each part works, and every key, command and file. Start with the task you have in mind, or read the directory below.

## Find what you need

| I want to… | Go to |
| --- | --- |
| Install splice and run my first session | [Getting started](getting-started.md) |
| Move from 0.6.4 to 0.7.0, or roll back | [Upgrading to v0.7.0](upgrading-to-0.7.0.md) |
| Sign in to ChatGPT, Grok, Kimi or Muse, or add an API key | [Providers and accounts](providers-and-accounts.md#signing-in) |
| Use more than one account on a provider | [Providers and accounts](providers-and-accounts.md#account-pools) |
| Run a local model (Ollama, LM Studio, vLLM) | [Providers and accounts](providers-and-accounts.md#local-models) |
| Run splice beside my usual `claude` | [Door and launchers](door-and-launchers.md#run-claude-code-beside-your-usual-launch) |
| Retire the old head commands | [Door and launchers](door-and-launchers.md#retire-custom-heads) |
| Put Claude Desktop on splice | [Claude Desktop](claude-desktop.md) |
| See usage, set a daily budget, compare models | [Console and sessions](console-and-sessions.md) |
| Share my MCP servers across sessions and harnesses | [MCP hub and sync](mcp-hub-and-sync.md) |
| Have two models review each other's work | [Peers and harnesses](peers-and-harnesses.md) |
| Use one machine's door from another | [Door and launchers](door-and-launchers.md#use-one-machines-door-from-another) |
| Serve a team from one machine | [Team server mode](team-server-mode.md) |
| Add a mode, a door hook or a pre-turn capability | [Modes and extensions](modes-and-extensions.md) |
| Look up a `splice.toml` key or environment variable | [Configuration reference](configuration-reference.md) |
| Look up a command or HTTP route | [CLI reference](cli-reference.md) |
| Know what splice stores and where my credentials live | [Disk and privacy](disk-and-privacy.md) |
| Fix something that is not working | [Architecture and troubleshooting](architecture-and-troubleshooting.md#troubleshooting) |

## All pages

| Page | What it covers |
| --- | --- |
| [Getting started](getting-started.md) | Requirements, the installer and Java runtime, guided setup, the OpenRouter and subscription starters, first launch, uninstall |
| [Upgrading to v0.7.0](upgrading-to-0.7.0.md) | What 0.7.0 changes, the upgrade steps from 0.6.4, configuration warnings, rollback, compatibility contracts |
| [Providers and accounts](providers-and-accounts.md) | Provider support, models without a head, custom and local providers, sign-in, credential locations, account pools |
| [Door and launchers](door-and-launchers.md) | `claude` on splice's door, one model list, side-by-side launchers and profiles, retiring heads, remote doors, the launch shim |
| [Claude Desktop](claude-desktop.md) | Desktop on splice, the model catalog and reasoning picker, policy and profile sync, the managed Code-tab launcher, Antigravity |
| [Console and sessions](console-and-sessions.md) | The web console and its pages, projects and model comparison, plan usage, daily budgets, prices, the splice manager |
| [MCP hub and sync](mcp-hub-and-sync.md) | Shared MCP hosting, the hub, MCP servers in Settings, import, sync to other harnesses, pruning |
| [Peers and harnesses](peers-and-harnesses.md) | Sessions that see and message each other, teams, named peers, Codex peers, the harness catalog, LAN sharing |
| [Team server mode](team-server-mode.md) | Server configuration, keys per person, identity-provider sign-in, the team door, forward auth, audit, metrics, cost meters |
| [Modes and extensions](modes-and-extensions.md) | Modes, door hooks, the modes that ship, capabilities, code mode for ChatGPT |
| [Configuration reference](configuration-reference.md) | Every `splice.toml` table and key, live reloads, `check-config`, environment variables |
| [CLI reference](cli-reference.md) | Every `splice` command, the installed launchers, the installer, every HTTP route |
| [Disk and privacy](disk-and-privacy.md) | Every file splice writes, credentials and file modes, opt-in capture, clean uninstall |
| [Architecture and troubleshooting](architecture-and-troubleshooting.md) | How it works, long-session reliability, backends, reasoning, layout, trade-offs, doctor and common problems |

## Specifications and observability

The wiki explains how to use splice. The design specifications and operator references it draws on live beside it:

- [Server mode](../specs/server-mode.md), [LAN share](../specs/lan-share.md) and [door networking](../specs/door-network.md)
- [Claude Desktop, full mode](../specs/desktop-full.md), [the Desktop door](../specs/desktop-door.md) and [Antigravity in Desktop](../specs/desktop-antigravity.md)
- [Custom providers](../specs/custom-provider.md), [modular backends](../specs/modular-backends.md) and [the default door](../specs/door-default.md)
- [The MCP hub](../specs/mcp-hub.md), [peer messaging](../specs/peer-messaging.md) and [the harness catalog](../specs/harness.md)
- [The web console](../specs/web-console.md) and [telemetry](../specs/telemetry.md)
- [Observability overview](../observability/README.md), [exporters](../observability/exporters.md), [the measurement contract](../observability/measurement-contract.md), [objectives](../observability/objectives.md) and [troubleshooting with telemetry](../observability/troubleshooting.md)

## Related pages

- [Project README](../../README.md)
- [Getting started](getting-started.md)
- [Upgrading to v0.7.0](upgrading-to-0.7.0.md)
- [Architecture and troubleshooting](architecture-and-troubleshooting.md)
- [CHANGELOG](../../CHANGELOG.md)
