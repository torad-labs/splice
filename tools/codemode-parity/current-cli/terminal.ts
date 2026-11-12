import { createHash } from "node:crypto";
import { closeSync, mkdirSync, openSync, readFileSync, readlinkSync, readdirSync, writeFileSync, writeSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import xterm from "@xterm/headless";
import { nativeCompletion, nativeInterruption } from "./native-events";
import type { CliProcess, NativeCompletion, NativeHostExpectation } from "./types";
import { fileIdentity } from "./file-identity";
import { captureTerminalWarnings } from "./terminal-warnings";
import { nativeReviewCompletion } from "./review-native-events";

interface TerminalOptions {
  argv: string[];
  cwd: string;
  home: string;
  directory: string;
  expectedHostSha256: string;
  expectedCliSha256: string;
  hostExpectation: NativeHostExpectation;
  timeoutMs: number;
  sourceProfilePattern?: string;
  onAttested?: (identity: Omit<CliProcess, "exit">) => void;
  onHostAttested?: (identity: { pid: number; sha256: string }) => void;
  review?: { expectedFinalText: string };
  interruption?: { notification: string; resumePrompt: string; callId: string; ready(): boolean };
}

function descendants(pid: number): number[] {
  const pending = [pid], found = new Set<number>();
  while (pending.length && found.size < 128) {
    const current = pending.pop()!;
    try {
      for (const task of readdirSync(`/proc/${current}/task`)) {
        let children: string;
        try { children = readFileSync(`/proc/${current}/task/${task}/children`, "utf8"); } catch { continue; }
        for (const value of children.trim().split(/\s+/)) {
          const child = Number(value);
          if (child > 0 && !found.has(child)) { found.add(child); pending.push(child); }
        }
      }
    } catch { /* A child may exit between the native event and this ownership observation. */ }
  }
  return [...found];
}

/** A real PTY and terminal-query emulator; neither ANSI text nor quiet time establishes completion. */
export async function runTerminal(options: TerminalOptions): Promise<{
  completion: NativeCompletion; hostProcesses: Array<{ pid: number; sha256: string }>; cliProcess: CliProcess;
}> {
  if (process.platform !== "linux") throw Error("Current executable/child attestation requires Linux /proc");
  if (typeof Bun.Terminal !== "function") throw Error("A Bun.Terminal-compatible runtime is required");
  if (options.review && (options.interruption || options.hostExpectation.kind !== "required")) throw Error("Native review requires its own completed two-conversation case and a pinned host");
  const profilePattern = options.sourceProfilePattern;
  if (profilePattern && (resolve(profilePattern) !== profilePattern || !profilePattern.startsWith(dirname(resolve(options.directory)) + "/") || basename(profilePattern) !== "%p.profraw")) throw Error("Source profile output escaped its disposable scenario");
  mkdirSync(options.directory, { recursive: true, mode: 0o700 });
  const raw = openSync(join(options.directory, "terminal.raw"), "w", 0o600);
  const events = openSync(join(options.directory, "terminal-events.jsonl"), "w", 0o600);
  const emulator = new xterm.Terminal({ cols: 120, rows: 40, scrollback: 10000, allowProposedApi: true });
  const record = (kind: string, data: string | Uint8Array) => {
    const bytes = Buffer.from(data);
    writeSync(events, JSON.stringify({ kind, at_ms: Date.now(), raw_base64: bytes.toString("base64"), bytes: bytes.length }) + "\n");
  };
  let terminal: Bun.Terminal | undefined;
  emulator.onData(value => { if (terminal && !terminal.closed) { record("terminal_query_reply", value); terminal.write(value); } });
  terminal = new Bun.Terminal({
    cols: 120, rows: 40, name: "xterm-256color",
    data(_terminal, bytes) { writeSync(raw, bytes); record("pty_output", bytes); emulator.write(bytes); },
  });
  const environment: Record<string, string | undefined> = { ...process.env, CODEX_HOME: options.home, TERM: "xterm-256color" };
  // The owned provider has no auth and talks only to loopback; do not give the reference inherited API credentials.
  for (const name of ["OPENAI_API_KEY", "CODEX_API_KEY", "CODEX_INTERNAL_API_KEY", "OPENAI_BASE_URL", "CHATGPT_BASE_URL"]) delete environment[name];
  delete environment.LLVM_PROFILE_FILE;
  if (profilePattern) environment.LLVM_PROFILE_FILE = profilePattern;
  let child: Bun.Subprocess | undefined;
  let cliIdentity: Omit<CliProcess, "exit"> | undefined;
  let completion: NativeCompletion | null = null;
  let completedAt: number | undefined;
  let sentExit = false, confirmedExit = false;
  let interrupted = false, resumed = false;
  const hosts = new Map<number, string>();
  const observedHosts = new Set<number>();
  const started = Date.now();
  const screen = () => {
    const buffer = emulator.buffer.active;
    return Array.from({ length: buffer.length }, (_, index) => buffer.getLine(index)?.translateToString(true) ?? "").join("\n");
  };
  try {
    child = Bun.spawn(options.argv, { cwd: options.cwd, env: environment, terminal });
    const stat = readFileSync(`/proc/${child.pid}/stat`, "utf8");
    const startTicks = stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19];
    const sha256 = (await fileIdentity(`/proc/${child.pid}/exe`)).sha256;
    if (!startTicks || !/^[0-9]+$/.test(startTicks) || sha256 !== options.expectedCliSha256) throw Error("Actual interactive CLI process identity differs");
    cliIdentity = { pid: child.pid, sha256, startTicks };
    record("cli_process_attested", JSON.stringify(cliIdentity));
    options.onAttested?.(cliIdentity);
    while (child.exitCode === null) {
      for (const pid of descendants(child.pid)) {
        if (hosts.has(pid)) continue;
        let attestedHost: { pid: number; sha256: string } | undefined;
        try {
          if (!basename(readlinkSync(`/proc/${pid}/exe`)).startsWith("codex-code-mode-host")) continue;
          observedHosts.add(pid);
          const sha256 = createHash("sha256").update(readFileSync(`/proc/${pid}/exe`)).digest("hex");
          if (sha256 === options.expectedHostSha256) { hosts.set(pid, sha256); attestedHost = { pid, sha256 }; }
        } catch { /* Observation is retried while the controlled client owns its child. */ }
        if (attestedHost) { record("native_host_attested", JSON.stringify(attestedHost)); options.onHostAttested?.(attestedHost); }
      }
      if (options.hostExpectation.kind === "disabled" && observedHosts.size) throw Error("Controlled disabled-host case exposed a native host child");
      if (options.interruption && !completedAt) {
        const state = nativeInterruption(options.home, options.interruption.callId, options.interruption.notification);
        if (!interrupted && state?.notified && hosts.size && options.interruption.ready()) {
          record("interrupt_after_native_notification", "\x03"); terminal.write("\x03"); interrupted = true;
        } else if (interrupted && !resumed && state?.aborted) {
          record("resume_after_native_abort", options.interruption.resumePrompt);
          terminal.write(options.interruption.resumePrompt);
          // Let the real terminal's paste detector finish before sending its submit key.
          await Bun.sleep(250);
          record("submit_resume_after_native_abort", "\r"); terminal.write("\r"); resumed = true;
        }
      }
      if (!completedAt) {
        completion = options.review ? nativeReviewCompletion(options.home, options.cwd, options.review.expectedFinalText) : nativeCompletion(options.home);
        if (completion) {
          completedAt = Date.now();
          await Bun.sleep(150);
          if (options.hostExpectation.kind === "disabled") {
            await captureTerminalWarnings({ directory: options.directory, pid: child.pid, completion,
              write: value => terminal!.write(value), record, alive: () => child!.exitCode === null,
              flush: () => new Promise<void>(resolve => emulator.write("", resolve)),
              rows: () => { const buffer = emulator.buffer.active; return Array.from({ length: 40 }, (_, row) => {
                const line = buffer.getLine(buffer.baseY + row); return { text: line?.translateToString(true) ?? "", wrapped: line?.isWrapped ?? false };
              }); } });
            completedAt = Date.now();
          }
          if (!options.review && child.exitCode === null && !terminal.closed) { record("exit_after_native_completion", "\x04"); terminal.write("\x04"); sentExit = true; }
        } else if (Date.now() - started > options.timeoutMs) throw Error("No authentic completed native turn before timeout");
      } else {
        if (sentExit && !confirmedExit && screen().includes("Press Ctrl-D again to exit")) {
          record("confirmed_exit_after_native_completion", "\x04"); terminal.write("\x04"); confirmedExit = true;
        }
        if (Date.now() - completedAt > 10000) throw Error("Interactive client did not exit after native completion");
      }
      await Bun.sleep(50);
    }
    const exit = await child.exited;
    if (!completion && options.review) completion = nativeReviewCompletion(options.home, options.cwd, options.review.expectedFinalText);
    if (!completion || exit !== 0) throw Error(`Client exit ${exit} without a successful native turn`);
    if (options.interruption && (!interrupted || !resumed)) throw Error("The PTY did not interrupt and resume the owned native turn");
    if (options.hostExpectation.kind === "required" && !hosts.size) throw Error("The actual client did not expose a child executing the pinned native host bytes");
    await new Promise<void>(resolve => emulator.write("", resolve));
    writeFileSync(join(options.directory, "terminal-screen.txt"), screen() + "\n", { mode: 0o600 });
    const buffer = emulator.buffer.active;
    writeFileSync(join(options.directory, "terminal-rows.json"), JSON.stringify(Array.from({ length: buffer.length }, (_, index) => {
      const line = buffer.getLine(index); return { text: line?.translateToString(true) ?? "", wrapped: line?.isWrapped ?? false };
    })) + "\n", { mode: 0o600 });
    return { completion, hostProcesses: [...hosts].map(([pid, sha256]) => ({ pid, sha256 })), cliProcess: { ...cliIdentity, exit } };
  } finally {
    if (child && child.exitCode === null) {
      child.kill("SIGTERM");
      await Promise.race([child.exited, Bun.sleep(2000)]);
      if (child.exitCode === null) { child.kill("SIGKILL"); await child.exited; }
    }
    await new Promise<void>(resolve => emulator.write("", resolve));
    writeFileSync(join(options.directory, "process.json"), JSON.stringify({ ...cliIdentity, pid: child?.pid, exit: child?.exitCode, signal: child?.signalCode }) + "\n", { mode: 0o600 });
    writeFileSync(join(options.directory, "terminal-screen.txt"), screen() + "\n", { mode: 0o600 });
    terminal.close(); emulator.dispose(); closeSync(raw); closeSync(events);
  }
}
