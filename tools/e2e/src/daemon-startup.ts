import { closeSync, fstatSync, openSync, readFileSync, readSync } from "node:fs";

// Packaged JVMs boot alongside module tests on CI. Twenty seconds mistakes contention for failure.
export const DAEMON_READY_SECONDS = 60;
const LOG_TAIL_LINES = 60;
const LOG_TAIL_BYTES = 64 * 1024;

export function bootLogTail(text: string): string {
  const bounded = Buffer.from(text.slice(-LOG_TAIL_BYTES)).subarray(-LOG_TAIL_BYTES).toString("utf8");
  return bounded.trimEnd().split(/\r?\n/).slice(-LOG_TAIL_LINES).join("\n");
}

/** Read only the bounded end, so a noisy failed boot cannot flood CI or exhaust harness memory. */
export function readBootLog(path: string): string {
  let fd: number | undefined;
  try {
    fd = openSync(path, "r");
    const size = fstatSync(fd).size;
    const start = Math.max(0, size - LOG_TAIL_BYTES);
    const bytes = Buffer.alloc(Math.min(size, LOG_TAIL_BYTES));
    const count = readSync(fd, bytes, 0, bytes.length, start);
    let text = bytes.subarray(0, count).toString("utf8");
    if (start > 0) {
      const newline = text.indexOf("\n");
      if (newline < 0) return "[boot log line exceeded byte limit; withheld]";
      text = text.slice(newline + 1);
    }
    return bootLogTail(text);
  } catch (error) {
    return "[boot log unavailable: " + (error as NodeJS.ErrnoException).code + "]";
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
}

export function bootFailure(message: string, elapsedSeconds: number, log: string): string {
  return `${message} after ${elapsedSeconds.toFixed(1)} seconds\nDaemon boot log (last ${LOG_TAIL_LINES} lines):\n${bootLogTail(log) || "[empty boot log]"}`;
}

/** Billed startup can load real auth. Scrub every credential string and the management bearer. */
export function redactBootCredentials(text: string, credentials: unknown, bearer: string): string {
  const secrets = new Set<string>([bearer]);
  const visit = (value: unknown): void => {
    if (typeof value === "string" && value.length > 0) secrets.add(value);
    else if (Array.isArray(value)) value.forEach(visit);
    else if (value && typeof value === "object") Object.values(value).forEach(visit);
  };
  visit(credentials);
  let safe = text;
  for (const secret of [...secrets].filter(Boolean).sort((a, b) => b.length - a.length)) {
    safe = safe.replaceAll(secret, "[redacted]");
    safe = safe.replaceAll(JSON.stringify(secret).slice(1, -1), "[redacted]");
  }
  return safe;
}

export function readCredentialBootLog(logPath: string, authPath: string, bearer: string): string {
  try {
    const credentials: unknown = JSON.parse(readFileSync(authPath, "utf8"));
    return redactBootCredentials(readBootLog(logPath), credentials, bearer);
  } catch {
    // Without the credential denominator, printing any raw line would violate the auth boundary.
    return "[boot log withheld: credential redaction unavailable]";
  }
}
