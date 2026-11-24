[README](../../README.md) · [Wiki Home](README.md)

# The Door, Launchers & Terminal Profiles

Use `splice launch claude` to run Claude Code on the Splice gateway alongside your usual `claude`, or move plain `claude` onto the gateway with `splice claude door`. Pick a model with `/model` without changing your Claude Code tools, permissions, terminal, hooks, skills, agents, commands, or CLAUDE.md.

## `claude` on splice's door

The **door** is the gateway on the local control port (default `http://127.0.0.1:3096/door`). The selector on each turn tells splice which model and provider to use; you don't need a separate head for each provider. The terminal uses its own gateway key in splice's state folder. Claude Desktop has a separate key: `splice desktop off` or `splice desktop rotate-key` does not interrupt the terminal.

| Command | What it does |
| --- | --- |
| `splice claude` or `splice claude --json` | Show the lane, door address, starting model and why it was chosen, the model picker, and omitted providers or heads with reasons. `splice status` and `splice doctor` also show the lane. |
| `splice claude door [--model <selector>]` | Link plain `claude` to splice if needed and put new sessions on the gateway. An explicit starting selector must be served by the door. |
| `splice claude head` | Return new `claude` sessions to the configured head whose command is `claude`, if there is one. Clears the saved door model and removes splice's own `/login` file. Existing door sessions keep running. |
| `splice claude check [--json]` | Check whether plain `claude` on the gateway is the one way Claude Code starts through splice, and whether those launches see one model list. Exits 0 only when both checks pass. |

The selected lane and starting model are saved in `~/.splice/state/claude-lane.json` by default and survive restarts and upgrades. Without an explicit lane, a configured local head or remote command named `claude` takes precedence; otherwise a splice-linked `claude` uses the door. **New in 0.7.0:** `splice setup` no longer wraps plain `claude`; it offers a side-by-side launcher instead. An existing wrap is not removed on upgrade. To unwrap plain `claude` and use Anthropic directly again, turn off **Send the claude command through splice** under **Providers → Anthropic** in the console. Switching to the head lane is not the same as unwrapping.

A launch with `--model` uses that selection for the session. Otherwise plain `claude` on the door uses its saved starting selector if still served. With no valid saved choice, it takes the pinned or first model of the first signed-in provider (Claude accounts added to splice first), then the first served provider if none uses a login. A terminal door turn appears in `/api/perf/turns` with `door: "terminal"`. With no usable provider, the door refuses a turn and points you to `/login`.

### One model list and tier aliases

On the local gateway, Claude Code discovers its list from `GET /door/v1/models`. Local gateway launches, including legacy head commands that use the gateway, don't install a picker list or tier variables. Signed-in providers (including ChatGPT, Grok, Kimi and Muse) offer their models under their provider's model names, with `splice · <command>` in their descriptions. Sign-ins and sign-outs reach the next listing without a daemon restart. API-backed providers such as OpenRouter and local servers also appear: the picker shows their declared models, or their discovered models when none are declared. A served model need not be in the picker to be selected with `--model`.

An explicit selector picks its provider. A request for a Claude tier (`opus`, `sonnet`, `haiku` or `fable`) can use the session's current provider, or the starting model's provider if the session hasn't picked one. Set `model_slots` **on the provider** to choose which model each tier means there. It maps tiers to model IDs, not to selectors, and does not change what's discoverable. One model can fill several tiers; a model the provider doesn't offer can't supply a tier. An exact Claude model that the gateway serves can still be selected by name; a named Claude model isn't silently replaced with a provider's pin just because the tier is unmapped.

For example, this is the tier map from the sample ChatGPT provider. Add the key inside your existing `[providers.codex]` table rather than declaring the table twice:

```toml
[providers.codex]
model_slots = { opus = "gpt-6-sol", sonnet = "gpt-5.6-terra", haiku = "gpt-6-luna", fable = "gpt-6-astra" }
```

On an older configuration, `splice check-config` warns about head-only tier settings; `splice check-config --fix` offers to move them to the provider. See the [configuration reference](configuration-reference.md#door-launch-profiles-local-harnesses-and-lan-sharing) for door commands and selector aliases, and the [sample topology](../../app/src/main/resources/splice.example.toml) for complete provider definitions.

### Sign-in and speed commands

Plain `claude` on the door uses your usual `~/.claude` settings, MCP servers and history; splice does not set `CLAUDE_CONFIG_DIR`. It adds only its own `~/.claude/commands/login.md` if the file is absent and disables Claude Code's native `/login` and `/logout` for the door launch. Splice's `/login` opens the console's **Accounts** page in your browser, signed in, and its hook stops the turn before the model sees it. The hook also recognizes `/splice:login` when exposed. If you wrote your own `/login`, splice leaves it alone and `splice claude` reports the conflict; run `splice console` to reach Accounts.

Because the file lives in the usual Claude folder, an editor extension or another Claude Code not started by splice may offer the file alongside its native `/login` and resolve either entry to the file. Without the hook, the file gives you two commands: `splice console` for splice's accounts, and `claude auth login` for Claude Code's own Anthropic sign-in. `splice claude head` and `splice uninstall` remove splice's file, not yours.

| Hook or command | What to expect |
| --- | --- |
| `/fast [on\|off]` | On a door session that last ran a ChatGPT model, set Fast speed if the model offers it. Before a ChatGPT turn, or on another provider, leave Claude Code's own `/fast` alone. |
| `/ultrafast [on\|off]` | Set Ultrafast on a ChatGPT model that offers it. Before any model has run, the hook checks the pinned model of the first active ChatGPT provider. If the model doesn't offer the tier, speed stays unchanged. |
| Session start | The startup hook tells splice where the session started, so it can bind the session to a team using the working directory. |

Speed controls are per session: the bare command toggles that speed, `on` selects it, and `off` returns to Standard. The door stages the speed hook when a ChatGPT OAuth provider is in the lineup. A change is made by the hook rather than sent as a model prompt.

## Run Claude Code beside your usual launch

```bash
splice launch claude
```

`splice setup` and `splice setup --update` can link the optional `claude-splice` shortcut. Both forms go straight to the gateway regardless of plain `claude`'s lane, passing remaining arguments to Claude Code. Without a profile they use your usual Claude folder without installing anything in it. Sign-in hooks ride the launch's `--settings` overlay; without splice's `commands/login.md` already present, the unprofiled side-by-side launch leaves Claude Code's own `/login` available. Use `splice login` or `splice console` for splice's logins. For the graphical app, use [Claude (Splice)](claude-desktop.md).

### Filtered launch profiles

Use a profile to start Claude Code with a filtered copy of your settings and tools rather than all your usual customizations. Put a table like this in `splice.toml` and restart splice so it reads the profile:

```toml
[launch.profiles.work]
keep_hooks = "own"
drop_hooks = ["SessionStart", "notify.sh"]
drop_blocks = ["team-notes"]
drop_names = ["team-", "old"]
```

```bash
splice restart
splice launch claude --profile work
```

| Key | Default | What it controls |
| --- | --- | --- |
| `dir` | `~/.splice/launch-profiles/<name>` | Destination Claude folder. An explicit path must be absolute after expanding `~/`; it cannot be the same as, contain, or live inside the usual Claude or Gemini directory, or resolve to another profile's folder. |
| `claude_json` | Shell's `$CLAUDE_CONFIG_DIR/.claude.json` if eligible; otherwise `~/.claude.json` | Read-only source for the profile's `.claude.json` state, including MCP servers and project metadata. See the [configuration reference](configuration-reference.md#door-launch-profiles-local-harnesses-and-lan-sharing) for allowed paths. |
| `keep_hooks` | `"own"` | `"own"` keeps your settings hooks, `"all"` also permits kept plugins' hooks, and `"none"` keeps neither. |
| `drop_hooks` | `[]` | Exact hook event names, or substrings in hook commands. |
| `keep_plugins` | `[]` | Keep installed plugins by name or `name@marketplace`. |
| `drop_blocks` | `[]` | Remove named blocks from the copied `CLAUDE.md`; see marker syntax below. |
| `drop_names` | `[]` | Omit skills, agents and commands whose names start with one of these prefixes. |

Put each `drop_blocks` marker on its own line. For `drop_blocks = ["team-notes"]`, use `<!-- team-notes -->`, `<!-- BEGIN team-notes -->`, or `# BEGIN team-notes` to open a block, and `<!-- /team-notes -->`, `<!-- END team-notes -->`, or `# END team-notes` to close it. XML blocks can open with attributes, such as `<team-notes audience="internal">`, and close only with `</team-notes>`. Matching XML tags can nest; a self-closing tag such as `<team-notes />` removes only its line. Splice keeps an unclosed block and reports it at launch.

Splice removes one `--profile <name>` or `--profile=<name>` before handing arguments to Claude Code; a `--` ends splice option parsing. A missing name, duplicate flag or unknown profile is an error. Before each launch, splice rebuilds its owned profile folder and sets `CLAUDE_CONFIG_DIR` to that folder for the child process. The source override applies only to `.claude.json`: settings and tools still come from `~/.claude`, and the project and session folders still link there so resume works. Splice strips Claude login and model-picker state and rebuilds MCP configuration from the selected source and its registry. It does not rewrite the source or take over a nonempty folder that it doesn't own.

## Retire custom heads

**Custom heads are deprecated and will be removed in a coming release.** Commands such as `claudex`, `claude-grok`, `claude-kimi`, `claude-muse` and other head commands linked by `splice add` still work in this release. Each uses its own Claude folder and prints a warning with the replacement to type. Most run through the same gateway and model list; a head that forwards your own Claude login still runs on that login. To replace a head, start `claude` on the gateway and choose its current selector with `/model`, for example `/model claude-codex--gpt-6.2-sol`. Existing selectors keep working.

| Command | Effect |
| --- | --- |
| `splice claude retire <command-or-key>...` | Move the named heads' splice-owned launch links and settings into a dated backup. |
| `splice claude retire --all` | Retire all eligible custom heads; upgrades do not retire them for you. |
| `splice claude restore <command-or-key>...` or `splice claude restore --all` | Restore from the latest matching backup, preserving backed-up files byte for byte. |
| `splice claude check [--json]` | Check the resulting launch paths and picker; neither form changes settings. |

Retirement leaves the head's `splice.toml` entry and provider credentials in place, so its models stay on the gateway. If you retire the head plain `claude` uses, splice puts new `claude` sessions on the gateway.

## Use one machine's door from another

On the machine that holds the logins:

```bash
splice share on        # opens port 3097 to this machine's LAN and prints the add command
splice share status    # prints it again with the door's model count
```

While sharing is on, run the printed command on the other machine, then start Claude Code:

```bash
splice remote add http://devbox.lan:3097
claude
```

That links `claude` on the second machine to the first machine's door with one URL in `[remote.claude]` in the second machine's `splice.toml`. It saves no login, key, token or model list; a launch fetches the current picker rows from the sharing machine. Each turn uses the first machine's credentials; `/model` lists its models and `/login` says where to sign in. Use `--as <command>` to link a different name. `splice remote` lists links and whether their machines answer; `splice remote remove <command>` removes one. An unreachable machine is reported before Claude Code starts.

The default share port is 3097; use `splice share on --port <port>` to change it. `splice share status` also prints the environment values for Claude Code clients without splice.

A machine shares its door as a whole. `splice remote add` refuses LAN addresses that name a single provider command or ask for a particular login or model list. An older `[remote.<command>]` entry for a per-command share still loads, but its launch tells you to re-add the server's door address. This is separate from [a keyed team server](team-server-mode.md).

The share listener accepts local-network and tailnet clients only: private and link-local sources, IPv6 sources on one of the machine's own /64s, and peers on its tailnet. The request's `Host` must name the machine and requests from a browser page are refused. There is no join, token or certificate: any device on the admitted LAN can run turns, and prompts and replies cross the LAN unencrypted. **Share only on a network you trust.** Close the listener on the sharing machine when you're done:

```bash
splice share off
```

The first sharing build (October 22–24) wrote imported commands as local heads. Splice refuses to boot with these entries and names each one. Use `splice remote add` with the serving machine's door address to replace an old head with a remote door entry of the same name and remove its old key from `keys.toml`. If you imported more than one name, repeat with `--as <command>` for each name.

## What the launcher does

The installed command links invoke [`splice-launch`](../../app/src/main/dist/bin/splice-launch), the shared shell shim. It reads the invoked name, starts the daemon if needed, asks the loopback control plane for a launch recipe, and executes the real Claude Code with that recipe. A gateway launch uses `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN` and `CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1`, not a `CLAUDE_CODE_USE_*` provider switch. Splice applies the client timeouts and context window, and preserves your `NO_PROXY` entries while adding the door's host. Non-secret settings also ride the per-launch overlay, so a stale provider switch, credential, tier or window in the regular settings environment can't redirect the launch. An explicit `--model` and a caller-supplied `statusLine` are kept. The key is never in the overlay.

The shim carries your shell's `ANTHROPIC_CUSTOM_HEADERS` to the client, replacing only splice's own launch headers with fresh values; it doesn't send your shell headers with the recipe request. The daemon, not the wrapper, talks to the provider.

## Related pages

- [Getting started](getting-started.md#launch)
- [Providers and accounts](providers-and-accounts.md)
- [Claude Desktop and Antigravity](claude-desktop.md)
- [Configuration reference](configuration-reference.md#door-launch-profiles-local-harnesses-and-lan-sharing)
- [CLI and control plane API reference](cli-reference.md#accounts-launchers-sharing-and-remote-commands)
- [Disk and privacy](disk-and-privacy.md#what-splice-keeps-on-your-disk)
