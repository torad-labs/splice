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

None was caught by review, and hazards 1 and 2 landed in the same file in the same PR hours apart:
the first was fixed as an instance, so the generator re-derived the class. That is why the answer
below is a generator rather than another checker.

## The file is GENERATED

`secret-scan-allow.txt` is emitted from **`secret-scan-allow.toml`** by
`checks/gen-secret-scan-allow.py`. Do not edit the `.txt`.

That is the fix for all three hazards above, and it is a different KIND of fix: they are now
impossible to express rather than merely detected (brain concept #924, "you make drift not
compile"). Hazards 1 and 2 landed in the same file in the same PR hours apart precisely because
the first was fixed as an instance, so the generator re-derived the class.

| hazard | why it can no longer be written |
|---|---|
| unanchored entry | the generator owns `^`...`$`; a `pattern` carrying its own anchors is refused |
| prose as a live regex | there is no prose slot in the `.txt`; `reason` lives in the TOML and never reaches grep |
| invalid ERE | generation fails, validated against **grep** (the actual consumer, not Python's `re`) |
| hand-edited `.txt` | `--check` regenerates and diffs; the gate runs it |

An exemption also cannot be added without a `reason` — an unexplained one is not reviewable.

```bash
python3 checks/gen-secret-scan-allow.py          # regenerate
python3 checks/gen-secret-scan-allow.py --check  # what the gate runs
bash checks/secret-scan-allow-selftest.sh        # canaries over the generated output
```

The canary self-test stays as defence in depth: it verifies the generator's OUTPUT, so a bug in
the generator itself is still caught.

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
