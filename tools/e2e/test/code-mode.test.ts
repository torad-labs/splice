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
import { existsSync, fstatSync, mkdirSync, mkdtempSync, readFileSync, rmSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { spawnSync } from "node:child_process";
import { join, resolve } from "node:path";
import { check, FileNotFoundError, get, OSError, popen, ValueError } from "../src/compat/python-values.ts";
import { loads, obj, type PyValue } from "../src/compat/python-json.ts";
import {
  Budget, compareConfigure, compareSeams as seams, mockConfigure, mockSeams, startDaemon, stopDaemon,
  requestJson, runCompare as run, type RunArgs,
} from "../src/commands/code-mode.ts";
import { reasoningCacheDaemonEnv } from "../src/commands/heads.ts";
import { bootFailure, readBootLog, readCredentialBootLog, redactBootCredentials } from "../src/daemon-startup.ts";

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

describe("mock daemon startup diagnostics", () => {
  test("a stalled health socket obeys the short startup request budget", async () => {
    const server = Bun.serve({
      hostname: "127.0.0.1", port: 0,
      fetch: () => new Promise<Response>(() => {}),
    });
    try {
      await expect(requestJson(server.port!, "GET", "/health", undefined, null, 0.02))
        .rejects.toBeInstanceOf(OSError);
    } finally {
      server.stop(true);
    }
  });

  test("boot diagnostics keep the last sixty lines and cap a single noisy line", async () => {
    await withDir(async (root) => {
      const log = join(root, "boot.log");
      writeFileSync(log, Array.from({ length: 100 }, (_, i) => `line-${i}`).join("\n") + "\n");
      const message = bootFailure("timeout", 1.25, readBootLog(log));
      expect(message).not.toContain("\nline-39\n");
      expect(message).toContain("\nline-40\n");
      expect(message).toEndWith("line-99");
      expect(message).toContain("after 1.3 seconds");
      writeFileSync(log, "x".repeat(100_000) + "\nlast-line\n");
      expect(readBootLog(log)).toBe("last-line");
      writeFileSync(log, "x".repeat(100_000));
      expect(Buffer.byteLength(readBootLog(log))).toBeLessThanOrEqual(64 * 1024);
      expect(readBootLog(join(root, "absent.log"))).toContain("boot log unavailable");
      expect(readCredentialBootLog(log, join(root, "absent-auth.json"), "synthetic-bearer"))
        .toBe("[boot log withheld: credential redaction unavailable]");
      expect(redactBootCredentials(JSON.stringify({ token: 'secret"quoted' }), { access: 'secret"quoted' }, "synthetic-bearer"))
        .toBe('{"token":"[redacted]"}');
      expect(redactBootCredentials("safe token-value bearer-value", { tokens: { access_token: "token-value" } }, "bearer-value"))
        .toBe("safe [redacted] [redacted]");
    });
  });

  test("spawn and cleanup errors close the log without hiding the startup diagnosis", async () => {
    await withDir(async (root) => {
      const old = { ...mockSeams };
      let fd = -1;
      let child: ReturnType<typeof popen> | undefined;
      mockSeams.Popen = (_argv, opts) => {
        fd = opts.stdoutFd;
        throw new OSError("synthetic spawn failure");
      };
      try {
        await expect(startDaemon(root, "synthetic.jar", {}, 0, 0)).rejects.toThrow("synthetic spawn failure");
        expect(() => fstatSync(fd)).toThrow();
        mockSeams.Popen = (_argv, opts) => {
          fd = opts.stdoutFd;
          writeFileSync(fd, "synthetic-cleanup-boot-marker\n");
          child = popen([process.execPath, "-e", "setInterval(() => {}, 1000);"], opts);
          return {
            pid: child.pid, poll: () => child!.poll(), wait: (timeout) => child!.wait(timeout),
            kill: () => child!.kill(),
            terminate: () => { throw new OSError("synthetic cleanup failure"); },
          };
        };
        mockSeams.requestJson = raisesOSError("synthetic unavailable");
        const error = await startDaemon(root, "synthetic.jar", {}, 0, 0)
          .then(() => { throw new Error("unexpected readiness"); }, (e: Error) => e);
        expect(error.message).toContain("did not become ready");
        expect(error.message).toContain("synthetic-cleanup-boot-marker");
        expect(error.message).toContain("synthetic cleanup failure");
        expect(() => fstatSync(fd)).toThrow();
        expect(child?.poll()).not.toBeNull();
      } finally {
        if (child?.poll() === null) {
          child.kill();
          await child.wait(2);
        }
        Object.assign(mockSeams, old);
      }
    });
  });

  test("an exited fake daemon carries stdout and stderr with its exit status", async () => {
    await withDir(async (root) => {
      const old = { ...mockSeams };
      let proc: ReturnType<typeof popen> | undefined;
      Object.assign(mockSeams, {
        Popen: (_argv: string[], opts: Parameters<typeof popen>[1]) => {
          proc = popen([process.execPath, "-e",
            'console.log("synthetic-stdout"); console.error("synthetic-stderr"); process.exit(23);'], opts);
          return proc;
        },
        requestJson: async () => {
          await proc!.wait(2);
          throw new OSError("synthetic health unavailable");
        },
      });
      try {
        const error = await startDaemon(root, "synthetic.jar", {}, 0, 2)
          .then(() => { throw new Error("fake daemon unexpectedly ready"); }, (e: Error) => e);
        expect(error).toBeInstanceOf(ValueError);
        expect(error.message).toContain("exited during startup (exit 23)");
        expect(error.message).toContain("synthetic-stdout");
        expect(error.message).toContain("synthetic-stderr");
        expect(error.message).toMatch(/after \d+\.\d seconds/);
      } finally {
        Object.assign(mockSeams, old);
      }
    });
  });

  test("successful readiness returns the owned child and open log for normal cleanup", async () => {
    await withDir(async (root) => {
      const old = { ...mockSeams };
      Object.assign(mockSeams, {
        Popen: (_argv: string[], opts: Parameters<typeof popen>[1]) =>
          popen([process.execPath, "-e", 'setInterval(() => {}, 1000);'], opts),
        requestJson: async () => obj([["ok", true], ["readyHeads", { __pyNum: "2", isFloat: false }]]),
      });
      try {
        const [proc, log] = await startDaemon(root, "synthetic.jar", {}, 0, 1);
        expect(proc.poll()).toBeNull();
        await stopDaemon(proc, log);
        expect(proc.poll()).not.toBeNull();
      } finally {
        Object.assign(mockSeams, old);
      }
    });
  });

  test("a live fake daemon timeout carries its boot log before cleanup", async () => {
    await withDir(async (root) => {
      const old = { ...mockSeams };
      let proc: ReturnType<typeof popen> | undefined;
      const marker = "synthetic-daemon-boot-marker";
      Object.assign(mockSeams, {
        Popen: (_argv: string[], opts: Parameters<typeof popen>[1]) => {
          proc = popen([process.execPath, "-e",
            `console.error("${marker}"); setInterval(() => {}, 1000);`], opts);
          return proc;
        },
        requestJson: async () => {
          // Synchronize with actual child output, not an arbitrary sleep.
          const until = performance.now() + 2000;
          while (!readFileSync(join(root, "daemon-output.log"), "utf8").includes(marker)) {
            if (performance.now() >= until) throw new Error("fake daemon did not write its marker");
            await Bun.sleep(10);
          }
          throw new OSError("synthetic health unavailable");
        },
      });
      try {
        const error = await startDaemon(root, "synthetic.jar", {}, 0, 0.01)
          .then(() => { throw new Error("fake daemon unexpectedly ready"); }, (e: Error) => e);
        expect(error).toBeInstanceOf(ValueError);
        expect(error.message).toContain("did not become ready");
        expect(error.message).toContain(marker);
        expect(error.message).toMatch(/after \d+\.\d seconds/);
        expect(proc?.poll()).not.toBeNull();
      } finally {
        Object.assign(mockSeams, old);
      }
    });
  });
});

describe("comparison receipts", () => {
  test("a clipped newline-free fake daemon log cannot expose a credential suffix", async () => {
    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      const secret = "synthetic-private-prefix-sensitive-suffix";
      writeFileSync(args.auth_file, JSON.stringify({ tokens: { access_token: secret } }));
      let proc: ReturnType<typeof popen>;
      await withSeams({
        Popen: (_argv, opts) => {
          proc = popen([process.execPath, "-e",
            `require("node:fs").writeFileSync(1, "p".repeat(100000) + "${secret}" + "x".repeat(65515)); process.exit(23);`], opts);
          return proc;
        },
        requestJson: async () => {
          await proc.wait(2);
          throw new OSError("synthetic unavailable");
        },
      }, async () => {
        const error = await run(args).then(() => { throw new Error("unexpected readiness"); }, (e: Error) => e);
        expect(error.message).toContain("exited during startup");
        expect(error.message).not.toContain("sensitive-suffix");
        expect(error.message).toContain("withheld");
      });
      expect(readFileSync(receipt, "utf8")).not.toContain("sensitive-suffix");
    });
  });


  test("startup log diagnosis redacts copied credentials and management bearer", async () => {
    await withDir(async (directory) => {
      const [args, , receipt] = makeArgs(directory);
      let bearer = "";
      await withSeams({
        Popen: (_argv, opts) => {
          bearer = opts.env.SPLICE_PROBE_BEARER!;
          writeFileSync(opts.stdoutFd, `synthetic-safe-boot-line\ndo-not-record\n${bearer}\n`);
          return new Process(23);
        },
      }, async () => {
        const error = await run(args).then(() => { throw new Error("unexpected readiness"); }, (e: Error) => e);
        expect(error.message).toContain("synthetic-safe-boot-line");
        expect(error.message).toContain("[redacted]");
        expect(error.message).not.toContain("do-not-record");
        expect(error.message).not.toContain(bearer);
      });
      expect(readFileSync(receipt, "utf8")).not.toContain("synthetic-safe-boot-line");
      expect(readFileSync(receipt, "utf8")).not.toContain("do-not-record");
      expect(readFileSync(receipt, "utf8")).not.toContain(bearer);
    });
  });

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
        Popen: () => proc, requestJson: raisesOSError("private connection detail"), monotonic: ticks([0, 60, 60]),
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
        requestJson: raisesOSError("private connection detail"), monotonic: ticks([0, 60, 60]),
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

// V4-294: each harness daemon is "isolated" by a scratch state dir, but its env spread process.env
// and set only CLAUDEX_STATE_DIR, which StatePaths reads AFTER SPLICE_STATE_DIR (StatePaths.kt:46,50).
// A shell that exports SPLICE_STATE_DIR booted the scratch daemon on the operator's real state
// (mgmt-key, config.json, logs) beside the live one. oracle.ts already drops the whole family.
const mkdirIn = (root: string, name: string): string => {
  mkdirSync(join(root, name));
  return join(root, name);
};

describe("the harness daemons' environment - V4-294", () => {
  test("no builder hands its daemon the caller's SPLICE_STATE_DIR, or anything else of that family", async () => {
    const sentinel = "/operator/real/state-294";
    const family = ["SPLICE_STATE_DIR", "CLAUDEX_HOME", "CODEX_HOME", "CHATGPT_ACCOUNT_ID"];
    const saved = family.map((name) => [name, process.env[name]] as const);
    for (const name of family) process.env[name] = sentinel;
    const root = mkdtempSync(join(tmpdir(), "harness-env-"));
    try {
      const source = join(root, "source-auth.json");
      writeFileSync(source, '{"tokens":{"access_token":"synthetic"}}');
      // Each builder makes its own state/ under the root it is given, so each gets its own root.
      const [compared] = await compareConfigure(mkdirIn(root, "compare"), source, 12345);
      const envs: Record<string, Record<string, string | undefined>> = {
        mockConfigure: mockConfigure(mkdirIn(root, "mock"), 1, 2, 3, 4, null),
        compareConfigure: compared,
        reasoningCacheDaemonEnv: reasoningCacheDaemonEnv(join(root, "splice.toml"), join(root, "state")),
      };
      const leaked = Object.entries(envs).flatMap(([builder, env]) =>
        Object.entries(env).filter(([, value]) => value === sentinel).map(([name]) => `${builder}: ${name}`));
      expect(leaked).toEqual([]);
      for (const [builder, env] of Object.entries(envs)) {
        expect(env.CLAUDEX_STATE_DIR ?? "", builder).toStartWith(root);
      }
    } finally {
      for (const [name, value] of saved) {
        if (value === undefined) delete process.env[name];
        else process.env[name] = value;
      }
      rmSync(root, { recursive: true, force: true });
    }
  });
});
