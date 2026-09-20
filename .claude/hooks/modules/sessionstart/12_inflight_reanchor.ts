/** §in-flight re-anchor — surface in-flight work at SessionStart.
 *
 *  If the current session already claimed an item, inject that single item back
 *  into the seat. Otherwise, surface the current in-flight campaign items with the
 *  same short report-back contract used at turn end.
 *
 *  The guidance text names the bun ledger CLI. It named the Python one until V4-143 deleted that
 *  file (2026-09-18); the rule then and now is the same — this text is READ AS AN INSTRUCTION by
 *  every seat at session start, so it must name a command that exists.
 */
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { HookResult, hookResult } from "../../orchestrator/result";
import { findProjectRoot } from "../../lib/astgrep_gate";

export const MODULE_NAME = "12_inflight_reanchor";
const SESSIONSTART_SOURCES: ReadonlySet<string> = new Set(["startup", "resume", "clear", "compact"]);
const LINE_ONE = "§in-flight re-anchor — in_flight at session start:";
const GUIDANCE =
  "If YOURS: note progress via bun .dev/campaigns/manifest.ts <path> note <ID> ...; " +
  "set-status done only after its verify passes; then reply ONE line: <ID> done — see ledger. " +
  "Laws: bun .dev/campaigns/manifest.ts laws";
const MAX_PAYLOAD_CHARS = 500;
const MAX_ITEMS = 3;
const TITLE_LIMIT = 60;

type HookEvent = Record<string, unknown>;

interface Item {
  id: string;
  stem: string;
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

/** Returns null where Python raised TOMLDecodeError — the caller treats an unreadable manifest as
 *  absent rather than as a finding, which is why this collapses the error instead of propagating. */
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

function pointerPath(root: string, sessionId: unknown): string {
  return join(root, ".claude", "state", `ledger-active-${safeId(sessionId)}.json`);
}

function manifestPath(root: string, rawPath: unknown): string | null {
  if (typeof rawPath !== "string" || !rawPath) return null;
  try {
    return rawPath.startsWith("/") ? rawPath : join(root, rawPath);
  } catch {
    return null;
  }
}

function campaignInflightItems(campaigns: string): Item[] {
  const items: Item[] = [];
  if (!existsSync(campaigns) || !statSync(campaigns).isDirectory()) return items;

  const manifests = readdirSync(campaigns)
    .filter((name) => name.endsWith(".toml"))
    .sort();
  for (const name of manifests) {
    const doc = readToml(join(campaigns, name));
    if (doc === null) continue;
    const entries = Array.isArray(doc.items) ? (doc.items as Record<string, unknown>[]) : [];
    for (const item of entries) {
      if (item.status !== "in_flight") continue;
      items.push({
        id: String(item.id ?? "?"),
        stem: name.replace(/\.toml$/, ""),
        title: String(item.title ?? "").slice(0, TITLE_LIMIT),
      });
    }
  }
  return items;
}

function pointerItem(root: string, sessionId: unknown): Item | null {
  const pointer = readJson(pointerPath(root, sessionId));
  const itemId = pointer.item_id;
  const ledgerPath = manifestPath(root, pointer.ledger_path);
  if (typeof itemId !== "string" || !itemId || ledgerPath === null) return null;
  if (!existsSync(ledgerPath) || !statSync(ledgerPath).isFile()) return null;

  const doc = readToml(ledgerPath);
  if (doc === null) return null;
  const entries = Array.isArray(doc.items) ? (doc.items as Record<string, unknown>[]) : [];
  for (const item of entries) {
    if (item.id === itemId && item.status === "in_flight") {
      return {
        id: itemId,
        stem: (ledgerPath.split("/").pop() ?? "").replace(/\.toml$/, ""),
        title: String(item.title ?? "").slice(0, TITLE_LIMIT),
      };
    }
  }
  return null;
}

function payload(items: Item[]): string {
  const lines = [LINE_ONE];
  for (const item of items.slice(0, MAX_ITEMS)) {
    lines.push(`- ${item.id} [${item.stem}] ${item.title}`);
  }
  if (items.length > MAX_ITEMS) lines.push(`… +${items.length - MAX_ITEMS} more`);
  lines.push(GUIDANCE);
  const text = lines.join("\n");
  // Python sliced by code point; every character in this payload is BMP, and the truncation is a
  // guard against a runaway ledger rather than a text operation, so a UTF-16 slice is equivalent.
  return text.length > MAX_PAYLOAD_CHARS ? text.slice(0, MAX_PAYLOAD_CHARS) : text;
}

export function applies(data: HookEvent): boolean {
  const source = data.source;
  return source === undefined || source === null || SESSIONSTART_SOURCES.has(String(source));
}

export function run(data: HookEvent): HookResult | null {
  const root = findProjectRoot(data.cwd as string | undefined);
  if (root === null) return null;

  const fromPointer = pointerItem(root, data.session_id);
  if (fromPointer !== null) return hookResult("inject", payload([fromPointer]), MODULE_NAME);

  const items = campaignInflightItems(join(root, ".dev/campaigns"));
  if (items.length === 0) return null;
  return hookResult("inject", payload(items), MODULE_NAME);
}
