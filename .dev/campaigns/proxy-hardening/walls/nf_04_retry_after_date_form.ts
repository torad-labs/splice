#!/usr/bin/env bun
/** WALL for NF-04 — Retry-After's HTTP-date form must be honoured, not silently discarded.
 *
 *  GAP (RED at authoring, 2026-08-07): retryAfterMs parsed only integer seconds; RFC 7231's
 *  HTTP-date form returned null, so the server's pushback was not a backoff floor, the absurd-
 *  pushback give-up could not fire, and the 429 cooldown fell back to the 20s guess. Cloudflare and
 *  gateway fronts emit the date form.
 *
 *  RE-ANCHORED 2026-08-18 (HD-25): the parse moved out of UpstreamClient.kt into its own file,
 *  splice/spi/RetryAfter.kt, and split into `secondsFormMs` + `httpDateMs` behind an elvis chain. The
 *  wall follows it and gains a leg it should always have had: it asserted "numeric-first ordering is
 *  the pinned behavior" in prose while only checking that `toLongOrNull` existed ANYWHERE in the file.
 *  Under a two-parser split that is no longer enough — a chain that tries the date form first would
 *  have passed the old check. The ordering is now checked. Not broadened: still one exact file, still
 *  exact tokens, no module-wide or substring matching.
 *
 *  GREEN requires, in RetryAfter.kt: a `retryAfterMs` entry point that reaches both a strict seconds
 *  parse (`toLongOrNull`) and an RFC_1123_DATE_TIME parser; and the seconds call textually AHEAD of the
 *  date call, which is the numeric-first spec. A dead date helper elsewhere in the file earns nothing.
 *
 *  WIDENED 2026-09-18 (V4-100). The leg above pins the ONE parser it can see, which was the whole gap:
 *  a SECOND parser elsewhere is not a smaller version of this bug, it is a fresh copy of the original
 *  one, and this wall could not see it at all. app/MuseRefresh.kt had grown exactly that — its own
 *  `retryAfterMs` with its own ordering and its own clamping — and no checker in the tree read it. So
 *  the wall now also refuses a SECOND parser ANYWHERE in the gateway's main sources.
 *
 *  The shape of the refusal is deliberately narrow, because a wall that flags every mention of the
 *  header is a wall that gets allowlisted: a file is a second parser only when it BOTH names the
 *  Retry-After header AND carries a token that only a parser has — an RFC_1123_DATE_TIME format, the
 *  digit-only seconds guard (`it in '0'..'9'`, either polarity), or the leading-zero normalizer. A file
 *  that merely reads the header and hands it to splice.spi.RetryAfter has none of those, so delegating
 *  stays cheap and re-implementing goes red. Measured against the tree at authoring: of every main
 *  source, exactly ONE file carries a marker, and it is the one allowed file.
 *
 *  EXIT 0 = date form honoured, seconds first, and this is the only parser. EXIT 1 = gap open.
 *  --selftest = the POSITIVE CONTROL (gate check C6).
 *
 *  V4-154: converted to TypeScript (bun). Python's search(text, pos) becomes a lastIndex seek on a
 *  fresh global regex, re.S becomes [\s\S], and the recursive rglob walk is reimplemented with the
 *  same sort order and the same /build/ and /src/test/ exclusions.
 */
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";

/** Python repr() of a string, and of a list of strings. An f-string that interpolates a LIST
 *  renders THIS -- `['a', 'b']` -- not JSON, so a port that used JSON.stringify produced a
 *  DIFFERENT failure message than the original on every red path while agreeing on every green
 *  one. Caught by driving the mutants as a CLI rather than feeding detect() a fixed corpus. */
/** Python escapes a character when str.isprintable() is False: categories Cc Cf Cs Co Cn Zl Zp,
 *  and Zs except the plain space. Only \n \r \t get short spellings; the rest render \xNN below
 *  0x100, \uNNNN below 0x10000, \UNNNNNNNN above. */
const NON_PRINTABLE = /[\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}\p{Zl}\p{Zp}\p{Zs}]/u;
function pyReprStr(s: string): string {
  const useDouble = s.includes("'") && !s.includes('"');
  const q = useDouble ? '"' : "'";
  let body = "";
  for (const ch of s) {
    const cp = ch.codePointAt(0) as number;
    if (ch === "\\") body += "\\\\";
    else if (ch === "\n") body += "\\n";
    else if (ch === "\r") body += "\\r";
    else if (ch === "\t") body += "\\t";
    else if (ch === q) body += "\\" + q;
    else if (NON_PRINTABLE.test(ch) && ch !== " ") {
      body +=
        cp < 0x100 ? "\\x" + cp.toString(16).padStart(2, "0")
        : cp < 0x10000 ? "\\u" + cp.toString(16).padStart(4, "0")
        : "\\U" + cp.toString(16).padStart(8, "0");
    } else body += ch;
  }
  return q + body + q;
}
function pyRepr(items: string[]): string {
  return "[" + items.map(pyReprStr).join(", ") + "]";
}

const ROOT = resolve(import.meta.dir, "../../../..");
const CLIENT = resolve(ROOT, "gateway/provider-spi/src/main/kotlin/splice/spi/RetryAfter.kt");
const NEXT_FUNCTION_RE = /^[ \t]*(?:(?:public|private|internal|protected|override|suspend|inline)[ \t]+)*fun[ \t]+\w+[ \t]*\(/m;
const RETURN_CHAIN_RE = /\breturn[ \t\n]+secondsFormMs\s*\([^)]*\)\s*\?:\s*httpDateMs\s*\([^)]*\)/;
const SECONDS_HELPER_RE = /\bfun\s+secondsFormMs\s*\([^)]*\)[^=]*=\s*\w+\.toLongOrNull\s*\(/;
const DATE_HELPER_RE = /\bfun\s+httpDateMs\s*\([^)]*\)[^=]*=\s*try\s*\{[\s\S]*RFC_1123_DATE_TIME/;

function reEsc(s: string): string {
  return s.replace(/[^A-Za-z0-9_]/g, (c) => "\\" + c);
}

/** Python re.search(pattern, text, pos): a match starting at or after `pos`. */
function searchFrom(re: RegExp, text: string, pos: number): RegExpExecArray | null {
  const g = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
  g.lastIndex = pos;
  return g.exec(text);
}

/** Every function with this name, each bounded by the next function declaration. */
export function functionSources(text: string, name: string): string[] {
  const starts = [...text.matchAll(new RegExp("\\bfun[ \\t]+" + reEsc(name) + "[ \\t]*\\(", "g"))];
  const sources: string[] = [];
  for (const start of starts) {
    const following = searchFrom(NEXT_FUNCTION_RE, text, (start.index as number) + start[0].length);
    const end = following === null ? text.length : (following.index as number);
    sources.push(text.slice(start.index as number, end));
  }
  return sources;
}

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(clientText: string | null): string[] {
  if (clientText === null) {
    return ["RetryAfter.kt missing — refusing to pass vacuously"];
  }
  const retries = functionSources(clientText, "retryAfterMs");
  if (retries.length !== 1) {
    return [
      "RetryAfter.kt must contain exactly one retryAfterMs entry point — refusing to " +
        "credit a missing or decoy declaration",
    ];
  }

  const problems: string[] = [];
  if (!RETURN_CHAIN_RE.test(retries[0])) {
    problems.push(
      "retryAfterMs must return the direct secondsFormMs(...) ?: " +
        "httpDateMs(...) chain — calls that are discarded, deferred, or date-first " +
        "do not implement numeric-first fallback",
    );
  }

  const seconds = functionSources(clientText, "secondsFormMs");
  if (seconds.length !== 1 || !SECONDS_HELPER_RE.test(seconds[0])) {
    problems.push(
      "RetryAfter.kt must contain exactly one strict secondsFormMs helper whose " +
        "returned expression starts at toLongOrNull — same-name decoys earn nothing",
    );
  }
  const dates = functionSources(clientText, "httpDateMs");
  if (dates.length !== 1 || !DATE_HELPER_RE.test(dates[0])) {
    problems.push(
      "RetryAfter.kt must contain exactly one httpDateMs try-parser backed by " +
        "RFC_1123_DATE_TIME — dead helpers and same-name decoys earn nothing",
    );
  }
  return problems;
}

// --- the WIDENED leg (V4-100) ------------------------------------------------------------------
// Detection stays pure (path -> code text) so the selftest can feed a second parser synthetically
// instead of having to write one to disk.
// Every §2.2 module home, walked whole exactly as gateway/ was (restructure PR 3 moves the modules
// out of gateway/ one commit at a time; an absent home contributes nothing, so the second-parser
// prohibition can only widen as modules land, never shrink).
const MAIN_SOURCES = ["gateway", "client", "core", "upstream", "dialects", "providers", "daemon", "app", "quality"];
const HEADER_MENTION_RE = /Retry-After|retry_after|retryAfter/i;
// Tokens ONLY a parser carries: the RFC 7231 date format, the digit-only seconds guard (either
// polarity — `all { it in '0'..'9' }` accepts it, `any { it !in '0'..'9' }` rejects non-digits), and
// the leading-zero normalizer that makes an arbitrarily padded seconds value small. A file that
// merely hands the header to splice.spi.RetryAfter carries none of them, which is what keeps
// delegating free and re-implementing red.
const PARSER_MARKER_RES = [
  /RFC_1123_DATE_TIME/,
  /it\s+!?in\s+'0'\.\.'9'/,
  /trimStart\('0'\)\s*\.ifEmpty/,
];

/** Pure detection over main-source path -> code text. No I/O. */
export function detectSecondParser(sources: Record<string, string>): string[] {
  const problems: string[] = [];
  for (const path of Object.keys(sources).sort()) {
    const text = sources[path];
    if (!HEADER_MENTION_RE.test(text)) {
      continue;
    }
    const markers = PARSER_MARKER_RES.filter((r) => r.test(text)).map((r) => r.source);
    if (markers.length > 0) {
      problems.push(
        `${path} parses the Retry-After header itself (${markers.length} parser token(s): ` +
          `${markers.join(", ")}). splice.spi.RetryAfter is the ONE parser — a second copy is ` +
          "a second set of ordering and clamping rules for the same header, which is the gap " +
          "this wall was blind to. Call it, do not re-derive it.",
      );
    }
  }
  return problems;
}

/** Every main-source Kotlin file under every module home that is not the one allowed parser, code-only and
 *  keyed by repo-relative path. The allowed file is excluded because its markers are the POINT —
 *  detect() is what judges it. */
export function mainSourceFiles(): Record<string, string> {
  const allowed = CLIENT;
  const out: Record<string, string> = {};
  const found: string[] = [];
  const stack = MAIN_SOURCES.map((home) => resolve(ROOT, home)).filter((dir) => existsSync(dir));
  while (stack.length > 0) {
    const cur = stack.pop() as string;
    for (const e of readdirSync(cur, { withFileTypes: true })) {
      const p = resolve(cur, e.name);
      if (e.isDirectory()) stack.push(p);
      else if (e.name.endsWith(".kt")) found.push(p);
    }
  }
  found.sort();
  for (const path of found) {
    const posix = path.split("\\").join("/");
    if (posix.includes("/build/") || posix.includes("/src/test/")) {
      continue;
    }
    if (path === allowed) {
      continue;
    }
    const text = read(path);
    if (text !== null) {
      out[path.slice(ROOT.length + 1)] = text;
    }
  }
  return out;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry.
 *
 *  The one reader is stripped: every leg is a REQUIRED token and this wall carries no banned
 *  string, which is the only direction that would have to stay raw. The ORDERING leg needs it
 *  twice over — RetryAfter.kt's KDoc discusses both parsers in prose, so a comment could reorder
 *  the two `.index()` reads without a line of code moving. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) && statSync(p).isFile() ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const OPEN_FIX = "fun retryAfterMs(header: String?): Long? =\n    header?.trim()?.toLongOrNull()";
const HELPERS =
  "fun secondsFormMs(value: String): Long? = value.toLongOrNull()\n" +
  "fun httpDateMs(value: String): Long? = try { " +
  "parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) } catch (ignored: Exception) { null }";
export const CLOSED_FIX =
  "fun retryAfterMs(header: String): Long? {\n" +
  "    return secondsFormMs(header) ?: httpDateMs(header)\n" +
  "}\n" +
  HELPERS;
export const BROKEN_FIX = "fun retryAfterMs(h: String?): Long? = DateTimeFormatter.RFC_1123_DATE_TIME_only";
export const INVERTED_FIX =
  "fun retryAfterMs(header: String): Long? {\n" +
  "    return httpDateMs(header) ?: secondsFormMs(header)\n" +
  "}\n" +
  HELPERS;
export const UNWIRED_DATE_FIX =
  "fun retryAfterMs(header: String): Long? { return secondsFormMs(header) }\n" + HELPERS;
export const DISCARDED_SECONDS_FIX =
  "fun retryAfterMs(header: String): Long? {\n" +
  "    secondsFormMs(header)\n" +
  "    return httpDateMs(header)\n" +
  "}\n" +
  HELPERS;
export const DECOY_HELPERS_FIX =
  "class Decoy {\n" +
  "    fun secondsFormMs(value: String): Long? = value.toLongOrNull()\n" +
  "    fun httpDateMs(value: String): Long? = parse(value, RFC_1123_DATE_TIME)\n" +
  "}\n" +
  "fun retryAfterMs(header: String): Long? {\n" +
  "    return secondsFormMs(header) ?: httpDateMs(header)\n" +
  "}\n" +
  "fun secondsFormMs(value: String): Long? = null\n" +
  "fun httpDateMs(value: String): Long? = null";

// The widened leg's fixtures. A SECOND parser (red) is the real regression this leg exists for —
// app/MuseRefresh.kt carried exactly this shape until V4-100 deleted it. The two GREEN fixtures are
// the false-positive controls: one delegates (mention, no marker), one parses an RFC 1123 date for
// something that is not this header (marker, no mention). The leg is the AND of the two, and each
// control fails if it silently becomes an OR.
export const SECOND_PARSER_FIXTURE: Record<string, string> = {
  "gateway/app/src/main/kotlin/splice/app/SecondParser.kt":
    "private fun retryAfterMs(header: String?): Long? {\n" +
    "    val value = header?.trim() ?: return null\n" +
    "    if (value.all { it in '0'..'9' }) return value.toLongOrNull()\n" +
    "    return try { parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) } " +
    "catch (_: Exception) { null }\n" +
    "}",
};
export const DELEGATING_FIXTURE: Record<string, string> = {
  "gateway/app/src/main/kotlin/splice/app/Delegating.kt":
    'val ms = retryAfter.retryAfterMs(response.headers["Retry-After"], clock)\n',
};
export const UNRELATED_DATE_FIXTURE: Record<string, string> = {
  "gateway/app/src/main/kotlin/splice/app/OtherDates.kt":
    "val expiry = ZonedDateTime.parse(cookie, DateTimeFormatter.RFC_1123_DATE_TIME)\n",
};
// A seconds-ONLY re-derivation in a different module: no date token at all, so it is caught by the
// digit/normalizer markers rather than the RFC one — the leg is not "the date parser moved".
export const SECOND_PARSER_FIXTURE_OTHER_MODULE: Record<string, string> = {
  "gateway/gateway/src/main/kotlin/splice/gateway/head/SecondParser.kt":
    'private val RETRY_AFTER = Regex("retry[-_]after", RegexOption.IGNORE_CASE)\n' +
    "fun seconds(value: String): Long = " +
    "value.trimStart('0').ifEmpty { \"0\" }.toLong()\n",
};

function selftest(): number {
  const fails: string[] = [];
  if (detect(OPEN_FIX).length === 0) {
    fails.push("seconds-only parser must be RED");
  }
  if (detect(CLOSED_FIX).length > 0) {
    fails.push(`seconds-first + date-fallback must be GREEN, got ${pyRepr(detect(CLOSED_FIX))}`);
  }
  if (detect(BROKEN_FIX).length === 0) {
    fails.push("a date-only parser that dropped the strict seconds path must be RED");
  }
  if (detect(INVERTED_FIX).length === 0) {
    fails.push(
      "a chain that tries the HTTP-date form FIRST must be RED — that is the " + "ordering this wall pins",
    );
  }
  if (detect(UNWIRED_DATE_FIX).length === 0) {
    fails.push("an HTTP-date helper that retryAfterMs never calls must be RED");
  }
  if (detect(DISCARDED_SECONDS_FIX).length === 0) {
    fails.push("a discarded seconds result followed by an unconditional date return must be RED");
  }
  if (detect(DECOY_HELPERS_FIX).length === 0) {
    fails.push("same-name decoy helpers must not mask the called broken helpers");
  }
  if (detect(null).length === 0) {
    fails.push("missing RetryAfter.kt must be RED, never a vacuous pass");
  }
  if (detect("class RetryAfterHeader").length === 0) {
    fails.push("a tree without retryAfterMs (shape change) must be RED, refusing vacuous pass");
  }
  if (detectSecondParser(SECOND_PARSER_FIXTURE).length === 0) {
    fails.push(
      "a SECOND Retry-After parser elsewhere in the gateway must be RED — that is " +
        "the widened leg's entire point",
    );
  }
  if (detectSecondParser(DELEGATING_FIXTURE).length > 0) {
    fails.push(
      "a file that merely delegates to splice.spi.RetryAfter must be GREEN, got " +
        pyRepr(detectSecondParser(DELEGATING_FIXTURE)),
    );
  }
  if (detectSecondParser(UNRELATED_DATE_FIXTURE).length > 0) {
    fails.push(
      "an RFC 1123 date parsed for something that is NOT the Retry-After header must " +
        "be GREEN — the leg is mention AND marker, never the marker alone",
    );
  }
  if (detectSecondParser(SECOND_PARSER_FIXTURE_OTHER_MODULE).length === 0) {
    fails.push(
      "a seconds-only re-derivation in another module must be RED too — the leg is " +
        "not 'the date parser moved', and a gate-only copy is still a copy",
    );
  }
  if (fails.length > 0) {
    process.stdout.write("NF-04 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "NF-04 SELFTEST OK — red on seconds/date-only, date-first, unwired/discarded parser " +
      "results, same-name decoys, missing file, shape change, and a SECOND parser anywhere in " +
      "the gateway's main sources; green on the direct seconds-first return chain and on a file " +
      "that delegates\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(CLIENT));
  problems.push(...detectSecondParser(mainSourceFiles()));
  if (problems.length > 0) {
    process.stdout.write(
      "NF-04 WALL RED — the Retry-After header is parsed in more than one place, or its " +
        "HTTP-date form is discarded:\n",
    );
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "NF-04 WALL GREEN: both RFC 7231 forms honoured, seconds-first, and RetryAfter.kt is the " +
      "only parser in the gateway's main sources.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
