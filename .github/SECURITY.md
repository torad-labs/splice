# Security Policy

## Supported Versions

Security fixes ship in the next release of the latest minor line (0.4.x today). Older lines
get none, and there are no maintained release branches. Update to the latest release
(`splice upgrade`) before reporting.

## Threat model

splice is a single-user daemon, and its security boundary is your Unix account.

- **Everything listens on loopback.** The control plane and every head bind `127.0.0.1` and
  refuse a request whose `Host` is not `127.0.0.1`, `localhost` or `[::1]`, which closes DNS
  rebinding from a browser page.
- **Two keys.** The management key (`<state>/mgmt-key`, 0600) opens the whole control plane. A
  launched session holds only a turn key, derived one-way from it. The turn key opens that
  install's head turns, status-line routes and resume hooks, but no management route.
- **State is owner-only.** The state and log directories are 0700, and credentials are written
  0600 from the instant they exist.
- **Out of scope:** a process running as your own user. It can read `mgmt-key`, as it can read
  your other credentials. So can anyone who can already run code as you, including a model
  whose tools you let run commands. In scope: a key reaching a transcript, an argv or page
  another account or origin can read, or another account on the machine getting past the
  control plane's key.

## Reporting a Vulnerability

Report privately via **GitHub Security Advisories**:
https://github.com/torad-labs/splice/security/advisories/new

Do not open a public issue for a suspected vulnerability.

**Do not attach tokens, API keys, `~/.codex/auth.json`, or live request/response logs to a
report.** splice proxies a live ChatGPT Codex / xAI session; captured traffic can contain
bearer tokens and conversation content. Redact secrets and trim logs to the minimal
reproducing snippet before attaching anything.

## Response Expectations

This is a personal project maintained best-effort, not a funded security team. There is no
SLA. Reports are triaged as time allows; issues touching auth bypass, the loopback bind
contract, or secret leakage get priority.

## Reasoning-cache retention

Within-turn `reasoning_cache` is on by default for ChatGPT, OpenAI Responses and Muse heads.
The daemon stores one JSONL file per conversation under `<state>/heads/<head>/reasoning/`,
in a 0700 directory with 0600 files. Each envelope contains the provider's encrypted reasoning
and any readable summary the provider supplied. The summary defaults to detailed where supported.
The envelope is base64-encoded JSON; base64 does not encrypt the readable summary.

The keyed cache survives a daemon restart and has no inactivity expiry. A conversation is removed
when it compacts, when the provider rejects its reasoning as stale, or when the head exceeds
8192 rounds or 64 MB across conversations, least recently used conversation first. Disabling
`quirks = { reasoning_cache = false }` purges it on the next head assembly; removing the head
purges its directory at daemon start. Entries are keyed by conversation. Cross-turn replay of
earlier turns' reasoning is a separate setting and defaults off.

Code mode is separate from this cache: its records keep the model's reasoning summaries in
plaintext on disk until 24 hours after the record's last use, checked every 5 minutes whether or not
the head is used again. [What splice keeps on your disk](../README.md#what-splice-keeps-on-your-disk)
lists every file splice writes, what it holds and how long it stays.
