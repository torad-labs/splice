#!/usr/bin/env bun
/** WALL for NF-01 — the per-head 429 cooldown horizon must be bounded AND clearable.
 *
 *  GAP (RED at authoring, 2026-07-26): UpstreamClient.kt:407-408 armed a head-wide fail-fast horizon
 *  straight from provider pushback with no ceiling —
 *      val until = clock() + (failed.retryAfterMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)
 *      rateLimitedUntilMs.accumulateAndGet(until) { current, candidate -> maxOf(current, candidate) }
 *  `accumulateAndGet(max)` means the LONGEST value ever seen wins permanently, so one malformed or
 *  multi-day `Retry-After` poisons the head for every concurrent and future turn. HeadServer.restart()
 *  rebuilds the Netty engine and calls driver.resetHealth() only — it cannot clear this AtomicLong.
 *
 *  RE-ANCHORED 2026-08-18 (HD-25): the horizon and every one of its touch points moved out of
 *  UpstreamClient.kt into splice/spi/RateLimitCooldown.kt — deliberately together, so the shared
 *  mutable state and its rules could not be split. The wall follows the code and gains a leg rather
 *  than losing one: `clearRateLimitCooldown()` is now a DELEGATE on UpstreamClient, so the chain
 *  HeadServer -> UpstreamClient.clearRateLimitCooldown -> RateLimitCooldown.clear must be checked
 *  link by link. A delegate that no longer reaches the AtomicLong is a restart that silently stops
 *  being an escape hatch, which is exactly the half-fix this wall exists to catch. Not broadened:
 *  still exact files, exact expressions, no module-wide or substring matching.
 *
 *  Three independent conditions (a clamp with no escape hatch is a fraction of the fix):
 *    1. a MAX_RATE_LIMIT_COOLDOWN_MS clamp exists AND is APPLIED where the horizon is armed
 *    2. RateLimitCooldown.clear() exists AND actually zeroes the AtomicLong
 *    3. UpstreamClient.clearRateLimitCooldown() exists AND delegates to it AND HeadServer calls it
 *
 *  EXIT 0 = all closed.  EXIT 1 = any open.
 *  --selftest = the POSITIVE CONTROL (gate check C6): proves this wall separates open from closed,
 *               including the declared-but-unapplied, exists-but-uncalled and delegates-nowhere
 *               half-fixes.
 *
 *  V4-154: converted to TypeScript (bun). re.S becomes [\s\S] throughout, the named body group
 *  becomes a positional capture, fullmatch becomes an anchored whole-string test, and splitlines is
 *  reimplemented over Python's full set of line boundaries rather than only \n, because arm_expr is
 *  a line FILTER whose membership decides the clamp leg.
 */
import { existsSync, readFileSync } from "node:fs";
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
const COOLDOWN = resolve(ROOT, "upstream/src/main/kotlin/splice/upstream/retry/RateLimitCooldown.kt");
const UPSTREAM = resolve(ROOT, "upstream/src/main/kotlin/splice/upstream/transport/UpstreamClient.kt");
const HEADSERVER = resolve(ROOT, "daemon/head/src/main/kotlin/splice/head/HeadServer.kt");

const ARM_RE = /rateLimitedUntilMs\.accumulateAndGet\([\s\S]*?\n/;
const CLEAR_BODY_RE = /fun\s+clear\s*\(\s*\)\s*(?::\s*Unit\s*)?\{([^{}]*)\}/g;
/** Python fullmatch: the WHOLE body must be the unconditional reset. */
const CLEAR_RESET_RE = /^\s*rateLimitedUntilMs\.set\(0L\)\s*;?\s*$/;
const DELEGATE_RE = /fun\s+clearRateLimitCooldown\s*\([^)]*\)[^{=]*[{=]\s*\n?\s*cooldown\.clear\(\)/;
const CLAMP = "MAX_RATE_LIMIT_COOLDOWN_MS";

/** Python str.splitlines(): splits on every line boundary, not only \n. */
function splitLines(text: string): string[] {
  return text.split(/\r\n|[\n\r\v\f\x1c-\x1e\x85\u2028\u2029]/);
}

/** Python re.finditer(...).map(m => m.group(1)) for the single-group CLEAR_BODY_RE. */
function clearBodies(text: string): string[] {
  return [...text.matchAll(CLEAR_BODY_RE)].map((m) => m[1]);
}

/** Pure detection — no I/O, so the selftest can feed synthetic sources. */
export function detect(cooldown: string | null, upstream: string | null, headserver: string | null): string[] {
  if (cooldown === null || upstream === null || headserver === null) {
    return [
      "RateLimitCooldown.kt, UpstreamClient.kt or HeadServer.kt missing — " + "refusing to pass vacuously",
    ];
  }
  const problems: string[] = [];

  const arm = ARM_RE.test(cooldown);
  if (!arm) {
    return [
      "the cooldown arming site (rateLimitedUntilMs.accumulateAndGet) was not found in " +
        "RateLimitCooldown.kt — shape changed; refusing to pass vacuously",
    ];
  }

  // The clamp must appear in the EXPRESSION that computes the horizon, not merely somewhere in
  // the preceding bytes. An earlier version scanned a 400-char lookbehind window, which counted a
  // `const val MAX_… = …` declaration sitting just above as "applied" — a false pass its own
  // positive control caught (2026-07-26). Bind to the assignment and the arming call instead.
  const armExpr = splitLines(cooldown)
    .filter(
      (ln) =>
        (ln.includes("val until") && (ln.includes("nowMs") || ln.includes("clock()"))) ||
        ln.includes("rateLimitedUntilMs.accumulateAndGet"),
    )
    .join("\n");

  if (!cooldown.includes(CLAMP)) {
    problems.push(
      `no ${CLAMP} clamp constant in RateLimitCooldown.kt ` + "(only DEFAULT_RATE_LIMIT_COOLDOWN_MS exists)",
    );
  } else if (!armExpr.includes(CLAMP)) {
    problems.push(
      `${CLAMP} is declared but NOT applied in the horizon expression ` +
        "(`val until = … + …` / accumulateAndGet) — a declared-but-unused clamp " +
        "bounds nothing",
    );
  }

  const clears = clearBodies(cooldown);
  if (clears.length !== 1 || !CLEAR_RESET_RE.test(clears[0])) {
    problems.push(
      "RateLimitCooldown must have exactly one clear() whose complete body is the " +
        "unconditional rateLimitedUntilMs.set(0L) reset — decoys, conditional " +
        "mentions, or a later re-arm do not make restart clear the horizon",
    );
  } else if (!DELEGATE_RE.test(upstream)) {
    problems.push(
      "UpstreamClient.clearRateLimitCooldown() does not delegate to " +
        "cooldown.clear() — the head-facing escape hatch no longer reaches the " +
        "AtomicLong it is supposed to zero",
    );
  } else if (!headserver.includes("clearRateLimitCooldown(")) {
    problems.push(
      "clearRateLimitCooldown() exists but HeadServer never calls it — " +
        "restart is still not an escape hatch",
    );
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry.
 *
 *  All three readers are stripped because all three legs are REQUIRED tokens (clamp, clear,
 *  delegate, call) — this wall carries no banned string, which is the direction that would have to
 *  stay raw. It also un-poisons `armExpr`: RateLimitCooldown's KDoc literally contains
 *  "`val until = clock() + minOf(...)`", a comment line the clamp check would otherwise read as
 *  part of the horizon expression. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

export const ARM_OPEN =
  "val until = nowMs + (pushbackMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS)\n" +
  "rateLimitedUntilMs.accumulateAndGet(until) { c, cand -> maxOf(c, cand) }\n";
export const ARM_CLAMPED =
  "val until = nowMs + minOf(pushbackMs ?: DEFAULT_RATE_LIMIT_COOLDOWN_MS, " +
  "MAX_RATE_LIMIT_COOLDOWN_MS)\n" +
  "rateLimitedUntilMs.accumulateAndGet(until) { c, cand -> maxOf(c, cand) }\n";
export const CLEAR_DEF = "fun clear() { rateLimitedUntilMs.set(0L) }\n";
export const CLEAR_OUTSIDE = "fun clear() { }\nfun resetForTests() { rateLimitedUntilMs.set(0L) }\n";
export const CLEAR_DECOY =
  "class Decoy { fun clear() { rateLimitedUntilMs.set(0L) } }\n" + "fun clear() { }\n";
export const CLEAR_REARMED =
  "fun clear() { rateLimitedUntilMs.set(0L); " + "rateLimitedUntilMs.set(Long.MAX_VALUE) }\n";
export const UC_DELEGATES = "public fun clearRateLimitCooldown() {\n    cooldown.clear()\n}\n";
export const UC_DELEGATES_NOWHERE = 'public fun clearRateLimitCooldown() {\n    log("cleared")\n}\n';
export const HS_CALLS = "driver.resetHealth(); upstream.clearRateLimitCooldown()\n";
export const HS_PLAIN = "driver.resetHealth()\n";

function selftest(): number {
  const fails: string[] = [];

  const kase = (
    name: string,
    cd: string | null,
    uc: string | null,
    hs: string | null,
    wantRed: boolean,
  ): void => {
    const got = detect(cd, uc, hs);
    if (wantRed && got.length === 0) {
      fails.push(`${name}: must be RED`);
    }
    if (!wantRed && got.length > 0) {
      fails.push(`${name}: must be GREEN, got ${pyRepr(got)}`);
    }
  };

  kase("open (no clamp, no clear)", ARM_OPEN, UC_DELEGATES, HS_PLAIN, true);
  kase(
    "half-fix: clamp declared but not applied at arming site",
    "const val MAX_RATE_LIMIT_COOLDOWN_MS = 120_000L\n" + ARM_OPEN + CLEAR_DEF,
    UC_DELEGATES,
    HS_CALLS,
    true,
  );
  kase("half-fix: clear defined but HeadServer never calls it", ARM_CLAMPED + CLEAR_DEF, UC_DELEGATES, HS_PLAIN, true);
  kase(
    "half-fix: delegate exists but no longer reaches the AtomicLong",
    ARM_CLAMPED + CLEAR_DEF,
    UC_DELEGATES_NOWHERE,
    HS_CALLS,
    true,
  );
  kase("half-fix: clear() declared but does not zero the AtomicLong", ARM_CLAMPED + "fun clear() { }\n", UC_DELEGATES, HS_CALLS, true);
  kase("half-fix: AtomicLong reset moved outside clear()", ARM_CLAMPED + CLEAR_OUTSIDE, UC_DELEGATES, HS_CALLS, true);
  kase("half-fix: decoy clear() masks the real empty clear()", ARM_CLAMPED + CLEAR_DECOY, UC_DELEGATES, HS_CALLS, true);
  kase("half-fix: clear() resets and then re-arms the horizon", ARM_CLAMPED + CLEAR_REARMED, UC_DELEGATES, HS_CALLS, true);
  kase("closed (clamp applied + clear zeroes + delegate + called)", ARM_CLAMPED + CLEAR_DEF, UC_DELEGATES, HS_CALLS, false);
  kase("missing sources", null, null, null, true);
  kase("arming site shape changed", "fun unrelated() {}\n", UC_DELEGATES, HS_CALLS, true);

  if (fails.length > 0) {
    process.stdout.write("NF-01 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "NF-01 SELFTEST OK — red on open and seven half-fixes, including reset outside clear, " +
      "a decoy clear, and reset-then-rearm; green only when clamp, exact reset, delegate, and " +
      "restart call are all live\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(COOLDOWN), read(UPSTREAM), read(HEADSERVER));
  if (problems.length > 0) {
    process.stdout.write("NF-01 WALL RED — the 429 cooldown horizon is unbounded and/or unclearable:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "NF-01 WALL GREEN: cooldown horizon is clamped at the arming site in " +
      "RateLimitCooldown.kt, and cleared on head restart through UpstreamClient's delegate.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
