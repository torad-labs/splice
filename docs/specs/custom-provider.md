# Custom providers

A team can add a provider splice does not ship a vendor module for — its own gateway, a self-hosted model server, an
internal proxy in front of a lab — by writing a `[providers.<key>]` table in splice.toml like any other, with a
handful of keys this spec documents. A team that publishes its own table as a file can hand it to anyone who runs
`splice add provider <file|https-url>` instead of hand-editing TOML.

## splice.toml

Each key below lives on an existing `[providers.<key>]` table (the `dialect`, `base_url` and `auth` it already
takes), or on `[[providers.<key>.models]]`, or on its own `[door.commands.<name>]`. A value splice cannot act on is a
finding, listed with every other boot finding, never a silent decode (repo CLAUDE.md, "Boot fails closed").

- `[providers.<key>].auth = { kind = "command", credential_command = "<shell command>", login_command = "<shell
  command>" }`. A fourth auth kind, beside `api-key` and the registered OAuth ones: no file and no OAuth flow splice
  runs, but a team's own command that prints the current key. `credential_command` is required with this kind and
  refused on any other; it runs with `/bin/sh -c`, the daemon's own environment (splice adds nothing it holds) and
  closed stdin, and must print one JSON object `{"token": "<key>", "expires_at": "<RFC 3339>"}` (`expires_at`
  optional) or one bare non-empty line, the key with no expiry. It runs when no key is held, when the held one
  expires within five minutes, and once after a 401; the key it yields rides where an api-key's rides and is held in
  memory only, never written to a file, a log line or splice's state. `login_command` is optional, runs only with
  this kind, and is the interactive sign-in a person runs through `splice login <head>` or the accounts page's Sign
  in — a browser or device flow, attached to the terminal for the first and detached (stdout/stderr read for the
  first `https://` address, which becomes the sign-in's `browser_url`) for the second.
- `[providers.<key>].ca_file = "<path>"`: a PEM bundle of one or more certificate authorities this provider's server
  is signed by, trusted on top of Java's own roots and the OS trust store. `~` expands; a relative path resolves
  against splice.toml's own folder. A file that is missing, unreadable or holds no PEM certificate is a finding
  naming `providers.<key>.ca_file`, never a boot refusal — the shared roots still work meanwhile. This is for a
  provider reached through a company's TLS-inspecting proxy, whose certificate is signed by a root nothing but the
  company's own machines know.
- `[providers.<key>].error_messages = "whole" | "filtered"` (absent = `"filtered"`, today's rule: a provider's error
  is cut to its first readable sentence, 160 characters, with ids, keys and paths dropped). `"whole"` shows the
  provider's own words as it wrote them, up to 2,000 characters, with its explanation and any URL kept rather than
  cut to one sentence — a team's gateway may want its own words, in full, to reach its people. Ids, keys, masked
  credentials and deeply-dotted field paths are STILL dropped in whole mode: asking for a provider's own words never
  means a credential it echoes back can reach a line splice writes or a diagnostic splice records. Any other string
  is a finding.
- `[providers.<key>].picker = true | false` (absent = the door's own rule; a head's own `picker` wins over its
  provider's). `true` lists every model the provider's endpoint lists beside the ones splice.toml declares for it
  (declared first, then the listed ones not declared, in the endpoint's order), rather than only the declared rows —
  useful for a local runtime whose declared rows are a small allowlist but whose person wants the picker to show
  everything the runtime actually serves.
- `[providers.<key>].served_window = { url = "<http(s) URL>", field = "<dotted JSON path>" }`: where this provider
  reports the window it really serves, read once at the first catalog build after a daemon start (or restart). A
  picked model's window is then the real ceiling the provider answered, not only the declared `context_window` —
  the same idea as the `/props` probe a local runtime already gets, written out for any provider. `url` must be an
  absolute `http://` or `https://` address and `field` must name the dotted path to a positive integer in the
  answer; either missing is a finding. A read that fails (unreachable, a non-2xx answer, no JSON, no number at that
  path) never refuses a turn: it is said in the daemon's log and under `splice doctor`, and the declared window
  stays in force.
- `[[providers.<key>.models]].max_output_tokens = <positive integer>` (absent = no cap, the turn's own
  `max_tokens` rides unchanged): the most this model is ever asked to write in one turn. A turn asking for more is
  asked for this instead, in whichever field the dialect sends it in; a turn asking for less is unchanged. For a
  self-hosted model whose server refuses a request for more tokens than it can actually generate. `0` or a negative
  number is a finding.
- `[door.commands.<name>] = { head = "<head key>", models = "head" | "door" }` (`models` absent = `"head"`): a
  command a wrapper starts Claude Code on the door through, pinned to `head`'s own model and, by default, its own
  rows in `/model`; `models = "door"` lists the door's whole picker instead. `head` must name a `[heads.*]` table
  whose provider does not forward the caller's own Claude login (that login is never on the door). The command's
  name may not be `claude` (Claude Code's own) or a name a `[heads.*]` table already answers to.

## `splice add provider`

```
splice add provider <file|https-url> [--check]
```

Installs a team's published provider definition — a file in splice.toml's own TOML grammar holding exactly one
`[providers.<key>]` table, its `[[providers.<key>.models]]`, the `[heads.*]` on that provider and any
`[door.commands.*]` on those heads, nothing else. A head in the definition may leave out `port` and
`discovery_prefix`: splice fills them in (a free port above the highest declared one, `claude-<head>--` for the
prefix) before checking it, so the definition decodes exactly as a hand-written splice.toml would. Installing a
provider already installed replaces it whole — its table, its heads (one of the same key keeps its port and prefix)
and its door commands — and never touches another provider's.

The definition is checked exactly as boot checks splice.toml: every finding (its own shape, the merged file's cross
checks, a local runtime's own answers) is listed at once and nothing is written. `--check` stops there either way,
installing nothing; without it, a good definition is written with `TopologyWriter` (comments and order kept) and a
running daemon is restarted, the way `splice add` already restarts it.

**A definition is read over https from any host, or over plain http only from this machine** (127.0.0.1, `::1`,
`localhost`) — never in the clear across a network, since it names the commands splice runs to sign a person in. A
read is capped at 15 seconds and 1 MiB: a definition is a page of TOML, never more.

**Installing a team's definition trusts its `credential_command` and `login_command` as much as splice.toml's own
head ever trusts a launch command: splice runs them with `/bin/sh -c`, the install's own environment, and no sandbox
of any kind.** Before running `splice add provider` on a definition, read it the way you would read a shell script
someone handed you — splice checks that its *shape* is a provider definition and nothing else, never what its
commands do. A malicious `credential_command` reads and prints anything the installing person's own shell can; it
runs in the daemon's process, not a browser tab or a sandboxed helper.

## Diagnostics

`splice doctor` names each provider on `kind = "command"` under its accounts section: signed in (until when), not
signed in, or failed in the credential command's own words, with how to sign in (`splice login <head>`, or `/login`
in a `claude` session); under it, one line per head naming whether it is in `/model` and the last error its provider
answered, in the provider's own words as a line would quote them (never a credential — the same redaction rule
`error_messages = "whole"` keeps). It reads the daemon's own `splice claude --json` state, so the two commands never
disagree about one head.

`splice doctor` also reports a `served_window` read the same way the daemon reads it: the window the provider
answered, or why the read failed and that the declared window is in force.
