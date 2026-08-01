# Security Policy

## Supported Versions

splice is pre-1.0 (see `CONTRIBUTING.md` for the versioning policy). Only `main` is
supported — there are no maintained release branches. Update to latest before reporting.

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
