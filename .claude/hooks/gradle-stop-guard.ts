#!/usr/bin/env bun
/**
 * Stop-hook gradle arm (#924 0c, LIGHT). A turn must not END on a red Kotlin compile.
 *
 * FAIL-OPEN by construction: this blocks ONLY on a definitive main-source compile failure. No .kt
 * change in the working tree, a self-timeout (cold daemon), a missing JDK 21, or any gradle-infra
 * hiccup all PASS silently — a Stop hook that spuriously wedges a turn is worse than none. The full
 * ./gradlew check (detekt + the 1000-stream load test) is impractical per-Stop; that lives in
 * `bash checks/gate.sh` and the gateway-gradle CI job. This arm catches only the one thing you must
 * never end a turn on: code that does not compile.
 *
 * THE FAIL-OPEN CHOICE IS THE DESIGN AND IS STATED HERE RATHER THAN IMPLIED: this hook is the THIRD
 * of three instruments — the write-time walls and `checks/gate.sh` are the other two — so a
 * failed-open here costs a delayed signal, never an unguarded tree. A hook that is the ONLY
 * enforcement of its rule must fail closed instead; this one is not.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve as pathResolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = pathResolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const GRADLE_DIR = join(ROOT, "gateway"); // the gradlew wrapper lives here, not at repo root
const JDK21_DEFAULT = "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home";
const GRADLE_TIMEOUT_S = 25; // self-limit under the 30s hook budget: cold compile fail-opens, not hard-killed

/** Python's json.dumps() defaults: `", "` / `": "` separators and ensure_ascii=True. The reason
 *  carries an em-dash, so a plain JSON.stringify would emit different bytes for the same string. */
function pyJsonString(value: string): string {
  const parts: string[] = ['"'];
  for (let i = 0; i < value.length; i += 1) {
    const ch = value[i];
    const code = value.charCodeAt(i);
    if (ch === '"') parts.push('\\"');
    else if (ch === "\\") parts.push("\\\\");
    else if (ch === "\n") parts.push("\\n");
    else if (ch === "\r") parts.push("\\r");
    else if (ch === "\t") parts.push("\\t");
    else if (code < 0x20 || code > 0x7e) parts.push("\\u" + code.toString(16).padStart(4, "0"));
    else parts.push(ch);
  }
  parts.push('"');
  return parts.join("");
}

function pyJsonDumps(value: unknown): string {
  if (value === null || value === undefined) return "null";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") return String(value);
  if (typeof value === "string") return pyJsonString(value);
  if (Array.isArray(value)) return "[" + value.map(pyJsonDumps).join(", ") + "]";
  if (typeof value === "object") {
    const parts = Object.entries(value as Record<string, unknown>).map(
      ([key, item]) => `${pyJsonString(key)}: ${pyJsonDumps(item)}`,
    );
    return "{" + parts.join(", ") + "}";
  }
  return "null";
}

function passthrough(): never {
  process.exit(0);
}

function gitLines(args: string[]): string | null {
  const proc = spawnSync("git", ["-C", ROOT, ...args], {
    encoding: "utf8",
    timeout: 5000,
    maxBuffer: 64 * 1024 * 1024,
  });
  if (proc.error) return null;
  return (proc.stdout || "").trim();
}

function ktChanged(): boolean {
  try {
    const tracked = gitLines(["diff", "--name-only", "HEAD", "--", "*.kt"]);
    const untracked = gitLines(["ls-files", "--others", "--exclude-standard", "--", "*.kt"]);
    if (tracked === null || untracked === null) return false;
    return Boolean(tracked || untracked);
  } catch {
    return false;
  }
}

function main(): void {
  try {
    readFileSync(0, "utf8"); // drain the Stop-hook payload (unused)
  } catch {
    // a hook that cannot read its payload is not a reason to block a turn
  }

  if (!ktChanged()) passthrough();

  // Prefer the KNOWN JDK 21 over an inherited JAVA_HOME: a shell exporting JDK 26 exists on
  // disk (so no fail-open) but cannot resolve the languageVersion=21 toolchain, and gradle's
  // "Cannot find a Java installation ... BUILD FAILED" then read as a red tree (two false
  // blocks with EMPTY error lists, 2026-07-18).
  const envHome = process.env.JAVA_HOME ?? "";
  const javaHome = existsSync(join(JDK21_DEFAULT, "bin", "java")) ? JDK21_DEFAULT : envHome;
  if (!javaHome || !existsSync(join(javaHome, "bin", "java"))) passthrough(); // no usable JDK
  if (!existsSync(join(GRADLE_DIR, "gradlew"))) passthrough(); // no wrapper -> fail-open

  const proc = spawnSync("./gradlew", ["-q", "compileKotlin"], {
    cwd: GRADLE_DIR,
    env: { ...process.env, JAVA_HOME: javaHome },
    encoding: "utf8",
    timeout: GRADLE_TIMEOUT_S * 1000,
    maxBuffer: 256 * 1024 * 1024,
  });
  // self-timeout / launch failure -> fail-open (the gate + CI still catch it)
  if (proc.error || proc.status === null) passthrough();
  if (proc.status === 0) passthrough();

  const out = (proc.stdout || "") + (proc.stderr || "");
  const errs = out.split("\n").filter((ln) => ln.startsWith("e: "));
  if (errs.length === 0) {
    // Toolchain/dependency/config failures also print BUILD FAILED with zero `e:` lines;
    // blocking on them contradicts the fail-open doctrine above. Definitive compile errors
    // ALWAYS carry `e:` lines — that is the only evidence worth blocking a turn on.
    passthrough();
  }

  const reason =
    "Kotlin main source does not compile — a turn must not end on a red tree (#924 0c, light " +
    "Stop arm). Fix these, then `bash checks/gate.sh` for the full gate:\n" +
    errs.slice(0, 6).join("\n");
  process.stdout.write(pyJsonDumps({ decision: "block", reason }) + "\n");
  process.exit(0);
}

main();
