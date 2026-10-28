import { createHash, randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { startScriptedUpstream } from "./scripted-upstream";
import { runTerminal } from "./terminal";
import type { CliReference, CliRun, CliScenario } from "./types";

const digest = (bytes: Uint8Array) => createHash("sha256").update(bytes).digest("hex");

function validateScenario(scenario: CliScenario): void {
  if (scenario.schema !== 1 || !/^[a-z][a-z0-9-]*$/.test(scenario.id) || !scenario.actions.length) throw Error("Invalid current CLI scenario");
  if (!scenario.actions.some(action => action.kind === "exec")) throw Error("A host-attested scenario must execute a code cell");
  for (const action of scenario.actions) {
    if (action.kind !== "exec" && action.kind !== "wait") throw Error("Unknown scripted action");
    if (action.kind === "exec" && (typeof action.source !== "string" || action.followingSources?.some(value => typeof value !== "string"))) throw Error("Invalid exec script");
    if (action.kind === "wait" && action.yieldTimeMs !== undefined && (!Number.isSafeInteger(action.yieldTimeMs) || action.yieldTimeMs < 0)) throw Error("Invalid wait budget");
  }
  if (scenario.completionHoldMs !== undefined && (!Number.isSafeInteger(scenario.completionHoldMs) || scenario.completionHoldMs < 0 || scenario.completionHoldMs > 10000)) throw Error("Invalid controlled completion hold");
}

/** Runs the signed reference's actual interactive client against only a deterministic loopback model. */
export async function runCliScenario(options: {
  reference: CliReference; scenario: CliScenario; directory: string; catalog: Uint8Array; model?: string; timeoutMs?: number;
  sourceProfilePattern?: string;
}): Promise<CliRun> {
  const scenario = structuredClone(options.scenario);
  validateScenario(scenario);
  const reference = structuredClone(options.reference);
  const catalog = Buffer.from(options.catalog);
  if (!/^[a-f0-9]{40}$/.test(reference.sourceRevision)) throw Error("Expected a pinned source revision");
  for (const item of [reference.cli, reference.host]) {
    if (!/^[a-f0-9]{64}$/.test(item.sha256) || digest(await readFile(item.path)) !== item.sha256) throw Error("Current CLI/reference binary identity differs");
  }
  if (dirname(resolve(reference.cli.path)) !== dirname(resolve(reference.host.path))) throw Error("Pinned CLI and host must share the frozen reference directory");
  const directory = resolve(options.directory);
  const sourceProfilePattern = options.sourceProfilePattern;
  if (sourceProfilePattern !== undefined && (resolve(sourceProfilePattern) !== sourceProfilePattern || !sourceProfilePattern.startsWith(directory + "/") || !sourceProfilePattern.endsWith("/%p.profraw"))) throw Error("Source profile output must be a PID-bound file in this scenario directory");
  await mkdir(directory, { recursive: false, mode: 0o700 });
  if (sourceProfilePattern) await mkdir(dirname(sourceProfilePattern), { recursive: true, mode: 0o700 });
  const cliHome = resolve(directory, "client-state"), workspace = resolve(directory, "workspace");
  await Promise.all([mkdir(cliHome, { mode: 0o700 }), mkdir(workspace, { mode: 0o700 })]);
  const nonce = randomBytes(16).toString("hex");
  const input = JSON.stringify(scenario);
  const inputSha256 = digest(Buffer.from(input));
  await writeFile(resolve(directory, "case-input.json"), input + "\n", { mode: 0o600 });
  await writeFile(resolve(directory, "catalog.json"), catalog, { mode: 0o600 });
  const upstream = startScriptedUpstream(scenario, directory, nonce);
  try {
    const config = [
    `model = ${JSON.stringify(options.model ?? "gpt-6.1-sol")}`, 'model_provider = "codemode_witness"',
    `model_catalog_json = ${JSON.stringify(resolve(directory, "catalog.json"))}`,
    'check_for_update_on_startup = false', 'approval_policy = "never"', 'sandbox_mode = "danger-full-access"',
    '[features]', 'code_mode = true', 'code_mode_host = true', 'plugins = false', 'remote_plugin = false', 'plugin_sharing = false',
    ...(scenario.rankedSearch ? ['code_mode_only_strict_3p_tools = false', 'code_mode_tool_search = true'] : []),
    '[model_providers.codemode_witness]', 'name = "Fixed local CodeMode reference"',
    `base_url = "http://127.0.0.1:${upstream.server.port}/v1"`, 'wire_api = "responses"',
    'requires_openai_auth = false', 'supports_websockets = false', 'request_max_retries = 0', 'stream_max_retries = 0',
    `[projects.${JSON.stringify(workspace)}]`, 'trust_level = "trusted"', '',
    ...(scenario.mcp ? ['[mcp_servers.parity]', 'command = "bun"', `args = ${JSON.stringify([resolve(import.meta.dir, "mcp-peer.ts"), resolve(directory, "mcp-peer.jsonl")])}`, 'startup_timeout_sec = 15', 'tool_timeout_sec = 15'] : []),
    ].join("\n");
    await writeFile(resolve(cliHome, "config.toml"), config, { mode: 0o600 });
    const argv = [resolve(reference.cli.path), "--no-daemon", "--no-alt-screen", "-C", workspace, "--", "Run the fixed local CodeMode qualification probe in this disposable workspace."];
    await writeFile(resolve(directory, "launch.json"), JSON.stringify({ argv, nonce, inputSha256, reference, catalogSha256: digest(catalog), modelBackend: "deterministic-loopback-no-inference", terminal: { implementation: "Bun.Terminal", emulator: "@xterm/headless@5.5.0" }, ...(sourceProfilePattern ? { sourceProfilePattern } : {}) }, null, 2) + "\n", { mode: 0o600 });
    const terminal = await runTerminal({ argv, cwd: workspace, home: cliHome, directory: resolve(directory, "terminal"), expectedHostSha256: reference.host.sha256, expectedCliSha256: reference.cli.sha256, timeoutMs: options.timeoutMs ?? 45000, sourceProfilePattern });
    const counts = upstream.counts();
    if (counts.failure) throw Error(counts.failure);
    if (counts.taskRequests !== scenario.actions.length + 1) throw Error("The actual client did not consume every scripted action exactly once");
    if (terminal.completion.finalText !== upstream.finalText) throw Error("Native completion does not belong to this fresh mock run");
    for (const item of [reference.cli, reference.host]) if (digest(await readFile(item.path)) !== item.sha256) throw Error("Reference binary changed during execution");
    await writeFile(resolve(directory, "native-events.jsonl"), await readFile(terminal.completion.sourceFile), { mode: 0o600 });
    const result: CliRun = { schema: 1, scenarioId: scenario.id, scenario, nonce, inputSha256, reference, directory, completion: terminal.completion, observations: upstream.observations, taskRequests: counts.taskRequests, auxiliaryRequests: counts.auxiliaryRequests, cliProcess: terminal.cliProcess, hostProcesses: terminal.hostProcesses };
    await writeFile(resolve(directory, "result.json"), JSON.stringify(result, null, 2) + "\n", { mode: 0o600 });
    return result;
  } catch (error) {
    await writeFile(resolve(directory, "failure.json"), JSON.stringify({ nonce, inputSha256, reference, ...upstream.counts(), error: error instanceof Error ? error.message : String(error) }, null, 2) + "\n", { mode: 0o600 });
    throw error;
  } finally { upstream.server.stop(true); await upstream.flush(); }
}
