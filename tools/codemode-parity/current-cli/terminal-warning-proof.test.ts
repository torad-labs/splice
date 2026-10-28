import { expect, test } from "bun:test";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { assertTerminalWarnings } from "./terminal-warning-proof";
import type { TerminalWarningCapture } from "./terminal-warnings";

const display = "Warnings · 1 of 1 · Startup\r\nA real retained warning";
const event = (kind: string, value: string) => ({ kind, at_ms: 0,
  raw_base64: Buffer.from(value).toString("base64"), bytes: Buffer.byteLength(value) });
const events = [event("open_availability_warning_view", "/warnings"), event("submit_availability_warning_view", "\r"),
  event("pty_output", display), event("keep_availability_warning_and_advance", "k")];
const capture = (): TerminalWarningCapture => ({ schema: 1, command: "/warnings", pid: 42, sessionId: "owned", kept: true,
  frames: [{ index: 1, count: 1, rows: Array.from({ length: 40 }, (_, index) => ({ wrapped: false,
    text: index === 0 ? "Warnings · 1 of 1 · Startup" : index === 1 ? "A real retained warning" : "" })) }] });

test("warning receipts must equal actual PTY rendering before each retained keep key", async () => {
  const directory = mkdtempSync(join(tmpdir(), "warning-pty-"));
  try {
    writeFileSync(join(directory, "terminal.raw"), display);
    writeFileSync(join(directory, "terminal-events.jsonl"), events.map(value => JSON.stringify(value)).join("\n") + "\n");
    await expect(assertTerminalWarnings(directory, capture())).resolves.toBeUndefined();
    const forged = capture(); forged.frames[0]!.rows[1]!.text = "An invented warning";
    await expect(assertTerminalWarnings(directory, forged)).rejects.toThrow("actual PTY rendering");
    writeFileSync(join(directory, "terminal.raw"), display + "unrecorded output");
    await expect(assertTerminalWarnings(directory, capture())).rejects.toThrow("recorded PTY output");
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test("unsubmitted warning details and truncated event streams cannot prove a viewer", async () => {
  const directory = mkdtempSync(join(tmpdir(), "warning-control-"));
  try {
    writeFileSync(join(directory, "terminal.raw"), display);
    writeFileSync(join(directory, "terminal-events.jsonl"), events.slice(1).map(value => JSON.stringify(value)).join("\n") + "\n");
    await expect(assertTerminalWarnings(directory, capture())).rejects.toThrow("not submitted in order");
    writeFileSync(join(directory, "terminal-events.jsonl"), events.map(value => JSON.stringify(value)).join("\n"));
    await expect(assertTerminalWarnings(directory, capture())).rejects.toThrow("incomplete");
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
