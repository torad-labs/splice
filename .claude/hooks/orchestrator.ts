#!/usr/bin/env bun
/**
 * splice hook orchestrator — every write-time policy routes to ast-grep rules.
 *
 * ONE orchestrator, ZERO per-rule Python (operator design constraint, 2026-07-13):
 * policy lives only in quality/rules/console/*.yml. This file owns routing, not rules.
 *
 *   PreToolUse (Write|Edit|MultiEdit)
 *       1. Compute the file content AS IT WOULD EXIST after the tool call
 *          (Write payload; Edit/MultiEdit applied to the on-disk file).
 *       2. Mirror it to <tmp>/<repo-relative-path> and run
 *          `ast-grep scan --config <repo>/sgconfig.yml <relpath>` with cwd=<tmp>,
 *          so each rule's files:/ignores: globs bind exactly as they do at the
 *          gate (globs resolve against the path as scanned; verified 2026-07-13
 *          on ast-grep 0.44.0).
 *       3. severity:error matches block the call; anything lower goes to stderr.
 *
 *   Stop / SubagentStop
 *       `ast-grep scan` over the working tree; error findings block the stop.
 *
 * The SAME rules run at the gate (`npm run gate:rules`) and in CI — a weakened
 * hook still fails the build (same-checker-twice).
 *
 * FAILURE POLICY, STATED PER LIFECYCLE BECAUSE THE TWO HALVES DIFFER ON PURPOSE:
 *   PreToolUse fails CLOSED — a broken wall must not silently wave writes through;
 *   the block names this file and the error log.
 *   Stop fails OPEN with a loud stderr warning — session end must never wedge on
 *   scan infrastructure; the write-time wall and the gate are the backstops.
 * The asymmetry is the design, not an oversight: PreToolUse is the only
 * enforcement of the walls at write time, while Stop is the third of three.
 *
 * Deliberate rule exceptions go inline: `// ast-grep-ignore: <rule-id>` plus a
 * justification; ast-grep honors these in both the hook and the gate.
 */
import { spawnSync } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve as pathResolve } from "node:path";
import { fileURLToPath } from "node:url";

// SPLICE_HOOK_ROOT exists for the hermetic test harness only. Redirecting it to
// dodge policy is visible, auditable, and caught by the gate re-running the
// same rules on the real tree.
const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = process.env.SPLICE_HOOK_ROOT || pathResolve(HERE, "..", "..");
const SGCONFIG = join(ROOT, "sgconfig.yml");
const ERROR_LOG = join(HERE, "log", "orchestrator_errors.log");

const WRITE_TOOLS = ["Write", "Edit", "MultiEdit"];
const SCAN_TIMEOUT = 20;

interface Match {
  severity?: string;
  ruleId?: string;
  file?: string;
  message?: string;
  note?: string;
  range?: { start?: { line?: number } };
}

/** Python's Path.resolve(): symlinks resolved in the EXISTING prefix, the rest normalised, and
 *  no error when the path does not exist yet — which is the normal case here, since a Write's
 *  target has not been created. fs.realpathSync would throw ENOENT on exactly that case. */
function pyResolve(p: string): string {
  const abs = pathResolve(p);
  const parts = abs.split("/").filter((s) => s !== "");
  let existing = "/";
  let index = 0;
  while (index < parts.length) {
    const candidate = join(existing, parts[index]);
    if (!existsSync(candidate)) break;
    existing = candidate;
    index += 1;
  }
  let real = existing;
  try {
    real = realpathSync(existing);
  } catch {
    real = existing;
  }
  return [real.replace(/\/$/, ""), ...parts.slice(index)].join("/");
}

/** Python's subprocess.run(..., timeout=...) raising: any failure to RUN the scanner is an
 *  exception here, which is what routes PreToolUse to its fail-closed branch. */
function scanAstGrep(targets: string[], cwd: string): Match[] {
  const proc = spawnSync("ast-grep", ["scan", "--config", SGCONFIG, "--json=compact", ...targets], {
    cwd,
    encoding: "utf8",
    timeout: SCAN_TIMEOUT * 1000,
    maxBuffer: 256 * 1024 * 1024,
  });
  if (proc.error) throw new Error(String(proc.error));
  const code = proc.status;
  // ast-grep exits 0 = clean, 1 = findings reported; anything else is infra failure.
  if (code !== 0 && code !== 1) {
    throw new Error(`ast-grep exit ${code}: ${(proc.stderr || "").trim().slice(0, 400)}`);
  }
  const out = (proc.stdout || "").trim();
  if (!out) return [];
  const matches = JSON.parse(out) as unknown;
  return Array.isArray(matches) ? (matches as Match[]) : [];
}

function proposedContent(tool: string, toolInput: Record<string, unknown>, path: string): string | null {
  if (tool === "Write") return String(toolInput.content ?? "");
  const edits = tool === "MultiEdit" ? toolInput.edits : [toolInput];
  if (!Array.isArray(edits)) return null;
  let content: string;
  try {
    content = readFileSync(path, "utf8");
  } catch {
    // No readable base file (Claude Code will reject the Edit anyway) —
    // scan the raw replacement text so shape violations still surface.
    return edits
      .filter((e) => e !== null && typeof e === "object")
      .map((e) => String((e as Record<string, unknown>).new_string ?? ""))
      .join("\n");
  }
  for (const edit of edits) {
    if (edit === null || typeof edit !== "object") continue;
    const e = edit as Record<string, unknown>;
    const oldS = String(e.old_string ?? "");
    const newS = String(e.new_string ?? "");
    if (!oldS) continue;
    // Python's str.replace(old, new, 1) replaces the FIRST occurrence; JS String.replace with
    // a string pattern does the same. Without the count, Python replaces every occurrence.
    content = e.replace_all ? content.split(oldS).join(newS) : content.replace(oldS, newS);
  }
  return content;
}

/** Scan proposed content at its repo-relative path so files:/ignores: bind. */
function scanMirrored(rel: string, content: string): Match[] {
  if (!existsSync(SGCONFIG)) throw new Error(`missing ${SGCONFIG}`);
  const tmp = mkdtempSync(join(tmpdir(), "splice-walls-"));
  try {
    const target = join(tmp, rel);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content, "utf8");
    return scanAstGrep([rel], tmp);
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

function scanTree(): Match[] {
  if (!existsSync(SGCONFIG)) throw new Error(`missing ${SGCONFIG}`);
  return scanAstGrep([], ROOT);
}

function formatFindings(header: string, matches: Match[], cap = 15): string {
  const lines = [`${header}: ${matches.length} finding(s)`, ""];
  for (const match of matches.slice(0, cap)) {
    const line = (match.range?.start?.line ?? 0) + 1;
    lines.push(`  ${match.ruleId ?? "?"}  ${match.file ?? "?"}:${line}`);
    const message = (match.message ?? "").trim();
    if (message) lines.push(`    ${message}`);
    const note = (match.note ?? "").trim();
    if (note) {
      for (const noteLine of note.split("\n")) {
        if (noteLine.trim()) lines.push(`    | ${noteLine}`);
      }
    }
  }
  if (matches.length > cap) lines.push(`  … and ${matches.length - cap} more`);
  return lines.join("\n");
}

/** Python's json.dumps() with its DEFAULTS, which differ from JSON.stringify in two ways that
 *  both show up in this hook's output.
 *
 *  SEPARATORS: Python writes `", "` and `": "`, JS writes `","` and `":"`. A consumer parsing
 *  JSON cannot tell, but a consumer grepping the raw stdout can, and this hook's stdout is a
 *  protocol channel.
 *
 *  ensure_ascii=True: every non-ASCII character becomes a \\uXXXX escape. The block messages
 *  carry `…` (the finding-cap line) and `§`, so a port that let JSON.stringify emit the literal
 *  characters would produce different bytes for the same reason string.
 *
 *  Spacing is done by a real serializer walk rather than by re-spacing the compact output: a
 *  reason string may itself contain `,` or `:`, and a string-level rewrite would corrupt it. */
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
    else if (ch === "\b") parts.push("\\b");
    else if (ch === "\f") parts.push("\\f");
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

function emitBlock(reason: string): void {
  process.stdout.write(pyJsonDumps({ decision: "block", reason }));
}

function logError(text: string): void {
  try {
    mkdirSync(dirname(ERROR_LOG), { recursive: true });
    const stamp = new Date().toISOString();
    writeFileSync(ERROR_LOG, `[${stamp}]\n${text}\n${"-".repeat(80)}\n`, { flag: "a", encoding: "utf8" });
  } catch {
    // never let observability break the hook
  }
}

function pretooluse(data: Record<string, unknown>): number {
  const tool = data.tool_name;
  if (typeof tool !== "string" || !WRITE_TOOLS.includes(tool)) return 0;
  const toolInput = (data.tool_input ?? {}) as Record<string, unknown>;
  const filePath = String(toolInput.file_path ?? "");
  if (!filePath) return 0;

  const abs = filePath.startsWith("/") ? filePath : join(String(data.cwd ?? ROOT), filePath);
  const resolved = pyResolve(abs);
  const rootResolved = pyResolve(ROOT);
  if (resolved !== rootResolved && !resolved.startsWith(rootResolved + "/")) {
    return 0; // outside the repo — not this wall's jurisdiction
  }
  const rel = resolved.slice(rootResolved.length + 1);

  const content = proposedContent(tool, toolInput, abs);
  if (content === null) return 0;

  const matches = scanMirrored(rel, content); // infra failure raises → main() fails closed
  const errors = matches.filter((m) => m.severity === "error");
  const advisories = matches.filter((m) => m.severity !== "error");
  if (advisories.length > 0) {
    process.stderr.write(formatFindings("splice walls (advisory)", advisories) + "\n");
  }
  if (errors.length > 0) {
    emitBlock(
      formatFindings(`SPLICE WALLS: write to ${rel} blocked`, errors) +
        "\n\nFix the content. For a deliberate, justified exception add\n" +
        "`// ast-grep-ignore: <rule-id>` with a reason on the line above.\n" +
        "Rules: quality/rules/console/ (tests in quality/rules/rule-tests/). The gate re-runs\n" +
        "the same rules: npm run gate:rules.",
    );
  }
  return 0;
}

function stop(data: Record<string, unknown>): number {
  if (data.stop_hook_active) return 0;
  let matches: Match[];
  try {
    matches = scanTree();
  } catch (exc) {
    logError(String(exc));
    process.stderr.write(
      `splice orchestrator: stop scan unavailable, relying on gate (see ${ERROR_LOG})\n`,
    );
    return 0;
  }
  const errors = matches.filter((m) => m.severity === "error");
  if (errors.length > 0) {
    emitBlock(
      formatFindings("SPLICE WALLS: the tree has rule violations — not done yet", errors) +
        "\n\nFix them before stopping (same rules as npm run gate:rules).",
    );
  }
  return 0;
}

function main(): number {
  const argv = process.argv.slice(2);
  const lifecycle = argv.length > 0 ? argv[0] : "";
  let data: unknown;
  try {
    data = JSON.parse(readFileSync(0, "utf8") || "");
  } catch {
    return 0;
  }
  if (data === null || typeof data !== "object" || Array.isArray(data)) return 0;
  try {
    if (lifecycle === "pretooluse") return pretooluse(data as Record<string, unknown>);
    if (lifecycle === "stop") return stop(data as Record<string, unknown>);
  } catch (exc) {
    logError(exc instanceof Error ? (exc.stack ?? String(exc)) : String(exc));
    if (lifecycle === "pretooluse") {
      emitBlock(
        "HOOK POLICY INCOMPLETE: splice orchestrator failed on a blocking lifecycle.\n\n" +
          `log: ${ERROR_LOG}\n\n` +
          "Failing closed — silently skipping write-time policy is unsafe. Fix the\n" +
          "orchestrator or the scan toolchain (is ast-grep on PATH?); never route around it.",
      );
    } else {
      process.stderr.write(`splice orchestrator: stop lifecycle degraded, see ${ERROR_LOG}\n`);
    }
  }
  return 0;
}

process.exit(main());
