#!/usr/bin/env bun
/** Run the explicitly approved bounded comparison in one disposable, isolated daemon.
 *
 *  Uses an existing ChatGPT auth file without modifying it. Only the budget proxy may
 *  contact the vendor; tools are in-memory fixtures and receipts contain aggregates.
 *  This consumes subscription quota: never add this runner to the default gate.
 *
 *  V4-145: converted from code_mode_compare.py. The names its tests patched (subprocess.Popen,
 *  ThreadingHTTPServer, request_json, run_comparison, time.monotonic, Budget) are reached through
 *  `seams`. configure() is async because a socket bind is (it was sync in Python).
 */
import { createHash } from "node:crypto";
import { chmodSync, closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { dumps, dumpsIndent, isPyObj, loads, obj, type PyObj, type PyValue } from "./pyjson.ts";
import { ephemeralPorts, threadingHTTPServer, type Methods, type PyServer } from "./pyhttp.ts";
import { Budget, proxyHandler, requestJson, runComparison, validatePromptExperiment } from "./code_mode_probe.ts";
import {
  argparse, AttributeError, get, isKeyError, isOSError, isTypeError, isValueError, osError, popen, setKey, SubprocessError,
  TimeoutExpired, typeName, ValueError, type Proc,
} from "./pyshim.ts";

/** The isolated daemon's config, state dir and credentials, and the environment that points at them. */
export async function configure(root: string, authFile: string, proxyPort: number, promptGuidance = false):
  Promise<[Record<string, string>, number, number, number]> {
  const state = join(root, "state");
  try {
    mkdirSync(state, { mode: 0o700 });
  } catch (e) {
    throw osError(e, state);
  }
  const bearer = "probe-management-" + Buffer.from(crypto.getRandomValues(new Uint8Array(24))).toString("hex");
  writeFileSync(join(state, "mgmt-key"), bearer);
  chmodSync(join(state, "mgmt-key"), 0o600);
  // Refresh is deliberately unavailable in this experiment. A stale credential
  // fails locally rather than mutating the operator's token or expanding spend.
  const auth = join(root, "auth.json");
  closeSync(openSync(auth, "a", 0o600));
  let source: Buffer;
  try {
    source = readFileSync(authFile);
  } catch (e) {
    throw osError(e, authFile);
  }
  writeFileSync(auth, source);
  const [control, baseline, codeMode] = await ephemeralPorts(3);
  const lines = ["[daemon]", `control_port = ${control}`, 'effort = "high"'];
  for (const [variant, port, enabled] of [["baseline", baseline, promptGuidance ? "true" : "false"], ["code_mode", codeMode, "true"]] as const) {
    lines.push(
      `[providers.${variant}]`, 'dialect = "openai-responses"',
      `base_url = "http://127.0.0.1:${proxyPort}"`,
      `auth = { kind = "chatgpt-oauth", file = ${dumps(auth)} }`,
      `quirks = { code_mode = ${enabled}, account_id_header = true, websocket = false, zstd_request_body = false, ` +
        "tool_surface = { enabled = true } }",
      `[[providers.${variant}.models]]`, 'id = "gpt-6-astra"', "context_window = 400000",
      `[heads.${variant}]`, `provider = "${variant}"`, `port = ${port}`,
      `discovery_prefix = "claude-probe-${variant}--"`, 'pinned_model = "gpt-6-astra"',
      `[heads.${variant}.claude]`, `command = "claude-probe-${variant}"`,
    );
  }
  const config = join(root, "splice.toml");
  writeFileSync(config, lines.join("\n") + "\n");
  const env: Record<string, string> = {
    ...(process.env as Record<string, string>),
    SPLICE_CONFIG: config, CLAUDEX_STATE_DIR: state, CLAUDEX_QUOTA_POLL: "off",
    CODEX_OAUTH_TOKEN_URL: `http://127.0.0.1:${proxyPort}/oauth/token`, SPLICE_PROBE_BEARER: bearer,
  };
  return [env, control, baseline, codeMode];
}

const isProcessError = (e: unknown) => isOSError(e) || e instanceof SubprocessError;

export async function stopProcess(proc: Proc | null): Promise<[number | null, Error | null]> {
  if (proc === null) return [null, null];
  let cleanupError: Error | null = null;
  let status: number | null;
  try {
    status = proc.poll();
  } catch (exc) {
    if (!isProcessError(exc)) throw exc;
    status = null;
    cleanupError = exc as Error;
  }
  if (status === null) {
    try {
      proc.terminate();
      try {
        await proc.wait(10);
      } catch (exc) {
        if (!(exc instanceof TimeoutExpired)) throw exc;
        proc.kill();
        await proc.wait(5);
      }
    } catch (exc) {
      if (!isProcessError(exc)) throw exc;
      cleanupError = cleanupError ?? (exc as Error);
    }
  }
  try {
    status = proc.poll();
  } catch (exc) {
    if (!isProcessError(exc)) throw exc;
    cleanupError = cleanupError ?? (exc as Error);
    status = null;
  }
  return [Number.isInteger(status) ? status : null, cleanupError];
}

export async function stopServer(server: PyServer | null, loop: Promise<void> | null): Promise<Error | null> {
  let cleanupError: Error | null = null;
  if (server !== null) {
    for (const operation of [() => server.shutdown(), () => server.serverClose()]) {
      try {
        await operation();
      } catch (exc) {
        if (!isOSError(exc)) throw exc;
        cleanupError = cleanupError ?? (exc as Error);
      }
    }
  }
  // thread.join(timeout=5): the serve loop has five seconds to return.
  if (loop !== null) await Promise.race([loop, Bun.sleep(5000)]);
  return cleanupError;
}

export function finalizeReceipt(receipt: string, artifactSha256: string, phase: string, category: string,
  exitStatus: number | null, budget: Budget): void {
  const finalAccounting = budget.snapshot();
  if (get(finalAccounting, "error") === "comparison ended") setKey(finalAccounting, "error", null);
  let saved: PyObj;
  if (existsSync(receipt)) {
    let text: string;
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(readFileSync(receipt));
    } catch {
      const e = new SyntaxError("'utf-8' codec can't decode bytes"); // UnicodeDecodeError is a ValueError
      e.name = "UnicodeDecodeError";
      throw e;
    }
    const parsed = loads(text.replaceAll("\r\n", "\n").replaceAll("\r", "\n"));
    // saved.update(...) on anything but a dict is an AttributeError, which nothing here catches.
    if (!isPyObj(parsed)) throw new AttributeError(`'${typeName(parsed)}' object has no attribute 'update'`);
    saved = parsed;
  } else {
    saved = obj([["model", "gpt-6-astra"], ["planned_runs", num(16)], ["completed_runs", num(0)], ["runs", []]]);
  }
  setKey(saved, "artifact_sha256", artifactSha256);
  setKey(saved, "phase", phase);
  setKey(saved, "category", category);
  setKey(saved, "exit_status", exitStatus === null ? null : num(exitStatus));
  setKey(saved, "final_accounting", finalAccounting);
  mkdirSync(dirname(receipt), { recursive: true });
  writeFileSync(receipt, dumpsIndent(saved, 2) + "\n");
}
const num = (n: number): PyValue => ({ __pyNum: String(n), isFloat: false });
const isExactInt = (v: PyValue) => typeof v === "object" && v !== null && "__pyNum" in v && !(v as { isFloat: boolean }).isFloat;

export interface RunArgs {
  artifact: string;
  auth_file: string;
  receipt: string;
  prompt_guidance?: boolean;
}

export async function run(args: RunArgs): Promise<void> {
  validatePromptExperiment(args);
  const artifact = resolve(args.artifact);
  const receipt = args.receipt;
  if (existsSync(receipt)) throw new ValueError("refusing to overwrite a receipt");
  let artifactBytes: Buffer;
  try {
    artifactBytes = readFileSync(artifact);
  } catch (e) {
    throw osError(e, artifact);
  }
  const artifactSha256 = createHash("sha256").update(artifactBytes).digest("hex");
  const budget = seams.Budget();
  let server: PyServer | null = null;
  let loop: Promise<void> | null = null;
  let proc: Proc | null = null;
  const previous = process.env.SPLICE_PROBE_BEARER;
  let phase = "startup";
  let category = "configuration_failure";
  let exitStatus: number | null = null;
  let cleanupError: Error | null = null;
  let receiptError: Error | null = null;
  let primaryError: Error | null = null;
  let launchAttempted = false;
  try {
    server = await seams.ThreadingHTTPServer(["127.0.0.1", 0], proxyHandler(budget));
    server.daemonThreads = false; // server_close must await every billed response's accounting.
    loop = Promise.resolve(server.serveForever());
    const root = mkdtempSync(join(tmpdir(), "code-mode-comparison-"));
    try {
      try {
        const promptGuidance = args.prompt_guidance ?? false;
        const [env, control, baseline, codeMode] = await configure(root, args.auth_file, server.serverPort, promptGuidance);
        process.env.SPLICE_PROBE_BEARER = env.SPLICE_PROBE_BEARER;
        const log = openSync(join(root, "daemon-output.log"), "w");
        try {
          launchAttempted = true;
          proc = seams.Popen(["java", "-Xmx256m", `-Duser.home=${root}`, "-jar", artifact, "daemon"], { env, cwd: root, stdoutFd: log });
          const deadline = seams.monotonic() + 20;
          for (;;) {
            const status = proc.poll();
            if (status !== null) {
              exitStatus = Number.isInteger(status) ? status : null;
              category = "daemon_exit";
              throw new ValueError("isolated daemon exited during startup");
            }
            let health: PyValue = null;
            let answered = false;
            try {
              health = await seams.requestJson(control, "GET", "/health");
              answered = true;
            } catch (e) {
              if (!(isOSError(e) || isValueError(e))) throw e;
            }
            if (answered) {
              if (!(isPyObj(health) && typeof get(health, "ok") === "boolean" && isExactInt(get(health, "readyHeads")))) {
                category = "invalid_health_response";
                throw new ValueError("isolated daemon returned invalid startup health");
              }
              if (get(health, "ok") === true && (get(health, "readyHeads") as { __pyNum: string }).__pyNum === "2") break;
            }
            if (seams.monotonic() >= deadline) {
              category = "readiness_timeout";
              throw new ValueError("isolated daemon did not become ready");
            }
            await seams.sleep(0.1);
          }
          phase = "comparison";
          await seams.runComparison({
            artifact, receipt, model: "gpt-6-astra",
            baseline_port: baseline, code_mode_port: codeMode, metrics_port: server.serverPort,
            prompt_guidance: promptGuidance,
          });
          phase = "complete";
          category = "complete";
        } finally {
          closeSync(log);
        }
      } finally {
        if (proc !== null) {
          // Prevent retries from starting while teardown drains existing work.
          budget.error = budget.error || "comparison ended";
          const [stoppedStatus, processCleanupError] = await stopProcess(proc);
          exitStatus = stoppedStatus !== null ? stoppedStatus : exitStatus;
          cleanupError = cleanupError ?? processCleanupError;
        }
      }
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  } catch (exc) {
    if (isOSError(exc) || isValueError(exc) || isKeyError(exc) || isTypeError(exc) || exc instanceof SubprocessError) {
      primaryError = exc as Error;
      if (phase === "startup" && category === "configuration_failure" && launchAttempted) category = "spawn_failure";
      else if (phase === "comparison") category = "comparison_failure";
    }
    throw exc;
  } finally {
    const serverCleanupError = await stopServer(server, loop);
    cleanupError = cleanupError ?? serverCleanupError;
    if (primaryError === null && cleanupError !== null) {
      phase = "cleanup";
      category = "cleanup_failure";
    }
    try {
      finalizeReceipt(receipt, artifactSha256, phase, category, exitStatus, budget);
    } catch (exc) {
      if (!(isOSError(exc) || isValueError(exc) || isTypeError(exc))) throw exc;
      receiptError = exc as Error;
    }
    if (previous === undefined) delete process.env.SPLICE_PROBE_BEARER;
    else process.env.SPLICE_PROBE_BEARER = previous;
    if (primaryError === null) {
      // eslint-disable-next-line no-unsafe-finally
      if (cleanupError !== null) throw cleanupError;
      // eslint-disable-next-line no-unsafe-finally
      if (receiptError !== null) throw receiptError;
    }
  }
}

/** The names the test suites replace (mock.patch targets in the original). */
export const seams = {
  Popen: popen as (argv: string[], opts: { env: Record<string, string>; cwd: string; stdoutFd: number }) => Proc,
  ThreadingHTTPServer: ((address: [string, number], methods: Methods) =>
    threadingHTTPServer(address[0], address[1], methods, "HTTP/1.1")) as (address: [string, number], methods: Methods) =>
    Promise<PyServer> | PyServer,
  requestJson,
  runComparison: runComparison as (args: Parameters<typeof runComparison>[0]) => Promise<void>,
  monotonic: (): number => performance.now() / 1000,
  sleep: (s: number): Promise<void> => Bun.sleep(s * 1000),
  Budget: (): Budget => new Budget(),
};

const PROG = "code_mode_compare.ts";
const USAGE = `usage: ${PROG} [-h] --artifact ARTIFACT --auth-file AUTH_FILE
                            --receipt RECEIPT [--prompt-guidance]
`;
const HELP = `${USAGE}
Run the explicitly approved bounded comparison in one disposable, isolated
daemon. Uses an existing ChatGPT auth file without modifying it. Only the
budget proxy may contact the vendor; tools are in-memory fixtures and receipts
contain aggregates. This consumes subscription quota: never add this runner to
the default gate.

options:
  -h, --help            show this help message and exit
  --artifact ARTIFACT
  --auth-file AUTH_FILE
  --receipt RECEIPT
  --prompt-guidance     enable code mode in both heads and vary only an
                        appended instruction section
`;

if (import.meta.main) {
  const a = argparse(process.argv.slice(2), [
    { flag: "--artifact", kind: "str", required: true },
    { flag: "--auth-file", kind: "str", required: true },
    { flag: "--receipt", kind: "str", required: true },
    { flag: "--prompt-guidance", kind: "true" },
  ], PROG, USAGE, HELP);
  await run(a as unknown as RunArgs);
}
