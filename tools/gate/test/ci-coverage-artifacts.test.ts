import { expect, test } from "bun:test";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { retainCoverage, collectCoverage } from "../src/ci-coverage/artifacts.ts";
import { layout } from "../src/lib/repo.ts";

test("coverage merge accepts exactly the complete candidate's binaries and rejects missing, stale or altered shards", () => {
  const root = mkdtempSync("/tmp/splice-coverage-");
  try {
    const settings = readFileSync(join(layout().repoRoot, "settings.gradle.kts"), "utf8");
    writeFileSync(join(root, "settings.gradle.kts"), settings);
    const modules = [...settings.matchAll(/project\("(:[^"]+)"\)\.projectDir = file\("([^"]+)"\)/g)]
      .filter(match => match[1] !== ":integrations-mcp-hub");
    for (const match of modules) {
      const dir = join(root, match[2]!, "build/reports/kover");
      mkdirSync(dir, { recursive: true });
      writeFileSync(join(dir, "report.bin"), "binary for " + match[1]);
    }
    const input = join(root, "incoming");
    for (let shard = 0; shard < 4; shard++) retainCoverage(root, join(input, String(shard)), shard, "candidate");
    expect(collectCoverage(root, input, "candidate")).toHaveLength(modules.length);
    expect(() => collectCoverage(root, input, "stale")).toThrow();
    const receipt = join(input, "0/receipt.json");
    const original = readFileSync(receipt, "utf8");
    rmSync(receipt);
    expect(() => collectCoverage(root, input, "candidate")).toThrow();
    writeFileSync(receipt, original);
    const binary = readdirSync(join(input, "0"), { recursive: true }).find(path => String(path).endsWith(".bin"))!;
    writeFileSync(join(input, "0", String(binary)), "altered");
    expect(() => collectCoverage(root, input, "candidate")).toThrow();
    rmSync(join(root, "app/build/reports/kover/report.bin"));
    expect(() => retainCoverage(root, join(root, "missing"), 0, "candidate")).toThrow();
  } finally { rmSync(root, { recursive: true, force: true }); }
});
