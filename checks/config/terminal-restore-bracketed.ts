#!/usr/bin/env bun
/**
 * CW-9 — raw-mode entry is only legal inside TerminalMode.raw.
 *
 * THE CLASS. A wizard that leaves the terminal raw with echo off is strictly
 * worse than no wizard, because the damage outlives the process. TerminalMode.raw
 * is the restoring bracket: capture, enter, try/finally restore, shutdown hook.
 * This wall closes the class so a widget cannot invoke stty on its own or enter
 * raw outside that bracket.
 *
 * SCOPE. Kotlin sources of splice.app.cli.prompt under
 * gateway/app/src/main/kotlin/splice/app/cli/prompt. Tests are out of scope —
 * they inject SttyCommand and never talk to a real tty.
 *
 * DENOMINATOR. Every .kt file in that directory on disk. Zero files, or a missing
 * package directory, is a FAILURE, not a pass. There is no allowlist and no
 * exemption table.
 *
 * PARSE. Sources are tokenized (comments dropped, string literals kept as STRING
 * tokens, identifiers as IDENT). This is not a substring grep: stty in a comment
 * does not count; the command string "stty" does.
 *
 * VIOLATIONS, failed BY NAME:
 *   1. STRING token stty in any file other than TerminalMode.kt
 *   2. STRING token -icanon (the raw-mode entry flags) outside the body of
 *      TerminalMode.raw — including in TerminalMode.kt itself if it is not
 *      inside fun raw
 *
 * Usage:
 *     bun checks/config/terminal-restore-bracketed.ts [check] [<root>]
 *     bun checks/config/terminal-restore-bracketed.ts --selftest
 *
 * A BARE RUN IS `check` — the one gating mode, so there is no non-gating default to mis-invoke.
 */
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

// parents[2]: this file lives at checks/config/, so the repo root is two levels up.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const PROMPT_REL = "gateway/app/src/main/kotlin/splice/app/cli/prompt";
const STTY = "stty";
const RAW_FLAG = "-icanon";
const TERMINAL_MODE = "TerminalMode.kt";

const promptDir = (root: string): string => join(root, PROMPT_REL);

function promptFiles(root: string): string[] | null {
  const directory = promptDir(root);
  if (!existsSync(directory)) return null;
  const names = [...new Bun.Glob("*.kt").scanSync({ cwd: directory, followSymlinks: true })].sort();
  return names.map((name) => join(directory, name));
}

/** Python's repr() of a list of strings. */
const pyReprList = (items: string[]): string =>
  `[${items.map((s) => `'${s.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`).join(", ")}]`;

/** Return [kind, value, line] tokens. kind is STRING, IDENT, or LBRACE/RBRACE. */
function tokenize(source: string): [string, string, number][] {
  const tokens: [string, string, number][] = [];
  let i = 0;
  const n = source.length;
  let line = 1;
  while (i < n) {
    const ch = source[i];
    if (ch === "\n") {
      line += 1;
      i += 1;
      continue;
    }
    if (/\s/.test(ch)) {
      i += 1;
      continue;
    }
    if (source.startsWith("//", i)) {
      i = source.indexOf("\n", i);
      if (i < 0) break;
      continue;
    }
    if (source.startsWith("/*", i)) {
      const end = source.indexOf("*/", i + 2);
      if (end < 0) break;
      line += countOf(source.slice(i, end), "\n");
      i = end + 2;
      continue;
    }
    if (source.startsWith('"""', i)) {
      const end = source.indexOf('"""', i + 3);
      if (end < 0) {
        tokens.push(["STRING", source.slice(i + 3), line]);
        break;
      }
      tokens.push(["STRING", source.slice(i + 3, end), line]);
      line += countOf(source.slice(i, end), "\n");
      i = end + 3;
      continue;
    }
    if (ch === '"') {
      let j = i + 1;
      const bits: string[] = [];
      while (j < n) {
        const cur = source[j];
        if (cur === "\\") {
          j += 2;
          continue;
        }
        if (cur === '"') break;
        bits.push(cur);
        j += 1;
      }
      tokens.push(["STRING", bits.join(""), line]);
      line += countOf(source.slice(i, j), "\n");
      i = j < n ? j + 1 : n;
      continue;
    }
    if (isAlpha(ch) || ch === "_") {
      let j = i + 1;
      while (j < n && (isAlnum(source[j]) || source[j] === "_")) j += 1;
      tokens.push(["IDENT", source.slice(i, j), line]);
      i = j;
      continue;
    }
    if (ch === "{") {
      tokens.push(["LBRACE", "{", line]);
      i += 1;
      continue;
    }
    if (ch === "}") {
      tokens.push(["RBRACE", "}", line]);
      i += 1;
      continue;
    }
    if (ch === "<") {
      tokens.push(["LT", "<", line]);
      i += 1;
      continue;
    }
    if (ch === ">") {
      tokens.push(["GT", ">", line]);
      i += 1;
      continue;
    }
    i += 1;
  }
  return tokens;
}

function countOf(text: string, needle: string): number {
  let count = 0;
  let at = text.indexOf(needle);
  while (at >= 0) {
    count += 1;
    at = text.indexOf(needle, at + needle.length);
  }
  return count;
}

// Python's str.isalpha()/isalnum() are Unicode-aware; Kotlin identifiers are ASCII here, and a
// non-ASCII identifier would not be a token this wall has an opinion about either way.
const isAlpha = (ch: string | undefined): boolean => ch !== undefined && /[A-Za-z]/.test(ch);
const isAlnum = (ch: string | undefined): boolean => ch !== undefined && /[A-Za-z0-9]/.test(ch);

/** Line numbers of STRING tokens that sit inside fun raw { ... }. */
function rawFunctionStringLines(tokens: [string, string, number][]): Set<number> {
  const lines = new Set<number>();
  let i = 0;
  const n = tokens.length;
  while (i < n) {
    if (tokens[i][0] === "IDENT" && tokens[i][1] === "fun") {
      let j = i + 1;
      if (j < n && tokens[j][0] === "LT") {
        let depth = 0;
        while (j < n) {
          if (tokens[j][0] === "LT") depth += 1;
          else if (tokens[j][0] === "GT") {
            depth -= 1;
            j += 1;
            if (depth === 0) break;
            continue;
          }
          j += 1;
        }
      }
      if (j < n && tokens[j][0] === "IDENT" && tokens[j][1] === "raw") {
        while (j < n && tokens[j][0] !== "LBRACE") j += 1;
        if (j >= n) break;
        let depth = 0;
        let k = j;
        while (k < n) {
          const [kind, , line] = tokens[k];
          if (kind === "LBRACE") depth += 1;
          else if (kind === "RBRACE") {
            depth -= 1;
            if (depth === 0) break;
          } else if (kind === "STRING") lines.add(line);
          k += 1;
        }
        i = k + 1;
        continue;
      }
    }
    i += 1;
  }
  return lines;
}

function checkFile(path: string, source: string): string[] {
  const name = path.split("/").pop() as string;
  const tokens = tokenize(source);
  const rawLines = name === TERMINAL_MODE ? rawFunctionStringLines(tokens) : new Set<number>();
  const problems: string[] = [];
  for (const [kind, value, line] of tokens) {
    if (kind !== "STRING") continue;
    if (value === STTY && name !== TERMINAL_MODE) {
      problems.push(`${name}:${line}: stty invocation outside ${TERMINAL_MODE}`);
    }
    if (value === RAW_FLAG && !(name === TERMINAL_MODE && rawLines.has(line))) {
      problems.push(`${name}:${line}: raw-mode entry (-icanon) is not inside TerminalMode.raw`);
    }
  }
  return problems;
}

function checkTree(root: string): string[] {
  const files = promptFiles(root);
  if (files === null) return [`prompt package missing: ${PROMPT_REL}`];
  if (files.length === 0) return [`scanned zero files under ${PROMPT_REL}`];
  const problems: string[] = [];
  for (const path of files) {
    const name = path.split("/").pop() as string;
    const rel = relative(root, path);
    let source: string;
    try {
      source = readFileSync(path, "utf8");
    } catch (exc) {
      problems.push(`${rel}: unreadable (${exc})`);
      continue;
    }
    for (const hit of checkFile(path, source)) {
      problems.push(hit.startsWith(name) ? hit : `${rel}: ${hit}`);
    }
  }
  return problems;
}

const COMPLIANT_TERMINAL = `
package splice.app.cli.prompt
internal class TerminalMode {
    fun <T> raw(block: () -> T): T {
        stty.run(listOf("stty", "-g"))
        stty.run(listOf("stty", "-icanon", "-echo", "min", "1", "time", "0"))
        return block()
    }
}
`;

const VIOLATION = `
package splice.app.cli.prompt
internal class LooseStty {
    fun go() {
        ProcessBuilder(listOf("stty", "-icanon", "-echo")).start()
    }
}
`;

function writePrompt(root: string, name: string, source: string): string {
  const directory = promptDir(root);
  mkdirSync(directory, { recursive: true });
  const path = join(directory, name);
  writeFileSync(path, source, "utf8");
  return path;
}

function selftest(): number {
  const failures: string[] = [];
  // A temp directory is the Python form of mktemp -d plus trap EXIT.
  const root = join(tmpdir(), `cw9-restore-${process.pid}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(root, { recursive: true });
  try {
    const empty = checkTree(root);
    if (!empty.some((hit) => hit.includes("missing") || hit.includes("zero files"))) {
      failures.push("empty tree must refuse to pass vacuously, got: " + pyReprList(empty));
    }
    writePrompt(root, TERMINAL_MODE, COMPLIANT_TERMINAL);
    const bad = writePrompt(root, "LooseStty.kt", VIOLATION);
    const red = checkTree(root);
    if (!red.some((hit) => hit.includes("LooseStty.kt"))) {
      failures.push("synthetic unbracketed stty must be RED naming LooseStty.kt, got: " + pyReprList(red));
    }
    rmSync(bad);
    const green = checkTree(root);
    if (green.length > 0) {
      failures.push("compliant TerminalMode-only tree must be GREEN, got: " + pyReprList(green));
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
  if (failures.length > 0) {
    process.stdout.write("terminal-restore-bracketed SELFTEST FAIL:\n");
    for (const failure of failures) process.stdout.write("  " + failure + "\n");
    return 1;
  }
  process.stdout.write(
    "terminal-restore-bracketed SELFTEST OK — empty tree is vacuous-fail; " +
      "unbracketed stty is RED naming LooseStty.kt; TerminalMode.raw only is GREEN\n",
  );
  return 0;
}

const USAGE = `usage: terminal-restore-bracketed [check] [<root>] [--selftest]
  check      gate leg: raw-mode entry only inside TerminalMode.raw (a bare run does this)
  --selftest red-green proof, out of tree
`;

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  let root = ROOT;
  for (const arg of argv) {
    if (arg === "check" || arg === "--selftest") continue;
    if (arg.startsWith("-")) {
      process.stderr.write(`terminal-restore-bracketed: unrecognised argument '${arg}'\n${USAGE}`);
      return 1;
    }
    root = arg;
    break;
  }
  if (!existsSync(root)) {
    process.stderr.write("terminal-restore-bracketed: tree missing\n");
    return 1;
  }
  const resolved = realpathSync(root);
  const problems = checkTree(resolved);
  if (problems.length > 0) {
    process.stdout.write("terminal-restore-bracketed RED:\n");
    for (const problem of problems) process.stdout.write("  " + problem + "\n");
    return 1;
  }
  const files = promptFiles(resolved) ?? [];
  process.stdout.write(
    `terminal-restore-bracketed GREEN: ${files.length} prompt sources, ` +
      "stty only in TerminalMode.kt, -icanon only inside fun raw\n",
  );
  return 0;
}

process.exit(main(process.argv.slice(2)));
