import { readFile } from "node:fs/promises";
import { basename, dirname, isAbsolute, resolve } from "node:path";

/** Resolve a location only. A recorded pointer never replaces receipt qualification checks. */
export async function resolveEvidenceDirectory(explicit: string | undefined, progressPath: string, fallback: string): Promise<string> {
  if (explicit !== undefined) return resolve(explicit);
  let raw: string;
  try { raw = await readFile(progressPath, "utf8"); }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return resolve(fallback);
    throw error;
  }
  let progress: any;
  try { progress = JSON.parse(raw); }
  catch { throw new Error("malformed qualification progress; use explicit --evidence to choose a receipt"); }
  if (!progress || typeof progress !== "object" || Array.isArray(progress) || progress.schema !== 1 ||
    !Object.hasOwn(progress, "receiptPath")) throw new Error("malformed qualification progress receipt pointer");
  if (progress.receiptPath === null) return resolve(fallback);
  const pointer = progress.receiptPath;
  if (typeof pointer !== "string" || pointer.includes("\0") || !isAbsolute(pointer) ||
    basename(pointer) !== "receipt.json" || pointer !== resolve(pointer)) {
    throw new Error("malformed qualification progress receipt pointer");
  }
  return dirname(pointer);
}
