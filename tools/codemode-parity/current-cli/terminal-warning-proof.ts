import assert from "node:assert/strict";
import { readFile, stat } from "node:fs/promises";
import { resolve } from "node:path";
import xterm from "@xterm/headless";
import type { TerminalWarningCapture } from "./terminal-warnings";

// Fixed synthetic scenarios need far less; refuse an unbounded client transcript as terminal evidence.
const captureBytes = 16 * 1024 * 1024;

/** Re-render actual PTY bytes at each retained keep/next key; a JSON warning claim alone earns nothing. */
export async function assertTerminalWarnings(directory: string, capture: TerminalWarningCapture): Promise<void> {
  const read = async (name: string) => {
    const path = resolve(directory, name);
    assert.ok((await stat(path)).size <= captureBytes, "Terminal warning evidence exceeds its capture bound");
    const bytes = await readFile(path);
    assert.ok(bytes.length <= captureBytes, "Terminal warning evidence grew beyond its capture bound");
    return bytes;
  };
  const events = (await read("terminal-events.jsonl")).toString(), raw = await read("terminal.raw");
  assert.ok(events.endsWith("\n"), "Terminal warning event capture is incomplete");
  const terminal = new xterm.Terminal({ cols: 120, rows: 40, scrollback: 10000, allowProposedApi: true });
  const output: Buffer[] = [];
  let opened = false, submitted = false, kept = 0;
  try {
    for (const line of events.trimEnd().split("\n")) {
      const event = JSON.parse(line);
      assert.equal(typeof event.kind, "string");
      assert.equal(typeof event.raw_base64, "string");
      const bytes = Buffer.from(event.raw_base64, "base64");
      assert.equal(bytes.toString("base64"), event.raw_base64, "PTY event has invalid bytes");
      assert.equal(bytes.length, event.bytes, "PTY event byte count differs");
      if (event.kind === "pty_output") {
        output.push(bytes);
        await new Promise<void>(resolveWrite => terminal.write(bytes, resolveWrite));
      } else if (event.kind === "open_availability_warning_view") {
        assert.ok(!opened && !submitted && !kept, "Warning viewer was opened more than once");
        assert.equal(bytes.toString(), "/warnings"); opened = true;
      } else if (event.kind === "submit_availability_warning_view") {
        assert.ok(opened && !submitted && !kept, "Warning command was not submitted in order");
        assert.equal(bytes.toString(), "\r"); submitted = true;
      } else if (event.kind === "keep_availability_warning_and_advance") {
        assert.ok(opened && submitted && kept < capture.frames.length, "Unowned warning keep/next key");
        assert.equal(bytes.toString(), "k");
        const buffer = terminal.buffer.active;
        const rows = Array.from({ length: 40 }, (_, index) => {
          const row = buffer.getLine(buffer.baseY + index);
          return { text: row?.translateToString(true) ?? "", wrapped: row?.isWrapped ?? false };
        });
        assert.deepEqual(rows, capture.frames[kept++]!.rows, "Retained warning differs from actual PTY rendering");
      }
    }
    assert.ok(opened && submitted && kept > 0 && kept === capture.frames.length, "Not every retained warning came from an actual viewer key");
    assert.ok(Buffer.concat(output).equals(raw), "Raw terminal bytes differ from the recorded PTY output");
  } finally { terminal.dispose(); }
}
