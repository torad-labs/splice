/**
 * PROCESS SPAWNS IN SOURCE, READ BY STRUCTURE.
 *
 * A source file runs the interpreter when a spawn call's PROGRAM is the interpreter, whatever the file
 * also says elsewhere. A label string next to `spawnSync("git", ...)`, a comment that quotes a spawn, and a
 * template that documents one are not spawns. So the question is asked of the syntax tree:
 *
 *   · TypeScript and JavaScript: the compiler's own parser. The callee is resolved through the file's
 *     imports and requires, so an aliased `spawnSync as launch` is still a spawn; `Bun.spawn`, `Bun.$` and
 *     `exec` take a command line (shell), `spawn` and `execFile` take a program and arguments.
 *   · Kotlin and Java: ast-grep patterns for the process-starting calls (ProcessBuilder, Runtime.exec,
 *     Gradle's commandLine). The program is the first argument, or the first element of the list it passes.
 *   · A launcher entry whose command is the interpreter (`{"command": ...}`), in a JSON string or an
 *     object literal.
 *
 * What it cannot see is a program held in a variable it cannot resolve to a literal in the same file. That
 * is a limit of the instrument and is not read as a pass: a spawn of such a variable is reported as
 * unresolved by the caller when it is the file's only evidence, never silently charged or cleared.
 */
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import ts from "typescript";
import { astGrepBin } from "./astgrep.ts";
import type { ShellWord } from "./no-python-shell.ts";

export interface SourceFacts {
  /** argument vectors that start a process: analysed as one command each */
  argv: ShellWord[][];
  /** command lines handed to a shell */
  shell: string[];
}

const word = (value: string): ShellWord => ({ raw: JSON.stringify(value), value });
const argvOf = (values: readonly string[]): ShellWord[] => values.map(word);

const CHILD_PROCESS = new Set(["child_process", "node:child_process"]);
/** program + arguments, no shell */
const PROGRAM_CALLS = new Set(["spawn", "spawnSync", "execFile", "execFileSync"]);
/** one command line, run by a shell */
const COMMAND_LINE_CALLS = new Set(["exec", "execSync"]);

type CalleeKind = "program" | "command-line" | "bun-spawn" | "bun-shell";

// ─── TypeScript / JavaScript ──────────────────────────────────────────────────────────────────────

function scriptKind(path: string): ts.ScriptKind {
  if (/\.tsx$/.test(path)) return ts.ScriptKind.TSX;
  if (/\.jsx$/.test(path)) return ts.ScriptKind.JSX;
  if (/\.(?:js|mjs|cjs)$/.test(path)) return ts.ScriptKind.JS;
  return ts.ScriptKind.TS;
}

const literalText = (n: ts.Node): string | null =>
  ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n) ? n.text : null;

export function sourceFactsTs(path: string, text: string): SourceFacts {
  const out: SourceFacts = { argv: [], shell: [] };
  const sf = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, scriptKind(path));
  const named = new Map<string, CalleeKind>(); // local name -> what it is
  const namespaces = new Set<string>(); // local names of the whole child_process module
  const constants = new Map<string, string>(); // const NAME = "literal"

  const kindOfName = (name: string): CalleeKind | undefined =>
    PROGRAM_CALLS.has(name) ? "program" : COMMAND_LINE_CALLS.has(name) ? "command-line" : undefined;

  const collect = (n: ts.Node): void => {
    if (ts.isImportDeclaration(n) && ts.isStringLiteral(n.moduleSpecifier)) {
      const module = n.moduleSpecifier.text;
      const bindings = n.importClause?.namedBindings;
      if (CHILD_PROCESS.has(module)) {
        if (n.importClause?.name) namespaces.add(n.importClause.name.text);
        if (bindings && ts.isNamespaceImport(bindings)) namespaces.add(bindings.name.text);
        if (bindings && ts.isNamedImports(bindings)) {
          for (const s of bindings.elements) {
            const kind = kindOfName((s.propertyName ?? s.name).text);
            if (kind) named.set(s.name.text, kind);
          }
        }
      }
      if (module === "bun" && bindings && ts.isNamedImports(bindings)) {
        for (const s of bindings.elements) if ((s.propertyName ?? s.name).text === "$") named.set(s.name.text, "bun-shell");
      }
    }
    if (ts.isVariableDeclaration(n) && n.initializer) {
      const value = literalText(n.initializer);
      if (ts.isIdentifier(n.name) && value !== null) constants.set(n.name.text, value);
      // const { spawnSync: launch } = require("child_process") / const cp = require("child_process")
      const init = n.initializer;
      if (ts.isCallExpression(init) && ts.isIdentifier(init.expression) && init.expression.text === "require") {
        const arg = init.arguments[0] ? literalText(init.arguments[0]) : null;
        if (arg !== null && CHILD_PROCESS.has(arg)) {
          if (ts.isIdentifier(n.name)) namespaces.add(n.name.text);
          if (ts.isObjectBindingPattern(n.name)) {
            for (const e of n.name.elements) {
              const kind = kindOfName(((e.propertyName ?? e.name) as ts.Identifier).text);
              if (kind && ts.isIdentifier(e.name)) named.set(e.name.text, kind);
            }
          }
        }
      }
    }
    ts.forEachChild(n, collect);
  };
  collect(sf);

  /** A string the expression evaluates to without running anything: literals, a const, a concatenation, and a
   *  template whose substitutions become a placeholder word. */
  const strOf = (n: ts.Expression | undefined): string | null => {
    if (!n) return null;
    const lit = literalText(n);
    if (lit !== null) return lit;
    if (ts.isIdentifier(n)) return constants.get(n.text) ?? null;
    if (ts.isParenthesizedExpression(n)) return strOf(n.expression);
    if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.PlusToken) {
      const l = strOf(n.left);
      const r = strOf(n.right);
      return l !== null && r !== null ? l + r : null;
    }
    if (ts.isTemplateExpression(n)) return n.head.text + n.templateSpans.map((s) => "X" + s.literal.text).join("");
    return null;
  };

  const calleeKind = (callee: ts.Expression): CalleeKind | undefined => {
    if (ts.isIdentifier(callee)) return named.get(callee.text);
    if (ts.isPropertyAccessExpression(callee)) {
      const member = callee.name.text;
      const target = callee.expression;
      if (ts.isIdentifier(target) && target.text === "Bun") {
        if (member === "spawn" || member === "spawnSync") return "bun-spawn";
        if (member === "$") return "bun-shell";
      }
      if (ts.isIdentifier(target) && namespaces.has(target.text)) return kindOfName(member);
      if (ts.isCallExpression(target) && ts.isIdentifier(target.expression) && target.expression.text === "require") {
        const arg = target.arguments[0] ? literalText(target.arguments[0]) : null;
        if (arg !== null && CHILD_PROCESS.has(arg)) return kindOfName(member);
      }
    }
    return undefined;
  };

  const arrayStrings = (n: ts.Expression | undefined): string[] | null => {
    if (!n || !ts.isArrayLiteralExpression(n)) return null;
    return n.elements.map((e) => strOf(e as ts.Expression) ?? "\u0000");
  };

  const launcherCommand = (value: string): void => {
    const trimmed = value.trim();
    if (!trimmed.startsWith("{") || !trimmed.includes("command")) return;
    try {
      for (const command of commandsIn(JSON.parse(trimmed))) out.argv.push(argvOf([command]));
    } catch {
      // not JSON: prose that happens to start with a brace
    }
  };

  const visit = (n: ts.Node): void => {
    if (ts.isCallExpression(n)) {
      const kind = calleeKind(n.expression);
      const first = n.arguments[0];
      if (kind === "program") {
        const program = strOf(first);
        const args = arrayStrings(n.arguments[1]) ?? [];
        if (program !== null) {
          out.argv.push(argvOf([program, ...args]));
          if (/\s/.test(program.trim())) out.shell.push(program); // spawn(cmd, { shell: true })
        }
      } else if (kind === "command-line") {
        const line = strOf(first);
        if (line !== null) out.shell.push(line);
      } else if (kind === "bun-spawn") {
        // Bun.spawn(["prog", ...]) or Bun.spawn({ cmd: ["prog", ...] })
        const cmd =
          first && ts.isObjectLiteralExpression(first)
            ? first.properties.find((p): p is ts.PropertyAssignment => ts.isPropertyAssignment(p) && p.name.getText() === "cmd")?.initializer
            : first;
        const argv = arrayStrings(cmd);
        if (argv) out.argv.push(argvOf(argv));
      }
    }
    if (ts.isTaggedTemplateExpression(n) && calleeKind(n.tag) === "bun-shell") {
      const t = n.template;
      out.shell.push(ts.isNoSubstitutionTemplateLiteral(t) ? t.text : t.head.text + t.templateSpans.map((s) => "X" + s.literal.text).join(""));
    }
    if (ts.isPropertyAssignment(n)) {
      const key = ts.isIdentifier(n.name) || ts.isStringLiteral(n.name) ? n.name.text : null;
      const value = strOf(n.initializer);
      if (key === "command" && value !== null) out.argv.push(argvOf([value]));
    }
    const lit = ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n) ? n.text : null;
    if (lit !== null) launcherCommand(lit);
    ts.forEachChild(n, visit);
  };
  visit(sf);
  return out;
}

/** Every `command` string in a parsed launcher config. */
export function commandsIn(value: unknown): string[] {
  const found: string[] = [];
  const walk = (v: unknown): void => {
    if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === "object") {
      for (const [k, child] of Object.entries(v)) {
        if (k === "command" && typeof child === "string") found.push(child);
        else walk(child);
      }
    }
  };
  walk(value);
  return found;
}

// ─── Kotlin / Java ─────────────────────────────────────────────────────────────────────────────────

/** One pattern per process-starting shape; $F is the program (or the whole command line), $$$R the rest. */
const LIST = ["listOf", "arrayOf", "mutableListOf", "arrayListOf"];
const JAVA_LIST = ["List.of", "Arrays.asList"];
export function patterns(language: "kotlin" | "java"): { id: string; pattern: string }[] {
  const all: string[] = [];
  if (language === "kotlin") {
    for (const c of ["ProcessBuilder", "commandLine", "$X.command"]) {
      all.push(`${c}($F, $$$R)`, `${c}($F)`);
      for (const l of LIST) all.push(`${c}(${l}($F, $$$R))`, `${c}(${l}($F))`);
    }
    all.push("$X.exec($F)", "$X.exec(arrayOf($F, $$$R))");
    all.push('put("command", $F)', '"command" to $F');
  } else {
    for (const c of ["new ProcessBuilder", "commandLine", "$X.command"]) {
      all.push(`${c}($F, $$$R)`, `${c}($F)`);
      for (const l of JAVA_LIST) all.push(`${c}(${l}($F, $$$R))`, `${c}(${l}($F))`);
    }
    all.push("$X.exec($F)", "$X.exec(new String[]{$F, $$$R})");
  }
  return all.map((pattern, i) => ({ id: `spawn-${i}`, pattern }));
}

/** Every string literal, for a launcher entry written as JSON text. */
const LITERAL_RULE = (language: string) => `id: literal\nlanguage: ${language}\nseverity: hint\nrule:\n  kind: string_literal`;

/** The text of a Kotlin or Java string literal with no interpolation, else null. */
function unquote(text: string): string | null {
  const t = text.trim();
  const triple = /^"""([\s\S]*)"""$/.exec(t);
  const raw = triple ? triple[1]! : /^"((?:[^"\\]|\\.)*)"$/.exec(t)?.[1];
  if (raw === undefined || raw.includes("$")) return null;
  return raw.replace(/\\(.)/g, "$1");
}

/** Kotlin and Java source files, as {path, text}: facts per path, from ONE ast-grep scan per language. */
export function sourceFactsJvm(repoRoot: string, files: readonly { path: string; text: string }[]): Map<string, SourceFacts> {
  const result = new Map<string, SourceFacts>();
  for (const language of ["kotlin", "java"] as const) {
    const mine = files.filter((f) => (language === "java" ? f.path.endsWith(".java") : /\.(?:kt|kts)$/.test(f.path)));
    if (!mine.length) continue;
    const dir = mkdtempSync(join(tmpdir(), "no-python-jvm-"));
    try {
      const names = mine.map((f, i) => {
        const name = `${i}.${language === "java" ? "java" : "kt"}`;
        writeFileSync(join(dir, name), f.text);
        return name;
      });
      const rules = [
        LITERAL_RULE(language),
        ...patterns(language).map((p) => `id: ${p.id}\nlanguage: ${language}\nseverity: hint\nrule:\n  pattern: ${JSON.stringify(p.pattern)}`),
      ].join("\n---\n");
      const r = Bun.spawnSync([astGrepBin(repoRoot), "scan", "--inline-rules", rules, "--json=compact", dir], {
        stdout: "pipe",
        stderr: "pipe",
      });
      if (r.exitCode !== 0) {
        throw new Error(
          `no-python: ast-grep failed on the ${language} batch (exit ${r.exitCode}) reading ${mine.slice(0, 8).map((f) => f.path).join(", ")}: ${r.stderr.toString().trim()}`,
        );
      }
      let hits: { file: string; ruleId: string; text: string; metaVariables: { single: Record<string, { text: string }>; multi: Record<string, { text: string }[]> } }[];
      try {
        hits = JSON.parse(r.stdout.toString() || "[]");
      } catch {
        throw new Error(`no-python: ast-grep returned unreadable output for the ${language} batch (exit ${r.exitCode}): ${r.stderr.toString().trim()}`);
      }
      for (const h of hits) {
        const path = mine[names.indexOf(basename(h.file))]!.path;
        const facts = result.get(path) ?? { argv: [], shell: [] };
        result.set(path, facts);
        if (h.ruleId === "literal") {
          const body = unquote(h.text)?.trim() ?? "";
          if (body.startsWith("{") && body.includes("command")) {
            try {
              for (const command of commandsIn(JSON.parse(body))) facts.argv.push(argvOf([command]));
            } catch {
              // prose that happens to start with a brace
            }
          }
          continue;
        }
        const first = unquote(h.metaVariables.single["F"]?.text ?? "");
        if (first === null) continue;
        const rest = (h.metaVariables.multi["R"] ?? []).filter((m) => m.text !== ",").map((m) => unquote(m.text) ?? "\u0000");
        facts.argv.push(argvOf([first, ...rest]));
        if (/\s/.test(first.trim())) facts.argv.push(argvOf(first.trim().split(/\s+/))); // Runtime.exec(String) splits on blanks
      }
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
  return result;
}
