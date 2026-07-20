# History rewrite record

The canonical `torad-labs/splice` history was rewritten before the public
release to remove private development captures. Those files are no longer
reachable from canonical branches or the release source lineage, and current
source refers to the Kotlin port's source snapshot only as
`pre-public-port-baseline`.

No credentials were identified in the removed material. The removed content
did include private operator context, so it must continue to be treated as
exposed even after the rewrite.

## Residual exposure

A Git history rewrite cannot recall objects already copied into clones, forks,
CI caches, mirrors, pull-request refs, or provider-side caches. Ordinary Git
operations cannot prove deletion from those locations.

Provider-retained objects and caches require a private GitHub Support request.
The operator retains the sensitive object identifiers and affected path list
outside this public repository for that purpose. They must not be copied back
into issues, pull requests, source comments, or public runbooks.

Do not perform another force-push as routine cleanup. Branch protection and
force-push blocking remain the repository's steady state.

## Operator checklist

1. Keep the private incident record and identifiers out of the repository.
2. Complete the GitHub Support request for provider-retained data.
3. Treat existing downstream copies as non-recallable.
4. Rotate credentials if a private audit later finds that any secret crossed
   the removed capture path.

Canonical rewrite completed 2026-07-19. Provider-side cache/object removal
remains an external support action.
