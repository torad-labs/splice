/** Focused receipt tests for the isolated code-mode comparison runner — the gate's
 *  "code-mode startup receipt selftest" leg.
 *
 *  V4-145: converted from code_mode_compare_test.py; each mock.patch of a code_mode_compare global
 *  is a swap on the module's `compareSeams`, restored in `finally`.
 *
 *  Restructure PR 5: a real `bun test` file. The ASSERTIONS stay `check.*` from the compat layer
 *  rather than becoming `expect`: they compare the tagged Python tree (ints vs floats, key order,
 *  int precision past 2^53), which is exactly what these receipts are about, and `expect`'s
 *  structural equality would silently accept a float where the receipt must carry an int.
 */
import { describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { spawnSync } from "node:child_process";
import { join, resolve } from "node:path";
import { check, FileNotFoundError, get, OSError, ValueError } from "../src/compat/python-values.ts";
import { loads, obj, type PyValue } from "../src/compat/python-json.ts";
import { Budget, compareSeams as seams, runCompare as run, type RunArgs } from "../src/commands/code-mode.ts";

const CLI = resolve(import.meta.dir, "../index.ts");

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

describe("comparison receipts", () => {
  test("early daemon exit writes sanitized startup receipt", async () => {
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
  });

  test("readiness timeout records terminated exit status", async () => {
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
  });

  test("invalid health response is a sanitized startup failure", async () => {
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
  });

  test("spawn and configuration failures are receipted without secrets", async () => {
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
  });

  test("existing receipt is not replaced", async () => {
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
  });

  test("cleanup preserves rows and drained accounting", async () => {
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
  });

  test("cleanup failure still drains server accounting and preserves startup failure", async () => {
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
      expect(server.closed).toBe(true);
      check.equal(3, accounting(receipt, "input_tokens"));
      check.equal(2, accounting(receipt, "output_tokens"));
      check.notIn("cleanup secret", readFileSync(receipt, "utf8"));
    });
  });

  // Same contract on the BILLED arm, and it matters more here: the comparison spends quota, so a
  // run that cannot possibly work must stop at the jar rather than after configuring a daemon.
  test("a missing fat jar is a harness failure (exit 2) that names the producer, never a nested gradle", () => {
    const receipt = join(mkdtempSync(join(tmpdir(), "code-mode-compare-")), "receipt.json");
    const auth = join(mkdtempSync(join(tmpdir(), "code-mode-auth-")), "auth.json");
    writeFileSync(auth, '{"tokens":{"access_token":"do-not-record"}}');
    const r = spawnSync(process.execPath,
      [CLI, "code-mode", "compare", "--artifact", "/nonexistent/app-all.jar", "--auth-file", auth, "--receipt", receipt],
      { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    expect(r.status).toBe(2);
    expect(r.stderr).toContain("fat jar missing at /nonexistent/app-all.jar");
    expect(r.stderr).toContain("bun tools/gate slot <label> -- :app:shadowJar");
    expect(r.stdout).toBe("");
    expect(existsSync(receipt)).toBe(false);
  });
});
