import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
import { dirname, resolve, basename } from "node:path";
import { gunzipSync } from "node:zlib";
import { zipEntry } from "../release/src/lib/zip";
import { artifact, sha256, type CaseEvidence } from "./qualification";

const supported = {
  "linux-amd64": "37703ae51e202c07681e205bc6a8f3c04e63aee4f8ddeb8d2fb09a4d69c00b48",
  "linux-aarch64": "d2f036fd6adc398a1f87a2458c3726558c2eb5660e73dc2b4b4a6b25241c6178",
  "darwin-aarch64": "b4b3a741c08ae62495ba8d0326c821ddf7001068518c7cf98fd8bc791d1d18e8",
  "windows-amd64": "85a4c05b832f4fa9ff4fd0b04582fd9496c971baad86ab54b277358023f24590",
};
export async function runPackagingCase(java: string, jar: string, nativeHostSha256: string, evidence: string, runId: string, cases: CaseEvidence[], expected?: { release: string; sourceRevision: string; platforms: Record<string, string> }): Promise<void> {
  const family = cases.find(item => item.id === "packaging.native-host")!;
  const observations: any[] = [];
  family.findings = [];
  try {
    const listing = Bun.spawnSync([resolve(dirname(java), "jar"), "tf", jar], { stdout: "pipe", stderr: "pipe" });
    assert.equal(listing.exitCode, 0, listing.stderr.toString());
    const entries = listing.stdout.toString().split("\n").filter(name => name.startsWith("splice/codemode/native/") && !name.endsWith("/"));
    assert.equal(new Set(entries).size, entries.length, "duplicate native jar entries are forbidden");
    const current = `${process.platform === "win32" ? "windows" : process.platform}-${process.arch === "x64" ? "amd64" : process.arch === "arm64" ? "aarch64" : process.arch}`;
    let shippedCurrent = false;
    for (const [target, pinned] of Object.entries(expected?.platforms ?? supported)) {
      const prefix = `splice/codemode/native/${target}/`;
      const shipped = entries.filter(name => name.startsWith(prefix));
      if (!shipped.length) { observations.push({ target, shipped: false, executed: false }); continue; }
      assert.deepEqual(shipped.sort(), [prefix + "codex-code-mode-host.gz", prefix + "host.properties"].sort());
      const properties = zipEntry(jar, prefix + "host.properties").toString("utf8");
      const values = new Map(properties.split(/\r?\n/).filter(line => line && !line.startsWith("#")).map(line => {
        const equals = line.indexOf("="); assert.ok(equals > 0); return [line.slice(0, equals).trim(), line.slice(equals + 1).trim()];
      }));
      assert.equal(values.get("sha256"), pinned); assert.equal(values.get("release"), expected?.release ?? "0.159.1");
      if (expected) assert.equal(values.get("sourceRevision"), expected.sourceRevision, `${target} packaged source provenance`);
      const bytes = gunzipSync(zipEntry(jar, prefix + "codex-code-mode-host.gz"));
      assert.equal(sha256(bytes), pinned, `${target} payload is not the pinned supported host`);
      if (target === current) { assert.equal(pinned, nativeHostSha256); shippedCurrent = true; }
      observations.push({ target, shipped: true, executed: target === current, properties, rawBytes: bytes.length, rawSha256: sha256(bytes) });
    }
    assert.ok(shippedCurrent, "candidate has no pinned native host for the executing platform");
    assert.equal(entries.length, observations.filter(value => value.shipped).length * 2, "unknown native target or payload entry");
    const runtime = cases.find(item => item.id === "runtime.module-semantics")!;
    assert.equal(runtime.state, "executed", "packaging needs independently inspected production adapter native-child identity");
    const trace = await Bun.file(runtime.artifacts.find(value => value.path.endsWith(".candidate.json"))!.path).json();
    assert.ok(basename(dirname(trace.observedIdentity.nativeExePath)).startsWith("splice-native-"),
      "production runtime used a local Codex installation instead of extracting the bundled pinned payload");
    family.artifacts.push(...runtime.artifacts);
  } catch (error) { family.findings.push(error instanceof Error ? error.message : String(error)); }
  const path = resolve(evidence, `${runId}.packaging.native-host.json`);
  await writeFile(path, JSON.stringify({ schema: 1, runId, caseId: family.id, observations,
    scope: "Every shipped target payload is hash verified; runtime execution is verified only on the current supported platform." }, null, 2) + "\n");
  family.artifacts.push(await artifact(path));
  family.state = family.findings.length ? "failed" : "executed";
  console.log(`${family.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${family.id}`);
}
