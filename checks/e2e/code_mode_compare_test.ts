#!/usr/bin/env bun
/** Focused receipt tests for the isolated code-mode comparison runner.
 *
 *  V4-145: converted from code_mode_compare_test.py; each mock.patch of a code_mode_compare global
 *  is a swap on code_mode_compare.seams, restored in `finally`.
 */
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { get, check, FileNotFoundError, OSError, runUnittest, ValueError, type Tests } from "./pyshim.ts";
import { loads, obj, type PyValue } from "./pyjson.ts";
import { Budget } from "./code_mode_probe.ts";
import { run, seams, type RunArgs } from "./code_mode_compare.ts";

class Process {
  pid = 0;
  terminated = false;
  constructor(public returncode: number | null = null, readonly terminateError: Error | null = null) {}
  poll(): number | null {
    return this.returncode;
  }
  terminate(): void {
    this.terminated = true;
    if (this.terminateError) throw this.terminateError;
    this.returncode = -15;
  }
  wait(_timeout?: number): number | null {
    return this.returncode;
  }
  kill(): void {
    this.returncode = -9;
  }
}

async function withSeams(patch: Partial<typeof seams>, fn: () => Promise<void>): Promise<void> {
  const old = { ...seams };
  Object.assign(seams, patch);
  try {
    await fn();
  } finally {
    Object.assign(seams, old);
  }
}
async function withDir(fn: (dir: string) => Promise<void>): Promise<void> {
  const dir = mkdtempSync(join(tmpdir(), "tmp"));
  try {
    await fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
function makeArgs(directory: string): [RunArgs, string, string] {
  const artifact = join(directory, "app.jar");
  const auth = join(directory, "auth.json");
  const receipt = join(directory, "receipt.json");
  writeFileSync(artifact, "synthetic artifact");
  writeFileSync(auth, '{"tokens":{"access_token":"do-not-record"}}');
  return [{ artifact, auth_file: auth, receipt }, artifact, receipt];
}
/** A time.monotonic replacement yielding `values`, then StopIteration as a Mock side_effect does. */
function ticks(values: number[]): () => number {
  const queue = [...values];
  return () => {
    if (queue.length === 0) throw new Error("StopIteration");
    return queue.shift() as number;
  };
}
const saved = (receipt: string): PyValue => loads(readFileSync(receipt, "utf8"));
const accounting = (receipt: string, key: string) => get(get(saved(receipt), "final_accounting"), key);
const raisesOSError = (message: string) => async () => {
  throw new OSError(message);
};

export const tests: Tests = {
  async test_early_daemon_exit_writes_sanitized_startup_receipt() {
    await withDir(async (directory) => {
      const [args, artifact, receipt] = makeArgs(directory);
      await withSeams({ Popen: () => new Process(23) }, async () => {
        await check.raises((e) => e instanceof ValueError, () => run(args), /exited during startup/);
      });
      const s = saved(receipt);
      check.equal(createHash("sha256").update(readFileSync(artifact)).digest("hex"), get(s, "artifact_sha256"));
      check.equal("startup", get(s, "phase"));
      check.equal("daemon_exit", get(s, "category"));
      check.equal(23, get(s, "exit_status"));
      check.equal(0, accounting(receipt, "requests"));
      check.notIn("do-not-record", readFileSync(receipt, "utf8"));
    });
  },

  async test_readiness_timeout_records_terminated_exit_status() {
    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      const proc = new Process();
      await withSeams({
        Popen: () => proc, requestJson: raisesOSError("private connection detail"), monotonic: ticks([0, 20]),
      }, async () => {
        await check.raises((e) => e instanceof ValueError, () => run(args), /did not become ready/);
      });
      const s = saved(receipt);
      check.equal("readiness_timeout", get(s, "category"));
      check.equal(-15, get(s, "exit_status"));
      check.notIn("private connection detail", readFileSync(receipt, "utf8"));
    });
  },

  async test_invalid_health_response_is_a_sanitized_startup_failure() {
    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      await withSeams({ Popen: () => new Process(), requestJson: async () => [] }, async () => {
        await check.raises((e) => e instanceof ValueError, () => run(args), /invalid startup health/);
      });
      const s = saved(receipt);
      check.equal("startup", get(s, "phase"));
      check.equal("invalid_health_response", get(s, "category"));
      check.equal(-15, get(s, "exit_status"));
    });
  },

  async test_spawn_and_configuration_failures_are_receipted_without_secrets() {
    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      await withSeams({ Popen: () => { throw new OSError("launch secret"); } }, async () => {
        await check.raises((e) => e instanceof OSError, () => run(args));
      });
      const s = saved(receipt);
      check.equal("spawn_failure", get(s, "category"));
      check.isNone(get(s, "exit_status"));
      check.notIn("launch secret", readFileSync(receipt, "utf8"));
      check.notIn("do-not-record", readFileSync(receipt, "utf8"));
    });

    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      unlinkSync(args.auth_file);
      await check.raises((e) => e instanceof FileNotFoundError, () => run(args));
      const s = saved(receipt);
      check.equal("configuration_failure", get(s, "category"));
      check.isNone(get(s, "exit_status"));
      check.notIn("auth.json", readFileSync(receipt, "utf8"));
    });
  },

  async test_existing_receipt_is_not_replaced() {
    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      writeFileSync(receipt, '{"keep":true}\n');
      let served = 0;
      let launched = 0;
      await withSeams({
        ThreadingHTTPServer: () => { served++; throw new Error("server constructed"); },
        Popen: () => { launched++; throw new Error("launched"); },
      }, async () => {
        await check.raises((e) => e instanceof ValueError, () => run(args), /refusing to overwrite/);
      });
      check.equal('{"keep":true}\n', readFileSync(receipt, "utf8"));
      check.equal(0, served);
      check.equal(0, launched);
    });
  },

  async test_cleanup_preserves_rows_and_drained_accounting() {
    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      const proc = new Process();
      const comparison = async () => {
        writeFileSync(receipt, JSON.stringify({ runs: [{ case: "kept" }], failure: "OSError" }));
        throw new OSError("private comparison detail");
      };
      await withSeams({
        runComparison: comparison, requestJson: async () => obj([["ok", true], ["readyHeads", { __pyNum: "2", isFloat: false }]]),
        Popen: () => proc,
      }, async () => {
        await check.raises((e) => e instanceof OSError, () => run(args));
      });
      const s = saved(receipt);
      check.equal([obj([["case", "kept"]])], get(s, "runs"));
      check.equal("comparison", get(s, "phase"));
      check.equal("comparison_failure", get(s, "category"));
      check.equal(-15, get(s, "exit_status"));
      check.equal(0, accounting(receipt, "requests"));
      check.isNone(accounting(receipt, "error"));
      check.notIn("private comparison detail", readFileSync(receipt, "utf8"));
    });
  },

  async test_cleanup_failure_still_drains_server_accounting_and_preserves_startup_failure() {
    const budget = new Budget();
    const server = {
      serverPort: 12345,
      daemonThreads: true,
      closed: false,
      serveForever: async () => {},
      shutdown: () => {},
      serverClose: () => {
        server.closed = true;
        budget.finish(obj([["input_tokens", { __pyNum: "3", isFloat: false }], ["output_tokens", { __pyNum: "2", isFloat: false }]]));
      },
    };
    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      const proc = new Process(null, new OSError("cleanup secret"));
      await withSeams({
        Budget: () => budget, ThreadingHTTPServer: () => server, Popen: () => proc,
        requestJson: raisesOSError("private connection detail"), monotonic: ticks([0, 20]),
      }, async () => {
        await check.raises((e) => e instanceof ValueError, () => run(args), /did not become ready/);
      });
      check.true(server.closed);
      check.equal(3, accounting(receipt, "input_tokens"));
      check.equal(2, accounting(receipt, "output_tokens"));
      check.notIn("cleanup secret", readFileSync(receipt, "utf8"));
    });
  },
};

if (import.meta.main) {
  process.exit(await runUnittest("code_mode_compare_test", "CompareReceiptTests", tests));
}
