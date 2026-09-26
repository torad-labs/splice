# Setup wizards — PostHog, torad-toolkit, and splice

- Created: 2026-09-15
- Read: `@posthog/wizard` (GitHub README + AGENTS.md + `bin.ts`, PostHog docs and handbook),
  `torad-toolkit` at `~/Documents/dev/projects/torad/torad-toolkit/torad-toolkit-main`
  (`src/cli/commands/init.ts`, `doctor.ts`, `detect.ts`, `package.json`),
  splice `SetupCommand.kt` / `AddCommand.kt` / `AdminSupport.kt` / `AddSeams.kt` / `CliStyle.kt`
  at `feat/v0.4.0` `7fa98534`
- Why: Marcos rates the PostHog wizard first-class and torad-toolkit pretty good, and asked how
  splice compares. Feeds the telemetry consent step
  (`.dev/campaigns/telemetry/FEATURES.md` §6) and 0.4.0's guided-provider-setup feature
  (`.dev/campaigns/v0.4.0/FEATURES.md` §1).

## The headline

**splice's wizard is not behind on taste. It is behind on widgets.**

PostHog and torad-toolkit both get their polish from the same npm library —
[`@clack/prompts`](https://www.npmjs.com/package/@clack/prompts) — which hands them `select`,
`multiselect`, `spinner`, `note`, `intro`/`outro` and cancel handling for free. splice is a JVM
CLI with **zero prompt or TUI dependencies**: `CliStyle.kt` is seven ANSI constants, and the only
input primitive in the tree is `readlnOrNull()` behind a y/n wrapper.

So the comparison is not "they have better designers". It is that two of the three are standing on
a widget library and one is hand-rolling escape codes. Everything in the gap table below follows
from that one fact, and it is the thing to fix first.

## At a glance

| | **PostHog wizard** | **torad-toolkit** | **splice setup** |
|---|---|---|---|
| Invocation | `npx @posthog/wizard` | `torad init` | `splice setup` |
| Runtime | Node ≥22.22 | Node | JVM 21 |
| Size | ~20k lines + 3 sibling repos | one repo, ~17 commands | one class, ~90 lines |
| Prompt layer | `@clack/prompts` | `@clack/prompts`, `@inquirer/prompts`, `chalk`, `ora` | hand-rolled ANSI + `readlnOrNull` |
| What it does | AI agent edits your codebase | materializes `.claude/` from a manifest | writes topology, links wrappers, runs logins |
| Detection | framework (Next/React/Svelte/Astro/RN/TanStack) | TypeScript / Kotlin / Android XR / frameworks | none — one fixed starter |
| Preflight | upstream health checks + Node version | — | — |
| Summary before writing | — (agent-driven) | ✅ `p.note(…, 'Summary')` then confirm | — |
| Spinner | ✅ | ✅ `p.spinner()` | — |
| Non-interactive | ✅ `--ci`, `--signup`, `provision --json` | ✅ `--json`, `--dry-run` | implicit only |
| Dry run | — | ✅ | — |
| Drift repair | `wizard doctor` | ✅ `torad doctor --fix` | ✅ `splice doctor` |
| Interruption recovery | ✅ orphaned-backup healing at startup | ✅ orphaned rollback backups + `sync` | — |
| Secret handling | ✅ session secret vault, agent never sees raw | n/a | ✅ per-head credential files, never printed |
| Next-steps block on exit | ✅ | ✅ | ✅ |
| Self-instrumented | ✅ (wizard reports its own usage to PostHog) | — | — |

## 1. PostHog wizard

**What it is.** An agentic CLI that wraps the **Claude Agent SDK** and edits your project for you.
`npx @posthog/wizard` authenticates your account, installs the SDKs, scans your codebase, writes
the initialization code, instruments a starter set of events based on your real product flows,
writes `.env`, generates dashboards in the PostHog app, and optionally installs the PostHog MCP
server. It is an onboarding *agent*, not a form.

**The command surface is a family, not a script.** `wizard` (default = integrate), `self-driving`,
`audit <area>`, `revenue-analytics`, `mcp-analytics`, `migrate`, `upload-source-maps`,
`mcp add|remove|tutorial`, `slack add`, `skill <name>`, `doctor`, `warehouse`.

**Four things worth stealing, in order of value to splice:**

1. **Preflight health checks that can refuse to run.** `evaluateWizardReadiness()` checks the
   Statuspage APIs of Anthropic, PostHog, GitHub and Cloudflare plus direct liveness endpoints, and
   returns `yes` / `yes_with_warnings` / `no`. A `downBlocksRun` list and a `degradedBlocksRun` list
   decide. **A wizard that knows it cannot succeed says so before touching anything**, instead of
   failing halfway through and leaving a half-configured project.

2. **A runtime preflight above the imports.** `bin.ts` checks the Node version *before* importing
   anything, with the comment that this is "the only thing standing between an old Node runtime and
   a cryptic dependency crash". Fail on your own terms with your own message, not on a stack trace
   from a transitive dependency.

3. **The secret vault.** `src/lib/secret-vault.ts` is a session-scoped in-memory vault: a tool that
   handles a secret calls `put()` and hands the agent an opaque `secret:<ref>` instead. The agent
   passes the ref around as if it were the value, and the host resolves it to the real secret only
   at the last moment, in-process, when writing the file. **The AI never sees the credential.**

4. **Interruption healing at startup.** Before anything reads Claude settings,
   `recoverOrphanedSettingsBackups()` repairs a backup that a previous interrupted run left behind.
   Crash recovery runs first, not as an afterthought.

**The architectural discipline is the best part, and splice already half-has it.** From their
`AGENTS.md`: *"product knowledge never enters infrastructure code."* The runner pipeline, the TUI
store, the detection loop and the prompt assembler are machinery that do not know what PostHog is.
Knowledge lives in typed configuration surfaces — frameworks in `FrameworkConfig`, integration
know-how as markdown skills in a separate `context-mill` repo, security policy as YARA rules in a
separate `warlock` repo, programs as step arrays. They credit this for keeping the wizard at ~20k
lines: *"boundaries prevent damage from propagating between concerns."*

splice's `AuthKindRegistry` and `ApiKeyProviderRegistry` are exactly this pattern — registries as
the typed surface, with comments saying consumers must take their denominator from the registry
rather than keep a second list. **splice is closer to PostHog's architecture than to its UI.**

## 2. torad-toolkit

**What it is.** `torad init` reads a manifest and materializes `.claude/` — skills, agents,
plugins, commands, MCP configs, settings — from a preset, recording what it installed in
`torad.json` so `sync`, `doctor` and `undo` can reason about it later.

**The flow, which is the cleanest of the three and the cheapest for splice to copy:**

1. `p.intro(chalk.bgCyan(chalk.black(' torad init ')))` — an inverse-video badge, not a bare line.
2. Detect the project (`tsconfig.json`, `build.gradle.kts`, lockfiles, `package.json` deps) and
   print `Detected: Kotlin, TypeScript` dimmed.
3. **Pre-select the preset from what was detected** — Kotlin or Android XR suggests `full`,
   TypeScript suggests `standard`. The user confirms a good guess instead of answering a blank.
4. `p.group({...})` of prompts, so cancel is handled once for the whole group rather than per
   question. The preset `select` carries **counts in the hints**, computed at runtime by
   `countPackContents` and rendered as `Core + TypeScript (<n> skills, <n> agents)` — so the choice
   is made on numbers, not adjectives.
5. `p.note(summary, 'Summary')` — packs, skill count, agent count, vendor mode, target path.
6. `p.confirm({ message: 'Install now?', initialValue: true })`.
7. `p.spinner()` around the actual work, stopping with a completed-count message.
8. `p.note(next steps, 'Next steps')` — four commands with aligned descriptions.
9. `p.outro(chalk.green('Toolkit ready!'))`.

**Also strong:** `--dry-run` and `--json` are first-class, not afterthoughts. Re-running on an
initialized project prints a yellow line and three next-command hints rather than an error. An
existing unmanaged `.claude/skills/` triggers a merge/replace/cancel `select` instead of silently
clobbering. `doctor --fix` auto-repairs drift and cleans orphaned rollback backups. Every failure
line names its fix: *"⚠ ast-grep CLI not installed — run `npm i -g @ast-grep/cli`"*.

**Where it is weaker than PostHog:** no upstream preflight, no interruption healing at process
start (it heals backups during `sync`, not before the first read), and no non-TTY story beyond
`--json`.

## 3. splice setup

**The flow today** (`SetupCommand.kt:20`):

1. Bold title, dim subtitle naming the OpenRouter starter.
2. `installCommand.init()` → `install("--all")` → `installSelf()`. No summary, no confirmation, no
   spinner; the work simply happens.
3. For each unauthenticated OAuth head: a dim paragraph warning that subscription heads reuse each
   vendor CLI's public OAuth client identity and are unofficial, then
   `AdminSupport.confirm("Sign in to <command> now?", default = true)` per head. Declining prints
   `skipped — sign in later with: <command> login`.
4. `You're set.`
5. An aligned next-steps block: `Launch`, `Dashboard`, `Status`, `Checkup`, commands in cyan,
   `Checkup` carrying `— anything wrong prints its fix`.

**What is genuinely good, and should not be lost in any redesign:**

- **The house style is coherent and already matches the other two's information design**: bold
  title with a dim `—` subtitle, two-space indent, coloured glyph (`✓` green, `✗` red, `!` yellow),
  label padded to a fixed width, then detail. Commands always cyan.
- **Every failure line names its fix.** Same discipline as torad's doctor.
- **The closing next-steps block is as good as either competitor's** — aligned labels, real
  commands, one dim explanatory tail. This is the part that most needs copying and splice already
  has it.
- **Credential hygiene is ahead of both.** Every OAuth head owns its own file under
  `~/.config/splice/auth/`, never the vendor CLI's; secrets are reported by presence, never value.
  PostHog needed a purpose-built secret vault to reach a comparable place because an AI agent is in
  the loop; splice gets there structurally because nothing untrusted is.
- **`splice doctor` is a real diagnostic**, with `--json` landing in 0.4.0.
- **Headless already degrades correctly.** `System.console() == null` returns the default, so
  Docker and CI neither hang nor prompt.

**The honest gaps:**

- **No selection primitive at all.** `AdminSupport.confirm` is y/n; there is no `select` or
  `multiselect` anywhere in the tree. This is why `setup` can only materialize one fixed starter
  and why 0.4.0's `splice add <profile>` takes the profile as an *argument* rather than offering a
  menu — the menu does not exist to offer.
- **No summary and no confirmation before writing.** `setup` modifies the topology, links wrappers
  and installs the `splice` command with no "here is what I am about to do". torad asks; splice does
  not.
- **No progress feedback.** Installing and linking is silent until it is done.
- **No preflight.** `doctor` exists but `setup` does not run it, so a missing Java, an unreachable
  provider or an occupied port is discovered by failing rather than by checking.
- **No `--dry-run`, no `--json` on setup**, and the non-interactive behaviour is a *consequence* of
  the null-console check rather than a designed `--ci` mode with documented defaults.
- **No interruption recovery.** A `setup` killed midway leaves whatever it had written.
- **Detection is absent.** splice knows a great deal about the machine it is on — which vendor CLIs
  are installed, which credential files exist, which env keys are set, whether a daemon is running
  — and uses none of it to pre-select anything.

## What splice should take, ranked by value over cost

1. **A minimal prompt toolkit — the unblocking change.** Four primitives cover everything both
   competitors do: `select`, `multiselect`, `note` (a titled box), and `spinner`. Either adopt
   [Mordant](https://github.com/ajalt/mordant) (Kotlin, by the Clikt author, does colours, tables,
   progress and widgets) or hand-roll ~200 lines against `CliStyle.kt`. **Everything else on this
   list is blocked behind it.** Given the repo's standing "no new dependencies" instinct on the
   transport side, hand-rolling four widgets is the more in-character choice and is a day of work.
2. **Detect, then pre-select.** Copy torad's step 2–3 exactly: look for installed vendor CLIs,
   existing credential files, `OPENROUTER_API_KEY`, a running daemon; print one dim `Detected:` line;
   default the choice to the answer. Confirming a good guess beats answering a blank prompt.
3. **Summary → confirm → spinner.** torad's cheapest, highest-value pattern, and it directly fixes
   "setup rewrites my topology with no warning".
4. **Counts in the hints.** `Claudex (ChatGPT) — 4 models, code mode` reads better than `Claudex`.
5. **A real preflight.** Run the cheap half of `doctor` before touching anything, and refuse with a
   named reason when it cannot succeed. PostHog's three-state `yes / yes_with_warnings / no` is the
   right shape — degraded should warn, not block.
6. **A designed `--ci` with documented defaults**, replacing the implicit null-console behaviour, so
   the Docker e2e exercises the same path a user's CI would.
7. **Interruption healing at startup**, once `setup` writes more than it does today.

## What splice should not take

- **The agentic model.** PostHog's wizard edits your codebase with an LLM. splice configures its
  own daemon; there is nothing to infer and nothing to write into the user's source. An agent here
  would add a failure mode and a trust question for no benefit.
- **The scale.** ~20k lines across four repos (`wizard`, `context-mill`, `warlock`,
  `wizard-workbench`) with upstream Statuspage dependencies is a product team's machinery. Take the
  *patterns* — preflight, typed surfaces, secret vault — not the architecture.
- **Self-instrumentation before the telemetry campaign lands.** The PostHog wizard reports its own
  usage to PostHog. That is a reasonable thing to want for `splice setup` eventually, and it must
  ride the channel and consent design in `.dev/campaigns/telemetry/FEATURES.md`, not a second path
  invented for the wizard.
- **`npx`-style remote execution.** splice is an installed binary with checksum and attestation
  verification; a curl-to-shell equivalent would undo a guarantee the project already paid for.

## Where this feeds

- **Telemetry consent step** (`.dev/campaigns/telemetry/FEATURES.md` §6) — the proposed step is
  written against `AdminSupport.confirm`, which is correct today. If item 1 lands, the same step
  should use `note` for the disclosure block rather than raw `println`, so the payload summary reads
  as a titled box like torad's `Summary`.
- **Guided provider setup** (`.dev/campaigns/v0.4.0/FEATURES.md` §1) — that section's resolved
  decision is `splice add <profile>` with the profile as an argument. Item 1 is what would let it
  be a menu instead, and items 2–4 are what would make it feel like `torad init`. Worth reopening
  that decision if the prompt toolkit lands first.
