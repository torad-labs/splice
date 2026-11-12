import { createHash } from "node:crypto";
export async function capturedCommand(argv: string[], cwd: string, input?: string, timeoutMs = 60_000, environment?: Record<string, string>): Promise<{ argv: string[]; pid: number; stdout: string; stderr: string; exitCode: number; stdin?: { sha256: string; bytes: number } }> {
  const child = Bun.spawn(argv, { cwd, stdin: input === undefined ? "ignore" : new Blob([input]), stdout: "pipe", stderr: "pipe", ...(environment ? { env: environment } : {}) });
  const timeout = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
  try {
    const [stdout, stderr, exitCode] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
    return { argv, pid: child.pid, stdout, stderr, exitCode, ...(input === undefined ? {} : { stdin: { sha256: createHash("sha256").update(input).digest("hex"), bytes: Buffer.byteLength(input) } }) };
  } finally { clearTimeout(timeout); }
}
