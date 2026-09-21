// `release accept`'s bundle legs, each proven able to fail (checks/release/accept.sh:8-43 and the
// python block's head until PR 6). The bundle here is SYNTHETIC — a real one needs a 71 MB fat jar
// and a full install rehearsal, which `release verify` runs; what is proven here is that every leg
// reds on its own mutant and that the legs before it were green when it did.
//
// `java` is faked on PATH the way tools/gate's slot tests fake gradlew: the jar's self-reported
// version is an input to these legs, not the thing under test.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";

const { repoRoot } = layout();
const CLI = join(repoRoot, "tools", "release", "index.ts");
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

const JAR_VERSION = "9.9.9";
const ASSETS = [
  "splice.jar", "splice-launch", "install.sh",
  "LICENSE", "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_LICENSES.txt", "PROVENANCE.md",
  "bom.cdx.json", "dependency-licenses.json",
] as const;

/** A staged bundle in the shape the task writes, with `changes` applied before the sums are taken. */
function bundle(changes: Record<string, string> = {}, extra: Record<string, string> = {}): string {
  const dir = mkdtempSync(join(tmpdir(), "release-accept-"));
  workspaces.push(dir);
  const content: Record<string, string> = {
    "splice.jar": "not really a jar\n",
    "splice-launch": `#!/usr/bin/env node\nconst SPLICE_GATEWAY_VERSION = "${JAR_VERSION}";\nconst SPLICE_SHIM_VERSION = "shim-5";\n`,
    "install.sh": "#!/usr/bin/env bash\ntrue\n",
    LICENSE: "license\n",
    "THIRD_PARTY_NOTICES.md": "notices\n",
    "THIRD_PARTY_LICENSES.txt": "license texts\n",
    "PROVENANCE.md": "# Provenance\n\nhow this was built\n",
    "bom.cdx.json": "{}\n",
    "dependency-licenses.json": '{"dependencies":[{"moduleName":"x"}]}\n',
    ...changes,
  };
  let sums = "";
  for (const asset of ASSETS) {
    writeFileSync(join(dir, asset), content[asset]!);
    sums += `${createHash("sha256").update(content[asset]!).digest("hex")}  ${asset}\n`;
  }
  writeFileSync(join(dir, "sha256sums.txt"), sums);
  for (const [name, body] of Object.entries(extra)) writeFileSync(join(dir, name), body);
  return dir;
}

/** `java -jar <jar> version` answering for a bundle that has no real jar in it. */
function fakeJava(version = JAR_VERSION): string {
  const dir = mkdtempSync(join(tmpdir(), "release-accept-java-"));
  workspaces.push(dir);
  writeFileSync(join(dir, "java"), `#!${process.execPath}\nconsole.log("splice ${version}");\n`);
  chmodSync(join(dir, "java"), 0o755);
  return dir;
}

function accept(dist: string, options: { javaDir?: string; argv?: readonly string[] } = {}) {
  const javaDir = options.javaDir ?? fakeJava();
  const proc = Bun.spawnSync([process.execPath, CLI, "accept", ...(options.argv ?? [dist])], {
    cwd: repoRoot,
    env: { ...Bun.env, PATH: `${javaDir}:${Bun.env.PATH ?? ""}`, SPLICE_EXPECTED_VERSION: "" },
    stdout: "pipe",
    stderr: "pipe",
  });
  return { code: proc.exitCode, output: `${proc.stdout.toString()}${proc.stderr.toString()}` };
}

describe("release accept", () => {
  test("the argv it does not take is refused", () => {
    expect(accept("", { argv: ["--nope"] }).output).toContain("unknown argument --nope");
    expect(accept("", { argv: ["a", "b"] }).output).toContain("one dist directory, got a second");
    expect(accept("", { argv: ["--version"] }).output).toContain("--version needs the version");
  });

  test("a missing manifest reports as EMPTY", () => {
    const dir = bundle();
    rmSync(join(dir, "sha256sums.txt"));
    const run = accept(dir);
    expect(run.code).toBe(1);
    expect(run.output).toContain("missing or EMPTY");
    expect(run.output).toContain("sha256sums.txt");
  });

  // DR-25 redo: `-f` accepted zero-byte artifacts whose checksums were computed AFTER truncation.
  test("a zero-byte artifact is EMPTY, not merely present", () => {
    const dir = bundle({ "PROVENANCE.md": "" });
    const run = accept(dir);
    expect(run.code).toBe(1);
    expect(run.output).toContain("missing or EMPTY");
    expect(run.output).toContain("PROVENANCE.md");
  });

  test("a non-empty PLACEHOLDER provenance fails the semantic floor", () => {
    const run = accept(bundle({ "PROVENANCE.md": "placeholder\n" }));
    expect(run.code).toBe(1);
    expect(run.output).toContain("PROVENANCE.md does not open with the provenance header");
  });

  test("the launcher and the jar must agree on a version", () => {
    const dir = bundle({
      "splice-launch": '#!/usr/bin/env node\nconst SPLICE_GATEWAY_VERSION = "0.0.1";\nconst SPLICE_SHIM_VERSION = "shim-5";\n',
    });
    const run = accept(dir);
    expect(run.code).toBe(1);
    expect(run.output).toContain(`launcher expects gateway 0.0.1 but jar is ${JAR_VERSION}`);
  });

  // The shim marker is JS as of PR 6; the bash spelling must not be readable by accident, or two
  // blanks would compare equal and the leg above would pass on a shim it never read.
  test("a shim with no JS marker fails the version comparison rather than passing it", () => {
    const dir = bundle({ "splice-launch": '#!/usr/bin/env bash\nSPLICE_GATEWAY_VERSION="9.9.9"\n' });
    expect(accept(dir).output).toContain(`launcher expects gateway undefined but jar is ${JAR_VERSION}`);
  });

  test("a checksum that is not the file's own fails", () => {
    const dir = bundle();
    writeFileSync(join(dir, "LICENSE"), "tampered\n");
    const run = accept(dir);
    expect(run.code).toBe(1);
    expect(run.output).toContain("LICENSE: FAILED");
    expect(run.output).toContain("did NOT match");
  });

  // The old leg compared the manifest's LINE COUNT to a hand copy of the asset list — a
  // denominator taken from the list being checked (§24). The directory is the denominator now.
  test("a staged file the manifest does not cover fails the coverage leg", () => {
    const dir = bundle({}, { "EXTRA-README.md": "stray\n" });
    const run = accept(dir);
    expect(run.code).toBe(1);
    expect(run.output).toContain("sha256sums.txt does not cover the exact asset set");
  });

  // POSITIVE CONTROL for every leg above: the same bundle, unmutated, gets past all of them and
  // fails at the NEXT one — so none of the reds above was the bundle being broken in general.
  test("an unmutated bundle passes every leg above and reds on the SBOM", () => {
    const run = accept(bundle());
    expect(run.code).toBe(1);
    expect(run.output).toContain("SBOM is not a non-empty CycloneDX document");
  });
});
