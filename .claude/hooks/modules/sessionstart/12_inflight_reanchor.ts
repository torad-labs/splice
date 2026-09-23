/** §in-flight re-anchor — a seat that claimed a row gets that row back at SessionStart.
 *
 *  The pointer is `.claude/state/ledger-active-<session_id>.json`, written by the ledger CLI's
 *  `claim`/`focus`. When it names a row that is still in_flight, this injects that one row and the
 *  command that prints its brief (laws included). A seat with no pointer gets nothing: listing every
 *  seat's in-flight rows to every session was noise, and was removed with the Stop-time in-flight
 *  warnings and the SessionStart law dump (hook audit, 2026-09-22).
 *
 *  The guidance text is READ AS AN INSTRUCTION, so it must name a command that exists.
 */
import { existsSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

import { HookResult, hookResult } from "../../orchestrator/result";
import { findProjectRoot } from "../../lib/project_root";

export const MODULE_NAME = "12_inflight_reanchor";
const SESSIONSTART_SOURCES: ReadonlySet<string> = new Set(["startup", "resume", "clear", "compact"]);
const MAX_PAYLOAD_CHARS = 500;
const TITLE_LIMIT = 60;

type HookEvent = Record<string, unknown>;

interface Item {
  id: string;
  ledger: string;
  title: string;
}

const safeId = (value: unknown): string =>
  String(value ?? "default").replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 128) || "default";

function readJson(path: string): Record<string, unknown> {
  try {
    const data = JSON.parse(readFileSync(path, "utf8")) as unknown;
    return data !== null && typeof data === "object" && !Array.isArray(data)
      ? (data as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

/** Returns null on a parse error — the caller treats an unreadable manifest as absent rather than
 *  as a finding, which is why this collapses the error instead of propagating. */
function readToml(path: string): Record<string, unknown> | null {
  try {
    const data = Bun.TOML.parse(readFileSync(path, "utf8")) as unknown;
    return data !== null && typeof data === "object" && !Array.isArray(data)
      ? (data as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function pointerItem(root: string, sessionId: unknown): Item | null {
  const pointer = readJson(join(root, ".claude", "state", `ledger-active-${safeId(sessionId)}.json`));
  const itemId = pointer.item_id;
  const rawPath = pointer.ledger_path;
  if (typeof itemId !== "string" || !itemId || typeof rawPath !== "string" || !rawPath) return null;
  const ledgerPath = rawPath.startsWith("/") ? rawPath : join(root, rawPath);
  if (!existsSync(ledgerPath) || !statSync(ledgerPath).isFile()) return null;

  const doc = readToml(ledgerPath);
  if (doc === null) return null;
  const entries = Array.isArray(doc.items) ? (doc.items as Record<string, unknown>[]) : [];
  for (const item of entries) {
    if (item.id === itemId && item.status === "in_flight") {
      return { id: itemId, ledger: relative(root, ledgerPath), title: String(item.title ?? "").slice(0, TITLE_LIMIT) };
    }
  }
  return null;
}

function payload(item: Item): string {
  const text = [
    `§in-flight re-anchor — this session claimed ${item.id} [${item.ledger}] ${item.title}`,
    `Brief and laws: bun .dev/campaigns/manifest.ts ${item.ledger} packet ${item.id}`,
  ].join("\n");
  // A guard against a runaway title or path, not a text operation.
  return text.length > MAX_PAYLOAD_CHARS ? text.slice(0, MAX_PAYLOAD_CHARS) : text;
}

export function applies(data: HookEvent): boolean {
  const source = data.source;
  return source === undefined || source === null || SESSIONSTART_SOURCES.has(String(source));
}

export function run(data: HookEvent): HookResult | null {
  const root = findProjectRoot(data.cwd as string | undefined);
  if (root === null) return null;
  const item = pointerItem(root, data.session_id);
  return item === null ? null : hookResult("inject", payload(item), MODULE_NAME);
}
