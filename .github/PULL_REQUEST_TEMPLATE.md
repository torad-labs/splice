<!-- TITLE must be Conventional Commits; the ORG gate enforces it (check `title`).
     The allowed types are NOT repeated here on purpose — a second copy is how this
     repo ended up with two enforcers that disagreed. There is one list, in
     checks/pr-title.sh, and `npm run gate` checks your commit subject against it.

       bash checks/pr-title.sh "feat(scope): subject"   # check a title before opening

     Do NOT infer the convention from `git log`: most of this repo's history predates
     the gate and uses types that fail it. -->

## What / why

## Gates

- [ ] `npm run gate` (complete Kotlin, Node, webui, release, and OSS gate)
- [ ] `npm run gate:rules` (ast-grep walls: tree scan + rule red/green)
- [ ] `npm run test:hooks`
- [ ] `bash checks/config-guard.sh`
- [ ] `cd gateway && ./gradlew check`

## Notes

Anything reviewers should know (breaking changes, follow-ups, things intentionally left out).
