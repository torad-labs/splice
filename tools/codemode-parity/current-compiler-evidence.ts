import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { isAbsolute } from "node:path";
import type { CompilerArtifact } from "./current-compiler-types";

export const compilerHash = (bytes: string | Uint8Array): string => createHash("sha256").update(bytes).digest("hex");
export function compilerObject(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("compiler proof requires an object");
  return value as Record<string, unknown>;
}
export function compilerString(value: unknown): string { if (typeof value !== "string" || !value) throw new Error("compiler proof requires a string"); return value; }
export function compilerStrings(value: unknown): string[] {
  if (!Array.isArray(value) || value.some(item => typeof item !== "string")) throw new Error("compiler proof requires a string array");
  return value as string[];
}
export function compilerArray(value: unknown): unknown[] { if (!Array.isArray(value)) throw new Error("compiler proof requires an array"); return value; }
export function compilerArtifact(value: unknown): CompilerArtifact {
  const row = compilerObject(value), path = compilerString(row.path), sha256 = compilerString(row.sha256), bytes = row.bytes;
  if (!isAbsolute(path) || !/^[a-f0-9]{64}$/.test(sha256) || typeof bytes !== "number" || !Number.isSafeInteger(bytes) || bytes < 0) throw new Error("invalid compiler artifact identity");
  return { path, sha256, bytes };
}
export async function verifyCompilerArtifact(file: CompilerArtifact): Promise<void> {
  if ((await stat(file.path)).size !== file.bytes) throw new Error("compiler input size differs");
  const digest = createHash("sha256"); let bytes = 0;
  for await (const chunk of createReadStream(file.path)) { bytes += (chunk as Buffer).length; digest.update(chunk as Buffer); }
  if (bytes !== file.bytes || digest.digest("hex") !== file.sha256) throw new Error("compiler input hash differs");
}
export function freezeCompilerFacts<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) freezeCompilerFacts(child);
    Object.freeze(value);
  }
  return value;
}
