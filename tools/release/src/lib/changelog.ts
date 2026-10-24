// THE RELEASE NOTES, FROM THE CHANGELOG — the hand-kept `## splice vX.Y.Z: theme - date` section of
// CHANGELOG.md for one version (V4-198, v0.4.0). release.yml hands it to the publish step as the
// release body, above GitHub's generated PR list; `bun test tools/release` holds that the version the
// launch shim declares has one, so a version bump without its CHANGELOG cut fails the bump's own gate
// (CONTRIBUTING's release step 1), long before a promotion would publish an empty page.
//
// A heading matches its version exactly: `v0.4.0` never matches `v0.4.0-beta.1` or `v0.4.01`. From v0.4.0
// a colon follows the version; the older headings use a space and an em dash (V4-254).
//
// A release body has a size limit. The v0.4.0 section is 189,877 characters, and GitHub answers a longer body with
// HTTP 422 after the build has run. A section that fits is the body whole. One that does not is cut at its
// `### Added` heading, which keeps Highlights, Upgrading, Security and Known limitations, and ends with a link to the
// full section at the release's tag. A section that cannot fit even so is an Error, and so is a bump whose section
// cannot be published: the version-has-a-section arm of test/changelog.test.ts reads the real CHANGELOG through here.

const REPO = "torad-labs/splice";

/** GitHub refuses a release body longer than this with HTTP 422 ("body is too long (maximum is 125000 characters)").
 *  No GitHub document states it; projects read it off the error. A JS string's length counts UTF-16 units, so it
 *  never undercounts characters. */
const GITHUB_BODY_LIMIT = 125_000;

/** Room kept for text added after this body: GitHub's generated list of merged PRs (release.yml sets
 *  generate_release_notes) and the local-build paragraph `release beta` puts first. Without it the body can pass here
 *  and still be refused. */
const APPENDED_RESERVE = 5_000;

/** The longest body this tool emits. */
const BODY_CAP = GITHUB_BODY_LIMIT - APPENDED_RESERVE;

/** The CHANGELOG heading of one release: `## splice v<version>` followed by a colon, a space or the line's end. */
function headingFor(version: string): RegExp {
  const escaped = version.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`^## splice v${escaped}(?:[: ]|$)`);
}

/** The line that ends a cut body: where the rest of the section reads, at the tag the release creates. GitHub's anchor
 *  for a heading is its text lowercased, every character that is not a letter, digit, space, hyphen or underscore
 *  dropped, and each space turned into a hyphen (so " - " becomes three hyphens). */
function restOfTheSection(version: string, heading: string): string {
  const anchor = heading
    .replace(/^#+\s*/, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, "")
    .replace(/\s/g, "-");
  return `This page stops before the Added list. Every change, Added, Changed and Fixed, is in [the CHANGELOG section for v${version}](https://github.com/${REPO}/blob/v${version}/CHANGELOG.md#${anchor}).`;
}

/** The release body for [version] from [changelog]: its section, whole when it fits [BODY_CAP], else cut at `### Added`
 *  with a link to the rest. The section runs from its heading line through the line before the next `## `
 *  heading, trailing blank lines dropped. An Error names the version when there is no such section, when the section
 *  holds nothing but its heading, or when the body cannot be made to fit. */
export function releaseNotes(changelog: string, version: string): string | Error {
  const lines = changelog.split("\n");
  const heading = headingFor(version);
  const start = lines.findIndex((line) => heading.test(line));
  if (start < 0) return new Error(`release notes: CHANGELOG.md has no "## splice v${version}" section`);
  const next = lines.findIndex((line, index) => index > start && line.startsWith("## "));
  const section = lines.slice(start, next < 0 ? lines.length : next).join("\n").trimEnd();
  if (section.split("\n").slice(1).every((line) => line.trim() === "")) {
    return new Error(`release notes: the "## splice v${version}" section of CHANGELOG.md is empty`);
  }
  const whole = `${section}\n`;
  if (whole.length <= BODY_CAP) return whole;

  const body = section.split("\n");
  const added = body.findIndex((line) => /^### Added\s*$/.test(line));
  const over = `release notes: the "## splice v${version}" section of CHANGELOG.md is ${whole.length} characters, over the ${BODY_CAP} a GitHub release body can hold`;
  if (added < 0) return new Error(`${over}, and has no "### Added" heading to cut at`);
  const cut = `${body.slice(0, added).join("\n").trimEnd()}\n\n${restOfTheSection(version, body[0]!)}\n`;
  if (cut.length > BODY_CAP) return new Error(`${over}; cut at "### Added" it is still ${cut.length}`);
  return cut;
}
