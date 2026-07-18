# Codemods — deterministic ast-grep rewrites

Codemods are a first-class tier here, not a last resort. A codemod is a *block rule + a tested,
deterministic fix*: the detection has the same enumerability cost as any wall in `.rules/`, and the
rewrite is a pure function you can dry-run and snapshot-test. It is strictly more automation for the
same enumeration cost — and it is how mechanical refactors should be done (AST-aware, reversible,
reviewable) instead of by-hand `sed`/string-replace.

## Run

    npx ast-grep scan --rule checks/codemods/<name>.yml <paths>            # DRY RUN — shows diffs, changes nothing
    npx ast-grep scan --rule checks/codemods/<name>.yml <paths> --update-all   # APPLY

Always dry-run first. Then `git diff` + build/gate green before committing.

## Choose the MODE per transform (this is the whole discipline)

Codemods are not one thing. Match the mode to what the rewrite does:

| Mode | When | How it runs |
|---|---|---|
| **Run-once refactor** | any multi-file mechanical change (rename, idiom→shared-helper, restructure) | dry-run → review diff → apply → gate green → commit. **Default** — beats by-hand replace every time. |
| **Auto-apply on hook** | deterministic **and** semantics-PRESERVING **and** context-independent (canonical-form normalization) | PostToolUse/Stop `--update-all`; the change is surfaced to the agent as hook output (never silent). |
| **Detect + review** | semantics-CHANGING or context-dependent | dry-run only; a human/agent decides per site. Do NOT blind-apply. |

The failure to avoid is blind-applying a **detect+review** transform. Example below is exactly that.

## Library

### codemod-runcatching-to-cancellable.yml — MODE: detect + review (NOT auto-apply)
Rewrites `runCatching {}` → `runCatchingCancellable {}`. Fills a real gap: the
`kt-catch-swallows-cancellation` wall only sees literal `catch` blocks, so it is blind to
`runCatching`, which swallows CancellationException the same way.

BUT `runCatchingCancellable` catches a NARROWER set (IOException/SerializationException/
IllegalArgumentException) than `runCatching` (all Throwable). Blind-applying would let previously-
swallowed exceptions escape — verified: it would break `Daemon` boot-isolation, `Main` shutdown, and
`LoginIo`'s non-POSIX `UnsupportedOperationException` fallback. So this codemod SURFACES sites for a
per-site decision; it does not fix them for you. The dry-run is the point — it shows the blast radius
before you touch anything.
