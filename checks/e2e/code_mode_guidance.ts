#!/usr/bin/env bun
/** Frozen synthetic repository and evidence oracles for the prompt-only A/B.
 *
 *  V4-145: converted from code_mode_guidance.py. Two Python library calls had no JS counterpart and
 *  are carried here rather than approximated:
 *
 *    shlex.split — the simulated Bash tool tokenizes the MODEL's command string, so the POSIX
 *    state machine (quotes, backslash escapes, "No closing quotation") is transcribed from
 *    Lib/shlex.py's read_token.
 *
 *    ast.parse + ast.walk — the LSP tool derives definitions and call sites from the fixture
 *    Python. pythonCalls() parses the subset of Python the fixtures are written in and yields Call
 *    nodes in ast.walk's breadth-first order with each Call's lineno. It FAILS CLOSED: any
 *    construct outside the subset (a nested def, a decorator, a comprehension, an f-string...)
 *    throws, so a fixture edit that outgrows it is a loud error, never a silently wrong reference
 *    list. The selftest pins its output for every fixture file against what ast produced.
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { dumps, fromJS, isPyObj, loads, obj, type PyObj, type PyValue } from "./pyjson.ts";
import {
  argparseError, check, cpCompare, get, hashKey, pyRstrip, pySplitlines, runUnittest, setKey,
  sub, ValueError, argparse, type Tests,
} from "./pyshim.ts";

export const SYSTEM = `Investigate the supplied synthetic repository using tools to establish the requested facts.
The tool implementations are read-only fixtures. Do not edit files or invent evidence.
Return only the requested JSON object, without markdown fences. Stop once sufficient evidence exists.`;

export const GUIDANCE_PATH = join(
  resolve(dirname(import.meta.path), "../.."),
  "providers/codex/src/main/resources",
  "splice/provider/codex/code-mode-orchestration.txt",
);
// read_text() decodes UTF-8 with universal newlines.
export const GUIDANCE = pyRstrip(
  new TextDecoder("utf-8", { fatal: true }).decode(readFileSync(GUIDANCE_PATH)).replaceAll("\r\n", "\n").replaceAll("\r", "\n"),
);

function tool(name: string, description: string, properties: [string, PyValue][]): PyValue {
  return obj([
    ["name", name],
    ["description", description],
    ["input_schema", obj([
      ["type", "object"],
      ["properties", obj(properties)],
      ["required", properties.map(([k]) => k)],
      ["additionalProperties", false],
    ])],
  ]);
}
const str = obj([["type", "string"]]);

export const TOOLS: PyValue[] = [
  tool("Read", "Read one complete synthetic fixture file by exact relative path.", [["file_path", str]]),
  tool("Grep", "Literal text search. path is a fixture path/prefix, or '.' for all files. Returns path:line:text.", [
    ["pattern", str], ["path", str],
  ]),
  tool("LSP", "Inspect Python definitions or call references across the fixture workspace. " +
    "findReferences returns caller function names and source locations, excluding comments/strings. " +
    "file_path is the defining Python file; symbol is empty for documentSymbol.", [
    ["operation", obj([["type", "string"], ["enum", ["documentSymbol", "findReferences"]]])],
    ["file_path", str], ["symbol", str],
  ]),
  tool("Bash", "Read-only simulated fixture commands only: find . -type f; cat PATH; grep -n LITERAL PATH. " +
    "No command execution, pipes, or multiple commands; use the documented forms exactly.", [["command", str]]),
  tool("mcp__calendar__list", "Unrelated synthetic calendar integration.", []),
  tool("mcp__deploy__status", "Unrelated synthetic deployment integration.", []),
  tool("mcp__tickets__search", "Unrelated synthetic ticket integration.", []),
];

export const FILES: [string, string][] = [
  ["config/settings.toml", "[service]\ntimeout_seconds = 15\nretries = 2\n"],
  ["config/worker.toml", "[worker]\nconcurrency = 4\n"],
  ["config/cache.toml", "[cache]\nttl_seconds = 60\nenabled = false\n"],
  ["app/config.py", 'def load_timeout(config):\n    return config["service"]["timeout_seconds"]\n'],
  ["app/limits.py", `def build_client(timeout_seconds):
    return {"timeout": timeout_seconds}


def normalize_timeout(seconds):
    return min(max(seconds, 0), 60)


# The text build_client in this comment is not a caller.
DISPLAY_NAME = "build_client"
`],
  ["app/service.py", `from app.config import load_timeout
from app.limits import build_client


def start(config):
    return build_client(load_timeout(config))
`],
  ["app/worker.py", `from app import limits


def worker_client():
    return limits.build_client(30)
`],
  ["docs/notes.txt", "The words build_client() appear here, but this is documentation only.\n"],
  ["spec/timeouts.md", "# Request timeouts\n\nA request timeout of zero is invalid; accept integers 1 through 60 seconds.\n"],
  ["tests/test_limits.py", `from app.limits import normalize_timeout


def test_zero_is_currently_accepted():
    assert normalize_timeout(0) == 0
`],
];

export const CASES: [string, string][] = [
  ["configuration-audit", "Audit config/settings.toml, config/worker.toml, and config/cache.toml. " +
    "Report service timeout_seconds, worker concurrency, and cache ttl_seconds. " +
    'Return JSON with numeric fields "timeout_seconds", "worker_concurrency", "cache_ttl_seconds", ' +
    'and "citations": an array of source references in path:line format.'],
  ["function-callers", "Find every actual Python caller of build_client, defined in app/limits.py. " +
    "Exclude definitions, imports, comments, and string decoys. " +
    'Return JSON with "callers": an array of "path:function" strings, ' +
    'and "citations": an array of call-site references in path:line format.'],
  ["config-consumer-trace", "Trace the configured service timeout from config/settings.toml through " +
    "app/config.py to app/service.py and identify its sink function. " +
    'Return JSON with "setting" (section.key), "reader" (path:function), ' +
    '"consumer" (path:function), "sink" (dotted module.function), ' +
    'and "citations": an array of source references in path:line format.'],
  ["timeout-boundary-bug", "Check normalize_timeout in app/limits.py against spec/timeouts.md and " +
    "tests/test_limits.py for input zero. Do not edit anything. " +
    'Return JSON with numeric "actual_zero", "allowed_min", "allowed_max", boolean "violates_spec", ' +
    'and "citations": an array of source references in path:line format.'],
];

// These expectations are evaluator-only; none are interpolated into model instructions.
export const CONTRACTS: Record<string, PyObj> = {
  "configuration-audit": fromJS({
    timeout_seconds: 15, worker_concurrency: 4, cache_ttl_seconds: 60,
    citations: ["config/settings.toml:2", "config/worker.toml:2", "config/cache.toml:2"],
  }) as PyObj,
  "function-callers": fromJS({
    callers: ["app/service.py:start", "app/worker.py:worker_client"],
    citations: ["app/service.py:6", "app/worker.py:5"],
  }) as PyObj,
  "config-consumer-trace": fromJS({
    setting: "service.timeout_seconds", reader: "app/config.py:load_timeout",
    consumer: "app/service.py:start", sink: "app.limits.build_client",
    citations: ["config/settings.toml:2", "app/config.py:2", "app/service.py:6"],
  }) as PyObj,
  "timeout-boundary-bug": fromJS({
    actual_zero: 0, allowed_min: 1, allowed_max: 60, violates_spec: true,
    citations: ["app/limits.py:6", "spec/timeouts.md:3", "tests/test_limits.py:5"],
  }) as PyObj,
};

export const REQUIRED_LINES: Record<string, [string, number][]> = {
  "configuration-audit": [["config/settings.toml", 2], ["config/worker.toml", 2], ["config/cache.toml", 2]],
  "function-callers": [["app/service.py", 5], ["app/service.py", 6], ["app/worker.py", 4], ["app/worker.py", 5]],
  "config-consumer-trace": [["config/settings.toml", 2], ["app/config.py", 1], ["app/config.py", 2],
    ["app/service.py", 5], ["app/service.py", 6]],
  "timeout-boundary-bug": [["app/limits.py", 6], ["spec/timeouts.md", 3], ["tests/test_limits.py", 5]],
};

// ---------------------------------------------------------------------------------------------
// shlex.split (posix=True, whitespace_split=True, comments=False), transcribed from read_token.
// ---------------------------------------------------------------------------------------------

export function shlexSplit(s: string): string[] {
  const WS = " \t\r\n";
  const QUOTES = "'\"";
  const out: string[] = [];
  let i = 0;
  const read = (): string => (i < s.length ? s[i++] : "");
  for (;;) {
    let state: string | null = " ";
    let token = "";
    let quoted = false;
    let escapedstate = " ";
    for (;;) {
      const c = read();
      if (state === " ") {
        if (!c) {
          state = null;
          break;
        } else if (WS.includes(c)) {
          if (token || quoted) break;
          continue;
        } else if (c === "\\") {
          escapedstate = "a";
          state = c;
        } else if (QUOTES.includes(c)) {
          state = c;
        } else {
          token = c;
          state = "a";
        }
      } else if (state !== null && QUOTES.includes(state)) {
        quoted = true;
        if (!c) throw new ValueError("No closing quotation");
        if (c === state) state = "a";
        else if (c === "\\" && state === '"') {
          escapedstate = state;
          state = c;
        } else token += c;
      } else if (state === "\\") {
        if (!c) throw new ValueError("No escaped character");
        if (QUOTES.includes(escapedstate) && c !== state && c !== escapedstate) token += state;
        token += c;
        state = escapedstate;
      } else if (state === "a") {
        if (!c) {
          state = null;
          break;
        } else if (WS.includes(c)) {
          state = " ";
          if (token || quoted) break;
          continue;
        } else if (QUOTES.includes(c)) state = c;
        else if (c === "\\") {
          escapedstate = "a";
          state = c;
        } else token += c;
      }
    }
    if (!quoted && token === "") return out;
    out.push(token);
  }
}

// ---------------------------------------------------------------------------------------------
// The Python subset: top-level defs and classes, and every Call inside each top-level function in
// ast.walk order. Node children follow ast's _fields order, which is what fixes that order.
// ---------------------------------------------------------------------------------------------

type Tok = { kind: "name" | "num" | "str" | "op" | "nl"; text: string; line: number; col: number };
interface Node {
  kind: string;
  line: number;
  kids: Node[];
  name?: string; // Name.id / Attribute.attr, for Call.func matching
}

function unsupported(what: string, line: number): never {
  throw new Error(`code_mode_guidance: fixture Python outside the modelled subset (${what} at line ${line}); ` +
    "extend pythonCalls() before changing FILES");
}

function tokenize(src: string): Tok[][] {
  const lines: Tok[][] = [];
  let cur: Tok[] = [];
  let depth = 0;
  let line = 1;
  let col = 0;
  let i = 0;
  const OPS = ["**=", "//=", ">>=", "<<=", "**", "//", "==", "!=", "<=", ">=", "->", ":=", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "@="];
  while (i < src.length) {
    const c = src[i];
    if (c === "\n") {
      if (depth === 0 && cur.length > 0) {
        lines.push(cur);
        cur = [];
      }
      i++;
      line++;
      col = 0;
      continue;
    }
    if (c === " ") {
      i++;
      col++;
      continue;
    }
    if (c === "\t" || c === "\f") unsupported("tab or form-feed indentation", line);
    if (c === "#") {
      while (i < src.length && src[i] !== "\n") i++;
      continue;
    }
    if (c === "\\" && src[i + 1] === "\n") {
      i += 2;
      line++;
      col = 0;
      continue;
    }
    const startLine = line;
    const startCol = col;
    const strM = /^([rRbBuU]{0,2})('''|"""|'|")/.exec(src.slice(i, i + 5));
    if (strM && !/[fF]/.test(strM[1]) && (strM[1] === "" || !/[\p{L}\p{N}_]/u.test(src[i - 1] ?? ""))) {
      const q = strM[2];
      const raw = /[rR]/.test(strM[1]);
      let j = i + strM[0].length;
      for (;;) {
        if (j >= src.length) unsupported("unterminated string", startLine);
        if (src[j] === "\\" && !raw) {
          if (src[j + 1] === "\n") line++;
          j += 2;
          continue;
        }
        if (src.startsWith(q, j)) {
          j += q.length;
          break;
        }
        if (src[j] === "\n") {
          if (q.length === 1) unsupported("newline in a string", startLine);
          line++;
        }
        j++;
      }
      cur.push({ kind: "str", text: src.slice(i, j), line: startLine, col: startCol });
      col += j - i;
      i = j;
      continue;
    }
    if (/^[fF]/.test(src.slice(i)) && /^[fF][rR]?['"]/.test(src.slice(i, i + 3))) unsupported("f-string", line);
    const nameM = /^[\p{L}_][\p{L}\p{N}_]*/u.exec(src.slice(i, i + 200));
    if (nameM) {
      cur.push({ kind: "name", text: nameM[0], line, col });
      i += nameM[0].length;
      col += nameM[0].length;
      continue;
    }
    const numM = /^(?:0[xXoObB][0-9a-fA-F_]+|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][+-]?\d+)?[jJ]?)/.exec(src.slice(i, i + 100));
    if (numM && numM[0]) {
      cur.push({ kind: "num", text: numM[0], line, col });
      i += numM[0].length;
      col += numM[0].length;
      continue;
    }
    const op = OPS.find((o) => src.startsWith(o, i)) ?? c;
    if ("([{".includes(op)) depth++;
    if (")]}".includes(op)) depth--;
    cur.push({ kind: "op", text: op, line, col });
    i += op.length;
    col += op.length;
  }
  if (cur.length > 0) lines.push(cur);
  return lines;
}

class ExprParser {
  private i = 0;
  constructor(private readonly t: Tok[]) {}
  get done(): boolean {
    return this.i >= this.t.length;
  }
  peek(): Tok | undefined {
    return this.t[this.i];
  }
  private is(text: string): boolean {
    const k = this.t[this.i];
    return k !== undefined && (k.kind === "op" || k.kind === "name") && k.text === text;
  }
  eat(text: string): boolean {
    if (this.is(text)) {
      this.i++;
      return true;
    }
    return false;
  }
  expect(text: string): void {
    if (!this.eat(text)) unsupported(`expected '${text}'`, this.peek()?.line ?? 0);
  }
  /** testlist: a bare tuple `a, b` is one Tuple node. */
  testlist(): Node {
    const first = this.test();
    if (!this.is(",")) return first;
    const elts = [first];
    while (this.eat(",")) {
      if (this.done || this.is("=") || this.is(")")) break;
      elts.push(this.test());
    }
    return { kind: "Tuple", line: first.line, kids: elts };
  }
  test(): Node {
    const line = this.peek()?.line ?? 0;
    if (this.is("lambda")) unsupported("lambda", line);
    const body = this.orTest();
    if (this.eat("if")) {
      const cond = this.orTest();
      this.expect("else");
      const orelse = this.test();
      return { kind: "IfExp", line: body.line, kids: [cond, body, orelse] };
    }
    return body;
  }
  private boolChain(op: string, next: () => Node): Node {
    const first = next();
    if (!this.is(op)) return first;
    const values = [first];
    while (this.eat(op)) values.push(next());
    return { kind: "BoolOp", line: first.line, kids: values };
  }
  private orTest(): Node {
    return this.boolChain("or", () => this.andTest());
  }
  private andTest(): Node {
    return this.boolChain("and", () => this.notTest());
  }
  private notTest(): Node {
    const t = this.peek();
    if (this.eat("not")) return { kind: "UnaryOp", line: t!.line, kids: [this.notTest()] };
    return this.comparison();
  }
  private comparison(): Node {
    const left = this.bin(0);
    const comps: Node[] = [];
    for (;;) {
      if (["<", ">", "==", ">=", "<=", "!="].some((o) => this.eat(o))) comps.push(this.bin(0));
      else if (this.eat("in")) comps.push(this.bin(0));
      else if (this.is("not") && this.t[this.i + 1]?.text === "in") {
        this.i += 2;
        comps.push(this.bin(0));
      } else if (this.eat("is")) {
        this.eat("not");
        comps.push(this.bin(0));
      } else break;
    }
    return comps.length ? { kind: "Compare", line: left.line, kids: [left, ...comps] } : left;
  }
  private static readonly LEVELS = [["|"], ["^"], ["&"], ["<<", ">>"], ["+", "-"], ["*", "/", "//", "%", "@"]];
  private bin(level: number): Node {
    if (level >= ExprParser.LEVELS.length) return this.unary();
    let left = this.bin(level + 1);
    for (;;) {
      const op = ExprParser.LEVELS[level].find((o) => this.t[this.i]?.kind === "op" && this.t[this.i].text === o);
      if (op === undefined) return left;
      this.i++;
      left = { kind: "BinOp", line: left.line, kids: [left, this.bin(level + 1)] };
    }
  }
  private unary(): Node {
    const t = this.peek();
    if (t && t.kind === "op" && ["-", "+", "~"].includes(t.text)) {
      this.i++;
      return { kind: "UnaryOp", line: t.line, kids: [this.unary()] };
    }
    return this.power();
  }
  private power(): Node {
    const base = this.primary();
    if (this.eat("**")) return { kind: "BinOp", line: base.line, kids: [base, this.unary()] };
    return base;
  }
  private primary(): Node {
    let node = this.atom();
    for (;;) {
      if (this.eat(".")) {
        const name = this.t[this.i++];
        if (!name || name.kind !== "name") unsupported("attribute", node.line);
        node = { kind: "Attribute", line: node.line, kids: [node], name: name.text };
      } else if (this.eat("(")) {
        const args: Node[] = [];
        const keywords: Node[] = [];
        while (!this.eat(")")) {
          const t = this.peek();
          if (!t) unsupported("unclosed call", node.line);
          if (this.eat("**")) keywords.push({ kind: "keyword", line: t.line, kids: [this.test()] });
          else if (this.eat("*")) args.push({ kind: "Starred", line: t.line, kids: [this.test()] });
          else if (t.kind === "name" && this.t[this.i + 1]?.text === "=") {
            this.i += 2;
            keywords.push({ kind: "keyword", line: t.line, kids: [this.test()] });
          } else {
            args.push(this.test());
            if (this.is("for")) unsupported("generator expression", t.line);
          }
          if (!this.eat(",")) {
            this.expect(")");
            break;
          }
        }
        node = { kind: "Call", line: node.line, kids: [node, ...args, ...keywords] };
      } else if (this.eat("[")) {
        const slice = this.subscript();
        this.expect("]");
        node = { kind: "Subscript", line: node.line, kids: [node, slice] };
      } else return node;
    }
  }
  private subscript(): Node {
    const line = this.peek()?.line ?? 0;
    const part = (): Node | null => (this.is(":") || this.is("]") || this.is(",") ? null : this.test());
    const lower = part();
    if (!this.eat(":")) {
      if (lower === null) unsupported("empty subscript", line);
      return lower as Node;
    }
    const upper = part();
    const step = this.eat(":") ? part() : null;
    return { kind: "Slice", line, kids: [lower, upper, step].filter((n): n is Node => n !== null) };
  }
  private atom(): Node {
    const t = this.t[this.i++];
    if (!t) unsupported("missing expression", 0);
    if (t.kind === "name") {
      if (["None", "True", "False"].includes(t.text)) return { kind: "Constant", line: t.line, kids: [] };
      if (["yield", "await", "lambda", "for", "if", "else", "not"].includes(t.text)) unsupported(t.text, t.line);
      return { kind: "Name", line: t.line, kids: [], name: t.text };
    }
    if (t.kind === "num") return { kind: "Constant", line: t.line, kids: [] };
    if (t.kind === "str") {
      while (this.peek()?.kind === "str") this.i++; // adjacent literals are ONE Constant
      return { kind: "Constant", line: t.line, kids: [] };
    }
    if (t.text === "(") {
      if (this.eat(")")) return { kind: "Tuple", line: t.line, kids: [] };
      const inner = this.testlist();
      if (this.is("for")) unsupported("generator expression", t.line);
      this.expect(")");
      return inner;
    }
    if (t.text === "[") {
      const elts: Node[] = [];
      while (!this.eat("]")) {
        elts.push(this.test());
        if (this.is("for")) unsupported("list comprehension", t.line);
        if (!this.eat(",")) {
          this.expect("]");
          break;
        }
      }
      return { kind: "List", line: t.line, kids: elts };
    }
    if (t.text === "{") {
      const keys: Node[] = [];
      const values: Node[] = [];
      let isSet = false;
      while (!this.eat("}")) {
        if (this.eat("**")) values.push(this.bin(0));
        else {
          const k = this.test();
          if (this.is("for")) unsupported("comprehension", t.line);
          if (this.eat(":")) {
            keys.push(k);
            values.push(this.test());
            if (this.is("for")) unsupported("dict comprehension", t.line);
          } else {
            isSet = true;
            keys.push(k);
          }
        }
        if (!this.eat(",")) {
          this.expect("}");
          break;
        }
      }
      if (isSet) return { kind: "Set", line: t.line, kids: keys };
      return { kind: "Dict", line: t.line, kids: [...keys, ...values] };
    }
    return unsupported(`token '${t.text}'`, t.line);
  }
}

/** One simple statement -> its node. Compound statements are outside the subset. */
function statement(toks: Tok[]): Node {
  const p = new ExprParser(toks);
  const first = toks[0];
  const kw = first.kind === "name" ? first.text : "";
  if (["if", "for", "while", "with", "try", "def", "class", "async", "match", "else", "elif", "except", "finally", "global", "nonlocal", "del", "yield"].includes(kw)) {
    unsupported(`statement '${kw}'`, first.line);
  }
  if (first.kind === "op" && first.text === "@") unsupported("decorator", first.line);
  let node: Node;
  if (kw === "pass") {
    p.eat("pass");
    node = { kind: "Pass", line: first.line, kids: [] };
  } else if (kw === "return") {
    p.eat("return");
    node = { kind: "Return", line: first.line, kids: p.done ? [] : [p.testlist()] };
  } else if (kw === "raise") {
    p.eat("raise");
    const kids: Node[] = [];
    if (!p.done) {
      kids.push(p.test());
      if (p.eat("from")) kids.push(p.test());
    }
    node = { kind: "Raise", line: first.line, kids };
  } else if (kw === "assert") {
    p.eat("assert");
    const kids = [p.test()];
    if (p.eat(",")) kids.push(p.test());
    node = { kind: "Assert", line: first.line, kids };
  } else if (kw === "import" || kw === "from") {
    return { kind: "Import", line: first.line, kids: [] };
  } else {
    const exprs = [p.testlist()];
    const aug = p.peek();
    if (aug && aug.kind === "op" && aug.text.length >= 2 && aug.text.endsWith("=") && !["==", "<=", ">=", "!="].includes(aug.text)) {
      p.eat(aug.text);
      node = { kind: "AugAssign", line: first.line, kids: [exprs[0], p.testlist()] };
    } else {
      while (p.eat("=")) exprs.push(p.testlist());
      node = exprs.length === 1
        ? { kind: "Expr", line: first.line, kids: [exprs[0]] }
        : { kind: "Assign", line: first.line, kids: exprs };
    }
  }
  if (!p.done) unsupported(`trailing '${p.peek()!.text}'`, p.peek()!.line);
  return node;
}

/** Split a logical line on top-level `;`. */
function simpleStatements(toks: Tok[]): Tok[][] {
  const out: Tok[][] = [[]];
  let depth = 0;
  for (const t of toks) {
    if (t.kind === "op" && "([{".includes(t.text)) depth++;
    if (t.kind === "op" && ")]}".includes(t.text)) depth--;
    if (depth === 0 && t.kind === "op" && t.text === ";") out.push([]);
    else out[out.length - 1].push(t);
  }
  return out.filter((s) => s.length > 0);
}

export interface PyDef {
  name: string;
  line: number;
  kind: "FunctionDef" | "ClassDef";
  calls: { line: number; callee: string | null }[];
}

/** ast.parse(src).body's FunctionDef/ClassDef nodes, and for each FunctionDef the Call nodes of
 *  ast.walk(def) in order: breadth-first over the ast children. */
export function pythonDefs(src: string): PyDef[] {
  const lines = tokenize(src);
  const defs: PyDef[] = [];
  for (let k = 0; k < lines.length; k++) {
    const toks = lines[k];
    if (toks[0].col !== 0) unsupported("indented line outside a def", toks[0].line);
    const head = toks[0].text;
    const bodyLines: Tok[][] = [];
    while (k + 1 < lines.length && lines[k + 1][0].col > 0) bodyLines.push(lines[++k]);
    if (toks[0].kind === "op" && head === "@") unsupported("decorator", toks[0].line);
    if (head === "async") continue; // AsyncFunctionDef is neither a FunctionDef nor a ClassDef
    if (head === "class") {
      defs.push({ name: toks[1].text, line: toks[0].line, kind: "ClassDef", calls: [] });
      continue;
    }
    if (head !== "def") continue;
    // def NAME ( plain, names ) : [simple statement]
    const name = toks[1].text;
    let j = 3;
    const params: Node[] = [];
    while (toks[j] && toks[j].text !== ")") {
      if (toks[j].kind !== "name") unsupported("parameter default, annotation or star", toks[j].line);
      params.push({ kind: "arg", line: toks[j].line, kids: [] });
      j++;
      if (toks[j]?.text === ",") j++;
      else if (toks[j]?.text !== ")") unsupported("parameter default, annotation or star", toks[j]?.line ?? 0);
    }
    if (toks[j + 1]?.text !== ":") unsupported("return annotation", toks[0].line);
    const inline = toks.slice(j + 2);
    const body: Node[] = [];
    for (const stmtToks of [inline, ...bodyLines].flatMap(simpleStatements)) body.push(statement(stmtToks));
    const root: Node = { kind: "FunctionDef", line: toks[0].line, kids: [{ kind: "arguments", line: 0, kids: params }, ...body] };
    const calls: PyDef["calls"] = [];
    const queue: Node[] = [root];
    while (queue.length > 0) {
      const node = queue.shift() as Node;
      queue.push(...node.kids);
      if (node.kind === "Call") {
        const f = node.kids[0];
        calls.push({ line: node.line, callee: f.kind === "Name" || f.kind === "Attribute" ? (f.name as string) : null });
      }
    }
    defs.push({ name, line: toks[0].line, kind: "FunctionDef", calls });
  }
  return defs;
}

// ---------------------------------------------------------------------------------------------

const lineKey = (path: string, n: number) => `${path} ${n}`;
/** json.dumps(value, sort_keys=True) — used only as an equality signature. */
function signature(v: PyValue): string {
  const sortKeys = (x: PyValue): PyValue => {
    if (Array.isArray(x)) return x.map(sortKeys);
    if (isPyObj(x)) return obj([...x.__pyObj].sort(([a], [b]) => cpCompare(a, b)).map(([k, y]) => [k, sortKeys(y)]));
    return x;
  };
  return dumps(sortKeys(v));
}
/** Python's str.isdecimal digits: every Nd run is a contiguous 0-9 block, so a digit's value is its
 *  offset from the start of its run, mod 10. */
function pyIntDigits(s: string): number {
  let v = 0;
  for (const ch of s) {
    let cp = ch.codePointAt(0) as number;
    let start = cp;
    while (/\p{Nd}/u.test(String.fromCodePoint(start - 1))) start--;
    cp = (cp - start) % 10;
    v = v * 10 + cp;
  }
  return v;
}

/** All tools derive results from FILES; no shell, disk, network, exec, or eval. */
export class Workspace {
  files = new Map(FILES);
  calls: PyValue[] = [];
  repeated_calls = 0;
  private seenCalls = new Set<string>();
  private lines = new Set<string>();
  private facts = new Set<string>();
  private noNewEvidence = 0;
  private quality = obj([]);
  private trace: PyValue[] = [];

  execute(tool: { name: PyValue; input: PyValue }): string {
    const name = tool.name;
    const args = tool.input;
    this.calls.push(name);
    const sig = signature([name, args]);
    this.repeated_calls += this.seenCalls.has(sig) ? 1 : 0;
    this.seenCalls.add(sig);
    this.trace.push(obj([["name", name], ["input", args]]));
    const before = [this.lines.size, this.facts.size];
    const handlers: Record<string, (a: PyValue) => string> = {
      Read: (a) => this.Read(a), Grep: (a) => this.Grep(a), LSP: (a) => this.LSP(a), Bash: (a) => this.Bash(a),
    };
    hashKey(name); // `name not in handlers` hashes the name: a list or dict name is a TypeError
    if (typeof name !== "string" || !Object.hasOwn(handlers, name)) {
      throw new ValueError("unknown or unavailable synthetic tool");
    }
    const result = handlers[name](args);
    this.noNewEvidence += before[0] === this.lines.size && before[1] === this.facts.size ? 1 : 0;
    return result;
  }

  private Read(args: PyValue): string {
    Workspace.exact(args, ["file_path"]);
    const path = sub(args, "file_path") as string;
    const text = this.files.get(path);
    if (text === undefined) throw new ValueError("unknown fixture path");
    const n = pySplitlines(text).length;
    for (let i = 1; i <= n; i++) this.lines.add(lineKey(path, i));
    return text;
  }

  private Grep(args: PyValue): string {
    Workspace.exact(args, ["pattern", "path"]);
    const pattern = sub(args, "pattern") as string;
    const prefix = sub(args, "path") as string;
    const trimmed = prefix.replace(/\/+$/, "");
    const paths = [...this.files.keys()].filter((p) => prefix === "." || p === prefix || p.startsWith(trimmed + "/"));
    if (!pattern || paths.length === 0) throw new ValueError("invalid literal search");
    const rows: string[] = [];
    for (const path of paths) {
      pySplitlines(this.files.get(path) as string).forEach((line, idx) => {
        if (line.includes(pattern)) {
          rows.push(`${path}:${idx + 1}:${line}`);
          this.lines.add(lineKey(path, idx + 1));
        }
      });
    }
    return rows.join("\n");
  }

  private LSP(args: PyValue): string {
    Workspace.exact(args, ["operation", "file_path", "symbol"]);
    const operation = sub(args, "operation") as string;
    const path = sub(args, "file_path") as string;
    const symbol = sub(args, "symbol") as string;
    if (!this.files.has(path) || !path.endsWith(".py")) throw new ValueError("LSP requires a Python fixture");
    const defs = pythonDefs(this.files.get(path) as string);
    let rows: PyValue[];
    if (operation === "documentSymbol" && !symbol) {
      rows = defs.map((d) => obj([["name", d.name], ["line", { __pyNum: String(d.line), isFloat: false }]]));
      for (const d of defs) this.lines.add(lineKey(path, d.line));
    } else if (operation === "findReferences" && symbol) {
      if (!defs.some((d) => d.kind === "FunctionDef" && d.name === symbol)) {
        throw new ValueError("symbol is not defined in the given fixture");
      }
      rows = [];
      for (const [candidate, source] of this.files) {
        if (!candidate.endsWith(".py")) continue;
        for (const enclosing of pythonDefs(source)) {
          if (enclosing.kind !== "FunctionDef") continue;
          for (const call of enclosing.calls) {
            if (call.callee !== symbol) continue;
            rows.push(obj([
              ["path", candidate], ["line", { __pyNum: String(call.line), isFloat: false }],
              ["caller", enclosing.name], ["caller_line", { __pyNum: String(enclosing.line), isFloat: false }],
            ]));
            this.lines.add(lineKey(candidate, call.line));
            this.lines.add(lineKey(candidate, enclosing.line));
          }
        }
      }
    } else throw new ValueError("invalid LSP operation");
    this.facts.add(JSON.stringify([operation, path, symbol]));
    return dumps(rows);
  }

  private Bash(args: PyValue): string {
    Workspace.exact(args, ["command"]);
    const words = shlexSplit(sub(args, "command") as string);
    if (words.length === 4 && words.join(" ") === ["find", ".", "-type", "f"].join(" ")) {
      for (const path of this.files.keys()) this.facts.add(JSON.stringify(["path", path]));
      return [...this.files.keys()].join("\n");
    }
    if (words.length === 2 && words[0] === "cat") return this.Read(obj([["file_path", words[1]]]));
    if (words.length === 4 && words[0] === "grep" && words[1] === "-n" && this.files.has(words[3])) {
      return this.Grep(obj([["pattern", words[2]], ["path", words[3]]]));
    }
    throw new ValueError("command is outside the read-only fixture allowlist");
  }

  /** isinstance(args, dict), exactly the expected keys, each value exactly a str. */
  private static exact(args: PyValue, expected: string[]): void {
    if (!isPyObj(args)) throw new ValueError("invalid tool input");
    const keys = new Set(args.__pyObj.map(([k]) => k));
    if (keys.size !== expected.length || !expected.every((k) => keys.has(k))
      || expected.some((k) => typeof sub(args, k) !== "string")) {
      throw new ValueError("invalid tool input");
    }
  }

  correct(caseName: string, text: PyValue): boolean {
    const expected = CONTRACTS[caseName];
    let answer: PyValue | undefined;
    if (typeof text !== "string") answer = null; // json.loads(non-str) is a TypeError, caught
    else {
      try {
        answer = loads(text);
      } catch (e) {
        if (!(e instanceof SyntaxError)) throw e;
        answer = null;
      }
    }
    const o = isPyObj(answer) ? answer : obj([]);
    const expectedKeys = expected.__pyObj.map(([k]) => k);
    const answerKeys = new Set(o.__pyObj.map(([k]) => k));
    this.quality = obj([["json_shape", isPyObj(answer) && answerKeys.size === expectedKeys.length
      && expectedKeys.every((k) => answerKeys.has(k))]]);
    for (const [key, value] of expected.__pyObj) {
      if (key === "citations") continue;
      const actual = get(o, key);
      let ok: boolean;
      if (Array.isArray(value) && Array.isArray(actual) && actual.every((v) => typeof v === "string")) {
        const a = (actual as string[]).slice().sort(cpCompare);
        const b = (value as string[]).slice().sort(cpCompare);
        ok = a.length === b.length && a.every((x, i) => x === b[i]);
      } else ok = sameTypeEqual(actual, value);
      setKey(this.quality, key, ok);
    }
    const references = get(o, "citations", []);
    const citedPaths = new Set<string>();
    let valid = Array.isArray(references) && references.length > 0;
    if (Array.isArray(references)) {
      for (const ref of references) {
        const m = typeof ref === "string" ? /^([^\n]+):(\p{Nd}+)(?:-(\p{Nd}+))?$/u.exec(ref) : null;
        if (!m) {
          valid = false;
          continue;
        }
        const [, path, first, last] = m;
        const lo = pyIntDigits(first);
        const hi = pyIntDigits(last ?? first);
        const nonEmpty = hi >= lo;
        // lines.issubset(self._lines), without materializing a range the model may have made huge:
        // a range wider than the lines ever seen cannot be a subset.
        let subset = nonEmpty && hi - lo + 1 <= this.lines.size;
        for (let n = lo; subset && n <= hi; n++) subset = this.lines.has(lineKey(path, n));
        valid = valid && nonEmpty && subset;
        if (nonEmpty) citedPaths.add(path);
      }
    }
    const required = REQUIRED_LINES[caseName];
    setKey(this.quality, "valid_citations", valid);
    setKey(this.quality, "required_sources_cited", required.every(([p]) => citedPaths.has(p)));
    setKey(this.quality, "source_evidence", required.every(([p, n]) => this.lines.has(lineKey(p, n))));
    return this.quality.__pyObj.every(([, v]) => v === true);
  }

  metrics(): PyObj {
    // dict(Counter(self.calls)): first-seen key order, keys merged by hash equality, and rendered as
    // json.dumps renders a non-str key.
    const counts = new Map<string, [PyValue, number]>();
    for (const name of this.calls) {
      const k = hashKey(name);
      const hit = counts.get(k);
      if (hit) hit[1]++;
      else counts.set(k, [name, 1]);
    }
    const jsonKey = (v: PyValue): string => {
      if (typeof v === "string") return v;
      if (v === null) return "null";
      if (typeof v === "boolean") return v ? "true" : "false";
      return dumps(v);
    };
    const passed = this.quality.__pyObj.filter(([, v]) => v === true).length;
    return obj([
      ["tool_counts", obj([...counts.values()].map(([k, n]) => [jsonKey(k), { __pyNum: String(n), isFloat: false }]))],
      ["exact_duplicate_calls", { __pyNum: String(this.repeated_calls), isFloat: false }],
      ["no_new_evidence_calls", { __pyNum: String(this.noNewEvidence), isFloat: false }],
      ["quality_checks", obj([
        ["passed", { __pyNum: String(passed), isFloat: false }],
        ["total", { __pyNum: String(this.quality.__pyObj.length), isFloat: false }],
        ["checks", obj(this.quality.__pyObj.map(([k, v]) => [k, v]))],
      ])],
      ["trace", [...this.trace]],
    ]);
  }
}

/** `actual == value and type(actual) is type(value)` for the contract's scalar types. */
function sameTypeEqual(actual: PyValue, value: PyValue): boolean {
  if (typeof value === "string") return actual === value;
  if (typeof value === "boolean") return actual === value;
  if (value === null) return actual === null;
  if (Array.isArray(value)) {
    // A list contract whose actual is not a list of str: list == list compares elementwise, and a
    // non-str element can never equal a str one.
    return false;
  }
  const v = value as { __pyNum: string; isFloat: boolean };
  const a = actual as { __pyNum?: string; isFloat?: boolean };
  return typeof actual === "object" && actual !== null && a.__pyNum !== undefined && a.isFloat === v.isFloat
    && (v.isFloat ? Number(a.__pyNum) === Number(v.__pyNum) : BigInt(a.__pyNum) === BigInt(v.__pyNum));
}

// ---------------------------------------------------------------------------------------------

const read = (path: string): PyObj => obj([["file_path", path]]);
const call = (name: string, input: PyValue) => ({ name, input });
const contractText = (c: string) => dumps(CONTRACTS[c]);

export const tests: Tests = {
  test_prompts_do_not_supply_the_answers() {
    const prompts = Object.fromEntries(CASES);
    check.notIn('"timeout_seconds":15', prompts["configuration-audit"]);
    check.notIn('"app/service.py:start"', prompts["function-callers"]);
    check.notIn('"consumer":"app/service.py:start"', prompts["config-consumer-trace"]);
    check.notIn('"bug":"normalize_timeout accepts 0"', prompts["timeout-boundary-bug"]);
    check.in("<code_mode_orchestration>", GUIDANCE);
    check.in("tools.call('Read', args)", GUIDANCE);
    check.notIn("Promise.all", SYSTEM);
  },

  test_source_name_alone_is_not_fact_evidence() {
    const w = new Workspace();
    for (const path of ["config/settings.toml", "config/worker.toml", "config/cache.toml"]) {
      w.execute(call("Grep", obj([["pattern", "["], ["path", path]])));
    }
    check.false(w.correct("configuration-audit", contractText("configuration-audit")));
  },

  test_all_oracles_accept_read_or_shell_evidence() {
    for (const c of Object.keys(CONTRACTS)) {
      for (const name of ["Read", "Bash"]) {
        const w = new Workspace();
        for (const path of new Set(REQUIRED_LINES[c].map(([p]) => p))) {
          w.execute(call(name, name === "Read" ? read(path) : obj([["command", "cat " + path]])));
        }
        const answer = obj(CONTRACTS[c].__pyObj.map(([k, v]) => [k, v]));
        setKey(answer, "citations", [...(get(CONTRACTS[c], "citations") as PyValue[])].reverse());
        check.true(w.correct(c, dumps(answer)), `${c} ${dumps(w.metrics())}`);
      }
    }
  },

  test_lsp_derives_callers_excluding_decoys() {
    const w = new Workspace();
    const refs = loads(w.execute(call("LSP", obj([
      ["operation", "findReferences"], ["file_path", "app/limits.py"], ["symbol", "build_client"],
    ])))) as PyValue[];
    check.equal(new Set(["start", "worker_client"]), new Set(refs.map((r) => get(r, "caller") as string)));
    check.equal(2, refs.length);
    check.true(w.correct("function-callers", contractText("function-callers")));
  },

  test_wrong_facts_and_unread_citations_fail() {
    for (const [c, contract] of Object.entries(CONTRACTS)) {
      const w = new Workspace();
      check.false(w.correct(c, dumps(contract)));
      for (const [path] of FILES) w.execute(call("Read", read(path)));
      check.true(w.correct(c, dumps(contract)));
      let bad = obj(contract.__pyObj.map(([k, v]) => [k, v]));
      setKey(bad, "citations", ["not-a-file:1"]);
      check.false(w.correct(c, dumps(bad)));
      bad = obj(contract.__pyObj.map(([k, v]) => [k, v]));
      const key = contract.__pyObj.map(([k]) => k).find((k) => k !== "citations") as string;
      setKey(bad, key, null);
      check.false(w.correct(c, dumps(bad)));
    }
  },

  test_redundancy_tracks_new_lines_not_just_file_names() {
    const w = new Workspace();
    w.execute(call("Grep", obj([["pattern", "build_client"], ["path", "app/service.py"]])));
    w.execute(call("Read", read("app/service.py")));
    check.equal(0, get(w.metrics(), "no_new_evidence_calls"));
    w.execute(call("Bash", obj([["command", "cat app/service.py"]])));
    w.execute(call("Read", read("app/service.py")));
    check.equal(2, get(w.metrics(), "no_new_evidence_calls"));
    check.equal(1, w.repeated_calls);
  },

  async test_invalid_operations_do_not_touch_the_host() {
    for (const t of [
      call("Bash", obj([["command", "cat /etc/passwd"]])),
      call("Read", read("../anything")),
      call("Bash", obj([["command", "find . -type f; true"]])),
      call("mcp__deploy__status", obj([])),
    ]) {
      await check.raises((e) => e instanceof ValueError, () => new Workspace().execute(t));
    }
  },

  // ADDED IN THE PORT: the Python-subset parser must reproduce what ast.parse/ast.walk gave for
  // every fixture file — pinned from CPython 3.13 at conversion time.
  test_python_subset_matches_ast_for_every_fixture() {
    const got = FILES.filter(([p]) => p.endsWith(".py")).map(([p, src]) =>
      [p, pythonDefs(src).map((d) => [d.name, d.line, d.kind, d.calls.map((c) => [c.callee, c.line])])]);
    check.equal([
      ["app/config.py", [["load_timeout", 1, "FunctionDef", []]]],
      ["app/limits.py", [["build_client", 1, "FunctionDef", []],
        ["normalize_timeout", 5, "FunctionDef", [["min", 6], ["max", 6]]]]],
      ["app/service.py", [["start", 5, "FunctionDef", [["build_client", 6], ["load_timeout", 6]]]]],
      ["app/worker.py", [["worker_client", 4, "FunctionDef", [["build_client", 5]]]]],
      ["tests/test_limits.py", [["test_zero_is_currently_accepted", 4, "FunctionDef", [["normalize_timeout", 5]]]]],
    ], got);
  },
};

const HELP = `usage: code_mode_guidance.ts [-h] [--selftest]

Frozen synthetic repository and evidence oracles for the prompt-only A/B.

options:
  -h, --help  show this help message and exit
  --selftest
`;
const USAGE = "usage: code_mode_guidance.ts [-h] [--selftest]\n";

if (import.meta.main) {
  const args = argparse(process.argv.slice(2), [{ flag: "--selftest", kind: "true" }], "code_mode_guidance.ts", USAGE, HELP);
  if (args.selftest) process.exit(await runUnittest("code_mode_guidance", "GuidanceTests", tests));
  argparseError("code_mode_guidance.ts", USAGE, "choose --selftest");
}
