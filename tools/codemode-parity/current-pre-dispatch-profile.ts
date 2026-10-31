import assert from "node:assert/strict";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { gzipSync } from "node:zlib";
import { capturedCommand } from "./current-command";
import { sourceCliExportArgs, type CliSourceBuildProof } from "./current-cli-source-profile";
import type { ClientProfileProof } from "./current-client-profile";
import type { CurrentInventory } from "./current-inventory";
import { sha256, type Artifact, type Identity } from "./qualification";
import { preDispatchControl } from "./current-pre-dispatch-contract";
import { preDispatchArtifact, preDispatchBoundedArgs, preDispatchChecked, preDispatchDriver, preDispatchVerify, preDispatchWrite } from "./current-pre-dispatch-io";

/** Executed in its own bounded process so the full canonical export is released before other verification. */
export async function producePreDispatchProfile(options: { build: Artifact; inventory: Artifact; raw: Artifact; pid: number; directory: string }): Promise<void> {
  const build = JSON.parse((await preDispatchChecked(options.build)).toString()) as CliSourceBuildProof;
  const inventory = JSON.parse((await preDispatchChecked(options.inventory)).toString()) as CurrentInventory;
  assert.equal(basename(options.raw.path), `${options.pid}.profraw`);
  assert.deepEqual(await readdir(dirname(options.raw.path)), [`${options.pid}.profraw`]);
  await preDispatchVerify(options.raw);
  const decoder = await preDispatchArtifact(resolve(import.meta.dir, "current-client-profile.ts"));
  const exporter = await preDispatchArtifact(resolve(import.meta.dir, "current-cli-source-profile.ts"));
  await writeFile(resolve(options.directory, "decoder-source.ts"), await readFile(decoder.path));
  const { llvmSourceEntries } = await import("./current-client-profile");
  const merged = resolve(options.directory, "profile.profdata");
  const merge = await capturedCommand([build.tools.profdata.path, "merge", "-sparse", options.raw.path, "-o", merged], options.directory);
  await preDispatchWrite(resolve(options.directory, "merge-command.json"), merge); assert.equal(merge.exitCode, 0, merge.stderr);
  const exported = await capturedCommand(sourceCliExportArgs(build, inventory, merged), options.directory, undefined, 180_000);
  assert.equal(exported.exitCode, 0, exported.stderr);
  const decoded = Buffer.from(exported.stdout), path = resolve(options.directory, "profile.json.gz"); await writeFile(path, gzipSync(decoded));
  await preDispatchWrite(resolve(options.directory, "export-command.json"), { ...exported, stdout: undefined, decodedSha256: sha256(decoded), decodedBytes: decoded.length });
  const entries = await llvmSourceEntries(JSON.parse(decoded.toString()), inventory, build.sourceDirectory, build.sourceRepository);
  const mappings = await preDispatchWrite(resolve(options.directory, "mappings.json"), entries);
  const matching = entries.mappings.filter(entry => entry.id === preDispatchControl.sourceUnit.id);
  assert.equal(matching.length, 1); assert.equal(matching[0]!.sha256, preDispatchControl.sourceUnit.sha256); assert.equal(matching[0]!.count, 1);
  assert.equal(matching[0]!.function, preDispatchControl.symbols.remove);
  const proof: ClientProfileProof = { schema: 1, profraw: options.raw, profdata: await preDispatchArtifact(merged), mappings,
    exported: { ...await preDispatchArtifact(path), decodedSha256: sha256(decoded), decodedBytes: decoded.length } };
  await preDispatchWrite(resolve(options.directory, "profile-proof.json"), proof);
  await preDispatchVerify(decoder); await preDispatchVerify(exporter);
  const cgroup = (await readFile("/proc/self/cgroup", "utf8")).trim().split("::")[1];
  await preDispatchWrite(resolve(options.directory, "decoder.json"), { decoder, exporter, matching, behavioralCoverage: false,
    memoryMax: (await readFile(`/sys/fs/cgroup${cgroup}/memory.max`, "utf8")).trim(),
    swapMax: (await readFile(`/sys/fs/cgroup${cgroup}/memory.swap.max`, "utf8")).trim(),
    memoryPeak: (await readFile(`/sys/fs/cgroup${cgroup}/memory.peak`, "utf8")).trim() });
}
export async function executePreDispatchProfile(options: {
  build: Artifact; inventory: Artifact; raw: Artifact; pid: number; directory: string; bun: Identity; runId: string;
}): Promise<{ proof: Artifact; driver: Artifact; command: Artifact; files: Artifact[] }> {
  await mkdir(options.directory);
  const driver = await preDispatchArtifact(preDispatchDriver("profile-driver.ts"));
  const config = await preDispatchWrite(resolve(options.directory, "profile-input.json"), options);
  const argv = preDispatchBoundedArgs(`splice-parity-pre-dispatch-${options.runId}-2`, [options.bun.path, driver.path, config.path]);
  const result = await capturedCommand(argv, options.directory, undefined, 195_000);
  const command = await preDispatchWrite(resolve(options.directory, "command.json"), result); assert.equal(result.exitCode, 0, result.stderr);
  const artifact = (name: string) => preDispatchArtifact(resolve(options.directory, name));
  return { proof: await artifact("profile-proof.json"), driver, command,
    files: [config, ...await Promise.all(["decoder.json", "decoder-source.ts", "merge-command.json", "export-command.json"].map(artifact))] };
}
