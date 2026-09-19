/** Shared helpers for the write-time architecture gates (campaign kotlin-hardening D2).
 *
 *  Ported from proof-main .claude/hooks/orchestrator/_lib.py (the north-star repo) and
 *  re-anchored to torad-fleet: root detection uses CLAUDE_PROJECT_DIR (set by both
 *  .claude/settings.json and .codex/hooks.json) with a marker-walk fallback, because
 *  fleet's sgconfig.yml does not exist until campaign item C1 lands — the gates that
 *  depend on it self-arm at that point.
 */
import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve as pathResolve } from "node:path";

import { HookEvent } from "./tool_input";

// Suffixes the architecture gate scans. Kotlin backend campaign first; TS is
// included so C2's ported frontend-boundary tenets fire on write when they land.
export const GATE_LANG_EXTS: ReadonlySet<string> = new Set([".kt", ".ts", ".tsx", ".mts", ".cts"]);

export function targetFilePath(toolInput: Record<string, unknown>): string | null {
  const value = toolInput.file_path ?? toolInput.path;
  return typeof value === "string" && value ? value : null;
}

/** The suffix including the dot, or "" when there is none — Python's Path.suffix. */
function suffixOf(p: string): string {
  const base = p.split("/").pop() ?? "";
  const dot = base.lastIndexOf(".");
  return dot > 0 ? base.slice(dot) : "";
}

/** Resolve the fleet repo root: CLAUDE_PROJECT_DIR first (validated), then a
 *  walk up from each start to the dir holding sgconfig.yml + .claude/. */
export function findProjectRoot(...starts: (string | null | undefined)[]): string | null {
  const candidates: (string | null | undefined)[] = [process.env.CLAUDE_PROJECT_DIR, ...starts];
  for (const start of candidates) {
    if (!start) continue;
    let p: string;
    try {
      p = pathResolve(start);
    } catch {
      continue;
    }
    try {
      if (statSync(p).isFile()) p = dirname(p);
    } catch {
      // a path that does not exist is still a legal walk start, exactly as Path.resolve() allowed
    }
    for (let cur = p; ; cur = dirname(cur)) {
      // splice adaptation 2026-07-16: root marker is sgconfig.yml
      // (settings.gradle.kts lives under gateway/)
      if (existsSync(join(cur, "sgconfig.yml")) && statSync(join(cur, ".claude")).isDirectory()) {
        return cur;
      }
      const parent = dirname(cur);
      if (parent === cur) break;
    }
  }
  return null;
}

/** The full proposed FILE content after the write — what a real `ast-grep scan`
 *  sees, so the write-time gate matches the Gradle/CI gate exactly. For Edit/
 *  MultiEdit the replacement is applied to the CURRENT file (a method edit must be
 *  scanned WITH its enclosing class). Falls back to the raw new text when the file
 *  can't be read or an edit anchor is absent (new file / unmatched old_string). */
export function proposedFileContent(toolName: string, toolInput: Record<string, unknown>): string {
  if (toolName === "Write") return String(toolInput.content ?? "") || "";

  const path = targetFilePath(toolInput);
  let current = "";
  if (path) {
    try {
      current = readFileSync(path, "utf8");
    } catch {
      current = "";
    }
  }

  if (toolName === "Edit") {
    const old = String(toolInput.old_string ?? "") || "";
    const newS = String(toolInput.new_string ?? "") || "";
    if (current && old && current.includes(old)) {
      // Python's replace(old, new) replaces every occurrence; replace(old, new, 1) the first,
      // which is what JS String.replace with a string pattern does.
      return toolInput.replace_all ? current.split(old).join(newS) : current.replace(old, newS);
    }
    return newS;
  }

  if (toolName === "MultiEdit") {
    const edits = Array.isArray(toolInput.edits) ? (toolInput.edits as Record<string, unknown>[]) : [];
    let text = current;
    let applied = false;
    for (const e of edits) {
      const old = String(e?.old_string ?? "") || "";
      const newS = String(e?.new_string ?? "") || "";
      if (old && text.includes(old)) {
        text = e?.replace_all ? text.split(old).join(newS) : text.replace(old, newS);
        applied = true;
      }
    }
    return applied ? text : edits.map((e) => String(e?.new_string ?? "") || "").join("\n");
  }

  return "";
}

export interface GateMatch {
  ruleId: string;
  severity: string;
  line: number;
}

/** Scan PROPOSED content against the repo rules (sgconfig.yml at root), with the
 *  file's real relative path so each rule's files:/ignores globs resolve exactly as
 *  in a normal `ast-grep scan`.
 *
 *  Strategy (MIRRORED SCRATCH ROOT — supersedes the proof sibling-probe pattern,
 *  D2 amendment 2026-07-02): write the content at its TRUE relative path under a
 *  temp root with sgconfig.yml + .rules/ copied in, and scan there. The sibling
 *  .fleet-gate-<pid>/ probe dir broke rules whose ignores: name EXACT file paths
 *  (e.g. the persistence-boundary allowlist): the inserted dir segment defeated
 *  the ignore and every edit to an allowlisted file was falsely blocked. At the
 *  true relpath, files:/ignores globs resolve byte-identically to a real scan.
 *  Plain file I/O (not the Edit tool) so it cannot re-trigger the hook chain.
 *  Raises on subprocess/JSON errors — the caller declares FAIL_CLOSED, so a
 *  broken scanner blocks instead of waving violations through. */
export function astgrepScanProposed(root: string, filePath: string, content: string): GateMatch[] {
  const absFp = pathResolve(filePath.startsWith("/") ? filePath : join(root, filePath));
  if (!GATE_LANG_EXTS.has(suffixOf(absFp))) return [];

  const rootResolved = pathResolve(root);
  if (absFp !== rootResolved && !absFp.startsWith(rootResolved + "/")) {
    return []; // outside the repo — not ours to gate
  }
  const rel = absFp.slice(rootResolved.length + 1);

  const matches: GateMatch[] = [];
  const sgconfig = join(root, "sgconfig.yml");
  if (!existsSync(sgconfig)) return [];

  const tmp = mkdtempSync(join(tmpdir(), "fleet-gate-"));
  try {
    cpSync(sgconfig, join(tmp, "sgconfig.yml"));
    const rulesDir = join(root, ".rules");
    if (existsSync(rulesDir) && statSync(rulesDir).isDirectory()) {
      cpSync(rulesDir, join(tmp, ".rules"), { recursive: true });
    }
    const probe = join(tmp, rel);
    mkdirSync(dirname(probe), { recursive: true });
    writeFileSync(probe, content, "utf8");
    const proc = spawnSync("ast-grep", ["scan", "--json=compact", probe], {
      cwd: tmp,
      encoding: "utf8",
      timeout: 30000,
      maxBuffer: 256 * 1024 * 1024,
    });
    if (proc.error) throw new Error(String(proc.error));
    const out = (proc.stdout || "").trim();
    if (out) {
      for (const m of JSON.parse(out) as Record<string, unknown>[]) {
        const severity = String(m.severity ?? "error");
        if (severity === "error" || severity === "warning") {
          const range = (m.range ?? {}) as { start?: { line?: number } };
          matches.push({
            ruleId: String(m.ruleId ?? "?"),
            severity,
            line: (range.start?.line ?? 0) + 1,
          });
        }
      }
    }
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
  return matches;
}
