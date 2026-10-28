import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { candidateCommand } from "../candidate-command";
import { candidateOutcomesMatch, compareControlPackets, sourceGatewayInputBytes } from "./compare";
import type { CliRun, ObjectValue } from "./types";
import { assertReviewProviderRun } from "./review-contract";

const digest = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");

/** The packaged application's existing diagnostic entry runs the actual gateway and native runtime. */
export async function compareGatewayScenario(options: {
  java: string; jar: { path: string; sha256: string }; source: CliRun; directory: string; timeoutMs?: number;
}): Promise<{ candidate: ObjectValue; directory: string }> {
  if (options.source.scenario.review) await assertReviewProviderRun(options.source);
  const directory = resolve(options.directory);
  if (!/^[a-f0-9]{64}$/.test(options.jar.sha256) || digest(await readFile(options.jar.path)) !== options.jar.sha256) throw Error("Candidate JAR identity differs");
  if (digest(await readFile(options.source.reference.host.path)) !== options.source.reference.host.sha256) throw Error("Reference host identity differs");
  await mkdir(directory, { recursive: false, mode: 0o700 });
  await writeFile(resolve(directory, "scenario.json"), sourceGatewayInputBytes(options.source), { mode: 0o600 });
  const argv = candidateCommand(options.java, resolve(options.jar.path), "--native-gateway-fixture", "runtime.current-scenario", options.source.nonce, directory, options.source.reference.host.path);
  const environment: Record<string, string | undefined> = { ...process.env };
  for (const name of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"]) delete environment[name];
  await writeFile(resolve(directory, "launch.json"), JSON.stringify({ argv, sourceRun: options.source.directory, jar: options.jar, nonce: options.source.nonce }, null, 2) + "\n", { mode: 0o600 });
  const child = spawn(options.java, argv.slice(1), { env: environment, stdio: ["ignore", "pipe", "pipe"], detached: process.platform !== "win32" });
  let stdout = "", stderr = "", failure: string | undefined;
  const stop = () => {
    if (!child.pid || child.exitCode !== null || child.signalCode !== null) return;
    if (process.platform === "win32") child.kill("SIGKILL");
    else { try { process.kill(-child.pid, "SIGKILL"); } catch { child.kill("SIGKILL"); } }
  };
  child.stdout.on("data", bytes => {
    stdout += bytes.toString();
    if (Buffer.byteLength(stdout) > 16 * 1024 * 1024) { failure = "Gateway observation exceeded its explicit capture limit"; stop(); }
  });
  child.stderr.on("data", bytes => {
    stderr += bytes.toString();
    if (Buffer.byteLength(stderr) > 4 * 1024 * 1024) { failure = "Gateway diagnostic exceeded its explicit capture limit"; stop(); }
  });
  const timer = setTimeout(() => { failure = "Packaged gateway scenario timed out"; stop(); }, options.timeoutMs ?? 30000);
  let exitCode: number | null;
  try {
    exitCode = await new Promise<number | null>((resolveExit, reject) => { child.once("error", reject); child.once("close", resolveExit); });
  } finally {
    clearTimeout(timer);
    await writeFile(resolve(directory, "stdout.json"), stdout, { mode: 0o600 });
    await writeFile(resolve(directory, "stderr.txt"), stderr, { mode: 0o600 });
  }
  await writeFile(resolve(directory, "process.json"), JSON.stringify({ pid: child.pid, exitCode, failure }, null, 2) + "\n", { mode: 0o600 });
  if (failure || exitCode !== 0) throw Error(failure ?? `Gateway exited ${exitCode}: ${stderr}`);
  const candidate = JSON.parse(stdout) as ObjectValue;
  const scope = options.source.scenario.availability ? "production-gateway-unavailable-provider" : "real-native-runtime-through-production-gateway";
  if (candidate.pid !== child.pid || candidate.caseId !== "runtime.current-scenario" || candidate.scope !== scope) throw Error("Gateway observation is not bound to the actual packaged process");
  if (!candidateOutcomesMatch(options.source, candidate)) throw Error("Gateway scenario did not complete through its required production outcomes");
  const identities = stderr.split("\n").filter(line => line.startsWith("{")).flatMap(line => {
    try { const value = JSON.parse(line) as ObjectValue; return value.type === "parity/identity" ? [value] : []; } catch { return []; }
  });
  const hosts = identities.filter(value => typeof value.nativeHostPid === "number" && value.nativeHostPid > 0);
  if (options.source.scenario.availability) {
    if (identities.length) throw Error("Explicit unavailable-provider gateway launched a native host");
  } else if (!hosts.length) throw Error("Packaged gateway did not attest its verified native-host launch");
  if (digest(await readFile(options.jar.path)) !== options.jar.sha256) throw Error("Candidate JAR changed during execution");
  compareControlPackets(options.source, candidate, directory);
  await writeFile(resolve(directory, "comparison.json"), JSON.stringify({ schema: 1, sourceRun: options.source.directory, sourceInputSha256: options.source.inputSha256, candidateJar: options.jar, candidateProcess: child.pid, nonce: options.source.nonce, comparison: "exact control-call/result sequence; only generated output-item IDs and first-header measurements bound", ...(options.source.scenario.mcp ? { boundary: "typed-mcp-delegate", frontendRawMcpEnvelope: "not-certified-generic-client-text-is-a-distinct-unresolved-path" } : {}), ...(options.source.scenario.availability ? { boundary: "unavailable-production-provider", nativeHost: "not-opened", noticeChannel: "head-log" } : {}), equal: true }, null, 2) + "\n", { mode: 0o600 });
  return { candidate, directory };
}
