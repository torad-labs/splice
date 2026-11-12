import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import type { Artifact } from "../qualification";

/** Source-built applications exceed 2 GiB; identity checks retain only one stream chunk. */
export async function fileIdentity(path: string): Promise<{ sha256: string; bytes: number }> {
  const before = await stat(path), hash = createHash("sha256");
  assert.ok(before.isFile() && Number.isSafeInteger(before.size) && before.size <= 3 * 1024 ** 3, "Identity input is not a bounded regular file");
  let bytes = 0;
  for await (const chunk of createReadStream(path, { highWaterMark: 64 * 1024 })) {
    bytes += chunk.length; assert.ok(bytes <= before.size, "Identity input grew during verification"); hash.update(chunk);
  }
  const after = await stat(path);
  assert.equal(bytes, before.size); assert.equal(after.size, before.size); assert.equal(after.mtimeMs, before.mtimeMs);
  assert.equal(after.ino, before.ino); assert.equal(after.dev, before.dev);
  return { sha256: hash.digest("hex"), bytes };
}

export async function verifyArtifact(proof: Artifact): Promise<void> {
  assert.match(proof.sha256, /^[a-f0-9]{64}$/);
  assert.deepEqual(await fileIdentity(proof.path), { sha256: proof.sha256, bytes: proof.bytes }, "Artifact identity differs");
}
