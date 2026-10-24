/** An actual Claude TUI in an isolated PTY; its session log is the client receipt. */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

type Obj = Record<string, any>;
export type InteractiveClientResult = {
  exitCode: number | null;
  thinking: string;
  redactedBlocks: number;
  finalInTranscript: boolean;
  finalOnTerminal: boolean;
  thinkingOnTerminal: boolean;
  terminalBytes: number;
  transcriptFound: boolean;
  timedOut: boolean;
};

export async function interactiveClient(options: {
  command: string[];
  cwd: string;
  env: Record<string, string>;
  sessionId: string;
  finalText: string;
  expectedThinking: string;
  timeoutMs: number;
}): Promise<InteractiveClientResult> {
  const root = join(options.env.CLAUDE_CONFIG_DIR!, "projects");
  if (options.env.ANTHROPIC_API_KEY !== "synthetic-probe-client-only") {
    throw new Error("interactive fixture accepts only its isolated synthetic API key");
  }
  let tail = "", terminalBytes = 0, fixtureKeyAccepted = false;
  let requestedExit = false, confirmedExit = false;
  const proc = Bun.spawn(options.command, {
    cwd: options.cwd, env: { ...options.env, TERM: "xterm-256color" },
    terminal: {
      cols: 160, rows: 60, name: "xterm-256color",
      data(terminal, bytes) {
        terminalBytes += bytes.length;
        const text = Buffer.from(bytes).toString("utf8");
        tail = (tail + text).slice(-1_000_000);
        if (text.includes("\x1b[6n")) terminal.write("\x1b[1;1R");
        if (text.includes("\x1b[c")) terminal.write("\x1b[?1;2c");
        if (text.includes("\x1b]11;?")) terminal.write("\x1b]11;rgb:0000/0000/0000\x1b\\");
        if (text.includes("\x1b]10;?")) terminal.write("\x1b]10;rgb:ffff/ffff/ffff\x1b\\");
        const plain = tail.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, "").replace(/\s/g, "");
        if (!fixtureKeyAccepted && plain.includes("DoyouwanttousethisAPIkey?")) {
          fixtureKeyAccepted = true;
          terminal.write("\x1b[A\r");
        }
        if (requestedExit && !confirmedExit && plain.includes("PressCtrl-Dagaintoexit")) {
          confirmedExit = true;
          terminal.write("\x04");
        }
      },
    },
  });
  let transcript = "", timedOut = false;
  const deadline = Date.now() + options.timeoutMs;
  const readTranscript = async () => {
    if (!existsSync(root)) return;
    const files = await Array.fromAsync(new Bun.Glob(`**/${options.sessionId}.jsonl`).scan({ cwd: root, onlyFiles: true }));
    if (files.length > 1) throw new Error("isolated client wrote multiple session transcripts");
    if (files[0]) transcript = readFileSync(join(root, files[0]), "utf8");
  };
  const blocks = (): Obj[] => {
    const entries = new Map<string, Obj>();
    for (const line of transcript.split("\n")) {
      let entry: Obj;
      try { entry = JSON.parse(line); } catch { continue; }
      if (entry.type === "assistant" && entry.uuid) entries.set(entry.uuid, entry);
    }
    return [...entries.values()].flatMap(entry => entry.message?.content ?? []);
  };
  try {
    while (proc.exitCode === null && Date.now() < deadline) {
      writeFileSync(join(options.env.HOME!, "client-terminal.log"), tail, { mode: 0o600 });
      await readTranscript();
      const complete = blocks().some(block => block.type === "text" && block.text?.includes(options.finalText));
      if (complete && tail.includes(options.finalText) && !requestedExit) {
        // Ctrl-D exits the real interactive prompt, after its final answer was persisted and rendered.
        await Bun.sleep(250);
        proc.terminal!.write("\x04");
        requestedExit = true;
      }
      await Bun.sleep(100);
    }
    if (proc.exitCode === null) { timedOut = true; proc.kill("SIGTERM"); }
    await proc.exited;
    await readTranscript();
  } finally {
    if (proc.exitCode === null) proc.kill("SIGKILL");
    proc.terminal?.close();
  }
  const content = blocks();
  return {
    exitCode: proc.exitCode,
    thinking: content.filter(block => block.type === "thinking").map(block => block.thinking ?? "").join(""),
    redactedBlocks: content.filter(block => block.type === "redacted_thinking").length,
    finalInTranscript: content.some(block => block.type === "text" && block.text?.includes(options.finalText)),
    finalOnTerminal: tail.includes(options.finalText),
    thinkingOnTerminal: tail.includes(options.expectedThinking),
    terminalBytes, transcriptFound: transcript.length > 0, timedOut,
  };
}
