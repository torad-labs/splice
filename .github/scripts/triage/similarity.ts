#!/usr/bin/env bun
// NEW: ports find-duplicates.sh's scoring off python3 (the no-python wall). Ranks open issues against a
// target by the SAME measure the script used: the ratio of difflib.SequenceMatcher over the normalized
// title + first 500 characters of body. The matcher below is difflib's algorithm line for line
// (autojunk included: Python's default, active once the second string reaches 200 characters, which every
// real issue body does), counted in code points as Python counts them.
//
//   bun similarity.ts <combined.json> <threshold> <top_k>     (combined = {target, candidates})
//
// Prints a JSON array of {number, title, score} on stdout, highest score first, ties in input order.
import { readFileSync } from "node:fs";

const AUTOJUNK_MIN = 200;

/** difflib.SequenceMatcher(None, a, b).ratio() over code points. */
export function ratio(left: string, right: string): number {
  const a = Array.from(left);
  const b = Array.from(right);
  const total = a.length + b.length;
  if (total === 0) return 1;
  return (2 * matched(a, b)) / total;
}

function indexB(b: readonly string[]): Map<string, number[]> {
  const b2j = new Map<string, number[]>();
  b.forEach((elt, j) => {
    const at = b2j.get(elt);
    if (at) at.push(j);
    else b2j.set(elt, [j]);
  });
  if (b.length >= AUTOJUNK_MIN) {
    const limit = Math.floor(b.length / 100) + 1;
    for (const [elt, at] of [...b2j]) if (at.length > limit) b2j.delete(elt);
  }
  return b2j;
}

type Span = readonly [alo: number, ahi: number, blo: number, bhi: number];

function matched(a: readonly string[], b: readonly string[]): number {
  const b2j = indexB(b);
  let sum = 0;
  const queue: Span[] = [[0, a.length, 0, b.length]];
  for (let span = queue.pop(); span; span = queue.pop()) {
    const [alo, ahi, blo, bhi] = span;
    const [i, j, k] = longest(a, b, b2j, alo, ahi, blo, bhi);
    if (k === 0) continue;
    sum += k;
    if (alo < i && blo < j) queue.push([alo, i, blo, j]);
    if (i + k < ahi && j + k < bhi) queue.push([i + k, ahi, j + k, bhi]);
  }
  return sum;
}

function longest(
  a: readonly string[],
  b: readonly string[],
  b2j: Map<string, number[]>,
  alo: number,
  ahi: number,
  blo: number,
  bhi: number,
): readonly [number, number, number] {
  let besti = alo;
  let bestj = blo;
  let bestsize = 0;
  let j2len = new Map<number, number>();
  for (let i = alo; i < ahi; i++) {
    const next = new Map<number, number>();
    for (const j of b2j.get(a[i]!) ?? []) {
      if (j < blo) continue;
      if (j >= bhi) break;
      const k = (j2len.get(j - 1) ?? 0) + 1;
      next.set(j, k);
      if (k > bestsize) {
        besti = i - k + 1;
        bestj = j - k + 1;
        bestsize = k;
      }
    }
    j2len = next;
  }
  // Extend through elements the popular-element prune dropped (no isjunk here, so nothing else is junk).
  while (besti > alo && bestj > blo && a[besti - 1] === b[bestj - 1]) {
    besti--;
    bestj--;
    bestsize++;
  }
  while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] === b[bestj + bestsize]) bestsize++;
  return [besti, bestj, bestsize];
}

// Python's str.split() whitespace, not JavaScript's \s: it adds \x1c-\x1f and \x85, and drops the byte-order mark.
const PY_SPACE = new RegExp("[\t\n\v\f\r\x1c-\x1f \x85\xa0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+");

export function normalize(text: unknown): string {
  if (text === null || text === undefined) return "";
  const head = Array.from(String(text)).slice(0, 500).join("").toLowerCase();
  return head.split(PY_SPACE).filter(Boolean).join(" ");
}

export function score(aTitle: unknown, aBody: unknown, bTitle: unknown, bBody: unknown): number {
  const a = `${normalize(aTitle)} ${normalize(aBody)}`;
  const b = `${normalize(bTitle)} ${normalize(bBody)}`;
  return a && b ? ratio(a, b) : 0;
}

/** Python's round(x, 3): the DOUBLE'S exact decimal value rounded to three places, half to even on an exact tie.
 *  Scaling by 1000 first is not that: 0.8075 is a double a hair under the tie, and `x * 1000` lands exactly on 807.5. */
export function round3(x: number): number {
  const [whole = "0", fraction = ""] = x.toFixed(40).split(".");
  const kept = fraction.slice(0, 3);
  const rest = fraction.slice(3);
  const half = "5" + "0".repeat(rest.length - 1);
  const up = rest > half || (rest === half && Number(kept.slice(-1)) % 2 === 1);
  const scaled = BigInt(whole + kept) + (up ? 1n : 0n);
  return Number(scaled) / 1000;
}

type Issue = { number?: number; title?: string; body?: string | null };

export function rank(target: Issue, candidates: readonly Issue[], threshold: number, topK: number) {
  const scored = candidates
    .filter((c) => c.number !== target.number)
    .map((c) => ({ number: c.number, title: c.title, raw: score(target.title, target.body, c.title, c.body) }))
    .filter((c) => c.raw >= threshold);
  // Order on the raw score and round only for display: two candidates that both show 0.806 keep their real order.
  scored.sort((x, y) => y.raw - x.raw);
  return scored.slice(0, topK).map((c) => ({ number: c.number, title: c.title, score: round3(c.raw) }));
}

if (import.meta.main) {
  const [file, threshold, topK] = process.argv.slice(2);
  const data = JSON.parse(readFileSync(file!, "utf8")) as { target: Issue; candidates: Issue[] };
  console.log(JSON.stringify(rank(data.target, data.candidates, Number(threshold), Number(topK))));
}
