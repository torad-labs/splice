// The ONE conventional-type list in this repo (V4-30), and the title shape the org gate enforces.
//
// WHY ONE FILE (brain concept #924 — "you make drift not compile"). A PR title with a bad type used
// to be caught only after the PR existed, by CI, for three structural reasons that made producing
// one close to inevitable: a PR TEMPLATE cannot constrain a title (templates populate the body, and
// `gh pr create --body` never renders it); TWO ENFORCERS DISAGREED — this repo shipped a
// pr-title.yml allowing `release` and `codex` while the org-injected gate rejects both and allows
// `style` (that workflow is deleted; the org gate is the single authority); and THE NEAREST SIGNAL
// MISLEADS — over half of main's history uses types that fail the gate (tracker, upstream, auth,
// walls), so inferring the convention from `git log` reliably produces an invalid title.
//
// So the list lives HERE, once. `gate title` validates a proposed title against it before a PR
// exists; checks/config/one-conventional-type-list.ts fails the build on any second copy of it —
// including a correct one, because a correct second copy is still the divergence mechanism — and
// parses TYPES from this line rather than restating it. TYPES mirrors the org gate verbatim: if the
// org list changes, this is the single line to update.
export const TYPES = "build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test";

/** `type(optional-scope)!: subject` — the Conventional Commits shape, matched exactly as the org
 *  gate matches it, so a title that passes here cannot fail there. */
export const TITLE_SHAPE = new RegExp(`^(${TYPES})(\\([^)]+\\))?!?: .+`);

/** The title's conventional type, or null when the title does not have the shape. */
export function conventionalType(title: string): string | null {
  return TITLE_SHAPE.exec(title)?.[1] ?? null;
}
