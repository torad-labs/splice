// gw-seen: what Claude Code does in gateway mode is read from a real launch of the version installed, and recorded in
// docs/specs/door-default.md's "Seen with Claude Code" table, each row with the version and the day it was seen: that
// discovery fills `/model` from the gateway, how its rows sit beside the built-in alias rows (a Claude model folded into
// the alias that names it, each model once), that no sign-in screen shows, that a selector in ANTHROPIC_MODEL or
// --model starts the session on it, the window the session holds for a selector, and that a head's command shows the
// same list as `claude`.

import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const ROOT = spawnSync("git", ["rev-parse", "--show-toplevel"], { cwd: import.meta.dir, encoding: "utf8" }).stdout.trim();

const spec = readFileSync(join(ROOT, "docs/specs/door-default.md"), "utf8");
const table = spec.slice(spec.indexOf("## Seen with Claude Code"));
const rows = table
  .split("\n")
  .filter((l) => l.startsWith("|") && !/^\|\s*-/.test(l) && !/^\|\s*Question/.test(l))
  .map((l) => {
    const cells = l.split("|").map((c) => c.trim());
    return { question: cells[1] ?? "", seen: cells.slice(2).join("|") };
  });
const gateway = rows.filter((r) => /gateway mode|CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY|ANTHROPIC_BASE_URL/i.test(r.question));

describe("gw-seen", () => {
  test("every gateway-mode row names the Claude Code version and the day it was seen", () => {
    expect(gateway.length).toBeGreaterThanOrEqual(6);
    for (const r of gateway) {
      expect([r.question, /2\.\d+\.\d+/.test(r.seen)]).toEqual([r.question, true]);
      expect([r.question, /(Nov|Dec)[a-z]* \d{1,2}, 2026|2026-\d\d-\d\d/.test(r.seen)]).toEqual([r.question, true]);
    }
  });

  test("the rows cover discovery, the alias rows, the sign-in screen, the starting model, the window and a head's command", () => {
    const asked = gateway.map((r) => r.question).join("\n");
    for (const about of [/\/model/, /alias/i, /sign-in/i, /ANTHROPIC_MODEL|--model/, /window/i, /head's (own )?command/i]) {
      expect([String(about), about.test(asked)]).toEqual([String(about), true]);
    }
  });
});
