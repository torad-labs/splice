#!/usr/bin/env bun
/** WALL for JW-01 — a daemon that dies at boot must leave its stack trace somewhere tailable.
 *
 *  GAP (RED at authoring, 2026-08-07): both cold-start paths launch the JVM with output discarded
 *  (>/dev/null 2>&1), and Main.runDaemon parses the topology BEFORE the log sink exists — broken
 *  TOML, an unwritable state dir, bad SPLICE_JVM_OPTS, a stolen port all die invisibly; the operator
 *  sees only "daemon failed version handshake (got <none>)".
 *
 *  GREEN requires ALL of:
 *    1. Main.kt installs a boot-failure net (bootFailureHandler) BEFORE TopologyLoader.loadOrMaterialize;
 *    2. bin/splice-launch redirects the spawned JVM to daemon-boot.log (with a /dev/null fallback
 *       for an unwritable logs dir) and prints the boot-log tail on handshake failure;
 *    3. DaemonLaunch.spawnDaemon does the same redirect, and ensureDaemon prints the tail when the
 *       daemon never comes up. The cold-start cluster left AdminSupport.kt (concentration HIGH,
 *       2026-08-19); this wall follows the body, not the facade.
 *
 *  EXIT 0 = boot failures visible. EXIT 1 = gap open. --selftest = the POSITIVE CONTROL (C6).
 *
 *  V4-154: converted to TypeScript (bun). Two strippers, not one — the Kotlin readers and a SHELL
 *  stripper for bin/splice-launch, whose comment marker is `#`. Running the Kotlin stripper over
 *  the shim would miss every `# TODO:` AND eat the `//` in `http://127.0.0.1`, so the shell marker
 *  is its own regex with a lookbehind for start-of-line-or-whitespace.
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
const MAIN = resolve(ROOT, "app/src/main/kotlin/splice/app/Main.kt");
const SHIM = resolve(ROOT, "bin/splice-launch");
const ADMIN = resolve(ROOT, "app/src/main/kotlin/splice/app/cli/daemon/DaemonLaunch.kt");

/** Pure detection. No I/O — the selftest feeds it directly. */
export function detect(main: string | null, shim: string | null, admin: string | null): string[] {
  for (const [name, text] of [
    ["Main.kt", main],
    ["bin/splice-launch", shim],
    ["DaemonLaunch.kt", admin],
  ] as [string, string | null][]) {
    if (text === null) {
      return [`${name} missing — refusing to pass vacuously`];
    }
  }
  const problems: string[] = [];
  const handler = (main ?? "").indexOf("bootFailureHandler");
  const topo = (main ?? "").indexOf("TopologyLoader.loadOrMaterialize");
  if (handler < 0) {
    problems.push(
      "Main.kt has no bootFailureHandler — a pre-logger boot throwable " +
        "(broken TOML, unwritable state dir) leaves no trace anywhere",
    );
  } else if (topo >= 0 && topo < handler && !(main ?? "").slice(0, topo).includes("Thread.setDefaultUncaughtExceptionHandler")) {
    problems.push(
      "the boot-failure net is installed AFTER the topology parse — exactly the " +
        "throw it exists to catch happens before it",
    );
  }
  if ((shim ?? "").includes("daemon >/dev/null 2>&1") && !(shim ?? "").includes("daemon-boot.log")) {
    problems.push(
      "bin/splice-launch still discards the spawned JVM's output — a boot stack " +
        "trace dies in /dev/null",
    );
  } else if (!(shim ?? "").includes("daemon-boot.log")) {
    problems.push("bin/splice-launch never mentions daemon-boot.log — no tailable boot lane");
  }
  if (!(shim ?? "").includes("tail")) {
    problems.push(
      "splice-launch does not print the boot-log tail on handshake failure — " +
        "the operator still has to know to hand-run the jar",
    );
  }
  if (!(admin ?? "").includes("daemon-boot.log")) {
    problems.push("DaemonLaunch.spawnDaemon still discards the spawned JVM's output");
  }
  return problems;
}

const BLOCK_COMMENT = /\/\*[\s\S]*?\*\//g;
const LINE_COMMENT = /\/\/.*?$/gm;
const IMPORT_LINE = /^import .*$/gm;
// bin/splice-launch is SHELL, not Kotlin: its comment marker is `#`. Running the Kotlin stripper
// over it would miss every `# TODO:` (leaving the hole open on the very reader that carries two of
// this wall's four required tokens) AND eat the `//` in `http://127.0.0.1`. Same law, own marker.
const SHELL_COMMENT = /(?:(?<=\s)|^)#.*?$/gm;

/** A mention is not a wiring: a token left behind in a `// TODO: restore ...` must not satisfy
 *  a REQUIRED token after the real call site is deleted. Same stripper cx_02/cx_09/cx_18 carry.
 *
 *  Applied to EVERY reader here because every check in this wall is a REQUIRED token — the
 *  `daemon >/dev/null 2>&1` test only picks which message to print (both of its branches demand
 *  daemon-boot.log), so nothing in this wall is a BAN, where stripping would instead let a
 *  violation hide inside a comment (the jw_08 split). */
export function codeOnly(text: string | null): string | null {
  if (text === null) return null;
  let stripped = text.replace(BLOCK_COMMENT, "");
  stripped = stripped.replace(LINE_COMMENT, "");
  return stripped.replace(IMPORT_LINE, "");
}

/** code_only for the shim — the same law spoken in the shell's comment marker. */
export function shellCodeOnly(text: string | null): string | null {
  if (text === null) return null;
  return text.replace(SHELL_COMMENT, "");
}

function read(p: string): string | null {
  return existsSync(p) ? codeOnly(readFileSync(p, "utf8")) : null;
}

function readShell(p: string): string | null {
  return existsSync(p) ? shellCodeOnly(readFileSync(p, "utf8")) : null;
}

export const MAIN_OPEN =
  "val topology = TopologyLoader.loadOrMaterialize(topologyPath)\nval log = persistentLogger";
export const MAIN_OK =
  "Thread.setDefaultUncaughtExceptionHandler(bootFailureHandler(statePaths))\n" +
  "val topology = TopologyLoader.loadOrMaterialize(topologyPath)";
export const SHIM_OPEN = 'nohup java $SPLICE_JVM_OPTS -jar "$JAR" daemon >/dev/null 2>&1 &';
export const SHIM_OK =
  'nohup java $SPLICE_JVM_OPTS -jar "$JAR" daemon >>"$BOOT_LOG" 2>&1 &\n' +
  'tail -n 15 "$BOOT_LOG" >&2\ndaemon-boot.log';
export const ADMIN_OPEN = "daemon >/dev/null 2>&1 &";
export const ADMIN_OK = 'daemon >>\\"$B\\" 2>&1 & daemon-boot.log';

function selftest(): number {
  const fails: string[] = [];
  if (detect(MAIN_OPEN, SHIM_OPEN, ADMIN_OPEN).length === 0) {
    fails.push("today's discard-everything shape must be RED");
  }
  if (detect(MAIN_OK, SHIM_OK, ADMIN_OK).length > 0) {
    fails.push(`netted+redirected shape must be GREEN, got ${pyRepr(detect(MAIN_OK, SHIM_OK, ADMIN_OK))}`);
  }
  if (detect(MAIN_OPEN, SHIM_OK, ADMIN_OK).length === 0) {
    fails.push("a missing Main.kt boot net must be RED");
  }
  if (
    detect(
      "val topology = TopologyLoader.loadOrMaterialize(p)\nfun bootFailureHandler() {}",
      SHIM_OK,
      ADMIN_OK,
    ).length === 0
  ) {
    fails.push("a net installed AFTER the topology parse must be RED");
  }
  if (detect(MAIN_OK, SHIM_OPEN, ADMIN_OK).length === 0) {
    fails.push("a still-discarding shim must be RED");
  }
  if (detect(MAIN_OK, SHIM_OK, ADMIN_OPEN).length === 0) {
    fails.push("a still-discarding DaemonLaunch must be RED");
  }
  if (detect(null, SHIM_OK, ADMIN_OK).length === 0) {
    fails.push("missing files must be RED, never a vacuous pass");
  }
  if (fails.length > 0) {
    process.stdout.write("JW-01 SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "JW-01 SELFTEST OK — red on discard-everything, late net, discarding shim/DaemonLaunch, " +
      "and missing files; green only when every boot lane is tailable\n",
  );
  return 0;
}

function main(): number {
  if (process.argv.includes("--selftest")) return selftest();
  const problems = detect(read(MAIN), readShell(SHIM), read(ADMIN));
  if (problems.length > 0) {
    process.stdout.write("JW-01 WALL RED — a boot-dead daemon leaves no trace:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write(
    "JW-01 WALL GREEN: boot failures land in daemon.log/daemon-boot.log and the shim shows the tail.\n",
  );
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
