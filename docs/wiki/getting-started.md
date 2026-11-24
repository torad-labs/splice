[README](../../README.md) · [Wiki Home](README.md)

# Getting started

Install Splice, connect a plan or an API key, and launch Claude Code on it. The installer checks your prerequisites, verifies what it downloads, and finishes with `splice doctor`; no GitHub account is needed.

## Requirements

**Platforms:** Linux and macOS natively; **Windows via WSL2** (run `wsl --install` in PowerShell once, then do everything below inside the WSL shell; it behaves exactly like Linux). Native Windows shells are refused by the installer with the same guidance: the launch shim and daemon are Unix programs.

On macOS, Splice identifies sessions from launcher-written process records; sessions launched outside Splice remain unknown. Hosted MCP servers have no memory cap or OOM protection on macOS. The per-turn write bound still runs on macOS, without the kernel acknowledgement count.

| Dependency | Why | If missing |
| --- | --- | --- |
| **Java 21+** | the spliced daemon ships as a fat jar | nothing to do: the installer takes any Java 21+ on the machine, and on a machine with none it downloads Eclipse Temurin's Java 21 runtime for Splice alone ([below](#java)) |
| **Claude Code** | Splice wraps it; `claude` must resolve on PATH | `curl -fsSL https://claude.ai/install.sh \| bash` (the native installer; no Node needed) |
| **curl** + **bash** | the installer, and the launch shim, which is plain `sh` and `curl` | preinstalled almost everywhere |
| **GitHub CLI** (optional) | signed in, it lets release installs and `splice upgrade` also verify each asset's build-provenance attestation | [cli.github.com](https://cli.github.com), then `gh auth login`; without it the install still verifies checksums and prints the command that checks provenance later |

You don't have to pre-check any of this: `install.sh` verifies every dependency up front and prints the exact fix for your machine's package manager. When you run `./install.sh` with both input and output attached to a terminal, it also offers to run each fix, with consent. `splice doctor` re-verifies everything at any time.

## Install a release

```bash
curl -fsSL https://splice.torad.ai/download/install.sh | bash
```

The installer puts the jar and the `splice-launch` shim in `~/.local/share/splice/`, links `splice` and your launch commands into `~/.local/bin` (make sure that is on your PATH), and runs `splice doctor`. A daemon that was already running stays on its old build until you restart it.

To pin one version instead of following `latest` (prereleases never become `latest`):

```bash
curl -fsSL https://splice.torad.ai/download/v0.4.0/install.sh \
  | env SPLICE_VERSION=v0.4.0 bash
```

### Java

The installer looks for Java 21 or newer: the `java` on your PATH, `JAVA_HOME`, macOS's `/usr/libexec/java_home`, and the folders JDKs install to (`/usr/lib/jvm`, `/Library/Java/JavaVirtualMachines`, Homebrew, SDKMAN, mise, asdf). On a machine with none, it downloads Eclipse Temurin's Java 21 runtime (about 50 MB, from Adoptium's releases on GitHub), checks it against the sha256 pinned in `install.sh`, and unpacks it into `~/.local/share/splice/runtime/`. Only Splice runs it: the Java the installer chose is linked at `~/.local/share/splice/java`, which the launcher runs, and your PATH and system Java stay as they are.

| Variable | Effect |
| --- | --- |
| `SPLICE_JAVA=/path/to/java` | use this Java |
| `SPLICE_NO_JAVA_DOWNLOAD=1` | stop and print the package command instead of downloading a runtime |
| `SPLICE_VERSION=vX.Y.Z` | install that release instead of `latest` |
| `SPLICE_SHARE_DIR`, `SPLICE_BIN_DIR` | install somewhere other than `~/.local/share/splice` and `~/.local/bin` |
| `SPLICE_DOWNLOAD_STALL_S` | seconds without progress before a download try counts as stalled (default 60) |

A checkout build (below) needs a full JDK and does not take the downloaded runtime.

### Verification

Releases are downloaded from splice.torad.ai/download. The downloaded jar and launch shim are checked against the release's `sha256sums.txt`, and any mismatch aborts the install. If the GitHub CLI is installed and signed in, the installer also verifies each asset's build-provenance attestation and refuses one that fails; if not, it prints the `gh attestation verify` command that does it later:

```bash
gh attestation verify ~/.local/share/splice/splice.jar --repo torad-labs/splice
gh attestation verify ~/.local/share/splice/splice-launch --repo torad-labs/splice
```

Before replacing anything, the installer runs the candidate jar's `version` and `shim-version` and requires the jar and launcher to match each other; if activation or linking fails, the previous jar and shim are put back.

### From source

```bash
git clone https://github.com/torad-labs/splice.git
cd splice
./install.sh
```

From a checkout, `install.sh` builds the fat jar instead of downloading one.

### Let your agent do it

Give this prompt to any coding agent with shell access:

```text
Install splice (https://github.com/torad-labs/splice) on this machine and verify it works:
1. Check prerequisites: bash, curl, Java 21+, and Claude Code
   (`claude` on PATH). Install anything missing with this machine's package manager:
   show me each install command and ask before running it.
2. Install from source: `git clone https://github.com/torad-labs/splice && cd splice
   && ./install.sh` (or use the release one-liner from the README instead).
3. Make sure ~/.local/bin is on my PATH (add it to my shell rc if not).
4. Ask me for an OpenRouter API key (I can create one at https://openrouter.ai/keys),
   store it with `splice key set OPENROUTER_API_KEY --stdin`, without printing it, then run
   `splice add openrouter --yes`. The key store also works with a systemd-managed daemon.
5. Run `splice doctor` and fix anything it flags. Every failing check prints its own
   fix command. Repeat until it reports no blockers.
6. Tell me it's ready and that `claude-openrouter` launches Claude Code through OpenRouter.
```

The agent can drive that loop for the same reason you can: `splice doctor` prints the fix for every failing check.

## Guided setup

```bash
splice setup
```

`splice setup` is what the installer tells you to run next. It says what it finds on the machine (the sign-ins and keys it can use, Claude Desktop and the mode Splice has it in, `agy`, `codex`, and a managed `processWrapper`), then asks which plans to connect (a ChatGPT, Grok, Kimi or Muse sign-in, a DeepSeek or OpenRouter key). Each plan's models run in `claude` through the Splice gateway (`/model`); custom head commands are deprecated, so setup keeps none for the plans it connects (`splice claude restore <name>` brings one back).

Your own `claude` and Claude Desktop stay on your personal account: setup offers the side-by-side `claude-splice` launcher and, where Claude Desktop is installed, a `Claude (Splice)` app launcher, and names `splice desktop on` for putting all of Claude Desktop on Splice instead. Claude's models on your own Claude plan run on a head of their own, which is deprecated, added with `splice add claude`.

It also asks whether sessions share MCP servers, which Modes to turn on, whether to install new releases automatically (`[daemon] auto_update`), and, on a Linux machine with an NVIDIA card, whether to run a local model. It shows a summary and writes nothing until you confirm; without a terminal it takes every default. It ends on `splice doctor`, with or without a plan.

After an upgrade, `splice setup --update` asks only about what the new release adds that this install does not have yet. The sections below are the same steps by hand.

## Choose how you pay

| Path | What you need | Command | Billed as |
| --- | --- | --- | --- |
| API key | a vendor key (OpenRouter, DeepSeek, Moonshot, any OpenAI-compatible endpoint) | `splice add openrouter` (or `deepseek`, `api-key`) | pay per token on that key |
| Subscription | a ChatGPT, Grok, Kimi or Muse plan | `splice add codex`, `grok`, `kimi` or `muse` | your plan's allowance |
| Claude login | your Claude plan, on the deprecated `claude` head | `splice add claude` | your Claude plan |
| Local model | Ollama, LM Studio, vLLM or similar on this machine | `splice add local --name <key>` | your GPU |

### API-key starter: OpenRouter

This is the zero-config provider path. It uses a paid API key, not a subscription allowance.

```bash
export OPENROUTER_API_KEY="…"     # vendor-issued pay-per-token API key
splice add openrouter             # sign in, add the head and install its command
claude-openrouter                 # launch it; splice status shows its loopback port
```

The daemon offers every text-producing, tool-capable model OpenRouter serves, read at start and every hour after. `splice add openrouter` writes `model_slots` to keep the opus, sonnet, haiku and fable tier mappings without restricting the picker, and ten `[[providers.openrouter.models]]` rows. Each model keeps its own window and price. Add or edit a row when you want your own label, window or rates.

`models` remains an explicit serving allowlist. Existing heads keep theirs until you edit it. To keep a discovered roster while choosing tiers, use `model_slots` under the existing head's table instead of a slotted `models` list:

```toml
[heads.example]
provider = "example"
port = 3105
discovery_prefix = "claude-example--"
pinned_model = "vendor/model-a"
model_slots = { opus = "vendor/model-a", sonnet = "vendor/model-b" }
```

Use your existing head key and fields in place of the example. Slot names are opus, sonnet, haiku and fable, with one model per slot. One model may fill several slots, as in `model_slots = { opus = "gpt-6.1-sol", sonnet = "gpt-6.1-sol" }`; the picker still lists it once. A `models` list names each model once, so put one model behind two tiers with `model_slots`. Do not combine `model_slots` with slots inside `models`. An unslotted `models` allowlist can have a separate tier map, but its tier ids must be allowed. A mapped model the endpoint does not list stays unmapped, with a boot-log explanation, rather than selecting another model. Restart after changing tier mappings.

Each of the ten rows carries the rate card read on September 29, 2026, and keeps it until you edit the row. A model the daemon discovers with no row is priced from the card OpenRouter lists for it, read when the daemon starts and every hour after. Either way its turns show a dollar figure on the status line and count against a budget. A model OpenRouter lists no price for reads `no rate card`. A `rates = { input = …, cache_read = …, output = … }` line on the row wins over the listed price.

No export handy? There are two other ways to get the key in. Both land in `~/.config/splice/keys.toml` (0600), which the daemon reads on every request, so a saved key needs no restart:

- `claude-openrouter login`: a masked terminal prompt (the key never hits shell history, `ps`, or a session transcript).
- Inside a `claude-openrouter` session, while the key is missing, Splice offers to capture it: paste the key as a **bare message** (nothing else in the text) and it is stored and blocked before it reaches the model. It never travels upstream. The session transcript still records the paste, so the masked `claude-openrouter login` stays the zero-trace path.

An explicit `OPENROUTER_API_KEY` in the daemon's environment always wins over the store. `splice key set|list|unset` manages the store directly (`--stdin` for scripts):

```bash
splice key set OPENROUTER_API_KEY --stdin < key.txt
splice key list
```

### Subscription setup: ChatGPT, Grok, Kimi or Muse

Run `splice add codex`, `splice add grok`, `splice add kimi` or `splice add muse`. It adds the provider and head, chooses a free port, signs you in, links the command and offers to restart the daemon. Then launch `claudex`, `claude-grok`, `claude-kimi` or `claude-muse`.

```bash
splice add codex      # ChatGPT sign-in; launches as claudex
claudex
```

If the daemon is already running, finish pending work before a full `splice restart` to load topology changes; a `context_window` edit needs none (see [Long-session reliability](architecture-and-troubleshooting.md#long-session-reliability)). A head restart alone does not reload TOML. Splice keeps its own credentials; you don't need to share the vendor CLI's credential file. See [Credential locations](providers-and-accounts.md#credential-locations).

### Claude login

`splice add claude` adds the deprecated `claude` head, which runs Claude Code over its own `~/.claude` with your Claude login. There is one Claude Code login, not one per command; sign in with `/login`. Account pools and the head-versus-door choice are covered in [Providers and accounts](providers-and-accounts.md) and [Door and launchers](door-and-launchers.md).

## Launch

| Command | What it starts |
| --- | --- |
| `claude` | plain Claude Code; on Splice's door when you chose that in setup or with `splice claude door` |
| `splice launch claude [--profile <name>] [args...]` | Claude Code through the Splice gateway, side by side with your usual `claude`, which stays as it is (the installer also links this as `claude-splice`) |
| `splice launch claude-desktop [args...]` | Claude Desktop through the gateway in an isolated profile, separate from your normal Desktop |
| `claudex`, `claude-grok`, `claude-openrouter`, … | a provider head's own command (deprecated; kept for heads you already have) |

Inside a session, `/model` lists every model the gateway serves. `splice status` shows the daemon, heads, ports and accounts.

### The billing word in Claude Code's header

Claude Code's header prints a billing word beside the model. On `claude` signed in with a Claude subscription, it names that plan, for example `Claude Max`. On every other head it reads `API Usage Billing`: Claude Code only has names for Anthropic's plans and the clouds that sell Claude, so it prints that for any other service. It does not mean Anthropic is billing you. Each head uses the plan, key or GPU you set it up with, and Splice's status line shows an estimate at API rates, never a charge, where Splice has a rate card for the model.

## Check the install

```bash
splice doctor
```

`splice doctor` checks prerequisites, install integrity, config, daemon and auth, and prints the exact fix under every failing check. Run it whenever something looks wrong; [Troubleshooting](architecture-and-troubleshooting.md#troubleshooting) covers `--json` reports and common problems.

## Upgrade

`splice upgrade` fetches, verifies and activates the latest release, then restarts the daemon. Its options, waits and rollback are in [Upgrading to 0.7.0](upgrading-to-0.7.0.md).

## Uninstall and reinstall

```bash
splice uninstall          # same as splice uninstall --all
splice uninstall claudex  # one head's command only
```

`splice uninstall` removes every command link Splice made in `~/.local/bin` (your own `claude` stays), and on Linux stops, disables and removes Splice's systemd user units. It also takes Splice's `/login` out of `~/.claude` and puts Claude Desktop back as it was before `splice desktop on`. It leaves the jar, your topology, keys, sign-ins and state on disk, so reinstalling is the install one-liner again (or `splice install` to re-link the commands of an install still on disk) and everything comes back as it was. To remove the data too, see [Clean uninstall](disk-and-privacy.md#clean-uninstall).

## Related pages

- [Upgrading to 0.7.0](upgrading-to-0.7.0.md)
- [Providers and accounts](providers-and-accounts.md)
- [Door and launchers](door-and-launchers.md)
- [Claude Desktop](claude-desktop.md)
- [CLI reference](cli-reference.md)
- [Configuration reference](configuration-reference.md)
- [Disk and privacy](disk-and-privacy.md)
- [Architecture and troubleshooting](architecture-and-troubleshooting.md)
