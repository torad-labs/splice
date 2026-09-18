/** Generic hook dispatcher for torad-fleet.
 *
 *  Stolen from kandi-main / qgre-agent .claude/hooks/orchestrator/runner.py, with
 *  TWO deliberate divergences documented in checks/HARNESS-REVIEW.md:
 *
 *    1. FAIL-CLOSED security modules (KMP-MIGRATION-PLAN.md §2.6, R1). The inherited
 *       runner swallows every module exception (fail-open) — correct for STYLE rules,
 *       wrong for a SECURITY gate: a crashing scan-clean module would silently let a
 *       child_process / eval( edit through. A module that declares `FAIL_CLOSED = True`
 *       and raises now emits a block instead of being swallowed.
 *
 *    2. Lifecycle-aware block emit (§2.5 Fact 4). PreToolUse blocks emit exit-2 +
 *       stderr (the reliable deny per the Claude Code docs — JSON is only parsed on
 *       exit 0, so the legacy {"decision":"block"} would be ignored under exit 2).
 *       Stop / SubagentStop emit the {"decision":"block"} JSON (exit 0) which forces
 *       the agent to continue. PostToolUse cannot block; inject becomes additionalContext.
 *
 *  Each lifecycle entry script calls dispatch("<lifecycle>"). The dispatcher globs
 *  modules/<lifecycle>/*.ts (skipping `_`-prefixed = disabled, and index.ts),
 *  runs each module's applies()/run(), and applies the state machine: block stops the
 *  chain and emits; inject accumulates and emits at end; warn writes to stderr.
 *
 *  ONE STRUCTURAL DIFFERENCE FROM THE ORIGINAL, and it is forced rather than chosen:
 *  Python loaded each module in-process with importlib and the whole runner was
 *  synchronous. TypeScript's equivalent loader is dynamic `import()`, which is ASYNC,
 *  so dispatch() is async and the lifecycle entrypoints await it. That is the entire
 *  cost of the language change here — the loader, the state machine and the
 *  fail-closed flag are otherwise the same design.
 *
 *  THE MODULE CONTRACT: a module exports `applies(data): boolean` and
 *  `run(data): HookResult | null`, and MAY export `FAIL_CLOSED = true` (crash blocks
 *  the tool call) or `BYPASS_EXEMPTION = true` (run even on generated/vendored paths).
 */
import {
  closeSync,
  existsSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  statSync,
  writeSync,
} from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { HookResult, isHookResult } from "./result";
import { filePathOf, isPathExempt } from "../lib/tool_input";

const HERE = dirname(fileURLToPath(import.meta.url));
const HOOKS_ROOT = dirname(HERE);
const MODULES_ROOT = join(HOOKS_ROOT, "modules");

// Renamed from kandi's KANDIBUDDY_HOOKS — the per-command kill switch.
const KILL_SWITCH_ENV = "OC_WORKSPACES_HOOKS";
const KILL_SWITCH_OFF = "off";
// Operator on/off switch — a FILE flag alongside the launch-time env var above, so all
// write-time hooks can be toggled live. Its mere existence disables every write-time hook;
// deleting it re-enables them. Fail-safe: a stat error falls through to ENFORCING
// (never silently off).
const KILL_SWITCH_FILE = join(dirname(HOOKS_ROOT), "state", "enforcement-disabled");

const CHAIN_BUDGET_SECONDS = 9.0;
const DISABLED_PREFIX = "_";

// The only lifecycle whose block must use exit-2 (the reliable PreToolUse deny).
const PRETOOL_LIFECYCLE = "pretooluse";

// HARDEN-1 (qgre runner lineage): durable module-crash / stdout-pollution record.
// Lives in gitignored runtime state beside deploy-gate.json; logging is fail-open —
// a log-write failure must never break the dispatch it observes.
const ERROR_LOG = join(dirname(HOOKS_ROOT), "state", "hook-module-errors.log");

function hooksDisabled(): boolean {
  if (process.env[KILL_SWITCH_ENV] === KILL_SWITCH_OFF) return true;
  try {
    return existsSync(KILL_SWITCH_FILE);
  } catch {
    return false;
  }
}

function appendErrorLog(entry: string): void {
  try {
    // Python made the parent, opened in append mode, and fsync'd. The fsync is the part worth
    // keeping: this log exists to survive a crash of the very process writing it.
    mkdirSync(dirname(ERROR_LOG), { recursive: true });
    const fd = openSync(ERROR_LOG, "a");
    try {
      writeSync(fd, entry);
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
  } catch {
    // never let observability break the runner
  }
}

function stamp(): string {
  return new Date().toISOString().replace(/\.\d{3}Z$/, "Z");
}

function logModuleError(modulePath: string, lifecycle: string, exc: unknown): void {
  const trace = exc instanceof Error ? (exc.stack ?? String(exc)) : String(exc);
  appendErrorLog(
    `[${stamp()}] lifecycle=${lifecycle} phase=module-crash module=${moduleStem(modulePath)} ` +
      `path=${modulePath}\n${trace}` + "-".repeat(80) + "\n",
  );
}

/** A module (or a subprocess it leaked) wrote to stdout — the harness JSON channel. The redirect
 *  in runModule already silenced it structurally; this records the offender so the stray print
 *  gets fixed instead of resurfacing as an invalid-hook-output parse error. */
function logStdoutPollution(modulePath: string, lifecycle: string, text: string): void {
  appendErrorLog(
    `[${stamp()}] lifecycle=${lifecycle} phase=stdout-pollution module=${moduleStem(modulePath)} ` +
      `(${text.length} bytes silenced):\n${text.slice(0, 2000)}\n` + "-".repeat(80) + "\n",
  );
}

const moduleStem = (p: string): string => (p.split("/").pop() ?? "").replace(/\.ts$/, "");

export async function dispatch(lifecycle: string): Promise<void> {
  if (hooksDisabled()) return;
  let data: unknown;
  try {
    data = JSON.parse(readFileSync(0, "utf8") || "");
  } catch {
    return;
  }
  if (data === null || typeof data !== "object" || Array.isArray(data)) return;
  const event = data as Record<string, unknown>;

  const pathExempt = isPathExempt(filePathOf(event));

  const modulesDir = join(MODULES_ROOT, lifecycle);
  if (!existsSync(modulesDir) || !statSync(modulesDir).isDirectory()) return;

  const chainStart = performance.now() / 1000;
  const injectPayloads: string[] = [];

  for (const modulePath of sortedModuleFiles(modulesDir)) {
    if (performance.now() / 1000 - chainStart > CHAIN_BUDGET_SECONDS) continue;

    const result = await runModule(modulePath, event, pathExempt, lifecycle);
    if (result === null) continue;

    if (result.kind === "block") {
      emitBlock(lifecycle, result.payload);
      return;
    }
    if (result.kind === "inject") {
      injectPayloads.push(result.payload);
      continue;
    }
    if (result.kind === "warn") {
      process.stderr.write(result.payload.replace(/\s+$/, "") + "\n");
      continue;
    }
  }

  if (injectPayloads.length > 0) emitInject(lifecycle, injectPayloads.join("\n\n---\n\n"));
}

function emitBlock(lifecycle: string, reason: string): never | void {
  if (lifecycle === PRETOOL_LIFECYCLE) {
    // exit 2 feeds stderr back to Claude and reliably denies the tool call.
    process.stderr.write(reason.replace(/\s+$/, "") + "\n");
    process.exit(2);
  }
  // Stop / SubagentStop (forces continue) / PostToolUse (surfaces feedback).
  process.stdout.write(pyJsonDumps({ decision: "block", reason }));
}

function emitInject(lifecycle: string, text: string): void {
  if (lifecycle === "posttooluse") {
    process.stdout.write(
      pyJsonDumps({
        hookSpecificOutput: { hookEventName: "PostToolUse", additionalContext: text },
      }),
    );
    return;
  }
  process.stdout.write(text);
}

function sortedModuleFiles(modulesDir: string): string[] {
  const candidates = [...new Bun.Glob("*.ts").scanSync({ cwd: modulesDir })].sort();
  return candidates
    .filter((name) => !name.startsWith(DISABLED_PREFIX) && name !== "index.ts")
    .map((name) => join(modulesDir, name));
}

/** Python's json.dumps() defaults — `", "` / `": "` separators and ensure_ascii=True. The block
 *  reasons carry an em-dash and a section sign, so a plain JSON.stringify would emit different
 *  bytes for the same string. Written as a serializer walk so a reason containing `,` or `:` in a
 *  string is not corrupted by a re-spacing pass. */
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

/** The port of Python's `with contextlib.redirect_stdout(captured)`. Python redirected the
 *  process-wide sys.stdout for the load/applies/run span so a module's stray print could not
 *  corrupt the harness JSON channel; this swaps process.stdout.write for the same span. The
 *  hook's OWN emits all happen outside the window, so they are unaffected. */
async function withCapturedStdout<T>(body: () => Promise<T>): Promise<{ value: T; captured: string }> {
  const original = process.stdout.write.bind(process.stdout);
  const chunks: string[] = [];
  (process.stdout as unknown as { write: unknown }).write = (
    chunk: string | Uint8Array,
    encoding?: unknown,
    cb?: unknown,
  ): boolean => {
    chunks.push(typeof chunk === "string" ? chunk : Buffer.from(chunk).toString("utf8"));
    if (typeof encoding === "function") (encoding as () => void)();
    else if (typeof cb === "function") (cb as () => void)();
    return true;
  };
  try {
    const value = await body();
    return { value, captured: chunks.join("") };
  } finally {
    (process.stdout as unknown as { write: unknown }).write = original;
  }
}

interface HookModule {
  applies?: (data: Record<string, unknown>) => boolean;
  run?: (data: Record<string, unknown>) => HookResult | null | undefined;
  FAIL_CLOSED?: boolean;
  BYPASS_EXEMPTION?: boolean;
}

async function runModule(
  modulePath: string,
  data: Record<string, unknown>,
  pathExempt: boolean,
  lifecycle: string,
): Promise<HookResult | null> {
  let module: HookModule | null = null;
  const outcome = await withCapturedStdout(async () => {
    try {
      module = (await import(modulePath)) as HookModule;

      // On exempt paths (generated/vendored), only run modules that explicitly
      // opt in via BYPASS_EXEMPTION = true (e.g. the Bash-command guards).
      if (pathExempt && !module.BYPASS_EXEMPTION) return null;

      if (typeof module.applies === "function" && !module.applies(data)) return null;
      if (typeof module.run !== "function") return null;

      const result = module.run(data);
      if (result === null || result === undefined) return null;
      // The port of `isinstance(result, HookResult)`: a module returning a bare object is
      // ignored rather than trusted.
      if (!isHookResult(result)) return null;
      return result;
    } catch (exc) {
      logModuleError(modulePath, lifecycle, exc);
      // FAIL-CLOSED (R1): a security module that crashes blocks; style modules
      // (no FAIL_CLOSED flag) stay fail-open exactly as the inherited runner did.
      if (module !== null && module.FAIL_CLOSED) {
        const trace = exc instanceof Error ? (exc.stack ?? String(exc)) : String(exc);
        return {
          kind: "block" as const,
          payload:
            `§fail-closed — security hook ${moduleStem(modulePath)} crashed; blocking the ` +
            `tool call rather than letting it through.\n\n${trace}` +
            `\ntraceback_log: ${ERROR_LOG}`,
          moduleName: moduleStem(modulePath),
        };
      }
      return null;
    }
  });
  if (outcome.captured) logStdoutPollution(modulePath, lifecycle, outcome.captured);
  return outcome.value;
}
