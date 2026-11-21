// df-live-run: the live test has been run on the real Claude Desktop in the sandbox, driven as a person would, and
// its record is in docs/specs/desktop-full.md: one row per case of outcome 8 with what was seen, every Unknown the
// app study left open answered, and no feature `splice desktop` names still "unknown".

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT, Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { slottedHead, turnOn } from "../desktop/lib/desktop.ts";
import { appOnYes, deskJson } from "./lib/full.ts";

setDefaultTimeout(180_000);

const CASES = [
  "on and relaunch",
  "picker in Chat",
  "picker in Cowork",
  "picker in Code",
  "turn on a GPT head",
  "turn on a Grok head",
  "turn on Claude",
  "model switch mid-session",
  "effort levels",
  "context meter",
  "compaction",
  "subagent naming a tier",
  "titles",
  "image attachment",
  "MCP tool",
  "auto mode",
  "web search and fetch on a non-Claude head",
  "usage display",
  "Chat turn",
  "Cowork turn",
  "perf rows",
  "picker after a head is added",
  "off",
  "standard mode",
];
const UNKNOWNS = ["standard-mode picker", "standard-mode titles", "connectors under the switch", "Remote Control under the switch", "Developer menu"];

/** The live test's record: the section of the spec that holds it. */
function record(): string {
  const spec = readFileSync(join(ROOT, "docs", "specs", "desktop-full.md"), "utf8");
  const at = spec.indexOf("\n## The live test, run");
  if (at < 0) throw new Error("docs/specs/desktop-full.md has no section \"## The live test, run\"");
  const rest = spec.slice(at + 1);
  const next = rest.indexOf("\n## ", 3);
  return next < 0 ? rest : rest.slice(0, next);
}

/** The record's table rows, by case: `| case | result | what was seen |`. */
function rows(): Record<string, { result: string; seen: string }> {
  const out: Record<string, { result: string; seen: string }> = {};
  for (const line of record().split("\n")) {
    const cells = line.split("|").map((c) => c.trim());
    if (cells.length >= 5 && CASES.includes(cells[1]!)) out[cells[1]!] = { result: cells[2]!.toLowerCase(), seen: cells[3]! };
  }
  return out;
}

let astra: Upstream;
let s: Splice;

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }));
  turnOn(s, "--model", "claude-astra--astra-large");
  appOnYes(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  astra?.stop();
});

describe("df-live-run", () => {
  test("every case of the live test has a row, a result and what was seen", () => {
    const r = rows();
    for (const c of CASES) {
      expect([c, r[c] !== undefined]).toEqual([c, true]);
      expect([c, ["pass", "fail", "unavailable", "needs sign-in"].includes(r[c]?.result ?? "")]).toEqual([c, true]);
      expect([c, (r[c]?.seen ?? "").length > 20]).toEqual([c, true]);
    }
  });

  test("no case failed", () => {
    for (const [c, v] of Object.entries(rows())) expect([c, v.result]).not.toEqual([c, "fail"]);
  });

  test("every Unknown of the app study is answered in the record", () => {
    const text = record();
    for (const u of UNKNOWNS) expect([u, text.includes(u)]).toEqual([u, true]);
  });

  test("no feature splice desktop names is still unknown, in either mode", () => {
    for (const f of deskJson(s).features ?? []) expect([f.mode, f.name, f.state]).not.toEqual([f.mode, f.name, "unknown"]);
    expect((deskJson(s).features ?? []).length).toBeGreaterThan(0);
  });
});
