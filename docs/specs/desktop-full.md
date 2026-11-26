# Claude Desktop, fully on splice (`desktop-full`)

Goal handed to the campaign on Nov 18, 2026. It follows `desktop-door.md`, whose door is built but has never run in
the real app.

## Built (Nov 21, 2026)

`splice desktop on` is outcome 1 with nothing to paste: it writes splice's configuration into Desktop's 3P library and
applies it (DesktopAppMode, the one owner of what splice changes in Desktop), with `inferenceCredentialHelper` naming
`<state>/desktop-credential`, which runs `splice desktop credential`. There is no `--app`: the environment-store paste
path (standard mode) is removed, and a door an older splice opened that way is still answered until `on` or `off`
replaces it. `--model` is Desktop's own default, listed first. `off` and `splice uninstall --all` take out exactly what
`on` put in. The picker is the model catalog's (docs/specs/door-default.md): discovery at `/door/v1/models` (and
`/desktop/v1/models`), each row under the id Claude's clients keep (ClientModelNames), with the provider's own name,
description, effort levels and window. The installed Desktop 6.28899.2 reads names and descriptions from discovery but
does not carry its `thinking`, `behaves_as` or `max_effort_level` fields into the picker. Splice therefore writes
`inferenceModels` from that same provider catalog when Desktop is enabled, at daemon startup and when the catalog
changes; `behavesAs` and `maxEffort` there enable its reasoning control. Desktop reads that configuration at launch.
`maxEffort` caps Desktop's own low, medium, high, xhigh and max sequence; it cannot express gaps in a provider's
sequence or its minimal and ultra levels. The gateway response still publishes the provider's exact effort options.
The door takes the key as
`x-api-key`, answers `GET`/`HEAD /` with `splice` for a client that asks for no HTML, and scales an app-mode request's
counts to the 200,000 (or 1M) window Desktop gives its Code tab. That pair is splice.toml's `[desktop]`
(`code_window`, `code_window_1m`), for an install whose Desktop is given other windows: Desktop sets the windows its
Code tab divides by itself, so the keys state what this install's Desktop sets and splice scales to them, and a model
is advertised with a 1M variant (`supports_1m`, `supports1m`) when its provider's real window reaches `code_window_1m`.
Discovery's `max_input_tokens` stays the provider's real ceiling, never a configured window. The app-mode header
identifies Desktop but not which window Code selected. A request that selects the large variant explicitly uses
`code_window_1m`. Native 1M models can use their built-in million without an opt-in beta, and Code can also read a large
window from an accepted discovery row. For these beta-free requests splice uses the largest plausible divisor and
never discounts input based on it. This can compact early, but cannot delay compaction because the wrong window
was assumed.
The daemon reads the table at start, so an edit takes a daemon restart and Desktop's next launch. `splice desktop` and `splice doctor` say the mode and
whether Desktop was reopened since splice changed it, and `splice desktop` lists each backend-dependent feature per mode,
working or why not (DesktopFeatures; what only the live test settles is "unknown"). `on` also lays
`<state>/desktop-launch`, which runs `splice desktop launch`: a managed processWrapper can name it, and it gives a
Code-tab launch on the door the desktop key Desktop stripped (Mantle's bearer, or the gateway's variable). splice still
never writes the managed file. Each turn's perf row names its subagent and parent agent (df-turn-headers). The policy
also raises Desktop's Claude Code import prompt (`bannerBehavior: "show"`, `bannerMaxDismissals: 10`), so gateway mode
can bring the sessions Claude Code has on the computer into its Code-tab list. Not built: the gateway answer fields of
df-wire-answers.

Outcome 9 (desktop-policy) is built: the configuration is generated, the build's default policy
(`claude-code-desktop-policy/ccd-policy.json`, DesktopPolicyDefaults) with what the person set up in Claude Code laid
over it (ClaudeCodeSetup, DesktopPolicy), written whole by `on` and given, where it is unset, to every applied
configuration that points at the door at each daemon start and catalog change (DesktopAppLibrary.publish). Read by
the real app on Nov 21, 2026: Desktop 6.28899.2, started in a throwaway home on a private display, opened in gateway
mode on the generated configuration and refused none of its keys, and the same run with three bad values named each
one in `main.log` (`Failed to parse managed config "<key>"`), so its silence is acceptance. Not driven further than
launch: a turn, the picker and Cowork under this configuration wait for the sandbox of outcome 8.

MCP modes (Nov 25, 2026): Desktop's own sessions are given the server of each mcp mode (modes/README.md) that is on
for every head, has no `[heads] only` and does not name `"desktop"` on `[callers] off`. It is one more stdio-bridge
entry in the gateway profile's `claude_desktop_config.json` (DesktopMcpSource, DesktopOwnMcp), the same transport as
the hosted servers Desktop already reaches there, so nothing goes into managed policy or `managedMcpServers` and what
Desktop may do is unchanged. The bridge reaches the daemon's own `/mcp/<server>` on the control port with the MCP
access key it reads from the state folder at each start, and names `desktop` as the caller, which the mode is told;
tools, `_meta` (`ui.resourceUri`, `ui.visibility`) and `ui://` resources pass through unchanged. The daemon writes the
entry at start, catalog change, `on` and each `PUT /api/modes/<name>` of an mcp mode, and records the names it gave
in `<state>/desktop-own-mcp.json` so commands that sync Desktop without the daemon keep them. A server of the person's
with a mode's name keeps the name there and at the daemon's address, and the mode's row says so (`server_taken`). Desktop reads the file at launch, so a switch shows at its next start; turned off,
the server stops answering at once. Not yet read in the real app: which of Chat, Cowork and the Code tab list these
servers and draw their pages waits for the sandbox of outcome 8.

## Who it is for and why

Someone who uses Claude Desktop (Chat, Cowork and the Code tab) and runs splice. Asked for on Nov 18, 2026: Desktop
fully supported, with nothing left uncovered. splice should be to Desktop what Amazon Bedrock or an LLM gateway is to
it: the provider Desktop is configured for, so that every model splice serves can be picked inside Desktop, and
Desktop's features work as they do on any provider Anthropic supports.

## Where it stands (read Nov 18, 2026)

- The door (`/desktop/v1/messages`, `/count_tokens`) is built and its campaign checks pass (#637, #653, #656, #667).
  It has never served the real app. On this machine Desktop 5.11492.5 is installed and the door is on, but Desktop's
  Code-tab environment store (`~/.config/Claude/ccd-environment-config.json`) does not exist, so the Code tab runs
  on the person's own Claude login.
- The person has to paste five variables into Desktop by hand. splice can't write that store: Desktop encrypts it
  with the keyring.
- Desktop's picker lists only Claude's models, and splice can't add rows to it in standard mode. Every Claude model
  picked in Desktop runs the door's saved default (`claude-codex--gpt-6.1-sol`), because `DoorRouting` sends a tier
  from a session that named no selector to the caller's default. The terminal's `claude` was fixed on Nov 17–18 to
  run a Claude model on the Claude accounts added to splice (the `claude` head is in the door, 15 models). Desktop
  wasn't.
- The door answers no `GET /v1/models`.

## What Anthropic documents (read Nov 18, 2026)

Desktop has a supported way to put a provider under the whole app: **Claude Desktop on 3P**
(https://claude.com/docs/third-party/claude-desktop/overview, `/gateway`, `/code`, `/models`, `/configuration`,
`/configuration-format`).

- `inferenceProvider: "gateway"` with `inferenceGatewayBaseUrl` and a credential routes Chat, Cowork and Code through
  a gateway that implements the Anthropic Messages API. `POST /v1/messages` (streaming, tools) is required.
  `GET /v1/models` is optional and fills the picker; auto-discovery keeps only IDs that read as Claude.
- `inferenceModels` builds the picker in Chat, Cowork and Code. Each entry has `name` (the ID sent), `labelOverride`,
  `supports1m`, `prefer1m`, `anthropicFamilyTier` (the tier alias it stands for), `isFamilyDefault`, `behavesAs`
  (the Claude model whose effort levels and limits it gets) and `maxEffort`. The first entry is the default. Also
  `defaultModelEffort`, `alwaysStartWithDefaultModel`, `modelDiscoveryEnabled` and `modelPrefer1mContext`.
- Credentials: `inferenceGatewayApiKey` and `inferenceGatewayAuthScheme` (bearer or x-api-key),
  `inferenceCredentialHelper` (an executable that prints the credential), or `inferenceGatewayOidc` /
  `inferenceIdpOidc` sign-in. `inferenceCustomHeaders`.
- The Code tab's Claude Code gets these as launch environment and options ("always applied": provider, credentials,
  `inferenceModels`, `autoModeEnabled`, `autoModeClassifierModel`, OTLP keys, `inferenceMaxTokensPerWindow`,
  `organizationInstructions`, `managedMcpServers` and more) or as managed policy.
- Where the configuration lives on Linux: the per-user local configuration is `~/.config/Claude-3p/configLibrary/`
  (`_meta.json` names the applied config, one `.json` per config; the in-app window writes it, and other vendors'
  CLIs write it too). The managed configuration is `/etc/claude-desktop/managed-settings.json`, root-owned.
  The app opens in 3P mode at launch when a provider is configured; its data then lives under `~/.config/Claude-3p`.
- What 3P mode costs the person: Chat and Cowork move to the provider as well, conversations are stored on the
  device, identity is local, Anthropic-hosted cloud environments and Remote Control aren't offered, and SSH sessions
  need a gateway address that isn't localhost.
- A gateway should keep `cache_control` intact and return `usage.cache_creation` with `ephemeral_5m_input_tokens`
  and `ephemeral_1h_input_tokens` (Chat and Cowork ask for 1h). It should forward `anthropic-beta`/`anthropic-version`
  and body fields as open lists, return integer `retry-after` and `x-should-retry`, forward
  `anthropic-ratelimit-unified-*` for the usage display, and pass error bodies through unmodified
  (https://code.claude.com/docs/en/llm-gateway-protocol). Claude Code sends `x-claude-code-session-id`,
  `x-claude-code-agent-id` and `x-claude-code-parent-agent-id`.
- Standard mode (signed in to claude.ai) keeps Desktop's own `ANTHROPIC_BASE_URL` for the Code tab. That's why the
  existing door uses the Mantle switch from the environment store.

## What the installed app does (Desktop 5.11492.5, read from its bundle on Nov 18, 2026)

Read from `/usr/lib/claude-desktop/resources/app.asar` (main process). Confirm each point in the live test before
building on it.

- **Standard mode's picker can't be changed by splice.** Desktop's UI never reads the models that its Claude Code
  reports. So `modelPicker`, `availableModels` and `ANTHROPIC_CUSTOM_MODEL_OPTION` in `~/.claude/settings.json` shape
  Claude Code's own `/model` only, never Desktop's picker. Only 3P mode gives a picker splice controls.
- **3P picker source:** the `inferenceModels` list first, else discovery, else a built-in catalog. For the `gateway`
  provider, discovery is `GET {base}/v1/models?limit=1000&after_id=…` with `anthropic-version: 2023-06-01`, up to 3
  pages. It reads `data[].{id, display_name, supports_1m, max_input_tokens, anthropic_family_tier,
  is_family_default}`, `has_more` and `last_id`. IDs are filtered: for gateway and mantle, a row with
  `anthropic_family_tier` passes. For `mantle` with a static token or helper, discovery is off and the picker is only
  `inferenceModels`. Desktop also gives Claude Code `availableModels` (exact match) and a `modelPicker` built from the
  list.
- **3P base URLs accept loopback http** (`allowLoopbackHttp`), so `http://127.0.0.1:<port>/desktop` is valid for
  `inferenceGatewayBaseUrl` and `inferenceBedrockBaseUrl`.
- **3P scope is the whole app.** There is no Code-tab-only scope. `deploymentMode` is any, 3p or 1p; `any` shows a
  chooser at sign-in.
- **3P sets Claude Code managed settings:** `CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1`, `DISABLE_GROWTHBOOK=1`,
  `CLAUDE_CODE_MODEL_CATALOG=0`, `CLAUDE_CODE_DISABLE_FAST_MODE=1`, `DISABLE_FEEDBACK_COMMAND=1`,
  `CLAUDE_CODE_ENABLE_AUTO_MODE`, `CLAUDE_CODE_AUTO_MODE_SERVER`, `CLAUDE_CODE_MAX_CONTEXT_TOKENS=200000` (1M through
  `[1m]` on the ID), and `disableClaudeAiConnectors: true`. Desktop does not turn off native context in this setup.
  `prefer1m` selects the 1M spelling; native context is resolved independently by Claude Code, before the ordinary
  environment window. An opt-in model normally sends the context beta; a native 1M model need not.
- **The credential:** a static token or `inferenceCredentialHelper`, which Desktop runs and puts in the session's
  environment (as `AWS_BEARER_TOKEN_BEDROCK` under mantle). Desktop sets no `apiKeyHelper`.
- **Desktop's main process calls the base itself, not only its Claude Code:**
  - `POST {base}/v1/messages`, not streamed, for the connection test (`max_tokens` 1), Claude in Chrome one-shots,
    and title and side calls. Under mantle it sends **`x-api-key`**, not Bearer (Bearer only with the bearer
    scheme). The door accepts only Bearer today.
  - A reachability probe, `GET {origin}/`, which hits `127.0.0.1:<port>/`, not `/desktop`.
- **Standard mode with a provider switch is a case Desktop expects.** Any truthy `CLAUDE_CODE_USE_*` in the session
  environment makes it skip its model check against the account and not treat a model 401 as an expired Desktop
  token. The env store drops `CLAUDE_CODE_PROCESS_WRAPPER`, `CLAUDE_CODE_AUTOUPDATER_DISABLED_BY_HOST`,
  `CLAUDE_CODE_SIMPLE` and `CLAUDE_AGENT_SDK_MCP_NO_PREFIX`. `CLAUDE_CODE_HOST_OFFERS_DEVICE_MCP` is deleted unless
  Desktop sets it, which corrects `desktop-door.md`'s "Dropped" row. Desktop sets `API_TIMEOUT_MS=900000` itself.
- **Feature by feature:**
  - In 3P: cloud sessions, Remote Control and the bridge are off, and the Cowork VM gives way to a CLI on the host.
    Fast mode is off. Auto mode needs the gateway's server-side classifier check, or it shows as unavailable.
    Usage shows a cost estimate from `inferenceModelPricing*`.
  - Standard mode under the switch: titles, Remote Control and claude.ai connectors are unknown. Claude Code has a
    cross-org guard that likely keeps connectors off.
  - Hooks, permission modes, worktrees, preview, files and images are local to the host or inline in the body, in
    both modes.
- **Unknown:** the standard-mode picker's server source, standard mode's title path, connectors and Remote Control
  under the switch, and the Developer-menu path on screen.

## Outcomes

1. **Desktop on splice as its provider (3P gateway mode).** One command (e.g. `splice desktop on --app`, name it as
   you see fit) writes a Desktop 3P local configuration in `configLibrary` that points Desktop at splice's door as a
   gateway. The person pastes nothing. The credential comes from `inferenceCredentialHelper`, a splice command that
   prints the desktop key, so no key is written in the file. `off` restores what was there, and Desktop returns to
   the person's claude.ai login. splice never touches the managed file in `/etc`. The command says, before it
   switches, what the person gives up (Chat and Cowork on splice too, local history, no Remote Control or cloud
   environments) and that Desktop must be relaunched.
2. **Every splice model in Desktop's picker,** in Chat, Cowork and Code, with the same rows as the terminal picker
   (`DesktopLineup.picker`). Each row has its label; `behavesAs` or `anthropicFamilyTier` so effort and limits are
   right; `supports1m` only where the model really takes 1M; and `maxEffort` from the head's effort vocabulary.
   Prefer the `gateway` provider with discovery: the door answers `GET /desktop/v1/models` in the shape Desktop
   reads (`id`, `display_name`, `supports_1m`, `max_input_tokens`, `anthropic_family_tier`, `is_family_default`,
   paging with `has_more`/`last_id`), so the picker follows the lineup at each launch. Rows must pass Desktop's ID
   filter. Use `inferenceModels` where discovery can't carry a field. Whichever is chosen, a sign-in or an added head
   reaches the picker at Desktop's next launch, and `splice desktop` says when a relaunch is needed.
3. **Claude picks run on Claude.** In both modes, a Claude model picked in Desktop runs on the Claude accounts added
   to splice (the `claude` head) when it serves that model, as the terminal does since Nov 18. The saved default
   stands in only when no head serves it. Tier aliases (`opus`, `sonnet`, `haiku`, `fable`) and side calls follow
   the session's head.
4. **Standard mode stays and gets its gaps closed.** The existing Code-tab-only door (the Mantle env store) remains
   for people who keep Chat and Cowork on claude.ai. Its window, tiers and recorded turns keep working.
5. **Wire parity with what Desktop expects of a gateway:**
   - The desktop key is accepted as `x-api-key` as well as Bearer.
   - `GET /` (Desktop's reachability probe) answers.
   - Desktop's own non-streamed one-shots (connection test, titles, Claude in Chrome) run like any door turn.

   Also: `cache_control` untouched, `usage.cache_creation`
   ephemeral 5m/1h fields, integer `retry-after`, `x-should-retry`, `anthropic-ratelimit-unified-*` from the head's
   own limits so Desktop's usage display means something, error bodies whole, and agent/session headers recorded
   on `/api/perf/turns`. `count_tokens` is answered on every head.
6. **Desktop features that depend on the backend** each work, or are named as unavailable with the reason in
   `splice desktop` (see the app study below): Auto mode and its classifier model, effort selector, 1M/context meter
   and compaction, title generation, subagents, web search/fetch on non-Claude heads, images and files, MCP, scheduled
   tasks.
7. **Diagnostics:** `splice desktop` and `splice doctor` say which mode Desktop is in (standard with the env store,
   3P with splice's configuration, 3P with someone else's or a managed file, or off), whether Desktop has been
   relaunched since the config changed, the picker rows, and the last turn per mode.
8. **The campaign runs the live test itself, on the real app in a private sandbox, and drives it the way a person
   would** (added Nov 18, 2026, at the founder's direction). It is an item of this feature, with its own check, and
   the feature isn't done until it passes.
   - **The sandbox wrapper** is repo tooling (Bun/TypeScript, e.g. `tools/desktop-sandbox`). It starts the installed
     Desktop (`/usr/lib/claude-desktop/claude-desktop`) with everything of its own:
     - `HOME` and `XDG_CONFIG_HOME` under a sandbox folder, so Desktop's data (`Claude/`, `Claude-3p/configLibrary`)
       and the Code tab's Claude folder (`~/.claude`, `CLAUDE_CONFIG_DIR`) are fresh and throwaway. Electron's
       single-instance lock is per data folder, so it runs beside the person's own Desktop.
     - Its own D-Bus session (`dbus-run-session`) and `--password-store=basic`, so it never reaches the person's
       keyring, their running Desktop, or their browser's deep links.
     - A private display (`Xvfb :N`, or `Xephyr` when a person wants to watch), so it never takes over the person's
       screen.
     - A splice daemon from the build under test, on its own port and state folder, never the person's running
       daemon or its state.

     The person's real `~/.config/Claude`, `~/.config/Claude-3p`, `~/.claude`, `~/.config/splice` and keyring are
     never written. The wrapper refuses to start if any path it would write resolves into them.
   - **Driving it (computer use):** the seat works the sandbox's display as a person would. It takes screenshots
     (`ffmpeg -f x11grab` on the private display) and reads them as images, and sends clicks and keys with
     `xdotool`. Where the app allows it, Chromium's `--remote-debugging-port` can read the DOM for exact text, as a
     complement and never instead of looking. This machine has no computer-use tool for agent sessions; this is that
     tool.
   - **What it covers, in 3P gateway mode with no human step:**
     - `splice desktop on` (3P), relaunch, and the picker's rows in Chat, Cowork and Code against the lineup.
     - A turn on a GPT head, on a Grok head and on Claude.
     - Model switching mid-session, effort levels, the context meter and a session long enough to compact.
     - A subagent naming a tier, titles, an image attachment, an MCP tool, auto mode (or its named absence) and web
       search/fetch on a non-Claude head.
     - Chat and Cowork turns.
     - `/api/perf/turns` rows for each.
     - `off` returning the sandbox Desktop to its login screen.
   - **Standard mode** (the Code tab with Chat on claude.ai) needs a claude.ai sign-in inside the sandbox. The seat
     covers what runs without one. Where only a sign-in unblocks the rest, it opens the sandbox on a visible
     `Xephyr` window and asks once, through the lead.
   - **The record:** findings, including every Unknown above that the run settles, go into this spec. The screenshots
     judged are attached to the PR, after checking that none shows a key, an account name or a private path.
9. **Desktop starts out matching the person's Claude Code (desktop-policy, added Nov 21, 2026, at the founder's
   direction).** The configuration splice gives Desktop is generated, never filled in by hand: splice's default policy
   with what the person already set up in Claude Code laid over it, so nothing is entered again in Desktop's own
   configuration window.
   - **The default** ships in the build as `claude-code-desktop-policy/ccd-policy.json`, in the flat keys Anthropic's
     configuration reference documents (https://claude.com/docs/third-party/claude-desktop/configuration), with
     everything useful on: Chat and Cowork, the built-in browser, extensions, projects, scheduled tasks, skills, keep
     awake, Developer Mode, plugins a person adds and their marketplaces, local MCP servers and tool search; every host
     reachable from the workspace (unset, Desktop gives Chat and Cowork none); MCP tool calls allowed 30 minutes (unset,
     three); a stream that sends only pings waited on 15 minutes longer. It holds no address, credential or model list:
     the door's address and the helper are the daemon's to give, and the models are written as they stand.
   - **Taken from Claude Code:** the effort the person's sessions run at (`effortLevel` in `settings.json`), as
     Desktop's default effort; and the marketplaces they added (`plugins/known_marketplaces.json`) that Desktop can
     reach, after the default's: a GitHub repository, a git remote or a hosted marketplace file, with its branch and
     folder. splice reads both files and writes neither.
   - **Left in Claude Code, each for a reason Desktop gives** (read from the bundle of Desktop 6.28899.2):
     - MCP servers. One managed server that is not `policy-only` makes Desktop start its Code sessions with
       `strictPluginOnlyCustomization: ["mcp"]`, so MCP loads from plugins and policy only and the person's own
       servers are gone there. The Code tab already reads them from `~/.claude.json`.
     - Permission rules. Code sessions read them already; as `disabledBuiltinTools` they would also bind Cowork and
       Chat, which a person did not ask for by setting them in Claude Code.
     - The status line: Desktop runs one only from a root-owned file outside the home folder.
     - A launcher (`processWrapper`): Desktop then passes no credential, so only a launcher that gives splice's key
       works, which is `desktop-launch` and a managed file's to name.
     - A marketplace read from a folder on this computer, which Desktop's configuration cannot name, and one Claude
       Code clones only in part, which Desktop would clone whole.
   - **No Claude login in gateway mode.** The configuration opens Desktop straight on splice (`deploymentMode: "3p"`,
     and `disableDeploymentModeChooser` for a Desktop older than 4.16336) and takes nothing from the person's Claude
     account. A person who wants their Claude account and splice runs a second Desktop beside their own, on a config
     folder of its own.
   - **Filled, never overruled.** `on` writes splice's configuration whole. At each daemon start and catalog change,
     every applied configuration that points at the door (splice's own, or one a person made, in their own library or
     in one beside it) is given what it leaves unset, the marketplaces its list lacks and the app-mode header among its
     own headers. A key it sets stays as the person set it, `false` included.
   - **Sessions.** The Code tab runs Claude Code over the same `~/.claude` as the terminal, so its sessions write
     their transcripts where the terminal's are and see every other session on the computer as a peer. Desktop's own
     list holds the sessions it started. Desktop 6.28899.2 brings Claude Code's sessions into that list only through
     its import, which scans `${CLAUDE_CONFIG_DIR:-$HOME/.claude}/projects` (read from the bundle on Nov 22, 2026),
     copies each transcript under a new id and records it, for folders Desktop trusts. Every head whose policy shares
     `projects` (the default) writes into that same tree, so the sessions started on a head are among the importable
     ones. Gateway mode has no claude.ai account for the import's `detect` value to resolve, so the shipped policy
     asks for the import outright: `claudeAiImport.bannerBehavior: "show"` with `bannerMaxDismissals: 10`, which
     keeps the offer returning until it is accepted. Accepting it is still the person's act, and sessions started
     after it are not in the list until the offer comes again: no key makes the list follow the tree as it changes.
     The same import is on Desktop's menu as "Import Claude Code CLI Sessions…". `automatic3pImport` copies an
     earlier Desktop profile, never the terminal's sessions.

## Constraints

- Repo CLAUDE.md and AGENTS.md hold. Tests check what a user sees or what a route returns. Vendor facts (Desktop's
  config format and paths) stay in one module. Boot fails closed. No Python. No new top-level functions or companion
  objects. Reasoning effort never varies by turn type.
- Never read or write Desktop's keyring-encrypted stores, the keyring itself, or `/etc/claude-desktop`. Never print
  the desktop key.
- splice CLAUDE.md §22: splice writes the values on both sides of the wire (the picker rows, windows, counts), so
  fix behaviour by what splice writes before anything else. Reverse-engineering Desktop is a last resort.
- The `custom-provider` campaign (issue #680) is changing the door at the same time: the terminal picker and tier
  mapping for key heads. Build on `DesktopLineup.picker` and its tier mapping, and don't fork them.
- A change to the console's Desktop card needs captures in dark and light themes at 1440 px and ~3600 px.
