import { createHash } from "node:crypto";
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
export const hash = (value: string | Uint8Array) => createHash("sha256").update(value).digest("hex");
export function json(path: string): unknown { return JSON.parse(readFileSync(path, "utf8")); }
export function save(path: string, value: unknown): void { mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, JSON.stringify(value, null, 2)); }
export function files(root: string): string[] {
  if (!existsSync(root)) return [];
  return readdirSync(root, { recursive: true, withFileTypes: true }).filter((entry) => entry.isFile()).map((entry) => join(entry.parentPath, entry.name));
}
export function safePath(root: string, path: string): string {
  const full = resolve(root, path), rel = relative(resolve(root), full);
  if (rel === "" || rel.startsWith("..") || resolve(path) === path) throw new Error(`Unsafe artifact path ${path}`);
  let cursor = full;
  while (cursor !== resolve(root)) {
    if (existsSync(cursor) && lstatSync(cursor).isSymbolicLink()) throw new Error(`Symlinked artifact path ${path}`);
    cursor = dirname(cursor);
  }
  return full;
}
