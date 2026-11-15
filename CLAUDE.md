# CLAUDE.md — working on splice with Claude Code

Read `AGENTS.md` first: it holds the invariants, the external contracts and the gates. This file adds
the working rules for a Claude Code session contributing to splice. `.github/CONTRIBUTING.md` covers
prerequisites, the gate commands, PR titles and releasing.

## Working

- The gate and Gradle builds are slow. Run them in the background and keep working on the next
  thing; come back when the result arrives.
- Edits are checked as you write them: `.claude/settings.json` runs `bun tools/gate rules` and the
  no-Python guard on every Write and Edit. A wall that blocks an edit means the code is wrong, not
  the wall.
- The pre-push hook runs the gate on what you push. That run is the check; there is no need for a
  separate duplicate run before pushing.
- Work goes on a short-lived branch off `main` and reaches `main` through a pull request. Versions
  and releases are cut by the maintainers.

## Code rules

- **Retries are total.** Known errors get a specific plan; every other error gets a bounded
  generic retry and an honest message.
- **Idle is a probe, never an error.** Act only on a dead path, by re-anchoring. Never reap a model
  that is thinking in silence.
- **Vendor facts stay in their provider module.** A shared file carries at most one dispatch line
  per head.
- **Heads are isolated.** No head's state leaks into `~/.claude` or into another head. Resuming a
  session on another head is an explicit `-r` copy with its model rewritten.
- **Reasoning effort is part of the prompt-cache key.** Never vary it by turn type, compaction
  included.
- **Boot fails closed.** Config findings refuse boot, all listed at once, and the CLI offers an
  interactive fix session. Never boot past a finding and never fix one silently.
- **Adding a head is one of three moves:** reuse a schema (pure TOML), declare a new one (dialect
  and auth grammar), or reuse one and reshape it (quirks).
- **`mirror_reasoning` stays false on every head.** Don't re-enable it for parity or continuity.
- **The Responses websocket transport ships off.** Turning it on is a one-line TOML change by the
  user, never a code default.
- A cross-head fallback for a primary head that is hard down is a product decision. Propose it in
  an issue rather than building it into a change.
- **No Python.** Repo tooling is Bun/TypeScript, and the gate fails on a tracked `.py` file.

## Kotlin style

- No new top-level functions and no new companion objects. Top-level `const val` is allowed. Test
  sources are exempt. The `quality/rules` walls enforce this.
- The architecture rules pack (`quality/rules/kotlin`) is wanted: when a rule contradicts the
  architecture, the architecture is what changes.
- No detekt or ast-grep suppressions. The one exception: `LongParameterList` skips `@Serializable`
  classes, which mirror an outside format (`splice.toml`, saved state, wire bodies) field by field.

## Tests

- A test checks what a user sees or what a route returns. No test asserts how the code works
  inside: no call counts, no "method X was called", no file-read counts.
- A fix doesn't need a new test unless that test checks user-visible behavior. Speed claims are
  shown by timing a real run, not by a test.
- When a change deliberately alters behavior, the tests that pinned the old behavior are deleted
  or rewritten in the same change, and the commit says so. Never restore old behavior to make an
  old test pass.

## UI changes (the console)

A claim about how something looks is shown by a capture or by measuring the rendered DOM, never by
a passing test, an HTTP 200, a grep, or a name's absence from the source. Before calling a console
change done:

1. Open every page you changed on the build you are describing, in dark and light themes, at a
   standard width (1440 px) and a wide desktop width (around 3400–3800 px), and look at every
   screenshot.
2. Fix what is broken: text over text, clipped text, content past the edge, crowding, walls of
   explanation, and any control whose effect a first-time user couldn't tell from the screen.
3. Judge use, not only defects: check what each control shows and does, that actions line up
   across cards and rows, and that the page matches its neighbours.
4. Attach the screenshots you judged to the pull request.

A number that could mislead is fixed in the number or its label. A short line beside a control is
fine when its name alone would be cryptic.
