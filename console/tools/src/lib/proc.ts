// The one place a console tool starts a process. Bun.spawnSync and not node:child_process: the
// runtime's own spawn, no shell anywhere (a command is an argv array, never a string), both streams
// captured on every outcome — the exit-gate's M1-49 rule — and a binary that cannot start is a
// named non-zero code, never a null read as success.
export interface Ran { code: number; out: string }

/** Run `argv` to completion; `out` is stdout then stderr, and `code` is 127 when it never started. */
export function run(argv: string[], cwd?: string): Ran {
  try {
    const r = Bun.spawnSync(argv, { cwd, stdout: 'pipe', stderr: 'pipe', stdin: 'ignore' });
    return { code: r.exitCode ?? (r.signalCode === null ? 1 : 128), out: `${r.stdout.toString()}${r.stderr.toString()}` };
  } catch (error) {
    return { code: 127, out: `${argv[0]}: ${(error as Error).message}` };
  }
}

/** Run with the streams inherited — for a build whose progress the operator should see. */
export function runLoud(argv: string[], cwd?: string): number {
  try {
    return Bun.spawnSync(argv, { cwd, stdout: 'inherit', stderr: 'inherit', stdin: 'ignore' }).exitCode ?? 1;
  } catch (error) {
    console.error(`${argv[0]}: ${(error as Error).message}`);
    return 127;
  }
}

/** The tools' own binary, for a tool that re-enters itself (a selftest's child run). */
export const SELF_RUNTIME: string = process.execPath;
