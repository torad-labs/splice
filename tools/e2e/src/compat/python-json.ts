#!/usr/bin/env bun
/** A Python-compatible JSON layer, so a ported script emits the SAME BYTES as json.dumps.
 *
 *  WHY THIS EXISTS, and why it is one file rather than a copy per script. Several checks in this
 *  tree both PARSE and RE-EMIT JSON, and Python's json module is not JSON.stringify in four ways
 *  that this campaign has already been bitten by:
 *
 *    1. ensure_ascii — Python escapes every non-ASCII code point to \uXXXX with a surrogate PAIR for
 *       astral characters; JS leaves them literal.
 *    2. NUMBER FORM — Python keeps int and float distinct and renders floats with repr(float):
 *       `1.0` stays `1.0`, `1e-05` keeps a two-digit exponent, `-0.0` keeps its sign.
 *    3. INTEGERS PAST 2^53 — Python is arbitrary precision; JS silently rounds.
 *    4. KEY ORDER — JS reorders integer-like keys ascending; Python keeps insertion order.
 *
 *  (2) and (4) cannot be recovered after JSON.parse, so this layer PARSES to a tagged tree: objects
 *  are ordered pair lists and numbers carry their raw token. Callers that only need to EMIT can
 *  build that tree directly with obj()/num().
 *
 *  Carried from checks/e2e/muse-curate.ts, where the artifact differential found all four.
 */

export type PyNum = { __pyNum: string; isFloat: boolean };
export type PyObj = { __pyObj: [string, PyValue][] };
export type PyValue = null | boolean | string | PyNum | PyObj | PyValue[];

export const isPyNum = (v: unknown): v is PyNum =>
  typeof v === "object" && v !== null && "__pyNum" in (v as Record<string, unknown>);
export const isPyObj = (v: unknown): v is PyObj =>
  typeof v === "object" && v !== null && "__pyObj" in (v as Record<string, unknown>);

/** Build an ordered object, the shape the dumper and redact() both expect. */
export function obj(pairs: [string, PyValue][]): PyObj {
  return { __pyObj: pairs };
}
/** Build a number from its source token: "12" is an int, "1.0" a float. */
export function num(token: string): PyNum {
  return { __pyNum: token, isFloat: /[.eE]/.test(token) };
}
export function objGet(o: PyObj, key: string): PyValue {
  const hit = o.__pyObj.find(([k]) => k === key);
  return hit === undefined ? null : hit[1];
}

/** json.JSONDecodeError — the message is Python's, with a CODE-POINT position, line and column. */
export class JSONDecodeError extends SyntaxError {
  readonly pos: number;
  constructor(readonly msg: string, readonly doc: string, jsPos: number) {
    const prefix = doc.slice(0, jsPos);
    const cp = (t: string) => Array.from(t).length;
    const pos = cp(prefix);
    const nl = prefix.lastIndexOf("\n");
    const lineno = prefix.split("\n").length;
    const colno = pos - (nl < 0 ? -1 : cp(prefix.slice(0, nl)));
    super(`${msg}: line ${lineno} column ${colno} (char ${pos})`);
    this.name = "JSONDecodeError";
    this.pos = pos;
  }
}

/** A transcription of CPython's C scanner (Modules/_json.c): the same accepted language (strict
 *  strings, NaN/Infinity constants, Python's NUMBER_RE, last-wins duplicate keys) and the same error
 *  message at the same position for every rejection, so `str(error)` survives the port. */
class Parser {
  constructor(private readonly s: string) {}

  parse(): PyValue {
    const s = this.s;
    if (s.startsWith("\ufeff")) throw new JSONDecodeError("Unexpected UTF-8 BOM (decode using utf-8-sig)", s, 0);
    const [v, end] = this.scan(this.ws(0));
    const at = this.ws(end);
    if (at !== s.length) throw new JSONDecodeError("Extra data", s, at);
    return v;
  }
  private ws(i: number): number {
    while (i < this.s.length && " \t\n\r".includes(this.s[i]!)) i++;
    return i;
  }
  private scan(i: number): [PyValue, number] {
    const s = this.s;
    if (i >= s.length) throw new JSONDecodeError("Expecting value", s, i);
    const c = s[i];
    if (c === '"') return this.string(i + 1);
    if (c === "{") return this.object(i + 1);
    if (c === "[") return this.array(i + 1);
    const words: [string, PyValue][] = [
      ["null", null], ["true", true], ["false", false],
      ["NaN", { __pyNum: "NaN", isFloat: true }], ["Infinity", { __pyNum: "Infinity", isFloat: true }],
      ["-Infinity", { __pyNum: "-Infinity", isFloat: true }],
    ];
    for (const [w, v] of words) if (s.startsWith(w, i)) return [v, i + w.length];
    // Python's NUMBER_RE exactly: -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)? — a leading zero
    // ends the integer part ("01" is 0 then extra data), and a "." or "e" not followed by a digit is
    // not part of the number (so "1." is 1 then extra data).
    const re = /-?(?:0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?/y;
    re.lastIndex = i;
    const m = re.exec(s);
    if (m === null) throw new JSONDecodeError("Expecting value", s, i);
    const isFloat = m[1] !== undefined || m[2] !== undefined;
    // An int is int(token): "-0" is 0. BigInt keeps precision past 2^53 while normalising.
    return [{ __pyNum: isFloat ? m[0] : BigInt(m[0]).toString(), isFloat }, i + m[0].length];
  }
  private string(end: number): [string, number] {
    const s = this.s;
    const begin = end - 1;
    let out = "";
    for (;;) {
      let next = end;
      for (; next < s.length; next++) {
        const d = s[next]!;
        if (d === '"' || d === "\\") break;
        // strict=True: a raw control character inside a string is an error, not data.
        if (d.charCodeAt(0) <= 0x1f) throw new JSONDecodeError("Invalid control character at", s, next);
      }
      if (next >= s.length) throw new JSONDecodeError("Unterminated string starting at", s, begin);
      out += s.slice(end, next);
      if (s[next] === '"') return [out, next + 1];
      next++;
      if (next >= s.length) throw new JSONDecodeError("Unterminated string starting at", s, begin);
      const e = s[next]!;
      if (e !== "u") {
        end = next + 1;
        const simple: Record<string, string> = {
          '"': '"', "\\": "\\", "/": "/", b: "\b", f: "\f", n: "\n", r: "\r", t: "\t",
        };
        if (!(e in simple)) throw new JSONDecodeError("Invalid \\escape", s, end - 2);
        out += simple[e]!;
        continue;
      }
      next++;
      end = next + 4;
      if (end >= s.length) throw new JSONDecodeError("Invalid \\uXXXX escape", s, next - 1);
      const hex4 = (from: number, errAt: number): number => {
        const h = s.slice(from, from + 4);
        if (!/^[0-9a-fA-F]{4}$/.test(h)) throw new JSONDecodeError("Invalid \\uXXXX escape", s, errAt);
        return parseInt(h, 16);
      };
      let c = hex4(next, end - 5);
      next = end;
      if (c >= 0xd800 && c <= 0xdbff && end + 6 < s.length && s[next] === "\\" && s[next + 1] === "u") {
        const c2 = hex4(next + 2, end + 6 - 5);
        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
          c = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
          end += 6;
        }
      }
      out += String.fromCodePoint(c);
    }
  }
  private object(i: number): [PyObj, number] {
    const s = this.s;
    const pairs: [string, PyValue][] = [];
    i = this.ws(i);
    if (i < s.length && s[i] === "}") return [obj(pairs), i + 1];
    for (;;) {
      if (i >= s.length || s[i] !== '"') {
        throw new JSONDecodeError("Expecting property name enclosed in double quotes", s, i);
      }
      const [k, afterKey] = this.string(i + 1);
      i = this.ws(afterKey);
      if (i >= s.length || s[i] !== ":") throw new JSONDecodeError("Expecting ':' delimiter", s, i);
      const [v, afterValue] = this.scan(this.ws(i + 1));
      // A repeated key is a dict assignment: the LAST value wins, at the FIRST key's position.
      const at = pairs.findIndex(([key]) => key === k);
      if (at >= 0) pairs[at]![1] = v;
      else pairs.push([k, v]);
      i = this.ws(afterValue);
      if (i < s.length && s[i] === "}") return [obj(pairs), i + 1];
      if (i >= s.length || s[i] !== ",") throw new JSONDecodeError("Expecting ',' delimiter", s, i);
      const comma = i;
      i = this.ws(i + 1);
      if (i < s.length && s[i] === "}") {
        throw new JSONDecodeError("Illegal trailing comma before end of object", s, comma);
      }
    }
  }
  private array(i: number): [PyValue[], number] {
    const s = this.s;
    const out: PyValue[] = [];
    i = this.ws(i);
    if (i < s.length && s[i] === "]") return [out, i + 1];
    for (;;) {
      const [v, after] = this.scan(i);
      out.push(v);
      i = this.ws(after);
      if (i < s.length && s[i] === "]") return [out, i + 1];
      if (i >= s.length || s[i] !== ",") throw new JSONDecodeError("Expecting ',' delimiter", s, i);
      const comma = i;
      i = this.ws(i + 1);
      if (i < s.length && s[i] === "]") {
        throw new JSONDecodeError("Illegal trailing comma before end of array", s, comma);
      }
    }
  }
}

export function loads(text: string): PyValue {
  return new Parser(text).parse();
}

const utf8Strict = new TextDecoder("utf-8", { fatal: true });
/** json.loads(bytes): a UTF-8 BOM selects utf-8-sig, anything else decodes as strict UTF-8 (a decode
 *  failure is a ValueError in Python too). The UTF-16/32 sniffing arms of json.detect_encoding are
 *  not modelled: every producer these checks read speaks UTF-8. */
export function loadsBytes(data: Uint8Array): PyValue {
  const bom = data.length >= 3 && data[0] === 0xef && data[1] === 0xbb && data[2] === 0xbf;
  let text: string;
  try {
    text = utf8Strict.decode(bom ? data.subarray(3) : data);
  } catch {
    const e = new SyntaxError("'utf-8' codec can't decode bytes");
    e.name = "UnicodeDecodeError";
    throw e;
  }
  return loads(text);
}

/** Python float.__repr__: shortest round-trip digits, positional for -4 <= exp < 16, scientific
 *  with a signed 2+ digit exponent otherwise, and an integral float keeps its `.0`. */
export function floatRepr(raw: string): string {
  const x = Number(raw);
  if (Number.isNaN(x)) return "NaN";
  if (x === Infinity) return "Infinity";
  if (x === -Infinity) return "-Infinity";
  if (Object.is(x, -0)) return "-0.0";
  if (x === 0) return "0.0";
  let neg = false;
  let s = x.toString();
  if (s.startsWith("-")) {
    neg = true;
    s = s.slice(1);
  }
  let mant = s;
  let exp = 0;
  const eIdx = s.indexOf("e");
  if (eIdx >= 0) {
    mant = s.slice(0, eIdx);
    exp = parseInt(s.slice(eIdx + 1), 10);
  }
  const dot = mant.indexOf(".");
  if (dot >= 0) {
    exp += dot - 1;
    mant = mant.slice(0, dot) + mant.slice(dot + 1);
  } else {
    exp += mant.length - 1;
  }
  // Normalise to a SINGLE leading digit. Without this a JS positional rendering of a small
  // magnitude keeps its leading zeros and reports exponent 0, missing Python's scientific form for
  // anything below 1e-4 — the generated-corpus differential caught exactly that.
  let lead = 0;
  while (lead < mant.length - 1 && mant[lead] === "0") lead++;
  if (lead > 0) {
    exp -= lead;
    mant = mant.slice(lead);
  }
  mant = mant.replace(/0+$/, "");
  if (mant === "") mant = "0";
  const sign = neg ? "-" : "";
  if (exp < -4 || exp >= 16) {
    const head = mant.length > 1 ? `${mant[0]}.${mant.slice(1)}` : mant;
    const es = exp < 0 ? "-" : "+";
    return `${sign}${head}e${es}${String(Math.abs(exp)).padStart(2, "0")}`;
  }
  if (exp >= 0) {
    const int = mant.slice(0, exp + 1).padEnd(exp + 1, "0");
    const frac = mant.slice(exp + 1);
    return `${sign}${int}.${frac === "" ? "0" : frac}`;
  }
  return `${sign}0.${"0".repeat(-exp - 1)}${mant}`;
}

/** Python json string escaping with ensure_ascii=True. */
export function quote(s: string): string {
  const SHORT: Record<number, string> = { 8: "\\b", 9: "\\t", 10: "\\n", 12: "\\f", 13: "\\r" };
  let out = '"';
  for (const ch of s) {
    const cp = ch.codePointAt(0) as number;
    if (ch === '"') out += '\\"';
    else if (ch === "\\") out += "\\\\";
    else if (cp in SHORT) out += SHORT[cp];
    else if (cp < 0x20) out += "\\u" + cp.toString(16).padStart(4, "0");
    else if (cp < 0x7f) out += ch;
    else if (cp <= 0xffff) out += "\\u" + cp.toString(16).padStart(4, "0");
    else {
      const v = cp - 0x10000;
      out += "\\u" + (0xd800 + (v >> 10)).toString(16).padStart(4, "0");
      out += "\\u" + (0xdc00 + (v & 0x3ff)).toString(16).padStart(4, "0");
    }
  }
  return out + '"';
}

/** json.dumps(value) — Python's default ", " and ": " separators, or `separators=(item, key)`. */
export function dumps(v: PyValue, itemSep = ", ", keySep = ": "): string {
  const walk = (node: PyValue): string => {
    if (node === null) return "null";
    if (node === true) return "true";
    if (node === false) return "false";
    if (typeof node === "string") return quote(node);
    if (isPyNum(node)) return node.isFloat ? floatRepr(node.__pyNum) : node.__pyNum;
    if (Array.isArray(node)) return "[" + node.map(walk).join(itemSep) + "]";
    return "{" + (node as PyObj).__pyObj.map(([k, x]) => `${quote(k)}${keySep}${walk(x)}`).join(itemSep) + "}";
  };
  return walk(v);
}

/** json.dumps(value, indent=N) — one item per line, Python's layout. */
export function dumpsIndent(v: PyValue, indent = 2): string {
  const pad = (n: number) => " ".repeat(n * indent);
  const walk = (node: PyValue, depth: number): string => {
    if (node === null) return "null";
    if (node === true) return "true";
    if (node === false) return "false";
    if (typeof node === "string") return quote(node);
    if (isPyNum(node)) return node.isFloat ? floatRepr(node.__pyNum) : node.__pyNum;
    if (Array.isArray(node)) {
      if (node.length === 0) return "[]";
      return `[\n${node.map((x) => pad(depth + 1) + walk(x, depth + 1)).join(",\n")}\n${pad(depth)}]`;
    }
    const pairs = (node as PyObj).__pyObj;
    if (pairs.length === 0) return "{}";
    return (
      `{\n` +
      pairs.map(([k, x]) => `${pad(depth + 1)}${quote(k)}: ${walk(x, depth + 1)}`).join(",\n") +
      `\n${pad(depth)}}`
    );
  };
  return walk(v, 0);
}

/** Convert an ordinary JS value (the output of JSON.parse, or a literal) into the tagged tree, for
 *  callers that build structures in TS rather than parsing a document. Plain objects lose nothing
 *  here because TS object key order IS insertion order for non-integer-like keys; a caller with
 *  integer-like keys must build with obj() instead. */
export function fromJS(v: unknown): PyValue {
  if (v === null || v === undefined) return null;
  if (typeof v === "boolean") return v;
  if (typeof v === "string") return v;
  if (typeof v === "number") {
    if (Number.isInteger(v)) return num(String(v));
    return num(v.toString());
  }
  if (Array.isArray(v)) return v.map(fromJS);
  const pairs: [string, PyValue][] = Object.keys(v as Record<string, unknown>).map((k) => [
    k,
    fromJS((v as Record<string, unknown>)[k]),
  ]);
  return obj(pairs);
}
