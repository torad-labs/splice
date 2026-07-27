# `/grant` — operator-only wall grants from the prompt line

Staged installer for a `/grant` command that replaces `SPLICE_WALLS_OK=1`, so opening the wall no
longer requires quitting and relaunching Claude Code.

## Why the env var forces a restart

`orchestrator.py` reads `os.environ.get("SPLICE_WALLS_OK")`. Hook processes are spawned by the
Claude Code CLI and inherit *its* environment, so nothing inside a session can set it — not a
Bash tool call (that's a child process), not a settings edit (`.claude/settings.json` is itself a
wall path). The only route was relaunching with the variable exported.

## Why this is a grant *file*, issued from a `UserPromptSubmit` hook

Ported from `torad-fleet`'s `03_grant_command.py`, whose security argument is the whole design:

> A UserPromptSubmit hook fires ONLY on text a human typed into the prompt box.
> An assistant emits tool calls and assistant messages; it cannot emit a user prompt.

So a grant is operator-only **by construction**, not by policy — there is no string an agent can
produce that reaches the issuing code. That's concept #924 applied to the grant channel itself:
the violation is unrepresentable rather than merely forbidden.

This is also why the obvious alternative is wrong. A plain `.claude/commands/grant.md` slash
command expands into a prompt the *model* then acts on, which puts the assistant in the issuing
path and turns the wall back into a suggestion. Don't do that.

Improvements over the env var, beyond not restarting:

- **bounded** — every grant expires (clamped to 120 minutes; expiry enforced at the gate, so a
  stale file can never hold the wall open)
- **reasoned** — a reason is mandatory and recorded in the file; the grant record is the only
  evidence of intent
- **revocable** — `/grant revoke`
- **inspectable** — `/grant` reports remaining time, reason, and issue time

## Install (you run this, not the assistant)

```bash
bash dev/walls-grant/install.sh
```

The installer edits `.claude/hooks/`, which is exactly what the wall blocks — that's the
bootstrap, and it's why it runs from your shell rather than through a tool call. After this the
env var is never needed again. Re-running is safe: it detects an existing install and re-verifies.

It performs four steps and then proves itself with a RED → GREEN → RED self-test that drives the
real `pretooluse` lifecycle: blocked with no grant, allowed with an active grant, blocked again
with an expired one.

## Usage

```
/grant                      state, what it unlocks, what it cannot
/grant <minutes> <reason>   issue a bounded grant (reason mandatory)
/grant revoke               end it now
```

## What a grant does NOT do

It authorizes editing wall infrastructure **through** the gates. It never skips one:
`npm run gate:rules` and `npm run test:hooks` must still pass, the Stop-lifecycle tree scan still
runs, and `--no-verify` stays forbidden. If you want a grant to make a failing check go away, the
honest move is to fix the check.

## Two traps found while building this

Both were caught by simulating the installer against a *copy* of `orchestrator.py` instead of
trusting it — worth knowing if you modify either file.

1. **The hook signals a deny as `{"decision":"block"}` JSON on stdout with exit 0**, not exit 2.
   An exit-code-based self-test reports "allowed" for every blocked write and passes vacuously.
2. **"Any block" is the wrong oracle.** The orchestrator also fails closed with
   `HOOK POLICY INCOMPLETE` when the scan toolchain is broken. A coarse check reads that as "the
   wall held", so the ALLOW arm fails for a reason unrelated to grants. The self-test matches the
   `SPLICE WALLS` marker specifically and reports a toolchain failure as its own outcome.

## Files

| file | destination |
|---|---|
| `03_grant_command.py` | `.claude/hooks/modules/userpromptsubmit/` |
| `install.sh` | run in place; patches `orchestrator.py`, updates `.gitignore` |

The transient grant file lives at `.claude/state/walls-grant.json` and is gitignored.
