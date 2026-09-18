#!/usr/bin/env bun
/** Python semantics the code-mode harnesses (code_mode_*.ts) were written against, in one place.
 *
 *  WHY THIS EXISTS. The code-mode A/B and mock harnesses were Python whose receipts record the NAME
 *  of whatever exception ended a run (`type(exc).__name__`) and whose control flow catches by class
 *  (`except (OSError, ValueError, KeyError, TypeError, HTTPException)`), while everything outside the
 *  tuple — an AttributeError from `.get` on a list, an IndexError — escapes and crashes the run. A
 *  port that returns undefined where Python raised turns a loud crash into a silent wrong row, and a
 *  port that raises the wrong class moves a failure across a catch boundary. So the values the
 *  harnesses read off the wire go through the accessors here, which raise exactly where Python
 *  raised and with the class Python raised:
 *
 *    get(d, k, dflt)  d.get(k, dflt)   AttributeError on a non-dict
 *    sub(d, k)        d[k]             KeyError / TypeError ('X' object is not subscriptable)
 *    at(list, i)      list[i]          IndexError
 *    iter(v)          for x in v       dict -> keys, str -> characters, else TypeError
 *    hashKey(v)       hash(v) for set/dict membership: 1 == 1.0 == True, unhashable -> TypeError
 *
 *  It also carries the Python exception hierarchy (by the NAMES receipts record), a unittest-shaped
 *  runner whose per-test lines match `python -m unittest -v`, and an argparse subset.
 */
import { spawn as nodeSpawn } from "node:child_process";
import { constants as osConstants } from "node:os";
import { isPyNum, isPyObj, type PyObj, type PyValue } from "./pyjson.ts";

// ---------------------------------------------------------------------------------------------
// Exceptions. JS TypeError stands for Python's TypeError; JSONDecodeError (pyjson) and
// UnicodeDecodeError are SyntaxErrors there and count as ValueError here, as they subclass it in
// Python.
// ---------------------------------------------------------------------------------------------

export class PyException extends Error {
  constructor(message = "") {
    super(message);
    this.name = new.target.name;
  }
}
export class ValueError extends PyException {}
export class KeyError extends PyException {}
export class AttributeError extends PyException {}
export class IndexError extends PyException {}
export class StopIteration extends PyException {}
export class AssertionError extends PyException {}
export class OSError extends PyException {
  errno: number | null = null;
}
export class FileNotFoundError extends OSError {}
export class FileExistsError extends OSError {}
export class PermissionError extends OSError {}
export class IsADirectoryError extends OSError {}
export class NotADirectoryError extends OSError {}
export class TimeoutError extends OSError {}
export class ConnectionError extends OSError {}
export class ConnectionRefusedError extends ConnectionError {}
export class ConnectionResetError extends ConnectionError {}
export class ConnectionAbortedError extends ConnectionError {}
export class BrokenPipeError extends ConnectionError {}
export class gaierror extends OSError {}
export class SSLError extends OSError {}
export class SSLCertVerificationError extends SSLError {}
/** http.client.HTTPException and the two members this code can meet. RemoteDisconnected is ALSO a
 *  ConnectionResetError in Python (multiple inheritance); JS has one parent, so it extends
 *  ConnectionResetError and isHTTPException() names it explicitly. */
export class HTTPException extends PyException {}
export class IncompleteRead extends HTTPException {}
export class BadStatusLine extends HTTPException {}
export class RemoteDisconnected extends ConnectionResetError {}
export class SubprocessError extends PyException {}
export class TimeoutExpired extends SubprocessError {
  constructor(readonly cmd: string[], readonly timeout: number) {
    super(`Command '${pyReprList(cmd)}' timed out after ${timeout} seconds`);
  }
}
export class BadZipFile extends PyException {}

export const isValueError = (e: unknown) => e instanceof ValueError || e instanceof SyntaxError;
export const isHTTPException = (e: unknown) => e instanceof HTTPException || e instanceof RemoteDisconnected;
export const isOSError = (e: unknown) => e instanceof OSError;
export const isKeyError = (e: unknown) => e instanceof KeyError;
export const isTypeError = (e: unknown) => e instanceof TypeError;
/** type(exc).__name__ */
export const pyName = (e: unknown): string => (e instanceof Error ? e.name : typeof e);

const ERRNO: Record<string, [number, string, typeof OSError]> = {
  ENOENT: [2, "No such file or directory", FileNotFoundError],
  EACCES: [13, "Permission denied", PermissionError],
  EPERM: [1, "Operation not permitted", PermissionError],
  EEXIST: [17, "File exists", FileExistsError],
  EISDIR: [21, "Is a directory", IsADirectoryError],
  ENOTDIR: [20, "Not a directory", NotADirectoryError],
  ECONNREFUSED: [111, "Connection refused", ConnectionRefusedError],
  ECONNRESET: [104, "Connection reset by peer", ConnectionResetError],
  ECONNABORTED: [103, "Software caused connection abort", ConnectionAbortedError],
  EPIPE: [32, "Broken pipe", BrokenPipeError],
  ETIMEDOUT: [110, "Connection timed out", TimeoutError],
  EADDRINUSE: [98, "Address already in use", OSError],
  EADDRNOTAVAIL: [99, "Cannot assign requested address", OSError],
  EHOSTUNREACH: [113, "No route to host", OSError],
  ENETUNREACH: [101, "Network is unreachable", OSError],
};
/** The OSError subclass and `str()` Python gives the same failure: `[Errno 2] No such file or
 *  directory: '/x'`. Anything already Python-shaped passes through untouched. */
export function osError(e: unknown, filename?: string): Error {
  if (e instanceof PyException) return e;
  const code = (e as { code?: string }).code ?? "";
  if (code === "ENOTFOUND" || code === "EAI_AGAIN") {
    return new gaierror(code === "ENOTFOUND" ? "[Errno -2] Name or service not known" : "[Errno -3] Temporary failure in name resolution");
  }
  if (code.startsWith("ERR_TLS") || code.includes("CERT") || code === "DEPTH_ZERO_SELF_SIGNED_CERT") {
    return new SSLCertVerificationError(String((e as Error).message));
  }
  const known = ERRNO[code];
  if (known === undefined) {
    const err = new OSError(String((e as Error).message ?? e));
    return err;
  }
  const [errno, text, Cls] = known;
  const err = new Cls(filename === undefined ? `[Errno ${errno}] ${text}` : `[Errno ${errno}] ${text}: ${pyRepr(filename)}`);
  err.errno = errno;
  return err;
}

// ---------------------------------------------------------------------------------------------
// Values.
// ---------------------------------------------------------------------------------------------

export function typeName(v: PyValue | undefined): string {
  if (v === null || v === undefined) return "NoneType";
  if (typeof v === "boolean") return "bool";
  if (typeof v === "string") return "str";
  if (Array.isArray(v)) return "list";
  if (isPyNum(v)) return v.isFloat ? "float" : "int";
  return "dict";
}
/** d.get(k, dflt) — a non-dict has no .get. */
export function get(d: PyValue, key: string, dflt: PyValue = null): PyValue {
  if (!isPyObj(d)) throw new AttributeError(`'${typeName(d)}' object has no attribute 'get'`);
  const hit = d.__pyObj.find(([k]) => k === key);
  return hit === undefined ? dflt : hit[1];
}
/** d[k] for a string key. A list or str indexed by a str is a TypeError, as in Python. */
export function sub(d: PyValue, key: string): PyValue {
  if (isPyObj(d)) {
    const hit = d.__pyObj.find(([k]) => k === key);
    if (hit === undefined) throw new KeyError(pyRepr(key));
    return hit[1];
  }
  if (Array.isArray(d)) throw new TypeError("list indices must be integers or slices, not str");
  if (typeof d === "string") throw new TypeError("string indices must be integers, not 'str'");
  throw new TypeError(`'${typeName(d)}' object is not subscriptable`);
}
/** seq[i] with Python's negative indexing and IndexError. */
export function at(seq: PyValue, i: number): PyValue {
  if (Array.isArray(seq)) {
    const j = i < 0 ? seq.length + i : i;
    if (j < 0 || j >= seq.length) throw new IndexError("list index out of range");
    return seq[j];
  }
  if (typeof seq === "string") {
    const cps = Array.from(seq);
    const j = i < 0 ? cps.length + i : i;
    if (j < 0 || j >= cps.length) throw new IndexError("string index out of range");
    return cps[j];
  }
  if (isPyObj(seq)) throw new KeyError(String(i));
  throw new TypeError(`'${typeName(seq)}' object is not subscriptable`);
}
export function has(d: PyValue, key: string): boolean {
  return isPyObj(d) && d.__pyObj.some(([k]) => k === key);
}
/** d[k] = v: a new key appends, an existing key keeps its position. */
export function setKey(d: PyObj, key: string, value: PyValue): void {
  const hit = d.__pyObj.find(([k]) => k === key);
  if (hit) hit[1] = value;
  else d.__pyObj.push([key, value]);
}
/** `for x in v` */
export function iter(v: PyValue): PyValue[] {
  if (Array.isArray(v)) return v;
  if (typeof v === "string") return Array.from(v);
  if (isPyObj(v)) return v.__pyObj.map(([k]) => k);
  throw new TypeError(`'${typeName(v)}' object is not iterable`);
}
export const numValue = (v: PyValue): number =>
  typeof v === "boolean" ? (v ? 1 : 0) : Number((v as { __pyNum: string }).__pyNum);
/** Python truthiness. */
export function truthy(v: PyValue | undefined): boolean {
  if (v === null || v === undefined || v === false) return false;
  if (v === true) return true;
  if (typeof v === "string" || Array.isArray(v)) return v.length > 0;
  if (isPyNum(v)) return numValue(v) !== 0;
  return (v as PyObj).__pyObj.length > 0;
}
const isNumeric = (v: PyValue) => typeof v === "boolean" || isPyNum(v);
/** Exact numeric value for comparison: ints as BigInt, integral floats too, other floats as-is. */
function exact(v: PyValue): bigint | number {
  if (typeof v === "boolean") return v ? 1n : 0n;
  const n = v as { __pyNum: string; isFloat: boolean };
  if (!n.isFloat) return BigInt(n.__pyNum);
  const x = Number(n.__pyNum);
  return Number.isInteger(x) ? BigInt(x) : x;
}
/** Python ==: dicts regardless of key order, 1 == 1.0 == True, NaN != NaN. */
export function pyEq(a: PyValue, b: PyValue): boolean {
  if (isNumeric(a) && isNumeric(b)) {
    const x = exact(a);
    const y = exact(b);
    return typeof x === typeof y ? x === y : false;
  }
  if (a === null || b === null || typeof a !== "object" || typeof b !== "object") return a === b;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => pyEq(x, b[i]));
  }
  if (isPyObj(a) && isPyObj(b)) {
    if (a.__pyObj.length !== b.__pyObj.length) return false;
    return a.__pyObj.every(([k, v]) => has(b, k) && pyEq(v, get(b, k)));
  }
  return false;
}
/** `x in seq` for a list or tuple: membership by ==. */
export const pyIn = (x: PyValue, seq: PyValue[]): boolean => seq.some((y) => pyEq(x, y));

let nanSerial = 0;
/** A string that is equal for exactly the values Python's hash/== treat as one key. */
export function hashKey(v: PyValue): string {
  if (v === null) return "None";
  if (typeof v === "string") return "s:" + v;
  if (isNumeric(v)) {
    const n = v as PyValue;
    if (isPyNum(n) && n.isFloat && Number.isNaN(Number(n.__pyNum))) return `nan:${nanSerial++}`;
    const x = exact(n);
    return typeof x === "bigint" ? "n:" + x.toString() : "f:" + String(x);
  }
  throw new TypeError(`unhashable type: '${typeName(v)}'`);
}

/** repr() of a str. */
export function pyRepr(s: string): string {
  const q = s.includes("'") && !s.includes('"') ? '"' : "'";
  let body = "";
  for (const ch of s) {
    const cp = ch.codePointAt(0) as number;
    if (ch === "\\") body += "\\\\";
    else if (ch === q) body += "\\" + q;
    else if (ch === "\n") body += "\\n";
    else if (ch === "\r") body += "\\r";
    else if (ch === "\t") body += "\\t";
    else if (cp < 0x20 || cp === 0x7f) body += "\\x" + cp.toString(16).padStart(2, "0");
    else body += ch;
  }
  return q + body + q;
}
/** repr() of a list of str. */
export const pyReprList = (xs: string[]): string => "[" + xs.map(pyRepr).join(", ") + "]";

const PY_WS = " \t\n\r\x0b\x0c\x1c\x1d\x1e\x1f\x85\xa0                　";
/** str.rstrip() with Python's whitespace set. */
export function pyRstrip(s: string): string {
  let b = s.length;
  while (b > 0 && PY_WS.includes(s[b - 1])) b--;
  return s.slice(0, b);
}
/** str.splitlines(). */
export function pySplitlines(s: string): string[] {
  const out: string[] = [];
  let cur = "";
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === "\r" && s[i + 1] === "\n") {
      out.push(cur);
      cur = "";
      i++;
    } else if ("\n\r\x0b\x0c\x1c\x1d\x1e\x85  ".includes(ch)) {
      out.push(cur);
      cur = "";
    } else cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}
/** Code-point order (Python's str order); JS's default sort is UTF-16 unit order. */
export function cpCompare(a: string, b: string): number {
  const x = Array.from(a);
  const y = Array.from(b);
  for (let i = 0; i < Math.min(x.length, y.length); i++) {
    const d = (x[i].codePointAt(0) as number) - (y[i].codePointAt(0) as number);
    if (d !== 0) return d;
  }
  return x.length - y.length;
}
/** int(str): surrounding whitespace, a sign, Unicode decimal digits with single underscores
 *  between them; anything else is Python's ValueError, message included. */
export function pyInt(s: string): number {
  const t = s.replace(new RegExp(`^[${PY_WS}]+|[${PY_WS}]+$`, "gu"), "");
  if (!/^[+-]?\p{Nd}+(?:_\p{Nd}+)*$/u.test(t)) throw new ValueError(`invalid literal for int() with base 10: ${pyRepr(s)}`);
  let v = 0;
  for (const ch of t.replace(/^[+-]/, "").replaceAll("_", "")) {
    const cp = ch.codePointAt(0) as number;
    let start = cp;
    // Every Nd run is a contiguous 0-9 block, so a digit's value is its offset in the run, mod 10.
    while (/\p{Nd}/u.test(String.fromCodePoint(start - 1))) start--;
    v = v * 10 + ((cp - start) % 10);
  }
  return t.startsWith("-") ? -v : v;
}
/** round(x) with no ndigits: nearest int, ties to even. */
export function pyRoundInt(x: number): number {
  const f = Math.floor(x);
  const d = x - f;
  if (d > 0.5) return f + 1;
  if (d < 0.5) return f;
  return f % 2 === 0 ? f : f + 1;
}

// ---------------------------------------------------------------------------------------------
// subprocess.Popen, for the `java -jar app-all.jar daemon` launches.
// ---------------------------------------------------------------------------------------------

export interface Proc {
  pid: number;
  poll(): number | null;
  terminate(): void;
  kill(): void;
  wait(timeout?: number): Promise<number | null> | number | null;
}

export function popen(argv: string[], opts: { env: Record<string, string>; cwd: string; stdoutFd: number }): Proc {
  const child = nodeSpawn(argv[0], argv.slice(1), {
    cwd: opts.cwd,
    env: opts.env,
    stdio: ["inherit", opts.stdoutFd, opts.stdoutFd],
  });
  if (child.pid === undefined) {
    // Popen raises synchronously; node reports a missing binary only through an async event.
    child.on("error", () => {});
    throw new FileNotFoundError(`[Errno 2] No such file or directory: ${pyRepr(argv[0])}`);
  }
  let returncode: number | null = null;
  const exited = new Promise<number>((res) => {
    child.on("exit", (code, signal) => {
      returncode = code ?? -(osConstants.signals[signal as NodeJS.Signals] ?? 0);
      res(returncode);
    });
  });
  const signal = (sig: NodeJS.Signals) => {
    // Popen.send_signal: a reaped child is left alone, a vanished one is not an error.
    if (returncode !== null) return;
    try {
      process.kill(child.pid as number, sig);
    } catch (e) {
      if ((e as { code?: string }).code !== "ESRCH") throw osError(e);
    }
  };
  return {
    pid: child.pid,
    poll: () => returncode,
    terminate: () => signal("SIGTERM"),
    kill: () => signal("SIGKILL"),
    wait: (timeout?: number) => {
      if (returncode !== null) return returncode;
      if (timeout === undefined) return exited;
      return new Promise<number>((res, rej) => {
        const t = setTimeout(() => rej(new TimeoutExpired(argv, timeout)), timeout * 1000);
        void exited.then((rc) => {
          clearTimeout(t);
          res(rc);
        });
      });
    },
  };
}

// ---------------------------------------------------------------------------------------------
// unittest. One class per module, tests run in NAME order (unittest's loader sorts them), each
// reported as `python -m unittest -v` reports it; the summary goes to stderr like unittest's.
// ---------------------------------------------------------------------------------------------

export type Tests = Record<string, () => void | Promise<void>>;

export async function runUnittest(module: string, cls: string, tests: Tests): Promise<number> {
  const names = Object.keys(tests).filter((n) => n.startsWith("test")).sort();
  const started = performance.now();
  const failures: [string, string][] = [];
  const errors: [string, string][] = [];
  for (const name of names) {
    const label = `${name} (${module}.${cls}.${name})`;
    try {
      await tests[name]();
      process.stderr.write(`${label} ... ok\n`);
    } catch (e) {
      const trace = e instanceof Error ? (e.stack ?? `${e.name}: ${e.message}`) : String(e);
      if (e instanceof AssertionError) {
        process.stderr.write(`${label} ... FAIL\n`);
        failures.push([label, trace]);
      } else {
        process.stderr.write(`${label} ... ERROR\n`);
        errors.push([label, trace]);
      }
    }
  }
  const rule = "=".repeat(70);
  for (const [kind, list] of [["ERROR", errors], ["FAIL", failures]] as const) {
    for (const [label, trace] of list) process.stderr.write(`\n${rule}\n${kind}: ${label}\n${"-".repeat(70)}\n${trace}\n`);
  }
  const secs = ((performance.now() - started) / 1000).toFixed(3);
  process.stderr.write(`\n${"-".repeat(70)}\nRan ${names.length} test${names.length === 1 ? "" : "s"} in ${secs}s\n\n`);
  if (failures.length + errors.length === 0) {
    process.stderr.write("OK\n");
    return 0;
  }
  const parts = [failures.length ? `failures=${failures.length}` : "", errors.length ? `errors=${errors.length}` : ""];
  process.stderr.write(`FAILED (${parts.filter(Boolean).join(", ")})\n`);
  return 1;
}

/** The TestCase assertions these suites use. Equality is Python == over the tagged tree; plain JS
 *  values compare structurally. */
export const check = {
  equal(expected: unknown, actual: unknown, msg?: string): void {
    if (!deepEq(expected, actual)) {
      throw new AssertionError(msg ?? `${show(expected)} != ${show(actual)}`);
    }
  },
  true(v: unknown, msg?: string): void {
    if (!jsTruthy(v)) throw new AssertionError(msg ?? `${show(v)} is not true`);
  },
  false(v: unknown, msg?: string): void {
    if (jsTruthy(v)) throw new AssertionError(msg ?? `${show(v)} is not false`);
  },
  in(needle: string, hay: string): void {
    if (!hay.includes(needle)) throw new AssertionError(`${pyRepr(needle)} not found in ${pyRepr(hay)}`);
  },
  notIn(needle: string, hay: string): void {
    if (hay.includes(needle)) throw new AssertionError(`${pyRepr(needle)} unexpectedly found in ${pyRepr(hay)}`);
  },
  isNone(v: unknown): void {
    if (v !== null && v !== undefined) throw new AssertionError(`${show(v)} is not None`);
  },
  isNotNone(v: unknown): void {
    if (v === null || v === undefined) throw new AssertionError("unexpectedly None");
  },
  async raises(cls: (e: unknown) => boolean, fn: () => unknown, pattern?: RegExp): Promise<void> {
    try {
      await fn();
    } catch (e) {
      if (!cls(e)) throw e;
      if (pattern && !pattern.test((e as Error).message)) {
        throw new AssertionError(`${pyRepr(String(pattern.source))} does not match ${pyRepr((e as Error).message)}`);
      }
      return;
    }
    throw new AssertionError("exception not raised");
  },
};
function jsTruthy(v: unknown): boolean {
  if (typeof v === "object" && v !== null && (isPyNum(v) || isPyObj(v) || Array.isArray(v))) return truthy(v as PyValue);
  return Boolean(v);
}
function isTagged(v: unknown): boolean {
  return v === null || typeof v === "string" || typeof v === "boolean" || isPyNum(v) || isPyObj(v) || Array.isArray(v);
}
function deepEq(a: unknown, b: unknown): boolean {
  if (typeof a === "number" && isPyNum(b)) return Number((b as { __pyNum: string }).__pyNum) === a && !(b as { isFloat: boolean }).isFloat;
  if (typeof b === "number" && isPyNum(a)) return deepEq(b, a);
  if (isTagged(a) && isTagged(b) && (isPyNum(a) || isPyObj(a) || isPyNum(b) || isPyObj(b))) return pyEq(a as PyValue, b as PyValue);
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => deepEq(x, b[i]));
  if (a instanceof Set && b instanceof Set) return a.size === b.size && [...a].every((x) => b.has(x));
  if (typeof a === "object" && a !== null && typeof b === "object" && b !== null) {
    const ka = Object.keys(a);
    const kb = Object.keys(b);
    return ka.length === kb.length && ka.every((k) => deepEq((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]));
  }
  return a === b;
}
function show(v: unknown): string {
  try {
    return JSON.stringify(v, (_k, x) => (typeof x === "bigint" ? x.toString() : x instanceof Set ? [...x] : x));
  } catch {
    return String(v);
  }
}

// ---------------------------------------------------------------------------------------------
// argparse, for the option shapes these scripts declare: `--flag` (store_true), `--opt VALUE`
// (str or int), `--opt=VALUE`, unique-prefix abbreviation, required options, and `-h`. The usage
// and help texts are the scripts' own (argparse's rendering of the original declarations).
// ---------------------------------------------------------------------------------------------

export interface OptSpec {
  flag: string;
  kind: "true" | "str" | "int";
  required?: boolean;
  dflt?: string | number | boolean | null;
}

export function argparse(argv: string[], spec: OptSpec[], prog: string, usage: string, help: string):
  Record<string, string | number | boolean | null> {
  const fail = (msg: string): never => {
    process.stderr.write(`${usage}${prog}: error: ${msg}\n`);
    process.exit(2);
  };
  const out: Record<string, string | number | boolean | null> = {};
  for (const o of spec) out[dest(o.flag)] = o.dflt ?? (o.kind === "true" ? false : null);
  const seen = new Set<string>();
  for (let i = 0; i < argv.length; i++) {
    const tok = argv[i];
    if (tok === "-h" || tok === "--help" || (tok.startsWith("--h") && "--help".startsWith(tok) && !spec.some((o) => o.flag.startsWith(tok)))) {
      process.stdout.write(help);
      process.exit(0);
    }
    if (!tok.startsWith("-") || tok === "-") fail(`unrecognized arguments: ${argv.slice(i).join(" ")}`);
    const eq = tok.indexOf("=");
    const flag = eq > 0 ? tok.slice(0, eq) : tok;
    const matches = spec.filter((o) => o.flag === flag || o.flag.startsWith(flag));
    const exact = matches.filter((o) => o.flag === flag);
    const pick = exact.length === 1 ? exact : matches;
    if (pick.length === 0) fail(`unrecognized arguments: ${argv.slice(i).join(" ")}`);
    if (pick.length > 1) fail(`ambiguous option: ${flag} could match ${pick.map((o) => o.flag).join(", ")}`);
    const o = pick[0];
    seen.add(o.flag);
    if (o.kind === "true") {
      if (eq > 0) fail(`argument ${o.flag}: ignored explicit argument ${pyRepr(tok.slice(eq + 1))}`);
      out[dest(o.flag)] = true;
      continue;
    }
    let value: string | undefined = eq > 0 ? tok.slice(eq + 1) : argv[++i];
    if (value === undefined || (eq < 0 && value.startsWith("-") && value.length > 1 && !/^-\d/.test(value))) {
      fail(`argument ${o.flag}: expected one argument`);
    }
    value = value as string;
    if (o.kind === "int") {
      if (!/^\s*[+-]?\d+(_\d+)*\s*$/.test(value)) fail(`argument ${o.flag}: invalid int value: ${pyRepr(value)}`);
      out[dest(o.flag)] = Number(value.trim().replaceAll("_", ""));
    } else out[dest(o.flag)] = value;
  }
  const missing = spec.filter((o) => o.required && !seen.has(o.flag)).map((o) => o.flag);
  if (missing.length > 0) fail(`the following arguments are required: ${missing.join(", ")}`);
  return out;
}
const dest = (flag: string) => flag.replace(/^--/, "").replaceAll("-", "_");
/** parser.error(msg): usage, then `prog: error: msg`, exit 2. */
export function argparseError(prog: string, usage: string, msg: string): never {
  process.stderr.write(`${usage}${prog}: error: ${msg}\n`);
  process.exit(2);
}
