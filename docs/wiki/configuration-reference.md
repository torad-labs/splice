[README](../../README.md) · [Wiki Home](README.md)

# Configuration Reference

Use `splice.toml` to choose what splice serves and how it runs. Find the file your daemon reads, then look up the table or knob you want to change.

[File and precedence](#locations-and-precedence) · [Live changes](#which-changes-take-effect-live) · [Provider schema](#providers-models-and-prices) · [Standing prompts](#standing-system-prompts) · [Serving knobs](#serving-knobs-in-defaults-and-override-tables) · [Environment](#environment-variables)

## Locations and precedence

- **One user topology file.** A nonempty `SPLICE_CONFIG` selects a file directly (`~/` expanded); otherwise splice loads `$XDG_CONFIG_HOME/splice/splice.toml` when that base is nonempty, else `~/.config/splice/splice.toml`. There is no automatic per-project `splice.toml` merge. `[projects."<root>"]` in the selected user file controls the project prompt layers; project-scoped compaction lives in `[[compaction.project]]`. Claude Code's own launch/profile settings and MCP declarations are separate client files, not layers of splice's TOML.
- **Home.** Nonblank `HOME` wins over JVM `user.home`; the shim passes its resolved home as `-Duser.home`. Kotlin tests can explicitly use the internal `UserHome.within` seam, restoring the prior resolution on exit; that does not affect `processDir`. App test JVMs strip ambient `SPLICE_CONFIG` and `XDG_CONFIG_HOME`; the test Java wrapper sets a separate `HOME` and `user.home` for each fork under `SPLICE_FORK_HOME_BASE`. Production has no separate test-home TOML layer.
- **State selection.** Usable `[daemon] state_dir` wins over `SPLICE_STATE_DIR`, then `CLAUDEX_STATE_DIR`, then `~/.splice/state`; blank/unusable TOML falls through (doctor can name the bad value). Absent explicit override, adopt `~/.claude-codex/state` if only that directory exists, or if both directories exist and only the legacy directory verifiably has the management key. The state parent is the splice root for root-relative artifacts, such as launch profiles. Publish/downloaded rates cache is `<state>/published-rates.tsv`.
- **API-key store.** Owner-only `keys.toml` adjacent to selected `splice.toml`; a provider's API-key env value precedes its optional auth file and then the stored value. OAuth uses provider `auth.file` or registered default (not the API-key table).
- **Serving knobs, lowest to highest.** Enum defaults → boot TOML's `[defaults]`, then operator telemetry and the supported explicit `[daemon]` fields, then sole-legacy-head seeding for the six legacy knobs → `<state>/config.json` → first nonempty env alias per knob → in-memory runtime PATCH → provider override → own legacy head override. The last two are specific to the route being served; a global view has neither. Gateway compatibility fills unset serving values from agreeing legacy declarations where applicable. Per-head overrides beat a global runtime PATCH. A global PATCH persists best effort to `<state>/config.json` (reports persistence status); on next boot that saved copy is below env again. A per-head PATCH edits `splice.toml`, landing provider-carried knobs in its provider table and other permitted knobs in the head table. A daemon-wide knob cannot be set per head; provider serving bounds are the explicit exception at provider scope, not at head scope.
- **Effort.** The default effort above is a fallback, not stronger than the turn's explicit effort or the Claude effort selection; provider/dialect clamps and defaults apply. Compaction retains session model and effort.

Sources: `integrations/topology/src/main/kotlin/splice/topology/{TopologyLoader,TopologyStatePaths}.kt`, `core/src/main/kotlin/splice/core/config/{StatePaths,UserHome,ConfigService,ConfigSnapshotCache,KeyStore}.kt`, [TopologyKnobLayer.kt](../../core/src/main/kotlin/splice/core/topology/TopologyKnobLayer.kt), `features/configuration/src/main/kotlin/splice/configuration/knobs/{ConfigRoutes,HeadOverrideWrite,HeadOverrideLanding}.kt`, [ApiKeyAuthProvider.kt](../../integrations/providers/openai/src/main/kotlin/splice/provider/openai/ApiKeyAuthProvider.kt):102–109, [build.gradle.kts](../../app/build.gradle.kts):160–183 and [java](../../gradle/fork-home/bin/java).

### Reading the configuration answer

Use `GET /api/config` to see the effective knob values. Add `?head=<key>` to include that head's serving overrides. Each entry in `knobs` includes:

| Field | What it tells you |
|---|---|
| `value` | Effective value, after merging and normalization. |
| `scope` | Winning layer: `default`, `toml`, `head`, `file`, `env` or `runtime`. The `head` scope includes inherited provider overrides; `env_names` lists the aliases for an `env` value. |
| `disposition` | `live` applies on the running daemon's next read; `restart` requires restarting the daemon. |
| `editable`, `read_only_reason` | Whether the general settings control can change the knob, and why when it cannot. |

`PATCH /api/config?head=<key>` edits the applicable override tables with a backup first, preserving comments. A global PATCH refuses head-only settings. `mirrorReasoning` is pinned false: its disposition is `restart`, and a PATCH trying to enable it answers with `false` under `applied`. Writing true in TOML or setting `CLAUDEX_MIRROR_REASONING` cannot turn it on.

## Which changes take effect live

- **TOML file edits normally need a restart** for topology (providers, heads, ports, auth declarations, sharing listeners, server config, startup MCP policy, standing prompts, capabilities). Hand edits to `[defaults]` and the `overrides` tables do not inject new knob values into a running daemon. Fresh CLI invocations and specific helper paths may read the file for their own operations; this is not daemon topology reload.
- **TOML reload exceptions:** running head catalogs re-read provider model-row `context_window`, `extra_windows`, `window_rules`, `default_context_window` and head `context_window` without rebuilding everything else; local-runtime changes wait for validation. The file reader also supports live `[compaction]` and `[daemon] mcp_share` for new connections. Updates re-read `auto_update` and `update_channel` on each look; the price refresh re-reads `refresh_prices` independently. The staleness comparator's explicit live-key mask contains the window fields listed above, `[compaction]`, `mcp_share`, `auto_update` and `update_channel`; it does **not** mask `refresh_prices` despite that independent live read. Do not equate every context-related provider field with a pure hot edit: the mask does not drop provider-level `context_window`, `served_window`, or the runtime knob `contextWindowOverride`.
- **PATCH and state layer:** runtime-live knobs apply on their next read; those marked restart remain snapshotted by their restart-bound consumers. `GET /api/config` reports per-knob disposition, PATCH reports `restart_required`. ConfigService observes state file changes; it can also update the running override maps after the corresponding API write. Mode switches made through the daemon's mode API update its running mode settings and persist TOML; manual file edits are not the mode API.
- **Digest is not a reload mechanism for all topology.** `TopologyWindows` keeps the running digest distinct from the last seen file digest, and advances only after the unchanged boot topology (modulo live keys) and pending validation allow it. A syntactically unreadable or refused changed window/rule does not overwrite the corresponding last good value.

Sources: [Topology.kt](../../core/src/main/kotlin/splice/core/topology/Topology.kt):135–163,389–394, [TopologyWindows.kt](../../app/src/main/kotlin/splice/app/daemon/TopologyWindows.kt):135–310, [Daemon.kt](../../app/src/main/kotlin/splice/app/Daemon.kt):430–441, [LifecycleWiring.kt](../../app/src/main/kotlin/splice/app/LifecycleWiring.kt):90–112,135–142, `app/src/main/kotlin/splice/app/modes/{DaemonModes,ModeSwitch}.kt`, `core/src/main/kotlin/splice/core/config/{ConfigService,ConfigSnapshotCache,KnobKind}.kt`, [KnobViews.kt](../../features/configuration/src/main/kotlin/splice/configuration/knobs/KnobViews.kt):60–68.

## Check configuration

```bash
splice check-config
splice check-config --fix
```

- `splice check-config` reads the selected configuration without materializing a missing file, writing config, starting the daemon, or opening a fix session. It uses boot preflight: structural and schema checks, capability/mode and topology checks, and applicable local-runtime validation. Returns **0** if absent or acceptable (prints advisory warnings), **3** for unreadable or boot-blocking findings (reports them together). It is not a promise that later listening/bind or startup-only operational steps succeed.
- `--fix` off a terminal says a terminal is required and just checks. On a terminal it offers corrections for the blocking findings, then advisory warnings where boot would otherwise proceed; does not automatically choose changes. Depending on the finding: move compatible head settings to the provider, edit (uses `VISUAL`, else `EDITOR`), remove/replace an assignment, clear a saved setting, skip or quit. An environment finding must be cleared where exported; no shell/service environment rewrite. The file is backed up before the first edit; saved settings get their own backup before first clear. This verb uses `StatePaths().configBackupsDir` (the environment/default state's `config-backups`), not a backup path read independently from the file being repaired. It rechecks after edits and returns the final check's status, so leaving only warnings still returns 0. For a move of a head standing prompt, the offered rewrite moves its text/file/mode companions together, and does not offer an unsafe move.
- Daemon boot refuses blocking config and the normal interactive CLI refusal path can offer remediation. The environment/saved spellings of retired knobs are **warnings** when the topology is otherwise acceptable; values are ignored and the daemon clears retired saved values at start. Retired TOML assignments are **errors**. Some legacy head settings the shared gateway can already honor are warnings, offered for explicit relocation; an upgrade does not rewrite them silently.

Sources: `app/src/main/kotlin/splice/app/cli/{Command,CommandParser,Cli,ConfigPreflight,ConfigFixOffer,ConfigFixSession,FixOffers,MoveFix,TomlMove}.kt`; `integrations/topology/src/main/kotlin/splice/topology/{TopologyLoader,ConfigFindings,RetiredSettings}.kt`.

## Schema at a glance

See the full [example splice.toml](../../app/src/main/resources/splice.example.toml) for provider and model examples. **Unset** means omit the field (not a TOML null literal). **Required** means it has no default unless marked as a validation requirement.

Schema source: [Topology.kt](../../core/src/main/kotlin/splice/core/topology/Topology.kt):50–73 declares `daemon`, `telemetry`, `claude`, `compaction`, `defaults`, `providers`, `heads`, `projects`, `share`, `remote`, `server`, `door`, `launch` and `harnesses`. Empty maps have no entries, optional object tables take their member defaults. `capabilities` and `modes` are parsed separately by `TopologyLoader.kt:341–355` and attached as extensions, not a table named `extensions`. There is no root `pricing`, `budgets`, `prompts` or `mcp` table: see provider/head cards, `server.budgets`, prompt fields, and the daemon/knob MCP settings below.

For example, set the control port and a shared concurrency limit as follows. If the tables already exist, add or replace the keys within them instead of repeating the table headers.

```toml
[daemon]
control_port = 3096

[defaults]
maxInflight = "12"
```

## Daemon

`[daemon]`: [Topology.kt](../../core/src/main/kotlin/splice/core/topology/Topology.kt):166–209. All serialized members are nullable; effective defaults shown.

| Key | TOML type | Default | Meaning |
|---|---|---|---|
| `control_port` | integer | 3096 | Loopback control, console and local gateway listener; named `controlPort` in the knob layers. |
| `state_dir` | string | state path resolution below | Root of the daemon state/lock/history; overrides state-path env vars if usable. |
| `show_reasoning` | string | `text` | `text`, `thinking` or `off`; see `showReasoning` knob. |
| `summary` | string | `detailed` | `detailed`, `concise`, `auto` or `none`. |
| `effort` | string | unset at knob layer | Default model effort; provider maps/falls back when unspecified. |
| `replay_reasoning` | bool | true | Replay prior reasoning for supported providers. |
| `mirror_reasoning` | bool | false, locked off | Does not enable copying reasoning summaries into transcripts even if set true. |
| `mcp_hosting` | bool | true | Enable daemon hosting for eligible MCP servers. |
| `mcp_hosting_exclude` | string array | empty | Server names excluded from hosting. |
| `mcp_share` | bool | true | New connections share eligible MCP instances rather than starting one copy per session. |
| `update_channel` | string | `stable` | Follow stable releases or `beta` (includes prereleases). |
| `auto_update` | bool | false | Permit automatic installation of a newer release. |
| `refresh_prices` | bool | true | Enable hourly price-table refresh; off keeps the bundled or last-fetched table. |
| `alert_command` | string array | unset | Operator's notification program and argv, run without a shell, alert on stdin. File only. |
| `reserved_peer_names` | string array | empty | Additional peer names refused at registration. |
| `antigravity` | bool | true | At boot try the installed `agy` for models to offer in Desktop; false does not run it. |

The `mcp_*_ms` host limits and other serving knobs are **not** members of `DaemonConfig`; use the camelCase names under `[defaults]`.

## Providers, models and prices

`[providers.<id>]`: [Topology.kt](../../core/src/main/kotlin/splice/core/topology/Topology.kt):211–340,389–606; validation also in `CustomProviderChecks.kt`, `UpstreamConfig.kt`, and `gateway/GatewayMoveChecks.kt` in the same directory. Example providers: `splice.example.toml:116–581,736–778,806–832`.

| Key | TOML type | Default | Meaning |
|---|---|---|---|
| `dialect` | string enum | required | `openai-responses`, `openai-chat`, or `anthropic-passthrough`. |
| `base_url` | string | `""` (first pool URL if blank) | Upstream base URL; set a base or list upstreams. |
| `auth` | table | required | Credential scheme, below. |
| `quirks` | table | default profile | Optional wire overrides, below. |
| `extra_headers` | map of string | empty | Static headers; forwarded client values win where applicable. |
| `forward_headers` | string array | empty | Exact header names to forward case-insensitively; credential and connection headers are refused. |
| `send_version` | bool | false | Add a Via header with the splice version. |
| `models` | array of tables | empty | Authored model rows; catalog can also include discovered models. |
| `extra_windows` | array of tables | empty | Extra per-ID context sizing, without requiring picker rows. |
| `window_rules` | array of tables | empty | Windows by model ID prefix. |
| `default_context_window` | integer, tokens | 0 (auto) | Fallback sizing; absent a positive explicit default/override, catalog falls back to first selected row, otherwise 200,000. |
| `local` | bool | auto | Auto marks loopback OpenAI Chat runtimes as local; explicit value overrides. |
| `models_url` | string | dialect's roster URL | Override where splice fetches the model roster. |
| `discovery` | table | no filters | Catalog selection from discovered models, below. |
| `upstreams` | array of tables | empty | Health-checked pool; choose a healthy upstream with least in flight. Required string keys `name` (unique stable name) and `url` (base URL); no field defaults. |
| `health_seconds` | integer | 5 | Health probe interval, minimum 1. |
| `hourly_usd` | number | unset | Hourly rental cost metered while host is up; otherwise token pricing only. |
| `picker` | bool | unset | Show/hide provider models in the gateway picker, subject to the door's default rules and a legacy head override. |
| `ca_file` | string | unset | Additional PEM trust bundle; relative to config dir; `~/` expands. |
| `error_messages` | string | `filtered` | `whole` surfaces the provider error wording; `filtered` uses splice's filtering. |
| `served_window` | table | unset | Probe for the provider's served context ceiling: string `url` and dotted JSON field path `field`, both schema `""` but required/validated if configured (`CustomProviderChecks.kt:18–24`). |
| `pinned_model` | string | unset (first catalog row for gateway if unspecified) | Starting row for the provider on the gateway. |
| `model_slots` | map of string | empty | Claude tier to model ID: `opus`, `sonnet`, `haiku`, `fable`; multiple tiers may use one model. |
| `context_window` | integer, tokens | unset | Provider-wide window override for the gateway and heads that inherit it. |
| `system_prompt`, `system_prompt_file`, `system_prompt_mode` | string, string, enum | unset | Provider's standing system prompt for the gateway and inheriting heads; see standing prompts. |
| `overrides` | map of string | empty | Provider's serving knobs; taken by gateway and inherited by its heads. See knob table. |

`[providers.<id>.auth]` (can be inline), `TopologySchema.kt:66–95` and `AuthKind.kt:23–109,150–177` in the topology directory:

| Key | TOML type | Default | Meaning |
|---|---|---|---|
| `kind` | string | required | `api-key`, `command`, `client` (forward client's own auth), `chatgpt-oauth`, `grok-oauth`, `kimi-oauth`, `muse-oauth`. |
| `file` | string | OAuth defaults below; otherwise unset | Override credential file. |
| `env` | string | inferred for API keys | API key env var name; when absent `<KEY-UPPERCASED>_API_KEY`, with key as passed by the caller of `effectiveApiKeyEnv`. |
| `credential_command` | string | unset; required for `command` | Shell command returning one token line or JSON with `token` and optional RFC 3339 `expires_at`. Only with `command`. |
| `login_command` | string | unset | Interactive sign-in command; only with `command`. |

Default OAuth files: `~/.config/splice/auth/codex.json`, `grok.json`, `kimi.json`, and `muse.json` respectively (literal splice-owned locations under the resolved home, not vendor CLI files).

`[providers.<id>.discovery]` (`ModelDiscoveryConfig.kt:18–60`): `include` (string array, empty = no inclusion filter) is a glob allowlist; `exclude` (string array, empty = no exclusions) is a glob denylist; `family` (string, unset) overrides the provider family/group shown in the console (e.g. for a remote runtime reached via a loopback tunnel); otherwise splice infers family from auth kind, provider key and local-runtime settings (`ProviderFamilyRule.kt:22–39`). Include/exclude are full-ID patterns with `*` wildcards, and exclude wins (`["*"]` disables discovery). They govern discovered rows, not manually written ones.

`[[providers.<id>.models]]` ([ModelCatalog.kt](../../core/src/main/kotlin/splice/core/model/ModelCatalog.kt):41–107):

| Key | TOML type | Default | Meaning |
|---|---|---|---|
| `id` | string | required | Model row ID/selectable spelling (optional tier suffix where applicable). |
| `label` | string | `""` | Display label; blank permits fallback. |
| `description` | string | `""` | Display description; blank permits fallback. |
| `context_window` | integer, tokens | required | Row's stated context window/compaction sizing. |
| `rates` | table | unset | Model's pricing card; see rates. |
| `client_model` | string | unset | Catalog's client-side Claude model identity for the row; wire ID remains `id`. Do not infer that current gateway-mode launches write a `modelOverrides` list: they use live discovery ([LaunchService.kt](../../features/launch/src/main/kotlin/splice/launch/recipe/LaunchService.kt):7–12). |
| `compaction_reserve_tokens` | integer | unset | Override calibrated compaction reserve; nonnegative. |
| `max_output_tokens` | integer | unset | Positive maximum for output tokens requested upstream; otherwise use requested limit. |

`[[providers.<id>.extra_windows]]`: `id` (string, required), `context_window` (integer, required; compaction target), `max_context_window` (positive integer, unset; explicit backend ceiling, else discovery then target). `[[providers.<id>.window_rules]]`: `prefix` (string, required, matched against model IDs), `context_window` (integer, required, target).

**Rate cards** ([TokenCost.kt](../../core/src/main/kotlin/splice/core/model/TokenCost.kt):58–79,167–226, `Topology.kt:512–528`; example `splice.example.toml:770–774,788–789`): Each provider model's `rates` and each value in `[heads.<head>.rates]` is the same flat card. These rates are USD per million tokens (not flat per-request). A head card wins for its model, then the provider row's authored card, discovered rate and shipped published rate in that order. No card leaves the model unpriced, with native Claude cost usable where supplied by the client. These are TOML fields, not Kotlin property names; runtime `peak` is not a field of the TOML serializer.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `input` | decimal | required | Uncached input. |
| `cache_read` | decimal | required | Cached input read. |
| `output` | decimal | required | Generated output. |
| `cache_write` | decimal | unset | Cache write rate. |
| `cache_write_1h` | decimal | unset | Hourly-tier cache write rate. |
| `long_context_over_input_tokens` | integer | unset | Threshold above which the long-context card applies; requires the next three fields if tier configured. |
| `long_context_input` | decimal | unset | Long-context uncached input. |
| `long_context_cache_read` | decimal | unset | Long-context cache read. |
| `long_context_output` | decimal | unset | Long-context generated output. |
| `long_context_cache_write` | decimal | unset | Optional long-context cache write. |
| `long_context_cache_write_1h` | decimal | unset | Optional long-context hourly-tier cache write. |
| `fast_input`, `fast_cache_read`, `fast_output`, `fast_cache_write` | decimal | unset | Fast-tier equivalents; input/read/output required together if configured; write optional. |
| `ultrafast_input`, `ultrafast_cache_read`, `ultrafast_output`, `ultrafast_cache_write` | decimal | unset | Ultrafast-tier equivalents; input/read/output required together if configured; write optional. |

### Headers for a gateway in front of a provider

Point `base_url` at your team's gateway and set the headers it expects. This example uses an Anthropic-format endpoint:

```toml
[providers.acme]
dialect = "anthropic-passthrough"
base_url = "https://llm-gateway.acme.example"
auth = { kind = "api-key", env = "ACME_GATEWAY_KEY" }
extra_headers = { "x-acme-team" = "platform" }
forward_headers = ["x-acme-user", "x-acme-cost-center"]
send_version = true
```

- **Fixed headers:** `extra_headers` applies to upstream requests, including model listing. It takes precedence over a generated header of the same name except the login credential header.
- **Headers from the client:** `forward_headers` permits the named headers to pass through unchanged. In Claude Code, set `ANTHROPIC_CUSTOM_HEADERS` to one `Name: Value` per line. A forwarded value takes precedence over the fixed value of that header. Login and connection headers such as `Authorization`, `x-api-key`, `Cookie`, `Host` and `Content-Length` are refused during validation. If a value contains a splice key or a key issued by a [team server](team-server-mode.md), splice drops that header and logs its name.
- **Gateway version:** `send_version = true` adds `Via: 1.1 splice (splice/<version>)` to upstream requests and leaves the caller's `User-Agent` unchanged. The default is off.

If your gateway requires a minimum splice version, have it check `Via` and return a short JSON error such as `{"error":{"message":"Upgrade splice before using this gateway: run splice upgrade"}}`. Splice's provider-error preview quotes up to 160 characters.

## Legacy heads and Claude sharing

`[heads.<head>]` ([HeadConfig.kt](../../core/src/main/kotlin/splice/core/topology/HeadConfig.kt):16–134, `HeadModels.kt`, `TopologySchema.kt:97–123`). Custom heads still work but are deprecated. The example names ports per head (e.g. `claudex` 3099 and `claude-grok` 3100); the schema requires a port and does not assign a universal head port.

| Key | TOML type | Default | Meaning |
|---|---|---|---|
| `provider` | string | required | Key of a provider in `[providers]`. |
| `port` | integer | required | Unique TCP listener port (loopback). |
| `discovery_prefix` | string | required | Prefix for this head's model selectors. |
| `pinned_model` | string | `""` at decode | Required for real heads except a provider forwarding the client's own login. For that kind it changes the starting model, not the roster. |
| `models` | array of tables | unset | Ordered head-local allowlist. Each row: `id` (string, required, declared/discovered model), `slot` (string, unset, legacy tier binding; do not combine with a `model_slots` map on the head). |
| `model_slots` | map of string | empty | Tier bindings without expanding/restricting the roster; same four tier names as provider, using allowed rows. Prefer provider bindings for gateway service. |
| `context_window` | integer | unset | Head client-window override, in tokens. |
| `overrides` | map of string | empty | Head-local typed knob values as strings. |
| `claude` | table | member defaults | Legacy wrapper settings, below. |
| `system_prompt`, `system_prompt_file`, `system_prompt_mode` | string, string, enum | unset | Legacy head-specific prompt; see standing prompts. |
| `rates` | map of rate cards | unset | Head-only overrides by model row ID; same card schema as above. |
| `picker` | bool | unset | Head-specific override of whether its models appear in the gateway picker. |

`[heads.<head>.claude]`: `command` (string, unset = head key) sets the wrapper command; `config_dir` (string, unset = `~/.claude-<head>`) sets the head's Claude folder; `isolate` (string array, empty) opts named shared items out for this head; `readable` (table, unset) can give a readable alternate selector with `prefix` (string, required) and `strip` (string, `""`, remove that leading text from model ID). `[claude]` `share` (string array) defaults to `["settings","mcps","skills","hooks","agents","commands","plugins","claude_md","sessions","projects"]`, the common sharing policy for wrappers. Sources also [HeadConfigDirs.kt](../../core/src/main/kotlin/splice/core/config/HeadConfigDirs.kt) and [ModelCatalog.kt](../../core/src/main/kotlin/splice/core/model/ModelCatalog.kt):436–444.

## Door, launch profiles, local harnesses and LAN sharing

- `[door]`: `commands` and `selector_aliases` are maps, both empty by default. `[door.commands.<name>]` takes `provider` (string, `""`, provider key), `head` (string, `""`, legacy alternative; the provider of that head), and `models` (string, unset = `head` for provider-only picker; `door` for whole picker). A provider must resolve, must not use `client` auth, and the command must not conflict with a head command or plain `claude`. `[door.selector_aliases]` maps old selector prefixes ending in `--` to provider keys; cannot take another provider's canonical prefix. Source: [DoorConfig.kt](../../core/src/main/kotlin/splice/core/topology/DoorConfig.kt):12–123.
- `[launch]` `profiles` is an empty map by default. `[launch.profiles.<name>]` has:
  - `dir` (string, unset = `<splice root>/launch-profiles/<name>`): isolated Claude folder rebuilt on launch; must be absolute after home expansion and cannot contain or be in the vanilla Claude or Gemini folders.
  - `claude_json` (string, unset): read-only `.claude.json` seed for the profile. Use an absolute path or `~/…` outside Splice profile folders, isolated head folders, and `~/.gemini`. When unset, Splice uses `$CLAUDE_CONFIG_DIR/.claude.json` from the shell launching Claude if that path is eligible, or `~/.claude.json` otherwise. A missing file seeds nothing; an unreadable or invalid file, or a Claude credentials file, stops the launch.
  - `keep_hooks` (string, `own`): settings hooks only; `all` also permits kept plugins' hooks; `none` keeps neither.
  - `drop_hooks` (string array, empty): drop by hook event or substring in a hook command.
  - `keep_plugins` (string array, empty): plugins to load by name or `name@marketplace`.
  - `drop_blocks` (string array, empty): remove named blocks from the copied `CLAUDE.md`. Supports comment, `# BEGIN` / `# END`, and XML markers; see [filtered launch profiles](door-and-launchers.md#filtered-launch-profiles) for syntax.
  - `drop_names` (string array, empty): omit skills, agents and commands with matching name prefixes.
  - Sources: [LaunchProfileConfig.kt](../../core/src/main/kotlin/splice/core/topology/LaunchProfileConfig.kt):18–145; [StatePaths.kt](../../core/src/main/kotlin/splice/core/config/StatePaths.kt):303–304.
- `[harnesses.<id>]`: `name` (string, unset but required nonblank), `description` (string, `""`), `protocol` (string, unset but only `acp` allowed for local entries), `argv` (string array, empty at decode; required absolute executable followed by arguments, no shell). ID starts with a lowercase letter, then lowercase letters, digits or hyphens; cannot replace a shipped ID. Source: [HarnessCatalog.kt](../../core/src/main/kotlin/splice/core/harness/HarnessCatalog.kt):10–70.
- `[share]`: `enabled` (bool, false) enables LAN sharing; `port` (integer, 3097) is its listener port; `hostnames` (string array, empty) adds names to the built-in local-hostname allowlist. `[remote.<command>]`: `url` (string, required) is a remote splice command/door URL; optional query `auth` and `models` identify credential and comma-separated model IDs; `window` (integer, unset) optionally pins the remote client window. Source: [LanShareConfig.kt](../../core/src/main/kotlin/splice/core/topology/LanShareConfig.kt):19–121,167–201; example `splice.example.toml:77–89`.

## Provider wire quirks

`[providers.<id>.quirks]` keys are boot-time, provider/dialect-specific. Unless stated otherwise the schema default is **unset**, retaining the provider's profile rather than assigning the example's value. Sources: [QuirksConfig.kt](../../core/src/main/kotlin/splice/core/topology/QuirksConfig.kt):23–199, `SummaryDelivery.kt:10–20`, `ChatEffortVocabularyConfig.kt:9–66`; application in [QuirksOverlay.kt](../../app/src/main/kotlin/splice/app/provider/QuirksOverlay.kt):31–186, `CodexResponsesArm.kt:78`, `CodexCodeModeAssembly.kt:68–122`, and [UpstreamFactory.kt](../../app/src/main/kotlin/splice/app/head/UpstreamFactory.kt):38; examples `splice.example.toml:141–249,370–381,470–510,565–566,751–762`.

| Key | TOML type | Meaning or effective default |
|---|---|---|
| `store` | bool | Responses `store` request value (generic false). |
| `account_id_header` | bool | Send ChatGPT account ID header (true for ChatGPT sign-in by default). |
| `cache_key` | string | Responses cache key strategy: `first-message-hash`, `session-id`, or `off`; other values retain profile ([ResponsesQuirks.kt](../../integrations/dialects/openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesQuirks.kt):206–224). |
| `effort_ceiling` | string | Compatibility-only; accepted but ignored. Provider/model ceiling decides. |
| `summary_field` | bool | Enable the Responses reasoning summary field. |
| `compact_effort` | string | Retired; setting it fails config validation. Compaction inherits session effort. |
| `tool_choice` | bool | Forward Responses tool choice. |
| `reasoning_cache` | bool | Responses reasoning envelope cache across tool rounds (on in Codex profile; off in Grok). |
| `parallel_tool_calls` | bool | Responses-lite `parallel_tool_calls` value (default false). |
| `websocket` | bool | Opt into Responses WebSockets; ships off. |
| `websocket_prefill` | bool | When using Code Mode WebSockets, prefill the first prefix before generation. Does not enable WebSockets. |
| `summary_delivery` | string enum | `sequential_cutoff` or `off` (omit field); unset keeps provider default. |
| `code_mode` | bool | Enable the splice Code Mode bridge on supported ChatGPT Responses shape; default on there. |
| `code_mode_workers` | integer | Worker budget for native Code Mode; default 6 (native Code Mode defaults in [JvmCodeModeRuntime.kt](../../integrations/codemode/src/main/kotlin/splice/codemode/JvmCodeModeRuntime.kt):37–50). |
| `code_mode_timeout_ms` | integer | Retired; setting it is refused. Native cells use yield/observation windows. |
| `code_mode_heap` | integer | JS heap ceiling per cell, MiB; default 512. |
| `code_mode_memory_mb` | integer | Positive head-wide admission reservation budget, MiB; default 24,576. |
| `code_mode_models` | string array | Also select native Code Mode for these upstream IDs, ignoring context suffix when matching; none added by default. |
| `zstd_request_body` | bool | Zstd-compress upstream request bodies; unset leaves it off. |
| `reasoning_effort` | bool | Enable reasoning fields for Chat Completions; generic default true. |
| `chat_effort_vocabulary` | table | Explicit Chat effort mapping; unset keeps existing policy. |
| `stream_usage` | bool | Ask Chat Completions streams for usage. Unset turns it on for local, command-auth or Grok OAuth Chat profiles; generic hosted Chat off. |
| `slot_affinity` | bool | Pin llama-server conversation to an upstream slot with `id_slot`; unset off. |
| `tool_surface` | table | Responses-lite deferred tool surface; unset retains provider's base policy. |
| `mfjs` | bool | Rewrite tool schemas to the Moonshot schema subset. |
| `block_allowlist` | string array | Drop content blocks with types outside this list; neutral profile forwards all. |
| `strip_cache_control` | bool | Remove cache control markers from Messages payload. |
| `synthesize_signatures` | bool | Generate missing thinking-block signature for providers that need it. |
| `map_thinking_adaptive` | bool | Remap thinking to Kimi's adaptive configuration/effort. |
| `strip_sampling_params` | bool | Drop temperature/top-p/top-k. |
| `reanchor_prefill` | bool | Allow eligible stream recovery using a trailing saved assistant prefill. Defaults off unless provider profile sets it. |
| `tool_name_cap` | integer | Shorten wire tool names to this limit; Muse Responses default 64; no cap in neutral profile. |
| `forward_client_identity` | bool | false at decode; retired LAN relay field; not acted on. |

`[providers.<id>.quirks.chat_effort_vocabulary]`: `default` (required string; upstream value for omitted client effort), `levels` (required map of string; client effort name to upstream effort), `model_pattern` (string, unset; regex limiting when vocabulary applies). `[providers.<id>.quirks.tool_surface]`: `enabled` (bool, true), `defer_prefixes` (string array, `["mcp__"]`), `defer` (string array, empty; exact tool names also deferred), `eager` (string array, empty; keep named tools eager), `min_deferred` (integer, 8; minimum deferred count before enabling search), `search_limit` (integer, 8; max returned by a search), `search_rounds` (integer, 3; max search rounds). The daemon-wide `toolSurface = "off"` disables it regardless of this table.

## Standing system prompts

Standing system text is **not a knob**. Every prompt table has `system_prompt` (string, unset, inline text), `system_prompt_file` (string, unset, a file instead of inline text), and `system_prompt_mode` (enum string, unset = `append`). Head/provider relative files are resolved beside `splice.toml`; project and project-head relative files under their project root. Leading `~/` expands. Both text and file or an unreadable file are boot findings. Empty inline text disables the layer, including a dialect's shipped standing prompt; on a provider/head with no explicit setting the Responses adapter can add its bundled prompt. A legacy head's own text/file overrides its provider's prompt as a unit; for gateway service use the provider table (an unambiguous legacy head value may be inherited by the gateway until migrated).

The three layers, in application order:

1. Serving provider/legacy head's own standing prompt.
2. `[projects."<root>"]`: project-wide prompt for sessions under that root. Specify an absolute quoted path (or `~/...`); the deepest configured ancestor of the session cwd wins. If cwd is unavailable there is no project layer.
3. `[projects."<root>".heads.<head>]`: same keys for a specific serving head in that project. The `heads` map defaults empty.

`append`: add trailing text without replacing the client's system blocks or their cache markers. `replace`: discard the client system field and all earlier layers; later appends may still follow. `strip`: treat text as one regex per line (ignore blanks and comments), remove matching blank-line-separated paragraphs at that point in the stack, keep other system content/cache markers. A strip list with invalid or no patterns is refused. Standing prompts/files are resolved as configuration, not live-read per turn. Sources: `core/src/main/kotlin/splice/core/prompt/{HeadSystemPrompt,SystemPromptLayers}.kt`, [SessionProject.kt](../../core/src/main/kotlin/splice/core/compaction/SessionProject.kt), `core/src/main/kotlin/splice/core/topology/{HeadConfig,TopologySchema}.kt`, `core/src/main/kotlin/splice/core/topology/gateway/{GatewayProviderRoute,HeadFallbacks}.kt`, [HeadServerFactory.kt](../../app/src/main/kotlin/splice/app/head/HeadServerFactory.kt):173–185, [TurnPrompts.kt](../../features/turns/src/main/kotlin/splice/head/turn/TurnPrompts.kt):57–100; example `splice.example.toml:601–660`.

Standing prompts reach the main thread and its subagents. Replacing the system field replaces a subagent's own instructions in that field; stripping removes only the matched paragraphs. Claude Code's auxiliary requests, such as titles, tool-call labels and probes, and its auto-mode review bypass both the standing prompt layers and team-slot instructions.

Append preserves existing `cache_control` breakpoints so they can be reused on later turns. Splice ships no strip pattern list. `splice doctor` identifies replace and strip settings and warns when strip has no pattern list; a configured strip layer needs a valid, nonempty list.

## Compaction instructions

Compaction uses a separate table ([CompactionScope.kt](../../core/src/main/kotlin/splice/core/compaction/CompactionScope.kt):7–28, `CompactionInstructions.kt:67–128,183–252`; example `splice.example.toml:90–103`):

| TOML table | Key | Type / default | Meaning |
|---|---|---|---|
| `[compaction]` | `instructions` / `file` | string / unset each | Global compaction instructions, inline or file (not both). |
| `[compaction]` | `model` / `project` | array of tables / empty each | More specific model and project rules. |
| `[[compaction.model]]` | `model` | required string | Nonblank upstream model ID; unique per model. |
| `[[compaction.model]]` | `instructions` / `file` | string / unset each | Model-specific compaction instructions. |
| `[[compaction.project]]` | `path` | required string | Project root, with longest matching path within the tier winning. |
| `[[compaction.project]]` | `model` | string / unset | Restrict this project rule to a particular upstream model; unique by path and model together. |
| `[[compaction.project]]` | `instructions` / `file` | string / unset each | Project-specific or project-model-specific instructions. |

Most to least specific: project+model, project, model, global, then client's own instructions. Explicit empty text opts out; no instruction source leaves client bytes alone. Relative paths resolve against the config directory; home paths expand. Tables and referenced compaction file content are live.

The chosen text goes after Claude Code's summarizer prompt, on compaction requests only. Earlier bytes stay in place for cache reuse.

| Dialect | Where the instructions go |
|---|---|
| `anthropic-passthrough` | A text block at the end of the last user message. A plain-string message becomes a block array. |
| `openai-responses` | A user item after the input. |
| `openai-chat` | Appended to the trailing user message. |

An invalid rule or a file splice cannot read at boot is a config finding. If a configured file becomes unreadable later, splice logs the problem and disables that rule's custom text. `GET /api/compact` shows instruction provenance without the text. `GET /api/compaction/instructions?head=<key>` lists scope, source and length for the head's rules, not their text.

```toml
[compaction]
instructions = "Keep exact file paths, commands, and error messages."

[[compaction.model]]
model = "gpt-6-sol"
instructions = "Keep file paths and the remaining work."

[[compaction.project]]
path = "~/work/api"
instructions = "Keep failing test names and their error messages."
```

## Modes and capabilities

`[modes.<name>]` takes `on` (bool, unset) and `heads` (map of bool by declared head, empty). The manifest's eligibility restrictions are absolute; within them, head-specific `heads.<head>` takes precedence over `on`, then the manifest's own default. Only names present in the build catalog are valid. Source: [ModeSetting.kt](../../core/src/main/kotlin/splice/core/mode/ModeSetting.kt):14–97, `modes/api/src/main/kotlin/splice/modes/api/{ModeRule,FoundMode}.kt`, and per-mode `modes/*/mode.toml` manifests. The source manifests set `ccdsession` on by default and `tableapp`, `template`, `templateapp` off.

`[capabilities.<name>]`: splice-run work around a model turn. Names use lowercase letters, digits and hyphens. Sources: `core/src/main/kotlin/splice/core/capability/{Capability,CapabilityDeclarations,CapabilityRule,CapabilityCalling,CapabilityWorkerCheck,CapabilityCallCheck,CapabilityColors}.kt`.

| Key | TOML type | Default | Meaning |
|---|---|---|---|
| `heads` | string array | empty (off everywhere) | Which declared heads run this capability. |
| `call` | string | `rule` | Run automatically by `rule`, offer to the model as `agent`, or `both`. Model-callable capabilities require Messages dialect and a `tool` table. |
| `tool` | table | unset | For `agent`/`both` only: required `name` (string, 1–64 letters/digits/underscore/hyphen), `description` (nonblank string), `input` (table encoding JSON Schema). |
| `when` | table | member defaults | Selection rule/offer filter, below. |
| `place` | string | `first-user-message` | Where automatic result text goes; `none` only with refusal-only work. Do not name with agent-only calls. |
| `may` | string | `add` | `add`, `refuse`, or `add-or-refuse`. |
| `message` | string | unset | While working, show built-in worker's own message if any, otherwise capability name. |
| `color` | string | `cyan` | Status text color; `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`, `gray`. |
| `timeout_s` | integer | 60; 180 for built-in `context-getter` | Worker timeout, 1–3600 seconds. |
| `on_failure` | string | `open` | `open` allows the turn after a worker failure; `closed` does not. |
| `send` | string array | empty | Fields passed to worker: `first_user_message`, `folder`, `model`, `head`. |
| `worker` | table | required | Worker definition, below. |

`[capabilities.<name>.when]`: `turn` (`first` default, or `any`; do not set for agent-only calls; automatic `any` cannot place text on the first message), `thread` (`main` default, `subagent` or `any`), `mode` (`interactive` default, `print` or `any`), `models` (nonempty string array of model IDs if present, unset = no model restriction). `[capabilities.<name>.worker]` requires string `kind`: `http` requires string `url` and takes optional string `auth_env` (bearer env var); `mcp` requires strings `server` and `tool`; `builtin` requires string `name` (shipped `context-getter`) and takes optional `model` (string; otherwise worker default).

## Server mode

`[server]` is off with no `listen`. There is no implicit server port. All subobjects have member defaults except `signin`, `telemetry` and `forward_auth` (unset = disabled). Sources: [ServerConfig.kt](../../core/src/main/kotlin/splice/core/topology/ServerConfig.kt):17–229, `ServerLedgerConfig.kt:14–107`, `ServerKeyFormat.kt`, `ServerChecks.kt`, `ServerIdentityChecks.kt`, `ServerLedgerChecks.kt`, `metering/ServerMeteringChecks.kt`.

| Key | TOML type | Default | Meaning |
|---|---|---|---|
| `listen` | string | `""` | Listener as `host:port`. |
| `commands` | string array | empty | Commands exposed to server-key holders, by head's command or provider key. |
| `tls_cert`, `tls_key` | string | unset | PEM certificate chain and unencrypted PKCS#8 PEM private key, both or neither; relative to config dir. |
| `trusted_proxies` | string array | empty | Addresses/CIDRs whose forwarded client address is trusted. |
| `admins` | string array | empty | Admin email identities. |
| `admin_cidrs` | string array | empty (unrestricted) | Address/CIDR restriction on admin privilege. |
| `keepalive_seconds` | integer | 0 | Server streamed-turn SSE keepalive interval; 0 sends none. |
| `drain_seconds` | integer | 0 | At stop, refuse keyed requests with 503, answer `/health` `draining`, and give turns in flight up to this many seconds (45 at most). |
| `stream_idle_seconds` | integer | 0 | End a streamed answer silent this long after its last word with an error event; 0 never times it. |
| `aliases` | map of strings | empty | `[server.aliases]`: a one-segment path (`"/v1"`) targeting a served head by key or command, or `"door"`. Use `"door"` for a provider served without a head; its provider key is not a valid alias target. |
| `key_prefix` | string | `spk_` | Prefix for newly minted keys only. |
| `metrics_prefix` | string | `splice_server_` | Literal prefix of all server Prometheus series. |
| `limits`, `reference`, `teams`, `export`, `budgets`, `client`, `signin`, `telemetry`, `forward_auth`, `backup` | table | as below | Server controls. |
| `cost_meters` | map of tables | empty | Named external hourly cost checks. |

| TOML table | Key | Type / default | Meaning |
|---|---|---|---|
| `[server.limits]` | `inflight_per_person` | integer / 0 | Concurrent turns per person. |
| `[server.limits]` | `rpm_per_person` | integer / 0 | New turns per minute per person. |
| `[server.limits]` | `tpm_per_person` | integer / 0 | Last minute's completed input + output tokens after which the next turn waits. |
| `[server.limits]` | `inflight_total` | integer / 0 | Whole server's turns in flight at once. |
| `[server.limits]` | `inflight_per_upstream` | integer / 0 | Turns in flight per server of a pooled provider. |
| `[server.limits]` | `busy_status`, `busy_retry_after`, `busy_message` | 429 or 503 / 429; seconds / 5; string / unset | The answer to a turn past either cap. |
| `[server.reference]` | `input_usd_per_mtok`, `cached_usd_per_mtok`, `output_usd_per_mtok` | numbers / unset each | Reference price per million tokens, for comparison in reports. |
| `[server.teams]` | `by_domain` | map of strings / empty | Quoted email domain to team assignment, used if no explicit team. |
| `[server.budgets]` | `person_daily_usd` | number / unset | Default daily USD budget per person, unless individually set. |
| `[server.budgets]` | `action` | string / `block` | Refuse next turn on budget or `warn` and serve. |
| `[server.client]` | `minimum` | string / unset | Oldest permitted client splice version; absent accepts any. |
| `[server.client]` | `version_header` | string / `x-splice-client` | Header the client version is read from. |
| `[server.client]` | `outdated_status`, `outdated_message` | 200, 400 or 426 / 200; string / unset | How an outdated client is answered. |
| `[server.client]` | `checkin` | boolean / false | Accept `POST /server/client/checkin` version reports. |
| `[server.client]` | `download_url` | string / unset | HTTPS release base URL advertised to install/update clients; all redirects must stay on HTTPS. See [Client distribution](team-server-mode.md#client-distribution). |
| `[server.signin]` | `issuer` | string / `""` | OIDC issuer URL (must be configured to enable sign-in). |
| `[server.signin]` | `client_id` | string / `""` | OIDC app ID. |
| `[server.signin]` | `client_secret_env` | string / unset | Variable holding OIDC secret, not the secret itself. |
| `[server.signin]` | `domains` | string array / empty | Email domains admitted for sign-in. |
| `[server.signin]` | `key_days` | integer / 30 | Lifetime of a sign-in-issued key. |
| `[server.signin]` | `rate_per_minute` | integer / 20 | Sign-in attempts per client address per minute; 0 disables cap. |
| `[server.signin]` | `hosted_domain` | string / unset | Require this exact hosted-domain claim (`hd`). |
| `[server.forward_auth]` | `allow` | string array / empty | Emails or `@domain` allowed in addition to admins. |
| `[server.forward_auth]` | `browser_origin` | string / unset | HTTPS browser app/sign-in origin; unset retains key-only checks. Requires sign-in configuration. |
| `[server.forward_auth]` | `session_seconds` | integer / 3600 | Browser session lifetime. |
| `[server.forward_auth]` | `email_header` | string / `X-Auth-Request-Email` | Identity response header. |
| `[server.forward_auth]` | `role_header` | string / unset | Optional role response header. |
| `[server.forward_auth]` | `client_id` | string / unset | Optional separate browser OAuth app ID at the same issuer. |
| `[server.forward_auth]` | `client_secret_env` | string / unset | Variable holding the separate app secret; requires separate client ID. |
| `[server.telemetry]` | `forward_to` | string / `""` | Collector's OTLP/HTTP base to which client telemetry is forwarded. |
| `[server.telemetry]` | `identity_attributes` | table / member defaults | Trusted identity attribute keys described below. |
| `[server.telemetry.identity_attributes]` | `person`, `email`, `team` | strings / `splice.person`, `user.email`, `splice.team` | Resource attribute names for server-authenticated person, email, and team. |
| `[server.cost_meters.<name>]` | `hourly_usd` | number / unset; required by validation | Finite nonnegative hourly cost. |
| `[server.cost_meters.<name>]` | `poll_seconds` | integer / 60 | Positive check interval. |
| `[server.cost_meters.<name>]` | `file`, `url`, `argv` | string, string, string array / unset each | Exactly one meter source: file, HTTP(S) response or program argv. |

`[server.export]` and `[server.backup]` each take the same fields: `to` (string, `""` = disabled; `file:///dir` or `s3://bucket/prefix`), `endpoint` (string, unset; default S3 endpoint for region), `region` (string, unset; effective S3 default `us-east-1`), `path_style` (bool, false), `access_key_env` and `secret_key_env` (strings, unset; names of S3 credential variables), and `every` (string, `1h`; positive whole number plus `s/m/h/d/w`). Export copies the usage ledger; backup snapshots/restores durable server state. A configured backup cannot be skipped on a fresh disk if boot cannot read the destination. Effective S3 defaults: `features/server/src/main/kotlin/splice/server/{export/ExportTargets,backup/SnapshotTargets}.kt`.

Zero means uncapped for `[server.limits]`. These are server-mode limits, not the personal local-console budget store or serving knobs. A cost meter charges only while its source indicates active: a file (relative to config dir if not absolute) contains trimmed `true`, an HTTP URL answers 2xx, or a program with absolute executable path exits 0. The command has no inherited environment and runs without a shell. A meter name is letters/digits/underscore/hyphen, starting with a letter or digit, and cannot duplicate a provider key. See [MeterSignals.kt](../../features/server/src/main/kotlin/splice/server/ledger/MeterSignals.kt):18–72 and [ServerMeteringChecks.kt](../../core/src/main/kotlin/splice/core/topology/metering/ServerMeteringChecks.kt):33–80.

## Operator telemetry and local budget data

`[telemetry]` contains only `operator` (object, default member values). All five `[telemetry.operator]` fields are nullable in the schema; effective defaults derive from the corresponding restart-required knobs below. This is **splice's** own export, separate from relaying client telemetry under `[server.telemetry]).

| Key | TOML type | Effective default | Meaning |
|---|---|---|---|
| `prometheus_enabled` | bool | false | Expose local Prometheus scrape. |
| `otlp_endpoint` | string | `""` | OTLP HTTP(S) URL without credentials, query or fragment; gRPC cannot contain a signal path. |
| `otlp_protocol` | string | `http/protobuf` | `http/protobuf` or `grpc`. |
| `otlp_headers` | string | `{}` | String containing bounded JSON object of string headers (max 16, max 4096 characters per value); not a TOML subtable. |
| `otlp_signals` | string | `""` | Comma-separated subset of `metrics,traces,logs`; blank disables signal export. |

Sources: `core/src/main/kotlin/splice/core/topology/telemetry/{OperatorTelemetryConfig,OperatorTelemetryRules}.kt`, [PerfExportConfig.kt](../../core/src/main/kotlin/splice/core/perf/export/PerfExportConfig.kt) and [Knob.kt](../../core/src/main/kotlin/splice/core/config/Knob.kt):605–634.

Local-console budget rows are not loaded from `splice.toml`: `<state>/budgets.json` (operator-facing `GET|PUT /api/budgets`). The local default action is `budgetDefaultAction` below, not `server.budgets.action`. Source: `features/usage/src/main/kotlin/splice/usage/budgets/{BudgetStore,BudgetRoutes}.kt`. The daemon's published rates refresh is separate from authored rate cards.

## Serving knobs in defaults and override tables

Base syntax: camelCase keys with **quoted strings** in `[defaults]`, `[providers.<id>.overrides]` and `[heads.<head>.overrides]`, e.g. `maxInflight = "12"`. The type shown is the effective coerced type: int means a whole-number setting, bool accepts case-insensitive `1/true/yes/on` or `0/false/no/off`. Dash in the Env column means no alias. Listed env aliases are in precedence order (first nonempty one wins). **Live** means runtime application through config/state/API where permitted; not live hand-editing of the TOML knob maps. Source: `core/src/main/kotlin/splice/core/config/{Knob,KnobDefault,KnobKind,KnobWords,ConfigCoercion,SpliceConfig}.kt`, and `splice.example.toml:850–1052`. The source also recognizes `[defaults] perf_collection_enabled` as a compatibility spelling of `perfCollectionEnabled` (wins over camelCase in that table).

Scope codes:
- **B**: shared default, can be set per provider/head; serving settings belong on provider for gateway use (legacy head values warn where they should be moved).
- **G**: only global, not provider/head-specific.
- **S**: global or provider (serving bound); a legacy head inherits it but may not set it in its own table.
- **H**: head-only, no global layer, no env.
- **L**: head-local display/trace knob when overridden; not one of the serving knobs moved to providers by the head PATCH.
- The six legacy Codex/Grok port/base/pin knobs in the first/last legacy rows govern only when that auth kind has exactly one declared legacy head. For other deployments use actual provider/head topology.

| Key | Type | Built-in default | Apply | Scope | Env | Meaning |
|---|---|---|---|---|---|---|
| `port` | int | 3099 | restart | B | `CODEX_PROXY_PORT` | Legacy ChatGPT sign-in head port. |
| `chatgptApiBase` | text | `https://chatgpt.com/backend-api/codex` | restart | B | `CHATGPT_API_BASE` | Legacy ChatGPT sign-in base URL. |
| `pinnedModel` | text | `gpt-6-sol` | restart | B | `CLAUDEX_PINNED_MODEL`, `CLAUDEX_MODEL` | Legacy pinned ChatGPT head model. |
| `effort` | text | unset | restart | B | `CLAUDEX_REASONING_EFFORT`, `CODEX_REASONING_EFFORT` | Default effort: `none/minimal/low/medium/high/xhigh/max`; per-turn and vendor rules still decide. |
| `verbosity` | text | unset | restart | B | — | Responses length: `low/medium/high`; unset retains profile (e.g. low for ChatGPT lite, omitted elsewhere). |
| `summary` | text | `detailed` | live | B | `CLAUDEX_REASONING_SUMMARY`, `CODEX_REASONING_SUMMARY` | Reasoning summary request: `detailed/concise/auto/none`. |
| `showReasoning` | text | `text` | live | B | `CLAUDEX_SHOW_REASONING`, `CODEX_SHOW_REASONING` | Display reasoning per `text/thinking/off`. |
| `replayReasoning` | bool | true | live | B | `CLAUDEX_REPLAY_REASONING`, `CODEX_REPLAY_REASONING` | Include earlier reasoning in input where supported. |
| `codeModeDefaultExecYieldTimeMs` | int | 30000 | restart | H | — | Default foreground Code Mode exec observation; pragma can override. |
| `codeModeToolDescriptionFirst` | bool | false | restart | H | — | Put tool instructions before namespace guidance. |
| `codeModeShowCellOverhead` | bool | false | restart | H | — | Include handler, host and overhead timings in native tool output. |
| `codeModeToolInputSchemaMaxBytes` | int | unset | restart | H | — | Optional larger declaration byte budget; bundled minimum still holds. |
| `codeModeUnifiedImageBudget` | bool | false | restart | H | — | Hide legacy detail guidance for a lite/original-capable selected model. |
| `mirrorReasoning` | bool | false | restart | B | `CLAUDEX_MIRROR_REASONING` | Locked off by normalization, irrespective of specified value. |
| `progressLine` | bool | true | live | B | `CLAUDEX_PROGRESS_LINE`, `SPLICE_PROGRESS_LINE` | Show elapsed progress when a turn is silent; separate from keepalives. |
| `toolSurface` | text | `auto` | restart | B | `CLAUDEX_TOOL_SURFACE` | `off` disables deferred search; `auto` honors provider policy. |
| `quotaPoll` | text | `auto` | restart | B | `CLAUDEX_QUOTA_POLL` | `auto` polls eligible subscription usage, `off` disables polls. |
| `quotaPollIntervalMs` | int | 300000 | live | B | `SPLICE_QUOTA_POLL_INTERVAL_MS` | Subscription poll interval, milliseconds (floor 30000). |
| `maxInflight` | int | 12 | live | B | `CLAUDEX_MAX_INFLIGHT` | Concurrent turns per serving lane; 0 unlimited. |
| `maxQueued` | int | 512 | live | B | `CLAUDEX_MAX_QUEUED` | Admission queue limit; 0 unlimited. |
| `transcriptView` | bool | true | live | G | — | Allow console to read already-written redacted Claude Code transcript. |
| `keepTranscriptCopies` | bool | true | live | G | — | Keep a copy before moving the conversation. |
| `transcriptCopyDays` | int | 30 | live | G | — | Days to retain transcript copies (at least 1). |
| `keepCompactionSummaries` | bool | true | live | B | — | Persist final compaction answers. |
| `keepCodeModeWork` | bool | true | live | B | — | Persist Code Mode journal rather than memory only. |
| `upstreamTimeoutMs` | int | 1800000 | restart | S | `CLAUDEX_UPSTREAM_TIMEOUT_MS` | Upstream progress timeout ceiling, milliseconds. |
| `firstByteTimeoutMs` | int | 90000 | live | S | `CLAUDEX_FIRST_BYTE_TIMEOUT_MS` | Time before watchdog's first-output probe, not a hard socket timeout. |
| `wsIdleCloseMs` | int | 600000 | restart | B | `SPLICE_WS_IDLE_CLOSE_MS` | Responses WebSocket idle-close duration. |
| `wsMaxAgeMs` | int | 2100000 | restart | B | `SPLICE_WS_MAX_AGE_MS` | Retire an idle Responses WebSocket after this age. |
| `streamIdleMs` | int | 90000 | live | S | `CLAUDEX_STREAM_IDLE_MS` | Silent-stream watchdog probe threshold. |
| `stallReanchorMs` | int | 20000 | live | S | `CLAUDEX_STALL_REANCHOR_MS` | Silence after visible output before eligible prefill recovery. |
| `authCacheMs` | int | 60000 | restart | B | `CLAUDEX_AUTH_CACHE_MS` | OAuth snapshot reuse lifetime. |
| `debug` | bool | false | restart | G | `CLAUDEX_DEBUG`, `CODEX_PROXY_DEBUG` | Verbose logging. |
| `contextWindowOverride` | int | unset | restart | B | `CODEX_MODEL_CONTEXT_WINDOW` | Positive client window override in tokens. |
| `grokPort` | int | 3100 | restart | B | `GROK_PROXY_PORT` | Legacy Grok sign-in head port. |
| `grokModel` | text | `grok-4.7` | restart | B | `CLAUDE_GROK_MODEL`, `CLAUDE_GROK_PINNED_MODEL` | Legacy pinned Grok head model. |
| `xaiApiBase` | text | `https://api.x.ai/v1` | restart | B | `XAI_API_BASE` | Legacy Grok sign-in base URL. |
| `controlPort` | int | 3096 | restart | G | `SPLICE_CONTROL_PORT`, `CONTROL_PROXY_PORT` | Loopback control listener port. |
| `usageWarnPct` | int | 80 | live | B | `SPLICE_USAGE_WARN_PCT` | Plan usage percent soft warning. |
| `usageWarnTokens5h` | int | 0 | live | B | `SPLICE_USAGE_WARN_TOKENS_5H` | Absolute five-hour token soft warning; 0 disables. |
| `mcpIdleTimeoutMs` | int | 1800000 | live | G | `SPLICE_MCP_IDLE_TIMEOUT_MS` | Idle time before closing a hosted MCP process without an open stream. |
| `mcpMaxServers` | int | 32 | live | G | `SPLICE_MCP_MAX_SERVERS` | Max hosted MCP processes. |
| `mcpRequestTimeoutMs` | int | 1800000 | live | G | `SPLICE_MCP_REQUEST_TIMEOUT_MS` | Hosted MCP request timeout. |
| `mcpInitializeTimeoutMs` | int | 60000 | live | G | `SPLICE_MCP_INITIALIZE_TIMEOUT_MS` | Hosted MCP initialization timeout. |
| `maxRequestBytes` | int | 33554432 | live | B | `SPLICE_MAX_REQUEST_BYTES` | Maximum request-body bytes the gateway will decode. |
| `requestReadTimeoutMs` | int | 30000 | live | B | `SPLICE_REQUEST_READ_TIMEOUT_MS` | Request-body reading time limit. |
| `ingressReadIdleMs` | int | 30000 | live | G | `SPLICE_INGRESS_READ_IDLE_MS` | Control listener's body-read inactivity limit; each received chunk resets it. |
| `materializationHeapBytes` | int | 0 | live | G | `SPLICE_MATERIALIZATION_HEAP_BYTES` | Shared request decode/translate heap budget; 0 derives from max heap less resident reserve. |
| `statuslineGitRoots` | text | `""` | live | L | `CLAUDEX_STATUSLINE_GIT_ROOTS` | Additional colon-separated absolute Git roots for statusline lookup. |
| `projectsFolders` | text | `""` | live | L | `SPLICE_PROJECTS_FOLDERS` | Colon-separated parent folders for session project labels; not standing prompt tables. |
| `supervisorUnit` | text | `splice.service` | restart | G | `SPLICE_SUPERVISOR_UNIT` | systemd user unit supervising the install. |
| `mcpSlice` | text | `app-mcp.slice` | restart | G | `SPLICE_MCP_SLICE` | systemd slice for contained hosted MCP processes. |
| `activityRetentionDays` | int | 90 | live | G | `SPLICE_ACTIVITY_RETENTION_DAYS` | UTC days of message edges retained; not the two-day activity-label view. |
| `messageEdges` | bool | true | live | G | `SPLICE_MESSAGE_EDGES` | Store new SendMessage metadata; off leaves old rows to retention/deletion. |
| `activityStoreHeads` | text | `*` | restart | G | `SPLICE_ACTIVITY_STORE_HEADS` | Activity labels: all heads, empty for none, or comma-separated head keys. |
| `wireTap` | int | 0 | restart | H | — | How many last upstream request bodies to keep in memory for this head; 0 off. |
| `trace` | bool | true | restart | H | — | Save full traces on this head; false opts out. |
| `traceRetentionDays` | int | 7 (nominal) | restart | L | `SPLICE_TRACE_RETENTION_DAYS` | Separate UTC days for trace retention, when explicitly set. Unset follows live history window. |
| `traceMaxBodyChars` | int | 16777216 | restart | L | `SPLICE_TRACE_MAX_BODY_CHARS` | Max body chars kept per trace record. |
| `budgetDefaultAction` | text | `warn` | live | G | `SPLICE_BUDGET_DEFAULT_ACTION` | Local budget row default: `warn` or `block`. |
| `perfArchiveRetentionDays` | int | 90 | restart | G | `SPLICE_PERF_ARCHIVE_RETENTION_DAYS` | Legacy carry-over when new history window absent. |
| `historyRetentionDays` | text | unset in enum; starter writes `"35"` | live | G | `SPLICE_HISTORY_RETENTION_DAYS` | Retain request records and hourly totals for days or `forever`; unset uses max(legacy archive days, 35). |
| `perfCollectionEnabled` | bool | true | restart | G | — | Save local performance measurements. |
| `telemetryPrometheusEnabled` | bool | false | restart | G | — | Enable local Prometheus scrape. |
| `telemetryOtlpEndpoint` | text | `""` | restart | G | — | Local operator OTLP endpoint. |
| `telemetryOtlpProtocol` | text | `http/protobuf` | restart | G | — | Local operator OTLP transport. |
| `telemetryOtlpHeaders` | text | `{}` | restart | G | — | Local operator OTLP JSON headers. |
| `telemetryOtlpSignals` | text | `""` | restart | G | — | Local operator OTLP signal list. |

Scope and precedence source: `KnobKind.kt:12–89`, `HeadOverrideLayer.kt:20–76`, `TopologyKnobLayer.kt:23–99`; disposition is generated from `Knob.restartRequired`. The normalized value can differ from the raw setting (bounds, unset rules, mirror off, etc.); `GET /api/config` shows effective values and provenance.

**Retired, not usable:** `upstreamRetries`, `retryBackoffBaseMs`, `retryBackoffCapMs`, `retryBackoffJitterPct`, `foldReasoningModels`, `foldMaxContinue`, `foldMarkerText`, `foldMaxTier`, `codexAuthPath`, `grokAuthPath`. Their legacy environment aliases `CLAUDEX_UPSTREAM_RETRIES`, `SPLICE_RETRY_BACKOFF_BASE_MS`, `SPLICE_RETRY_BACKOFF_CAP_MS`, `SPLICE_RETRY_BACKOFF_JITTER_PCT`, `CLAUDEX_FOLD_REASONING_MODELS`, `CLAUDEX_FOLD_MAX_CONTINUE`, `CLAUDEX_FOLD_MARKER_TEXT`, `CLAUDEX_FOLD_MAX_TIER`, `CODEX_AUTH_PATH` and `GROK_AUTH_PATH` are ignored and shown as warnings, not consumed. Old `[daemon]` fold spellings (`fold_reasoning_models`, `fold_max_continue`, `fold_marker_text`, `fold_max_tier`) are likewise findings. Use provider `auth.file` for auth paths. `SPLICE_WIRE_TAP` and `SPLICE_TRACE` are no longer env aliases; the active keys are head-only. Source: [RetiredKnob.kt](../../core/src/main/kotlin/splice/core/config/RetiredKnob.kt):10–47, [RetiredSettings.kt](../../integrations/topology/src/main/kotlin/splice/topology/RetiredSettings.kt); `splice.example.toml:1020–1026`.

## Environment variables

A variable below is not necessarily a user-facing setting. The active knob aliases, including older `CLAUDEX_*` and `CODEX_*` spellings, are enumerated per key above. Environment names selected by users in `auth.env`, worker `auth_env`, server sign-in, forward-auth, export and backup are dynamic; example provider key names are sample values, not a second built-in alias list.

| Variable(s) | Meaning and source |
|---|---|
| `SPLICE_CONFIG`, `XDG_CONFIG_HOME`, `HOME` | Topology/home selection above; see `TopologyLoader.kt:249–257` and `UserHome.kt:15–76`. `XDG_CONFIG_HOME` also selects the Linux Desktop MCP config location. |
| `SPLICE_STATE_DIR`, `CLAUDEX_STATE_DIR` | State-path selection above; see `StatePaths.kt:44–55,161–210`. |
| `SPLICE_BIN_DIR`, `SPLICE_SHARE_DIR` | Install destinations; default `~/.local/bin` and `~/.local/share/splice`. `XDG_DATA_HOME` selects parent for systemd user units, default `~/.local/share`. Sources: `install.sh:15–16`, [InstallPaths.kt](../../core/src/main/kotlin/splice/core/config/InstallPaths.kt), [InstallWiring.kt](../../app/src/main/kotlin/splice/app/InstallWiring.kt):132–137. |
| `SPLICE_JAR`, `SPLICE_SHIM` | Explicit prebuilt jar and paired shim for installer. Launcher also reads `SPLICE_JAR`, default `<share>/splice.jar`. Source: `install.sh:483–519`, [splice-launch](../../app/src/main/dist/bin/splice-launch):320–321. |
| `SPLICE_JAVA`, `SPLICE_NO_JAVA_DOWNLOAD` | Explicit Java executable; set latter to `1` to disable installer's Java download. Source: `install.sh:101–166`, `splice-launch:326–350`. |
| `SPLICE_VERSION`, `SPLICE_RELEASE_BASE_URL`, `SPLICE_DOWNLOAD_STALL_S` | Install pinned release, override release asset base, set installer transfer stall threshold (60 seconds default); upgrade/check also read the base override. Sources: `install.sh:274,523–566`, `features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade/{UpgradeCommand,UpgradeChecks}.kt`. |
| `SPLICE_UPGRADE_JAVA` | Test seam: Java executable for probing a candidate release; defaults to current process executable or `java`. Source: `UpgradeCommand.kt:86`. |
| `SPLICE_HEAD`, `SPLICE_JVM_OPTS`, `SPLICE_CLI_JVM_OPTS`, `SPLICE_UNIT_WAIT_SECONDS`, `SPLICE_HANDOVER` | Launcher command-name override (default basename), daemon JVM options, CLI JVM options, unit readiness wait (30 seconds), and daemon succession switch (`off` disables generation handover). Source: `splice-launch:301,423,502–516,723–752`. |
| `SPLICE_LAUNCH_FUNCTIONS_ONLY`, `SPLICE_LOGIN_ORIGIN`, `CLAUDEX_DANGEROUSLY_SKIP_PERMISSIONS` | Launcher internal source/function mode; hook records login origin (`hook` versus other); value `1` requests the client's skip-permissions behavior. Source: `splice-launch:218–226,432,883–891,1174`. |
| `LISTEN_PID`, `LISTEN_FDS`, `LISTEN_FDNAMES`, `SPLICE_GENERATIONS`, `SPLICE_SUCCEEDS`, `INVOCATION_ID` | Supervisor/socket-activation and daemon generation inputs, not operator-set values. JVM options including `JAVA_TOOL_OPTIONS` are ambient; detached successor carries effective home and browser guard. Sources: `splice-launch:460–516`, [Succession.kt](../../app/src/main/kotlin/splice/app/daemon/Succession.kt), [DaemonSuccessor.kt](../../features/lifecycle/src/main/kotlin/splice/lifecycle/restart/DaemonSuccessor.kt). |
| `SPLICE_CONSOLE_DIR` | Absolute console asset directory override (uses packaged assets if unset); development seam. Source: [ConsoleMount.kt](../../app/src/main/kotlin/splice/app/control/mount/ConsoleMount.kt):149–155. |
| `SPLICE_PEER_KEY_FILE`, `XDG_RUNTIME_DIR` | Peer key file override and peer helper runtime socket root. Sources: [PeerCall.kt](../../app/src/main/kotlin/splice/app/cli/peer/PeerCall.kt):25,48–56, [PeersWiring.kt](../../app/src/main/kotlin/splice/app/peer/PeersWiring.kt):56. |
| `CODEX_THREAD_ID`, `CODEX_SESSION_ID`, `CODEX_HOME` | Native Codex thread overrides for peer hooks (after explicit ID, before hook stdin) and native Codex home; see [PeerCodexCommand.kt](../../app/src/main/kotlin/splice/app/cli/peer/PeerCodexCommand.kt):25–27,60,132. `CODEX_THREAD_ID` also supplies peer identity if `--as` absent. |
| `TEMPLE_TOOLS_SERVER`, `TEMPLE_ROOT`, `TEMPLE_URL`, `TEMPLE_CODEX_HOME`, `CODEX_HOME` | Discovery of a fallback locally registered MCP service, its URL, and its native Codex home (explicit registration, saved value, preferred env, generic env). Sources: [TempleMcpEntry.kt](../../integrations/claude-code/src/main/kotlin/splice/client/mcp/TempleMcpEntry.kt):89–97, [McpServiceRegistration.kt](../../app/src/main/kotlin/splice/app/cli/mcp/McpServiceRegistration.kt):49–58, [TempleMcpClients.kt](../../app/src/main/kotlin/splice/app/mcp/TempleMcpClients.kt):26–36. |
| `APPDATA`, `XDG_CONFIG_HOME` | Windows/Linux Desktop MCP config roots. Source: [McpHarnessDiscovery.kt](../../app/src/main/kotlin/splice/app/mcp/McpHarnessDiscovery.kt):58–80. |
| `ANTHROPIC_BASE_URL`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL`, `CLAUDE_CODE_HOST_AUTH_ENV_VAR`, `CLAUDE_CODE_USE_MANTLE` | Desktop launcher identifies gateway/Mantle wiring and named credential env; launch shim recognizes Mantle. Source: [DesktopLaunchEnv.kt](../../app/src/main/kotlin/splice/app/cli/desktop/DesktopLaunchEnv.kt), `splice-launch:210–215`. |
| `CLAUDE_CONFIG_DIR`, `CLAUDE_CODE_ENTRYPOINT`, `CLAUDE_CODE_HOST_SESSION_ID`, `CLAUDE_SPLICE_DESKTOP`, `SPLICE_DESKTOP_ESCAPED` | Desktop/client launch inputs (config folder, caller/host session identity, side-by-side marker, internal privilege handoff marker). Sources: `app/src/main/kotlin/splice/app/cli/desktop/{DesktopLaunchEnv,PrivilegeEscape}.kt`, [SideBySideDesktop.kt](../../app/src/main/kotlin/splice/app/desktop/setup/SideBySideDesktop.kt). |
| `SPLICE_FOREGROUND_OWNER`, `SPLICE_MCP_HUB`, `SPLICE_MCP_CONTROL_PORT` | Generated launch ownership marker and generated native MCP front service settings; not TOML aliases. Sources: [LaunchEnvironment.kt](../../features/launch/src/main/kotlin/splice/launch/recipe/LaunchEnvironment.kt), [McpFrontEnv.kt](../../app/src/main/kotlin/splice/app/McpFrontEnv.kt). |
| `ANTHROPIC_CUSTOM_HEADERS`, `NO_PROXY`, `no_proxy` | Ambient client headers and proxy exclusions for launch; splice strips inherited splice headers and merges its own. Sources: `splice-launch:879–891` and [LaunchAmbient.kt](../../features/launch/src/main/kotlin/splice/launch/recipe/LaunchAmbient.kt). |
| `PATH`, `SHELL`, `DISPLAY`, `WAYLAND_DISPLAY`, `DBUS_SESSION_BUS_ADDRESS` | Host executable lookup, doctor login-shell lookup, graphical-login detection, service-manager reachability. Sources: [DoctorPathCheck.kt](../../features/diagnostics/src/main/kotlin/splice/diagnostics/doctor/DoctorPathCheck.kt), [ConsoleAccountsOpen.kt](../../app/src/main/kotlin/splice/app/cli/auth/ConsoleAccountsOpen.kt):25, [PrivilegeEscape.kt](../../app/src/main/kotlin/splice/app/cli/desktop/PrivilegeEscape.kt), `splice-launch:672–675`. |
| `TERM`, `NO_COLOR`, `COLORTERM`, `VISUAL`, `EDITOR`, `SSL_CERT_FILE` | CLI color, editor, additional OS trust bundle. Sources: [CliPalette.kt](../../core/src/main/kotlin/splice/core/terminal/CliPalette.kt):122–129, [ConfigFixOffer.kt](../../app/src/main/kotlin/splice/app/cli/ConfigFixOffer.kt):114–118, [TrustRoots.kt](../../integrations/upstream/src/main/kotlin/splice/upstream/transport/tls/TrustRoots.kt):44–54. |
| `MAX_THINKING_TOKENS` | Inherited nonempty client override is preserved over a launch recipe's default ([splice-launch](../../app/src/main/dist/bin/splice-launch):1001–1029). |
| `SPLICE_CONFIG_DIR`, `SPLICE_HOME` (Desktop sandbox tooling only) | The repo-only sandbox supplies these along with its effective `HOME`, `XDG_CONFIG_HOME`, `SPLICE_CONFIG`, and `SPLICE_STATE_DIR`; they are not daemon config/home selectors. The sandbox root check protects the caller's `SPLICE_CONFIG_DIR`. Sources: [runtime.ts](../../tools/desktop-sandbox/lib/runtime.ts):65–98, [paths.ts](../../tools/desktop-sandbox/lib/paths.ts):34–57. |
| `CODEX_PROXY_TEST=1` | Test-only lowers stream-idle floor from 30 s to 250 ms. Source: `core/src/main/kotlin/splice/core/config/{ConfigCoercion,ConfigService}.kt`. |

### Test homes and loopback-only endpoint overrides

A test home isolates local files. It does not change the endpoint rule: splice accepts these endpoint overrides only for loopback hosts and logs a refused variable once before using the built-in address.

 Codex `CODEX_OAUTH_ISSUER`, `CODEX_OAUTH_AUTHORIZE_URL`, `CODEX_OAUTH_TOKEN_URL`; Grok `GROK_OAUTH_ISSUER`, `GROK_OAUTH_AUTHORIZE_URL`, `GROK_OAUTH_TOKEN_URL`; Kimi `KIMI_OAUTH_HOST`; Muse `MUSE_OAUTH_DEVICE_AUTHORIZATION_URL`, `MUSE_OAUTH_TOKEN_URL`, `MUSE_OAUTH_KEY_URL`; native Claude account/usage `CLAUDE_OAUTH_PROFILE_URL`, `CLAUDE_OAUTH_USAGE_URL`. Only localhost, 127.0.0.1 and IPv6 loopback are accepted; other hosts/invalid URLs are ignored in favor of the built-in endpoint. Client IDs `CODEX_OAUTH_CLIENT_ID`, `GROK_OAUTH_CLIENT_ID` and Codex `CODEX_OAUTH_ORIGINATOR` are plain env reads, not URL overrides. Sources: [LoopbackOverride.kt](../../core/src/main/kotlin/splice/core/util/LoopbackOverride.kt), `integrations/providers/{codex,grok,kimi,muse}/src/main` (`CodexOAuthEndpoints.kt`, `GrokAuthDescribe.kt`, `KimiOAuth.kt`, `MuseOAuth.kt`), [ClaudeCredentialProfiles.kt](../../app/src/main/kotlin/splice/app/auth/claude/ClaudeCredentialProfiles.kt):131, `features/usage/src/main/kotlin/splice/usage/quota/{QuotaProbes,ClaudeUsageProbe}.kt`.

## Related pages

- [CLI & Control Plane API Reference](cli-reference.md)
- [Getting Started](getting-started.md)
- [Providers and Accounts](providers-and-accounts.md)
- [Door and Launchers](door-and-launchers.md)
- [Claude Desktop](claude-desktop.md)
- [MCP Hub and Sync](mcp-hub-and-sync.md)
- [Modes and Extensions](modes-and-extensions.md)
- [Team Server Mode](team-server-mode.md)
- [Disk and Privacy](disk-and-privacy.md)
