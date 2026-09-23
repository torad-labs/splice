// The red proofs for P1. A coverage proof that has never been shown to fail proves nothing about
// the tree; it proves only that it ran. Each mutant below is a real rule file, copied and then
// broken in one of the four ways a glob can silently stop enforcing something, and each must turn
// the proof RED with a message that names the rule and what it lost. The same rules, copied and NOT
// broken, must be green — a baseline that is red would make every mutant meaningless.
import { afterAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { cpSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { proveCoverage, type CoverageReport } from "../src/lib/coverage.ts";
import { layout } from "../src/lib/repo.ts";
import { readGradleModules } from "../src/lib/sources.ts";
import { EXCLUSIONS, ROUTED_CONFIG } from "../src/commands/rules.ts";

// Every case runs the whole-tree proof (ast-grep over every source root), about 1.7 s each on an
// idle box. Bun's 5 s default failed two of them at load average 82 on 2026-09-22 while they
// passed in 25.7 s under a longer limit: a budget for the work, not a hang detector.
setDefaultTimeout(60_000);

const { repoRoot, buildRoot } = layout();
const workspaces: string[] = [];

afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

/** The rule directories sgconfig.yml routes, named ONCE. The mutant copies and the baseline's
 *  denominator both read this, so a third rule dir added to the config cannot land while the
 *  copies and the count quietly go on describing the old two. */
const RULE_DIRS = ["console", "kotlin"] as const;

/** The rule total counted from the FILESYSTEM, never retyped. A hand-kept total is the same defect
 *  mutant (b) below was written against: `62` went stale the moment a rule landed correctly
 *  (6b6ad77a added the 63rd) and reddened the gate for the change rather than for a fault.
 *  Counting the files cross-checks the prover against a source it does NOT reach through
 *  sgconfig.yml, so a ruleDir dropped from that config still fails this arm. */
function rulesOnDisk(): number {
  return RULE_DIRS.reduce(
    (total, dir) =>
      total + readdirSync(join(repoRoot, "quality", "rules", dir)).filter((f) => f.endsWith(".yml")).length,
    0,
  );
}

/** A copy of the REAL rule set and the REAL exclusion table, which a mutant then edits. */
function copyOfTheRealRules(): { sgconfig: string; exclusions: string; rule(id: string): string } {
  const dir = mkdtempSync(join(tmpdir(), "gate-coverage-mutant-"));
  workspaces.push(dir);
  for (const name of RULE_DIRS) {
    cpSync(join(repoRoot, "quality", "rules", name), join(dir, name), { recursive: true });
  }
  writeFileSync(join(dir, "sgconfig.yml"), `ruleDirs:\n${RULE_DIRS.map((d) => `  - ${d}\n`).join("")}`);
  // A distinct filename per copy: the TOML reader imports by path, and Bun caches modules by path.
  const exclusions = join(dir, "exclusions.toml");
  cpSync(join(repoRoot, EXCLUSIONS), exclusions);
  return {
    sgconfig: join(dir, "sgconfig.yml"),
    exclusions,
    rule: (id: string) => join(dir, "kotlin", `${id}.yml`),
  };
}

// The deterministic stale mutant: its files: glob spans every Kotlin source set, it carries no
// ignores:, and no row in the table names it — so it reaches every source root and ANY row listing
// it is stale by construction. The arm below asserts that last property against the real table
// rather than trusting it: a future row naming this rule would make the mutation a no-op and leave
// the arm silently vacuous.
const DEAD_ENTRY = "kt-l2-mirror-delegates-to-predicate";

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
    const onDisk = rulesOnDisk();
    expect(onDisk, "counting the rule files found none — a zero denominator agrees with any prover").toBeGreaterThan(0);
    expect(real.rules).toBe(onDisk);
    expect(real.crossCheckAgreed).toBe(real.crossChecked);
    expect(real.crossChecked).toBe(onDisk);

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
    // :core drops out; every other module still matches, so `ast-grep scan` stays green. The
    // "every other module" list is READ FROM THE BUILD (settings.gradle.kts), never retyped: a
    // hand-kept brace list lost :client, then :upstream, as the restructure moved them out of
    // gateway/, and the mutant then lost three roots instead of the one it claims to.
    const others = readGradleModules(repoRoot, buildRoot)
      .filter((m) => m.id !== "core")
      .map((m) => m.dir);
    edit(copy.rule("kt-no-lateinit"), '"**/src/main/**/*.kt"', `"{${others.join(",")}}/src/main/**/*.kt"`);
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const lost = messagesFor(report, "kt-no-lateinit", "source-root-lost");
    expect(lost).toHaveLength(1);
    // the file count is the tree's, not the test's: it moved 135 -> 103 when :client left :core
    expect(lost[0]).toMatch(/kt-no-lateinit reaches 0 of \d+ files in core\/src\/main\/kotlin/);
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
    // kt-no-vanilla-config-dir carries a files-row spanning all of :daemon-control's main sources. If its
    // glob for :daemon-control disappears, that row must not absorb the loss — losing a module is the
    // failure the proof exists for, and a broad file exemption is the obvious way to hide it.
    const copy = copyOfTheRealRules();
    edit(copy.rule("kt-no-vanilla-config-dir"), "  - daemon/control/src/main/kotlin/splice/control/Launch*.kt\n", "");
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const lost = messagesFor(report, "kt-no-vanilla-config-dir", "source-root-lost");
    expect(lost).toHaveLength(1);
    expect(lost[0]).toContain("daemon/control/src/main/kotlin");
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
    expect(lost[0]).toContain("app/src/main/kotlin");
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

  // The same retirement check, on the row shape that actually hides things. `used` was keyed on the
  // ROW, so a row listing several rules stayed "used" the moment ONE of them excused a real loss —
  // and the arm below it only ever mutates a SINGLE-rule row. A dead entry sitting beside a live
  // sibling was therefore the one shape never tested, and 70 of this table's rule entries live in
  // multi-rule rows. Measured 2026-09-21: six rules were widened to reach a newly extracted module,
  // the row excusing them went stale in six places, and this proof stayed green.
  test("a dead entry beside a LIVE sibling is still a finding", async () => {
    const real = await prove(join(repoRoot, ROUTED_CONFIG), join(repoRoot, EXCLUSIONS));
    expect(real.ok, "the real table must be green, or this arm cannot attribute its own finding").toBe(true);
    expect(
      readFileSync(join(repoRoot, EXCLUSIONS), "utf8"),
      `${DEAD_ENTRY} must be named by NO row, or injecting it proves nothing`,
    ).not.toContain(DEAD_ENTRY);

    const copy = copyOfTheRealRules();
    const before = readFileSync(copy.exclusions, "utf8");
    const anchor = "rules = [\n";
    expect(before, "the table must still carry a multi-rule row for this arm to mutate").toContain(anchor);
    writeFileSync(copy.exclusions, before.replace(anchor, `${anchor}  "${DEAD_ENTRY}",\n`));

    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const stale = report.findings.filter((f) => f.kind === "exclusion-stale");
    // EXACTLY the injected entry and nothing else: the siblings it hides behind are untouched and
    // still excuse real losses, which is precisely the condition under which a row-grained key
    // reported nothing. Asserted as a SET rather than a list because usage moved to the atom: the
    // injected rule is reported once per scope cell the row spells for it, so a row with two
    // sourceSets yields two findings naming the same rule. The claim is unchanged — this entry is
    // dead, its siblings are not — only the grain it is counted at.
    expect(stale.length).toBeGreaterThan(0);
    expect(new Set(stale.map((f) => f.rule))).toEqual(new Set([DEAD_ENTRY]));
    expect(stale[0]?.message).toContain("excuses nothing");
  });

  // The same retirement check one dimension further in, and the reason the (row, rule) key was not
  // enough either. A row names up to FOURTEEN modules; crediting usage to the rule let a single live
  // module vouch for every dead one beside it.
  test("a stale MODULE beside a live module in the same row is still a finding", async () => {
    const real = await prove(join(repoRoot, ROUTED_CONFIG), join(repoRoot, EXCLUSIONS));
    expect(real.ok, "the real table must be green, or this arm cannot attribute its own finding").toBe(true);

    const copy = copyOfTheRealRules();
    // :app is INSIDE kt-json-scalars-single-source's files: list, so excluding :app excuses nothing —
    // the retirement case, not a typo. The row's six other modules keep excusing real losses, which
    // is exactly the masking condition.
    edit(
      copy.exclusions,
      'rule = "kt-json-scalars-single-source"\nmodules = [\n  "integrations-claude-code",',
      'rule = "kt-json-scalars-single-source"\nmodules = [\n  "app", "integrations-claude-code",',
    );
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const stale = report.findings.filter((f) => f.kind === "exclusion-stale");
    // ONE finding. The live siblings are not reported, and their silence is proven by the count
    // rather than described: seven module cells, one dead, one finding.
    expect(stale).toHaveLength(1);
    expect(stale[0]!.rule).toBe("kt-json-scalars-single-source");
    expect(stale[0]!.message).toContain("module app");
    expect(stale[0]!.message).toContain("excuses nothing");
  });

  // A live file glob must not mask a stale sibling in the same scope.
  test("a dead files GLOB beside a live glob in the same row is still a finding", async () => {
    const copy = copyOfTheRealRules();
    edit(
      copy.exclusions,
      'rule = "kt-error-envelope-single-source"\nfiles = ["core/src/main/kotlin/splice/core/wire/ErrorEnvelope.kt"]',
      'rule = "kt-error-envelope-single-source"\nfiles = ["core/src/main/kotlin/splice/core/wire/ErrorEnvelope.kt", "core/src/main/kotlin/splice/core/wire/NoSuchFile.kt"]',
    );
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    const stale = report.findings.filter((f) => f.kind === "exclusion-stale");
    expect(stale).toHaveLength(1);
    expect(stale[0]!.rule).toBe("kt-error-envelope-single-source");
    expect(stale[0]!.message).toContain("files glob core/src/main/kotlin/splice/core/wire/NoSuchFile.kt");
  });

  for (const scope of ['modules = ["app"]', 'sourceSets = ["test"]']) {
    test(`a file exclusion cannot waive a file outside its ${scope}`, async () => {
      const copy = copyOfTheRealRules();
      const anchor = 'files = ["core/src/main/kotlin/splice/core/wire/ErrorEnvelope.kt"]';
      edit(copy.exclusions, anchor, `${anchor}\n${scope}`);
      const report = await prove(copy.sgconfig, copy.exclusions);
      expect(report.ok).toBe(false);
      const lost = messagesFor(report, "kt-error-envelope-single-source", "file-lost");
      expect(lost).toHaveLength(1);
      expect(lost[0]).toContain("core/src/main/kotlin/splice/core/wire/ErrorEnvelope.kt");
      expect(messagesFor(report, "kt-error-envelope-single-source", "exclusion-stale")).toHaveLength(1);
    });
  }

  test("a file exclusion credits its matching module and source-set cell", async () => {
    const copy = copyOfTheRealRules();
    const anchor = 'files = ["core/src/main/kotlin/splice/core/wire/ErrorEnvelope.kt"]';
    edit(copy.exclusions, anchor, `${anchor}\nmodules = ["core"]\nsourceSets = ["main"]`);
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.findings).toEqual([]);
    expect(report.ok).toBe(true);
  });

  test("a stale exclusion is a finding, not a default", async () => {
    const copy = copyOfTheRealRules();
    edit(
      copy.exclusions,
      '[[exclusion]]\nrule = "kt-no-println"\nmodules = ["app"]',
      '[[exclusion]]\nrule = "kt-no-println"\nmodules = ["providers-openai"]',
    );
    const report = await prove(copy.sgconfig, copy.exclusions);
    expect(report.ok).toBe(false);
    expect(report.findings.some((f) => f.kind === "exclusion-stale" && f.message.includes("kt-no-println"))).toBe(true);
    expect(report.findings.some((f) => f.kind === "source-root-lost" && f.message.includes("app/src/main/kotlin"))).toBe(true);
  });
});
