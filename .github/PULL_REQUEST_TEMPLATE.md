<!-- TITLE must be Conventional Commits, and CI enforces it (required check `lint`):
     feat fix docs test build ci chore perf refactor revert release codex
     Scope optional: fix(walls): ...  Any other type FAILS and blocks the merge. -->

## What / why

## Gates

- [ ] `npm run gate` (complete Kotlin, Node, webui, release, and OSS gate)
- [ ] `npm run gate:rules` (ast-grep walls: tree scan + rule red/green)
- [ ] `npm run test:hooks`
- [ ] `bash checks/config-guard.sh`
- [ ] `cd gateway && ./gradlew check`

## Notes

Anything reviewers should know (breaking changes, follow-ups, things intentionally left out).
