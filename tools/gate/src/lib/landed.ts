// A ROW THAT READS `done` CLAIMS A LANDING. This is the check that the landing happened.
// (.dev/web-console/landed.mjs until PR 5; `gate ledger landed` is its entry.)
//
// Found 2026-09-18 07:00 by the orchestrator, against the orchestrator: wire-check.mjs — M1-37's
// whole deliverable, the instrument that found the eight unserved routes — had read `done` since
// the previous night, carried a valid receipt, passed its verify, and existed nowhere but one
// worktree as an `??` line in `git status`. One `git clean` and the row was gone with no blob to
// recover it from. It surfaced only because `stage` refused for an unrelated reason.
//
// The gap is structural, not an oversight, and that is why it needs a check rather than a
// resolution. A receipt attests that THE BUILDER RAN THE VERIFY. Builders never commit, so no
// receipt can attest that the result was committed, and the orchestrator — the one seat whose
// signature could cover it — was the seat that dropped it.
//
// WHAT IT ASKS, and the framing took two tries. The first version compared the receipted blob to
// the INDEX and reported 257 problems, nearly all of them noise: a receipt records the bytes at
// receipt time, and every later row that legitimately edits the same file moves the index away from
// it. That check would have been abandoned within a day for crying wolf, which is how a wall dies.
//
// The honest question is REACHABILITY, not equality: were the receipted bytes ever committed at
// all? `git rev-list --objects HEAD` answers it in one pass for the whole history, and it covers
// both halves of the failure with one question — the file that was never committed (the blob does
// not exist), and the file that WAS committed at different bytes than the ones verified (the blob
// the receipt names is absent even though the path is tracked). Tracked-ness alone goes green on
// the second half, which is the more insidious one, because git is happy and the thing that shipped
// is not the thing that was proven.
//
// IGNORED FILES ARE REPORTED, NEVER SILENT. A campaign may deliberately keep regenerable capture
// bytes out of history. Those are not lost work. But "it is gitignored" is exactly the sentence that
// would hide a real artifact, so they are counted and named on every run: a growing count is
// visible, a silent exemption is not.
import { execFileSync } from "node:child_process";

export const LANDED_STATUS: ReadonlySet<string> = new Set(["done", "verified"]);

export interface RowFile {
  readonly id: string;
  readonly file: string;
  readonly blob: string;
}
export interface Superseded extends RowFile {
  /** the LATER row whose receipt put this file in history, at its bytes rather than this row's */
  readonly by: string;
}
export interface LandedReport {
  rows: number;
  files: number;
  unlanded: RowFile[];
  ignored: RowFile[];
  noReceipt: string[];
  superseded: Superseded[];
}

interface Claim {
  readonly id: string;
  readonly files: string[];
  readonly blobs: string[];
}

/** The pure core, so the tests can drive it without a repository. `reachable` is the set of object
 *  ids reachable from HEAD; `ignored` answers whether a path is deliberately kept out of history. */
export function audit(text: string, reachable: ReadonlySet<string>, ignored: (file: string) => boolean = () => false): LandedReport {
  const out: LandedReport = { rows: 0, files: 0, unlanded: [], ignored: [], noReceipt: [], superseded: [] };
  const claims: Claim[] = [];
  for (const b of text.split(/^\[\[items\]\]\s*$/m).slice(1)) {
    const id = /^id = "([^"]+)"/m.exec(b)?.[1];
    const status = /^status = "([^"]+)"/m.exec(b)?.[1];
    if (!id || !status || !LANDED_STATUS.has(status)) continue;
    const receipts = [...b.matchAll(/RECEIPT files=(\S+) blobs=(\S+)/g)];
    if (receipts.length === 0) {
      out.noReceipt.push(id);
      continue;
    }
    // The LAST receipt is the row's live claim: a re-receipt supersedes, it does not accumulate.
    const [, files, blobs] = receipts[receipts.length - 1]!;
    claims.push({ id, files: files!.split(","), blobs: blobs!.split(",") });
  }
  // A file can land under a DIFFERENT row's receipt, and then the first row's own bytes never
  // existed in history at all. That is not a missing landing: when two rows share a fence and the
  // later one is still writing, the only honest commit boundary belongs to whoever is still
  // writing, so the finished row rides in the live row's commit. Reporting those as NOT IN HISTORY
  // prescribes a re-receipt, and a re-receipt cannot terminate while the sharing row keeps
  // re-rendering — it is the wrong cure, confidently given.
  // Only a LATER row supersedes. Rows are appended in order, so a row's index is its place in
  // time, and bytes proved by an EARLIER row predate this row's work — they attest nothing about
  // it. Measured on the web-console ledger, the first cut of this rule excused M1-70 with M1-14's
  // committed comp-check.mjs and M1-63 with M1-35's coverage.mjs: a false green, produced by the
  // check built to catch false greens, because "the path is in history" was never the question.
  const landedLater = new Map<string, { at: number; id: string }[]>(); // file -> reachable claims on it
  claims.forEach((c, at) => {
    for (let i = 0; i < c.files.length; i += 1) {
      if (!reachable.has(c.blobs[i]!)) continue;
      const list = landedLater.get(c.files[i]!) ?? [];
      list.push({ at, id: c.id });
      landedLater.set(c.files[i]!, list);
    }
  });
  claims.forEach((c, at) => {
    out.rows += 1;
    for (let i = 0; i < c.files.length; i += 1) {
      out.files += 1;
      const file = c.files[i]!;
      const blob = c.blobs[i]!;
      if (reachable.has(blob)) continue;
      if (ignored(file)) {
        out.ignored.push({ id: c.id, file, blob });
        continue;
      }
      // Attested transitively, never directly: the path is in history at bytes a later row proved,
      // which is a weaker claim than this row's own receipt and the report says so by name.
      const by = (landedLater.get(file) ?? []).find((s) => s.at > at);
      if (by) {
        out.superseded.push({ id: c.id, file, blob, by: by.id });
        continue;
      }
      out.unlanded.push({ id: c.id, file, blob });
    }
  });
  return out;
}

/** Every object id reachable from HEAD, in one pass over the whole history. */
export function reachableFromHead(root: string): Set<string> {
  const out = execFileSync("git", ["rev-list", "--objects", "HEAD"], { cwd: root, maxBuffer: 1 << 28, encoding: "utf8" });
  const set = new Set<string>();
  for (const line of out.split("\n")) {
    const id = line.slice(0, 40);
    if (id) set.add(id);
  }
  return set;
}

export function isIgnored(root: string, file: string): boolean {
  try {
    execFileSync("git", ["check-ignore", "-q", "--", file], { cwd: root, stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}
