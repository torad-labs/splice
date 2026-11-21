// gw-docs: what a person or an agent reads says it as the product now behaves. door-default.md's decisions put every
// session in gateway mode with discovery (in place of decision 4's picker rows) and set the coming removal of custom
// heads with the retire, restore and check commands (in place of decision 7's "no removal"); `allowedProviders` is
// named as the managed setting an organization may use, which splice never writes. The README, AGENTS.md, the
// CHANGELOG and the splice skill's reference say custom heads are deprecated and will be removed in a coming release,
// and what replaces them. The repository is public: no person's home folder in any of it.

import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const ROOT = spawnSync("git", ["rev-parse", "--show-toplevel"], { cwd: import.meta.dir, encoding: "utf8" }).stdout.trim();
const read = (path: string) => readFileSync(join(ROOT, path), "utf8");
const REFERENCE = "app/src/main/resources/manager-home/.claude/skills/splice/reference";
const reference = () => readdirSync(join(ROOT, REFERENCE)).map((f) => read(join(REFERENCE, f))).join("\n");
const REMOVAL = /deprecated and will be removed in a coming release/i;
const COMMANDS = ["splice claude retire", "splice claude restore", "splice claude check"];

describe("gw-docs", () => {
  test("door-default.md's decisions are gateway mode and the coming removal, not picker rows and no removal", () => {
    const spec = read("docs/specs/door-default.md");
    const decisions = spec.slice(spec.indexOf("## Decisions"), spec.indexOf("## Seen with Claude Code"));
    expect(decisions).toContain("CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY");
    expect(decisions).toContain("/v1/models");
    expect(decisions).toContain("Splice gateway");
    expect(decisions).toContain("allowedProviders");
    expect(decisions).toMatch(REMOVAL);
    for (const c of COMMANDS) expect([c, decisions.includes(c)]).toEqual([c, true]);
    expect(decisions).not.toMatch(/no date is set for removal/i);
    expect(decisions).not.toMatch(/`modelPicker` with `replaceBuiltInOptions` true/);
    expect(decisions).not.toMatch(/`CLAUDE_CODE_USE_MANTLE=1`, `ANTHROPIC_BEDROCK_MANTLE_BASE_URL` \(the door\)/);
  });

  test("the README, AGENTS.md, the CHANGELOG and the skill's reference say heads go in a coming release, and what replaces them", () => {
    for (const [path, text] of [
      ["README.md", read("README.md")],
      ["AGENTS.md", read("AGENTS.md")],
      ["CHANGELOG.md", read("CHANGELOG.md")],
      [REFERENCE, reference()],
    ] as const) {
      expect([path, REMOVAL.test(text)]).toEqual([path, true]);
      expect([path, text.includes("splice claude retire")]).toEqual([path, true]);
    }
    const ref = reference();
    for (const c of COMMANDS) expect([c, ref.includes(c)]).toEqual([c, true]);
    expect(ref).toContain("CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY");
  });

  test("none of it names a person's home folder", () => {
    for (const path of ["AGENTS.md", "CHANGELOG.md", "docs/specs/door-default.md"]) {
      expect([path, /\/home\/[a-z]/.test(read(path))]).toEqual([path, false]);
    }
    expect(/\/home\/[a-z]/.test(reference())).toBe(false);
  });
});
