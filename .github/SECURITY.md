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
  session's turns, its statusline and its resume hook.
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

With `reasoning_cache` enabled (the default for the codex provider), the daemon holds each
turn's `reasoning.encrypted_content` envelopes in memory so tool round-trips can replay them.
Retention is **activity-based** (changed 2026-07-31; it was previously a fixed 30-minute cap):
a conversation's envelopes are kept while that conversation stays active and expire wholesale
after 30 minutes of inactivity — a session in continuous use retains its envelopes for the
session's lifetime. Hard caps bound the worst case: 256 rounds / 64 MB across all conversations
on a head, enforced by whole-conversation eviction. The envelopes are opaque ciphertext (the
upstream holds the keys); plaintext reasoning is never retained. They are process-memory only —
never written to disk — and vanish on restart. Entries are scoped to their conversation (a stable
first-message key), so concurrent conversations sharing one head can never receive each other's
envelopes; staleness eviction is deliberately unscoped, which can only over-evict (a cache miss),
never cross-inject. Set `quirks = { reasoning_cache = false }` to disable.
