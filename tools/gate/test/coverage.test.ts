// The red proofs for P1. A coverage proof that has never been shown to fail proves nothing about
// the tree; it proves only that it ran. Each mutant below is a real rule file, copied and then
// broken in one of the four ways a glob can silently stop enforcing something, and each must turn
// the proof RED with a message that names the rule and what it lost. The same rules, copied and NOT
// broken, must be green — a baseline that is red would make every mutant meaningless.
import { afterAll, describe, expect, test } from "bun:test";
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { proveCoverage, type CoverageReport } from "../src/lib/coverage.ts";
import { layout } from "../src/lib/repo.ts";
import { EXCLUSIONS, ROUTED_CONFIG } from "../src/commands/rules.ts";

const { repoRoot, buildRoot } = layout();
const workspaces: string[] = [];

afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

/** A copy of the REAL rule set and the REAL exclusion table, which a mutant then edits. */
function copyOfTheRealRules(): { sgconfig: string; exclusions: string; rule(id: string): string } {
  const dir = mkdtempSync(join(tmpdir(), "gate-coverage-mutant-"));
  workspaces.push(dir);
  cpSync(join(repoRoot, "quality", "rules", "console"), join(dir, "console"), { recursive: true });
  cpSync(join(repoRoot, "quality", "rules", "kotlin"), join(dir, "kotlin"), { recursive: true });
  writeFileSync(join(dir, "sgconfig.yml"), "ruleDirs:\n  - console\n  - kotlin\n");
  // A distinct filename per copy: the TOML reader imports by path, and Bun caches modules by path.
  const exclusions = join(dir, "exclusions.toml");
  cpSync(join(repoRoot, EXCLUSIONS), exclusions);
  return {
    sgconfig: join(dir, "sgconfig.yml"),
    exclusions,
    rule: (id: string) => join(dir, "kotlin", `${id}.yml`),
  };
}

const prove = (sgconfigPath: string, exclusionsPath: string): Promise<CoverageReport> =>
  proveCoverage({ repoRoot, buildRoot, sgconfigPath, exclusionsPath });

function edit(path: string, from: string, to: string): void {
  const before = readFileSync(path, "utf8");
  expect(before, `${path} must contain the text the mutant replaces`).toContain(from);
  writeFileSync(path, before.replace(from, to));
}

function messagesFor(report: CoverageReport, rule: string, kind: string): string[] {
  return report.findings.filter((f) => f.rule === rule && f.kind === kind).map((f) => f.message);
}

describe("the P1 coverage proof", () => {
  test("is green on the real tree, and on an unmutated copy of the real rules", async () => {
    const real = await prove(join(repoRoot, ROUTED_CONFIG), join(repoRoot, EXCLUSIONS));
    expect(real.findings.map((f) => f.message)).toEqual([]);
    expect(real.ok).toBe(true);
    expect(real.rules).toBe(62);
    expect(real.crossCheckAgreed).toBe(real.crossChecked);
    expect(real.crossChecked).toBe(62);

    const copy = copyOfTheRealRules();
    const baseline = await prove(copy.sgconfig, copy.exclusions);
    expect(baseline.findings.map((f) => f.message)).toEqual([]);
    expect(baseline.ok).toBe(true);
  });

  test("mutant (a): a glob that matches nothing", async () => {
    const copy = copyOfTheRealRules();
    edit(copy.rule("kt-no-println"), '"**/src/main/**/*.kt"', '"gateway/does-not-exist/src/main/**/*.kt"');
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const [message] = messagesFor(report, "kt-no-println", "rule-matches-nothing");
    expect(message).toContain("kt-no-println matches NO file in the expected source set");
    expect(message).toContain("gateway/does-not-exist/src/main/**/*.kt");
  });

  test("mutant (b): a glob that loses one module while the others still match", async () => {
    const copy = copyOfTheRealRules();
    // :core drops out; every other module still matches, so `ast-grep scan` stays green.
    edit(
      copy.rule("kt-no-lateinit"),
      '"**/src/main/**/*.kt"',
      '"gateway/{app,control,gateway,provider-spi,provider-codex,provider-grok,provider-kimi,provider-muse,provider-openai,dialect-anthropic-passthrough,dialect-openai-responses,dialect-openai-chat,fir-checks}/src/main/**/*.kt"',
    );
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const lost = messagesFor(report, "kt-no-lateinit", "source-root-lost");
    expect(lost).toHaveLength(1);
    expect(lost[0]).toContain("kt-no-lateinit reaches 0 of 135 files in core/src/main/kotlin");
    expect(lost[0]).toContain("no dated exclusion covers it");
    // the loss is invisible to the scan: the other thirteen modules are still enforced
    expect(report.coveredPairs).toBeGreaterThan(700);
  });

  test("mutant (c): a glob that loses one file inside a covered module", async () => {
    const copy = copyOfTheRealRules();
    edit(
      copy.rule("kt-no-unsafe-cast"),
      'files:\n  - "**/src/main/**/*.kt"',
      'files:\n  - "**/src/main/**/*.kt"\nignores:\n  - core/src/main/kotlin/splice/core/wire/HttpStatus.kt',
    );
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const lost = messagesFor(report, "kt-no-unsafe-cast", "file-lost");
    expect(lost).toHaveLength(1);
    expect(lost[0]).toContain("kt-no-unsafe-cast loses 1 file(s) inside covered core/src/main/kotlin");
    expect(lost[0]).toContain("splice/core/wire/HttpStatus.kt");
  });

  test("mutant (d): an exclusion with no date", async () => {
    const copy = copyOfTheRealRules();
    edit(copy.exclusions, '[[exclusion]]\nrule = "kt-no-println"\nmodules = ["app"]\nsourceSets = ["main"]\ndate = "2026-09-20"', '[[exclusion]]\nrule = "kt-no-println"\nmodules = ["app"]\nsourceSets = ["main"]');
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const invalid = report.findings.filter((f) => f.kind === "exclusion-invalid").map((f) => f.message);
    expect(invalid).toHaveLength(1);
    expect(invalid[0]).toContain("kt-no-println");
    expect(invalid[0]).toContain("has NO DATE");
  });

  test("a files-row can never excuse a WHOLLY lost source root", async () => {
    // kt-no-vanilla-config-dir carries a files-row spanning all of :control's main sources. If its
    // glob for :control disappears, that row must not absorb the loss — losing a module is the
    // failure the proof exists for, and a broad file exemption is the obvious way to hide it.
    const copy = copyOfTheRealRules();
    edit(copy.rule("kt-no-vanilla-config-dir"), "  - gateway/control/src/main/kotlin/splice/control/Launch*.kt\n", "");
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const lost = messagesFor(report, "kt-no-vanilla-config-dir", "source-root-lost");
    expect(lost).toHaveLength(1);
    expect(lost[0]).toContain("gateway/control/src/main/kotlin");
  });

  test("mutant (e): a modules row typed as a scalar cannot waive the source root it names", async () => {
    // `modules = "app"` instead of `modules = ["app"]`. Valid TOML, one character, and on a reader
    // that maps a mistyped field to [] the whole report stays GREEN: the row still covers :app's
    // loss — and every other module's too, for any rule it names.
    const copy = copyOfTheRealRules();
    edit(
      copy.exclusions,
      '[[exclusion]]\nrule = "kt-no-println"\nmodules = ["app"]',
      '[[exclusion]]\nrule = "kt-no-println"\nmodules = "app"',
    );
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const invalid = report.findings.filter((f) => f.kind === "exclusion-invalid").map((f) => f.message);
    expect(invalid).toHaveLength(1);
    expect(invalid[0]).toContain("kt-no-println");
    expect(invalid[0]).toContain('has modules = the string "app"');
    // and the loss it used to excuse is reported again, by name
    const lost = messagesFor(report, "kt-no-println", "source-root-lost");
    expect(lost).toHaveLength(1);
    expect(lost[0]).toContain("gateway/app/src/main/kotlin");
  });

  test("mutant (f): a files row typed as a scalar cannot become a whole-source-root row", async () => {
    const copy = copyOfTheRealRules();
    edit(
      copy.exclusions,
      'files = ["core/src/main/kotlin/splice/core/wire/HttpStatus.kt"]',
      'files = "core/src/main/kotlin/splice/core/wire/HttpStatus.kt"',
    );
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const invalid = report.findings.filter((f) => f.kind === "exclusion-invalid").map((f) => f.message);
    expect(invalid).toHaveLength(1);
    expect(invalid[0]).toContain("kt-http-status-single-source");
    expect(invalid[0]).toContain("has files = the string");
    // the file it named is lost again — the row did not widen into one that excuses source roots
    const lost = messagesFor(report, "kt-http-status-single-source", "file-lost");
    expect(lost).toHaveLength(1);
    expect(lost[0]).toContain("splice/core/wire/HttpStatus.kt");
    expect(messagesFor(report, "kt-http-status-single-source", "source-root-lost")).toEqual([]);
  });

  test("a stale exclusion is a finding, not a default", async () => {
    const copy = copyOfTheRealRules();
    edit(
      copy.exclusions,
      '[[exclusion]]\nrule = "kt-no-println"\nmodules = ["app"]',
      '[[exclusion]]\nrule = "kt-no-println"\nmodules = ["provider-openai"]',
    );
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    expect(report.findings.some((f) => f.kind === "exclusion-stale" && f.message.includes("kt-no-println"))).toBe(true);
    expect(report.findings.some((f) => f.kind === "source-root-lost" && f.message.includes("gateway/app/src/main/kotlin"))).toBe(true);
  });
});
