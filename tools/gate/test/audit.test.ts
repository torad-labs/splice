// `gate audit` — the retry checks/oss/verify-OSS-I.sh wrapped `bun audit` in, with the registry
// faked through GATE_AUDIT_COMMAND the way the slot's tests fake gradlew. No arm here touches the
// network: a leg whose test needs registry.npmjs.org is a leg that is red when npm is.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ATTEMPTS, AUDIT_ARGS, auditCommand, runAudit } from "../src/lib/audit.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

/** A fake `bun` whose `audit` fails the first `failures` times, counting attempts in a file. */
function fakeAudit(body: string): { dir: string; command: string[]; counter: string } {
  const dir = mkdtempSync(join(tmpdir(), "gate-audit-"));
  workspaces.push(dir);
  const counter = join(dir, "attempts");
  const path = join(dir, "bun-audit");
  writeFileSync(
    path,
    `#!${process.execPath}\n` +
      'import { appendFileSync } from "node:fs";\n' +
      `appendFileSync(${JSON.stringify(counter)}, process.argv.slice(2).join(" ") + "\\n");\n` +
      `const attempts = ${JSON.stringify(counter)};\n` +
      body,
  );
  chmodSync(path, 0o755);
  return { dir, command: [path, ...AUDIT_ARGS], counter };
}

const attemptsMade = (counter: string): string[] =>
  readFileSync(counter, "utf8").split("\n").filter((line) => line !== "");

function captured(): { lines: string[]; restore: () => void } {
  const lines: string[] = [];
  const before = console.error;
  console.error = (...parts: unknown[]) => void lines.push(parts.join(" "));
  return { lines, restore: () => void (console.error = before) };
}

describe("gate audit", () => {
  test("the command is `bun audit --audit-level=critical`, and the env override replaces bun alone", () => {
    expect(auditCommand({})).toEqual(["bun", "audit", "--audit-level=critical"]);
    expect(auditCommand({ GATE_AUDIT_COMMAND: "/tmp/fake" })).toEqual(["/tmp/fake", "audit", "--audit-level=critical"]);
  });

  test("a clean audit passes on the first attempt and never retries", async () => {
    const fake = fakeAudit("process.exit(0);\n");
    const log = captured();
    try {
      expect(await runAudit({ cwd: repoRoot, command: fake.command, backoffStepMs: 1 })).toBe(0);
    } finally {
      log.restore();
    }
    expect(attemptsMade(fake.counter)).toEqual(["audit --audit-level=critical"]);
    expect(log.lines.join("\n")).toBe("");
  });

  // The outage shape: registry.npmjs.org answered 503 mid-gate (PR #122, 2026-09-04).
  test("two failures then a pass is a PASS, with the retry lines the script printed", async () => {
    const fake = fakeAudit(
      'import { readFileSync } from "node:fs";\n' +
        'const made = readFileSync(attempts, "utf8").split("\\n").filter((l) => l !== "").length;\n' +
        "process.exit(made > 2 ? 0 : 1);\n",
    );
    const log = captured();
    try {
      expect(await runAudit({ cwd: repoRoot, command: fake.command, backoffStepMs: 1 })).toBe(0);
    } finally {
      log.restore();
    }
    expect(attemptsMade(fake.counter).length).toBe(3);
    expect(log.lines).toEqual([
      "bun audit attempt 1 failed — retrying in 0.001s",
      "bun audit attempt 2 failed — retrying in 0.002s",
    ]);
  });

  test("a real advisory fails all three attempts and stays red", async () => {
    const fake = fakeAudit("process.exit(1);\n");
    const log = captured();
    try {
      expect(await runAudit({ cwd: repoRoot, command: fake.command, backoffStepMs: 1 })).toBe(1);
    } finally {
      log.restore();
    }
    expect(attemptsMade(fake.counter).length).toBe(ATTEMPTS);
    expect(log.lines.at(-1)).toBe("bun audit failed on three attempts");
    // and the LAST attempt does not print a "retrying" line it will never honour
    expect(log.lines.filter((line) => line.includes("retrying")).length).toBe(ATTEMPTS - 1);
  });

  // `timeout 60`: the audit has hung for five minutes and then answered 503. A hung attempt must
  // lose the attempt, not the gate's next hour.
  //
  // Counted from the RUNNER's own lines, never the fake's counter file: the kill timer starts at
  // spawn, so on a loaded machine a child can be killed before its first line runs, and the counter
  // then misses an attempt the runner did make (gate of record on fdd2dd39, 2026-09-23: 2 of 3
  // recorded; a 1ms timer records none). That each attempt HUNG is proved by time instead — only the
  // kill timer ends a child that never exits, so every attempt waited it out.
  test("a hung attempt is killed and counts as a failure", async () => {
    const fake = fakeAudit("await new Promise(() => {});\n");
    const log = captured();
    const timeoutMs = 250;
    const started = Date.now();
    try {
      expect(await runAudit({ cwd: repoRoot, command: fake.command, backoffStepMs: 1, timeoutMs })).toBe(1);
    } finally {
      log.restore();
    }
    const elapsed = Date.now() - started;
    expect(elapsed).toBeGreaterThanOrEqual(ATTEMPTS * timeoutMs);
    expect(elapsed).toBeLessThan(10_000);
    expect(log.lines.filter((line) => line.includes("retrying")).length).toBe(ATTEMPTS - 1);
    expect(log.lines.at(-1)).toBe("bun audit failed on three attempts");
  });

  test("through the CLI: the verb takes no arguments, and reports the fake registry's verdict", () => {
    const cli = join(repoRoot, "tools", "gate", "index.ts");
    const clean = fakeAudit("process.exit(0);\n");
    const green = Bun.spawnSync([process.execPath, cli, "audit"], {
      cwd: repoRoot,
      env: { ...Bun.env, GATE_AUDIT_COMMAND: clean.command[0]!, GATE_AUDIT_BACKOFF_MS: "1" },
      stdout: "pipe",
      stderr: "pipe",
    });
    expect(green.exitCode).toBe(0);

    const advisory = fakeAudit("process.exit(1);\n");
    const red = Bun.spawnSync([process.execPath, cli, "audit"], {
      cwd: repoRoot,
      env: { ...Bun.env, GATE_AUDIT_COMMAND: advisory.command[0]!, GATE_AUDIT_BACKOFF_MS: "1" },
      stdout: "pipe",
      stderr: "pipe",
    });
    expect(red.exitCode).toBe(1);
    expect(red.stderr.toString()).toContain("bun audit failed on three attempts");

    const refused = Bun.spawnSync([process.execPath, cli, "audit", "--fix"], { cwd: repoRoot, stdout: "pipe", stderr: "pipe" });
    expect(refused.exitCode).toBe(2);
    expect(refused.stderr.toString()).toContain("takes no arguments");
  });
});
