// Mutation-proves src/lib/configguard.ts (DR-115, DR-131/132, DR-133). The wall guards the RULES;
// this proves the wall can actually fail. The real checker runs against a mirrored tree — every
// file it reads — so fixtures never touch the repo's own config.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { configGuardProblems } from "../src/lib/configguard.ts";
import { fnmatchCase } from "../src/lib/dependabot.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
let tmp = "";
const guard = () => configGuardProblems(tmp);
const names = (problems: string[], phrase: string) => problems.some((p) => p.includes(phrase));

beforeAll(() => {
  tmp = mkdtempSync(join(tmpdir(), "gate-configguard-"));
  for (const rel of ["quality/detekt/detekt.yml", ".github/dependabot.yml", "package.json"]) {
    mkdirSync(dirname(join(tmp, rel)), { recursive: true });
    cpSync(join(repoRoot, rel), join(tmp, rel));
  }
  cpSync(join(repoRoot, "quality", "rules"), join(tmp, "quality", "rules"), { recursive: true });
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

  // ── the Dependabot Kotlin scope (checks/config/dependabot-kotlin-scope.ts's arms until PR 6) ──
  const dependabot = () => join(tmp, ".github", "dependabot.yml");
  function withDependabot(mutate: (text: string) => string, check: (problems: string[]) => void): void {
    const original = readFileSync(dependabot(), "utf8");
    writeFileSync(dependabot(), mutate(original));
    try {
      check(guard());
    } finally {
      writeFileSync(dependabot(), original);
    }
  }
  test("an ignore glob that swallows kotlinx is refused by glob and name", () => {
    withDependabot((t) => t.replace('- dependency-name: "org.jetbrains.kotlin:*"', '- dependency-name: "org.jetbrains.kotlin*"'), (p) =>
      expect(names(p, "ignore glob 'org.jetbrains.kotlin*' swallows kotlinx name 'org.jetbrains.kotlinx.kover'")).toBe(true));
  });
  test("a toolchain name no glob blocks is refused by name", () => {
    withDependabot((t) => t.replace(/ *- dependency-name: "org\.jetbrains\.kotlin\.\*".*\n/, ""), (p) =>
      expect(names(p, "toolchain name 'org.jetbrains.kotlin.jvm' is not blocked by any ignore glob")).toBe(true));
  });
  test("a broken grouping contract is refused with the value quoted back", () => {
    withDependabot((t) => t.replace(/(gradle-minor-patch:\n\s+applies-to: version-updates\n\s+patterns:) \["\*"\]/, '$1 ["org.*"]'), (p) =>
      expect(names(p, "patterns is ['org.*'], expected ['*']")).toBe(true));
  });
  test("dependabot.yml without a gradle block, or unparseable, refuses to pass", () => {
    withDependabot((t) => t.replace('package-ecosystem: "gradle"', 'package-ecosystem: "maven"'), (p) => expect(names(p, "no gradle update found")).toBe(true));
    withDependabot(() => "updates: [\n", (p) => expect(names(p, "not parseable YAML")).toBe(true));
  });
  test("fnmatchCase is Python's, including the literal ] that opens a class", () => {
    expect(fnmatchCase("org.jetbrains.kotlinx.kover", "org.jetbrains.kotlin*")).toBe(true);
    expect(fnmatchCase("org.jetbrains.kotlinx.kover", "org.jetbrains.kotlin.*")).toBe(false);
    expect(fnmatchCase("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlin:*")).toBe(true);
    expect(fnmatchCase("]", "[!]a]")).toBe(false);
    expect(fnmatchCase("b", "[!]a]")).toBe(true);
    expect(fnmatchCase("abc", "[!]a]")).toBe(false);
  });
});
