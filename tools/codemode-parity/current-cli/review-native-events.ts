import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import type { NativeCompletion, ObjectValue } from "./types";

type Row = { type: string; payload?: ObjectValue };
type Conversation = { sourceFile: string; rows: Row[]; meta: ObjectValue };
export type NativeReviewCompletion = NativeCompletion & {
  review: { sessionId: string; parentSessionId: string; rootTurnId: string; turnId: string; sourceFile: string; finalText: string };
};
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-7[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;

function completed(conversation: Conversation): ObjectValue | undefined {
  const events = conversation.rows.filter(row => row.type === "event_msg").map(row => row.payload);
  if (events.some(event => ["turn_aborted", "error"].includes(String(event?.type)))) return;
  const starts = events.filter(event => event?.type === "task_started");
  const ends = events.filter(event => event?.type === "task_complete");
  if (starts.length !== 1 || ends.length !== 1 || events.indexOf(starts[0]) >= events.indexOf(ends[0])) return;
  return ends[0];
}

/** A review owns exactly a user conversation and its explicitly linked native review child. */
export function nativeReviewCompletion(home: string, cwd: string, expectedReviewText: string): NativeReviewCompletion | null {
  try {
    const expected = JSON.parse(expectedReviewText);
    if (!expected || !Array.isArray(expected.findings) || expected.findings.length ||
      expected.overall_correctness !== "patch is correct" || typeof expected.overall_explanation !== "string" ||
      !/^REVIEW_CURRENT_[a-f0-9]{32}$/.test(expected.overall_explanation) || expected.overall_confidence_score !== 1) return null;
    const directory = join(home, "sessions");
    if (!existsSync(directory)) return null;
    const files = readdirSync(directory, { recursive: true, withFileTypes: true })
      .filter(entry => entry.isFile() && entry.name.endsWith(".jsonl"))
      .map(entry => join(entry.parentPath, entry.name));
    if (files.length !== 2) return null;
    const conversations: Conversation[] = [];
    for (const sourceFile of files) {
      if (statSync(sourceFile).size > 16 * 1024 * 1024) return null;
      const text = readFileSync(sourceFile, "utf8");
      if (!text.endsWith("\n")) return null;
      const rows: Row[] = text.trimEnd().split("\n").map(line => JSON.parse(line));
      const metadata = rows.filter(row => row.type === "session_meta");
      const meta = metadata[0]?.payload;
      if (metadata.length !== 1 || !meta || typeof meta.id !== "string" || !uuid.test(meta.id) || meta.cwd !== cwd ||
        typeof meta.cli_version !== "string" || !/^\d+\.\d+\.\d+$/.test(meta.cli_version)) return null;
      conversations.push({ sourceFile, rows, meta });
    }
    const parents = conversations.filter(item => item.meta.source === "exec" && item.meta.thread_source === "user" &&
      item.meta.parent_thread_id === undefined);
    const children = conversations.filter(item => item.meta.source && typeof item.meta.source === "object" &&
      !Array.isArray(item.meta.source) && (item.meta.source as ObjectValue).subagent === "review" && item.meta.thread_source === "subagent");
    if (parents.length !== 1 || children.length !== 1) return null;
    const parent = parents[0]!, child = children[0]!;
    if (parent === child || parent.meta.id === child.meta.id || child.meta.parent_thread_id !== parent.meta.id ||
      child.meta.cli_version !== parent.meta.cli_version) return null;
    const parentEnd = completed(parent), childEnd = completed(child);
    if (!parentEnd || !childEnd || typeof parentEnd.turn_id !== "string" || !uuid.test(parentEnd.turn_id) ||
      parentEnd.root_turn_id !== parentEnd.turn_id || childEnd.root_turn_id !== parentEnd.turn_id ||
      typeof childEnd.turn_id !== "string" || !uuid.test(childEnd.turn_id) || childEnd.turn_id === parentEnd.turn_id ||
      childEnd.last_agent_message !== expectedReviewText ||
      !(parentEnd.last_agent_message === null || parentEnd.last_agent_message === undefined || parentEnd.last_agent_message === "")) return null;
    return { sessionId: parent.meta.id as string, sourceFile: parent.sourceFile, finalText: "",
      review: { sessionId: child.meta.id as string, parentSessionId: parent.meta.id as string,
        rootTurnId: parentEnd.turn_id, turnId: childEnd.turn_id, sourceFile: child.sourceFile, finalText: expectedReviewText } };
  } catch { return null; }
}
