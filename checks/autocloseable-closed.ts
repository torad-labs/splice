#!/usr/bin/env bun
/**
 * V4-95 — every main-source class that declares itself AutoCloseable is CLOSED from a main source.
 *
 * THE LAW. Implementing AutoCloseable is a promise that the type owns something the JVM will not
 * reclaim on its own: a process, a file lock, a channel, a thread. The promise is only kept if some
 * PRODUCTION path actually calls `close()`. A closeable whose only `close()` callers live in
 * `src/test` has the shape of a managed resource and the behaviour of a leak — and the test suite is
 * green, because the tests are precisely the code that closes it.
 *
 * THE SCAR (ARCH-AUDIT 2026-09-17, audit D row 6). `JvmCodeModeRuntime` implements `CodeModeRuntime`,
 * which extends `AutoCloseable`, and its `close()` shuts down the worker pool: it destroys child JVMs
 * and releases their permits. It is constructed in production at
 * app/src/main/kotlin/splice/app/provider/CodexResponsesArm.kt:101 (`runtime =
 * JvmCodeModeRuntime()`), and nothing in any main source ever closes it. The only `.close()`/`.use {}`
 * callers are app/src/test/kotlin/splice/app/codemode/CodeModeRuntimeTest.kt:238 and friends — which is why every
 * test that exercises worker reclamation passes while the daemon never reclaims anything.
 *
 * THE DENOMINATOR, FROM THE SOURCE, AND TRANSITIVELY (§24). The types are not a list in this file.
 * Every `class`/`interface`/`object` declaration under gateway/{module}/src/main is parsed off disk
 * with its supertype list, and the closeable set is the TRANSITIVE closure of {AutoCloseable,
 * Closeable} over that graph. Transitivity is the whole point: `JvmCodeModeRuntime` does not say
 * `AutoCloseable` anywhere — it says `CodeModeRuntime`, which says `AutoCloseable` in a different
 * module. A direct-mention denominator would have reported the audit's own finding as absent, which
 * is the tautology this campaign keeps finding (a check whose denominator comes from the same list it
 * checks cannot fail for anything outside that list).
 *
 * DISPOSITION. Every concrete closeable gets exactly one, and absence is not one:
 *   closed      — main-source evidence that it is closed (see EVIDENCE below);
 *   allowlisted — a DATED entry in ALLOWLIST with a written reason;
 *   RED         — reported BY NAME with its declaration site.
 * Interfaces are excluded by design: `CodeModeRuntime : AutoCloseable` declares the contract, it does
 * not own a resource. An interface that is never implemented is a different defect and not this one's.
 *
 * EVIDENCE that a type is closed, in main sources only. Three forms, all of which the tree already
 * uses, so the compliant form needs no invention:
 *   1. construct-and-use   `WorkerSession(start).use { … }`      (CodeModeWorker.kt:62)
 *   2. a handle name       `val lock = DaemonLock(path)` … `lock.close()`   (Main.kt:77, :185)
 *                          `var channel: WorkerChannel? = null` … `channel?.close()`
 *                          (JvmCodeModeRuntime.kt:48, :73)
 *   3. a method reference  `Cancellables.runCatchingCleanup(lease::close)`
 *                          (OAuthAccountFiles.kt:71)
 * A "handle name" is any identifier this parser saw BOUND to the type — `val`/`var` with that
 * declared type, a constructor or function parameter of that type, or a `val x = Type(…)`
 * initialisation. Closing evidence is then `x.close()`, `x?.close()`, `x::close`, `x.use` or
 * `x?.use` anywhere in a main source.
 *
 * NOT CAUGHT, and stated rather than discovered later:
 *   - Whether close is reached on every PATH. This proves a production caller exists, not that it
 *     runs in a finally. `use { }` is the form that also gives you that, which is why it is listed
 *     first.
 *   - A handle passed into a helper that closes it through its own parameter name. The helper's
 *     parameter IS a binding of that type, so the name is collected and the evidence found there —
 *     but only if the parameter is spelled with the concrete type. A helper taking the INTERFACE
 *     (`fun shut(r: CodeModeRuntime) = r.close()`) is now handled by `interface_closed()` (2026-09-17,
 *     V4-107): closing a handle typed by a closeable INTERFACE is taken as closing its (single)
 *     concrete implementer, so `config.runtime.close()` in CodexCodeModeBridge.onHeadStop closes
 *     JvmCodeModeRuntime without the checker needing a concrete-typed handle. Taught rather than
 *     allowlisted, per this header's own promise.
 *   - Anonymous closeables (`return AutoCloseable { timer.cancel() }`, Spinner.kt:26). They have no
 *     declaration to enumerate and no name to track. Spinner closes its own through
 *     `pulses?.close()`.
 *
 * SELFTEST. `--selftest` builds temp trees and proves BOTH directions: GREEN on a tree where each
 * evidence form appears, RED BY NAME on a synthetic closeable that nothing closes, RED on one closed
 * only from src/test (the scar, reconstructed), RED through a TWO-HOP interface chain (the property
 * that made the real finding visible), GREEN for a dated allowlist entry and RED for an undated or
 * reasonless one, and a refusal to pass vacuously when the parse yields no closeables at all.
 *
 * Usage:
 *     bun checks/autocloseable-closed.ts [check]
 *     bun checks/autocloseable-closed.ts --selftest
 *
 * A BARE RUN IS `check` — the one gating mode, so there is no non-gating default to mis-invoke.
 * `check` is accepted (and ignored) so the gate leg reads like its siblings in tools/gate/config/ladder.json;
 * anything else is a typo and fails rather than being silently dropped.
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// `__file__`'s parent.parent, i.e. the repo root one level above checks/.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

// restructure PR 3: :client is the first module to live outside gateway/, so the production
// universe is no longer one `gateway/*` pattern. A source root this checker stops walking is a
// denominator that shrinks in silence, which is the one failure every ratchet here exists to
// prevent — so the list names every §2.2 module home, the ones that exist and the ones the next
// module commits create (a glob over an absent directory matches nothing, so the denominator can
// only grow), until PR 5 hands these checkers the build-derived source units of tools/gate.
const MODULE_HOMES = [
  "gateway/*", "client", "core", "upstream", "dialects/*", "providers/*", "daemon/*", "app", "quality/*",
];
const MAIN_GLOBS = MODULE_HOMES.map((home) => `${home}/src/main/**/*.kt`);
const TEST_GLOBS = MODULE_HOMES.flatMap((home) => [`${home}/src/test/**/*.kt`, `${home}/src/testFixtures/**/*.kt`]);

const SEEDS = new Set(["AutoCloseable", "Closeable", "java.lang.AutoCloseable", "java.io.Closeable"]);

// Concrete closeables that may go unclosed in main. Format: name -> "YYYY-MM-DD: <reason>".
// EMPTY today, and that is a finding rather than an oversight: all six concrete closeables in the
// tree either carry main-source closing evidence or are the audit's RED. An undated or reasonless
// entry is itself a failure (see audit()), because an exemption with no written reason is an absence
// wearing a label.
let ALLOWLIST: Record<string, string> = {};

const DECL =
  /^[ \t]*(?:(?:public|internal|private|protected|abstract|open|final|sealed|data|value|inner|fun|enum|annotation|expect|actual|companion)\s+)*(class|interface|object)\s+([A-Za-z_]\w*)/gm;
// The independent second reading used as the parser-drift guard: a supertype list mentioning a seed.
const DIRECT_MENTION = /[:,]\s*(?:java\.lang\.|java\.io\.)?(?:AutoCloseable|Closeable)\b/g;

/** Blank out comment bodies, string literals and char literals, preserving offsets and newlines.
 *  Offsets matter: every declaration site is reported as a line number. */
function stripComments(source: string): string {
  const out: string[] = [];
  let i = 0;
  const n = source.length;
  while (i < n) {
    const two = source.slice(i, i + 2);
    if (two === "//") {
      while (i < n && source[i] !== "\n") {
        out.push(" ");
        i += 1;
      }
      continue;
    }
    if (two === "/*") {
      while (i < n && source.slice(i, i + 2) !== "*/") {
        out.push(source[i] === "\n" ? "\n" : " ");
        i += 1;
      }
      out.push("  ");
      i += 2;
      continue;
    }
    if (source.slice(i, i + 3) === '"""') {
      out.push("   ");
      i += 3;
      while (i < n && source.slice(i, i + 3) !== '"""') {
        out.push(source[i] === "\n" ? "\n" : " ");
        i += 1;
      }
      out.push("   ");
      i += 3;
      continue;
    }
    if (source[i] === '"') {
      out.push(" ");
      i += 1;
      while (i < n && source[i] !== '"') {
        if (source[i] === "\\") {
          out.push(" ");
          i += 1;
          if (i < n) {
            out.push(" ");
            i += 1;
          }
          continue;
        }
        out.push(source[i] === "\n" ? "\n" : " ");
        i += 1;
      }
      out.push(" ");
      i += 1;
      continue;
    }
    out.push(source[i]);
    i += 1;
  }
  return out.join("");
}

/** The supertype names of a declaration, given the text after its name. Walks to the ':' that
 *  opens the supertype list at nesting depth 0 — so a constructor parameter typed `Foo : Bar` (an
 *  impossibility) or a generic bound in <> cannot be mistaken for one — then splits on depth-0
 *  commas until the class body opens. */
function supertypes(tail: string): string[] {
  let depth = 0;
  let colon = -1;
  for (let i = 0; i < tail.length; i += 1) {
    const c = tail[i];
    if ("(<[".includes(c)) depth += 1;
    else if (")>]".includes(c)) depth -= 1;
    else if (depth === 0 && c === "{") break;
    else if (depth === 0 && c === ":") {
      colon = i;
      break;
    } else if (
      depth === 0 &&
      c === "\n" &&
      countOf(tail.slice(0, i), "(") === countOf(tail.slice(0, i), ")") &&
      i > 0
    ) {
      // a declaration with no supertype list and no body on this line still ends at the body
      continue;
    }
  }
  if (colon < 0) return [];
  const names: string[] = [];
  let buf = "";
  depth = 0;
  for (const c of tail.slice(colon + 1)) {
    if ("(<[".includes(c)) depth += 1;
    else if (")>]".includes(c)) depth -= 1;
    if (depth === 0 && c === "{") break;
    if (depth === 0 && c === ",") {
      names.push(buf);
      buf = "";
    } else {
      buf += c;
    }
  }
  names.push(buf);
  const out: string[] = [];
  for (const raw of names) {
    const m = /^\s*([A-Za-z_][\w.]*)/.exec(raw);
    if (m !== null) out.push(m[1]);
  }
  return out;
}

function countOf(text: string, needle: string): number {
  let count = 0;
  let at = text.indexOf(needle);
  while (at >= 0) {
    count += 1;
    at = text.indexOf(needle, at + needle.length);
  }
  return count;
}

class Decl {
  readonly kind: string;
  readonly name: string;
  readonly supers: string[];
  readonly path: string;
  readonly line: number;

  constructor(kind: string, name: string, supers: string[], path: string, line: number) {
    this.kind = kind;
    this.name = name;
    this.supers = supers;
    this.path = path;
    this.line = line;
  }
}

function declarations(sources: Map<string, string>): Decl[] {
  const out: Decl[] = [];
  for (const path of [...sources.keys()].sort()) {
    const src = sources.get(path) as string;
    for (const m of src.matchAll(DECL)) {
      const at = m.index as number;
      out.push(
        new Decl(
          m[1],
          m[2],
          supertypes(src.slice(at + m[0].length, at + m[0].length + 2000)),
          path,
          src.slice(0, at).split("\n").length,
        ),
      );
    }
  }
  return out;
}

/** Transitive closure of the seeds over the declaration graph, by SIMPLE name. Simple-name
 *  resolution is an over-approximation — two unrelated `Lease` classes in different modules would
 *  both join if either did — and it is the safe direction: it can only add types to the audit, and
 *  a false member fails loudly (by name, with its declaration site) instead of silently. */
function closeableClosure(decls: Decl[]): Set<string> {
  const closeable = new Set<string>();
  let changed = true;
  while (changed) {
    changed = false;
    for (const d of decls) {
      if (closeable.has(d.name)) continue;
      if (d.supers.some((s) => SEEDS.has(s) || closeable.has(s.split(".").pop() as string))) {
        closeable.add(d.name);
        changed = true;
      }
    }
  }
  return closeable;
}

/** Identifiers bound to type_name: declared-type properties and parameters, and `val x = T(…)`. */
function handleNames(sources: Map<string, string>, typeName: string): Set<string> {
  const names = new Set<string>();
  const typed = new RegExp(
    `\\b(?:val|var)?\\s*([A-Za-z_]\\w*)\\s*:\\s*(?:[A-Za-z_][\\w.]*\\.)?${escapeRe(typeName)}\\s*\\??`,
    "g",
  );
  const initialised = new RegExp(
    `\\b(?:val|var)\\s+([A-Za-z_]\\w*)\\s*(?::[^=\\n]*)?=\\s*(?:[A-Za-z_][\\w.]*\\.)?${escapeRe(typeName)}\\s*\\(`,
    "g",
  );
  for (const src of sources.values()) {
    for (const m of src.matchAll(typed)) names.add(m[1]);
    for (const m of src.matchAll(initialised)) names.add(m[1]);
  }
  return names;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** `T(…).use` / `T(…)?.use` / `T(…).close()` — the construction closed on the spot. The paren
 *  walk is what makes this work across the line breaks a multi-argument construction wears. */
function constructAndUse(sources: Map<string, string>, typeName: string): boolean {
  const pattern = new RegExp(`\\b(?:[A-Za-z_][\\w.]*\\.)?${escapeRe(typeName)}\\s*\\(`, "g");
  for (const src of sources.values()) {
    for (const m of src.matchAll(pattern)) {
      let i = (m.index as number) + m[0].length - 1;
      let depth = 0;
      while (i < src.length) {
        if (src[i] === "(") depth += 1;
        else if (src[i] === ")") {
          depth -= 1;
          if (depth === 0) break;
        }
        i += 1;
      }
      const after = src.slice(i + 1, i + 40);
      if (/^\s*\??\.(use\b|close\s*\()/.test(after)) return true;
    }
  }
  return false;
}

function closedByName(sources: Map<string, string>, names: Set<string>): boolean {
  for (const name of names) {
    const closing = new RegExp(`\\b${escapeRe(name)}\\s*(?:\\?\\s*)?(?:\\.use\\b|\\.close\\s*\\(|::close\\b)`);
    for (const src of sources.values()) {
      if (closing.test(src)) return true;
    }
  }
  return false;
}

/** True when super_name is a closeable INTERFACE that has main-source closing evidence.
 *
 *  Closing through the interface is the AutoCloseable idiom — `close()`/`use {}` on the CONTRACT
 *  type, not the concrete one — and a handle typed by the interface (`val runtime: CodeModeRuntime`
 *  … `runtime.close()`) closes whichever concrete implementation it holds. The name-based evidence
 *  above cannot follow a construction into an interface-typed handle, so this is the false RED the
 *  checker's header promised to teach once such a helper appeared. Scoped to INTERFACES, not
 *  concrete superclasses: an interface's close() IS the contract its implementer honours, whereas a
 *  closed Parent handle does not prove a given Child was closed. */
function interfaceClosed(main: Map<string, string>, decls: Decl[], closeable: Set<string>, superName: string): boolean {
  const base = superName.split(".").pop() as string;
  if (!closeable.has(base)) return false;
  if ((decls.find((d) => d.name === base)?.kind ?? null) !== "interface") return false;
  return constructAndUse(main, base) || closedByName(main, handleNames(main, base));
}

function read(root: string, patterns: string | string[]): Map<string, string> {
  const out = new Map<string, string>();
  for (const pattern of Array.isArray(patterns) ? patterns : [patterns]) {
    const files = [...new Bun.Glob(pattern).scanSync({ cwd: root, followSymlinks: true })].sort();
    for (const rel of files) out.set(rel, stripComments(readFileSync(join(root, rel), "utf8")));
  }
  return out;
}

function audit(root: string): string[] {
  const problems: string[] = [];
  const main = read(root, MAIN_GLOBS);
  if (main.size === 0) {
    return [
      `no Kotlin main sources matched ${MAIN_GLOBS.join(", ")} under ${root} — refusing to pass vacuously; ` +
        "a checker that reads nothing vouches for nothing.",
    ];
  }

  const decls = declarations(main);
  const closeable = closeableClosure(decls);

  // Parser-drift guard, computed a SECOND and independent way: the number of declarations the
  // parser attributed a seed supertype to must equal the number of supertype lists that MENTION a
  // seed. They can only disagree if `supertypes()` stopped reading a declaration shape it should
  // have read, which is how a denominator quietly shrinks.
  const parsedDirect = new Set(decls.filter((d) => d.supers.some((s) => SEEDS.has(s))).map((d) => d.name));
  let mentioned = 0;
  for (const src of main.values()) mentioned += [...src.matchAll(DIRECT_MENTION)].length;
  // A seed may also appear as a property type or SAM constructor, so the mention count is an upper
  // bound; the failure that matters is the parser finding FEWER than the declarations that exist.
  if (mentioned > 0 && parsedDirect.size === 0) {
    problems.push(
      `${mentioned} supertype list(s) mention AutoCloseable/Closeable but the parser ` +
        "attributed NONE to a declaration — supertypes() has drifted from the Kotlin it reads, " +
        "so the closeable denominator is empty for a parser reason, not a code reason.",
    );
  }
  if (closeable.size === 0) {
    problems.push(
      "the closeable closure is EMPTY — refusing to pass vacuously. Either no type in this " +
        "tree implements AutoCloseable/Closeable (then this checker has nothing to guard and " +
        "should say so out loud) or the parse failed.",
    );
    return problems;
  }

  const concrete = decls.filter((d) => closeable.has(d.name) && d.kind !== "interface");
  const ordered = [...concrete].sort((a, b) =>
    a.path === b.path ? a.line - b.line : a.path < b.path ? -1 : 1,
  );
  for (const decl of ordered) {
    if (decl.name in ALLOWLIST) {
      const reason = ALLOWLIST[decl.name];
      if (!/^\d{4}-\d{2}-\d{2}: \S/.test(reason)) {
        problems.push(
          `${decl.path}:${decl.line} ${decl.name} — ALLOWLIST entry is not ` +
            "'YYYY-MM-DD: <reason>'. An exemption with no dated, written reason is an " +
            "absence wearing a label.",
        );
      }
      continue;
    }
    const names = handleNames(main, decl.name);
    if (constructAndUse(main, decl.name) || closedByName(main, names)) continue;
    if (decl.supers.some((superName) => interfaceClosed(main, decls, closeable, superName))) continue;
    const joined = [...main.values()].join("\n");
    const constructed = new RegExp(
      `\\b(?:[A-Za-z_][\\w.]*\\.)?${escapeRe(decl.name)}\\s*\\(`,
    ).test(joined);
    const why = constructed
      ? "constructed in a main source but never closed from one"
      : "never constructed in a main source either — dead in production, or its only " +
        "construction moved to tests";
    problems.push(
      `${decl.path}:${decl.line} ${decl.name} implements AutoCloseable/Closeable ` +
        `(via ${decl.supers.join(" -> ") || "AutoCloseable"}) and is ${why}. ` +
        "Close it from production: `use { }`, a `close()` on a handle, or `handle::close` in a " +
        "cleanup. A closeable whose only close() callers are tests is a leak with a green suite.",
    );
  }
  return problems;
}

/** Reporting aid: where the type IS closed, when it is not closed in main. Not part of the
 *  verdict — it is the sentence that makes a RED actionable. */
function testOnlyClosers(root: string, typeName: string): string[] {
  const tests = read(root, TEST_GLOBS);
  const names = new Set([...handleNames(tests, typeName), typeName]);
  const hits: string[] = [];
  for (const [path, src] of tests) {
    for (const name of names) {
      const re = new RegExp(
        `\\b${escapeRe(name)}\\s*(?:\\([^()]*\\))?\\s*(?:\\?\\s*)?(?:\\.use\\b|\\.close\\s*\\(|::close\\b)`,
        "g",
      );
      for (const m of src.matchAll(re)) {
        hits.push(`${path}:${src.slice(0, m.index as number).split("\n").length}`);
      }
    }
  }
  return [...new Set(hits)].sort();
}

// ── selftest fixtures ──────────────────────────────────────────────────────────────────────────

const SPI_CONTRACT = `package splice.upstream

public interface CodeModeRuntime : AutoCloseable {
    public fun start(): Unit
}
`;

// Each of the three evidence forms, once.
const COMPLIANT_APP = `package splice.app

internal class WorkerSession(private val start: WorkerStart) : AutoCloseable {
    override fun close() = Unit
}

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    override fun close() = Unit
}

internal class Lease(val label: String) : AutoCloseable {
    override fun close() = Unit
}

internal class Runner {
    fun one(start: WorkerStart) = WorkerSession(start).use { session -> session.toString() }

    fun two(path: Path) {
        val lock = DaemonLock(path)
        lock.close()
    }

    fun three(lease: Lease) {
        Cancellables.runCatchingCleanup(lease::close)
    }
}
`;

// The scar: a two-hop closeable closed only from src/test.
const LEAKY_APP = `package splice.app

public class JvmCodeModeRuntime : CodeModeRuntime {
    override fun start() = Unit
    override fun close() = Unit
}

internal class Arm {
    fun build() = Wiring(runtime = JvmCodeModeRuntime())
}
`;

const LEAKY_TEST = `package splice.app

class CodeModeRuntimeTest {
    fun reclaims() {
        JvmCodeModeRuntime().use { runtime -> runtime.start() }
    }
}
`;

// The interface-typed close: a concrete closeable constructed in main and closed through its
// interface-typed handle (the AutoCloseable idiom) — the false-RED interface_closed() was taught to
// close. `Arm.build` constructs it; `Config.shutdown` closes it through the `CodeModeRuntime` port.
const INTERFACE_CLOSED_APP = `package splice.app

public class JvmCodeModeRuntime : CodeModeRuntime {
    override fun start() = Unit
    override fun close() = Unit
}

internal class Config(private val runtime: CodeModeRuntime) {
    fun shutdown() { runtime.close() }
}

internal class Arm {
    fun build() = Config(runtime = JvmCodeModeRuntime())
}
`;

// The BORING case: exactly one closeable in the whole tree, and it is closed.
const ONE_CLOSEABLE_APP = `package splice.app

public class DaemonLock(private val lockFile: Path) : AutoCloseable {
    override fun close() = Unit
}

internal class Runner {
    fun go(path: Path) {
        val lock = DaemonLock(path)
        lock.close()
    }
}
`;

const NO_CLOSEABLE_APP = `package splice.app

internal class Plain(val label: String) {
    fun go() = Unit
}
`;

function write(root: string, files: Record<string, string>): void {
  const existing = MODULE_HOMES.map((home) => `${home}/src/*/**/*.kt`)
    .flatMap((p) => [...new Bun.Glob(p).scanSync({ cwd: root, followSymlinks: true })]);
  for (const rel of existing) {
    const target = join(root, rel);
    if (existsSync(target)) rmSync(target);
  }
  for (const [rel, body] of Object.entries(files)) {
    const path = join(root, rel);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, body, "utf8");
  }
}

const APP = "app/src/main/kotlin/splice/app/App.kt";
const SPI = "upstream/src/main/kotlin/splice/upstream/Spi.kt";
const TEST = "app/src/test/kotlin/RuntimeTest.kt";

/** Python's repr() of a list of strings. */
const pyReprList = (items: string[]): string =>
  `[${items.map((s) => `'${s.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`).join(", ")}]`;

function selftest(): number {
  const failures: string[] = [];
  const root = join(tmpdir(), `autocloseable-${process.pid}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(root, { recursive: true });
  try {
    // 1. GREEN: every evidence form, plus the interface exclusion.
    write(root, { [SPI]: SPI_CONTRACT, [APP]: COMPLIANT_APP });
    let hits = audit(root);
    if (hits.length > 0) failures.push(`1. the compliant tree must be GREEN, got: ${pyReprList(hits)}`);

    // 2. the BORING case: exactly one closeable, closed. A wall that only works on a crowd is
    //    not a wall (§24 — the boring case is the one that gets waved through).
    write(root, { [APP]: ONE_CLOSEABLE_APP });
    hits = audit(root);
    if (hits.length > 0) failures.push(`2. one-closeable tree, closed, must be GREEN, got: ${pyReprList(hits)}`);
    //    ...and the same single closeable, NOT closed, must be RED — so case 2's green is a
    //    measurement of the evidence and not of an empty denominator.
    write(root, { [APP]: ONE_CLOSEABLE_APP.replace("        lock.close()\n", "") });
    hits = audit(root);
    if (!hits.some((h) => h.includes("DaemonLock"))) {
      failures.push(`2b. the boring case must be able to FAIL, got: ${pyReprList(hits)}`);
    }

    // 3. RED BY NAME through a TWO-HOP interface chain, closed only from src/test — the scar.
    write(root, { [SPI]: SPI_CONTRACT, [APP]: LEAKY_APP, [TEST]: LEAKY_TEST });
    hits = audit(root);
    if (!hits.some((h) => h.includes("JvmCodeModeRuntime"))) {
      failures.push(`3. a two-hop closeable closed only in tests must be RED BY NAME, got: ${pyReprList(hits)}`);
    }
    if (!hits.some((h) => h.includes("never closed from one"))) {
      failures.push(`3b. the RED must say it IS constructed in main, got: ${pyReprList(hits)}`);
    }
    if (hits.some((h) => h.split(/\s+/)[1] === "CodeModeRuntime")) {
      failures.push(`3c. the INTERFACE must not be reported, only the class, got: ${pyReprList(hits)}`);
    }
    if (hits.filter((h) => h.includes("implements AutoCloseable")).length !== 1) {
      failures.push(`3e. exactly ONE finding is expected here, got: ${pyReprList(hits)}`);
    }
    if (testOnlyClosers(root, "JvmCodeModeRuntime").length === 0) {
      failures.push("3d. the reporting aid must locate the test-only closers");
    }

    // 4. the same tree with the close moved into main goes GREEN — proves 3 failed for the
    //    stated reason (no main closer) and not for some incidental parse difference.
    write(root, {
      [SPI]: SPI_CONTRACT,
      [APP]: LEAKY_APP.replace(
        "    fun build() = Wiring(runtime = JvmCodeModeRuntime())",
        "    fun build() {\n        val runtime = JvmCodeModeRuntime()\n        runtime.close()\n    }",
      ),
      [TEST]: LEAKY_TEST,
    });
    hits = audit(root);
    if (hits.length > 0) failures.push(`4. closing it from main must go GREEN, got: ${pyReprList(hits)}`);

    // 5. a dated allowlist entry is a disposition; an undated one is not.
    write(root, { [SPI]: SPI_CONTRACT, [APP]: LEAKY_APP, [TEST]: LEAKY_TEST });
    ALLOWLIST = { JvmCodeModeRuntime: "2026-09-17: selftest fixture, allowlisted on purpose." };
    hits = audit(root);
    if (hits.length > 0) failures.push(`5. a dated allowlist entry must be GREEN, got: ${pyReprList(hits)}`);
    ALLOWLIST = { JvmCodeModeRuntime: "because I said so" };
    hits = audit(root);
    if (!hits.some((h) => h.includes("not 'YYYY-MM-DD"))) {
      failures.push(`5b. an UNDATED allowlist entry must be RED, got: ${pyReprList(hits)}`);
    }
    ALLOWLIST = {};

    // 6. refuse to pass vacuously: no closeables at all, and no sources at all.
    write(root, { [APP]: NO_CLOSEABLE_APP });
    hits = audit(root);
    if (!hits.some((h) => h.includes("refusing to pass vacuously"))) {
      failures.push(`6. a tree with no closeable must REFUSE, not pass, got: ${pyReprList(hits)}`);
    }
    write(root, {});
    hits = audit(root);
    if (!hits.some((h) => h.includes("refusing to pass vacuously"))) {
      failures.push(`6b. a tree with no main sources must REFUSE, got: ${pyReprList(hits)}`);
    }

    // 7. the interface-typed close is closing evidence; removing the close goes RED by name.
    write(root, { [SPI]: SPI_CONTRACT, [APP]: INTERFACE_CLOSED_APP });
    hits = audit(root);
    if (hits.length > 0) failures.push(`7. a close through the interface must be GREEN, got: ${pyReprList(hits)}`);
    write(root, {
      [SPI]: SPI_CONTRACT,
      [APP]: INTERFACE_CLOSED_APP.replace(
        "    fun shutdown() { runtime.close() }",
        "    fun shutdown() = Unit",
      ),
    });
    hits = audit(root);
    if (!hits.some((h) => h.includes("JvmCodeModeRuntime"))) {
      failures.push(`7b. removing the interface-typed close must be RED by name, got: ${pyReprList(hits)}`);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }

  if (failures.length > 0) {
    process.stdout.write("autocloseable-closed --selftest FAIL\n");
    for (const failure of failures) process.stdout.write(`  x ${failure}\n`);
    return 1;
  }
  process.stdout.write(
    "autocloseable-closed --selftest OK — compliant GREEN (all 3 evidence forms), boring " +
      "one-type case GREEN and red-provable, two-hop test-only closer RED by name, same tree " +
      "GREEN once closed from main, interface-typed close GREEN and red-provable, dated " +
      "allowlist GREEN / undated RED, empty parse REFUSES\n",
  );
  return 0;
}

function main(argv: string[]): number {
  if (argv.includes("--selftest")) return selftest();
  // `check` is accepted (and ignored) so the gate `run` line reads like its siblings in
  // the ladder; anything else is a typo and must fail rather than be silently dropped.
  for (const arg of argv) {
    if (arg !== "check") {
      process.stdout.write(
        `autocloseable-closed: unknown argument '${arg}' (usage: [check] | --selftest)\n`,
      );
      return 2;
    }
  }
  const problems = audit(ROOT);
  if (problems.length === 0) {
    const mainSources = read(ROOT, MAIN_GLOBS);
    const closure = closeableClosure(declarations(mainSources));
    const concrete = declarations(mainSources).filter((d) => closure.has(d.name) && d.kind !== "interface");
    process.stdout.write(
      `autocloseable-closed: PASS (${concrete.length} concrete closeable type(s) accounted for)\n`,
    );
    return 0;
  }
  process.stdout.write("autocloseable-closed: FAIL\n");
  for (const problem of problems) {
    process.stdout.write(`  x ${problem}\n`);
    const parts = problem.split(/\s+/);
    const name = parts.length > 1 ? parts[1] : "";
    for (const hit of testOnlyClosers(ROOT, name)) {
      process.stdout.write(`      closed only here: ${hit}\n`);
    }
  }
  return 1;
}

process.exit(main(process.argv.slice(2)));
