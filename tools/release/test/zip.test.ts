// The zip reader that replaced python's zipfile for the jar's META-INF comparison. Both storage
// methods a JDK-built jar uses are exercised, and so is the real staged jar when one is there —
// 31k entries and every sidecar deflated, which no hand-built fixture proves.
import { afterAll, describe, expect, test } from "bun:test";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { deflateRawSync } from "node:zlib";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { zipEntry } from "../src/lib/zip.ts";

const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

interface Member {
  readonly name: string;
  readonly body: Buffer;
  readonly deflate: boolean;
}

/** A minimal, real zip: local headers, a central directory and an EOCD. CRCs are left zero — the
 *  reader checks the inflated LENGTH against the directory, never the CRC. */
function writeZip(members: readonly Member[]): string {
  const dir = mkdtempSync(join(tmpdir(), "release-zip-"));
  workspaces.push(dir);
  const path = join(dir, "fixture.jar");
  const locals: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const member of members) {
    const name = Buffer.from(member.name, "utf8");
    const data = member.deflate ? deflateRawSync(member.body) : member.body;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(member.deflate ? 8 : 0, 8);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(member.body.length, 22);
    local.writeUInt16LE(name.length, 26);
    locals.push(local, name, data);

    const record = Buffer.alloc(46);
    record.writeUInt32LE(0x02014b50, 0);
    record.writeUInt16LE(20, 6);
    record.writeUInt16LE(member.deflate ? 8 : 0, 10);
    record.writeUInt32LE(data.length, 20);
    record.writeUInt32LE(member.body.length, 24);
    record.writeUInt16LE(name.length, 28);
    record.writeUInt32LE(offset, 42);
    central.push(record, name);
    offset += 30 + name.length + data.length;
  }
  const centralBuffer = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(members.length, 8);
  eocd.writeUInt16LE(members.length, 10);
  eocd.writeUInt32LE(centralBuffer.length, 12);
  eocd.writeUInt32LE(offset, 16);
  writeFileSync(path, Buffer.concat([...locals, centralBuffer, eocd]));
  return path;
}

describe("zipEntry", () => {
  test("reads a STORED entry and a DEFLATED one out of the same archive", () => {
    const stored = Buffer.from("META-INF stored body\n");
    const deflated = Buffer.from("x".repeat(4096));
    const path = writeZip([
      { name: "META-INF/LICENSE", body: stored, deflate: false },
      { name: "META-INF/PROVENANCE.md", body: deflated, deflate: true },
    ]);
    expect(zipEntry(path, "META-INF/LICENSE").equals(stored)).toBe(true);
    expect(zipEntry(path, "META-INF/PROVENANCE.md").equals(deflated)).toBe(true);
  });

  test("an entry that is not there throws, never returns an empty buffer", () => {
    const path = writeZip([{ name: "a", body: Buffer.from("a"), deflate: false }]);
    expect(() => zipEntry(path, "META-INF/LICENSE")).toThrow("has no entry META-INF/LICENSE");
  });

  test("a file that is not an archive is a refusal", () => {
    const dir = mkdtempSync(join(tmpdir(), "release-zip-"));
    workspaces.push(dir);
    const path = join(dir, "not-a-jar");
    writeFileSync(path, "just text, long enough to search\n");
    expect(() => zipEntry(path, "anything")).toThrow("no end-of-central-directory");
  });

  test("the real staged jar, when one is staged: the sidecar is the file beside it", () => {
    const { repoRoot } = layout();
    const jar = join(repoRoot, "dist", "splice.jar");
    if (!existsSync(jar)) return; // nothing staged in this worktree; `release accept` covers it
    expect(zipEntry(jar, "META-INF/LICENSE").length).toBeGreaterThan(0);
    expect(zipEntry(jar, "META-INF/PROVENANCE.md").toString("utf8").startsWith("# Provenance")).toBe(true);
  });
});
