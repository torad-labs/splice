Last read: 2026-10-15 against splice 0.4.0 (feat/v0.4.0)

# The splice command line

Every `splice` verb, what it does, and what you may do with it. `splice <verb> --help` prints that verb's
usage and runs nothing (V4-309: help never reaches the verb). A verb you mistype prints the usage list and
exits 2.

Source: `app/src/main/kotlin/splice/app/cli/CommandParser.kt` (the verb table), `Cli.kt` (usage, exit 2),
`Command.kt` (what each verb calls), `app/src/main/kotlin/splice/app/Main.kt:59-62` (`daemon`, `start`).

## Read this first

- The CLI reads the management key itself when a verb needs the daemon. You never see it, pass it, or type it.
- You may run, without asking, the seven verbs in your CLAUDE.md: `status`, `logs`, `sessions`, `doctor`,
  `check-config`, `models`, `version`. Run `logs` with `--tail N`, never `--follow` (it never exits and blocks
  your pane).
- Every other verb needs the person's yes first, and some you never run yourself (last section).
- A verb that handles a secret (`login`, `key`, `add`, `setup`) is run by the person in their own terminal.
  You give them the exact command and wait.
- Your own turns go through a splice head if the person picked one. `restart` and `upgrade` cut your turn too.
- Run `restart` and `upgrade` in the background (after the yes), and never `logs --follow`. Read nothing in the state
  folder directly: `status`, `doctor` and `logs` read it for you.

## The verb table

Columns. Daemon: whether the verb needs the daemon up (`no` means it works with the daemon stopped).
Key: whether the CLI uses the management key for you (`yes` means the CLI reads it, never you).
You: `free` (run it), `ask` (person's yes first), `person` (the person runs it in their terminal).

| Verb | What it does | Flags | Daemon | Key | You |
|---|---|---|---|---|---|
| `status` | Daemon up or down, one row per head (signed in, key present, runtime answering), accounts, config and jar paths | none | no (adds account rows when up) | optional | free |
| `logs` | Prints the tail of `daemon.log` | `--head <key>`, `--tail N` (default 50), `--follow` or `-f` | no | no | free (no `--follow`) |
| `sessions` | Claude Code sessions on this machine, joined to their head, LIVE/STALE/gone, the SendMessage line | none | no (adds account and model columns when up) | optional | free |
| `doctor` | Five-section health check; every failing check prints its fix command | `--live`, `--json`, `--with-logs`, `--out FILE` | no | optional | free (ask for `--out`) |
| `check-config` | Runs the boot findings pass on `splice.toml` and nothing else | none | no | no | free |
| `models` | Compares each provider's published model list with the rows in `splice.toml` | `[provider]`, `--all` | no | no | free |
| `version` | Prints `splice <version>` | none | no | no | free |
| `perf` | Per-head latency, failure share, retries, cache hit ratio from the perf files | `--window 1h\|24h\|7d` | no | no | ask (read-only, not on your free list) |
| `trace` | A head's full request/response trace from day files | `<head>`, `--last N`, `--session S`, `--turn ID`, `--json`, `--purge` | no | no | ask (prints prompts; `--purge` deletes) |
| `wire` | Upstream request bodies a head sent, only if wireTap is on for that head | `<head>`, `--last N`, `--json` | yes | yes | ask (prints prompts) |
| `listeners` | The ports this build would listen on, for a socket manager | none | no | no | ask (read-only) |
| `capabilities` | What this build can do about inherited sockets | none | no | no | ask (read-only) |
| `shim-version` | Prints the launch-shim version this jar expects | none | no | no | ask (read-only) |
| `restart` | Stops the daemon and starts it again | `--now` | starts it | yes | ask |
| `upgrade` | Fetches, verifies and activates a release, restarts, runs doctor | `--to vX.Y.Z`, `--now`, `--rollback`, `--check` | no (restarts it) | yes | ask (`--check` is read-only) |
| `console` | Starts the daemon if needed and opens the console in the person's browser, unlocked | none | starts it | yes | person |
| `login` | Signs in to a head (browser or device flow, or a masked key prompt) | `<head>`, `--label <name>`, `--discard` | no | no | person |
| `key` | The API-key store (`keys.toml`) | `set <ENV_NAME> [--value V \| --stdin]`, `list`, `unset <ENV_NAME>` | no | no | person |
| `add` | Adds a provider and a head without editing TOML; writes `splice.toml` | `<profile>`, `--name`, `--base-url`, `--model`, `--command`, `--key provider\|new`, `--live`, `--yes`, `-y` | optional | yes | person |
| `add-model` | Picks OpenRouter catalog rows through a prompt and writes them | none | no | no | person |
| `setup` | Guided first-run flow; writes TOML, installs commands, may restart | none | no | yes | person, never you |
| `init` | Writes the starter `splice.toml` if absent, and the MCP hub env file on Linux | none | no | no | ask |
| `install` | Links head wrapper commands into `~/.local/bin` | `[<head>\|--all]` | no | no | ask |
| `uninstall` | Removes head wrapper links (never the jar, never config) | `[<head>\|--all]` | no | no | ask |
| `daemon` | Runs the daemon in the foreground (default with no verb) | none | is the daemon | no | never |
| `start` | Starts the daemon through its systemd unit, else in the foreground | none | is the daemon | no | never |
| `record-launch`, `pending-login` | Internal: the launch shim and sign-in hooks call them | internal | no | no | never |

Source: the verb table in `CommandParser.kt`; flags are each verb's own parser:
`features/diagnostics/.../doctor/report/DoctorReportOptions.kt` (doctor), `features/diagnostics/.../logs/LogsCommand.kt`
(logs), `features/models/.../ModelsCommand.kt`, `features/usage/.../PerfCommand.kt`,
`features/diagnostics/.../wire/WireArgs.kt`, `features/turns/.../trace/TraceCommand.kt`,
`features/configuration/.../add/AddArgs.kt`, `features/lifecycle/.../upgrade/UpgradeArgs.kt`,
`app/.../cli/auth/KeyCommand.kt`. The "Daemon" and "Key" columns are read from each verb's code; `add`'s
daemon use is the restart and probe it offers at the end (`AddCommand.kt`). `start` and `daemon` are in
`Main.kt`, not in the verb table, so `splice start --help` is not a documented form (unverified beyond Main.kt).

## Exit codes

| Code | Meaning |
|---|---|
| 0 | The verb succeeded. `--help` also exits 0 |
| 1 | The verb ran and failed or refused. A verb that refuses prints one line starting `splice:` |
| 2 | The verb is unknown or its words are not a verb's: usage printed |
| 3 | `check-config` only: splice would refuse to boot on this `splice.toml`; every finding is printed |

`doctor` exits 1 only on a real failure (a stopped daemon or an unused unsigned head is not one). `models`
exits 1 when a declared row is over a published ceiling or no longer served. `install` exits 1 when a
wrapper name is taken or the head is unknown. `upgrade` exits 1 when it refused or the wait expired.

Source: `Cli.kt` (2 and the `guarded` failure boundary), `ConfigPreflight.kt:22-56` (`CONFIG_REFUSED_EXIT = 3`),
`DoctorCommand.kt` (verdict), `ModelsCommand.kt:46` (true when providers agree), `Command.kt:60-72` (install).

## The verbs you run most

### status

```
splice status
```

Prints one wordmark line (`daemon running on <port>` or `daemon stopped`), a table with one row per head,
account pool rows when the daemon is up, then the config path and the jar path. It works with the daemon
stopped. It parses `splice.toml` with `loadOrMaterialize`, so on a machine with NO `splice.toml` it writes the
starter file. On an installed machine that never happens.

A stopped daemon is normal: it starts on the first head launch.

Source: `app/.../cli/status/StatusCommand.kt:46-84`.

### logs

```
splice logs --tail 100
splice logs --head claudex --tail 200
```

Reads `daemon.log` from the state's logs directory. It needs no daemon and no key, so it is the first thing to
run when the daemon will not start. `--head <key>` keeps lines tagged `[<key>]`. A missing file prints
`[logs unavailable: <path> does not exist yet; the daemon creates it when it starts]` and exits 0.
The verb reads the last 4 MiB of the current file only; the rolled `daemon.log.1` is not read. `--tail` must be a
positive integer. Never run `--follow`.

Source: `features/diagnostics/.../logs/LogsCommand.kt:22-31, 118-167` (default tail 50), `LogFileSource.kt:41-47,161`
(absent-file line, 4 MiB read), `Main.kt:294` (one rolled generation at 64 MB). `LogsCommand.kt:1-6` says a missing file
is empty output; the code prints the bracketed line (the code wins).

### sessions

```
splice sessions
```

Lists the Claude Code sessions registered in `~/.claude/sessions`, one line each: name, head, model, status,
availability, age and folder. LIVE sessions also get the copyable SendMessage line. Words: `live` is a running
process, `stale` is a live process with no registry update for 30 minutes, `gone` is an exited process.
`direct` in the head column means the session never went through splice. `unknown head` means splice cannot
place it. Headless `claude -p` runs never register and never appear.

Source: `features/sessions/.../list/SessionsCommand.kt:60-110`, `.../registry/SessionRegistry.kt:23,243`
(`STALE_AFTER_MS` = 30 minutes). The console's Sessions page uses "ended" for what this verb calls `gone`
(see accounts-sessions-console.md).

### doctor

```
splice doctor
splice doctor --live
splice doctor --json
splice doctor --json --with-logs --out /tmp/splice-report.json
```

The "why is it not working" command. Five sections: prerequisites, installation, configuration, daemon, auth.
A pass is one dim line. A failure prints a glyph, the check name and the exact fix command on its own line.
Secrets are reported by presence only.

| Flag | Effect |
|---|---|
| none | Text report. Exit 1 only on a real failure |
| `--live` | Also sends ONE tiny streamed request with one tool to each local-runtime model (Ollama, LM Studio, vLLM, generic OpenAI-compatible) so tool calling and streaming are proven. The only flag that sends a model turn, and only to local runtimes; cloud heads are not called. Plain `doctor` already makes read-only GETs of each local runtime's model list, and `models` calls every provider's list endpoint |
| `--json` | The same findings as a redacted JSON report (allowlisted fields; no credentials, account ids, emails or working directories) |
| `--with-logs` | With `--json` only: appends the last 500 daemon log lines through the same redaction |
| `--out FILE` | With `--json` only: writes the report to FILE instead of stdout. Ask first, it writes a file |

Anything else, or `--with-logs`/`--out` without `--json`, prints usage and writes nothing. Doctor also reports
`splice.toml` as stale after an edit that needs a restart.

Source: `features/diagnostics/.../doctor/DoctorCommand.kt:50-110` (flags, verdict),
`.../report/DoctorReportOptions.kt` (strict flag grammar, `DOCTOR_USAGE`), `.../report/DoctorJsonReport.kt:1-8`
(allowlist, nothing uploaded). `--live` scope: `DoctorCommand.kt` header comment and README "Local models".

### check-config

```
splice check-config
echo $?
```

Reads `splice.toml` and runs exactly the findings pass boot runs. It never writes, never starts a daemon and
never offers a fix. Output is empty and exit 0 when the file is clean, or when `splice.toml` does not exist yet
(a first run boots a starter). Exit 3 with every finding listed when boot would refuse. An unreadable file or a
dangling symlink is also exit 3. Run it after every edit of `splice.toml`, before you offer a restart.

Source: `app/.../cli/ConfigPreflight.kt:1-56`.

### models

```
splice models
splice models openrouter --all
```

For each provider in `splice.toml`: asks the provider what it serves (using the stored credential, the same call
the daemon makes), and marks each declared row served, capped (this row caps a larger ceiling), needs a decision
(declared but not served), or new (served, not declared). Undeclared models show eight at a time; `--all` shows
the rest. A provider that publishes no list prints a dash and counts as agreeing. One provider name limits the
report. Exit 1 if any provider that answered disagrees with `splice.toml`, or a name matches no provider (the
error lists the declared ones). It makes network calls to every provider it reports on.

Source: `features/models/.../list/ModelsCommand.kt:20-90`, `app/.../ModelsWiring.kt:21-34`.

### version

```
splice version
```

Prints `splice <GATEWAY_VERSION>`. This is the version of the jar that runs the CLI, which can differ from the
running daemon. `splice status` counts the daemon as running only when its version equals the CLI's, so a daemon left
running from an older jar reads as "daemon stopped" there. If status says stopped while heads still answer, run `splice doctor`
for the daemon section, then ask about a restart (the version match rule is the only cause this file verified).

Source: `Command.kt:36-38`, `app/../cli/status/StatusCommand.kt:46-48` (`up = health?.version == GATEWAY_VERSION`).

## The verbs that need the person

### restart

```
splice restart
splice restart --now
```

Stops the daemon (every head with it) and cold-starts it from the CLI's shell. Say plainly before you ask:
**a restart cuts every turn in flight in every session on this machine, including yours.** Sessions retry on
their own and come back. Without `--now` it first waits for running compactions, up to 15 minutes. `--now`
skips that wait. A systemd user unit that runs the daemon restarts through the unit instead.

The new daemon reads its API-key environment variables from the shell that ran `restart` (or the unit's
environment). A restart run from your pane would carry YOUR environment, not the person's, so prefer the console's "Restart
splice" button or the person's own terminal; if you do run it after a yes, run it in the background. Keys in `keys.toml` are
re-read per request and need no restart.

Needs a restart: any provider or head change in `splice.toml`, tier mappings, ports. Needs none: a model's
`context_window`, `extra_windows`, `window_rules`, `default_context_window`, a head's `context_window`, a key
saved with `splice key set`, and budgets (a budget edit applies within a second). Doctor says when `splice.toml` is stale.

Source: `features/lifecycle/.../restart/RestartCommand.kt:46-130`, `README.md` "Long-session reliability" and
"Troubleshooting". Retry behaviour after a restart is in reliability-mcp-install.md.

### login

```
claudex login                       # the head's own command
splice login claudex
splice login claudex --label work   # a further account of the same kind
splice login claude-splice --label work
```

`<head>` is a head key or its wrapper command; with no head and exactly one sign-in-capable head it picks that
one. The flow depends on the provider's auth kind:

| Auth kind | What happens |
|---|---|
| `chatgpt-oauth`, `grok-oauth` | Browser OAuth flow (loopback redirect) |
| `kimi-oauth`, `muse-oauth` | Device code flow: enter a code on the vendor's page |
| `api-key` | Masked terminal prompt, stored in `keys.toml`. `--label` is refused |
| `client` (Claude) | Claude Code signs in with its own `/login`. `--label <name>` saves or switches a named copy of that login; `--discard` lets a switch drop an unlabeled login |

`--label` adds an account beside the primary one. `--discard` only means something with `--label`. Without
`--label` the primary account is signed in again in place. The labelled-copy flow for Claude refuses to touch a
head while any of its sessions runs.

Source: `app/.../cli/auth/LoginCommand.kt:38-176`, `ClaudeLoginLabel.kt:30-45`, `CommandParser.kt` (login grammar),
`README.md` "More than one Claude login". Accounts and pools: accounts-sessions-console.md.

### key set | list | unset

```
splice key set OPENROUTER_API_KEY              # masked prompt; the person types the key
printf '%s' "$KEY" | splice key set NAME --stdin   # for scripts the person writes
splice key list                                # names only, "<NAME> = stored"
splice key unset OPENROUTER_API_KEY
```

The store is `keys.toml` beside `splice.toml`, mode 0600. `set` with no flag prompts masked and refuses without
a terminal. `--value V` works but shows in `ps` and shell history; `--stdin` does not. A saved key is used by
the next request, no restart. An environment variable of the same name in the daemon's environment wins over the
store. `list` prints variable names only, never values. You do not run any `key` form: you give the person the
command and the variable name from `splice.toml` (`auth.env`, or `<KEY>_API_KEY` by default).

Source: `app/.../cli/auth/KeyCommand.kt:60-170`, `README.md` "API-key starter".

### add (an api-key or OAuth provider)

```
splice add openrouter
splice add deepseek --yes
splice add api-key --name myvendor --base-url https://api.example.com/v1 --model some-model:128000
splice add openrouter --name or-two --key new --command claude-or-two
splice add codex --live
```

Profiles: `codex`, `grok`, `kimi`, `muse`, `deepseek`, `openrouter`, `claude`, `local`, `api-key`.
It picks a free port, signs in through the `login` flow, checks the candidate before writing (the file parses,
the credential is present, the endpoint answers, models agree with published windows), saves with a temp file
and one rename, links the command, and offers the restart. A refusal before the save leaves `splice.toml`
byte-identical. `--live` runs one short real turn through the new head. `--yes` or `-y` takes every default
and asks nothing, and it restarts the running daemon (`AddCommand.kt:finish`). A second head on a provider already in the file needs
`--name`, and `--key provider|new`; a profile that ships its own command needs `--command` for the second head.
`--model ID[:WINDOW]` may repeat.

This verb is the quickest route for any head a profile covers. For an endpoint with no profile, see
heads-and-backends.md. It changes `splice.toml`, so it is the person's, not yours.

Source: `features/configuration/.../add/AddArgs.kt`, `AddProfileCatalog.kt` (profile names), `AddCommand.kt:1-60`,
`README.md` "Subscription setup" and "A second head on the same provider".

### install and uninstall

```
splice install --all
splice install claudex
splice uninstall claudex
```

`install` links one wrapper symlink per head (named by the head's `command`) into `~/.local/bin`, pointing at
the shared launch shim, plus the `splice` command itself. It refuses a name that a foreign file already holds
and says which. With `--all` on Linux it also creates the MCP hub's systemd user units where none exist.
`uninstall` removes only those symlinks; it never removes the jar, the shim, `splice.toml` or credentials.
Both accept a head key or its command, or `--all` (the default with no word). Neither restarts anything.

Source: `features/launch/.../install/InstallLinker.kt:44-70`, `UninstallCommand.kt:19-45`, `Command.kt:56-75`.

### upgrade

```
splice upgrade --check
splice upgrade
splice upgrade --to v0.4.0
splice upgrade --rollback
```

`--check` is read-only: it prints the installed and the newest release on the update channel, and stages nothing.
A plain `upgrade` fetches the release, verifies checksums (and attestation when `gh` is signed in), stages it
under `~/.local/share/splice/releases/<version>`, runs the new release's own `doctor` and `check-config`, waits up
to 30 minutes for turns in flight (`--now` skips the wait), repoints the live jar, restarts, and runs doctor. A
release that does not come up healthy is put back. `--rollback` restores the previous release and takes no `--to`.
A second upgrade while one runs is refused. Config and credentials are never touched.

`--auto` is the daemon's own updater, not for you. The usage line `splice upgrade --help` prints omits `--check`
(it is accepted; see `UpgradeArgs.kt:32`). Install and rollback detail: reliability-mcp-install.md.

Source: `features/lifecycle/.../upgrade/UpgradeCommand.kt:1-110`, `UpgradeArgs.kt`, `UpgradeCheckLine.kt`,
`README.md` "Install a release".

### console

```
splice console
```

Starts the daemon if it is not running, writes a redirect page in the state folder (mode 0600) and opens it in the
person's browser. The page carries the management key in the URL fragment, which never reaches the daemon or a
log; the key is never printed. If no browser opens, the verb prints the page's `file://` path to open by hand.
It exits 1 if the daemon cannot start, or the management key is unreadable or absent (then
`splice restart` creates it). You never need it: the person opens the console from their own terminal.

Source: `app/.../cli/status/ConsoleCommand.kt:1-55`.

### init

```
splice init
```

Writes the starter `splice.toml` only when none exists. Then writes or refreshes the manager's files in the
`manager/` subfolder beside it from the jar (this `CLAUDE.md`, `.claude/settings.json` and this skill) and prints
`splice: the splice manager's folder is <path>`. On Linux it also writes the MCP hub front's environment file
(`mcp-front.env`). Prints `splice: topology already at <path>` when the file is there. `install.sh` runs it on
every install and upgrade, and every daemon start refreshes the manager's files too. A file the person edited is
kept as they left it. The record of what splice wrote is `<state>/manager-home/written.json`. It never refuses
boot or init (best effort).

Source: `app/src/main/kotlin/splice/app/InstallWiring.kt` (`init`), `app/src/main/kotlin/splice/app/manager/ManagerHome.kt:1-50`
(both were uncommitted in the shared checkout when read), `docs/specs/v0.4.0.md` section 15.

### uninstall of splice itself

There is no verb that removes splice. `splice uninstall --all` removes wrapper links only. Removing the jar,
`~/.local/share/splice`, state and config is manual and is the person's decision.

## Never run these yourself

| Never | Why |
|---|---|
| `splice login`, `splice key set`, `splice add`, `splice setup`, `splice add-model` | They handle a credential or write `splice.toml`. Give the person the command; they run it |
| `splice setup` | With no terminal it takes every default and writes, installs commands, may restart and may wrap plain `claude`. A pane you drive is not a safe terminal for it |
| `splice add ... --yes` | Skips every question and restarts the running daemon, which cuts turns in flight |
| `splice restart`, `splice upgrade` without the person's yes | Cuts turns in flight, yours included |
| `splice logs --follow` | Never exits; blocks your pane |
| `splice daemon`, `splice start` | Runs or starts the daemon in your shell; a second daemon is refused, and a foreground one ties up the pane |
| `splice trace --purge` | Deletes a head's trace files; not undoable |
| `splice console` | Opens a browser on the person's desktop with the key in the URL |
| Anything that reads `keys.toml`, `auth/`, `key-ledger.json` or `mgmt-key` | `cat`, `grep`, `cp`, a script: denied when the command names the file; a glob can still slip past (see operations.md, section 2). A key you meet in output is named by its setting, never repeated |

Source: manager-home `CLAUDE.md` (what you may do, secrets, ask first); `SetupCommand.kt:1-5` (non-TTY defaults);
`AddArgs.kt` (`--yes`); `LogsCommand.kt:27-31`; `TraceCommand.kt:47-56`.

## Recipes

### Is splice healthy?

1. `splice status` and read the head rows.
2. `splice doctor` and read each failing check's fix line.
3. `splice logs --tail 100` for the last errors.
4. Verify: `splice doctor` ends with "Everything checks out." or "No blockers".

### Did my edit of splice.toml break boot?

1. `splice check-config`; exit 0 and no output is clean.
2. Exit 3: read every finding listed (boot lists all at once). Fix them or put the `.bak` copy back.
3. Verify: `splice check-config` exits 0, then `splice doctor` shows no configuration failure.

### Why does a model say it is not served?

1. `splice models <provider>` and find the row marked "needs a decision".
2. Fix the row id or window in `splice.toml` (copy to `.bak-<date>` first), then `splice check-config`.
3. Verify: `splice models <provider>` shows the row served.

For TOML fields see toml-reference.md; for adding or reshaping heads see heads-and-backends.md; for retries,
MCP, install and upgrade see reliability-mcp-install.md; for safe working order see operations.md.
