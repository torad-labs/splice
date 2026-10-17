// The write-time wall — `bun tools/gate rules --stdin pretooluse`, the command .claude/settings.json
// routes PreToolUse (Write|Edit|MultiEdit) to.
// (.claude/hooks/orchestrator.ts until PR 5; the rule set and the mirror mechanics are unchanged.)
//
// ONE router, ZERO per-rule hooks (operator design constraint, 2026-07-13): policy lives only in
// quality/rules/**/*.yml. This file owns routing, not rules.
//
//   pretooluse (Write|Edit|MultiEdit)
//       1. Compute the file content AS IT WOULD EXIST after the tool call (Write payload;
//          Edit/MultiEdit applied to the on-disk file).
//       2. Mirror it to <tmp>/<repo-relative-path> and run
//          `ast-grep scan --config <root>/sgconfig.yml <relpath>` with cwd=<tmp>, so each rule's
//          files:/ignores: globs bind exactly as they do at the gate (globs resolve against the
//          path as scanned; verified 2026-07-13 on ast-grep 0.44.0).
//       3. severity:error matches block the call; anything lower goes to stderr.
//
// The SAME rules run at the gate (`bun tools/gate rules`, = npm run gate:rules) and in CI, through
// the same ast-grep resolution (src/lib/astgrep.ts: the tree's pinned node_modules/.bin/ast-grep,
// else PATH) — a weakened hook still fails the build (same-checker-twice).
//
// FAILURE POLICY: fails CLOSED — a broken wall must not silently wave writes through; the block
// names this file and the error log.
//
// NO STOP-TIME SCAN (removed 2026-09-22, hook audit). A whole-tree `ast-grep scan` ran at every Stop
// and SubagentStop: 2.1-3.7 s per turn end, and in a checkout shared by several seats its 12 blocks
// in 14 days were findings in files the blocked seat had not touched (another seat's move in
// progress). It was the third of three instruments; this wall and the gate + CI are the two left.
//
// Deliberate rule exceptions go inline, as ast-grep's ignore comment naming the rule id plus a
// justification; ast-grep honors these in both the hook and the gate. (Not spelled out here: this
// comment would itself parse as a directive that suppresses nothing.)
//
// SPLICE_HOOK_ROOT exists for the hermetic test harness only (test/hook.test.ts). Redirecting it
// to dodge policy is visible, auditable, and caught by the gate re-running the same rules on the
// real tree.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve as pathResolve } from "node:path";
import { astGrepBin } from "./astgrep.ts";
import { findRepoRoot } from "./repo.ts";

export const LIFECYCLES = ["pretooluse"] as const;
export type Lifecycle = (typeof LIFECYCLES)[number];
export const isLifecycle = (s: string | undefined): s is Lifecycle => (LIFECYCLES as readonly string[]).includes(s ?? "");

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

interface Roots {
  /** the tree whose sgconfig.yml, rules and ast-grep bind — the repository, or SPLICE_HOOK_ROOT under test */
  readonly root: string;
  readonly sgconfig: string;
  /** always under the real repository (gitignored), whatever root the scan binds to */
  readonly errorLog: string;
}

function roots(env: Record<string, string | undefined>): Roots {
  const repoRoot = findRepoRoot(import.meta.dir);
  const root = env.SPLICE_HOOK_ROOT || repoRoot;
  return {
    root,
    sgconfig: join(root, "sgconfig.yml"),
    errorLog: join(repoRoot, ".claude", "hooks", "log", "walls-hook-errors.log"),
  };
}

/** Resolve a proposed path the way a Write's target needs: symlinks resolved in the EXISTING
 *  prefix, the rest normalised, and no error when the path does not exist yet — the normal case,
 *  since a Write's target has not been created. fs.realpathSync would throw ENOENT on exactly that. */
function resolveProposed(p: string): string {
  const abs = pathResolve(p);
  const parts = abs.split("/").filter((s) => s !== "");
  let existing = "/";
  let index = 0;
  while (index < parts.length) {
    const candidate = join(existing, parts[index]!);
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

/** Any failure to RUN the scanner throws, which is what routes pretooluse to its fail-closed branch. */
function scanAstGrep(r: Roots, targets: string[], cwd: string): Match[] {
  const proc = spawnSync(astGrepBin(r.root), ["scan", "--config", r.sgconfig, "--json=compact", ...targets], {
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
    // String.replace with a string pattern replaces the FIRST occurrence — an Edit's contract;
    // replace_all replaces every one.
    content = e.replace_all ? content.split(oldS).join(newS) : content.replace(oldS, newS);
  }
  return content;
}

/** Scan proposed content at its repo-relative path so files:/ignores: bind. */
function scanMirrored(r: Roots, rel: string, content: string): Match[] {
  if (!existsSync(r.sgconfig)) throw new Error(`missing ${r.sgconfig}`);
  const tmp = mkdtempSync(join(tmpdir(), "splice-walls-"));
  try {
    const target = join(tmp, rel);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content, "utf8");
    return scanAstGrep(r, [rel], tmp);
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
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

/** stdout is the hook protocol channel: the one JSON decision, or nothing. */
function emitBlock(reason: string): void {
  process.stdout.write(JSON.stringify({ decision: "block", reason }));
}

function logError(r: Roots, text: string): void {
  try {
    mkdirSync(dirname(r.errorLog), { recursive: true });
    const stamp = new Date().toISOString();
    writeFileSync(r.errorLog, `[${stamp}]\n${text}\n${"-".repeat(80)}\n`, { flag: "a", encoding: "utf8" });
  } catch {
    // never let observability break the hook
  }
}

/** The tree a write binds to: the innermost git WORKTREE between the file and [rootResolved] that
 *  carries its own sgconfig.yml, else [r] itself. A worktree under .claude/worktrees is a full
 *  checkout whose gate scans it from ITS root, so its rules must see `core/...`, not
 *  `.claude/worktrees/<wt>/core/...` — the prefix made every path glob miss, blocking a file its
 *  rule exempts and exempting a file its rule scopes. The error log stays with the real repo. */
function nestedWorktree(r: Roots, rootResolved: string, resolved: string): Roots {
  let dir = dirname(resolved);
  while (dir.startsWith(rootResolved + "/")) {
    if (existsSync(join(dir, ".git")) && existsSync(join(dir, "sgconfig.yml"))) {
      return { root: dir, sgconfig: join(dir, "sgconfig.yml"), errorLog: r.errorLog };
    }
    dir = dirname(dir);
  }
  return r;
}

function pretooluse(r: Roots, data: Record<string, unknown>): number {
  const tool = data.tool_name;
  if (typeof tool !== "string" || !WRITE_TOOLS.includes(tool)) return 0;
  const toolInput = (data.tool_input ?? {}) as Record<string, unknown>;
  const filePath = String(toolInput.file_path ?? "");
  if (!filePath) return 0;

  const abs = filePath.startsWith("/") ? filePath : join(String(data.cwd ?? r.root), filePath);
  const resolved = resolveProposed(abs);
  const rootResolved = resolveProposed(r.root);
  if (resolved !== rootResolved && !resolved.startsWith(rootResolved + "/")) {
    return 0; // outside the repo — not this wall's jurisdiction
  }
  const tree = nestedWorktree(r, rootResolved, resolved);
  const rel = resolved.slice(resolveProposed(tree.root).length + 1);

  const content = proposedContent(tool, toolInput, abs);
  if (content === null) return 0;

  const matches = scanMirrored(tree, rel, content); // infra failure throws → hook() fails closed
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
        "Rules: quality/rules/ (tests in quality/rules/rule-tests/). The gate re-runs\n" +
        "the same rules: bun tools/gate rules.",
    );
  }
  return 0;
}

/** Run the wall over the PreToolUse event on `stdin`. Always exits 0: the decision travels on
 *  stdout, and a non-zero exit would be a SECOND, unstructured channel Claude Code also reads. */
export function hook(stdin: string, env: Record<string, string | undefined> = Bun.env): number {
  let data: unknown;
  try {
    data = JSON.parse(stdin || "");
  } catch {
    return 0;
  }
  if (data === null || typeof data !== "object" || Array.isArray(data)) return 0;
  const r = roots(env);
  try {
    return pretooluse(r, data as Record<string, unknown>);
  } catch (exc) {
    logError(r, exc instanceof Error ? (exc.stack ?? String(exc)) : String(exc));
    emitBlock(
      "HOOK POLICY INCOMPLETE: the splice walls hook failed on a blocking lifecycle.\n\n" +
        `log: ${r.errorLog}\n\n` +
        "Failing closed — silently skipping write-time policy is unsafe. Fix the hook\n" +
        "(tools/gate/src/lib/hook.ts) or the scan toolchain (is ast-grep installed?); never route around it.",
    );
  }
  return 0;
}
