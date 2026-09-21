// `gate ledger landed|laws` — what a campaign ledger CLAIMS against what the history and the tree
// HOLD. (.dev/web-console/{landed,law-check}.mjs until PR 5; console/tools' exit gate runs both.)
//
//   ledger landed [<ledger.toml>]                every done/verified row's receipted bytes are
//                                                reachable from HEAD (src/lib/landed.ts)
//   ledger laws [--ledger <p>] [--report] [--json]   laws 25 and 27 over every row, read through the
//                                                ledger CLI (src/lib/laws.ts). --report prints and
//                                                ALWAYS exits 0: whether the laws block a milestone
//                                                is the orchestrator's call, so the exit gate
//                                                reports and does not gate.
// Both refuse an empty row set: a check that reads nothing and reports clean is the joke version
// of itself. Exit 2 is DID NOT RUN (laws), 1 is a finding.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { audit, isIgnored, reachableFromHead } from "../lib/landed.ts";
import { BUILD_LEG, check, readLedger } from "../lib/laws.ts";
import { layout } from "../lib/repo.ts";

export const usage =
  "ledger landed [<ledger.toml>]        every done/verified row's receipted bytes are reachable from HEAD\n" +
  "  ledger laws [--ledger <p>] [--report] [--json]   laws 25 and 27 over every row, through the ledger CLI";

/** The campaign whose ledger both checks read unless told otherwise. */
export const DEFAULT_LEDGER = ".dev/campaigns/web-console.toml";

export function ledger(argv: readonly string[]): number {
  const [sub, ...rest] = argv;
  if (sub === "landed") return landed(rest);
  if (sub === "laws") return laws(rest);
  console.error(`gate ledger: expected landed or laws${sub ? `, got ${sub}` : ""}`);
  return 2;
}

function landed(argv: readonly string[]): number {
  const positional = argv.filter((a) => !a.startsWith("--"));
  const flags = argv.filter((a) => a.startsWith("--"));
  if (positional.length > 1 || flags.length > 0) {
    console.error("gate ledger landed: takes at most one argument, the ledger path");
    return 2;
  }
  const { repoRoot } = layout();
  const ledgerPath = positional[0] ?? DEFAULT_LEDGER;
  const text = readFileSync(resolve(repoRoot, ledgerPath), "utf8");
  const reachable = reachableFromHead(repoRoot);
  const r = audit(text, reachable, (file) => isIgnored(repoRoot, file));

  console.log(`landed: ${r.rows} row(s) read from ${ledgerPath}, ${r.files} receipted file(s) checked against ${reachable.size} object(s) reachable from HEAD`);
  for (const e of r.ignored) console.log(`  ignored-by-design  ${e.id.padEnd(8)} ${e.file}`);
  if (r.ignored.length) console.log(`  ${r.ignored.length} receipted file(s) are gitignored — regenerable bytes, named here so the exemption cannot grow in silence`);
  for (const id of r.noReceipt) console.log(`  NO RECEIPT         ${id} — status claims a landing and nothing proves what landed`);
  for (const e of r.superseded) console.log(`  landed-under-${e.by.padEnd(7)} ${e.id.padEnd(8)} ${e.file} — this row's own bytes (${e.blob.slice(0, 8)}) never existed in history`);
  if (r.superseded.length) console.log(`  ${r.superseded.length} file(s) attested TRANSITIVELY: the path is in history at bytes another row proved, which is weaker than the row's own receipt`);
  for (const e of r.unlanded) console.log(`  NOT IN HISTORY     ${e.id.padEnd(8)} ${e.file} (receipted ${e.blob.slice(0, 8)})`);

  if (r.rows === 0) {
    console.error("landed: no landed rows read — an empty row set is not a pass");
    return 1;
  }
  if (r.unlanded.length || r.noReceipt.length) {
    console.error(`\nlanded: ${r.unlanded.length + r.noReceipt.length} row-file(s) claim a landing that is not in the history.`);
    console.error("REMEDY: the builder re-receipts the file at its current bytes, the orchestrator stages and commits the row.");
    return 1;
  }
  console.log("landed: every landed row is in the history");
  return 0;
}

function laws(argv: readonly string[]): number {
  let ledgerPath = DEFAULT_LEDGER;
  let report = false;
  let json = false;
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]!;
    if (arg === "--ledger" && argv[i + 1] !== undefined) ledgerPath = argv[++i]!;
    else if (arg === "--report") report = true;
    else if (arg === "--json") json = true;
    else {
      console.error(`gate ledger laws: unknown argument ${arg}`);
      return 2;
    }
  }
  const { repoRoot } = layout();
  const rows = readLedger(repoRoot, ledgerPath);
  const result = check(rows);
  console.log(`law-check: ${result.rows} row(s) read from the ledger (${ledgerPath})`);
  if (result.rows === 0) {
    console.error("DID NOT RUN: the ledger parsed to zero rows. A law check that reads nothing and reports clean is the joke version of itself.");
    return 2;
  }
  const d = result.dispositions;
  const settled = Object.values(d).reduce((a, b) => a + b, 0);
  console.log(`  dispositions: ${d.ok} ok, ${d["law-25"]} law-25 only, ${d["law-27"]} law-27 only, ${d.both} both, ${d.unreadable} unreadable, ${d.undecidable} undecidable — ${settled} of ${result.rows} settled`);
  // THE IDENTITY, ASSERTED AND NOT ASSUMED. Every row carries exactly one disposition and absence is
  // not one (§24). A row that fell through the chain would otherwise vanish from both the count and
  // the findings — the same silence the empty-ledger refusal is about, one row wide.
  if (settled !== result.rows) {
    console.error(`FAIL dispositions: ${settled} settled against ${result.rows} rows read — ${Math.abs(result.rows - settled)} row(s) carry no disposition`);
    return 2;
  }
  // BOTH READINGS OF LAW 25, printed, because they differ by 4x and the difference is a ruling the
  // orchestrator owns: the strict reading is "any fence that could hold CSS" (the row's words), and
  // the narrow one is "a fence that names a .css file itself".
  const explicit = rows.filter((row) => row.files.some((f) => f.endsWith(".css")));
  const explicitNoBuild = explicit.filter((row) => !BUILD_LEG.test(row.verify ?? ""));
  console.log(`  law 25, narrow reading (fence names a .css file): ${explicitNoBuild.length} of ${explicit.length} rows lack it${explicitNoBuild.length === 0 ? "" : ` — ${explicitNoBuild.map((r) => r.id).join(" ")}`}`);
  const done = rows.filter((row) => row.status === "done");
  console.log(`  done rows: ${done.length}, of which ${done.filter((row) => BUILD_LEG.test(row.verify ?? "")).length} carry \`npx vite build\``);
  for (const law of [25, 27] as const) {
    const list = result.findings.filter((f) => f.law === law);
    console.log(`  law ${law}: ${list.length} violation(s)${list.length === 0 ? "" : " — by name:"}`);
    for (const finding of list) console.log(`    ${finding.id}  law ${finding.law}  ${finding.detail}`);
  }
  if (json) console.log(JSON.stringify(result, null, 1));
  if (report) return 0;
  return result.findings.length === 0 ? 0 : 1;
}
