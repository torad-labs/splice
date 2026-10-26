import assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { controlScenarios } from "./current-cli/cases";
import { runCliScenario } from "./current-cli/run";
import { compareGatewayScenario } from "./current-cli/gateway";
import { compareControlPackets, gatewayInputBytes } from "./current-cli/compare";
import { isTitleRequest } from "./current-cli/scripted-upstream";
import { nativeCompletion } from "./current-cli/native-events";
import type { CliRun } from "./current-cli/types";
import type { CurrentReference } from "./current-readiness";
import type { CurrentInventory } from "./current-inventory";
import type { ExecutedVerifier } from "./current-coverage";
import { artifact, sha256, type Artifact, type Identity } from "./qualification";
import { capturedCommand } from "./current-command";
import { resolveCurrentSource } from "./current-source";
import { verifyLinuxSigstore } from "./current-native-proof";
import { candidateCommand } from "./candidate-command";

export type CliControlCase = {
  schema: 1; runId: string; scenarioId: string; inputSha256: string;
  candidateJarSha256: string; referenceCliSha256: string; nativeHostSha256: string;
  source: Artifact; sourceFiles: Artifact[]; candidate: Artifact; candidateFiles: Artifact[];
};
const sourceNames = ["case-input.json", "catalog.json", "launch.json", "requests.json", "frames.json", "observations.json", "native-events.jsonl", "client-state/config.toml", "terminal/terminal.raw", "terminal/terminal-events.jsonl", "terminal/terminal-screen.txt", "terminal/process.json"];
const candidateNames = ["scenario.json", "launch.json", "process.json", "stderr.txt", "comparison.json"];
const repository = resolve(import.meta.dir, "../..");
async function checked(proof: Artifact): Promise<Buffer> {
  const bytes = await readFile(proof.path); assert.equal(bytes.length, proof.bytes); assert.equal(sha256(bytes), proof.sha256); return bytes;
}
async function catalogBytes(lock: CurrentReference): Promise<Buffer> {
  const source = await resolveCurrentSource(lock, lock.sourceRepositoryDefault ?? undefined);
  const command = await capturedCommand(["git", "-C", source, "show", `${lock.sourceRevision}:codex-rs/core/models.json`], import.meta.dir);
  assert.equal(command.exitCode, 0, command.stderr); return Buffer.from(command.stdout);
}
async function verifiedCli(lock: CurrentReference, cli: Identity): Promise<void> {
  const bytes = await readFile(cli.path); assert.equal(sha256(bytes), lock.cliSha256);
  const bundle = resolve(repository, lock.cliSourceBundle);
  assert.equal(sha256(await readFile(bundle)), lock.cliSourceBundleSha256, "signed CLI source bundle changed");
  await verifyLinuxSigstore(bytes, bundle, lock);
}

/** Signed interactive source and the real packaged gateway; no source entry is inferred from a sibling output. */
export async function appendCurrentCli(java: string, jar: Identity, reference: { cli: Identity; nativeHost: Identity }, evidence: string, runId: string, lock: CurrentReference, execution: ExecutedVerifier): Promise<void> {
  const directory = resolve(evidence, `${runId}.current-cli`); await mkdir(directory);
  execution.cliCases = [];
  try {
    await verifiedCli(lock, reference.cli); const catalog = await catalogBytes(lock);
    for (const scenario of controlScenarios) {
      const sourceDirectory = resolve(directory, `${scenario.id}.source`), candidateDirectory = resolve(directory, `${scenario.id}.candidate`);
      const source = await runCliScenario({ reference: { cli: reference.cli, host: reference.nativeHost, sourceRevision: lock.sourceRevision }, scenario, directory: sourceDirectory, catalog });
      await compareGatewayScenario({ java, jar, source, directory: candidateDirectory });
      const proof: CliControlCase = { schema: 1, runId, scenarioId: scenario.id, inputSha256: source.inputSha256,
        candidateJarSha256: jar.sha256, referenceCliSha256: lock.cliSha256, nativeHostSha256: lock.nativeHostSha256,
        source: await artifact(resolve(sourceDirectory, "result.json")), sourceFiles: await Promise.all(sourceNames.map(name => artifact(resolve(sourceDirectory, name)))),
        candidate: await artifact(resolve(candidateDirectory, "stdout.json")), candidateFiles: await Promise.all(candidateNames.map(name => artifact(resolve(candidateDirectory, name)))) };
      execution.cliCases.push(proof); execution.artifacts.push(proof.source, ...proof.sourceFiles, proof.candidate, ...proof.candidateFiles);
    }
    const findings = await cliControlFindings(execution, runId, lock, jar, reference);
    assert.equal(findings.length, 0, findings.join("; "));
  } catch (error) {
    execution.state = "failed"; execution.findings.push(`Actual signed CLI/gateway controls: ${error instanceof Error ? error.message : String(error)}`);
    const path = resolve(directory, "failure.json"); await writeFile(path, JSON.stringify({ runId, error: execution.findings.at(-1) }) + "\n"); execution.artifacts.push(await artifact(path));
  }
  execution.finishedAt = new Date().toISOString();
}

export async function cliControlFindings(execution: ExecutedVerifier | undefined, runId: string, lock: CurrentReference, jar: Identity, reference: { cli: Identity; nativeHost: Identity }, _inventory?: CurrentInventory): Promise<string[]> {
  const findings: string[] = [], seen = new Set<string>();
  try { await verifiedCli(lock, reference.cli); } catch (error) { findings.push(`current signed CLI identity: ${error instanceof Error ? error.message : String(error)}`); }
  let catalog: Buffer;
  try { catalog = await catalogBytes(lock); } catch (error) { return [...findings, `current signed CLI catalog: ${error instanceof Error ? error.message : String(error)}`]; }
  for (const proof of execution?.cliCases ?? []) try {
    const scenario = controlScenarios.find(item => item.id === proof.scenarioId); assert.ok(scenario, "unrecognized CLI source scenario");
    assert.ok(!seen.has(proof.scenarioId), "duplicate CLI source scenario"); seen.add(proof.scenarioId);
    assert.equal(proof.schema, 1); assert.equal(proof.runId, runId); assert.equal(proof.inputSha256, sha256(JSON.stringify(scenario)));
    assert.equal(proof.candidateJarSha256, jar.sha256); assert.equal(proof.referenceCliSha256, lock.cliSha256); assert.equal(proof.nativeHostSha256, lock.nativeHostSha256);
    const source: CliRun = JSON.parse((await checked(proof.source)).toString()), candidate = JSON.parse((await checked(proof.candidate)).toString());
    assert.deepEqual(source.scenario, scenario); assert.equal(source.inputSha256, proof.inputSha256); assert.match(source.nonce, /^[a-f0-9]{32}$/);
    assert.equal(source.reference.sourceRevision, lock.sourceRevision); assert.equal(source.reference.cli.sha256, lock.cliSha256); assert.equal(source.reference.host.sha256, lock.nativeHostSha256);
    assert.equal(source.reference.cli.path, reference.cli.path); assert.equal(source.reference.host.path, reference.nativeHost.path);
    const file = async (files: Artifact[], base: string, name: string) => { const path = resolve(base, name), matches = files.filter(item => item.path === path); assert.equal(matches.length, 1, `missing or duplicated raw CLI proof ${name}`); return checked(matches[0]!); };
    assert.equal(proof.source.path, resolve(source.directory, "result.json"));
    assert.equal(proof.sourceFiles.length, sourceNames.length); for (const name of sourceNames) await file(proof.sourceFiles, source.directory, name);
    assert.equal((await file(proof.sourceFiles, source.directory, "case-input.json")).toString(), JSON.stringify(scenario) + "\n");
    assert.ok((await file(proof.sourceFiles, source.directory, "catalog.json")).equals(catalog), "source catalog differs from immutable source");
    const launch = JSON.parse((await file(proof.sourceFiles, source.directory, "launch.json")).toString());
    assert.equal(launch.argv[0], reference.cli.path); assert.equal(launch.nonce, source.nonce); assert.equal(launch.inputSha256, proof.inputSha256); assert.equal(launch.catalogSha256, sha256(catalog));
    assert.equal(launch.modelBackend, "deterministic-loopback-no-inference");
    assert.ok(source.cliProcess?.pid > 0 && Number.isSafeInteger(source.cliProcess.pid), "actual CLI process identity is absent");
    assert.equal(source.cliProcess.sha256, lock.cliSha256); assert.match(source.cliProcess.startTicks, /^\d+$/); assert.equal(source.cliProcess.exit, 0);
    const terminalProcess = JSON.parse((await file(proof.sourceFiles, source.directory, "terminal/process.json")).toString());
    assert.equal(terminalProcess.pid, source.cliProcess.pid); assert.equal(terminalProcess.exit, 0);
    assert.equal(terminalProcess.startTicks, source.cliProcess.startTicks); assert.equal(terminalProcess.sha256, lock.cliSha256);
    assert.ok(source.hostProcesses.length && source.hostProcesses.every(host => Number.isSafeInteger(host.pid) && host.pid > 0 && host.sha256 === lock.nativeHostSha256));
    const completion = nativeCompletion(resolve(source.directory, "client-state")); assert.deepEqual(completion, source.completion);
    assert.equal(source.completion.finalText, `CODEMODE_CURRENT_${source.nonce}`);
    assert.ok((await file(proof.sourceFiles, source.directory, "native-events.jsonl")).equals(await readFile(source.completion.sourceFile)));
    const observations = JSON.parse((await file(proof.sourceFiles, source.directory, "observations.json")).toString()); assert.deepEqual(observations, source.observations);
    const requests = JSON.parse((await file(proof.sourceFiles, source.directory, "requests.json")).toString()), tasks = requests.filter((body: any) => !isTitleRequest(body));
    assert.equal(source.taskRequests, scenario.actions.length + 1); assert.equal(tasks.length, source.taskRequests); assert.equal(source.auxiliaryRequests, requests.length - tasks.length);
    assert.deepEqual(observations, tasks.map((body: any, index: number) => ({ request: index + 1, input: body.input })));
    const candidateDirectory = resolve(proof.candidate.path, ".."); assert.equal(proof.candidate.path, resolve(candidateDirectory, "stdout.json"));
    assert.equal(proof.candidateFiles.length, candidateNames.length); for (const name of candidateNames) await file(proof.candidateFiles, candidateDirectory, name);
    assert.equal((await file(proof.candidateFiles, candidateDirectory, "scenario.json")).toString(), gatewayInputBytes(scenario));
    const candidateLaunch = JSON.parse((await file(proof.candidateFiles, candidateDirectory, "launch.json")).toString()), processProof = JSON.parse((await file(proof.candidateFiles, candidateDirectory, "process.json")).toString());
    assert.deepEqual(candidateLaunch.jar, { path: jar.path, sha256: jar.sha256 }); assert.equal(candidateLaunch.nonce, source.nonce);
    assert.deepEqual(candidateLaunch.argv.slice(1), candidateCommand("java", jar.path, "--native-gateway-fixture", "runtime.current-scenario", source.nonce, candidateDirectory, reference.nativeHost.path).slice(1));
    assert.equal(candidateLaunch.argv[candidateLaunch.argv.indexOf("-cp") + 1], jar.path, "gateway must load only the unmodified candidate JAR");
    assert.equal(processProof.exitCode, 0); assert.equal(processProof.failure, undefined); assert.ok(Number.isSafeInteger(processProof.pid) && processProof.pid > 0); assert.equal(candidate.pid, processProof.pid);
    assert.equal(candidate.caseId, "runtime.current-scenario"); assert.equal(candidate.scope, "real-native-runtime-through-production-gateway");
    assert.ok(candidate.outcomes.length && candidate.outcomes.every((outcome: string) => outcome === "Success"));
    const stderr = (await file(proof.candidateFiles, candidateDirectory, "stderr.txt")).toString();
    const identities = stderr.split("\n").filter(line=>line.startsWith("{")).flatMap(line=>{try{return [JSON.parse(line)];}catch{return [];}});
    assert.ok(identities.some(value=>value.type==="parity/identity"&&Number.isSafeInteger(value.nativeHostPid)&&value.nativeHostPid>0), "packaged gateway did not launch its verified native host");
    compareControlPackets(source, candidate);
  } catch (error) { findings.push(`${proof.scenarioId}: invalid signed CLI/gateway proof: ${error instanceof Error ? error.message : String(error)}`); }
  for (const scenario of controlScenarios) if (!seen.has(scenario.id)) findings.push(`mandatory signed CLI/gateway case was omitted: ${scenario.id}`);
  return findings;
}
