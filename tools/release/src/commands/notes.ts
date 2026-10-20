// `release notes <version> [--out FILE]` — the CHANGELOG section release.yml publishes as the release
// body (src/lib/changelog.ts), cut at its `### Added` heading with a link to the rest when the whole section is over
// what a GitHub release body can hold. Exit 1 when the version has no section, or when the body cannot be made to
// fit: the release stops before a page with no notes, or one GitHub would refuse, goes public.
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { releaseNotes } from "../lib/changelog.ts";

export const usage =
  "notes <version> [--out FILE]         the version's CHANGELOG.md section, the release body (cut at ### Added when too long for GitHub); exit 1 when there is none or it cannot fit";

export function notes(argv: readonly string[], repoRoot: string): number {
  const [version, flag, out, ...rest] = argv;
  if (!version || version.startsWith("-") || (flag !== undefined && (flag !== "--out" || !out)) || rest.length) {
    console.error(`release: usage: bun tools/release ${usage}`);
    return 2;
  }
  const section = releaseNotes(readFileSync(join(repoRoot, "CHANGELOG.md"), "utf8"), version.replace(/^v/, ""));
  if (section instanceof Error) {
    console.error(section.message);
    return 1;
  }
  if (out) writeFileSync(out, section);
  else process.stdout.write(section);
  return 0;
}
