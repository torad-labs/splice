# secret-scan-allow.txt

Exemptions for the org secret-scan's supplementary pattern pass.

**The documentation lives here, not in the file itself.** The scan applies the allowlist as
`grep -vEf`, and `grep -f` has no comment syntax — so every line of that file is a live regex,
prose included. Three separate ways a "comment" has broken scanning, all in PR #81:

| # | Hazard | Effect |
|---|--------|--------|
| 1 | Unanchored entry | Appending a credential to the allowed declaration bypassed the scan entirely. |
| 2 | A bare `#` separator | An unanchored regex matching any hit *containing* `#` — silently exempted every credential in a shell/Python/YAML/TOML comment, repo-wide. |
| 3 | Unbalanced `(` in prose | Made the whole file an **invalid** pattern set, so `grep -vEf` errors instead of filtering. |

None was caught by review. All three are caught by a planted canary in under a second, which is
why the file is now patterns-only and verified mechanically.

## Rules

1. **No blank lines.** An empty regex matches every hit and disables the scan while CI stays green.
2. **Every line anchored.** Prose lines start `^#`; exemptions are anchored `^`…`$`. A hit always
   arrives from `grep -nIE` as `<line>:<content>`, so it begins with a **digit** — a `^#` line can
   therefore never match one, which is what makes it inert.
3. **Every line a valid ERE.** Balanced parens and brackets. Prefer prose with no metacharacters.
4. **Keep prose out.** Add reasoning to this README instead. The file should stay near-empty.
5. **Never verify by eye.** Run `bash checks/secret-scan-allow-selftest.sh` after any edit; it is
   wired into `npm run gate`.

## The current entry

```
^[0-9]+:[[:space:]]*const val CUSTOM_API[_]KEY_RESPONSES = "customApiKeyResponses"[[:space:]]*$
```

This is the Claude Code `settings.json` key **name**, not a credential. The scan's ASSIGN pattern
fires on `API_?KEY[A-Za-z_]*` followed by `= "<15+ chars>"`, which the constant declaration matches
by shape, while its value is a literal JSON field name Claude Code itself defines — the
approved/rejected list for custom API keys.

It is anchored to the whole line so any *other* assignment appended to it is still scanned, and it
permits only trailing whitespace after the closing quote.

**Self-exemption:** the allowlist is scanned too, and an entry quoting a credential-shaped
declaration is itself credential-shaped. The `[_]` character class keeps the regex matching the
real declaration while breaking the literal the ASSIGN pattern looks for, so the file does not have
to exempt itself.
