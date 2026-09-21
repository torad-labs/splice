// The ONE definition of "an ast-grep rule document", derived from a real YAML parse instead of a
// line grep. (checks/config/ast-grep-rule-docs.ts until PR 5; the routing and config-guard legs
// both read it, so the two cannot disagree about what a rule is — and, since DR-131/DR-132, they
// agree with ast-grep rather than only with each other.)
//
// Four shapes were proven to load in ast-grep 0.45.0 and run NON-BLOCKING (`ast-grep scan` exits 0
// on a match; the same rule with a real top-level `severity: error` exits 1) while the old grep
// walls said PASS: severity nested under `metadata:`; severity inside a `note:` block scalar; a
// multi-doc file whose doc 2 has no severity; and flow style (`{id: x, language: kotlin, rule:
// {pattern: p()}}`), which has no line-leading `id:` at all, so BOTH walls skipped the file and the
// routing leg went fail-OPEN on a dormant directory holding one. Structure, not indentation, is the
// denominator.
//
// Bun.YAML.parse returns a DIFFERENT SHAPE per document count — a single mapping is an object, a
// stream of two is an array — and a single SEQUENCE-root document also parses to an array. The
// `Array.isArray(v) ? v : [v]` below is correct for THIS corpus specifically (measured: of the
// tracked rule YAML files, zero are multi-document and zero have a sequence root; ast-grep would
// reject a sequence-root rule file anyway). Do not copy it to a corpus where that is untrue.
import { readFileSync, readdirSync, statSync } from "node:fs";
import { relative, resolve } from "node:path";

const RULE_SUFFIXES = [".yml", ".yaml"];

export type Doc = Record<string, unknown>;

/** [index, mapping] for every YAML document in path. Throws on unparseable input — a rule file
 *  ast-grep cannot read is a hard failure, never a silent skip. */
export function documents(path: string): [number, Doc][] {
  const parsed = Bun.YAML.parse(readFileSync(path, "utf8")) as unknown;
  const list: unknown[] = Array.isArray(parsed) ? parsed : [parsed];
  const out: [number, Doc][] = [];
  list.forEach((doc, i) => {
    if (typeof doc === "object" && doc !== null && !Array.isArray(doc)) out.push([i, doc as Doc]);
  });
  return out;
}

/** A rule-TEST fixture: cases without a matcher — `valid:`/`invalid:` present and no `rule:` —
 *  identified structurally, so the exemption cannot be claimed by dropping a real rule into a
 *  directory named rule-tests. */
export const isFixture = (doc: Doc): boolean => ("valid" in doc || "invalid" in doc) && !("rule" in doc);

/** Carries a top-level id — a rule or a fixture. The parser-derived twin of `grep '^ *id:'`. */
export const isAstGrepDoc = (doc: Doc): boolean => "id" in doc;

/** Every regular file under root, depth-bounded like `find -maxdepth`, sorted. */
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

/** Files under root (bounded by maxdepth) holding at least one ast-grep document. An UNPARSEABLE
 *  .yml counts: fail-closed — an unreadable rule file is not "no rules here". */
export function ruleFiles(root: string, maxdepth: number): string[] {
  const base = resolve(root);
  const found: string[] = [];
  for (const path of walk(base, maxdepth)) {
    if (!RULE_SUFFIXES.some((s) => path.endsWith(s))) continue;
    if (path.slice(base.length + 1).split("/").length > maxdepth) continue;
    try {
      if (documents(path).some(([, doc]) => isAstGrepDoc(doc))) found.push(path);
    } catch {
      found.push(path);
    }
  }
  return found;
}

/** Every rule document under <root>/quality/rules that is not a blocking error, by name. The two
 *  phrases are load-bearing: the test arms pin them so a wall that fails for the WRONG reason
 *  cannot be mistaken for one that works. */
export function severityViolations(root: string): string[] {
  const violations: string[] = [];
  const rulesDir = resolve(root, "quality/rules");
  for (const path of walk(rulesDir, Number.MAX_SAFE_INTEGER)) {
    if (!RULE_SUFFIXES.some((s) => path.endsWith(s))) continue;
    const r = relative(resolve(root), path);
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
      if (severity === undefined || severity === null) {
        violations.push(
          `${r} document ${index} (id: ${String(doc["id"])}) declares no top-level severity, ` +
            "so ast-grep loads it and runs non-blocking (scan exits 0 on a match)",
        );
      } else if (severity !== "error") {
        violations.push(
          `${r} document ${index} (id: ${String(doc["id"])}) has a non-error severity ` +
            `('${String(severity)}') — every rule document must be 'severity: error'`,
        );
      }
    }
  }
  return violations;
}
