import assert from "node:assert/strict";
import { mkdir, readdir } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { capturedCommand } from "./current-command";
import { assertSourceCliBuild, type CliSourceBuildProof } from "./current-cli-source-profile";
import type { CurrentInventory } from "./current-inventory";
import type { Artifact, Identity } from "./qualification";
import { preDispatchControl, preDispatchInputSha256, type PreDispatchEvent } from "./current-pre-dispatch-contract";
import { preDispatchArtifact, preDispatchBoundedArgs, preDispatchChecked, preDispatchDriver, preDispatchVerify, preDispatchWrite } from "./current-pre-dispatch-io";

export type PreDispatchSource = {
  directory: string; build: Artifact; config: Artifact; command: Artifact; driver: Artifact;
  recipe: Artifact; receipt: Artifact; controls: Artifact; mi: Artifact; terminal: Artifact; nativeEvents: Artifact;
  requests: Artifact; frames: Artifact; profileRaw: Artifact; files: Artifact[];
};
const eventRows = (text: string): PreDispatchEvent[] => text.trimEnd().split("\n").filter(Boolean).map(line => JSON.parse(line));
export function assertPreDispatchSourceControls(receipt: any, events: PreDispatchEvent[], mi: string): void {
  assert.equal(receipt.prototypePassed, true);
  assert.equal(receipt.before.cli.sha256, preDispatchControl.sourceCliSha256); assert.deepEqual(receipt.before, receipt.after);
  assert.equal(receipt.interrupted.notified, false); assert.equal(receipt.interrupted.aborted, true);
  assert.equal(receipt.taskRequests, 4); assert.ok(receipt.hosts.length > 0);
  for (const host of receipt.hosts) assert.equal(host.sha256, preDispatchControl.nativeHostSha256);
  assert.equal(receipt.resourceLimits.memoryMax, "4294967296"); assert.equal(receipt.resourceLimits.swapMax, "0");
  assert.ok(Number(receipt.resourceLimits.memoryPeak) <= preDispatchControl.scheduling.memoryBytes);
  const once = (kind: string) => { const rows = events.filter(event => event.kind === kind); assert.equal(rows.length, 1, kind); return rows[0]!.value; };
  const attested = once("attested_inferior"); assert.equal(attested.pid, receipt.pid); assert.equal(attested.startTicks, receipt.startTicks);
  assert.match(attested.execStop, /reason="exec"/);
  const point = once("gate_breakpoints"); assert.equal(new Set([point.readyId, point.waitId, point.removeId]).size, 3);
  const commands = events.filter(event => event.kind === "mi_command").map(event => ({ token: event.value.token, input: String(event.value.input) }));
  for (const symbol of Object.values(preDispatchControl.symbols)) assert.ok(commands.some(command => command.input === `-data-evaluate-expression ${JSON.stringify(`&'${symbol}'`)}`));
  assert.deepEqual(commands.filter(command => command.input.startsWith("-break-insert")).map(command => command.input), [
    `-break-insert -h -i 1 *0x${point.ready}`, `-break-insert -h *0x${point.wait}`, `-break-insert -h *0x${point.remove}`,
  ]);
  assert.ok(commands.some(command => command.input === '-interpreter-exec console "catch exec"'));
  assert.ok(mi.includes("shlib events"), "GDB loader software breakpoint disclosure missing from raw maintenance output");
  assert.ok(!commands.some(command => /write-memory|exec-jump|set var|return |call |start --|starti/.test(command.input)), "debugger mutated values or skipped ordinary execution");
  const waiting = once("gate_wait_false_observed"), interrupt = once("pty_interrupt_before_ready"), removed = once("removed_before_ready_released");
  const thread = (line: string) => /thread-id="(\d+)"/.exec(line)?.[1];
  assert.ok(thread(waiting.readyStop) && thread(waiting.waitStop) && thread(waiting.readyStop) !== thread(waiting.waitStop));
  assert.equal(interrupt.readyThread, thread(waiting.readyStop)); assert.equal(removed.readyThread, interrupt.readyThread);
  assert.equal(new Set(waiting.threadNames.filter((item: any) => item.name.includes("tokio")).map((item: any) => item.id)).size >= 2, true);
  const checks = commands.filter(command => command.input === `-thread-info ${interrupt.readyThread}`);
  assert.equal(checks.length, 2); for (const check of checks) {
    const result = mi.split("\n").find(line => line.startsWith(`${check.token}^done`)); assert.ok(result?.includes('state="stopped"'));
  }
  assert.equal(once("owned_gdb").identity.sha256.length, 64);
}
export async function executePreDispatchSource(options: {
  build: Artifact; inventory: CurrentInventory; host: Identity; gdb: Identity; bun: Identity; directory: string; runId: string;
}): Promise<PreDispatchSource> {
  assert.equal(process.platform, "linux"); assert.match(options.runId, /^[a-f0-9]{32}$/);
  const build = JSON.parse((await preDispatchChecked(options.build)).toString()) as CliSourceBuildProof;
  await assertSourceCliBuild(build, options.inventory);
  assert.equal(build.sourceRevision, preDispatchControl.sourceRevision); assert.equal(build.binary.sha256, preDispatchControl.sourceCliSha256);
  assert.equal(options.host.sha256, preDispatchControl.nativeHostSha256); assert.equal(dirname(build.binary.path), dirname(options.host.path));
  for (const tool of [options.gdb, options.bun, options.host]) await preDispatchVerify(tool);
  const driver = await preDispatchArtifact(preDispatchDriver("source-driver.ts"));
  await mkdir(dirname(options.directory), { recursive: true });
  const catalogCommand = await capturedCommand(["git", "-C", build.sourceRepository, "show", `${build.sourceRevision}:codex-rs/models-manager/models.json`], dirname(options.directory));
  assert.equal(catalogCommand.exitCode, 0, catalogCommand.stderr);
  const catalogPath = options.directory + ".catalog.json"; await Bun.write(catalogPath, catalogCommand.stdout);
  const config = await preDispatchWrite(options.directory + ".config.json", {
    schema: 1, inputSha256: preDispatchInputSha256, control: preDispatchControl, directory: options.directory,
    cli: build.binary, host: options.host, gdb: options.gdb, nonce: options.runId, catalog: await preDispatchArtifact(catalogPath),
  });
  const argv = preDispatchBoundedArgs(`splice-parity-pre-dispatch-${options.runId}-1`, [options.bun.path, driver.path, config.path]);
  const command = await preDispatchWrite(options.directory + ".command.json", await capturedCommand(argv, dirname(options.directory), undefined, 195_000));
  const result = JSON.parse((await preDispatchChecked(command)).toString()); assert.equal(result.exitCode, 0, result.stderr);
  const artifact = (name: string) => preDispatchArtifact(resolve(options.directory, name));
  const receipt = await artifact("receipt.json"), controls = await artifact("control-events.jsonl"), mi = await artifact("gdb-mi.raw");
  const value = JSON.parse((await preDispatchChecked(receipt)).toString());
  assertPreDispatchSourceControls(value, eventRows((await preDispatchChecked(controls)).toString()), (await preDispatchChecked(mi)).toString());
  const profileRaw = await preDispatchArtifact(value.profile.path);
  assert.equal(basename(profileRaw.path), `${value.pid}.profraw`);
  assert.deepEqual(await readdir(dirname(profileRaw.path)), [`${value.pid}.profraw`]);
  await preDispatchVerify(options.build); await preDispatchVerify(driver); await preDispatchVerify(options.gdb);
  return { directory: options.directory, build: options.build, config, command, driver, recipe: await artifact("recipe.json"), receipt, controls, mi,
    terminal: await artifact("terminal.raw"), nativeEvents: await artifact("native-events.jsonl"), requests: await artifact("requests.json"), frames: await artifact("frames.json"), profileRaw,
    files: await Promise.all(["limits.json", "terminal-screen.txt", "prototype-source.json", "gdb-stderr.txt", "client-state/config.toml", "catalog.json"].map(artifact)) };
}
