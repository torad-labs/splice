// signin-e2e accept: `bun tools/release accept`, which release.yml and `bun tools/release beta` run on every release,
// runs the sign-in check on the jar it accepts, once the bundle's own legs pass and before the install rehearsal, and a
// sign-in check that fails fails the release, by name. The bundle is synthetic in the way tools/release/test/accept.test.ts
// builds one: its jar carries its sidecars, and `java` on PATH answers the version and serves no daemon, so the
// sign-in check cannot pass on it. CONTRIBUTING's Releasing section says the check runs and how to run it alone.

import { afterAll, expect, setDefaultTimeout, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ROOT } from "./lib/report.ts";

setDefaultTimeout(600_000);

const dirs: string[] = [];
afterAll(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
});
const scratch = (prefix: string) => {
  const d = mkdtempSync(join(tmpdir(), prefix));
  dirs.push(d);
  return d;
};

const VERSION = "9.9.9";
const SIDECARS = ["LICENSE", "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_LICENSES.txt", "PROVENANCE.md", "bom.cdx.json", "dependency-licenses.json"];
const ASSETS = ["splice.jar", "splice-launch", "install.sh", ...SIDECARS];

/** A stored (uncompressed) zip of these entries. */
function zip(entries: { name: string; body: Buffer }[]): Buffer {
  const local: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const e of entries) {
    const name = Buffer.from(e.name);
    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50, 0);
    header.writeUInt16LE(20, 4);
    header.writeUInt32LE(e.body.length, 18);
    header.writeUInt32LE(e.body.length, 22);
    header.writeUInt16LE(name.length, 26);
    local.push(header, name, e.body);
    const record = Buffer.alloc(46);
    record.writeUInt32LE(0x02014b50, 0);
    record.writeUInt16LE(20, 6);
    record.writeUInt32LE(e.body.length, 20);
    record.writeUInt32LE(e.body.length, 24);
    record.writeUInt16LE(name.length, 28);
    record.writeUInt32LE(offset, 42);
    central.push(record, name);
    offset += header.length + name.length + e.body.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}

/** A staged bundle whose own legs all pass: sums, compliance documents, and a jar carrying its sidecars. */
function bundle(): string {
  const dist = scratch("signin-e2e-accept-");
  const content: Record<string, string> = {
    "splice-launch": `#!/bin/sh\nSPLICE_GATEWAY_VERSION="${VERSION}"\nSPLICE_SHIM_VERSION="shim-5"\n`,
    "install.sh": "#!/usr/bin/env bash\ntrue\n",
    LICENSE: "license\n",
    "THIRD_PARTY_NOTICES.md": "notices\n",
    "THIRD_PARTY_LICENSES.txt": "license texts\n",
    "PROVENANCE.md": "# Provenance\n\nhow this was built\n",
    "bom.cdx.json": JSON.stringify({ bomFormat: "CycloneDX", specVersion: "1.6", serialNumber: "urn:uuid:synthetic", components: [{}] }),
    "dependency-licenses.json": '{"dependencies":[{"moduleName":"x"}]}\n',
  };
  for (const [name, body] of Object.entries(content)) writeFileSync(join(dist, name), body);
  writeFileSync(join(dist, "splice.jar"), zip(SIDECARS.map((n) => ({ name: `META-INF/${n}`, body: readFileSync(join(dist, n)) }))));
  writeFileSync(
    join(dist, "sha256sums.txt"),
    ASSETS.map((a) => `${createHash("sha256").update(readFileSync(join(dist, a))).digest("hex")}  ${a}\n`).join(""),
  );
  return dist;
}

/** `java` that answers `version` as the jar's and runs no daemon: whatever else it is asked, it exits 1 at once. */
function fakeJava(): string {
  const dir = scratch("signin-e2e-java-");
  writeFileSync(
    join(dir, "java"),
    `#!/bin/sh\nfor a in "$@"; do [ "$a" = version ] && { echo "splice ${VERSION}"; exit 0; }; done\necho "made-up java: no daemon in this jar" >&2\nexit 1\n`,
  );
  chmodSync(join(dir, "java"), 0o755);
  return dir;
}

test("release accept runs the sign-in check on the jar it accepts, and a failing one fails the release by name", () => {
  const dist = bundle();
  const r = spawnSync("bun", ["tools/release", "accept", dist], {
    cwd: ROOT,
    encoding: "utf8",
    env: { ...process.env, PATH: `${fakeJava()}:${process.env.PATH ?? ""}`, SPLICE_EXPECTED_VERSION: "" },
  });
  const out = `${r.stdout ?? ""}${r.stderr ?? ""}`;
  expect(r.status, out).toBe(1);
  expect(out).toContain("release accept: the sign-in check failed");
  // the bundle's own legs passed, and the install rehearsal did not run on a release whose sign-in is broken
  expect(out).not.toContain("missing or EMPTY");
  expect(out).not.toContain("did NOT match");
  expect(out).not.toContain("differs from");
  expect(out).not.toContain("installed splice command");
});

test("CONTRIBUTING's Releasing section says the sign-in check runs, and how to run it alone", () => {
  const text = readFileSync(join(ROOT, ".github/CONTRIBUTING.md"), "utf8");
  const start = text.indexOf("## Releasing");
  expect(start).toBeGreaterThanOrEqual(0);
  const rest = text.slice(start + 1);
  const next = rest.search(/\n## /);
  const section = next < 0 ? rest : rest.slice(0, next);
  expect(section).toContain("bun tools/e2e signin");
});
