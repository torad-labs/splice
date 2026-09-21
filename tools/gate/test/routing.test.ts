// Mutation-proves src/lib/routing.ts (DR-132). The leg guards the tree; these arms guard the LEG.
// rule-routing.sh shipped without them, and that is exactly how its forward direction ran
// fail-OPEN: a dormant directory holding a flow-style rule reported PASS, because "is this a rule
// file?" was `grep -E '^[[:space:]]*id:'` and flow style has no line-leading `id:`. The real
// checker runs against a mirrored tree so fixtures never touch the repo's own quality/rules.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { cpSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../src/lib/repo.ts";
import { routingProblems } from "../src/lib/routing.ts";
import { ruleFiles } from "../src/lib/ruledocs.ts";

const { repoRoot } = layout();
let tmp = "";
const routing = (allowlist?: readonly string[]) =>
  routingProblems({ repoRoot: tmp, sgconfigPath: join(tmp, "sgconfig.yml"), allowlist });
const restoreConfig = () => cpSync(join(repoRoot, "sgconfig.yml"), join(tmp, "sgconfig.yml"));

beforeAll(() => {
  tmp = mkdtempSync(join(tmpdir(), "gate-routing-"));
  restoreConfig();
  cpSync(join(repoRoot, "quality", "rules"), join(tmp, "quality", "rules"), { recursive: true });
});
afterAll(() => rmSync(tmp, { recursive: true, force: true }));

describe("rule routing", () => {
  test("control: the mirrored, unmutated tree is green", () => {
    expect(routing()).toEqual([]);
  });

  const dormant = () => join(tmp, "quality", "rules", "zz-selftest-dormant");

  test("1. DR-132: an unreferenced directory holding a FLOW-STYLE rule", () => {
    mkdirSync(dormant());
    writeFileSync(join(dormant(), "r.yml"), "{id: zz-dormant-flow, language: kotlin, severity: error, rule: {pattern: selftestBad()}}\n");
    try {
      expect(routing().some((p) => p.includes("but nothing references it"))).toBe(true);
    } finally {
      rmSync(dormant(), { recursive: true });
    }
  });

  test("2. the block-style equivalent — the shape that already worked, pinned so it keeps working", () => {
    mkdirSync(dormant());
    writeFileSync(join(dormant(), "r.yml"), "id: zz-dormant-block\nlanguage: kotlin\nseverity: error\nrule:\n  pattern: selftestBad()\n");
    try {
      expect(routing().some((p) => p.includes("but nothing references it"))).toBe(true);
    } finally {
      rmSync(dormant(), { recursive: true });
    }
  });

  test("3. inverse: a routed ruleDir that holds no rules", () => {
    mkdirSync(join(tmp, "quality", "rules", "zz-empty"));
    writeFileSync(
      join(tmp, "sgconfig.yml"),
      "ruleDirs:\n  - quality/rules/console\n  - quality/rules/kotlin\n  - quality/rules/zz-empty\ntestConfigs:\n  - testDir: quality/rules/rule-tests\n",
    );
    try {
      expect(routing().some((p) => p.includes("holds 0 ast-grep rule files"))).toBe(true);
    } finally {
      rmSync(join(tmp, "quality", "rules", "zz-empty"), { recursive: true });
    }
  });

  test("4. inverse: a routed ruleDir that does not exist", () => {
    try {
      expect(routing().some((p) => p.includes("which does not exist"))).toBe(true);
    } finally {
      restoreConfig();
    }
  });

  test("5. an UNPARSEABLE rule file is counted, never skipped — the enumerator fails closed", () => {
    // The shell arm removed the enumerator script and required a hard failure; the enumerator is
    // an import now, so the property that survives is the fail-closed reading of a file it cannot
    // parse: a broken .yml under a dormant directory still makes that directory unreferenced.
    mkdirSync(dormant());
    writeFileSync(join(dormant(), "r.yml"), "id: [unclosed\n  rule: {\n");
    try {
      expect(ruleFiles(dormant(), 1)).toHaveLength(1);
      expect(routing().some((p) => p.includes("but nothing references it"))).toBe(true);
    } finally {
      rmSync(dormant(), { recursive: true });
    }
  });

  test("6. CONTROL: a dormant dir carrying a dated allowlist entry stays green; undated or stale entries do not", () => {
    mkdirSync(dormant());
    writeFileSync(join(dormant(), "r.yml"), "id: zz-dormant-block\nlanguage: kotlin\nseverity: error\nrule:\n  pattern: selftestBad()\n");
    try {
      expect(routing(["quality/rules/zz-selftest-dormant|2026-09-21: a reference pack, deliberately unrouted"])).toEqual([]);
      expect(routing(["quality/rules/zz-selftest-dormant|no date here"]).some((p) => p.includes("has no dated reason"))).toBe(true);
      expect(routing(["quality/rules/zz-gone|2026-09-21: stale"]).some((p) => p.includes("which no longer exists"))).toBe(true);
    } finally {
      rmSync(dormant(), { recursive: true });
    }
  });

  test("a sgconfig with no ruleDirs is dormant everywhere, by name", () => {
    writeFileSync(join(tmp, "sgconfig.yml"), "testConfigs:\n  - testDir: quality/rules/rule-tests\n");
    try {
      expect(routing().some((p) => p.includes("declares no ruleDirs"))).toBe(true);
    } finally {
      restoreConfig();
    }
  });
});
