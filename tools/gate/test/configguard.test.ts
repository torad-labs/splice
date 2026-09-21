// Mutation-proves src/lib/configguard.ts (DR-115, DR-131/132, DR-133). The wall guards the RULES;
// this proves the wall can actually fail. The real checker runs against a mirrored tree — every
// file it and its two subprocess legs read — so fixtures never touch the repo's own config.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { configGuardProblems } from "../src/lib/configguard.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
let tmp = "";
const guard = () => configGuardProblems(tmp);
const names = (problems: string[], phrase: string) => problems.some((p) => p.includes(phrase));

/** Copy a repo-relative file into the mirror, then every relative import it makes, recursively —
 *  so the mirror follows the checkers' imports wherever a later move puts them. */
function mirrorWithImports(rel: string, seen = new Set<string>()): void {
  if (seen.has(rel)) return;
  seen.add(rel);
  const src = join(repoRoot, rel);
  mkdirSync(dirname(join(tmp, rel)), { recursive: true });
  cpSync(src, join(tmp, rel));
  if (!rel.endsWith(".ts")) return;
  for (const [, target] of readFileSync(src, "utf8").matchAll(/from\s+"(\.[^"]+)"/g)) {
    const abs = resolve(dirname(src), target!);
    mirrorWithImports(abs.slice(repoRoot.length + 1), seen);
  }
}

beforeAll(() => {
  tmp = mkdtempSync(join(tmpdir(), "gate-configguard-"));
  for (const rel of ["quality/detekt/detekt.yml", ".github/dependabot.yml", "package.json", "checks/gate.sh"]) {
    mkdirSync(dirname(join(tmp, rel)), { recursive: true });
    cpSync(join(repoRoot, rel), join(tmp, rel));
  }
  cpSync(join(repoRoot, "quality", "rules"), join(tmp, "quality", "rules"), { recursive: true });
  mirrorWithImports("checks/config/dependabot-kotlin-scope.ts");
  mirrorWithImports("checks/config/concentration-leg-routed.ts");
});
afterAll(() => rmSync(tmp, { recursive: true, force: true }));

const fixture = () => join(tmp, "quality", "rules", "kotlin", "zz-dr115-fixture.yml");
function withFixture(yaml: string, check: (problems: string[]) => void): void {
  writeFileSync(fixture(), yaml);
  try {
    check(guard());
  } finally {
    rmSync(fixture(), { force: true });
  }
}

describe("the config guard", () => {
  test("control: the mirrored, unmutated tree is green", () => {
    expect(guard()).toEqual([]);
  });

  // ── the severity wall: 6 dodges that a line grep waved through, 2 shapes that must still pass ─
  test("1. DR-115: a second YAML doc downgraded to warning fails, not hides behind doc one", () => {
    withFixture(
      "id: dr115-doc-one\nlanguage: kotlin\nseverity: error\nrule:\n  pattern: selftestBadOne()\n---\nid: dr115-doc-two\nlanguage: kotlin\nseverity: warning\nrule:\n  pattern: selftestBadTwo()\n",
      (p) => expect(names(p, "non-error severity")).toBe(true),
    );
  });
  test("2. DR-115: a second doc with NO severity runs non-blocking and equally fails", () => {
    withFixture(
      "id: dr115-doc-one\nlanguage: kotlin\nseverity: error\nrule:\n  pattern: selftestBadOne()\n---\nid: dr115-doc-two\nlanguage: kotlin\nrule:\n  pattern: selftestBadTwo()\n",
      (p) => expect(names(p, "runs non-blocking")).toBe(true),
    );
  });
  test("3. DR-131: severity nested under metadata: satisfies a line count, not a parser", () => {
    withFixture("id: dr131-nested\nlanguage: kotlin\nmetadata:\n  severity: error\nrule:\n  pattern: selftestBadOne()\n", (p) =>
      expect(names(p, "runs non-blocking")).toBe(true));
  });
  test("4. DR-131: severity: error only inside a note: block scalar", () => {
    withFixture("id: dr131-scalar\nlanguage: kotlin\nnote: |\n  severity: error\nrule:\n  pattern: selftestBadOne()\n", (p) =>
      expect(names(p, "runs non-blocking")).toBe(true));
  });
  test("5. DR-131: the multi-doc case re-opened by a decoy in doc one", () => {
    withFixture(
      "id: dr131-decoy-one\nlanguage: kotlin\nseverity: error\nnote: |\n  severity: error\nrule:\n  pattern: neverMatchesAnything()\n---\nid: dr131-decoy-two\nlanguage: kotlin\nrule:\n  pattern: selftestBadTwo()\n",
      (p) => expect(names(p, "runs non-blocking")).toBe(true),
    );
  });
  test("6. DR-132: flow style has no line-leading id:, so the old wall skipped the file whole", () => {
    withFixture("{id: dr132-flow, language: kotlin, rule: {pattern: selftestBadOne()}}\n", (p) =>
      expect(names(p, "runs non-blocking")).toBe(true));
  });
  test("7. CONTROL: a flow-style rule that IS a blocking error passes — the wall keys on severity, not shape", () => {
    withFixture("{id: dr132-flow-ok, language: kotlin, severity: error, rule: {pattern: selftestBadOne()}}\n", (p) =>
      expect(p).toEqual([]));
  });
  test("8. CONTROL: a rule-test FIXTURE needs no severity, in flow style too", () => {
    withFixture('{id: dr132-fixture, valid: ["fun ok() {}"], invalid: ["fun bad() {}"]}\n', (p) => expect(p).toEqual([]));
  });

  // ── the detekt posture ───────────────────────────────────────────────────────────────────────
  const detekt = () => join(tmp, "quality", "detekt", "detekt.yml");
  function withDetekt(mutate: (text: string) => string, check: (problems: string[]) => void): void {
    const original = readFileSync(detekt(), "utf8");
    writeFileSync(detekt(), mutate(original));
    try {
      check(guard());
    } finally {
      writeFileSync(detekt(), original);
    }
  }
  test("a declared detekt baseline is refused", () => {
    withDetekt((t) => `baseline: detekt-baseline.xml\n${t}`, (p) => expect(names(p, "declares a baseline")).toBe(true));
  });
  test("maxIssues above 0 and warningsAsErrors off are refused", () => {
    withDetekt((t) => t.replace(/maxIssues:\s*0/, "maxIssues: 5"), (p) => expect(names(p, "maxIssues must be 0")).toBe(true));
    withDetekt((t) => t.replace(/warningsAsErrors:\s*true/, "warningsAsErrors: false"), (p) =>
      expect(names(p, "warningsAsErrors must be true")).toBe(true));
  });
  test("a baseline file anywhere in the tree is refused by name", () => {
    mkdirSync(join(tmp, "app"), { recursive: true });
    writeFileSync(join(tmp, "app", "detekt-baseline.xml"), "<SmellBaseline/>\n");
    try {
      expect(names(guard(), "baseline.xml exists (app/detekt-baseline.xml)")).toBe(true);
    } finally {
      rmSync(join(tmp, "app"), { recursive: true });
    }
  });

  // ── DR-133: the concentration-leg routing guard's own reachability model ────────────────────
  // Mutations go on the MIRRORED gate.sh, never the repo's own.
  const gate = () => join(tmp, "checks", "gate.sh");
  const LEG = 'run "concentration"  npm run --silent gate:concentration';
  const gateWithoutLeg = () => readFileSync(join(repoRoot, "checks", "gate.sh"), "utf8").split("\n").filter((l) => l !== LEG).join("\n");
  function withGate(text: string, check: (problems: string[]) => void): void {
    writeFileSync(gate(), text);
    try {
      check(guard());
    } finally {
      cpSync(join(repoRoot, "checks", "gate.sh"), gate());
    }
  }
  test("9. DR-133: the leg moved into a function body nobody calls", () => {
    expect(existsSync(gate())).toBe(true);
    withGate(`${gateWithoutLeg()}\ndisabled_legs() {\n${LEG}\n}\n`, (p) => expect(names(p, "nested scope")).toBe(true));
  });
  test("10. DR-133: the identical leg text as heredoc DATA", () => {
    withGate(`${gateWithoutLeg()}\ncat <<'EOF' >/dev/null\n${LEG}\nEOF\n`, (p) => expect(names(p, "does not run")).toBe(true));
  });
  test("11. DR-114 regression pin: the leg wrapped in if false", () => {
    withGate(`${gateWithoutLeg()}\nif false; then\n${LEG}\nfi\n`, (p) => expect(names(p, "nested scope")).toBe(true));
  });
  test("12. CONTROL: the real gate.sh defines functions with braces and stays routed", () => {
    expect(guard()).toEqual([]);
  });
});
