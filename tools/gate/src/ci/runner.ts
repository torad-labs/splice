import { existsSync, mkdirSync, readFileSync, copyFileSync, appendFileSync, rmSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { pathsSince, readLadder, runDirectLegs } from "../commands/hook.ts";
import { ciSelection } from "../lib/ci-selection.ts";
import { layout } from "../lib/repo.ts";
import { runUnderSlot } from "../lib/slot.ts";
import { files, hash, json, safePath, save } from "./files.ts";
import { partitionGraph, verifyReceipts, type Plan, type Receipt, type TaskNode } from "./plan.ts";

interface CandidatePlan extends Plan { candidate: string; digest: string; main: boolean; graph: TaskNode[]; }
interface FileRecord { path: string; hash: string; }
interface WorkerReceipt extends Receipt { files: FileRecord[]; products?: FileRecord[]; }
const PRODUCTS = ["app/build/libs/app-all.jar", "app/src/main/dist/bin/splice-launch", "integrations/mcp-hub/build/bin/linuxX64/holderReleaseExecutable/splice-mcp-holder.kexe", "integrations/mcp-hub/build/bin/linuxX64/frontReleaseExecutable/splice-mcp-front.kexe"];
const root = () => layout().repoRoot;
const location = () => join(root(), ".ci", "plan.json");
const sha = () => {
  const result = Bun.spawnSync(["git", "rev-parse", "HEAD"], { cwd: root() });
  if (result.exitCode !== 0) throw new Error("Cannot bind candidate");
  return result.stdout.toString().trim();
};
const gradle = (args: string[], env: Record<string,string> = {}) => runUnderSlot({ layout: layout(), label: "ci-partition", args: ["--no-build-cache", "--no-configuration-cache", "--console=plain", "--continue", ...args], env: { ...env, SPLICE_CI_ROOT: root() } });
const planRead = (): CandidatePlan => {
  const plan = json(location()) as CandidatePlan;
  const { digest, ...body } = plan;
  if (hash(JSON.stringify(body)) !== digest || plan.candidate !== sha()) throw new Error("Stale or corrupt candidate plan");
  return plan;
};

export async function prepare(base?: string): Promise<number> {
  const main = base === undefined;
  const scope = base === undefined ? undefined : ciSelection(root(), pathsSince(root(), base));
  const tasks = scope ? [...scope.gradle] : ["gateOfRecord"];
  const graphPath = join(root(), ".ci", "graph.json");
  let graph: TaskNode[] = [];
  if (tasks.length) {
    const rc = await gradle(["-I", "tools/gate/ci.init.gradle", "--dry-run", ...tasks], { SPLICE_CI_GRAPH: graphPath });
    if (rc !== 0) return rc;
    graph = json(graphPath) as TaskNode[];
    if (!graph.length) throw new Error("Selected Gradle tasks produced no graph");
  }
  // Included-build tasks are a separate Gradle graph, not root subproject nodes.
  const external = main ? ["build-logic:test", "build-logic:detekt", "build-logic:ktlintCheck"] : tasks.filter((task) => task.startsWith("build-logic:"));
  const plan = partitionGraph(graph, scope?.direct.map((leg) => leg.task) ?? [], 4);
  plan.workers.find((worker) => worker.id === "source")!.tasks.push(...external);
  if (main) plan.workers.push({ id: "rehearsal", tasks: [], direct: [] });
  const body = { ...plan, candidate: sha(), main, graph };
  const bound = { ...body, digest: hash(JSON.stringify(body)) };
  save(location(), bound);
  const active = plan.workers.filter((worker) => worker.tasks.length || worker.direct.length || worker.id === "rehearsal");
  // Inactive workers still have explicit receipts, written here and checked by the collector.
  const empty = join(root(), ".ci", "empty");
  for (const previous of files(empty)) {
    if (!previous.endsWith("/receipt.json")) throw new Error("Unexpected file in generated empty receipts");
  }
  rmSync(empty, { recursive: true, force: true });
  for (const worker of plan.workers.filter((entry) => !active.includes(entry))) save(join(root(), ".ci", "empty", worker.id, "receipt.json"), { ...worker, worker: worker.id, candidate: body.candidate, plan: bound.digest, ok: true, files: [] });
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `matrix=${JSON.stringify({ include: active.map((worker) => ({ worker: worker.id })) })}\n`);
  console.log(JSON.stringify({ workers: active.map((worker) => worker.id), selectedTasks: graph.length, obligations: plan.obligations.length }));
  return 0;
}

export async function execute(id: string): Promise<number> {
  const plan = planRead();
  if (plan.main && process.env.GITHUB_ACTIONS !== "true") throw new Error("Main CI workers require an isolated Actions checkout; refusing to clean the shared local tree");
  const worker = plan.workers.find((entry) => entry.id === id);
  if (!worker) throw new Error(`Unknown worker ${id}`);
  const output = join(root(), ".ci", "out", id), records: FileRecord[] = [];
  mkdirSync(output, { recursive: true });
  let ok = false;
  try {
    let rc = 0;
    if (id === "rehearsal") {
      const child = Bun.spawn(["bun", "tools/release", "verify"], { cwd: root(), stdout: "inherit", stderr: "inherit" });
      rc = await child.exited;
    } else {
      const excluded = plan.obligations.filter((task) => !worker.tasks.includes(task));
      const shardTasks = worker.tasks.filter((task) => plan.sharded.includes(task));
      const env: Record<string,string> = {};
      if (shardTasks.length) {
        const shardFile = join(root(), ".ci", "shard.json");
        const weightsFile = join(root(), "tools/gate/config/ci-test-durations.json");
        save(shardFile, { tasks: shardTasks, index: Number(id.slice(4)), count: 4, weights: existsSync(weightsFile) ? json(weightsFile) : {}, output: join(output, "shards") });
        env.SPLICE_CI_SHARDS = shardFile;
      }
      const trace = join(output, "tasks.jsonl"); env.SPLICE_CI_TRACE = trace;
      if (worker.tasks.length) {
        if (plan.main) { rc = await gradle(["clean"]); if (rc !== 0) return rc; }
        rc = await gradle(["-I", "tools/gate/ci.init.gradle", "--profile", ...worker.tasks, ...excluded.flatMap((task) => ["-x", task])], env);
        if (rc === 0) {
          const observed = new Set(readFileSync(trace, "utf8").trim().split("\n").filter(Boolean).map((line) => (JSON.parse(line) as {path:string}).path));
          for (const task of worker.tasks.filter((task) => !task.startsWith("build-logic:"))) if (!observed.has(task)) throw new Error(`Worker did not execute ${task}`);
        }
      }
      const legs = readLadder(root()).filter((leg) => worker.direct.includes(leg.task));
      if (legs.length !== worker.direct.length) throw new Error("Direct-leg inventory changed");
      const failed = await runDirectLegs(root(), legs);
      if (failed.length) rc = 1;
    }
    ok = rc === 0;
    return rc;
  } finally {
    const reportFiles = plan.graph.filter((task) => task.kind === "test").flatMap((task) => {
      // Actual report directories are exported by Gradle, not inferred from module names.
      const extra = task as TaskNode & { reports?: string };
      return extra.reports ? files(safePath(root(), extra.reports)).filter((file) => file.endsWith(".xml")) : [];
    });
    for (const file of new Set(reportFiles)) {
      const path = relative(root(), file), dest = safePath(join(output, "reports"), path);
      mkdirSync(dirname(dest), { recursive: true }); copyFileSync(file, dest); records.push({ path, hash: hash(readFileSync(file)) });
    }
    const products: FileRecord[] = [];
    if (plan.main && ok && (id === "packaged" || id === "native")) {
      for (const path of PRODUCTS.filter((path) => (id === "packaged") === path.startsWith("app/"))) {
        const file = safePath(root(), path), dest = safePath(join(output,"products"),path);
        if (!existsSync(file)) { ok = false; break; }
        mkdirSync(dirname(dest), { recursive:true }); copyFileSync(file,dest); products.push({path,hash:hash(readFileSync(file))});
      }
    }
    save(join(output, "receipt.json"), { worker: id, candidate: plan.candidate, plan: plan.digest, ok, tasks: worker.tasks, direct: worker.direct, files: records, products });
    if (plan.main && (id === "packaged" || id === "native") && products.length !== 2) throw new Error(`Missing main products from ${id}`);
  }
}

export async function collect(directory: string): Promise<number> {
  const plan = planRead();
  const paths = [...files(resolve(directory)), ...files(join(root(), ".ci", "empty"))].filter((file) => file.endsWith("/receipt.json"));
  const receipts = paths.map((file) => json(file) as WorkerReceipt);
  verifyReceipts(plan, receipts, sha(), plan.digest);
  const copied = new Map<string,string>(), productPaths = new Set<string>();
  for (let i=0;i<paths.length;i++) {
    const receipt = receipts[i]!, from = dirname(paths[i]!);
    for (const item of receipt.products ?? []) {
      if (!PRODUCTS.includes(item.path) || productPaths.has(item.path)) throw new Error("Unknown or duplicate build product");
      const input=safePath(join(from,"products"),item.path), content=readFileSync(input);
      if(hash(content)!==item.hash)throw new Error("Build product digest mismatch");
      const dest=safePath(root(),item.path);mkdirSync(dirname(dest),{recursive:true});copyFileSync(input,dest);productPaths.add(item.path);
    }
    for (const item of receipt.files) {
      if (!/\/build\/test-results\/.+\.xml$/.test(item.path)) throw new Error("Unexpected report path");
      const content = readFileSync(safePath(join(from, "reports"), item.path));
      if (hash(content) !== item.hash) throw new Error("Report digest mismatch");
      if (copied.has(item.path) && copied.get(item.path) !== item.hash) throw new Error(`Conflicting test report ${item.path}`);
      copied.set(item.path,item.hash);
      const dest = safePath(root(),item.path); mkdirSync(dirname(dest),{recursive:true}); copyFileSync(safePath(join(from,"reports"),item.path),dest);
    }
  }
  if(plan.main&&PRODUCTS.some(path=>!productPaths.has(path)))throw new Error("Incomplete main product inventory");
  for (const task of plan.sharded) {
    const manifests = files(resolve(directory)).filter((file)=>file.endsWith(`/shards/${task.replaceAll(":","_")}.json`)).map((file)=>json(file) as {candidates:string[];selected:string[];index:number;count:number});
    if (manifests.length!==4 || new Set(manifests.map(m=>m.index)).size!==4) throw new Error(`Missing class shard ${task}`);
    const inventory=JSON.stringify(manifests[0]!.candidates),selected=manifests.flatMap(m=>m.selected);
    if(manifests.some(m=>m.count!==4||JSON.stringify(m.candidates)!==inventory)||new Set(selected).size!==selected.length||JSON.stringify([...selected].sort())!==inventory)throw new Error(`Incomplete class partition ${task}`);
  }
  if (plan.discovery) {
    // All producers were checked above and their same-candidate XML restored. Run the
    // existing discovery implementation without repeating its producer tasks.
    const tests=plan.graph.filter(task=>task.kind==='test').map(task=>task.path);
    const rc=await gradle(["verifyTestDiscovery",...tests.flatMap(task=>["-x",task])]);
    if(rc!==0)return rc;
  }
  console.log(`GATE: PASS — ${receipts.length} exact-candidate worker receipts; ${copied.size} test reports`);
  return 0;
}
