#!/usr/bin/env bun
/** checks/config/ast-grep-rule-docs.ts — the ONE definition of "an ast-grep rule document",
 *  derived from a real YAML parse instead of a line grep.
 *
 *  DR-131/DR-132. Both rule walls used to answer "is this a rule file?" and "does this doc carry
 *  severity: error?" with `grep -E '^[[:space:]]*id:'` and a matching count of `severity: error`
 *  lines. rule-routing.sh:38 said so out loud — "the SAME test checks/config-guard.sh uses ... so the
 *  two legs cannot disagree about what a rule is". They could not disagree with each OTHER. They
 *  could, and did, disagree with ast-grep, and a hand-authored pair of lists agreeing with each other
 *  is not a check against reality (the completeness law).
 *
 *  Four shapes were proven to load in ast-grep 0.45.0 and run NON-BLOCKING (`ast-grep scan` exits 0 on
 *  a match; the same rule with a real top-level `severity: error` exits 1) while both walls said PASS:
 *
 *    A  severity: error nested under `metadata:`      — counted by the grep, invisible to ast-grep
 *    B  severity: error inside a `note:` block scalar — 15 .rules files already use block scalars
 *    C  multi-doc: doc 1 carries a decoy in `note:`, doc 2 has no severity at all
 *    D  `{id: x, language: kotlin, rule: {pattern: p()}}` — flow style, zero line-leading `id:` keys,
 *       so BOTH walls skipped the file entirely: no severity check, and rule-routing.sh's forward
 *       direction went fail-OPEN on a dormant directory holding one
 *
 *  A real parser sees all four. Structure, not indentation, is the denominator.
 *
 *  Subcommands:
 *    severity <root>          every rule document under <root>/.rules carries a top-level
 *                             `severity: error`; exits 1 naming each document that does not
 *    count <dir> <maxdepth>   number of files under <dir> holding at least one ast-grep document
 *                             (a doc with a top-level `id`) — the parser-derived replacement for
 *                             rule-routing.sh's `rule_file_count`
 *
 *  V4-145: converted to TypeScript (bun). PyYAML is replaced by Bun.YAML, which ships in Bun 1.4.0 —
 *  no dependency added. THE ONE THING THAT NEEDED A MEASUREMENT RATHER THAN A TRANSLATION is
 *  `yaml.safe_load_all`: it yields every document, while Bun.YAML.parse returns a DIFFERENT SHAPE per
 *  document count, and a single SEQUENCE-root document parses to an array — indistinguishable from a
 *  two-document stream. The orchestrator measured it: single mapping -> object, single sequence ->
 *  array, two docs -> array of docs, leading `---` -> still one object. `Array.isArray(v) ? v : [v]`
 *  is therefore the correct port HERE, and correct for this corpus specifically: of 166 tracked .rules
 *  YAML files, ZERO are multi-document and ZERO have a sequence root. A sequence-root rule file would
 *  silently become N documents; ast-grep would reject such a file anyway, which is why the ternary is
 *  safe rather than merely lucky. Do not copy it to a corpus where that is untrue.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { resolve } from "node:path";

const RULE_SUFFIXES = [".yml", ".yaml"];

/** The module docstring, which the usage path prints to stderr. */
const DOC = `checks/config/ast-grep-rule-docs.ts — the ONE definition of "an ast-grep rule document",
derived from a real YAML parse instead of a line grep.`;

type Doc = Record<string, unknown>;

/** Python repr() of a scalar, for the messages that quote a severity back. */
function pyRepr(v: unknown): string {
  if (typeof v === "string") return `'${v.replaceAll("\\", "\\\\").replaceAll("'", "\\'")}'`;
  if (v === null || v === undefined) return "None";
  return String(v);
}

/** Python str() of a scalar, for the `(id: X)` fragment. */
function pyStr(v: unknown): string {
  if (v === null || v === undefined) return "None";
  if (v === true) return "True";
  if (v === false) return "False";
  return String(v);
}

/** [index, mapping] for every YAML document in path. Throws on unparseable input —
 *  a rule file ast-grep cannot read is a hard failure, never a silent skip. */
export function documents(path: string): [number, Doc][] {
  const parsed = Bun.YAML.parse(readFileSync(path, "utf8")) as unknown;
  // See the header: Bun.YAML.parse is shape-per-document, so a single document must be wrapped.
  const list: unknown[] = Array.isArray(parsed) ? parsed : [parsed];
  const out: [number, Doc][] = [];
  list.forEach((doc, i) => {
    if (typeof doc === "object" && doc !== null && !Array.isArray(doc)) {
      out.push([i, doc as Doc]);
    }
  });
  return out;
}

/** A rule-TEST fixture: cases without a matcher. Identified structurally, exactly as the
 *  shell wall did — `valid:`/`invalid:` present and no `rule:` — so the exemption still cannot
 *  be claimed by dropping a real rule into a directory named rule-tests. */
export function isFixture(doc: Doc): boolean {
  return ("valid" in doc || "invalid" in doc) && !("rule" in doc);
}

/** Carries a top-level id — a rule or a fixture. The parser-derived twin of `grep '^ *id:'`. */
export function isAstGrepDoc(doc: Doc): boolean {
  return "id" in doc;
}

/** Walk root and return every regular file, depth-bounded, mirroring find -maxdepth. */
function walk(root: string, maxdepth: number): string[] {
  const found: string[] = [];
  const visit = (dir: string, depth: number): void => {
    let entries: string[];
    try {
      entries = readdirSync(dir).sort();
    } catch {
      return;
    }
    for (const name of entries) {
      const p = resolve(dir, name);
      let st;
      try {
        st = statSync(p);
      } catch {
        continue;
      }
      if (st.isDirectory()) {
        if (depth < maxdepth) visit(p, depth + 1);
      } else if (st.isFile()) {
        found.push(p);
      }
    }
  };
  visit(root, 1);
  return found.sort();
}

function depthUnder(root: string, p: string): number {
  return p.slice(root.length + 1).split("/").length;
}

/** Files under root (bounded by maxdepth) holding >=1 ast-grep doc. */
export function ruleFiles(root: string, maxdepth: number): string[] {
  const base = resolve(root);
  const found: string[] = [];
  for (const path of walk(base, maxdepth)) {
    if (!RULE_SUFFIXES.some((s) => path.endsWith(s))) continue;
    if (depthUnder(base, path) > maxdepth) continue;
    try {
      if (documents(path).some(([, doc]) => isAstGrepDoc(doc))) {
        found.push(path);
      }
    } catch {
      // Unparseable: count it. Fail-closed — an unreadable rule file is not "no rules here".
      found.push(path);
    }
  }
  return found;
}

function rel(root: string, p: string): string {
  const base = resolve(root);
  return p.startsWith(base + "/") ? p.slice(base.length + 1) : p;
}

export function checkSeverity(root: string): string[] {
  const violations: string[] = [];
  const rulesDir = resolve(root, ".rules");
  let paths: string[];
  try {
    paths = walk(rulesDir, Number.MAX_SAFE_INTEGER);
  } catch {
    paths = [];
  }
  for (const path of paths.sort()) {
    if (!RULE_SUFFIXES.some((s) => path.endsWith(s))) continue;
    const r = rel(root, path);
    let docs: [number, Doc][];
    try {
      docs = documents(path);
    } catch (exc) {
      violations.push(`${r} is not parseable YAML (${(exc as Error).name}) — ast-grep cannot load it`);
      continue;
    }
    for (const [index, doc] of docs) {
      if (!isAstGrepDoc(doc) || isFixture(doc)) continue;
      const severity = doc["severity"];
      // Wording is load-bearing: config-guard-selftest.sh pins these two phrases so a wall
      // that fails for the WRONG reason cannot be mistaken for one that works.
      if (severity === undefined || severity === null) {
        violations.push(
          `${r} document ${index} (id: ${pyStr(doc["id"])}) declares no top-level severity, ` +
            "so ast-grep loads it and runs non-blocking (scan exits 0 on a match)",
        );
      } else if (severity !== "error") {
        violations.push(
          `${r} document ${index} (id: ${pyStr(doc["id"])}) has a non-error severity ` +
            `(${pyRepr(severity)}) — every rule document must be 'severity: error'`,
        );
      }
    }
  }
  return violations;
}

export function main(argv: string[]): number {
  if (argv.length >= 3 && argv[1] === "severity") {
    const violations = checkSeverity(argv[2]);
    for (const v of violations) process.stdout.write(`  ✗ ${v}\n`);
    return violations.length > 0 ? 1 : 0;
  }
  if (argv.length >= 4 && argv[1] === "count") {
    process.stdout.write(`${ruleFiles(argv[2], Number(argv[3])).length}\n`);
    return 0;
  }
  // Python prints __doc__ (the module docstring) to stderr on a usage error and returns 2.
  process.stderr.write(DOC + "\n");
  return 2;
}

if (import.meta.main) {
  // Python's sys.argv[0] is the script, so slice(1) puts the first ARGUMENT at index 1 —
  // process.argv[0] is the bun binary and would otherwise shift every subcommand by one.
  process.exit(main(process.argv.slice(1)));
}
