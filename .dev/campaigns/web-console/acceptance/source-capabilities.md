# Source 1: what splice can do today, as acceptance questions

Derived 2026-09-27 (read-only) from CommandParser.kt / Command.kt and their sub-verb parsers,
app/src/main/dist/bin/splice-launch, README.md, CHANGELOG.md (v0.4.0 back to v0.3.0-beta.1),
app/src/main/resources/splice.example.toml, and the /api/* route registrations. Each question says
how the user does it today. Input to the console's acceptance list (owner: Marlin); not the list.

## Getting set up
- Can the user install splice with one command that checks prerequisites and verifies checksums? — today: `curl … install.sh | bash` (README.md:84-92)
- Can the user pin the installer to one exact release? — today: `SPLICE_VERSION=vX.Y.Z bash` (README.md:94-99)
- Can the user build and install from a local checkout? — today: `./install.sh` (README.md:113-119)
- Can the user hand the install-and-verify loop to a coding agent? — today: the packaged agent prompt (README.md:121-139)
- Can the user run splice on Windows? — today: WSL2 only (README.md:67-70)
- Can the user get a working setup with just an API key? — today: `OPENROUTER_API_KEY=… splice setup` (README.md:145-153)
- Can the user run a guided wizard that finds existing credentials and offers heads to add? — today: `splice setup` (SetupCommand.kt:66-97)
- Can the user write just the starter topology? — today: `splice init` (CommandParser.kt:53)
- Can the user (re)link the wrapper commands for one head or all? — today: `splice install [<head>|--all]` (CommandParser.kt:30,54)
- Can the user remove a head's wrapper command? — today: `splice uninstall [<head>|--all]` (CommandParser.kt:55)
- Can the user hand an NVIDIA card to a local model during setup? — today: `splice setup` offers rig (CHANGELOG.md:124-135)
- Can the user have a missing dependency fixed by the installer? — today: install.sh offers each fix (README.md:80-82)

## Accounts and sign-in
- Can the user sign in a ChatGPT/Grok/Kimi/Muse subscription? — today: `<head> login` / `splice login <head>` (LoginCommand.kt:44-59)
- Can the user sign in an API-key provider without shell history? — today: `<head> login` masked prompt (README.md:160-161)
- Can the user paste an API key into a live session and have it captured before the model sees it? — today: bare-message key capture (README.md:162-165)
- Can the user set, list or unset a stored API key? — today: `splice key set|list|unset` (KeyCommand.kt:44-59)
- Can the user sign in a second account of the same kind and have splice pool it? — today: `splice login <head> --label <name>` (CommandParser.kt:56-71)
- Can the user drop an unlabeled login when switching accounts? — today: `--discard` (CommandParser.kt:44,61,68-70)
- Can splice move a session to another pooled account when one is rate-limited? — today: automatic switching (CHANGELOG.md:414-460)
- Can the user see which account a head or session is on, and why it last switched? — today: status line, `splice status`, `splice doctor` (README.md:294-306)
- Can the user remove or relabel a pooled account? — today: DELETE/PATCH /api/auth/{head}/accounts/{label} (CHANGELOG.md:225-226)
- Can the user pin a head to one pooled account? — today: POST/DELETE /api/auth/{head}/switch (CHANGELOG.md:227-230)
- Can the user sign in another account from inside Claude Code? — today: `/login --label NAME` (CHANGELOG.md:375-394)
- Can the user keep several Claude logins on claude-splice and switch between them? — today: `splice login claude-splice --label <name>` (README.md:308-320)
- Can the user wrap the plain `claude` command to run through splice? — today: POST /api/claude-head/wrap, /unwrap (CHANGELOG.md:190-206)
- Can the user see every account across every head in one view with real usage windows? — today: GET /api/accounts (CHANGELOG.md:231-238)
- Can the user keep splice's credential separate from the vendor CLI's? — today: splice-owned files by default, `auth.file` opt-in (README.md:265)

## Heads and models
- Can the user add a new provider/head without editing splice.toml? — today: `splice add <profile> [--name][--base-url][--model][--command][--live][--yes]` (AddArgs.kt:12-64)
- Can the user add more curated OpenRouter models to a head? — today: `splice add-model` (Command.kt:73-75)
- Can the user see what a provider actually serves versus what is declared? — today: `splice models [provider|--all]` (Command.kt:79-81)
- Can the user add a new OpenAI-compatible vendor with no code? — today: `[providers.<key>]` TOML (splice.example.toml:244-252)
- Can the user run a local model (Ollama, LM Studio, vLLM) as a head? — today: loopback base_url (splice.example.toml:209-243; README.md:456-475)
- Can splice refuse a model the runtime doesn't serve? — today: boot + doctor probe (README.md:465-467)
- Can the user get a newly shipped vendor model without editing TOML? — today: discovery at boot (CHANGELOG.md:152-166)
- Can the user hide discovered models from the picker? — today: `discovery = { include, exclude }` (splice.example.toml:193,405)
- Can the user pick which model backs Claude Code's opus/sonnet/haiku/fable on a head? — today: `models = [{ id, slot }]` (splice.example.toml:513…707)
- Can the user change a model's context window without a restart? — today: `context_window` hot reload (README.md:613; CHANGELOG.md:273-283)
- Can the user run several heads at once, each with its own command? — today: `[heads.<key>]` (splice.example.toml:503-723)
- Can the user cap concurrent turns per head? — today: `maxInflight` (splice.example.toml:517-518)
- Can the user give a slow head a longer timeout? — today: `upstreamTimeoutMs` (splice.example.toml:590-592)
- Can the user silence splice's "still working" line? — today: `progressLine = false` (splice.example.toml:519-523)
- Can the user restart the daemon so an edit takes effect? — today: `splice restart [--now]` (Command.kt:99-102)
- Can the user start, stop or restart one head? — today: POST /api/heads/{head}/{action}
- Can the user inspect a head's live request state? — today: GET /api/heads/{head}/inspect
- Can the user see and stop one in-flight turn? — today: GET /api/heads/{head}/turns/live, POST …/turns/{id}/stop
- Can the user check each head's status (up, configured, signed in)? — today: `splice status` (StatusCommand.kt:39-76)

## Prompts and behavior
- Can the user give one head standing instructions on every turn? — today: `system_prompt` / `system_prompt_file` (README.md:506-518)
- Can the user append instructions beside Claude Code's prompt without breaking cache? — today: `system_prompt_mode = "append"` (splice.example.toml:453-457)
- Can the user replace Claude Code's own instructions on a head? — today: `system_prompt_mode = "replace"` (README.md:515-518)
- Can the user strip paragraphs out of Claude Code's system prompt? — today: `system_prompt_mode = "strip"` + patterns (splice.example.toml:462-476)
- Can the user set a prompt for one repository across every head? — today: `[projects."<root>"]` (README.md:520-527)
- Can the user set a prompt for one head in one project? — today: `[projects."<root>".heads.<key>]` (splice.example.toml:487-488)
- Can the user add instructions that only ride on compaction? — today: `[compaction]` rules (README.md:497-504)
- Can the user see which compaction rule applied to a session and why? — today: GET /api/compact, /api/compaction/instructions (TurnsMount.kt:47-52)
- Can the user turn code mode off for ChatGPT? — today: `code_mode = false` (README.md:531-538)
- Can the user choose which tiers get code mode? — today: `code_mode_models` (splice.example.toml:119)
- Can the user tune code mode's workers, timeout and heap? — today: `code_mode_*` (splice.example.toml:114-119)
- Can the user see why a code-mode script failed? — today: the `⚠ splice:` line (README.md:540-548)
- Can the user choose whether streamed reasoning is shown? — today: `show_reasoning`, `summary` (splice.example.toml:15-19)
- Can the user opt into replaying encrypted reasoning for cache warmth? — today: `CLAUDEX_REPLAY_REASONING=1` (README.md:633)

## Sessions and history
- Can the user list every Claude Code session and the head that launched it? — today: `splice sessions` / GET /api/sessions (SessionsCommand.kt:52-69)
- Can the user tell whether a session is live, stale or gone? — today: availability glyph (SessionsCommand.kt:91-95)
- Can the user get a SendMessage address for a live session? — today: the `send:` line (SessionsCommand.kt:97-105)
- Can the user resume a session on a different head? — today: `<head> -r <session-id>` (README.md:32)
- Can the user get the exact resume command for another head? — today: GET /api/sessions/{id}/resume?head= (ResumeRecipeRoute.kt:49-92)
- Can the user wall one head's transcripts off from the others? — today: `isolate = ["projects"]` (README.md:229-233)
- Can the user see who a session messaged and when? — today: GET /api/sessions/{id}/edges, /api/sessions/edges
- Can the user read a session's transcript? — today: GET /api/sessions/{id}/transcript
- Can the user browse the files a project touches? — today: GET /api/projects/{id}/files

## Watching and understanding turns
- Can the user see why something is broken, with the fix? — today: `splice doctor` (DoctorCommand.kt:63-98)
- Can the user get a shareable, redacted report? — today: `splice doctor --json [--with-logs] [--out FILE]`
- Can the user prove a head's tools and streaming really work? — today: `splice doctor --live`
- Can the user tail or follow the daemon log? — today: `splice logs [--head][--tail N][--follow]` (LogsCommand.kt:19-33)
- Can the user see the exact upstream request bodies a head sent? — today: `wireTap = N` + `splice wire <head>` (WireCommand.kt:48-54)
- Can the user get a head's full request/response trace? — today: `trace = true` + `splice trace <head> [--session][--turn]` (TraceCommand.kt:41-47)
- Can the user purge a head's trace? — today: `splice trace <head> --purge`
- Can the user see latency, outcome and cache stats per head? — today: `splice perf [--window]` / GET /api/perf/summary
- Can the user pull per-turn rows? — today: GET /api/perf/turns
- Can the user watch a live stream of daemon activity? — today: GET /api/events (SSE)
- Can the user see whether a turn is connecting, streaming or idle, and for how long? — today: GET /api/heads live-turn rows (CHANGELOG.md:769-774)
- Can the user learn their Claude Code is newer than splice was tested with? — today: version-drift warning (CHANGELOG.md:537-540)
- Can the user tell the running config is stale versus disk? — today: topologyStale in /health (splice-launch:427-432)

## Usage, limits and cost
- Can the user see 5h/7d plan usage inside Claude Code's status line? — today: usage headers (README.md:219-223)
- Can the user turn off background usage polling? — today: `CLAUDEX_QUOTA_POLL=off` (README.md:223)
- Can the user see an API-rate estimate on a subscription head? — today: `API est. $X` (README.md:184)
- Can the user set a daily budget per head, warn or block? — today: GET/PUT /api/budgets (CHANGELOG.md:239-243)
- Can the user get a webhook alert on a budget or limit? — today: /api/alerts (CHANGELOG.md:244-246)
- Can the user try a prompt against a head's credential outside a session? — today: POST /api/playground (CHANGELOG.md:246-249)
- Can the user see tokens and dollars per hour or per session? — today: GET /api/economics (README.md:407-408)
- Can the user be warned before hitting a plan's ceiling? — today: `usageWarnPct`, `usageWarnTokens5h` (splice.example.toml:805-806)

## Teams and sessions working together
- Can a ChatGPT session ask a Grok session for a review? — today: ListAgents + SendMessage (README.md:55,225-227)
- Can the user turn off cross-head discovery for a head? — today: `isolate = ["sessions"]` (README.md:233)
- Can the user compose a team of sessions on different heads? — today: POST /api/teams (CHANGELOG.md:207-219)
- Can the user see a team's hand-offs, activity and cost per role? — today: GET /api/teams/{id}[/economics|/activity|/chat|/edges]
- Can the user archive a team? — today: POST /api/teams/{id}/archive
- Can the user edit a slot's instructions? — today: PUT /api/teams/{id}/slots/{slot}/instructions
- Can a plain `claude` session hold a team slot? — today: yes, receive-only in the chat (README.md:235)

## Keeping it healthy and upgrading
- Can the user upgrade to the latest verified release in one command? — today: `splice upgrade [--to][--now]` (UpgradeCommand.kt:64-77)
- Can the user roll back? — today: `splice upgrade --rollback`
- Can the user upgrade without losing turns in flight? — today: waits for idle unless `--now`
- Can the user upgrade from the console? — today: POST /api/upgrade, /api/upgrade/run
- Can the user verify a release's integrity? — today: sha256sums + `gh attestation verify` (README.md:90-108)
- Can the user restart from the console with a safe drain? — today: POST /api/daemon/restart[?now=1]
- Can the user cross-check every model row against the providers at once? — today: `splice models --all`
- Can the user host one MCP server for every session? — today: shared MCP hosting (README.md:477-489)
- Can the user exclude one MCP server, or turn hosting off? — today: `mcp_hosting_exclude`, `mcp_hosting = false`
- Can the user see which MCP servers are hosted or excluded and why? — today: GET /api/mcp (README.md:491-495)
- Can the user open a dashboard without pasting the key? — today: `splice dashboard` (DashboardCommand.kt:31-64)
- Can the user name a different systemd unit? — today: `supervisorUnit` (splice.example.toml:813-822)

## Privacy and data on disk
- Can the user find out which files splice writes and what each holds? — today: README "What splice keeps on your disk" (README.md:359-436)
- Can the user be sure a session never holds the management key? — today: turn key (CHANGELOG.md:64-99)
- Can the user be sure a client-auth head never forwards splice's keys? — today: 401 refusal (CHANGELOG.md:85-87)
- Can the user delete a head's trace data now? — today: `splice trace <head> --purge`
- Can the user find out how long each kind of record is kept? — today: README retention columns (README.md:359-436)
- Can the user recover the copy of splice.toml from before a console change? — today: config-backups (README.md:400)
