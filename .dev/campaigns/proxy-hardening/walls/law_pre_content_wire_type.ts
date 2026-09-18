#!/usr/bin/env bun
/** LAW ENFORCER — PRE-CONTENT-WIRE-TYPE.
 *
 *  EXIT 0 = the rule is applied at the seam and nowhere else, and no unexempted envelope builds an
 *  in-band error type.  EXIT 1 = at least one of those is false, named by file:line.
 *  --selftest = the POSITIVE CONTROL (gate check C6).
 *
 *  V4-154: converted to TypeScript (bun), whole — the parser, the synthetic fixtures and the
 *  selftest, with `main` last. Registered nowhere until the coupled unit lands with the deletion.
 *
 *  HOW THIS PORT WAS PROVEN, because the live tree is GREEN and a green differential there compares
 *  two empty problem lists:
 *   · BOTH implementations were driven over the original's own 16 synthetic fixtures and their FULL
 *     problem lists compared — 16 agree, 0 differ, of which 14 are RED. The reds are the evidence,
 *     and 16 matching file:LINE strings is direct proof that codeView preserves offsets and
 *     newlines, which is hazard 1 caught by construction rather than by inspection.
 *   · FULL STDOUT of both modes was compared for both implementations: identical, including exit codes.
 *   · THE WIDEN ARM: the rule's body was mutated to govern a third type and its signature's flag was
 *     RENAMED, in a scratch copy, and both implementations were asked to derive the governed set and
 *     the parameter names. Both returned ["NEW_GOVERNED","RATE_LIMIT"] and ["type","clientSawContent"].
 *     Agreement on today's tokens alone would not distinguish a port that READS the rule from one
 *     that transcribed it; this does.
 *
 *  THREE HAZARDS THAT ARE THE ACCEPTANCE CRITERIA, not notes:
 *   1. codeView MUST stay a SINGLE LEXICAL PASS over a character array that blanks comments and
 *      string/char literals IN PLACE, preserving every offset and newline. The regex stripper the
 *      other walls use gets both precedence cases backwards — a `//` inside a string would open a
 *      comment, a quote inside a comment would open a string — and `line()` derives file:LINE from
 *      the offset, which is this wall's entire contract. An offset drift makes every message wrong
 *      while the wall still reports GREEN.
 *   2. `splitArgs` MUST keep treating a trailing comma as Kotlin's live spelling rather than an
 *      empty argument.
 *   3. `governedTypes`/`ruleParams` MUST keep reading the rule's OWN body and signature. A port that
 *      hard-codes those tokens passes today and rots silently — the same second-copy-that-drifts
 *      shape as keying a census to a burn-down list instead of the filesystem.
 */
import { globSync, readFileSync, existsSync } from "node:fs";
import { resolve, relative } from "node:path";

/** Python repr() of a string, and of a list of strings. An f-string that interpolates a LIST
 *  renders THIS -- `['a', 'b']` -- not JSON, so a port that used JSON.stringify produced a
 *  DIFFERENT failure message than the original on every red path while agreeing on every green
 *  one. Caught by driving the mutants as a CLI rather than feeding detect() a fixed corpus. */
function pyReprStr(s: string): string {
  const useDouble = s.includes("'") && !s.includes('"');
  const q = useDouble ? '"' : "'";
  let body = s
    .replaceAll("\\", "\\\\")
    .replaceAll("\n", "\\n")
    .replaceAll("\r", "\\r")
    .replaceAll("\t", "\\t");
  body = body.replaceAll(q, "\\" + q);
  return q + body + q;
}
function pyRepr(items: string[]): string {
  return "[" + items.map(pyReprStr).join(", ") + "]";
}

const ROOT = resolve(import.meta.dir, "../../../..");
const SOURCE_ROOT_GLOB = "gateway/*/src/main/kotlin";
const SEAM_FILE = "gateway/gateway/src/main/kotlin/splice/gateway/wire/SseEmitter.kt";
const SEAM_FUN = "emitError";

const COLLECT_FILE = "gateway/gateway/src/main/kotlin/splice/gateway/wire/CollectingTerminal.kt";
const COLLECT_MARK = "CollectingTerminal";
const COLLECT_REASON = "the COLLECT path (stream:false), where the failure's real HTTP status carries the verdict";

const PRE_TURN_FILE = "gateway/gateway/src/main/kotlin/splice/gateway/head/AdmissionResponses.kt";
const PRE_TURN_MARK = "AdmissionResponses";
const PRE_TURN_REASON = "2026-09-17: the PRE-TURN admission plane. Every verdict here is decided before";

const POOLED_FILE = "gateway/provider-spi/src/main/kotlin/splice/spi/RateLimitCooldown.kt";
const POOLED_MARK = "RateLimitCooldown";
const POOLED_REASON = "2026-09-17: the POOLED-REFUSAL fail-fast body, synthesized by the cooldown itself";

const EXEMPTIONS: [string, string, string][] = [
  [COLLECT_FILE, COLLECT_MARK, COLLECT_REASON],
  [PRE_TURN_FILE, PRE_TURN_MARK, PRE_TURN_REASON],
  [POOLED_FILE, POOLED_MARK, POOLED_REASON],
];

const ROUTED = "PreContentWireType.of(";
const RULE_OBJECT = "object PreContentWireType";
const FOUNDING = ["RATE_LIMIT", "API_ERROR"];
const ENVELOPE_WRITERS = new Set(["put", "putJsonObject", "add", "JsonPrimitive", "errorEnvelope", "of"]);

const GOVERNED_RE = /==\s*ErrorType\.([A-Z][A-Z_0-9]*)\b/g;
const OF_SIGNATURE_RE = /\bfun\s+of\s*\(/;
const ROUTED_HEAD_RE = /^PreContentWireType\s*\.\s*of\s*\(/;
const NAMED_ARG_RE = /^([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$/s;
const ENVELOPE_FUN_RE = /\b(\w*[eE]rrorEnvelope)(?:\s*\.\s*of)?\s*\(/g;
const WIRENAME_RE = /\.wireName\b/g;
const BIND_RE = /\b(?:val|var)\s+([A-Za-z_]\w*)\b[^=\n]*?=\s*/g;

/** Blank comments and string/char literals WITHOUT moving offsets or newlines.
 *
 *  One lexical pass, so a `//` inside a string cannot open a comment and a `"` inside a comment
 *  cannot open a string — the two ways a two-regex stripper gets this backwards. Offsets survive so
 *  every finding can still name file:LINE, which is this wall's whole contract. */
export function codeView(text: string): string {
  const out = text.split("");
  const n = text.length;
  let i = 0;
  const blank = (start: number, end: number): void => {
    for (let at = start; at < Math.min(end, n); at += 1) {
      if (out[at] !== "\r" && out[at] !== "\n") out[at] = " ";
    }
  };
  while (i < n) {
    if (text.startsWith("//", i)) {
      let end = text.indexOf("\n", i);
      end = end < 0 ? n : end;
      blank(i, end);
      i = end;
    } else if (text.startsWith("/*", i)) {
      const close = text.indexOf("*/", i + 2);
      const end = close < 0 ? n : close + 2;
      blank(i, end);
      i = end;
    } else if (text.startsWith('"""', i)) {
      const close = text.indexOf('"""', i + 3);
      const end = close < 0 ? n : close + 3;
      blank(i, end);
      i = end;
    } else if (text[i] === '"' || text[i] === "'") {
      const quote = text[i];
      let end = i + 1;
      while (end < n) {
        if (text[end] === "\\") end += 2;
        else if (text[end] === quote) { end += 1; break; }
        else if (text[end] === "\n") break;
        else end += 1;
      }
      blank(i, end);
      i = end;
    } else {
      i += 1;
    }
  }
  return out.join("");
}

function line(code: string, at: number): number {
  let count = 0;
  for (let i = 0; i < at && i < code.length; i += 1) if (code[i] === "\n") count += 1;
  return count + 1;
}

function balancedEnd(code: string, openAt: number): number {
  let depth = 0;
  for (let at = openAt; at < code.length; at += 1) {
    if ("([{".includes(code[at])) depth += 1;
    else if (")]}".includes(code[at])) {
      depth -= 1;
      if (depth === 0) return at;
    }
  }
  return code.length;
}

/** The initializer expression at [assignEnd]: to the first newline at paren depth 0, so a multi-line
 *  call is read whole. */
function rhsOf(code: string, assignEnd: number): string {
  let depth = 0;
  for (let at = assignEnd; at < code.length; at += 1) {
    const ch = code[at];
    if ("([{".includes(ch)) depth += 1;
    else if (")]}".includes(ch)) depth -= 1;
    else if (ch === "\n" && depth <= 0) return code.slice(assignEnd, at);
  }
  return code.slice(assignEnd);
}

/** One-line form of an expression: whitespace (and the blanked comments inside it) collapsed. */
const norm = (expr: string): string => expr.split(/\s+/).filter((s) => s !== "").join(" ");

/** Drop parens that wrap the WHOLE expression, so `(of(t, c))` grades as `of(t, c)`. */
function unwrap(expr: string): string {
  while (expr.startsWith("(") && balancedEnd(expr, 0) === expr.length - 1) expr = expr.slice(1, -1).trim();
  return expr;
}

/** Top-level commas only, and a trailing comma is not an argument (Kotlin's live spelling). */
function splitArgs(inner: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let at = 0; at < inner.length; at += 1) {
    const ch = inner[at];
    if ("([{".includes(ch)) depth += 1;
    else if (")]}".includes(ch)) depth -= 1;
    else if (ch === "," && depth === 0) { parts.push(inner.slice(start, at)); start = at + 1; }
  }
  parts.push(inner.slice(start));
  return parts.map((p) => p.trim()).filter((p) => p !== "");
}

/** Name of the innermost call whose '(' encloses [at] — "" when none. This is what separates a wire
 *  write from a telemetry read. */
function enclosingCall(code: string, at: number): string {
  const stack: number[] = [];
  for (let i = 0; i < at; i += 1) {
    if (code[i] === "(") stack.push(i);
    else if (code[i] === ")" && stack.length > 0) stack.pop();
  }
  if (stack.length === 0) return "";
  const head = code.slice(0, stack[stack.length - 1]).replace(/\s+$/, "");
  const name = /([A-Za-z_]\w*)$/.exec(head);
  return name ? name[1] : "";
}

/** The receiver expression immediately left of the '.' at [dotAt]. */
function receiverBefore(code: string, dotAt: number): string {
  let end = dotAt;
  while (end > 0 && " \t\n".includes(code[end - 1])) end -= 1;
  let i = end;
  while (i > 0) {
    const ch = code[i - 1];
    if (ch === ")") {
      let depth = 0;
      while (i > 0) {
        if (code[i - 1] === ")") depth += 1;
        else if (code[i - 1] === "(") {
          depth -= 1;
          if (depth === 0) { i -= 1; break; }
        }
        i -= 1;
      }
      continue;
    }
    if (/[A-Za-z0-9_]/.test(ch) || ch === ".") { i -= 1; continue; }
    break;
  }
  return norm(code.slice(i, end));
}

/** (offset, receiver, writer) for every `.wireName` this file hands to a JSON writer. */
function wireWrites(code: string): [number, string, string][] {
  const out: [number, string, string][] = [];
  for (const m of code.matchAll(WIRENAME_RE)) {
    const writer = enclosingCall(code, m.index);
    if (ENVELOPE_WRITERS.has(writer)) out.push([m.index, receiverBefore(code, m.index), writer]);
  }
  return out;
}

/** (offset, what) for every in-band error-envelope construction. */
function envelopeSites(code: string): [number, string][] {
  const sites: [number, string][] = wireWrites(code).map(([at, recv, writer]) => [
    at, `\`${recv}.wireName\` into ${writer}(…)`,
  ]);
  for (const m of code.matchAll(ENVELOPE_FUN_RE)) sites.push([m.index, `a \`${m[1]}(\` error-envelope builder`]);
  const seen = new Map<number, string>();
  for (const [at, what] of sites) {
    const ln = line(code, at);
    if (!seen.has(ln)) seen.set(ln, what);
  }
  return [...seen.entries()].sort((a, b) => a[0] - b[0]).map(([at, what]) => [at, what]);
}

/** The ErrorType values `object PreContentWireType` actually remaps — read from its own body so the
 *  wall widens with the rule instead of carrying a second, drifting copy of the list. Only the types
 *  the rule COMPARES AGAINST: reading every token would sweep up the OVERLOADED it RETURNS. */
export function governedTypes(seamCode: string): Set<string> {
  const at = seamCode.indexOf(RULE_OBJECT);
  if (at < 0) return new Set();
  const brace = seamCode.indexOf("{", at);
  if (brace < 0) return new Set();
  const body = seamCode.slice(brace, balancedEnd(seamCode, brace));
  return new Set([...body.matchAll(GOVERNED_RE)].map((m) => m[1]));
}

/** The parameter NAMES of the rule's own `fun of(`, so the content flag is identified by the rule's
 *  signature rather than by a second copy of the name living in this wall. */
export function ruleParams(seamCode: string): string[] {
  const at = seamCode.indexOf(RULE_OBJECT);
  if (at < 0) return [];
  const brace = seamCode.indexOf("{", at);
  if (brace < 0) return [];
  const body = seamCode.slice(brace, balancedEnd(seamCode, brace));
  const sig = OF_SIGNATURE_RE.exec(body);
  if (!sig) return [];
  const inner = body.slice(sig.index + sig[0].length, balancedEnd(body, sig.index + sig[0].length - 1));
  const names: string[] = [];
  for (const param of splitArgs(inner)) {
    const name = /^(?:\w+\s+)*([A-Za-z_]\w*)\s*:/.exec(param);
    if (!name) return [];
    names.push(name[1]);
  }
  return names;
}

/** routed | not-whole | flag-true | of-unreadable — the WHOLE-expression grade of [expr]. */
function routingVerdict(expr: string, params: string[]): string {
  expr = unwrap(norm(expr));
  const head = ROUTED_HEAD_RE.exec(expr);
  if (!head || balancedEnd(expr, head[0].length - 1) !== expr.length - 1) return "not-whole";
  const args = splitArgs(expr.slice(head[0].length, -1));
  const flagName = params[1];
  const named = new Map<string, string>();
  const positional: string[] = [];
  for (const arg of args) {
    const hit = NAMED_ARG_RE.exec(arg);
    if (hit) named.set(hit[1], hit[2].trim());
    else positional.push(arg);
  }
  if (args.length > params.length || args.length < 2) return "of-unreadable";
  let flag: string;
  if (named.has(flagName)) flag = named.get(flagName)!;
  else if (positional.length >= 2) flag = positional[1];
  else return "of-unreadable";
  return flag === "true" ? "flag-true" : "routed";
}

/** (offset, balanced body) per `fun <name>(` declaration. null when one has no brace body — an
 *  expression-bodied emitter is a shape this wall has never read, so it refuses to judge it. */
function funBodies(code: string, name: string): [number, string][] | null {
  const out: [number, string][] = [];
  const re = new RegExp(`\\bfun\\s+${name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*\\(`, "g");
  for (const m of code.matchAll(re)) {
    const close = balancedEnd(code, m.index + m[0].length - 1);
    const brace = code.indexOf("{", close);
    if (brace < 0 || code.slice(close + 1, brace).includes("=") || code.slice(close + 1, brace).includes("fun ")) {
      return null;
    }
    out.push([brace, code.slice(brace, balancedEnd(code, brace) + 1)]);
  }
  return out;
}

function why(verdict: string, call: string, params: string[]): string {
  const snippet = norm(call).slice(0, 60);
  if (verdict === "flag-true") {
    return `${SEAM_FUN} applies the rule as \`${snippet}\` — with ${params[1]} pinned to the literal true the rule returns the type UNCHANGED, so the routing is spelled and does nothing. Pass the emitter's real content flag`;
  }
  if (verdict === "of-unreadable") {
    return `${SEAM_FUN} applies the rule as \`${snippet}\`, whose arguments do not read against the rule's own signature (${params.join(", ")}), so the content flag cannot be checked; refusing to judge it routed`;
  }
  return `${SEAM_FUN} applies the rule inside the expression \`${snippet}\` rather than AS the expression: a conditional or elvis that merely CONTAINS the routing can still put the real type on the wire. Route the whole expression`;
}

function seamProblems(seamCode: string, params: string[]): string[] {
  const bodies = funBodies(seamCode, SEAM_FUN);
  if (bodies === null) {
    return [`${SEAM_FILE} declares a \`fun ${SEAM_FUN}(\` with no brace body — a shape this wall cannot read; refusing to pass vacuously`];
  }
  if (bodies.length === 0) {
    return [`${SEAM_FILE} declares no \`fun ${SEAM_FUN}(\` — the seam the whole law now rests on is gone; refusing to pass vacuously`];
  }
  const problems: string[] = [];
  for (const [brace, body] of bodies) {
    const ln = line(seamCode, brace);
    const calls: number[] = [];
    for (const m of body.matchAll(new RegExp(ROUTED.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "g"))) calls.push(m.index);
    if (calls.length === 0) {
      problems.push(`${SEAM_FILE}:${ln} — ${SEAM_FUN} writes an error frame WITHOUT ${ROUTED}…): the one place the pre-content rule is applied no longer applies it, so every pre-content failure reaches the client terminal`);
      continue;
    }
    if (calls.length > 1) {
      problems.push(`${SEAM_FILE}:${ln} — ${SEAM_FUN} applies ${ROUTED}…) ${calls.length} times; one seam means one application, and the wall cannot tell which result ships`);
      continue;
    }
    const at = calls[0];
    const call = body.slice(at, balancedEnd(body, at + ROUTED.length - 1) + 1);
    let bound: string | null = null;
    let verdict = "not-whole";
    for (const m of body.matchAll(BIND_RE)) {
      const rhs = rhsOf(body, m.index + m[0].length);
      if (rhs.includes(ROUTED)) {
        bound = m[1];
        verdict = routingVerdict(rhs, params);
        break;
      }
    }
    if (bound === null) {
      verdict = routingVerdict(call, params);
      bound = norm(call);
    }
    if (verdict !== "routed") {
      problems.push(`${SEAM_FILE}:${ln} — ${why(verdict, call, params)}`);
      continue;
    }
    const writes = wireWrites(body);
    if (writes.length === 0) {
      problems.push(`${SEAM_FILE}:${ln} — ${SEAM_FUN} computes the routed type but hands no \`.wireName\` to any envelope writer, so the wall cannot see the routed value reach the wire; refusing to pass vacuously`);
      continue;
    }
    for (const [off, recv, writer] of writes) {
      if (recv !== bound) {
        problems.push(`${SEAM_FILE}:${line(seamCode, brace + off)} — ${SEAM_FUN} routes the type into \`${bound}\` and then writes \`${recv}.wireName\` to the wire via ${writer}(…): the routing is computed and DISCARDED, which is exactly what it looks like when the rule is applied and forgotten`);
      }
    }
  }
  return problems;
}

/** Pure detection — no I/O, so the selftest feeds synthetic trees. */
export function detect(sources: Record<string, string> | null, seamText: string | null): string[] {
  if (seamText === null) {
    return [`${SEAM_FILE} is missing — the seam this law now rests on does not exist; refusing to pass vacuously`];
  }
  const seamCode = codeView(seamText);
  if (!seamCode.includes(RULE_OBJECT) || !seamCode.includes("fun of(")) {
    return [`${SEAM_FILE} no longer declares \`${RULE_OBJECT}\` with a \`fun of(\` — the rule the seam applies is gone or has moved; refusing to pass vacuously`];
  }
  const governed = governedTypes(seamCode);
  const missing = FOUNDING.filter((t) => !governed.has(t));
  if (missing.length > 0) {
    return [`PreContentWireType no longer remaps ${missing.join(", ")} — the rule was NARROWED, which silently narrows every wall derived from it. A wall may only tighten.`];
  }
  const params = ruleParams(seamCode);
  if (params.length < 2) {
    return [`${SEAM_FILE}'s \`fun of(\` signature does not read as (type, content flag, …) — the wall derives the content flag from it and will not guess; refusing to pass vacuously`];
  }
  if (!sources || Object.keys(sources).length === 0) {
    return [`no Kotlin sources found under ${SOURCE_ROOT_GLOB} — refusing to pass vacuously`];
  }

  const problems = seamProblems(seamCode, params);
  let envelopes = 0;
  for (const path of Object.keys(sources).sort()) {
    const code = codeView(sources[path]);
    if (path !== SEAM_FILE) {
      for (const m of code.matchAll(new RegExp(ROUTED.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "g"))) {
        problems.push(`${path}:${line(code, m.index)} — a SECOND site applies ${ROUTED}…). The rule is applied once, at the emitter seam; a hand copy here is the V4-79 defect (four copies of one rule) that made a new ending able to skip it`);
      }
    }
    let exempt = path === SEAM_FILE;
    for (const [exFile, exMark, exReason] of EXEMPTIONS) {
      if (path !== exFile) continue;
      if (!code.includes(exMark)) {
        problems.push(`${path} holds a written envelope exemption but no longer declares \`${exMark}\` — the exemption was written for ${exReason}; it may not outlive that reason`);
      } else {
        exempt = true;
      }
    }
    const sites = envelopeSites(code);
    envelopes += sites.length;
    if (exempt) continue;
    for (const [at, what] of sites) {
      problems.push(`${path}:${at} — ${what} builds an in-band error envelope OUTSIDE the emitter seam, so the pre-content rule cannot reach it. Either route it through ${SEAM_FILE}'s ${SEAM_FUN} or earn an exemption written into this wall the way the collect path has one`);
    }
  }
  if (envelopes === 0) {
    return [`scanned ${Object.keys(sources).length} source file(s) and found ZERO error-envelope constructions — a denominator of zero passes for any rule; refusing to pass vacuously`];
  }
  if (problems.length > 0) {
    problems.push(`(census: ${envelopes} error-envelope construction(s) in ${Object.keys(sources).length} file(s); governed types derived from the rule: ${[...governed].sort().join(", ")}; rule signature: of(${params.join(", ")}))`);
  }
  return problems;
}

export function load(): [Record<string, string> | null, string | null] {
  const sources: Record<string, string> = {};
  for (const root of globSync(resolve(ROOT, SOURCE_ROOT_GLOB)).sort()) {
    for (const kt of globSync(`${root}/**/*.kt`).sort()) {
      sources[relative(ROOT, kt)] = readFileSync(kt, "utf8");
    }
  }
  const seam = resolve(ROOT, SEAM_FILE);
  return [Object.keys(sources).length > 0 ? sources : null, existsSync(seam) ? readFileSync(seam, "utf8") : null];
}

// ── synthetic fixtures ───────────────────────────────────────────────────────

export const RULE =
  "internal object PreContentWireType {\n" +
  "    fun of(type: ErrorType, contentReachedClient: Boolean, permanent: Boolean = false)" +
  ": ErrorType {\n" +
  "        if (contentReachedClient) return type\n" +
  "        return when {\n" +
  "            type == ErrorType.RATE_LIMIT -> ErrorType.OVERLOADED\n" +
  "            type == ErrorType.API_ERROR && !permanent -> ErrorType.OVERLOADED\n" +
  "            else -> type\n" +
  "        }\n    }\n}\n";
const RULE_NARROWED =
  "internal object PreContentWireType {\n" +
  "    fun of(type: ErrorType, contentReachedClient: Boolean, permanent: Boolean = false)" +
  ": ErrorType =\n" +
  "        if (type == ErrorType.RATE_LIMIT && !contentReachedClient) ErrorType.OVERLOADED " +
  "else type\n}\n";

/** An SseEmitter whose emitError applies [routing] and writes [wire].wireName to the frame. */
function seam(routing: string, wire = "wireType", rule = RULE): string {
  return (
    "class SseEmitter : TurnTerminal {\n" +
    "    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {\n" +
    `        ${routing}\n` +
    "        frames.frame(\n" +
    '            "error",\n' +
    "            buildJsonObject {\n" +
    `                put(TYPE, ${wire}.wireName)\n` +
    "                put(MESSAGE, message)\n" +
    "            },\n" +
    "        )\n" +
    "    }\n}\n"
  ) + rule;
}

export const SEAM_OK = seam("val wireType = PreContentWireType.of(type, contentReached(), permanent)");
const SEAM_INLINE =
  "class SseEmitter : TurnTerminal {\n" +
  "    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {\n" +
  "        frames.frame(\n" +
  '            "error",\n' +
  "            buildJsonObject {\n" +
  "                put(TYPE, PreContentWireType.of(type, contentReached(), permanent).wireName)\n" +
  "            },\n" +
  "        )\n" +
  "    }\n}\n" + RULE;
const SEAM_NO_ROUTE = seam("val wireType = type");
const SEAM_TWICE = seam(
  "val wireType = if (permanent) PreContentWireType.of(type, contentReached()) " +
    "else PreContentWireType.of(type, true)");
const SEAM_MIXED = seam(
  "val wireType = if (permanent) type else PreContentWireType.of(type, contentReached())");
const SEAM_ELVIS = seam("val wireType = PreContentWireType.of(type, contentReached()) ?: type");
const SEAM_FLAG_TRUE = seam("val wireType = PreContentWireType.of(type, contentReachedClient = true)");
const SEAM_DISCARDS = seam("val wireType = PreContentWireType.of(type, contentReached(), permanent)", "type");
const SEAM_NO_WIRE =
  "class SseEmitter : TurnTerminal {\n" +
  "    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {\n" +
  "        val wireType = PreContentWireType.of(type, contentReached(), permanent)\n" +
  '        frames.frame("error", buildJsonObject { put(MESSAGE, message) })\n' +
  "    }\n}\n" + RULE;
const SEAM_NO_RULE = seam(
  "val wireType = PreContentWireType.of(type, contentReached(), permanent)",
  "wireType", "internal object Something { fun other() {} }\n");
const SEAM_NARROWED = seam(
  "val wireType = PreContentWireType.of(type, contentReached(), permanent)", "wireType", RULE_NARROWED);

/** The collect path: the ONE exemption, and a copy of it that has stopped being the collect path. */
const COLLECT =
  "internal class CollectingTerminal : TurnTerminal {\n" +
  "    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) {\n" +
  "        body = errorEnvelope(type.wireName, message)\n" +
  "    }\n" +
  "    private fun errorEnvelope(type: String, message: String): JsonObject =\n" +
  '        buildJsonObject { put("type", type) }\n}\n';
const COLLECT_STALE = COLLECT.replace(/CollectingTerminal/g, "SomethingElse");

const HAND_COPY =
  "internal class TurnConnEnd {\n  suspend fun end() {\n" +
  '    drive.emitter.emitError(PreContentWireType.of(ErrorType.API_ERROR, c), "boom")\n' +
  "  }\n}\n";
const ENVELOPE_OUTSIDE =
  "internal class NewEnding {\n  fun frame(failure: ClassifiedFailure) =\n" +
  '    buildJsonObject { put("type", failure.type.wireName) }\n}\n';
const ENVELOPE_BUILDER =
  "internal class NewEnding {\n" +
  "  private fun errorEnvelope(type: String) = buildJsonObject " +
  '{ put("type", type) }\n}\n';
const TELEMETRY_READ =
  "internal class TurnLine {\n  fun tag(outcome: TurnOutcome) =\n" +
  "    telemetry.recordStreamError(meta, elapsedMs, outcome.type.wireName)\n}\n";
const COMMENT_DECOY =
  "internal class TurnEnding {\n  suspend fun end() {\n" +
  "    // the wire type is routed by PreContentWireType.of(type, reached) at the seam\n" +
  '    drive.emitter.emitError(ErrorType.API_ERROR, "boom")\n  }\n}\n';
const STRING_DECOY =
  "internal class Doc {\n  val doc = \"\"\"PreContentWireType.of(t, c) and " +
  'put("type", failure.type.wireName)"""\n}\n';
const CLEAN_ENDING =
  "internal class TurnEnding {\n  suspend fun end() {\n" +
  '    drive.emitter.emitError(ErrorType.API_ERROR, "boom", permanent = true)\n' +
  "  }\n}\n";

/** A synthetic main-source tree: the seam, the exempt collect path, and whatever else. */
function tree(seamText: string = SEAM_OK, extra: Record<string, string> = {}): Record<string, string> {
  const sources: Record<string, string> = { [SEAM_FILE]: seamText, [COLLECT_FILE]: COLLECT };
  for (const [name, text] of Object.entries(extra)) {
    sources[`gateway/gateway/src/main/kotlin/splice/gateway/head/${name}.kt`] = text;
  }
  return sources;
}

function selftest(): number {
  const fails: string[] = [];

  const kase = (
    name: string, srcs: Record<string, string>, seamText: string | null,
    wantRed: boolean, mustName?: string,
  ): void => {
    const got = detect(srcs, seamText);
    if (wantRed && got.length === 0) { fails.push(`${name}: must be RED`); return; }
    if (!wantRed && got.length > 0) { fails.push(`${name}: must be GREEN, got ${pyRepr(got)}`); return; }
    if (mustName && !got.some((g) => g.includes(mustName))) {
      fails.push(`${name}: must name ${mustName}, got ${pyRepr(got)}`);
    }
  };

  // (a) THE SEAM
  kase("seam: routes through a local and ships it", tree(), SEAM_OK, false);
  kase("seam: routes INLINE straight to the wire", tree(SEAM_INLINE), SEAM_INLINE, false);
  kase("seam: emitError no longer applies the rule", tree(SEAM_NO_ROUTE), SEAM_NO_ROUTE, true, "WITHOUT PreContentWireType.of(");
  kase("seam: the rule applied twice in one body", tree(SEAM_TWICE), SEAM_TWICE, true, "2 times");
  kase("seam: the routing CONTAINED in a conditional (V4-82 part 1, at the seam)", tree(SEAM_MIXED), SEAM_MIXED, true, "rather than AS the expression");
  kase("seam: the routing elvis'd behind the raw type", tree(SEAM_ELVIS), SEAM_ELVIS, true, "rather than AS the expression");
  kase("seam: the content flag pinned to the literal true", tree(SEAM_FLAG_TRUE), SEAM_FLAG_TRUE, true, "returns the type UNCHANGED");
  kase("seam: routes, then writes the RAW type to the wire", tree(SEAM_DISCARDS), SEAM_DISCARDS, true, "computed and DISCARDED");
  kase("seam: routes but writes no wire type at all", tree(SEAM_NO_WIRE), SEAM_NO_WIRE, true, "hands no `.wireName`");
  // (b) NO COPIES
  kase("copies: a second file applies the rule (the V4-79 hand-copy defect)", tree(SEAM_OK, { TurnConnEnd: HAND_COPY }), SEAM_OK, true, "TurnConnEnd.kt:3");
  kase("copies: an ending that passes its REAL type is CORRECT after V4-81", tree(SEAM_OK, { TurnEnding: CLEAN_ENDING }), SEAM_OK, false);
  // (c) THE GOVERNED SET
  kase("ratchet: the rule NARROWED to stop governing API_ERROR", tree(SEAM_NARROWED), SEAM_NARROWED, true, "NARROWED");
  // (d) ENVELOPES OUTSIDE THE SEAM
  kase("envelopes: a new ending builds its own error envelope", tree(SEAM_OK, { NewEnding: ENVELOPE_OUTSIDE }), SEAM_OK, true, "NewEnding.kt:3");
  kase("envelopes: a new errorEnvelope( builder outside the seam", tree(SEAM_OK, { NewEnding: ENVELOPE_BUILDER }), SEAM_OK, true, "NewEnding.kt:2");

  // V4-102's two new exemptions, each with a GREEN case AND a RED twin whose only difference is the
  // missing mark — the pairing is what proves the exemption fires for the enumerated reason rather
  // than for whatever happens to be in the file.
  const envelopeBody = 'fun body() = buildJsonObject { put("type", "error") }';
  kase("envelopes: the pre-turn admission plane keeps its WRITTEN exemption",
    { [SEAM_FILE]: SEAM_OK, [COLLECT_FILE]: COLLECT, [PRE_TURN_FILE]: `object ${PRE_TURN_MARK} { ${envelopeBody} }` }, SEAM_OK, false);
  kase("envelopes: the pre-turn exemption without its mark is RED",
    { [SEAM_FILE]: SEAM_OK, [COLLECT_FILE]: COLLECT, [PRE_TURN_FILE]: `object SomethingElse { ${envelopeBody} }` }, SEAM_OK, true, PRE_TURN_MARK);
  kase("envelopes: the pooled refusal keeps its WRITTEN exemption",
    { [SEAM_FILE]: SEAM_OK, [COLLECT_FILE]: COLLECT, [POOLED_FILE]: `class ${POOLED_MARK} { ${envelopeBody} }` }, SEAM_OK, false);
  kase("envelopes: the pooled exemption without its mark is RED",
    { [SEAM_FILE]: SEAM_OK, [COLLECT_FILE]: COLLECT, [POOLED_FILE]: `class SomethingElse { ${envelopeBody} }` }, SEAM_OK, true, POOLED_MARK);

  kase("envelopes: the collect path keeps its WRITTEN exemption", tree(), SEAM_OK, false);
  kase("envelopes: the exemption's file is no longer the collect terminal",
    { [SEAM_FILE]: SEAM_OK, [COLLECT_FILE]: COLLECT_STALE }, SEAM_OK, true, "may not outlive that reason");
  kase("envelopes: a telemetry `.wireName` is not an envelope", tree(SEAM_OK, { TurnLine: TELEMETRY_READ }), SEAM_OK, false);
  // decoys
  kase("decoy: a COMMENT claiming the routing beside a raw-type emit", tree(SEAM_OK, { TurnEnding: COMMENT_DECOY }), SEAM_OK, false);
  kase("decoy: a raw STRING carrying both the routing and an envelope", tree(SEAM_OK, { Doc: STRING_DECOY }), SEAM_OK, false);
  // FAIL-CLOSED
  kase("vacuous: the seam file is gone", tree(), null, true, "is missing");
  kase("vacuous: the rule object no longer declared", tree(SEAM_NO_RULE), SEAM_NO_RULE, true, "no longer declares");
  kase("vacuous: no sources at all", {}, SEAM_OK, true);
  kase("vacuous: zero error-envelope constructions anywhere", { [SEAM_FILE]: SEAM_NO_WIRE }, SEAM_NO_WIRE, true);

  if (![...governedTypes(codeView(SEAM_OK))].every((t) => ["RATE_LIMIT", "API_ERROR"].includes(t))
      || governedTypes(codeView(SEAM_OK)).size < 2) {
    fails.push("the governed set must be READ from the rule object's body, not assumed");
  }
  if (ruleParams(codeView(SEAM_OK)).slice(0, 2).join(",") !== "type,contentReachedClient") {
    fails.push("the content flag must be DERIVED from the rule's own `fun of(` signature");
  }
  if (fails.length > 0) {
    process.stdout.write("PRE-CONTENT-WIRE-TYPE SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "PRE-CONTENT-WIRE-TYPE SELFTEST OK — green on the live seam (routed through a local and " +
      "inline), on an ending that passes its real type, on the collect path's written " +
      "exemption, on a telemetry .wireName and on comment/raw-string decoys; red by file:line " +
      "on a seam that stops routing, routes twice, routes inside a conditional or an elvis, " +
      "pins the content flag to true, discards the routed value, or writes no wire type; red " +
      "on a SECOND site applying the rule (the V4-79 hand-copy defect) and on any error " +
      "envelope built outside the seam; red rather than vacuous on a stale exemption, a " +
      "NARROWED rule, a vanished rule object, a missing seam, no sources, and zero envelopes\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const [sources, seamText] = load();
  const problems = detect(sources, seamText);
  if (problems.length > 0) {
    process.stdout.write(
      "PRE-CONTENT-WIRE-TYPE WALL RED — the pre-content rule is not the seam's sole, whole " +
        "application, or an in-band error envelope is built outside it:\n",
    );
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    `PRE-CONTENT-WIRE-TYPE WALL GREEN: ${SEAM_FILE}'s ${SEAM_FUN} applies ${ROUTED}…) as the ` +
      `whole wire type and ships it; no other main source among ${Object.keys(sources ?? {}).length} applies ` +
      "the rule or builds an in-band error envelope outside the one written exemption.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
