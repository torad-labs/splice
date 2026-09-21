#!/usr/bin/env bun
/** WALL for CX-18 — usage must be read through ONE alias chain, and the real-world alias shapes
 *  must be in it.
 *
 *  GAP (RED at authoring, 2026-08-10): usage parsing was three hand-rolled readers that disagreed.
 *    · openai-chat read ONLY `prompt_tokens` / `completion_tokens` for the two main buckets. Several
 *      OpenAI-compatible backends and OpenRouter's Responses-shaped routes emit `input_tokens` /
 *      `output_tokens`, so those turns landed with ZERO usage.
 *    · anthropic-passthrough read only the FLAT `cache_creation_input_tokens` and missed Anthropic's
 *      newer nested `cache_creation: {ephemeral_5m_input_tokens, ephemeral_1h_input_tokens}`.
 *      successOutcome folds cacheCreation back into inputTokens, so missing it understated the whole
 *      context-window percentage on every cache-writing turn.
 *    · chat's own `num(vararg keys)` helper was a fourth reader, used for the cached bucket only.
 *
 *  Wrong usage is not cosmetic: `used_percentage` drives Claude Code's auto-compaction trigger, so a
 *  blind head either never compacts or compacts constantly.
 *
 *  GREEN requires all three, and they are independent failures:
 *    1. ONE SHARED CHAIN EXISTS — `firstLong` in :core, next to the other JsonNull-safe scalar reads.
 *    2. EACH TRANSLATOR USES IT for the shapes it missed — chat for the input_/output_ aliases (with
 *       the CANONICAL spelling first, so a backend emitting both is read by the standard field), and
 *       passthrough for the nested cache_creation SUM.
 *    3. NO TRANSLATOR KEEPS A PRIVATE ALIAS READER — chat's `num(obj: JsonObject, vararg keys` is
 *       gone. Leaving it is how three readers became four; a wall that only checked the new behavior
 *       would stay green while the drift it exists to end quietly regrew.
 *
 *  The nested read is pinned as a SUM (`parts.sum()`), not merely as a mention of `cache_creation`:
 *  a first-of read over two TTL buckets silently drops one of them, which is the same undercount in a
 *  smaller costume.
 *
 *  Tokens measured at 0 occurrences in HEAD d0da545, >=1 after the fix.
 *
 *  EXIT 0 = closed. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6), with the half-fixes
 *  DERIVED FROM THE REAL SOURCES one at a time.
 *
 *  V4-154: converted to TypeScript (bun). The two structures that had to survive are the BAN_DIRS set
 *  derived from PATHS' parents (so a repoint can never leave the ban behind) and the NEIGHBOURHOOD
 *  sweep, which is a NEGATIVE invariant and therefore cannot be fixed by naming the files that do the
 *  work — a sibling written later must be covered with no wall edit.
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

// The CARRIER files — the one file per key that must hold that key's REQUIRED call sites. These are
// positive tokens only; the FORBIDDEN_READER ban is NOT scoped to this map (see BAN_DIRS).
export const PATHS: Record<string, string> = {
  core: "core/src/main/kotlin/splice/core/util/JsonScalars.kt",
  // 2026-08-25: detekt Filename fix renamed the file after its single class — ResponsesHarvest
  // now lives in ResponsesHarvest.kt, and Harvested.kt holds the (alias-free) Harvested payload
  // type that used to sit in HarvestedText.kt. Repointed at the code, same single-file resolution.
  harvest: "gateway/dialect-openai-responses/src/main/kotlin/splice/dialect/responses/ResponsesHarvest.kt",
  // HD-24 (2026-08-17): UsageHud decomposed; firstNum (the delegating alias-chain call) moved to
  // UsageJson.kt (the usage-accounting owner).
  hud: "daemon/head/src/main/kotlin/splice/head/usage/UsageJson.kt",
  // HD-24 (2026-08-17): ChatStreamTranslator decomposed; both usage-alias reads moved to
  // ChatUsage.kt (the usage-accounting owner).
  chat: "gateway/dialect-openai-chat/src/main/kotlin/splice/dialect/chat/ChatUsage.kt",
  // HD-25 (2026-08-18): PassthroughStreamTranslator decomposed; both nested-cache_creation reads
  // moved to PassthroughUsage.kt (the usage-accounting owner), the same repoint HD-24 made twice
  // above. BAN_DIRS is derived from this path's PARENT, and the destination is a same-package
  // sibling, so the negative half sweeps exactly the same neighbourhood it did before.
  passthrough: "gateway/dialect-anthropic-passthrough/src/main/kotlin/splice/dialect/passthrough/PassthroughUsage.kt",
};

// The NEIGHBOURHOODS the forbidden-reader ban sweeps: the package directory of every carrier above,
// derived from PATHS rather than hand-listed so a future repoint can never leave the ban behind.
//
// REPAIR (2026-08-17, review of a08a438). The ban below reads "a private multi-key reader in ANY
// file that reads usage ... every file, no exemptions", and that was literally true while each key
// named a god file holding the WHOLE usage surface. The HD-24 decompositions silently turned "every
// file" into "five of ninety": UsageHud.kt became eleven siblings and the key was repointed at the
// 48-line UsageJson.kt, leaving UsageRing.kt and RateLimitHeaders.kt — which both read usage scalars
// via usageJson.num — outside the ban entirely. MEASURED before this repair: appending
// `internal fun num(x: JsonObject, vararg keys: String): Long = 0L` to RateLimitHeaders.kt printed
// WALL GREEN exit 0, while the SAME injection into the pre-split UsageHud.kt was RED. ChatUsage.kt
// had the same hole from 7cc7fa0. So a fourth alias reader with divergent numeric parsing could
// regrow anywhere in these packages and ship green — the precise regression this ban exists to
// prevent. cx_01's ed2ba76 carrier-chain remedy fixes the POSITIVE half of this class by naming
// every file that does the work; a NEGATIVE invariant cannot be fixed that way, because the file
// that would carry the violation does not exist yet. It is scoped to the neighbourhood instead, so
// a NEW sibling is covered the moment it is written, with no wall edit and nothing to repoint.
export const BAN_DIRS: string[] = [...new Set(Object.values(PATHS).map((rel) => rel.split("/").slice(0, -1).join("/")))].sort();

// Every token below matches a LITERAL SOURCE SUBSTRING, so a pure-style migration can break one
// while the invariant is fully intact — the class the W4-A wall's isNotEmpty/isNotBlank note
// records. The remedy is that wall's, applied per token: an entry may be a TUPLE of equivalent
// spellings, satisfied by any one of them (see `alts`). This is not a relaxation — every required
// call site still has to be matched by something in the file, each spelling still names a whole
// call site rather than a bare identifier, and deleting the call site removes every spelling at once.
export const REQUIRED: Record<string, [string | string[], string][]> = {
  core: [
    [
      [
        "public fun firstLong(obj: JsonObject?, vararg keys: String)",
        "public fun JsonObject.firstLong(vararg keys: String)",
      ],
      "the shared alias chain does not exist, so every translator still hand-rolls its own",
    ],
  ],
  chat: [
    [
      [
        'JsonScalars.firstLong(u, "prompt_tokens", "input_tokens")',
        'u.firstLong("prompt_tokens", "input_tokens")',
      ],
      "the input bucket has no alias chain — a backend emitting input_tokens lands at zero usage",
    ],
    [
      [
        'JsonScalars.firstLong(u, "completion_tokens", "output_tokens")',
        'u.firstLong("completion_tokens", "output_tokens")',
      ],
      "the output bucket has no alias chain — a backend emitting output_tokens lands at zero",
    ],
  ],
  harvest: [
    [
      [
        'JsonScalars.firstLong(usage, "input_tokens", "prompt_tokens")',
        'usage.firstLong("input_tokens", "prompt_tokens")',
      ],
      "the Responses harvest still hand-rolls its own alias reader — the very 'fourth reader' " +
        "the item forbade, and with the OPPOSITE precedence to the chat chain",
    ],
  ],
  hud: [
    [
      ["JsonScalars.firstLong(obj, *keys)", "JsonScalars.firstLong(this, *keys)", "firstLong(*keys)"],
      "the HUD payload builder keeps a SECOND shared chain with different numeric parsing " +
        "(toDouble vs toLong), so the same bytes yield different usage depending on the reader",
    ],
  ],
  passthrough: [
    [
      'u["cache_creation"] as? JsonObject',
      "the nested per-TTL cache_creation object is never read, understating inputTokens and " +
        "therefore the context-window percentage on every cache-writing turn",
    ],
    [
      "parts.sum()",
      "the nested TTL buckets are not SUMMED — reading one of them drops the other, the same " +
        "undercount this item exists to fix",
    ],
  ],
};

// Shapes that must NOT survive: a private multi-key reader in ANY file that reads usage. A literal
// ban was defeated by renaming one parameter (review 2026-08-10: `obj` -> `o` walked straight past
// it), and it was only ever applied to the chat file while Harvested.kt carried the byte-identical
// signature untouched. Regex, every file in every BAN_DIRS neighbourhood, no exemptions.
const FORBIDDEN_READER = /fun\s+num\s*\([^)]*vararg\s+keys/;
const FORBIDDEN_WHY =
  "keeps a private multi-key alias reader alongside the shared chain — a fourth " +
  "reader is exactly what the item forbade, and they drift apart on numeric parsing";

// --- CODE, NOT MENTIONS -------------------------------------------------------------------------
// Adversarial review (2026-08-10) proved every wall in this campaign that matched raw file text was
// satisfiable by a COMMENT or an IMPORT naming the token. Concretely: the CX-02 wall graded a tree
// GREEN where the Responses call body had been replaced by `return system.orEmpty()`, because the
// KDoc above it still said "withCompactDirective"; and the CX-11 wall graded GREEN with its required
// expression moved into a `// TODO(next):` comment and the pre-fix branch restored. Both are exactly
// the regression these walls exist to catch. Tokens are therefore matched against code with comments
// and imports removed — a mention is not a wiring.
const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

/** Equivalent spellings of ONE call site. A bare string is its own only spelling. */
export function alts(entry: string | string[]): string[] {
  return typeof entry === "string" ? [entry] : entry;
}

/** Pure detection. No I/O — the selftest feeds it derived sources directly.
 *
 *  `sources` carries the five CARRIER keys plus one key per neighbourhood file (keyed by its
 *  repo-relative path). Required tokens are checked on carriers; the forbidden reader is checked on
 *  EVERY key, so a sibling written after this wall was last touched is banned too. */
export function detect(sources: Record<string, string | null>): string[] {
  const problems: string[] = [];
  for (const key of Object.keys(PATHS)) {
    const text = sources[key] ?? null;
    if (text === null) {
      problems.push(`${key} source missing — refusing to pass vacuously`);
      continue;
    }
    for (const [entry, why] of REQUIRED[key] ?? []) {
      const a = alts(entry);
      if (!a.some((token) => text.includes(token))) {
        problems.push(`${key}: ${why} (missing \`${a.join(" | ")}\`)`);
      }
    }
  }
  for (const key of Object.keys(sources)) {
    const text = sources[key];
    if (text === null) {
      if (!(key in PATHS)) {
        problems.push(`${key} missing — refusing to sweep a neighbourhood vacuously`);
      }
      continue;
    }
    const found = FORBIDDEN_READER.exec(text);
    if (found !== null) {
      problems.push(`${key}: ${FORBIDDEN_WHY} (found \`${found[0]}\`)`);
    }
  }
  return problems;
}

/** Every .kt in every BAN_DIRS package that is not already a carrier. A missing package reads as
 *  a null key, so deleting or moving a whole package is RED rather than a silently empty sweep. */
export function neighbours(): Record<string, string | null> {
  const carriers = new Set(Object.values(PATHS));
  const out: Record<string, string | null> = {};
  for (const rel of BAN_DIRS) {
    const d = resolve(ROOT, rel);
    if (!existsSync(d) || !statSync(d).isDirectory()) {
      out[`${rel}/*.kt`] = null;
      continue;
    }
    const files = readdirSync(d).filter((f) => f.endsWith(".kt")).sort();
    for (const f of files) {
      const key = `${rel}/${f}`;
      if (!carriers.has(key)) {
        out[key] = codeOnly(readFileSync(resolve(d, f), "utf8"));
      }
    }
  }
  return out;
}

export function load(): Record<string, string | null> {
  const out: Record<string, string | null> = {};
  for (const key of Object.keys(PATHS)) {
    const p = resolve(ROOT, PATHS[key]);
    out[key] = existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
  }
  Object.assign(out, neighbours());
  return out;
}

// The pre-fix shape — literally true of all three files at HEAD d0da545.
export const PREFIX_SHAPE: Record<string, string> = {
  harvest: "fun num(obj: JsonObject, vararg keys: String): Long = 0L",
  hud: "private fun JsonObject.firstNum(vararg k: String): Long? = null",
  core: "public fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()",
  chat: '(u["prompt_tokens"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { inputTokens = it }',
  passthrough: 'num(u, "cache_creation_input_tokens")?.let { cacheCreation = it }',
};

function selftest(): number {
  const fails: string[] = [];
  const live = load();

  if (detect(live).length > 0) {
    fails.push(`the real sources must be GREEN before half-fixes can be derived: ${pyRepr(detect(live))}`);
  } else {
    for (const key of Object.keys(REQUIRED)) {
      for (const [entry] of REQUIRED[key]) {
        // ANY-OF entries hold equivalent spellings, so only the spelling actually PRESENT can
        // be deleted to derive the half-fix — and every present spelling must go, or the
        // remaining one keeps the wall green and proves nothing.
        const a = alts(entry);
        let text = live[key] ?? "";
        const present = a.filter((token) => text.includes(token));
        if (present.length === 0) {
          fails.push(`cannot derive a ${key} half-fix: none of ${pyRepr(a)} is in the real source`);
          continue;
        }
        for (const token of present) {
          text = text.replaceAll(token, "");
        }
        const problems = detect({ ...live, [key]: text });
        if (!problems.some((p) => p.startsWith(`${key}:`) && a.some((t) => p.includes(t)))) {
          fails.push(
            `deleting \`${present.join(" | ")}\` from ${key} must be RED for its own reason, got ${pyRepr(problems)}`,
          );
        }
      }
    }

    // The forbidden-shape control: re-introducing the private reader must go RED even though
    // every required token is still present. This is the half a behavior-only wall misses.
    const regrowCases: [string, string][] = [
      ["chat", "fun num(obj: JsonObject, vararg keys: String): Long = 0L"],
      ["chat", "fun num(o: JsonObject, vararg keys: String): Long = 0L"],
      ["harvest", "fun num(x: JsonObject, vararg keys: String): Long = 0L"],
    ];
    for (const [victim, spelling] of regrowCases) {
      const regrown = { ...live, [victim]: (live[victim] ?? "") + "\n    " + spelling + "\n" };
      if (!detect(regrown).some((p) => p.includes("private multi-key alias reader"))) {
        fails.push(`a re-introduced private reader in ${victim} (${spelling}) must be RED`);
      }
    }

    // The NEIGHBOURHOOD control (repair, 2026-08-17): the same regrowth in a SIBLING must be RED
    // too, or a decomposition quietly moves the surface out from under the ban. Every non-carrier
    // file in every banned package, one at a time — this is the instrument that caught a08a438.
    const neigh = Object.keys(live).filter((k) => !(k in PATHS));
    if (neigh.length === 0) {
      fails.push("no neighbourhood files were swept — the ban would be scoped to carriers again");
    }
    for (const key of neigh) {
      const regrown = {
        ...live,
        [key]: (live[key] ?? "") + "\n    " + "internal fun num(x: JsonObject, vararg keys: String): Long = 0L\n",
      };
      if (
        !detect(regrown).some((p) => p.startsWith(`${key}:`) && p.includes("private multi-key alias reader"))
      ) {
        fails.push(`a private reader regrown in the sibling ${key} must be RED`);
      }
    }

    // And a whole package going missing must be RED, never an empty sweep reading as clean.
    for (const rel of BAN_DIRS) {
      const gone: Record<string, string | null> = {};
      for (const k of Object.keys(live)) {
        if (!k.startsWith(`${rel}/`)) gone[k] = live[k];
      }
      gone[`${rel}/*.kt`] = null;
      if (!detect(gone).some((p) => p.includes("refusing to sweep a neighbourhood vacuously"))) {
        fails.push(`a missing ${rel} package must be RED, never a vacuous sweep`);
      }
    }
  }

  if (detect({ ...PREFIX_SHAPE }).length === 0) {
    fails.push("the pre-fix shape must be RED");
  }

  for (const key of Object.keys(PATHS)) {
    const partial = { ...live, [key]: PREFIX_SHAPE[key] };
    if (detect(partial).length === 0) {
      fails.push(`a gap left open in ${key} alone must be RED`);
    }
    const missing = { ...live, [key]: null };
    if (detect(missing).length === 0) {
      fails.push(`a missing ${key} file must be RED, never a vacuous pass`);
    }
  }

  if (fails.length > 0) {
    process.stdout.write("CX-18 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "CX-18 SELFTEST OK — red on the pre-fix shape, on any single source left open, on a " +
      "missing file, on a missing banned package, on a re-introduced private alias reader in a " +
      "carrier AND in every swept sibling, and — derived from the REAL sources, one token at a " +
      "time — on a tree missing any one half of the shared chain.\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(load());
  if (problems.length > 0) {
    process.stdout.write("CX-18 WALL RED — usage parsing can still land a real backend at zero or partial:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "CX-18 WALL GREEN: one shared alias chain, the real-world alias shapes are in it, and no " +
      "translator keeps a private reader.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
