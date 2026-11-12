import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { sha256 } from "./qualification";

type Fixture = Record<string, any>;
const scalarTexts = ["é", "中", "😀", "\u0000", "\n", "\r", "\t", "a", '"', "\\", "0", "A", "F", "Ｇ", "７", "Ａ"];
const templates = [["", ""], ['"', '"'], ['"abc', 'xyz"'], ['"\\', '"'], ['"\\u', '"'], ['"\\u0', 'abc"'], ['"\\uD800', '"'], ['"\\uD800\\', '"'], ['"\\uD800\\u', '000"'], ["{", "}"], ['{"', '":0}'], ['{"a":', "}"], ["[", "]"], ["0", ""], ["-", ""], ["0.", ""], ["0e+", ""], ["true", ""], ["nul", ""], ["{}", ""], [" \n\t", ""], ['"é😀', 'z"'], ['\n"é', '\\q"'], ['"', '\\q"'], ['"', '\n"'], ['"', ""]];

/** Exhaustive small byte boundaries plus a fixed seeded mutation corpus. No saved expected outputs. */
export function jsonCorpusFixtures(): Fixture[] {
  const bytes = new Set<string>(), rows: Fixture[] = [];
  const add = (value: Uint8Array | number[]) => bytes.add(Buffer.from(value).toString("hex"));
  const enc = (value: string) => Buffer.from(value);
  for (const prefix of ["t", "tr", "tru", "f", "fa", "fal", "fals", "n", "nu", "nul"]) {
    for (const suffix of ["", " ", "é", "😀"]) for (let byte = 0; byte < 256; byte++) add(Buffer.concat([enc(prefix), Buffer.from([byte]), enc(suffix)]));
    for (const point of ["é", "中", "😀"]) add(enc(prefix + point));
  }
  for (let byte = 0; byte < 256; byte++) for (const [before, after] of templates) add(Buffer.concat([enc(before!), Buffer.from([byte]), enc(after!)]));
  for (let first = 0; first < 256; first++) for (let second = 0; second < 256; second++) add([34, first, second, 34]);
  const leads = [0xc0, 0xc1, 0xc2, 0xdf, 0xe0, 0xe1, 0xed, 0xef, 0xf0, 0xf1, 0xf4, 0xf5, 0xff];
  const tails = [0, 0x22, 0x5c, 0x7f, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc0, 0xff];
  for (const first of leads) for (let second = 0; second < 256; second++) for (const third of tails) add([34, first, second, third, 34]);
  for (const first of leads) for (const second of tails) for (const third of tails) for (const fourth of tails) add([34, first, second, third, fourth, 34]);
  let random = 0x164;
  const next = () => { random ^= random << 13; random ^= random >>> 17; random ^= random << 5; return random >>> 0; };
  for (let index = 0; index < 12_000; index++) { const pair = templates[next() % templates.length]!, payload = Buffer.alloc(next() % 16); for (let at = 0; at < payload.length; at++) payload[at] = next(); add(Buffer.concat([enc(pair[0]!), payload, enc(pair[1]!)])); }
  for (const hex of ["22ff22", "7b7dff", "225cff22", "20ff", "22e28222", "22eda08022", "22f490808022", "22f48fbfbf22"]) add(Buffer.from(hex, "hex"));
  for (const before of ['"\\u', '"\\u0', '"\\u00', '"\\u000', '"\\uD800', '"\\uD800\\', '"\\uD800\\u', '"\\uD800\\u0']) for (const tail of scalarTexts) for (const after of ["", '"', '0123"', '\\n"', '\\q"']) add(enc(before + tail + after));
  for (const bytes_hex of bytes) rows.push({ operation: "json_value", bytes_hex, corpus: "json-value-bytes" });
  for (const value of scalarTexts.concat(Array.from({ length: 32 }, (_, index) => String.fromCharCode(index)))) for (const before of ["", "\\", "\\u", "\\u0", "\\u000", "\\uD800", "\\uD800\\u"]) for (const after of ['"}', '0123"}', ""]) rows.push({ operation: "wait_arguments", arguments: '{"unknown":"' + before + value + after, corpus: "json-ignored-value" });
  const text = readFileSync(resolve(import.meta.dir, "current-json-string-fixtures.jsonl"), "utf8");
  for (const line of text.trimEnd().split("\n")) rows.push({ operation: "json_value", source: JSON.parse(line).text, corpus: "json-value-string" });
  return [...new Map(rows.map(row => [JSON.stringify(row), row])).values()];
}

export function assertJsonCorpus(fixtures: Fixture[]): void {
  const lock = JSON.parse(readFileSync(resolve(import.meta.dir, "current-json.lock.json"), "utf8"));
  assert.equal(lock.schema, 1); assert.equal(fixtures.length, lock.cases, "mandatory JSON byte/string cases omitted");
  const rows = fixtures.map(({ id: _id, ...input }) => JSON.stringify(input)).sort();
  assert.equal(new Set(rows).size, rows.length, "duplicate JSON corpus inputs");
  assert.equal(sha256(rows.join("\n") + "\n"), lock.inputsSha256, "mandatory JSON corpus changed");
  assert.equal(sha256(readFileSync(resolve(import.meta.dir, "current-json-string-fixtures.jsonl"))), lock.stringInputsSha256);
}
