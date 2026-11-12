import { copyFileSync, mkdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { files, hash, json, safePath, save } from "../ci/files.ts";
import { coveragePlan } from "./plan.ts";

interface Receipt {
  candidate: string;
  shard: number;
  plan: string;
  reports: { task: string; path: string; hash: string }[];
}
function inventory(root: string) {
  const settings = readFileSync(join(root, "settings.gradle.kts"), "utf8");
  const plan = coveragePlan(settings);
  const dirs = new Map([...settings.matchAll(/project\("(:[^"]+)"\)\.projectDir = file\("([^"]+)"\)/g)]
    .map(match => [match[1]!, match[2]!]));
  const reports = plan.map(shard => shard.reports.map(task => {
    const dir = dirs.get(task.slice(0, task.lastIndexOf(":")));
    if (!dir) throw new Error("No project directory for " + task);
    return { task, path: dir + "/build/reports/kover/report.bin" };
  }));
  return { reports, digest: hash(JSON.stringify(reports)) };
}
export function retainCoverage(root: string, output: string, shard: number, candidate: string): void {
  const { reports, digest } = inventory(root);
  const owned = reports[shard];
  if (!owned) throw new Error("Unknown coverage shard");
  const records = owned.map(report => {
    const source = safePath(root, report.path), bytes = readFileSync(source);
    if (!bytes.length) throw new Error("Empty coverage report " + report.path);
    const target = safePath(output, report.path);
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(source, target);
    return { ...report, hash: hash(bytes) };
  });
  save(join(output, "receipt.json"), { candidate, shard, plan: digest, reports: records });
}
export function collectCoverage(root: string, input: string, candidate: string): string[] {
  const { reports, digest } = inventory(root);
  const receipts = files(input).filter(path => path.endsWith("/receipt.json"));
  if (receipts.length !== reports.length) throw new Error("Missing or duplicate coverage shards");
  const seen = new Set<number>(), result: string[] = [];
  for (const path of receipts) {
    const receipt = json(path) as Receipt;
    if (receipt.candidate !== candidate || receipt.plan !== digest || seen.has(receipt.shard) || !reports[receipt.shard]) throw new Error("Stale or duplicate coverage shard");
    seen.add(receipt.shard);
    const owned = reports[receipt.shard]!;
    if (JSON.stringify(receipt.reports.map(({task, path}) => ({task, path}))) !== JSON.stringify(owned)) throw new Error("Incomplete coverage ownership");
    for (const report of receipt.reports) {
      const source = safePath(dirname(path), report.path);
      if (hash(readFileSync(source)) !== report.hash) throw new Error("Corrupt coverage binary");
      result.push(source);
    }
  }
  return result;
}
