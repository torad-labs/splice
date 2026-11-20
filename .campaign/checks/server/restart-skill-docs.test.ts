// restart-at-once, docs-skill: the splice skill's reference and the manager's own CLAUDE.md tell the manager agent
// what a restart does now: the change applies at once and what is answering finishes, except where a restart still
// stops the daemon whole; `--wait` brings back the old wait. No sentence still tells it to warn that a restart cuts
// every turn in flight. Whitespace is read as one space, so a sentence wrapped across lines reads whole.

import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./lib/harness.ts";

const HOME = "app/src/main/resources/manager-home";
const REF = `${HOME}/.claude/skills/splice/reference`;
const read = (p: string) => readFileSync(join(ROOT, p), "utf8");
const flat = (s: string) => s.replace(/\s+/g, " ");

/** The text from the first line starting with [start] up to the next line matching [end] (or the end). */
function section(text: string, start: string, end: RegExp): string {
  const lines = text.split("\n");
  const from = lines.findIndex((l) => l.startsWith(start));
  if (from < 0) throw new Error(`no line starts with ${start}`);
  const rest = lines.slice(from + 1);
  const to = rest.findIndex((l) => end.test(l));
  return flat([lines[from], ...(to < 0 ? rest : rest.slice(0, to))].join("\n"));
}

describe("docs-skill", () => {
  test("cli.md lists --wait for restart and upgrade, and its restart section says what happens now", () => {
    const cli = read(`${REF}/cli.md`);
    const row = (verb: string) => cli.split("\n").find((l) => l.startsWith(`| \`${verb}\` |`)) ?? "";
    expect(row("restart")).toContain("--wait");
    expect(row("upgrade")).toContain("--wait");
    const restart = section(cli, "### restart", /^### /);
    expect(restart).toContain("--wait");
    expect(restart).toContain("finish");
    expect(restart).not.toContain("a restart cuts every turn in flight in every session on this machine");
  });

  test("operations.md's restart section says what finishes and names --wait", () => {
    const ops = section(read(`${REF}/operations.md`), "## 5. Restarting splice", /^## /);
    expect(ops).toContain("--wait");
    expect(ops).toContain("finish");
    expect(ops).not.toContain("A restart cuts the turns in flight in every session on this machine, this one included.");
    expect(ops).not.toContain("Without `--now`, waits for compactions in flight");
  });

  test("reliability-mcp-install.md no longer says a restart first waits for compactions unless --now", () => {
    const rel = flat(read(`${REF}/reliability-mcp-install.md`));
    expect(rel).not.toContain("`splice restart` first waits for running compactions, up to 30 minutes, unless `--now`");
    expect(rel).toContain("--wait");
  });

  test("heads-and-backends.md no longer tells the manager to say a restart cuts every turn", () => {
    expect(flat(read(`${REF}/heads-and-backends.md`))).not.toContain("Say first that it cuts every turn in flight on the machine");
  });

  test("the manager's CLAUDE.md says what a restart does now", () => {
    const home = flat(read(`${HOME}/CLAUDE.md`));
    expect(home).not.toContain("It cuts the turns in flight in every session on this machine, this one included.");
    const bullet = home.split("- **").find((b) => b.startsWith("`splice restart`.**")) ?? "";
    expect(bullet).toContain("finish");
  });

  test("no person's machine is named", () => {
    for (const p of [`${HOME}/CLAUDE.md`, `${REF}/cli.md`, `${REF}/operations.md`, `${REF}/reliability-mcp-install.md`, `${REF}/heads-and-backends.md`]) {
      expect(read(p)).not.toMatch(/\/home\/marcos|\/Users\/marcos/);
    }
  });
});
