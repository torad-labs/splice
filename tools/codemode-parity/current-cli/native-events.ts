import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import type { NativeCompletion, ObjectValue } from "./types";

function ownedRows(home: string): { sourceFile: string; rows: Array<{ type: string; payload?: ObjectValue }> } | null {
  const directory = join(home, "sessions");
  if (!existsSync(directory)) return null;
  const files = readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith(".jsonl") && !entry.parentPath.split(/[\\/]/).includes("subagents"))
    .map(entry => join(entry.parentPath, entry.name));
  if (files.length !== 1 || statSync(files[0]!).size > 16 * 1024 * 1024) return null;
  const text = readFileSync(files[0]!, "utf8"); if (!text.endsWith("\n")) return null;
  try { return { sourceFile: files[0]!, rows: text.trimEnd().split("\n").map(line => JSON.parse(line)) }; } catch { return null; }
}

/** Only a persisted notification from this exact outer call may trigger the user's PTY interruption. */
export function nativeInterruption(home: string, callId: string, notification: string): { sessionId: string; sourceFile: string; notified: boolean; aborted: boolean } | null {
  const file = ownedRows(home); if (!file) return null;
  const session = file.rows.find(row => row.type === "session_meta" && typeof row.payload?.id === "string");
  if (!session) return null;
  const called = file.rows.findIndex(row => row.type === "response_item" && row.payload?.type === "custom_tool_call" && row.payload.name === "exec" && row.payload.call_id === callId);
  const notified = called < 0 ? -1 : file.rows.findIndex((row, index) => index > called && row.type === "response_item" && row.payload?.type === "custom_tool_call_output" && row.payload.name === "exec" && row.payload.call_id === callId && row.payload.output === notification);
  const aborted = notified >= 0 && file.rows.some((row, index) => index > notified && row.type === "event_msg" && row.payload?.type === "turn_aborted");
  return { sessionId: session.payload!.id as string, sourceFile: file.sourceFile, notified: called >= 0 && notified >= 0, aborted };
}

/** Completion comes only from the single owned Codex conversation's persisted native event. */
export function nativeCompletion(home: string): NativeCompletion | null {
  const file = ownedRows(home); if (!file) return null;
  let sessionId: string | undefined;
  let finalText: string | undefined;
  let complete = false;
  try {
    for (const row of file.rows) {
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
  return complete && sessionId ? { sessionId, finalText: finalText ?? "", sourceFile: file.sourceFile } : null;
}
