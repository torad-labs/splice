import { expect, test } from "bun:test";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { interactiveClient } from "./interactive-client";

function fixture(script: string, timeoutMs: number) {
  const root = mkdtempSync(join(tmpdir(), "interactive-client-cleanup-"));
  const pending = interactiveClient({
    command: [process.execPath, "-e", script], cwd: root,
    env: {
      HOME: root, CLAUDE_CONFIG_DIR: root,
      ANTHROPIC_API_KEY: "synthetic-probe-client-only",
    },
    sessionId: "fixture-session", finalText: "fixture final answer",
    expectedThinking: "fixture reasoning", timeoutMs,
  });
  return { root, pending };
}

test("timeout reaps a real PTY child that ignores SIGTERM", async () => {
  const { root, pending } = fixture(`
    const fs = require("node:fs");
    process.on("SIGTERM", () => fs.writeFileSync("term-seen", "yes"));
    fs.writeFileSync("child-pid", String(process.pid));
    setInterval(() => {}, 100);
  `, 500);
  let watchdog: ReturnType<typeof setTimeout> | undefined;
  try {
    const result = await Promise.race([
      pending.then(value => ({ kind: "completed" as const, value })),
      new Promise<{ kind: "watchdog" }>(resolve => {
        watchdog = setTimeout(() => resolve({ kind: "watchdog" }), 2200);
      }),
    ]);
    expect(existsSync(join(root, "term-seen"))).toBe(true);
    expect(result.kind).toBe("completed");
    if (result.kind !== "completed") throw new Error("helper stranded after SIGTERM");
    expect(result.value.timedOut).toBe(true);
    expect(result.value.exitCode).not.toBe(0);
    const pid = Number(readFileSync(join(root, "child-pid"), "utf8"));
    expect(() => process.kill(pid, 0)).toThrow();
  } finally {
    clearTimeout(watchdog);
    const pidFile = join(root, "child-pid");
    if (existsSync(pidFile)) {
      try { process.kill(Number(readFileSync(pidFile, "utf8")), "SIGKILL"); } catch {}
    }
    await pending;
    rmSync(root, { recursive: true, force: true });
  }
}, 5000);

test("native completion still exits through interactive Ctrl-D without SIGTERM", async () => {
  const { root, pending } = fixture(`
    const fs = require("node:fs");
    process.on("SIGTERM", () => { fs.writeFileSync("term-seen", "yes"); process.exit(9); });
    fs.mkdirSync("projects/fixture", { recursive: true });
    fs.writeFileSync("projects/fixture/fixture-session.jsonl", JSON.stringify({
      type: "assistant", uuid: "fixture-answer", message: { content: [
        { type: "thinking", thinking: "fixture reasoning" },
        { type: "text", text: "fixture final answer" }
      ] }
    }) + "\\n");
    process.stdin.setRawMode(true);
    process.stdin.resume();
    process.stdin.on("data", bytes => { if (bytes.includes(4)) process.exit(0); });
    console.log("fixture reasoning\\nfixture final answer");
  `, 2500);
  try {
    const result = await pending;
    expect(result.exitCode).toBe(0);
    expect(result.timedOut).toBe(false);
    expect(result.thinking).toBe("fixture reasoning");
    expect(result.finalInTranscript).toBe(true);
    expect(result.finalOnTerminal).toBe(true);
    expect(existsSync(join(root, "term-seen"))).toBe(false);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}, 5000);

test("timeout permits a cooperative child to exit during TERM grace", async () => {
  const { root, pending } = fixture(`
    const fs = require("node:fs");
    process.on("SIGTERM", () => {
      fs.writeFileSync("term-seen", "yes");
      setTimeout(() => process.exit(23), 40);
    });
    setInterval(() => {}, 100);
  `, 500);
  try {
    const result = await pending;
    expect(result.timedOut).toBe(true);
    expect(result.exitCode).toBe(23);
    expect(existsSync(join(root, "term-seen"))).toBe(true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}, 5000);

test("artifact failure also reaps the live PTY child before rejecting", async () => {
  const { root, pending } = fixture(`
    const fs = require("node:fs");
    process.on("SIGTERM", () => {});
    fs.writeFileSync("child-pid", String(process.pid));
    for (const name of ["one", "two"]) {
      fs.mkdirSync("projects/" + name, { recursive: true });
      fs.writeFileSync("projects/" + name + "/fixture-session.jsonl", "{}\\n");
    }
    setInterval(() => {}, 100);
  `, 2000);
  try {
    await expect(pending).rejects.toThrow("multiple session transcripts");
    const pid = Number(readFileSync(join(root, "child-pid"), "utf8"));
    expect(() => process.kill(pid, 0)).toThrow();
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}, 5000);
