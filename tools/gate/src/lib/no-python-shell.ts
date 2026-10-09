/**
 * SHELL, READ BY STRUCTURE: which commands a snippet of shell would run, and what each runs.
 *
 * The no-python wall used to ask whether a LINE looked like it ran the interpreter. That is a text match,
 * and it missed a quoted command, a versioned or absolute interpreter, an option-carrying `sudo`, and an
 * extensionless launcher, while charging an `echo` that quoted the interpreter's name. The question here
 * is the one the shell asks: what are the COMMANDS. ast-grep's bash grammar finds every `command` node
 * (including those inside `$(...)`, `if`, `&&` and pipelines); a comment and an argument to `echo` are
 * not commands. The words of one command are then read with shell quoting, and the wrapper chain
 * (`sudo -u root env X=1 nice PROGRAM`) is walked to the program that actually runs.
 *
 * A `sh -c "..."` or `eval "..."` argument is shell again and is read the same way, to a fixed depth.
 */
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { astGrepBin } from "./astgrep.ts";

/** `runtime target`: a command line that runs a script file. "py" is the interpreter, "bun" is bun. */
export interface Call {
  readonly runtime: "py" | "bun";
  readonly target: string;
}

export interface ShellFacts {
  /** a command in the snippet runs or installs the interpreter */
  readonly interpreter: boolean;
  readonly calls: readonly Call[];
}

/** The interpreter and its installers, by program name: the bare name, a versioned one, or a path to either. */
const INTERPRETER = /^(?:python(?:\d+(?:\.\d+)*)?w?|pypy\d*|pip(?:\d+(?:\.\d+)*)?|pipx)$/;
export const isInterpreter = (word: string): boolean => INTERPRETER.test(basename(word));

/** A package that IS the interpreter or its installer, for `apt-get install ...`. */
const INTERPRETER_PACKAGE = /^(?:python\d*(?:[.+-][\w.+-]*)?|py\d*-[\w.+-]+|pip\d*|pipx)$/;
const PACKAGE_MANAGER = new Set(["apt", "apt-get", "apk", "dnf", "yum", "brew", "pacman", "zypper"]);
const INSTALL_VERB = new Set(["install", "add", "-S", "-Sy", "-Syu"]);
const SHELLS = new Set(["sh", "bash", "zsh", "dash", "ksh", "ash"]);

/** Options of a wrapper that consume the NEXT word. A wrapper not listed here takes none. */
const WRAPPER_ARG_OPTIONS: Readonly<Record<string, ReadonlySet<string>>> = {
  sudo: new Set(["-u", "-g", "-h", "-p", "-C", "-D", "-R", "-T", "-U", "-r", "-t", "--user", "--group", "--host", "--prompt", "--chdir"]),
  doas: new Set(["-u", "-C"]),
  env: new Set(["-u", "-C", "-S", "--unset", "--chdir"]),
  nohup: new Set(),
  nice: new Set(["-n"]),
  ionice: new Set(["-c", "-n", "-p"]),
  setsid: new Set(),
  time: new Set(["-f", "-o"]),
  timeout: new Set(["-k", "-s"]),
  stdbuf: new Set(["-i", "-o", "-e"]),
  exec: new Set(["-a"]),
  xargs: new Set(["-n", "-L", "-I", "-P", "-s", "-d", "-E", "-a"]),
  builtin: new Set(),
  command: new Set(),
};
/** After the wrapper's options, this many positional words belong to the wrapper (`timeout 5 cmd`). */
const WRAPPER_POSITIONALS: Readonly<Record<string, number>> = { timeout: 1 };

export interface ShellWord {
  /** as written, quotes and all: an assignment is recognised on this */
  readonly raw: string;
  /** after quote and backslash removal */
  readonly value: string;
}

/** The words of ONE simple command, up to its first unquoted operator or newline. */
export function shellWords(text: string): ShellWord[] {
  const words: ShellWord[] = [];
  let raw = "";
  let value = "";
  let open = false;
  const flush = () => {
    if (open) words.push({ raw, value });
    raw = "";
    value = "";
    open = false;
  };
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (c === "\\" && i + 1 < text.length) {
      if (text[i + 1] === "\n") {
        i++; // a continuation joins the lines
        continue;
      }
      raw += c + text[i + 1]!;
      value += text[i + 1]!;
      open = true;
      i++;
    } else if (c === "'") {
      const end = text.indexOf("'", i + 1);
      const stop = end === -1 ? text.length : end;
      raw += text.slice(i, stop + 1);
      value += text.slice(i + 1, stop);
      open = true;
      i = stop;
    } else if (c === '"') {
      let j = i + 1;
      let inner = "";
      while (j < text.length && text[j] !== '"') {
        if (text[j] === "\\" && j + 1 < text.length && '"\\$`\n'.includes(text[j + 1]!)) {
          if (text[j + 1] !== "\n") inner += text[j + 1]!;
          j += 2;
        } else inner += text[j++]!;
      }
      raw += text.slice(i, j + 1);
      value += inner;
      open = true;
      i = j;
    } else if (c === " " || c === "\t") flush();
    else if (c === "\n" || c === ";" || c === "&" || c === "|" || c === "(" || c === ")" || c === "<" || c === ">") break;
    else {
      raw += c;
      value += c;
      open = true;
    }
  }
  flush();
  return words;
}

const ASSIGNMENT = /^[A-Za-z_][A-Za-z0-9_]*(?:\[[^\]]*\])?\+?=/;

export interface WordFacts {
  interpreter: boolean;
  calls: Call[];
  /** shell text this command hands to a shell: `sh -c ARG`, `eval ARG` */
  nested: string[];
}

/** What running these words would do. Assignments in front are skipped, wrappers are walked through. */
export function analyzeWords(rawWords: readonly ShellWord[]): WordFacts {
  const facts: WordFacts = { interpreter: false, calls: [], nested: [] };
  let first = 0;
  while (first < rawWords.length && ASSIGNMENT.test(rawWords[first]!.raw)) first++;
  const words = rawWords.slice(first).map((w) => w.value);
  let at = 0;
  for (let guard = 0; guard < 16 && at < words.length; guard++) {
    const name = basename(words[at]!);
    const rest = words.slice(at + 1);
    if (isInterpreter(name)) {
      facts.interpreter = true;
      recordScript(facts, "py", rest);
      return facts;
    }
    if (name === "bun") {
      recordScript(facts, "bun", rest);
      return facts;
    }
    if (SHELLS.has(name)) {
      const flag = rest.findIndex((w) => /^-[A-Za-z]*c[A-Za-z]*$/.test(w));
      if (flag !== -1 && rest[flag + 1] !== undefined) facts.nested.push(rest[flag + 1]!);
      return facts;
    }
    if (name === "eval") {
      facts.nested.push(rest.join(" "));
      return facts;
    }
    if (PACKAGE_MANAGER.has(name)) {
      const verb = rest.findIndex((w) => INSTALL_VERB.has(w));
      if (verb !== -1 && rest.slice(verb + 1).some((w) => !w.startsWith("-") && INTERPRETER_PACKAGE.test(w))) {
        facts.interpreter = true;
      }
      return facts;
    }
    const options = WRAPPER_ARG_OPTIONS[name];
    if (!options) return facts;
    if (name === "command" && rest.some((w) => w === "-v" || w === "-V")) return facts; // a lookup, not a run
    let j = at + 1;
    let positionals = WRAPPER_POSITIONALS[name] ?? 0;
    while (j < words.length) {
      const w = words[j]!;
      if (w === "--") {
        j++;
        break;
      }
      if (w.startsWith("-") && w.length > 1) j += options.has(w) ? 2 : 1;
      else if (ASSIGNMENT.test(w)) j++;
      else if (positionals > 0) {
        positionals--;
        j++;
      } else break;
    }
    at = j;
  }
  return facts;
}

/** `PROGRAM x.py` / `bun run x.ts`: the first word that is the script, before any `-c code` or `-m module`. */
function recordScript(facts: WordFacts, runtime: Call["runtime"], rest: readonly string[]): void {
  for (const w of rest) {
    if (runtime === "py" && (w === "-c" || w === "-m")) return;
    if (w.startsWith("-") || (runtime === "bun" && (w === "run" || w === "x"))) continue;
    if (/\.(?:py|ts)$/.test(w)) facts.calls.push({ runtime, target: w });
    return;
  }
}

const COMMANDS_RULE = `id: shell-commands
language: bash
severity: hint
rule:
  kind: command
`;

/** Every `command` node's text, per snippet, from ONE ast-grep scan over the batch. */
function commandsOf(repoRoot: string, snippets: readonly string[], labels: readonly string[]): string[][] {
  const out: string[][] = snippets.map(() => []);
  if (!snippets.length) return out;
  const dir = mkdtempSync(join(tmpdir(), "no-python-shell-"));
  try {
    snippets.forEach((text, i) => writeFileSync(join(dir, `${i}.sh`), text));
    const r = Bun.spawnSync([astGrepBin(repoRoot), "scan", "--inline-rules", COMMANDS_RULE, "--json=compact", dir], {
      stdout: "pipe",
      stderr: "pipe",
    });
    // A scan that failed says nothing about the snippets: it must fail the wall, never read as "no commands".
    if (r.exitCode !== 0) {
      const files = [...new Set(labels)].slice(0, 8).join(", ");
      throw new Error(`no-python: ast-grep failed on the shell batch (exit ${r.exitCode}) reading ${files}: ${r.stderr.toString().trim()}`);
    }
    let hits: { file: string; text: string }[];
    try {
      hits = JSON.parse(r.stdout.toString() || "[]");
    } catch {
      throw new Error(`no-python: ast-grep returned unreadable output for the shell batch (exit ${r.exitCode}): ${r.stderr.toString().trim()}`);
    }
    for (const h of hits) out[Number(basename(h.file, ".sh"))]!.push(h.text);
    return out;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const MAX_NESTING = 4;

/** Facts per snippet. One scan per nesting level, however many snippets there are. */
export function analyzeShell(repoRoot: string, snippets: readonly string[], labels: readonly string[] = snippets): ShellFacts[] {
  const interpreter = snippets.map(() => false);
  const calls: Call[][] = snippets.map(() => []);
  let level: { owner: number; text: string }[] = snippets.map((text, owner) => ({ owner, text }));
  const labelOf = (owner: number): string => labels[owner] ?? "a shell snippet";
  for (let depth = 0; level.length > 0 && depth < MAX_NESTING; depth++) {
    const commands = commandsOf(repoRoot, level.map((l) => l.text), level.map((l) => labelOf(l.owner)));
    const next: { owner: number; text: string }[] = [];
    level.forEach((l, k) => {
      for (const command of commands[k]!) {
        const facts = analyzeWords(shellWords(command));
        if (facts.interpreter) interpreter[l.owner] = true;
        calls[l.owner]!.push(...facts.calls);
        for (const nested of facts.nested) next.push({ owner: l.owner, text: nested });
      }
    });
    level = next;
  }
  return snippets.map((_, i) => ({ interpreter: interpreter[i]!, calls: calls[i]! }));
}
