import assert from "node:assert/strict";
import { copyFile, mkdir, readFile, readdir } from "node:fs/promises";
import { resolve } from "node:path";
import { capturedCommand } from "./current-command";
import { preDispatchControl, preDispatchInputSha256, preDispatchScenario } from "./current-pre-dispatch-contract";
import { preDispatchArtifact, preDispatchBoundedArgs, preDispatchDriver, preDispatchVerify, preDispatchWrite } from "./current-pre-dispatch-io";
import type { Artifact, Identity } from "./qualification";

export type PreDispatchKotlinCompiler = { compiler: Artifact; dependencies: Artifact[]; version: "2.3.21" };
export type PreDispatchCandidateOptions = {
  jar: Identity; java: Identity; compiler: PreDispatchKotlinCompiler; directory: string; runId: string;
};
export type PreDispatchCandidate = { directory: string; config: Artifact; driver: Artifact; command: Artifact; result: Artifact;
  compile: Artifact; receipt: Artifact; recipe: Artifact; events: Artifact; posted: Artifact; nativeDecoded: Artifact; files: Artifact[] };
async function retireOwnedNative(directory: string): Promise<void> {
  let events: any[]; try { events = (await readFile(resolve(directory, "events.jsonl"), "utf8")).trimEnd().split("\n").map(line => JSON.parse(line)); } catch { return; }
  const owned = events.filter(event => event.kind === "native_process");
  const cleanup: unknown[] = [];
  for (const event of owned) {
    const pid = event.value.pid, startTicks = event.value.stat.slice(event.value.stat.lastIndexOf(")") + 2).split(" ")[19];
    const same = async () => { try { const stat = await readFile(`/proc/${pid}/stat`, "utf8"); return stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19] === startTicks; } catch { return false; } };
    const deadline = Date.now() + 2000; while (await same() && Date.now() < deadline) await Bun.sleep(25);
    const leaked = await same(); if (leaked) process.kill(pid, "SIGKILL");
    cleanup.push({ pid, startTicks, leaked, action: leaked ? "killed exact owned lifetime" : "already exited" });
  }
  await preDispatchWrite(resolve(directory, "cleanup.json"), cleanup);
  assert.ok(cleanup.every((item: any) => !item.leaked), "native process survived ordinary fixture shutdown; killed only its attested lifetime");
}
/** Only the transparent fixture classes are compiled. All production code is loaded from the exact candidate jar. */
export async function producePreDispatchCandidate(options: PreDispatchCandidateOptions): Promise<void> {
  const jar = await preDispatchArtifact(options.jar.path); assert.equal(jar.sha256, options.jar.sha256);
  await preDispatchVerify(options.java); await preDispatchVerify(options.compiler.compiler);
  for (const dependency of options.compiler.dependencies) await preDispatchVerify(dependency);
  assert.equal(options.compiler.version, "2.3.21");
  const fixtures = ["PendingDeliveryPrototype", "NativeStreamDecoder"], sourceFiles: Array<{ fixture: Artifact; source: Artifact }> = [];
  for (const name of fixtures) {
    const fixture = await preDispatchArtifact(preDispatchDriver(`${name}.kt.fixture`)), target = resolve(options.directory, `${name}.kt`);
    await copyFile(fixture.path, target); sourceFiles.push({ fixture, source: await preDispatchArtifact(target) });
  }
  const output = resolve(options.directory, "pending-delivery.jar"), classpath = [options.compiler.compiler.path, jar.path, ...options.compiler.dependencies.map(pin => pin.path)].join(":");
  const version = await capturedCommand([options.java.path, "-Xmx1g", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-version"], options.directory);
  assert.equal(version.exitCode, 0); assert.match(version.stdout + version.stderr, /kotlinc-jvm 2\.3\.21\b/);
  const argv = [options.java.path, "-Xmx1g", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
    "-classpath", jar.path, `-Xfriend-paths=${jar.path}`, "-jvm-target", "21", "-d", output, ...sourceFiles.map(pair => pair.source.path)];
  const command = await capturedCommand(argv, options.directory, undefined, 60_000);
  const compile = { schema: 1, command, version, candidate: jar, java: options.java, compiler: options.compiler, sourceFiles,
    output: command.exitCode === 0 ? await preDispatchArtifact(output) : null, gradle: false };
  await preDispatchWrite(resolve(options.directory, "compile.json"), compile); assert.equal(command.exitCode, 0, command.stderr);
  const listing = await capturedCommand(["unzip", "-Z1", output], options.directory);
  assert.equal(listing.exitCode, 0); const entries = listing.stdout.trimEnd().split("\n");
  assert.ok(entries.length > 0 && entries.every(name => name.startsWith("splice/dispatchprototype/") || name.startsWith("META-INF/")), "fixture jar shadows production classes");
  await preDispatchWrite(resolve(options.directory, "fixture-entries.json"), entries);
  const recipe = { ...preDispatchScenario(options.runId), inputSha256: preDispatchInputSha256, candidateJar: jar,
    scope: "Linux fixture-only controlled scheduling; no inference or installed-state mutation", boundary: preDispatchControl.candidateBoundary };
  await preDispatchWrite(resolve(options.directory, "recipe.json"), recipe);
  await mkdir(resolve(options.directory, "native-tmp"));
  const execute = [options.java.path, "-Xmx1g", `-Djava.io.tmpdir=${resolve(options.directory, "native-tmp")}`, `-Duser.home=${resolve(options.directory, "private-home")}`,
    "-cp", `${output}:${jar.path}`, "splice.dispatchprototype.PendingDeliveryPrototype", options.directory];
  const run = await capturedCommand(execute, options.directory, undefined, 60_000);
  await preDispatchWrite(resolve(options.directory, "process.json"), run);
  await retireOwnedNative(options.directory);
  const cgroup = (await readFile("/proc/self/cgroup", "utf8")).trim().split("::")[1];
  const limits: Record<string, string> = {};
  for (const name of ["memory.max", "memory.swap.max", "memory.peak", "memory.events"]) limits[name] = await readFile(`/sys/fs/cgroup${cgroup}/${name}`, "utf8");
  await preDispatchWrite(resolve(options.directory, "limits.json"), { cgroup, ...limits });
  assert.equal(run.exitCode, 0, run.stderr);
  const decode = await capturedCommand([options.java.path, "-Xmx256m", "-cp", `${output}:${jar.path}`, "splice.dispatchprototype.NativeStreamDecoder", options.directory], options.directory, undefined, 30_000);
  await preDispatchWrite(resolve(options.directory, "native-decode-command.json"), decode); assert.equal(decode.exitCode, 0, decode.stderr);
  await preDispatchVerify(jar); for (const pair of sourceFiles) { await preDispatchVerify(pair.fixture); await preDispatchVerify(pair.source); }
  const result = JSON.parse((await readFile(resolve(options.directory, "result.json"))).toString());
  for (const origin of Object.values(result.classOrigins)) assert.equal(origin, jar.path);
  await preDispatchWrite(resolve(options.directory, "receipt.json"), { schema: 1, inputSha256: preDispatchInputSha256, runId: options.runId,
    jar, processPid: run.pid, nativeHostSha256: preDispatchControl.nativeHostSha256, qualified: false, behavioralCoverage: false,
    disclosure: "The public notification delivery permit is distinct from the source watch readiness gate. Request cancellation alone does not authorize interruption." });
}
export async function executePreDispatchCandidate(options: PreDispatchCandidateOptions & { bun: Identity }): Promise<PreDispatchCandidate> {
  await mkdir(options.directory);
  const config = await preDispatchWrite(resolve(options.directory, "input.json"), options), driver = await preDispatchArtifact(preDispatchDriver("candidate-driver.ts"));
  const argv = preDispatchBoundedArgs(`splice-parity-pre-dispatch-${options.runId}-3`, [options.bun.path, driver.path, config.path]);
  const result = await capturedCommand(argv, options.directory, undefined, 195_000);
  const command = await preDispatchWrite(resolve(options.directory, "command.json"), result); assert.equal(result.exitCode, 0, result.stderr);
  const artifact = (name: string) => preDispatchArtifact(resolve(options.directory, name));
  const files = ["process.json", "cleanup.json", "pending-delivery.jar", "PendingDeliveryPrototype.kt", "NativeStreamDecoder.kt", "fixture-entries.json", "limits.json",
    "native-client-to-host.raw", "native-host-to-client.raw", "native-decode-command.json"];
  const storage = await readdir(resolve(options.directory, "records"), { recursive: true, withFileTypes: true });
  for (const entry of storage) if (entry.isFile()) files.push(resolve(entry.parentPath, entry.name));
  return { directory: options.directory, config, driver, command, compile: await artifact("compile.json"), receipt: await artifact("receipt.json"),
    recipe: await artifact("recipe.json"), result: await artifact("result.json"), events: await artifact("events.jsonl"), posted: await artifact("posted.json"),
    nativeDecoded: await artifact("native-decoded.json"), files: await Promise.all(files.map(artifact)) };
}
