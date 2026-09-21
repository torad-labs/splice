// Mutation-proves src/lib/laws.ts: both laws both ways on synthetic rows, the unreadable fence on
// the REAL filesystem, and the empty ledger as a real subprocess so the exit code is the evidence.
import { describe, expect, test } from "bun:test";
import { chmodSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { check, cssFences, undecidableFences, type Row } from "../src/lib/laws.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const GATE = join(repoRoot, "tools", "gate", "index.ts");
const laws = (rows: Row[]) => [...new Set(check(rows).findings.map((f) => `law-${f.law}`))].sort();
const one = (files: string[], verify: string): Row[] => [{ id: "X-1", status: "done", files, verify }];
const SELF = ["tools/gate/src/lib/laws.ts"]; // a fence with no CSS anywhere

describe("gate ledger laws: both laws, both ways", () => {
  test("a CSS-fenced row with no build leg is RED", () => {
    expect(laws(one(["console/src/widgets/rule/rule.css"], "npx tsc --noEmit"))).toEqual(["law-25"]);
  });
  test("the same row WITH npx vite build is GREEN", () => {
    expect(laws(one(["console/src/widgets/rule/rule.css"], "npx tsc --noEmit && npx vite build"))).toEqual([]);
  });
  test("npm run build is NOT a build leg (tsc masks the CSS defect)", () => {
    expect(laws(one(["console/src/widgets/rule/rule.css"], "npm run build -w console"))).toEqual(["law-25"]);
  });
  test("a CSS-holding directory counts, not just a .css path", () => {
    expect(laws(one(["console/src/widgets/rule/**"], "npx tsc --noEmit"))).toEqual(["law-25"]);
  });
  test("a fence with no CSS anywhere is not a law-25 row", () => {
    expect(laws(one(SELF, "npx tsc --noEmit"))).toEqual([]);
  });
  test("the M1-08 shape is RED", () => {
    expect(laws(one(SELF, "if npx vitest run tests/world.test.ts 2>&1 | grep -E -q 'src/pages/'; then exit 1; fi"))).toEqual(["law-27"]);
  });
  test("a bare ! grep is RED", () => {
    expect(laws(one(SELF, "! grep -q TODO file.txt"))).toEqual(["law-27"]);
  });
  test("emptiness as the pass is RED", () => {
    expect(laws(one(SELF, 'test -z "$(grep -v ok file.txt)"'))).toEqual(["law-27"]);
  });
  test("a presence assertion of the compliant shape is GREEN", () => {
    expect(laws(one(SELF, "bun console/tools scan console/src && grep -q OWNER .dev/web-console/census/m1-punch-list.md"))).toEqual([]);
  });
  test("both laws on one row are both reported", () => {
    expect(laws(one(["console/src/widgets/rule/rule.css"], "npx tsc --noEmit && if grep -q x y; then exit 1; fi"))).toEqual(["law-25", "law-27"]);
  });
  test("an unreadable row is a finding of its own (law 0), never dropped from the denominator", () => {
    const r = check([{ id: "X-9", status: null, verify: null, files: [], unreadable: true }]);
    expect(r.dispositions.unreadable).toBe(1);
    expect(r.findings.map((f) => f.law)).toEqual([0]);
  });
});

// THE UNREADABLE TREE, on the REAL filesystem and not on a stub. A stubbed `undecidableOf` would
// prove only that check() reacts to a list someone handed it, which is the half that was never in
// doubt; the half that was broken is holdsCss' answer, and the only thing that can produce that
// answer is a directory the process cannot read. chmod 000, and the three answers are asked of the
// EXPORTED surface the check actually calls.
describe("gate ledger laws: the unreadable fence", () => {
  const box = join(tmpdir(), `gate-laws-unreadable-${process.pid}`);
  const locked = join(box, "locked");
  const mixed = join(box, "mixed");
  const arm = (body: () => void): void => {
    mkdirSync(join(locked, "inner"), { recursive: true });
    writeFileSync(join(locked, "inner", "hidden.css"), "a{}");
    mkdirSync(join(mixed, "shut"), { recursive: true });
    writeFileSync(join(mixed, "seen.css"), "a{}");
    chmodSync(locked, 0o000);
    chmodSync(join(mixed, "shut"), 0o000);
    try {
      body();
    } finally {
      chmodSync(locked, 0o755);
      chmodSync(join(mixed, "shut"), 0o755);
      rmSync(box, { recursive: true, force: true });
    }
  };

  test("an UNREADABLE fence is `unknown`, not `no`; ENOENT is a real `no`; a .css found first wins", () => {
    arm(() => {
      const unreadable = [`${locked}/**`];
      expect(undecidableFences(unreadable)).toHaveLength(1);
      expect(cssFences(unreadable)).toHaveLength(0);
      const gone = [`${box}/never-created/**`];
      expect(undecidableFences(gone)).toHaveLength(0);
      expect(cssFences(gone)).toHaveLength(0);
      const partial = [`${mixed}/**`];
      expect(cssFences(partial)).toHaveLength(1);
      expect(undecidableFences(partial)).toHaveLength(0);
      // AND THE WHOLE POINT: the row this fence belongs to must not come back `ok`. Before this arm
      // it did — cssFences returned [], `css.length > 0` was false, and the law-25 arm never ran.
      const result = check(one(unreadable, "npx tsc --noEmit"));
      expect([result.dispositions.undecidable, result.dispositions.ok, result.findings.length]).toEqual([1, 0, 1]);
      expect(result.findings[0]!.detail).toStartWith("DID NOT RUN");
    });
  });
});

describe("gate ledger: the contract", () => {
  test("an empty ledger is DID NOT RUN (exit 2), not a clean report — a real subprocess against a fixture", () => {
    const fixture = join(tmpdir(), `gate-laws-empty-${process.pid}.toml`);
    writeFileSync(fixture, "# a ledger with no rows\n");
    try {
      const proc = Bun.spawnSync([process.execPath, GATE, "ledger", "laws", "--ledger", fixture], { cwd: repoRoot, stdout: "pipe", stderr: "pipe" });
      expect(proc.exitCode, proc.stderr.toString()).toBe(2);
      expect(proc.stdout.toString()).toContain("law-check: 0 row(s) read from the ledger");
    } finally {
      rmSync(fixture, { force: true });
    }
  });

  test("an unknown or missing subverb, and stray arguments, are refused with exit 2", () => {
    for (const argv of [[], ["census"], ["landed", "a.toml", "b.toml"], ["landed", "--json"], ["laws", "--bogus"]]) {
      const proc = Bun.spawnSync([process.execPath, GATE, "ledger", ...argv], { cwd: repoRoot, stdout: "pipe", stderr: "pipe" });
      expect(proc.exitCode, `argv ${argv.join(" ")}`).toBe(2);
    }
  });
});
