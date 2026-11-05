import observedDurations from "../../config/ci-task-durations.json";

export interface TaskNode { path: string; deps: string[]; kind: string; actions: boolean; }
export interface Worker { id: string; tasks: string[]; direct: string[]; }
export interface Plan { workers: Worker[]; obligations: string[]; sharded: string[]; discovery: boolean; }
export interface Receipt { worker: string; candidate: string; plan: string; ok: boolean; tasks: string[]; direct: string[]; }
const HEAVY = new Set([":app:test", ":features-turns:test"]);
const PACKAGED = new Set([":oracleReplay", ":conformance", ":launchOwnerPackagedProof", ":codeModePackagedMock", ":app:codeModePackagedTest"]);
const TOOLING = new Set([":gateTests", ":hookTests", ":releaseTests", ":codeModeQualificationGuards"]);

/** Partition the REAL selected task closure, retaining new/unrecognized terminal actions. */
export function partitionGraph(tasks: TaskNode[], direct: string[], count: number): Plan {
  if (!Number.isInteger(count) || count < 1 || count > 8) throw new Error("Invalid JVM worker count");
  const byPath = new Map(tasks.map((task) => [task.path, task]));
  if (byPath.size !== tasks.length) throw new Error("Duplicate task identity");
  const visiting = new Set<string>(), ancestors = new Map<string, Set<string>>();
  const dependencies = (id: string): Set<string> => {
    const cached = ancestors.get(id); if (cached) return cached;
    const task = byPath.get(id); if (!task) throw new Error(`Unknown task dependency ${id}`);
    if (visiting.has(id)) throw new Error("Cyclic task dependencies");
    visiting.add(id);
    const result = new Set(task.deps.flatMap((dep) => [dep, ...dependencies(dep)]));
    visiting.delete(id); ancestors.set(id, result); return result;
  };
  for (const task of tasks) dependencies(task.path);
  const actions = tasks.filter((task) => task.actions && task.kind !== "aggregate" && task.path !== ":verifyTestDiscovery");
  const prerequisites = new Set(actions.flatMap((task) => [...dependencies(task.path)]));
  const obligations = actions.filter((task) => ["test", "ladder", "verification"].includes(task.kind) || !prerequisites.has(task.path));
  const ids = ["source", "tooling", "browser", "architecture", "native", "packaged", ...Array.from({ length: count }, (_, i) => `jvm-${i}`)];
  const workers: Worker[] = ids.map((id) => ({ id, tasks: [], direct: [] }));
  const owner = (id: string) => workers.find((worker) => worker.id === id)!;
  const durations: Record<string, number> = observedDurations;
  const loads = Array.from({ length: count }, () => 0);
  const assignJvm = (path: string) => {
    const index = loads.indexOf(Math.min(...loads));
    loads[index]! += durations[path] ?? 30;
    return `jvm-${index}`;
  };
  const sharded: string[] = [];
  const group = (path: string, kind: string): string => {
    if (path === ":consoleTests") return "browser";
    if (TOOLING.has(path)) return "tooling";
    if (path.startsWith(":quality-architecture:")) return "architecture";
    if (path.startsWith(":integrations-mcp-hub:") || path === ":mcpHubBinaries") return "native";
    if (PACKAGED.has(path) || path === ":app:shadowJar") return "packaged";
    if (kind === "test") return assignJvm(path);
    return "source";
  };
  for (const task of obligations.sort((a,b) => (durations[b.path] ?? 30) - (durations[a.path] ?? 30) || a.path.localeCompare(b.path))) {
    if (task.kind === "test" && HEAVY.has(task.path)) {
      sharded.push(task.path);
      for (let i = 0; i < count; i++) { owner(`jvm-${i}`).tasks.push(task.path); loads[i]! += (durations[task.path] ?? 30) / count; }
    } else owner(group(task.path, task.kind)).tasks.push(task.path);
  }
  for (const leg of direct) owner(group(`:${leg}`, "ladder")).direct.push(leg);
  // Every selected executable task is either an obligation or an input of one.
  const covered = new Set(obligations.flatMap((task) => [task.path, ...dependencies(task.path)]));
  for (const task of actions) if (!covered.has(task.path)) throw new Error(`Unowned task ${task.path}`);
  return { workers, obligations: obligations.map((task) => task.path), sharded, discovery: byPath.has(":verifyTestDiscovery") };
}

export function verifyReceipts(plan: Plan, receipts: Receipt[], candidate: string, digest: string): void {
  if (receipts.length !== plan.workers.length) throw new Error("Missing or extra worker receipts");
  const seen = new Set<string>();
  for (const receipt of receipts) {
    const worker = plan.workers.find((entry) => entry.id === receipt.worker);
    if (!worker || seen.has(receipt.worker)) throw new Error("Unknown or duplicate worker receipt");
    seen.add(receipt.worker);
    if (!receipt.ok || receipt.candidate !== candidate || receipt.plan !== digest) throw new Error(`Failed or stale worker ${receipt.worker}`);
    if (JSON.stringify(receipt.tasks) !== JSON.stringify(worker.tasks) || JSON.stringify(receipt.direct) !== JSON.stringify(worker.direct)) throw new Error(`Wrong ownership for ${receipt.worker}`);
  }
}
