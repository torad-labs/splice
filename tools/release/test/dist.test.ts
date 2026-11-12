// The staged manifest and the dist enumeration — checks/oss/verify-OSS-D.sh:128-220's legs and all
// four of its mutants, which is where the denominator stops coming from the list being checked.
import { afterAll, describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { SUMS, enumerationProblem, readManifest } from "../src/lib/dist.ts";

const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

const HASH = "0".repeat(64);

function dist(entries: Record<string, string>, sums?: string): string {
  const dir = mkdtempSync(join(tmpdir(), "release-dist-"));
  workspaces.push(dir);
  for (const [name, content] of Object.entries(entries)) writeFileSync(join(dir, name), content);
  if (sums !== undefined) writeFileSync(join(dir, SUMS), sums);
  return dir;
}

describe("the staged manifest", () => {
  test("is the asset set, in the order it was staged", () => {
    const dir = dist({}, `${HASH}  splice.jar\n${HASH}  LICENSE\n`);
    const manifest = readManifest(dir);
    expect(manifest).not.toBeInstanceOf(Error);
    expect((manifest as Exclude<typeof manifest, Error>).assets).toEqual(["splice.jar", "LICENSE"]);
  });

  test("an absent or empty manifest reports as EMPTY, the way the asset loop would", () => {
    expect((readManifest(dist({})) as Error).message).toContain("missing or EMPTY");
    expect((readManifest(dist({}, "")) as Error).message).toContain("missing or EMPTY");
  });

  test("a line that is not a checksum is a refusal, never a skipped asset", () => {
    const problem = readManifest(dist({}, `${HASH}  splice.jar\nnot-a-checksum\n`));
    expect((problem as Error).message).toContain("has a line that is not a checksum");
  });
});

describe("the dist enumeration", () => {
  const published = ["splice.jar", "LICENSE", SUMS];
  const bundle = () => dist({ "splice.jar": "jar", LICENSE: "license" }, `${HASH}  splice.jar\n${HASH}  LICENSE\n`);

  test("a bundle that is exactly the published set passes", () => {
    expect(enumerationProblem(bundle(), published)).toBeNull();
  });

  // The mutant that proves the leg can fail, every run: a staged file OUTSIDE the published set
  // used to ship absent from checksums and release under a green verifier.
  test("an undispositioned extra file fails BY NAME", () => {
    const dir = bundle();
    writeFileSync(join(dir, "EXTRA-README.md"), "stray\n");
    const problem = enumerationProblem(dir, published);
    expect(problem).toContain("NO disposition");
    expect(problem).toContain("EXTRA-README.md");
  });

  test("a directory needs a disposition too", () => {
    const dir = bundle();
    mkdirSync(join(dir, "nested"));
    expect(enumerationProblem(dir, published)).toContain("NO disposition");
  });

  // §24: a blank or placeholder exclusion reason is an absence wearing a label.
  test("a blank reason never dispositions a file", () => {
    const dir = bundle();
    writeFileSync(join(dir, "EXTRA-README.md"), "stray\n");
    for (const reason of ["", "   ", "TODO", "tbd", "n/a", "...", "-"]) {
      expect(enumerationProblem(dir, published, { "EXTRA-README.md": reason }), `reason ${JSON.stringify(reason)}`)
        .toContain("blank or placeholder reason");
    }
  });

  test("a substantive reason that MENTIONS a placeholder word still dispositions (positive control)", () => {
    const dir = bundle();
    writeFileSync(join(dir, "EXTRA-README.md"), "stray\n");
    expect(enumerationProblem(dir, published, { "EXTRA-README.md": "see TODO-1234: excluded until the platform port lands" }))
      .toBeNull();
  });

  test("a published file that is not there fails BY NAME", () => {
    const dir = dist({ "splice.jar": "jar" }, `${HASH}  splice.jar\n`);
    expect(enumerationProblem(dir, published)).toContain("published set missing from actual dist/");
  });
});
