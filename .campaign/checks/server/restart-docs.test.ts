// restart-at-once, docs-readme: the README, the CHANGELOG and docs/specs say what a restart and an upgrade do now.
// They hand over at once; `--wait` brings back the old wait; the wait a restart keeps by default is for compactions it
// would cut, and the README says which those are and why. No sentence still says a restart or an upgrade waits by
// default. The repository is public: no person's machine is named. Whitespace is read as one space, so a sentence
// wrapped across lines reads whole.

import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./lib/harness.ts";

const read = (p: string) => readFileSync(join(ROOT, p), "utf8");
const flat = (s: string) => s.replace(/\s+/g, " ");
const README = read("README.md");

/** The text from the first line starting with [start] up to the next line starting with [end] (or the end). */
function section(text: string, start: string, end: RegExp): string {
  const lines = text.split("\n");
  const from = lines.findIndex((l) => l.startsWith(start));
  if (from < 0) throw new Error(`no line starts with ${start}`);
  const rest = lines.slice(from + 1);
  const to = rest.findIndex((l) => end.test(l));
  return flat([lines[from], ...(to < 0 ? rest : rest.slice(0, to))].join("\n"));
}

/** The one README line of the command list that starts with [command]. */
function listed(command: string): string {
  const line = README.split("\n").find((l) => l.startsWith(`${command} `) && l.includes("#"));
  if (!line) throw new Error(`no command-list line for ${command}`);
  return line;
}

describe("docs-readme", () => {
  test("the command list names --wait on restart and upgrade, and restart no longer says it waits", () => {
    const restart = listed("splice restart");
    expect(restart).toContain("--wait");
    expect(restart).not.toContain("after waiting for running compactions");
    expect(listed("splice upgrade")).toContain("--wait");
  });

  test("Upgrading says the upgrade hands over at once and --wait waits, with no default 30-minute wait for turns", () => {
    const upgrading = section(README, "**Upgrading.**", /^\s*$/);
    expect(upgrading).toContain("--wait");
    expect(upgrading).not.toContain("waits up to 30 minutes for every head's in-flight turns to finish (`--now` skips");
  });

  test("Long-session reliability names the one wait kept by default, and --wait and ?wait=1", () => {
    const long = section(README, "### Long-session reliability", /^###? /);
    expect(long).not.toContain("Unless you choose `--now`, `splice restart`, `splice upgrade` and `POST /api/daemon/restart` wait for compactions first");
    expect(long).toContain("--wait");
    expect(long).toContain("?wait=1");
    expect(long).toContain("slot");
    expect(long).toContain("Muse");
  });

  test("the CHANGELOG's Unreleased section has the change", () => {
    const unreleased = section(read("CHANGELOG.md"), "## Unreleased", /^## /);
    expect(unreleased).toContain("splice restart");
    expect(unreleased).toContain("--wait");
  });

  test("the v0.4.0 spec's upgrade wait carries the change", () => {
    expect(read("docs/specs/v0.4.0.md")).toContain("--wait");
  });

  test("no person's machine is named", () => {
    for (const text of [README, read("CHANGELOG.md")]) expect(text).not.toMatch(/\/home\/marcos|\/Users\/marcos/);
  });
});
