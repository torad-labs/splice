/**
 * THE TYPECHECK WALL — every tracked TypeScript file is in a typechecked program, and no file
 * gains a type error.
 *
 * Why it exists (2026-09-23): nothing in the gate ran `tsc`. `bun` executes TypeScript without
 * checking it, so the campaign wall `law_pre_content_wire_type.ts` carried 19 errors that the gate's
 * own tsconfig reported and no leg ever read, and 49 of 437 tracked .ts files sat in no program at
 * all. The console's build ran `tsc`, but the build is not a gate leg.
 *
 * THE DENOMINATOR COMES FROM THE SOURCE: `git ls-files`, unfiltered. A tracked file that no
 * program loads fails BY NAME unless `excused` names it with a reason; a program list that
 * silently shrank cannot turn the check green (global rules §24).
 *
 * Errors that predate the wall live in `baseline`, per file. The baseline may only SHRINK: a file
 * above its count fails with its diagnostics printed, a file below it fails until the count is
 * lowered, and a listed file that is gone or already clean is stale. Raising a number to make the
 * gate pass is the violation the wall exists to catch.
 *
 * A census that could not run — a missing compiler, a tsconfig tsc cannot read, a program that
 * loads nothing — is exit 2, never a pass.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { isAbsolute, join, relative } from "node:path";

export const CONFIG = "tools/gate/config/typecheck.json";

export interface TypecheckConfig {
  readonly compiler: string;
  readonly programs: readonly string[];
  readonly excused: Readonly<Record<string, string>>;
  readonly baseline: Readonly<Record<string, number>>;
}

export class WallError extends Error {
  constructor(message: string, readonly exit = 2) {
    super(message);
  }
}

export interface Report {
  readonly lines: string[];
  readonly problems: string[];
  readonly summary: string;
}

interface ProgramResult {
  readonly program: string;
  readonly files: Set<string>;
  readonly diagnostics: string[];
  readonly ms: number;
}

const DIAGNOSTIC = /^(.+?)\((\d+),(\d+)\): error (TS\d+): /;
const PROGRAM_ERROR = /^error TS\d+: /;

export function loadConfig(root: string): TypecheckConfig {
  const path = join(root, CONFIG);
  if (!existsSync(path)) throw new WallError(`typecheck: ${CONFIG} is missing — refusing to report a pass for a census with no programs`);
  const raw = JSON.parse(readFileSync(path, "utf8")) as Partial<TypecheckConfig>;
  if (typeof raw.compiler !== "string" || !Array.isArray(raw.programs) || raw.programs.length === 0) {
    throw new WallError(`typecheck: ${CONFIG} needs a compiler path and a non-empty programs list`);
  }
  return { compiler: raw.compiler, programs: raw.programs, excused: raw.excused ?? {}, baseline: raw.baseline ?? {} };
}

/** Every tracked TypeScript source, repo-relative. The denominator. */
export function trackedSources(root: string): string[] {
  const r = spawnSync("git", ["-C", root, "ls-files", "-z", "--", "*.ts", "*.tsx", "*.mts", "*.cts"], { encoding: "utf8" });
  if (r.status !== 0) throw new WallError(`typecheck: git ls-files failed (${(r.stderr || "").trim()}) — refusing to report a pass for a census that did not run`);
  return r.stdout.split("\0").filter((p) => p !== "").sort();
}

function runProgram(root: string, compiler: string, program: string): ProgramResult {
  const started = performance.now();
  const r = spawnSync(compiler, ["--noEmit", "--pretty", "false", "--listFiles", "-p", program], {
    cwd: root, encoding: "utf8", maxBuffer: 1 << 28,
  });
  if (r.error) throw new WallError(`typecheck: ${program}: could not start ${compiler} (${r.error.message})`);
  const files = new Set<string>();
  const diagnostics: string[] = [];
  for (const line of (r.stdout + r.stderr).split("\n")) {
    if (line === "") continue;
    if (PROGRAM_ERROR.test(line)) throw new WallError(`typecheck: ${program}: ${line}`);
    if (DIAGNOSTIC.test(line)) diagnostics.push(line);
    else if (isAbsolute(line)) {
      const rel = relative(root, line);
      if (!rel.startsWith("..") && !rel.split("/").includes("node_modules")) files.add(rel);
    }
  }
  if (r.status !== 0 && diagnostics.length === 0) {
    throw new WallError(`typecheck: ${program}: tsc exited ${r.status} with no diagnostics — ${(r.stderr || r.stdout).trim().slice(0, 400)}`);
  }
  if (files.size === 0) throw new WallError(`typecheck: ${program} loads ZERO repository files — refusing to pass vacuously`);
  return { program, files, diagnostics, ms: Math.round(performance.now() - started) };
}

export function wall(root: string, config: TypecheckConfig = loadConfig(root)): Report {
  const compiler = isAbsolute(config.compiler) ? config.compiler : join(root, config.compiler);
  if (!existsSync(compiler)) throw new WallError(`typecheck: compiler ${config.compiler} is missing — install dependencies; a typecheck that cannot run is not a pass`);
  const tracked = trackedSources(root);
  if (tracked.length === 0) throw new WallError("typecheck: enumerated ZERO tracked TypeScript files — the source is gone");
  const trackedSet = new Set(tracked);

  const results = config.programs.map((p) => runProgram(root, compiler, p));
  const covered = new Set<string>();
  const byFile = new Map<string, Set<string>>();
  for (const res of results) {
    for (const f of res.files) covered.add(f);
    for (const d of res.diagnostics) {
      const file = DIAGNOSTIC.exec(d)?.[1];
      if (file === undefined || !trackedSet.has(file)) continue; // the gate judges tracked content
      const seen = byFile.get(file) ?? new Set<string>();
      seen.add(d); // a file in two programs reports the same diagnostic twice
      byFile.set(file, seen);
    }
  }

  const problems: string[] = [];
  for (const f of tracked) {
    if (!covered.has(f) && !(f in config.excused)) {
      problems.push(`uncovered ${f}: in no typechecked program — add it to a program's include, or excuse it by name with a reason`);
    }
  }
  for (const [f, why] of Object.entries(config.excused)) {
    if (!trackedSet.has(f)) problems.push(`stale excuse ${f}: not a tracked file`);
    else if (covered.has(f)) problems.push(`stale excuse ${f}: a program loads it now — remove the excuse`);
    else if (why.trim() === "") problems.push(`excuse ${f} carries no reason — a blank reason is an absence wearing a label`);
  }
  for (const [f, diags] of [...byFile.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const allowed = config.baseline[f] ?? 0;
    if (diags.size > allowed) {
      problems.push(`new type errors in ${f}: ${diags.size} (baseline ${allowed})\n      ${[...diags].join("\n      ")}`);
    }
  }
  for (const [f, allowed] of Object.entries(config.baseline)) {
    const actual = byFile.get(f)?.size ?? 0;
    if (!trackedSet.has(f)) problems.push(`stale baseline ${f}: not a tracked file`);
    else if (actual < allowed) problems.push(`ratchet ${f}: ${actual} errors, baseline ${allowed} — lower the baseline to ${actual}${actual === 0 ? " (remove the entry)" : ""}`);
  }

  const lines = results.map((r) => `  program ${r.program}: ${r.files.size} files, ${r.diagnostics.length} diagnostics (${r.ms} ms)`);
  const debt = Object.values(config.baseline).reduce((a, b) => a + b, 0);
  const summary =
    `typecheck: ${tracked.length} tracked TypeScript files, ${tracked.length - Object.keys(config.excused).length} in a program, ` +
    `${Object.keys(config.excused).length} excused by name; baseline debt ${debt} error(s) in ${Object.keys(config.baseline).length} file(s), none added`;
  return { lines, problems, summary };
}
