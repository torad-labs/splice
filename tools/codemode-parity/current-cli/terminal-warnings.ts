import { writeFileSync } from "node:fs";
import { join } from "node:path";
import type { NativeCompletion } from "./types";

export interface WarningScreenRow { text: string; wrapped: boolean }
export interface TerminalWarningCapture {
  schema: 1; command: "/warnings"; pid: number; sessionId: string;
  frames: Array<{ index: number; count: number; rows: WarningScreenRow[] }>;
  kept: true;
}

/** The immutable TUI retains warning details in /warnings; its normal transcript may show only a footer notice. */
export async function captureTerminalWarnings(options: {
  directory: string; pid: number; completion: NativeCompletion;
  write(value: string): void; record(kind: string, value: string): void;
  rows(): WarningScreenRow[]; flush(): Promise<void>; alive(): boolean;
}): Promise<TerminalWarningCapture> {
  const send = (kind: string, value: string) => { options.record(kind, value); options.write(value); };
  send("open_availability_warning_view", "/warnings");
  // Submit separately so the real composer finishes its paste detection, as for a normal typed command.
  await Bun.sleep(250); send("submit_availability_warning_view", "\r");
  const frames: TerminalWarningCapture["frames"] = [];
  const wait = async (previous?: number) => {
    const deadline = Date.now() + 4000;
    while (options.alive() && Date.now() < deadline) {
      await options.flush(); const rows = options.rows(), text = rows.map(row => row.text).join("\n");
      const matches = [...text.matchAll(/Warnings · (\d+) of (\d+) ·/g)], header = matches.at(-1);
      if (header && Number(header[1]) !== previous) return { index: Number(header[1]), count: Number(header[2]), rows };
      if (previous === undefined && /\bWarnings\b/.test(text) && text.includes("No warnings")) return { index: 0, count: 0, rows };
      if (previous !== undefined && !/\bWarnings\b/.test(text)) return undefined;
      await Bun.sleep(25);
    }
    throw Error("Actual TUI did not open or advance its warning viewer");
  };
  let frame = await wait();
  if (!frame) throw Error("Actual TUI warning viewer is absent");
  const count = frame.count;
  if (count > 32 || frame.index !== (count ? 1 : 0)) throw Error("Actual TUI warning list exceeds its isolated bound");
  while (frame) {
    if (frame.count !== count || frame.index !== (count ? frames.length + 1 : 0)) throw Error("Actual TUI warning list changed during capture");
    frames.push(frame); send("keep_availability_warning_and_advance", "k");
    if (!count || frame.index === count) {
      await wait(frame.index); break;
    }
    frame = await wait(frame.index);
    if (!frame) throw Error("Actual TUI closed before every retained warning was captured");
  }
  const capture: TerminalWarningCapture = { schema: 1, command: "/warnings", pid: options.pid,
    sessionId: options.completion.sessionId, frames, kept: true };
  writeFileSync(join(options.directory, "availability-warning-view.json"), JSON.stringify(capture, null, 2) + "\n", { mode: 0o600 });
  return capture;
}
