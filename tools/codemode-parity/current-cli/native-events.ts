import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import type { NativeCompletion, ObjectValue } from "./types";

/** Completion comes only from the single owned Codex conversation's persisted native event. */
export function nativeCompletion(home: string): NativeCompletion | null {
  const directory = join(home, "sessions");
  if (!existsSync(directory)) return null;
  const files = readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith(".jsonl") && !entry.parentPath.split(/[\\/]/).includes("subagents"))
    .map(entry => join(entry.parentPath, entry.name));
  if (files.length !== 1) return null;
  const text = readFileSync(files[0]!, "utf8");
  if (!text.endsWith("\n")) return null;
  let sessionId: string | undefined;
  let finalText: string | undefined;
  let complete = false;
  try {
    for (const line of text.split("\n").filter(Boolean)) {
      const row = JSON.parse(line) as { type: string; payload?: ObjectValue };
      const payload = row.payload;
      if (row.type === "session_meta" && typeof payload?.id === "string") sessionId = payload.id;
      if (row.type !== "event_msg") continue;
      if (["task_started", "turn_aborted", "error"].includes(String(payload?.type))) complete = false;
      if (payload?.type === "task_complete") {
        complete = true;
        finalText = typeof payload.last_agent_message === "string" ? payload.last_agent_message : "";
      }
    }
  } catch { return null; }
  return complete && sessionId ? { sessionId, finalText: finalText ?? "", sourceFile: files[0]! } : null;
}
