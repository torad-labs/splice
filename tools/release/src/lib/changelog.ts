// THE RELEASE NOTES, FROM THE CHANGELOG — the hand-kept `## splice vX.Y.Z — theme - date` section of
// CHANGELOG.md for one version (V4-198, v0.4.0). release.yml hands it to the publish step as the
// release body, above GitHub's generated PR list; `bun test tools/release` holds that the version the
// launch shim declares has one, so a version bump without its CHANGELOG cut fails the bump's own gate
// (CONTRIBUTING's release step 1), long before a promotion would publish an empty page.
//
// A heading matches its version exactly: `v0.4.0` never matches `v0.4.0-beta.1` or `v0.4.01`.

/** The CHANGELOG heading of one release: `## splice v<version>` followed by a space or the line's end. */
function headingFor(version: string): RegExp {
  const escaped = version.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`^## splice v${escaped}(?: |$)`);
}

/** The section of [changelog] for [version]: its heading line through the line before the next `## `
 *  heading, trailing blank lines dropped. An Error names the version when there is no such section,
 *  or when the section holds nothing but its heading. */
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
  return `${section}\n`;
}
