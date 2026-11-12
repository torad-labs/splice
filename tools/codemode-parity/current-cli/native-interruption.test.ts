import { expect, test } from "bun:test";
import { mkdtempSync, mkdirSync, rmSync, truncateSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { nativeInterruption } from "./native-events";

function fixture(rows: object[]) {
  const directory = mkdtempSync(join(tmpdir(), "codemode-native-interruption-")); mkdirSync(join(directory, "sessions"));
  const path = join(directory, "sessions/fixed-owned.jsonl"); writeFileSync(path, rows.map(row => JSON.stringify(row)).join("\n") + "\n");
  return { directory, path, close: () => rmSync(directory, { recursive: true, force: true }) };
}
const metadata = { type: "session_meta", payload: { id: "fixed-session" } };
const call = { type: "response_item", payload: { type: "custom_tool_call", name: "exec", call_id: "fixed-call", input: 'notify("ready")' } };
const notification = { type: "response_item", payload: { type: "custom_tool_call_output", name: "exec", call_id: "fixed-call", output: "ready" } };
const aborted = { type: "event_msg", payload: { type: "turn_aborted" } };

test("the interruption trigger is a persisted notification owned by the exact observed code call", () => {
  const owned = fixture([metadata, call, notification, aborted]);
  try {
    expect(nativeInterruption(owned.directory, "fixed-call", "ready")).toEqual({ sessionId: "fixed-session", sourceFile: owned.path, notified: true, aborted: true });
    expect(nativeInterruption(owned.directory, "another-call", "ready")?.notified).toBe(false);
    expect(nativeInterruption(owned.directory, "fixed-call", "another-notification")?.aborted).toBe(false);
  } finally { owned.close(); }
});

test("instruction text, primary output and an earlier abort cannot impersonate the notification boundary", () => {
  for (const rows of [
    [metadata, call, { ...notification, payload: { ...notification.payload, output: [{ type: "input_text", text: "ready" }] } }],
    [metadata, notification, call],
    [metadata, call, { ...notification, payload: { ...notification.payload, name: "other" } }],
  ]) {
    const owned = fixture(rows);
    try { expect(nativeInterruption(owned.directory, "fixed-call", "ready")?.notified).toBe(false); } finally { owned.close(); }
  }
  const earlier = fixture([metadata, aborted, call, notification]);
  try { expect(nativeInterruption(earlier.directory, "fixed-call", "ready")?.aborted).toBe(false); } finally { earlier.close(); }
});

test("ambiguous conversations and oversized capture files cannot drive the PTY", () => {
  const owned = fixture([metadata, call, notification]);
  try {
    truncateSync(owned.path, 16 * 1024 * 1024 + 1); expect(nativeInterruption(owned.directory, "fixed-call", "ready")).toBeNull();
    writeFileSync(owned.path, JSON.stringify(metadata) + "\n"); writeFileSync(join(owned.directory, "sessions/another.jsonl"), JSON.stringify(metadata) + "\n");
    expect(nativeInterruption(owned.directory, "fixed-call", "ready")).toBeNull();
  } finally { owned.close(); }
});
