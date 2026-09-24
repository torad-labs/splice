// The release body is the version's CHANGELOG section (src/lib/changelog.ts). The unit arms pin the
// extraction; the last arm runs against the REAL CHANGELOG.md and the REAL shim marker, which is what
// makes a version bump without its CHANGELOG cut fail the bump's own gate.
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { releaseNotes } from "../src/lib/changelog.ts";
import { shimMarkers, shimPath } from "../src/lib/shim.ts";

const { repoRoot } = layout();

const LOG = [
  "# Changelog",
  "",
  "## splice v0.5.0-beta.1 — a prerelease - 2026-10-01",
  "",
  "### Added",
  "- beta",
  "",
  "## splice v0.4.0 — the release - 2026-09-24",
  "",
  "### Highlights",
  "- one",
  "",
  "### Fixed",
  "- two",
  "",
  "## splice v0.3.2 — the one before - 2026-09-07",
  "",
  "- three",
  "",
].join("\n");

describe("release notes from the CHANGELOG", () => {
  test("a version's section runs from its heading to the next release heading", () => {
    expect(releaseNotes(LOG, "0.4.0")).toBe(
      "## splice v0.4.0 — the release - 2026-09-24\n\n### Highlights\n- one\n\n### Fixed\n- two\n",
    );
  });

  test("the last section runs to the end of the file", () => {
    expect(releaseNotes(LOG, "0.3.2")).toBe("## splice v0.3.2 — the one before - 2026-09-07\n\n- three\n");
  });

  test("a heading matches its version exactly, never a prerelease or a longer number", () => {
    expect(releaseNotes(LOG, "0.5.0")).toBeInstanceOf(Error);
    expect(releaseNotes(LOG, "0.4.01")).toBeInstanceOf(Error);
    expect(releaseNotes(LOG, "0.4")).toBeInstanceOf(Error);
    expect(releaseNotes(LOG, "0.5.0-beta.1")).toBe(
      "## splice v0.5.0-beta.1 — a prerelease - 2026-10-01\n\n### Added\n- beta\n",
    );
  });

  test("a missing or empty section is an Error naming the version", () => {
    const missing = releaseNotes(LOG, "9.9.9");
    expect(missing).toBeInstanceOf(Error);
    expect((missing as Error).message).toContain("v9.9.9");
    expect(releaseNotes("## splice v1.0.0 — empty - x\n\n\n## splice v0.9.0 — y - x\n- z\n", "1.0.0")).toBeInstanceOf(Error);
  });

  test("the version the launch shim declares has its CHANGELOG section", () => {
    const markers = shimMarkers(shimPath(repoRoot));
    expect(markers).not.toBeInstanceOf(Error);
    const version = (markers as { gateway: string }).gateway;
    const section = releaseNotes(readFileSync(join(repoRoot, "CHANGELOG.md"), "utf8"), version);
    expect(section, `CHANGELOG.md needs a "## splice v${version}" section: cut it in the bump PR`).not.toBeInstanceOf(Error);
  });
});
