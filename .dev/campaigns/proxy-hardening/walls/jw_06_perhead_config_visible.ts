#!/usr/bin/env bun
/** WALL for JW-06 — the per-head config override layer must be VISIBLE in /api/config.
 *
 *  GAP (RED at authoring, 2026-08-07): [heads.<key>.overrides] is a real, tested precedence layer
 *  inside ConfigService, but ConfigLayers has no per-head field and configJson always emits the
 *  global view — "why is kimi's maxInflight 8 when the panel says 100" is unanswerable from the
 *  dashboard's own provenance feature, precisely for the heads that were tuned.
 *
 *  GREEN requires ALL of:
 *    1. ConfigLayers carries the perHead map;
 *    2. /api/config accepts a head parameter (effective becomes getConfig(headKey)) and emits the
 *       perHead layer in its real precedence position;
 *    3. the webui config surface can select a head (the selector reaches the fetch).
 *
 *  EXIT 0 = layer visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). The multi-file group (ControlServer.kt + ConfigRoutes.kt)
 *  is concatenated and ALL-OF, with either file missing a vacuity RED — and its label names BOTH
 *  files, because labelling it with one name sent the debugger to a file that was still there.
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
// HD-25 (2026-08-18): ConfigLayers moved out of ConfigService.kt into ConfigResults.kt when the
// config god object decomposed — same package, same `perHead` field, new file. RE-ANCHORED to the
// exact file that now holds the declaration, NOT widened to the package: a directory-wide search
// would pass on the field living anywhere, which is precisely the resolution loss this campaign
// exists to prevent. If ConfigLayers moves again, move this path with it.
const LAYERS = resolve(ROOT, "core/src/main/kotlin/splice/core/config/ConfigResults.kt");
// HD-24: configJson (the "perHead" emitter) and the /api/config route (the head query-param read)
// split across two files when ControlServer decomposed — the head param stayed in ControlServer's
// route table, "perHead" moved with configJson into ConfigRoutes. Concatenated like the campaign's
// other multi-file wall keys: ALL-OF still applies, and either file missing is a vacuity RED.
const CTRL_FILES = [
  resolve(ROOT, "daemon/control/src/main/kotlin/splice/control/ControlServer.kt"),
  resolve(ROOT, "daemon/control/src/main/kotlin/splice/control/api/fleet/ConfigRoutes.kt"),
];
const WEBUI = resolve(ROOT, "console/src/entities/config/api/index.ts");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(layers: string | null, ctrl: string | null, webui: string | null): string[] {
  // The middle group is CTRL_FILES (two files, concatenated), so it is labelled with BOTH names:
  // under the single-member label, deleting or renaming ConfigRoutes.kt alone — exactly the
  // decomposition this campaign's file-list mechanism exists to survive without a wall edit — went
  // correctly RED while printing that ControlServer.kt was missing, sending the debugger to a file
  // that is still there. cx_01/cx_09/cx_18 label their multi-file groups logically for the same
  // reason; this was the one place it slipped (review 2026-08-28, PR 99).
  const groups: [string, string | null][] = [
    ["ConfigResults.kt", layers],
    ["ControlServer.kt + ConfigRoutes.kt", ctrl],
    ["config entity api", webui],
  ];
  for (const [name, text] of groups) {
    if (text === null) {
      return [`${name} missing — refusing to pass vacuously`];
    }
  }
  const problems: string[] = [];
  if (!(layers ?? "").includes("val perHead:")) {
    problems.push(
      "ConfigLayers has no perHead field — the layer exists in mergedRaw but " +
        "the transparency surface cannot show it",
    );
  }
  if (
    !(ctrl ?? "").includes('queryParameters["head"]') &&
    !(ctrl ?? "").includes('parameters["head"]')
  ) {
    problems.push(
      "/api/config takes no head parameter — the effective view is global-only " +
        "for exactly the heads that were tuned",
    );
  }
  if (!(ctrl ?? "").includes('"perHead"')) {
    problems.push("configJson never emits the perHead layer");
  }
  if (!(webui ?? "").includes("head?") && !(webui ?? "").includes("head:")) {
    problems.push("the webui config fetch cannot select a head");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  this wall after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 already carry. */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

/** Concatenate every file's text, in order — null (vacuity RED) if any is missing. */
function readAll(paths: string[]): string | null {
  const texts: string[] = [];
  for (const p of paths) {
    const text = read(p);
    if (text === null) return null;
    texts.push(text);
  }
  return texts.join("\n");
}

export const LAYERS_OK = "data class ConfigLayers(val perHead: Map<String, Map<String, Any?>>)";
export const CTRL_OK = 'call.request.queryParameters["head"]\nputJsonObject("perHead") {}';
export const WEBUI_OK = "config(head?: string)";

function selftest(): number {
  const fails: string[] = [];
  if (detect("layers no field", "configJson global only", "fetch fixed").length === 0) {
    fails.push("today's head-blind shape must be RED");
  }
  if (detect(LAYERS_OK, CTRL_OK, WEBUI_OK).length > 0) {
    fails.push(`visible-layer shape must be GREEN, got ${pyRepr(detect(LAYERS_OK, CTRL_OK, WEBUI_OK))}`);
  }
  if (detect("layers no field", CTRL_OK, WEBUI_OK).length === 0) {
    fails.push("a ConfigLayers without perHead must be RED");
  }
  if (detect(LAYERS_OK, "configJson global only", WEBUI_OK).length === 0) {
    fails.push("a head-less /api/config must be RED");
  }
  if (detect(LAYERS_OK, CTRL_OK, "fetch fixed").length === 0) {
    fails.push("a webui that cannot select a head must be RED");
  }
  if (detect(null, CTRL_OK, WEBUI_OK).length === 0) {
    fails.push("missing files must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("JW-06 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "JW-06 SELFTEST OK — red on hidden layer, head-less endpoint, fixed fetch, and missing " +
      "files; green only when the per-head layer is visible end to end\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(LAYERS), readAll(CTRL_FILES), read(WEBUI));
  if (problems.length > 0) {
    process.stdout.write("JW-06 WALL RED — the per-head override layer is invisible:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "JW-06 WALL GREEN: the per-head layer rides ConfigLayers, /api/config?head, and the webui fetch.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
