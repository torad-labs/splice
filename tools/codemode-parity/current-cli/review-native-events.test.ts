import { expect, test } from "bun:test";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { nativeCompletion } from "./native-events";
import { nativeReviewCompletion } from "./review-native-events";

const parent = "00000000-0000-7000-8000-000000000001", child = "00000000-0000-7000-8000-000000000002";
const rootTurn = "00000000-0000-7000-8000-000000000003", childTurn = "00000000-0000-7000-8000-000000000004";
const finalText = JSON.stringify({ findings: [], overall_correctness: "patch is correct",
  overall_explanation: "REVIEW_CURRENT_" + "a".repeat(32), overall_confidence_score: 1 });
function fixture() {
  const home = mkdtempSync(join(tmpdir(), "codemode-review-events-")), cwd = join(home, "workspace");
  const directory = join(home, "sessions", "2026", "10", "27");
  mkdirSync(directory, { recursive: true });
  const parentFile = join(directory, "parent.jsonl"), childFile = join(directory, "child.jsonl");
  const rows = (id: string, nested: boolean) => [
    { type: "session_meta", payload: { id, cwd, cli_version: "0.165.0", source: nested ? { subagent: "review" } : "exec",
      thread_source: nested ? "subagent" : "user", ...(nested ? { parent_thread_id: parent } : {}) } },
    { type: "event_msg", payload: { type: "task_started", turn_id: nested ? childTurn : rootTurn } },
    { type: "event_msg", payload: { type: "task_complete", root_turn_id: rootTurn,
      turn_id: nested ? childTurn : rootTurn, last_agent_message: nested ? finalText : null } },
  ];
  const write = (file: string, values: unknown[]) => writeFileSync(file, values.map(row => JSON.stringify(row)).join("\n") + "\n");
  write(parentFile, rows(parent, false)); write(childFile, rows(child, true));
  return { home, cwd, parentFile, childFile, write, close: () => rmSync(home, { recursive: true, force: true }) };
}

test("review completion retains separate native parent and child authority", () => {
  const f = fixture();
  try {
    expect(nativeCompletion(f.home)).toBeNull();
    expect(nativeReviewCompletion(f.home, f.cwd, finalText)).toEqual({ sessionId: parent, sourceFile: f.parentFile,
      finalText: "", review: { sessionId: child, parentSessionId: parent, rootTurnId: rootTurn, turnId: childTurn,
        sourceFile: f.childFile, finalText } });
  } finally { f.close(); }
});

test("review rejects another parent, root, output, workspace, role or unfinished child", () => {
  for (const change of ["parent", "root", "output", "cwd", "role", "abort", "unfinished", "duplicate_meta", "truncated"]) {
    const f = fixture();
    try {
      const rows = readFileSync(f.childFile, "utf8").trim().split("\n").map(line => JSON.parse(line));
      if (change === "parent") rows[0].payload.parent_thread_id = child;
      if (change === "root") rows[2].payload.root_turn_id = childTurn;
      if (change === "output") rows[2].payload.last_agent_message = "an unrelated review";
      if (change === "cwd") rows[0].payload.cwd += "-other";
      if (change === "role") rows[0].payload.source = { subagent: "ordinary" };
      if (change === "abort") rows.push({ type: "event_msg", payload: { type: "turn_aborted" } });
      if (change === "unfinished") rows.pop();
      if (change === "duplicate_meta") rows.push(rows[0]);
      f.write(f.childFile, rows);
      if (change === "truncated") writeFileSync(f.childFile, readFileSync(f.childFile, "utf8").trimEnd());
      expect(nativeReviewCompletion(f.home, f.cwd, finalText), change).toBeNull();
    } finally { f.close(); }
  }
});

test("review refuses additional conversations and a stale expected nonce", () => {
  const f = fixture();
  try {
    expect(nativeReviewCompletion(f.home, f.cwd, finalText.replace("a".repeat(32), "b".repeat(32)))).toBeNull();
    writeFileSync(join(f.home, "sessions", "third.jsonl"), readFileSync(f.childFile));
    expect(nativeReviewCompletion(f.home, f.cwd, finalText)).toBeNull();
  } finally { f.close(); }
});
