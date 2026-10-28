import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { readFile, realpath, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import type { Artifact, Identity } from "./qualification";

export async function preDispatchArtifact(path: string): Promise<Artifact> {
  const canonical = await realpath(path), digest = createHash("sha256"); let bytes = 0;
  for await (const chunk of createReadStream(canonical)) { digest.update(chunk); bytes += chunk.length; }
  return { path: canonical, bytes, sha256: digest.digest("hex") };
}
export async function preDispatchChecked(pin: Identity & { bytes?: number }, maxBytes = 128 * 1024 ** 2): Promise<Buffer> {
  const actual = await preDispatchArtifact(pin.path); assert.equal(actual.sha256, pin.sha256, `artifact hash differs: ${pin.path}`);
  if (pin.bytes !== undefined) assert.equal(actual.bytes, pin.bytes);
  assert.ok(actual.bytes <= maxBytes, `artifact exceeds bounded JSON/read allowance: ${pin.path}`);
  return readFile(actual.path);
}
export async function preDispatchVerify(pin: Identity & { bytes?: number }): Promise<void> {
  const actual = await preDispatchArtifact(pin.path); assert.equal(actual.sha256, pin.sha256);
  if (pin.bytes !== undefined) assert.equal(actual.bytes, pin.bytes);
}
export async function preDispatchWrite(path: string, value: unknown): Promise<Artifact> {
  await writeFile(path, JSON.stringify(value, null, 2) + "\n"); return preDispatchArtifact(path);
}
export const preDispatchDriver = (name: string): string => resolve(import.meta.dir, "pre-dispatch", name);
export function preDispatchBoundedArgs(unit: string, command: string[], seconds = 180): string[] {
  assert.match(unit, /^splice-parity-pre-dispatch-[a-f0-9-]+$/);
  return ["systemd-run", "--user", "--scope", `--unit=${unit}`, "-p", "MemoryMax=4G", "-p", "MemorySwapMax=0", "-p", "CPUWeight=20",
    "nice", "-n", "5", "ionice", "-c", "3", "timeout", "--kill-after=5s", `${seconds}s`, ...command];
}
