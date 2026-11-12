import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { homedir } from "node:os";
import { readFileSync } from "node:fs";
import { sha256 } from "./qualification";

export const snowballVocabulary = { version: "1.2.0", sha256: "ee41dc46a87d1564f96711c4f442ea6eb37eeca7877987e269df5f9d02c22b20", lines: 29417 } as const;
export function tokenizerCorpusIdentity(fixtures: Record<string, any>[]): { count: number; sha256: string } {
  const hashes = fixtures.map(value => { assert.equal(value.operation, "tokenize"); assert.equal(typeof value.text, "string"); return sha256(JSON.stringify({ operation: "tokenize", text: value.text })); }).sort();
  assert.equal(new Set(hashes).size, hashes.length, "duplicate tokenizer inputs cannot inflate the corpus");
  return { count: hashes.length, sha256: sha256(hashes.join("\n") + "\n") };
}
export function assertTokenizerCorpus(fixtures: Record<string, any>[]): void {
  const lock = JSON.parse(readFileSync(resolve(import.meta.dir, "current-tokenizer.lock.json"), "utf8"));
  assert.equal(lock.schema, 1); assert.deepEqual(lock.vocabulary, snowballVocabulary);
  assert.equal(lock.generatorSha256, sha256(readFileSync(import.meta.path)), "tokenizer generator changed without its reviewed corpus identity");
  assert.equal(lock.dependencyLockSha256, sha256(readFileSync(resolve(import.meta.dir, "current-core-oracle.lock"))), "tokenizer transitive source graph changed");
  assert.deepEqual(tokenizerCorpusIdentity(fixtures), lock.corpus, "mandatory tokenizer corpus was omitted, changed or shrunk");
}
export async function tokenizerFixtures(save: (name: string, data: string) => Promise<unknown> = async () => {}): Promise<Record<string, any>[]> {
  const fixtures: Record<string, any>[] = [], root = resolve(process.env.CARGO_HOME ?? resolve(homedir(), ".cargo"), "registry/src");
  let vocabulary: string | undefined;
  for (const registry of await readdir(root)) try {
    const bytes = await readFile(resolve(root, registry, `rust-stemmers-${snowballVocabulary.version}/test_data/voc_en.txt`));
    assert.equal(sha256(bytes), snowballVocabulary.sha256, "original licensed Snowball corpus changed; a shorter cache corpus cannot qualify");
    vocabulary = bytes.toString(); assert.equal(vocabulary.trimEnd().split("\n").length, snowballVocabulary.lines); break;
  } catch (error: any) { if (error.code !== "ENOENT") throw error; }
  assert.ok(vocabulary, "locked rust-stemmers original vocabulary is unavailable; build/fetch the source oracle dependencies first");
  await save("snowball-english-vocabulary.txt", vocabulary);
  vocabulary.trimEnd().split("\n").forEach((text, index) => {
    fixtures.push({ operation: "tokenize", text });
    if (index % 13 === 0) fixtures.push({ operation: "tokenize", text: text + " tools read École 🍕 汉字 cannot've " });
  });
  const chars = ["a", "b", "0", "1", "_", "'", ".", ":", ",", ";", "-", " ", "\n", "\""];
  for (const a of chars) for (const b of chars) for (const c of chars) fixtures.push({ operation: "tokenize", text: a + b + c });
  let seed = 164; const random = (bound: number) => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed % bound; };
  const alphabet = "abcdeiyY0123_.,;:'!\"\n\t";
  for (let index = 0; index < 3000; index++) { let text = ""; for (let position = 0, length = 1 + random(50); position < length; position++) text += alphabet[random(alphabet.length)]; fixtures.push({ operation: "tokenize", text }); }
  for (let base = 0; base <= 0x10ffff; base += 4096) { let text = ""; for (let point = base; point < Math.min(base + 4096, 0x110000); point++) if (point < 0xd800 || point > 0xdfff) text += String.fromCodePoint(point); fixtures.push({ operation: "tokenize", text }); }
  return fixtures;
}
