// Mutation-proves src/lib/landed.ts: the pure core, driven without a repository. Each arm plants
// one shape a landing claim can take and asserts the disposition it must get.
import { describe, expect, test } from "bun:test";
import { audit } from "../src/lib/landed.ts";

const row = (id: string, status: string, files: string, blobs: string): string =>
  `[[items]]\nid = "${id}"\nstatus = "${status}"\n# [d] RECEIPT files=${files} blobs=${blobs}\n`;
const reach = (...ids: string[]) => new Set(ids);

describe("gate ledger landed: the audit", () => {
  test("clean: a landed row whose bytes are in the history", () => {
    const r = audit(row("A", "done", "a.ts", "aaa"), reach("aaa"));
    expect([r.rows, r.files, r.unlanded.length]).toEqual([1, 1, 0]);
  });

  // THE BORING CASE, and the one that actually happened. The file exists, reads correctly, passes
  // every other leg, and git has never seen it. A check that only notices MISSING files waves it through.
  test("unlanded: a landed row whose bytes were never committed", () => {
    const r = audit(row("B", "done", "b.ts", "bbb"), reach("zzz"));
    expect(r.unlanded).toEqual([{ id: "B", file: "b.ts", blob: "bbb" }]);
  });

  // The second half: the PATH is committed, at bytes that are not the ones verified. Tracked-ness
  // alone calls this green.
  test("unlanded: committed at bytes other than the receipted ones", () => {
    const r = audit(row("C", "done", "c.ts", "ccc"), reach("ccc-other", "tree"));
    expect(r.unlanded.map((e) => e.blob)).toEqual(["ccc"]);
  });

  test("ignored: a receipted file the campaign deliberately keeps out of history is counted, not lost", () => {
    const r = audit(row("D", "done", "x.png", "ddd"), reach(), (f) => f.endsWith(".png"));
    expect([r.unlanded.length, r.ignored.length]).toEqual([0, 1]);
  });

  test("skipped: an in_flight row claims nothing yet and is not checked", () => {
    const r = audit(row("E", "in_flight", "e.ts", "eee"), reach());
    expect([r.rows, r.unlanded.length]).toEqual([0, 0]);
  });

  test("noReceipt: a landed row with no receipt at all", () => {
    const r = audit('[[items]]\nid = "F"\nstatus = "done"\n', reach());
    expect([r.noReceipt, r.rows]).toEqual([["F"], 0]);
  });

  // An empty ledger must not read as a pass. The command enforces it via the row count, so the
  // count has to be honest here: zero rows is zero rows, never an implicit all-clear.
  test("empty: an empty ledger reads as zero rows, not as a clean bill", () => {
    const r = audit("", reach());
    expect([r.rows, r.files, r.unlanded.length]).toEqual([0, 0, 0]);
  });

  // A re-receipt supersedes: M1-37 was re-receipted onto one file after its first named two.
  test("supersede: the last receipt is the live claim, not the union of all of them", () => {
    const r = audit(row("G", "done", "g.ts,stale.txt", "ggg,sss") + "# [d] RECEIPT files=g.ts blobs=ggg\n", reach("ggg"));
    expect([r.files, r.unlanded.length]).toEqual([1, 0]);
  });

  // Two rows sharing a fence: the finished one rides in the live one's commit, so its OWN bytes
  // never existed. Calling that NOT IN HISTORY prescribes a re-receipt that cannot terminate while
  // the sharing row keeps writing. H's h.ts is in history at I's bytes, not at H's.
  test("superseded: a file landed under another row's receipt, not under this one", () => {
    const r = audit(row("H", "done", "h.ts", "hhh") + row("I", "done", "h.ts", "iii"), reach("iii"));
    expect(r.unlanded).toEqual([]);
    expect(r.superseded).toEqual([{ id: "H", file: "h.ts", blob: "hhh", by: "I" }]);
  });

  // And the boring half of it: superseding needs ANOTHER row. A row cannot vouch for itself, or
  // every unlanded file in a multi-file receipt would excuse every other one.
  test("no-self-vouch: a row whose only claim on a file is its own stays unlanded", () => {
    const r = audit(row("J", "done", "j.ts,j.ts", "jjj,jjj"), reach());
    expect([r.superseded.length, r.unlanded.length]).toEqual([0, 2]);
  });

  // THE FALSE GREEN THIS CHECK ALMOST SHIPPED. K committed k.ts long ago; L receipts it now at
  // bytes never committed. K's blob is reachable and proves nothing about L, because it predates
  // it. Only a LATER row supersedes — measured against the live ledger, the first cut of the rule
  // excused two rows exactly this way.
  test("earlier-no-vouch: bytes proved by an EARLIER row do not attest a later row", () => {
    const r = audit(row("K", "done", "k.ts", "kkk") + row("L", "done", "k.ts", "lll"), reach("kkk"));
    expect(r.superseded).toEqual([]);
    expect(r.unlanded.map((e) => e.id)).toEqual(["L"]);
  });
});
