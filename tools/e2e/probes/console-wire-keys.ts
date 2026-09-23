#!/usr/bin/env bun
/** V4-140 — the console read against a LIVE daemon: every key the console's TypeScript types
 *  declare must be PRESENT in the payload the daemon actually sends, and non-null wherever the type
 *  says it cannot be null.
 *
 *  WHY THIS EXISTS. Every console capture to date was fixture-fed, and the fixtures were written to
 *  match the console's own types, so the two agreed with each other and neither was checked against
 *  the wire. The defect that exposed it: the models page read `context_window_source`, declared
 *  NON-OPTIONAL in console/src/entities/model/model/types.ts, while the daemon emitted
 *  `window_source`. TypeScript cannot see across the wire, so nothing fired, and the column
 *  rendered `undefined` against a live daemon while every fixture looked right.
 *
 *  THE DENOMINATOR COMES FROM THE SOURCE, NEVER FROM A LIST IN THIS FILE (law 24):
 *   - the ROUTES are every `request<T>(...)` call in console/src, found by the TypeScript compiler
 *     over console/tsconfig.json, so a route the console starts reading is checked with no edit here;
 *   - the KEYS are T's properties as the type checker resolves them (interfaces, extends, aliases,
 *     unions, arrays, Record values), so a field the console starts declaring is checked too.
 *  What IS listed here is only INPUT, never the denominator: the values that fill a templated path
 *  (a head key, a session id), and the call sites that are dispositioned rather than checked, each
 *  with its reason. A call site in neither place fails BY NAME — an unknown is never a pass.
 *
 *  PRESENCE AND NULL-NESS, NEVER VALUES, so the check stays stable while the daemon's data moves.
 *  A key the type does not declare is printed as UNDECLARED (it is how `window_source` would have
 *  shown up) but does not fail: the daemon may send more than the console reads.
 *
 *  WHAT A PASS CANNOT MEAN. A slot inside an empty array, or under an absent optional parent, has
 *  no live value to read; it is printed as UNEXERCISED with its path, never counted as checked. A
 *  route whose payload carries NONE of its declared keys fails (VACUOUS), so an empty object or an
 *  error envelope can never pass by iterating nothing.
 *
 *  THE DAEMON:
 *    --boot <jar>       boot THAT jar isolated (its own user.home, config and free ports, no
 *                       provider credential in its environment), read it, stop it. The tree's
 *                       build is app/build/libs/app-all.jar.
 *    --control <url>    read an already-running daemon instead (default http://127.0.0.1:3096,
 *                       bearer from the state root's mgmt-key or --key-file). Read-only GETs.
 *    --capture <dir>    also write each payload read, as <call-site id>.json.
 *    --replay <dir>     read payloads from a capture instead of HTTP (no daemon).
 *    --selftest         read, then prove the check can fail on those real payloads: for EVERY
 *                       checked route, one present required top-level key is renamed, one present
 *                       required key INSIDE an array element is renamed (the shape of the
 *                       context_window_source defect), and the payload is replaced by {}; each arm
 *                       must print a FAIL line the unmutated run did not, and a rename must NAME
 *                       the key. This mode grades only the arms: it prints the plain run's findings
 *                       as a count, and its exit code and last line are the arms' verdict.
 *  Exit 0 = every read call site checked green or dispositioned; 1 = a FAIL line was printed.
 *  The exit code and the last line always agree.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join, relative, resolve } from "node:path";
import ts from "typescript";
import { findRepoRoot } from "../../gate/src/lib/repo.ts";

const REPO = findRepoRoot(import.meta.dir);
const WEBUI = join(REPO, "console");
const REQUEST_HOME = join(WEBUI, "src/shared/api/index.ts");
const FETCH_TIMEOUT_MS = 15_000;
const BOOT_TIMEOUT_MS = 90_000;
const MAX_DEPTH = 12;

// ── input: how a templated path is filled ──────────────────────────────────────────────────────
//
// A `${...}` in a route path is filled by the TEXT of its expression. Numbers and string-literal
// unions are filled from the expression's own type (the first literal), so they need no entry.
// An identifier here names a LIST route and the field of its first row that supplies the value.
// A placeholder that matches nothing below and has no literal type fails the call site by name.
interface IdSource {
  route: string;
  array: string;
  field: string;
}
const ID_SOURCES: Record<string, IdSource> = {
  head: { route: "/api/heads", array: "heads", field: "key" },
  "encodeURIComponent(head)": { route: "/api/heads", array: "heads", field: "key" },
  "encodeURIComponent(sessionId)": { route: "/api/sessions", array: "sessions", field: "session_id" },
  "entities/team|encodeURIComponent(id)": { route: "/api/teams", array: "teams", field: "id" },
  "entities/project|encodeURIComponent(id)": { route: "/api/projects", array: "projects", field: "id" },
};
/** Query suffixes the console builds at runtime, filled as the console fills them by default:
 *  fetchPerfTurns asks ONE head per request (the route refuses an absent head, PerfRoutes.turns)
 *  and always sets n, and fetchCapture sends no query unless the operator scrubs. A `{name}` token
 *  is an ID_SOURCES key, filled with a live value exactly as a path placeholder is. */
const QUERY_FILL: Record<string, string> = {
  "entities/perf|query.toString()": "head={head}&n=20",
  "entities/perf|query": "",
};

/** A path expression that is a call to a local helper rather than a literal. Keyed by file and the
 *  exact call text, so a changed call no longer matches and the call site fails by name. */
const PATH_OF_CALL: Record<string, string> = {
  "entities/transcript/api/index.ts|pagePath(sessionId, null)":
    "/api/sessions/${encodeURIComponent(sessionId)}/transcript",
};

// ── input: call sites dispositioned rather than checked, each with its reason ─────────────────
const DISPOSITIONED: Record<string, string> = {
  "entities/transcript/api/index.ts|pagePath(state.sessionId, token)":
    "a later page of the same route and the same TranscriptPage type the first page is checked " +
    "against; its cursor exists only after a first page returned one",
  "entities/auth/api/index.ts|path":
    "settle<T>() is the transport of the five auth WRITES; its T is generic here and each write " +
    "is a POST, so there is no read payload at this call site",
};
/** Non-request fetch() sites in console/src, which the same scan enumerates. */
const FETCH_DISPOSITIONED: Record<string, string> = {
  "shared/api/index.ts|path":
    "the transport inside request<T>() itself; every call of request<T>() is its own call site above",
  "shared/api/index.ts|'/health'":
    "unauthenticated probe read fail-open, and its one declared key (topologyStale) is optional",
  "entities/events/api/index.ts|STREAM_PATH":
    "the SSE stream, not a JSON read; its families are checked against ConsoleEvent's serializer " +
    "by splice.app.ConsoleEventProducersTest",
};

// ── the denominator: every request<T> call in console/src ────────────────────────────────────────

interface CallSite {
  id: string;
  file: string;
  line: number;
  method: string | null;
  pathText: string;
  templates: Template[] | null;
  type: ts.Type;
  typeText: string;
  /** The row the console names when this read 404s, read from pendingOf() in the catch around it. */
  pending: string | null;
}
interface Template {
  parts: (string | Placeholder)[];
}
interface Placeholder {
  text: string;
  literal: string | null;
}
interface FetchSite {
  key: string;
  line: number;
}

function loadProgram(): ts.Program {
  const configPath = join(WEBUI, "tsconfig.json");
  const parsed = ts.getParsedCommandLineOfConfigFile(configPath, {}, {
    ...ts.sys,
    onUnRecoverableConfigFileDiagnostic: (d) => {
      throw new Error(ts.flattenDiagnosticMessageText(d.messageText, "\n"));
    },
  });
  if (!parsed) throw new Error(`cannot parse ${configPath}`);
  return ts.createProgram(parsed.fileNames, parsed.options);
}

function isProductionSource(file: string): boolean {
  const rel = relative(join(WEBUI, "src"), file);
  return !rel.startsWith("..") && !/(\.test\.|\.spec\.|__tests__|\/test\/)/.test(rel);
}

function methodOf(init: ts.Expression | undefined): string | null {
  if (init === undefined) return "GET";
  if (!ts.isObjectLiteralExpression(init)) return null;
  for (const prop of init.properties) {
    if (ts.isPropertyAssignment(prop) && prop.name.getText() === "method") {
      return ts.isStringLiteralLike(prop.initializer) ? prop.initializer.text.toUpperCase() : null;
    }
  }
  return "GET";
}

function literalOf(checker: ts.TypeChecker, expr: ts.Expression): string | null {
  const type = checker.getTypeAtLocation(expr);
  const members = type.isUnion() ? type.types : [type];
  for (const member of members) {
    if (member.isStringLiteral()) return member.value;
    if (member.isNumberLiteral()) return String(member.value);
  }
  if (type.flags & ts.TypeFlags.NumberLike) return "20";
  return null;
}

/** The shared/api function an identifier names, through any import alias (`pendingOf as x`). */
function declarationOf(checker: ts.TypeChecker, expr: ts.Expression): string | null {
  let symbol = checker.getSymbolAtLocation(expr);
  if (symbol !== undefined && symbol.flags & ts.SymbolFlags.Alias) symbol = checker.getAliasedSymbol(symbol);
  const declared = symbol?.declarations?.[0];
  return declared && ts.isFunctionDeclaration(declared) && declared.name
    ? `${declared.getSourceFile().fileName}#${declared.name.text}`
    : null;
}
const PENDING_OF = `${REQUEST_HOME}#pendingOf`;

/** The console renders a 404 on an unbuilt route as PENDING <row> on purpose (pendingOf in
 *  shared/api). Which call sites do is read from the source: the try around the call whose catch
 *  passes pendingOf() a row id the checker resolves to a string literal — directly, or through one
 *  helper in the same file that the catch calls (transcript's resolveFailure). */
function pendingRowOf(checker: ts.TypeChecker, node: ts.Node): string | null {
  for (let at: ts.Node = node; at.parent !== undefined; at = at.parent) {
    const parent = at.parent;
    if (ts.isTryStatement(parent) && parent.tryBlock === at && parent.catchClause !== undefined) {
      let row: string | null = null;
      const file = node.getSourceFile().fileName;
      const visit = (n: ts.Node, helperDepth: number): void => {
        if (ts.isCallExpression(n)) {
          if (declarationOf(checker, n.expression) === PENDING_OF) {
            const arg = n.arguments[1];
            const type = arg ? checker.getTypeAtLocation(arg) : undefined;
            if (type?.isStringLiteral()) row = type.value;
          } else if (helperDepth === 0) {
            const helper = checker.getSymbolAtLocation(n.expression)?.declarations?.[0];
            if (helper && ts.isFunctionDeclaration(helper) && helper.body && helper.getSourceFile().fileName === file) {
              visit(helper.body, 1);
            }
          }
        }
        ts.forEachChild(n, (c) => visit(c, helperDepth));
      };
      visit(parent.catchClause, 0);
      return row;
    }
    if (ts.isFunctionLike(parent)) return null;
  }
  return null;
}

function templatesOf(checker: ts.TypeChecker, expr: ts.Expression, fileKey: string): Template[] | null {
  if (ts.isStringLiteralLike(expr)) return [{ parts: [expr.text] }];
  if (ts.isTemplateExpression(expr)) {
    const parts: (string | Placeholder)[] = [expr.head.text];
    for (const span of expr.templateSpans) {
      parts.push({ text: span.expression.getText(), literal: literalOf(checker, span.expression) });
      parts.push(span.literal.text);
    }
    return [{ parts }];
  }
  if (ts.isConditionalExpression(expr)) {
    const a = templatesOf(checker, expr.whenTrue, fileKey);
    const b = templatesOf(checker, expr.whenFalse, fileKey);
    return a && b ? [...a, ...b] : null;
  }
  if (ts.isParenthesizedExpression(expr)) return templatesOf(checker, expr.expression, fileKey);
  const mapped = PATH_OF_CALL[`${fileKey}|${expr.getText()}`];
  if (mapped !== undefined) {
    const parts: (string | Placeholder)[] = [];
    for (const piece of mapped.split(/(\$\{[^}]+\})/)) {
      if (piece.startsWith("${")) parts.push({ text: piece.slice(2, -1), literal: null });
      else if (piece !== "") parts.push(piece);
    }
    return [{ parts }];
  }
  return null;
}

function enumerate(program: ts.Program): { calls: CallSite[]; fetches: FetchSite[] } {
  const checker = program.getTypeChecker();
  const calls: CallSite[] = [];
  const fetches: FetchSite[] = [];
  for (const source of program.getSourceFiles()) {
    if (source.isDeclarationFile || !isProductionSource(source.fileName)) continue;
    const fileKey = relative(join(WEBUI, "src"), source.fileName);
    const visit = (node: ts.Node): void => {
      if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
        const name = node.expression.text;
        const line = source.getLineAndCharacterOfPosition(node.getStart()).line + 1;
        // An entity file imports request from @shared/api, so its symbol is the import alias;
        // resolve it to the declaration, or every entity route silently drops out of the scan.
        let symbol = checker.getSymbolAtLocation(node.expression);
        if (symbol !== undefined && symbol.flags & ts.SymbolFlags.Alias) symbol = checker.getAliasedSymbol(symbol);
        const home = symbol?.declarations?.[0]?.getSourceFile().fileName;
        if (declarationOf(checker, node.expression) === `${REQUEST_HOME}#request` && node.typeArguments?.length === 1) {
          const pathExpr = node.arguments[0];
          const typeNode = node.typeArguments[0];
          if (pathExpr !== undefined && typeNode !== undefined) {
            calls.push({
              id: `${fileKey}:${line}`,
              file: fileKey,
              line,
              method: methodOf(node.arguments[1]),
              pathText: pathExpr.getText(),
              templates: templatesOf(checker, pathExpr, fileKey),
              type: checker.getTypeFromTypeNode(typeNode),
              typeText: typeNode.getText(),
              pending: pendingRowOf(checker, node),
            });
          }
        } else if (name === "fetch" && home !== undefined && !home.startsWith(join(WEBUI, "src"))) {
          fetches.push({ key: `${fileKey}|${node.arguments[0]?.getText() ?? ""}`, line });
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  return { calls, fetches };
}

// ── the type walk: declared slots, and a live value measured against them ──────────────────────

interface Result {
  failures: Map<string, string>;
  undeclared: Set<string>;
  evaluated: Set<string>;
  declared: Set<string>;
  presentTop: number;
}

function newResult(): Result {
  return { failures: new Map(), undeclared: new Set(), evaluated: new Set(), declared: new Set(), presentTop: 0 };
}

function strip(checker: ts.TypeChecker, type: ts.Type): { core: ts.Type; nullable: boolean; undef: boolean } {
  const members = type.isUnion() ? type.types : [type];
  const nullable = members.some((m) => (m.flags & ts.TypeFlags.Null) !== 0);
  const undef = members.some((m) => (m.flags & (ts.TypeFlags.Undefined | ts.TypeFlags.Void)) !== 0);
  return { core: checker.getNonNullableType(type), nullable, undef };
}

type Shape =
  | { kind: "leaf" }
  | { kind: "array"; element: ts.Type }
  | { kind: "object"; props: ts.Symbol[]; index: ts.Type | null }
  | { kind: "union"; members: ts.Type[] };

function shapeOf(checker: ts.TypeChecker, type: ts.Type): Shape {
  const scalar = ts.TypeFlags.Any | ts.TypeFlags.Unknown | ts.TypeFlags.StringLike | ts.TypeFlags.NumberLike |
    ts.TypeFlags.BooleanLike | ts.TypeFlags.BigIntLike | ts.TypeFlags.EnumLike | ts.TypeFlags.ESSymbolLike |
    ts.TypeFlags.Null | ts.TypeFlags.Undefined | ts.TypeFlags.Void | ts.TypeFlags.Never;
  if (type.flags & scalar) {
    return { kind: "leaf" };
  }
  if (checker.isArrayType(type)) {
    const element = checker.getTypeArguments(type as ts.TypeReference)[0];
    return element ? { kind: "array", element } : { kind: "leaf" };
  }
  if (checker.isTupleType(type)) return { kind: "leaf" };
  if (type.isUnion()) {
    const objects = type.types.filter((m) => shapeOf(checker, m).kind === "object");
    return objects.length === type.types.length ? { kind: "union", members: objects } : { kind: "leaf" };
  }
  if (type.flags & ts.TypeFlags.Object || type.isIntersection()) {
    const props = checker.getPropertiesOfType(type).filter((p) => !(p.flags & ts.SymbolFlags.Method));
    const index = checker.getIndexInfosOfType(type).find((i) => i.keyType.flags & ts.TypeFlags.String)?.type ?? null;
    if (props.length === 0 && index === null) return { kind: "leaf" };
    return { kind: "object", props, index };
  }
  return { kind: "leaf" };
}

/** Every slot the type declares, walked with no data: the per-route denominator. */
function declareSlots(checker: ts.TypeChecker, type: ts.Type, path: string, out: Set<string>, seen: ts.Type[]): void {
  if (seen.length > MAX_DEPTH || seen.includes(type)) return;
  const { core } = strip(checker, type);
  const shape = shapeOf(checker, core);
  const next = [...seen, type];
  if (shape.kind === "array") declareSlots(checker, shape.element, `${path}[]`, out, next);
  if (shape.kind === "union") for (const m of shape.members) declareSlots(checker, m, path, out, next);
  if (shape.kind === "object") {
    for (const prop of shape.props) {
      const slot = path === "" ? prop.name : `${path}.${prop.name}`;
      out.add(slot);
      declareSlots(checker, checker.getTypeOfSymbol(prop), slot, out, next);
    }
    if (shape.index) {
      out.add(`${path}{}`);
      declareSlots(checker, shape.index, `${path}{}`, out, next);
    }
  }
}

function fail(result: Result, slot: string, message: string): void {
  if (!result.failures.has(slot)) result.failures.set(slot, message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function missingCount(checker: ts.TypeChecker, member: ts.Type, value: Record<string, unknown>): number {
  const shape = shapeOf(checker, member);
  if (shape.kind !== "object") return Number.MAX_SAFE_INTEGER;
  return shape.props.filter((p) => !(p.name in value) && !optional(checker, p)).length;
}

function optional(checker: ts.TypeChecker, prop: ts.Symbol): boolean {
  return (prop.flags & ts.SymbolFlags.Optional) !== 0 || strip(checker, checker.getTypeOfSymbol(prop)).undef;
}

function measure(
  checker: ts.TypeChecker,
  type: ts.Type,
  value: unknown,
  slot: string,
  where: string,
  result: Result,
  depth: number,
): void {
  if (depth > MAX_DEPTH) return;
  const { core } = strip(checker, type);
  const shape = shapeOf(checker, core);
  if (shape.kind === "leaf") return;
  if (shape.kind === "array") {
    if (!Array.isArray(value)) {
      fail(result, slot || "(payload)", `SHAPE ${where || "(payload)"}: declared an array, got ${kindOf(value)}`);
      return;
    }
    value.forEach((element, i) => measure(checker, shape.element, element, `${slot}[]`, `${where}[${i}]`, result, depth + 1));
    return;
  }
  if (!isRecord(value)) {
    fail(result, slot || "(payload)", `SHAPE ${where || "(payload)"}: declared an object, got ${kindOf(value)}`);
    return;
  }
  if (shape.kind === "union") {
    const best = [...shape.members].sort((a, b) => missingCount(checker, a, value) - missingCount(checker, b, value))[0];
    if (best) measure(checker, best, value, slot, where, result, depth);
    return;
  }
  const names = new Set(shape.props.map((p) => p.name));
  for (const prop of shape.props) {
    const propSlot = slot === "" ? prop.name : `${slot}.${prop.name}`;
    const propWhere = where === "" ? prop.name : `${where}.${prop.name}`;
    const propType = checker.getTypeOfSymbol(prop);
    result.evaluated.add(propSlot);
    if (!(prop.name in value)) {
      if (!optional(checker, prop)) {
        fail(result, propSlot, `MISSING ${propWhere}: declared non-optional, absent on the wire`);
      }
      continue;
    }
    if (depth === 0) result.presentTop += 1;
    const present = value[prop.name];
    if (present === null) {
      if (!strip(checker, propType).nullable) {
        fail(result, propSlot, `NULL ${propWhere}: declared non-nullable, null on the wire`);
      }
      continue;
    }
    measure(checker, propType, present, propSlot, propWhere, result, depth + 1);
  }
  if (shape.index) {
    for (const [key, entry] of Object.entries(value)) {
      if (names.has(key)) continue;
      result.evaluated.add(`${slot}{}`);
      if (depth === 0) result.presentTop += 1;
      measure(checker, shape.index, entry, `${slot}{}`, `${where}.${key}`, result, depth + 1);
    }
  } else {
    for (const key of Object.keys(value)) {
      if (!names.has(key)) result.undeclared.add(slot === "" ? key : `${slot}.${key}`);
    }
  }
}

function kindOf(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "an array";
  return typeof value === "object" ? "an object" : `a ${typeof value}`;
}

// ── the daemon ─────────────────────────────────────────────────────────────────────────────────

interface Daemon {
  base: string;
  key: string;
  /** The booted JVM's pid; null for a daemon this check attached to and does not own. */
  pid: number | null;
  stop: () => Promise<void>;
}

async function freePort(): Promise<number> {
  const server = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } });
  const port = server.port;
  server.stop(true);
  return port;
}

async function answers(url: string): Promise<boolean> {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(2_000) });
    return res.ok;
  } catch {
    return false;
  }
}

/** Boots [jar] with a home, a config and ports of its own. The environment is rebuilt from
 *  scratch rather than inherited, so no provider credential in this shell reaches the daemon and
 *  its head can never spend quota: the head boots unauthenticated, which is a state the console
 *  renders and therefore a payload worth reading. */
/** V4-177: the LIVE install's mgmt-key file, resolved the way StatePaths.kt resolves the state root
 *  — env first, then ~/.splice/state, adopting a pre-0.4 ~/.claude-codex/state in place when that is
 *  the only one on the box. `--attach` points at whatever daemon is already running here, so picking
 *  the wrong root reads a key that daemon never minted and every GET comes back 401. */
export function liveStateDir(home = homedir(), env = process.env): string {
  // PER VARIABLE, and blank is not an answer. `??` falls through only on null/undefined, so
  // `export SPLICE_STATE_DIR=` gave "" and skipped CLAUDEX_STATE_DIR entirely — while Kotlin and
  // all three shell copies fall through to it. That is the exact variable StatePaths' own KDoc
  // names as the real case, and reading the wrong key turns every GET into a 401 that reads like a
  // payload/contract failure rather than a wrong root.
  for (const name of ["SPLICE_STATE_DIR", "CLAUDEX_STATE_DIR"]) {
    const value = env[name];
    if (value !== undefined && value.trim() !== "") return value;
  }
  // Adoption needs POSITIVE evidence on both sides: the current root proven absent, the pre-0.4 one
  // proven to be a DIRECTORY. ENOENT alone is absence — `throwIfNoEntry: false` would answer
  // undefined on ENOTDIR too (a REGULAR FILE where ~/.splice belongs) and adopt the pre-0.4 root
  // where StatePaths' three-valued probe declines. "Cannot be read" is not absent either.
  const probe = (dir: string): "dir" | "absent" | "unusable" => {
    try {
      return statSync(dir).isDirectory() ? "dir" : "unusable";
    } catch (failure) {
      // EACCES on the parent, ENOTDIR: present-or-absent is UNKNOWN, which is not absent.
      return (failure as NodeJS.ErrnoException).code === "ENOENT" ? "absent" : "unusable";
    }
  };
  const current = join(home, ".splice", "state");
  const legacy = join(home, ".claude-codex", "state");
  return probe(current) === "absent" && probe(legacy) === "dir" ? legacy : current;
}

function liveMgmtKeyFile(): string {
  return join(liveStateDir(), "mgmt-key");
}

async function boot(jar: string): Promise<Daemon> {
  if (!existsSync(jar)) throw new Error(`--boot: no jar at ${jar}; build it with :app:shadowJar`);
  const home = mkdtempSync(join(tmpdir(), "console-wire-keys-"));
  const control = await freePort();
  const headPort = await freePort();
  const config = join(home, ".config/splice/splice.toml");
  mkdirSync(dirname(config), { recursive: true });
  writeFileSync(config, [
    "[daemon]",
    `control_port = ${control}`,
    "",
    "[providers.openrouter]",
    'dialect = "openai-chat"',
    'base_url = "https://openrouter.invalid/api/v1"',
    'auth = { kind = "api-key", env = "CONSOLE_WIRE_KEYS_NO_SUCH_KEY" }',
    "",
    "[[providers.openrouter.models]]",
    'id = "wire/keys-model"',
    'label = "Wire Keys Model"',
    "context_window = 200000",
    "",
    "[heads.openrouter]",
    'provider = "openrouter"',
    `port = ${headPort}`,
    'discovery_prefix = "claude-openrouter--"',
    'pinned_model = "wire/keys-model"',
    'models = [{ id = "wire/keys-model", slot = "sonnet" }]',
    "",
  ].join("\n"));
  const env = {
    PATH: process.env.PATH ?? "/usr/bin:/bin",
    HOME: home,
    XDG_CONFIG_HOME: join(home, ".config"),
    SPLICE_CONFIG: config,
    SPLICE_CONTROL_PORT: String(control),
  };
  // splice.noSystemBrowser: the wall against opening the operator's browser (LoginIo.kt) is set by
  // the shared Gradle TEST task, and a daemon spawned here is a different JVM that inherits none of
  // it, so it is set again here. The rebuilt environment also carries no DISPLAY, WAYLAND_DISPLAY or
  // DBUS address, so an opener that slipped the property would still have no desktop to reach.
  const child: ChildProcess = spawn("java", [
    "-Xmx512m",
    "-Dsplice.noSystemBrowser=1",
    `-Duser.home=${home}`,
    "-jar",
    jar,
    "daemon",
  ], {
    env,
    stdio: ["ignore", "ignore", "ignore"],
  });
  const base = `http://127.0.0.1:${control}`;
  const stop = async (): Promise<void> => {
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      const deadline = Date.now() + 20_000;
      while (child.exitCode === null && Date.now() < deadline) await Bun.sleep(100);
      if (child.exitCode === null) child.kill("SIGKILL");
    }
    rmSync(home, { recursive: true, force: true });
  };
  const deadline = Date.now() + BOOT_TIMEOUT_MS;
  while (!(await answers(`${base}/health`))) {
    if (child.exitCode !== null || Date.now() > deadline) {
      await stop();
      throw new Error(`--boot: ${jar} did not answer ${base}/health (exit ${child.exitCode})`);
    }
    await Bun.sleep(250);
  }
  // V4-177: a daemon booted into a fresh HOME writes the current layout; there is no pre-0.4
  // root in a throwaway home for it to adopt.
  const keyFile = join(home, ".splice/state/mgmt-key");
  return { base, key: readFileSync(keyFile, "utf8").trim(), pid: child.pid ?? null, stop };
}

async function attach(base: string, keyFile: string): Promise<Daemon> {
  if (!(await answers(`${base}/health`))) throw new Error(`--control: nothing answers ${base}/health`);
  return { base, key: readFileSync(keyFile, "utf8").trim(), pid: null, stop: async () => {} };
}

// ── reading ────────────────────────────────────────────────────────────────────────────────────

type Payloads = Map<string, unknown>;

interface Source {
  read(site: CallSite, path: string): Promise<{ ok: true; body: unknown } | { ok: false; why: string; status?: number }>;
}

function httpSource(daemon: Daemon, capture: Payloads): Source {
  return {
    async read(site, path) {
      try {
        const res = await fetch(`${daemon.base}${path}`, {
          headers: { Authorization: `Bearer ${daemon.key}` },
          signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
        });
        const text = await res.text();
        if (!res.ok) {
          const said = errorMessageOf(text);
          const why = `HTTP ${res.status} on GET ${path}${said === undefined ? "" : ` (the daemon said: ${said})`}`;
          return { ok: false, why, status: res.status };
        }
        const body: unknown = JSON.parse(text);
        capture.set(site.id, body);
        return { ok: true, body };
      } catch (err) {
        return { ok: false, why: `GET ${path}: ${err instanceof Error ? err.message : String(err)}` };
      }
    },
  };
}

/** The reason in an error body. The daemon writes {"error": "<text>"}; the console's request()
 *  reads {"error": {"message": ...}}. Both are read here, so the FAIL line carries the reason
 *  whichever envelope a route uses. */
function errorMessageOf(text: string): string | undefined {
  try {
    const body: unknown = JSON.parse(text);
    const error = isRecord(body) ? body.error : undefined;
    if (typeof error === "string") return error;
    if (isRecord(error) && typeof error.message === "string") return error.message;
  } catch {
    // not JSON: the status alone is the reason
  }
  return undefined;
}

function replaySource(payloads: Payloads): Source {
  return {
    async read(site) {
      return payloads.has(site.id)
        ? { ok: true, body: payloads.get(site.id) }
        : { ok: false, why: `no captured payload for ${site.id}` };
    },
  };
}

// ── one run ────────────────────────────────────────────────────────────────────────────────────

interface Outcome {
  lines: string[];
  failed: boolean;
  failSlots: Map<string, string[]>;
  checked: CallSite[];
}

async function fillPath(
  site: CallSite,
  template: Template,
  lists: Map<string, unknown>,
  source: Source,
  sites: CallSite[],
): Promise<{ path: string } | { unfilled: string } | { empty: string }> {
  let path = "";
  for (const part of template.parts) {
    if (typeof part === "string") {
      path += part;
      continue;
    }
    if (part.literal !== null) {
      path += encodeURIComponent(part.literal);
      continue;
    }
    const entity = site.file.split("/").slice(0, 2).join("/");
    const query = QUERY_FILL[`${entity}|${part.text}`];
    if (query !== undefined) {
      let filled = query;
      for (const token of query.match(/\{[^}]+\}/g) ?? []) {
        const idSource = ID_SOURCES[token.slice(1, -1)];
        if (idSource === undefined) return { unfilled: token };
        const value = await liveId(idSource, lists, source, sites);
        if (typeof value !== "string") return value;
        filled = filled.replace(token, encodeURIComponent(value));
      }
      path += filled;
      continue;
    }
    const idSource = ID_SOURCES[`${entity}|${part.text}`] ?? ID_SOURCES[part.text];
    if (idSource === undefined) return { unfilled: part.text };
    const value = await liveId(idSource, lists, source, sites);
    if (typeof value !== "string") return value;
    path += encodeURIComponent(value);
  }
  return { path };
}

/** The first live value an ID source's list route reports, read once per run and cached in `lists`. */
async function liveId(
  idSource: IdSource,
  lists: Map<string, unknown>,
  source: Source,
  sites: CallSite[],
): Promise<string | { empty: string }> {
  if (!lists.has(idSource.route)) {
    const lister = sites.find((s) => s.templates?.[0]?.parts.length === 1 && s.templates[0].parts[0] === idSource.route);
    const read = lister ? await source.read(lister, idSource.route) : { ok: false as const, why: "" };
    lists.set(idSource.route, read.ok ? read.body : null);
  }
  const rows = (lists.get(idSource.route) as Record<string, unknown> | null)?.[idSource.array];
  const value = Array.isArray(rows)
    ? rows.map((r) => (isRecord(r) ? r[idSource.field] : null)).find((v) => typeof v === "string" && v !== "")
    : undefined;
  return typeof value === "string" ? value : { empty: `${idSource.route} has no ${idSource.array}[].${idSource.field}` };
}

async function run(checker: ts.TypeChecker, calls: CallSite[], fetches: FetchSite[], source: Source): Promise<Outcome> {
  const lines: string[] = [];
  const failSlots = new Map<string, string[]>();
  const checked: CallSite[] = [];
  let failed = false;
  const lists = new Map<string, unknown>();
  const failLine = (text: string): void => {
    failed = true;
    lines.push(`FAIL ${text}`);
  };
  for (const f of fetches) {
    const reason = FETCH_DISPOSITIONED[f.key];
    if (reason === undefined) failLine(`${f.key} (line ${f.line}): a fetch() read with no disposition`);
    else lines.push(`EXCLUDED ${f.key}: ${reason}`);
  }
  for (const site of calls) {
    const label = `${site.id} ${site.typeText} ${site.pathText}`;
    const reason = DISPOSITIONED[`${site.file}|${site.pathText}`];
    if (reason !== undefined) {
      lines.push(`EXCLUDED ${label}: ${reason}`);
      continue;
    }
    if (site.method === null) {
      failLine(`${label}: its method is not a literal, so it cannot be told apart from a read; disposition it`);
      continue;
    }
    if (site.method !== "GET") {
      lines.push(`EXCLUDED ${label}: a ${site.method} write, not a read payload`);
      continue;
    }
    if (site.templates === null) {
      failLine(`${label}: the path is not a literal the check can resolve; add it to PATH_OF_CALL or disposition it`);
      continue;
    }
    for (const template of site.templates) {
      const filled = await fillPath(site, template, lists, source, calls);
      if ("unfilled" in filled) {
        failLine(`${label}: no value for placeholder \${${filled.unfilled}}; add an ID_SOURCES entry`);
        continue;
      }
      if ("empty" in filled) {
        lines.push(`UNEXERCISED ${label}: ${filled.empty}, so there is no id to read it with`);
        continue;
      }
      const read = await source.read(site, filled.path);
      if (!read.ok && read.status === 404 && site.pending !== null) {
        lines.push(`PENDING ${label}: 404, which the console renders as PENDING ${site.pending} by design`);
        continue;
      }
      if (!read.ok) {
        failLine(`${label}: ${read.why}`);
        continue;
      }
      checked.push(site);
      const result = newResult();
      declareSlots(checker, site.type, "", result.declared, []);
      measure(checker, site.type, read.body, "", "", result, 0);
      const slots = [...result.failures.keys()];
      failSlots.set(site.id, slots);
      if (result.presentTop === 0) {
        failLine(`${label} GET ${filled.path}: VACUOUS — none of the ${result.declared.size} declared keys is present`);
      }
      for (const message of result.failures.values()) failLine(`${label}: ${message}`);
      const unexercised = [...result.declared].filter((s) => !result.evaluated.has(s));
      lines.push(
        `CHECKED ${label} GET ${filled.path}: ${[...result.evaluated].filter((s) => result.declared.has(s)).length} of ${result.declared.size} declared slots ` +
          `read, ${result.failures.size} failing`,
      );
      for (const slot of unexercised) lines.push(`  UNEXERCISED ${slot}: no live value to read (empty array or absent optional parent)`);
      for (const key of result.undeclared) lines.push(`  UNDECLARED ${key}: sent by the daemon, not declared by the console`);
    }
  }
  if (calls.length === 0) failLine("no request<T>() call site found in console/src: the scan itself is broken");
  if (checked.length === 0) failLine("no route was checked: a run that reads nothing is not a pass");
  return { lines, failed, failSlots, checked };
}

// ── the browser wall: reading the console must never open a window ─────────────────────────────
//
// Twice a test opened the operator's real browser (a grok OAuth login on every :app:test; device
// login tabs on 127.0.0.1/verify), and once it read for a day as the DAEMON re-prompting. A check
// that boots a daemon and walks every console route is the likeliest thing to touch an auth
// surface, so after the reads: no process under the booted daemon may be a browser launcher, and
// every login or authorize URL a payload carried is named (by host only: such a URL can carry a
// PKCE challenge) so the assertion is visibly about it.
const LAUNCHER = /(^|\/)(xdg-open|gio|open|sensible-browser|x-www-browser|www-browser)(\0|$)|chrom(e|ium)|firefox/;
const AUTH_URL = /^https?:\/\/[^\s]*(authorize|oauth|login|device|verify)/i;

function descendants(root: number): { pid: number; cmd: string }[] {
  const parent = new Map<number, number>();
  const cmd = new Map<number, string>();
  for (const entry of readdirSync("/proc")) {
    const pid = Number(entry);
    if (!Number.isInteger(pid)) continue;
    try {
      const stat = readFileSync(`/proc/${pid}/stat`, "utf8");
      parent.set(pid, Number(stat.slice(stat.lastIndexOf(")") + 2).split(" ")[1]));
      cmd.set(pid, readFileSync(`/proc/${pid}/cmdline`, "utf8"));
    } catch {
      // the process exited between the listing and the read
    }
  }
  const out: { pid: number; cmd: string }[] = [];
  for (const pid of parent.keys()) {
    for (let at = parent.get(pid); at !== undefined && at > 1; at = parent.get(at)) {
      if (at === root) {
        out.push({ pid, cmd: cmd.get(pid) ?? "" });
        break;
      }
    }
  }
  return out;
}

function authUrls(value: unknown, where: string, out: string[]): void {
  if (typeof value === "string" && AUTH_URL.test(value)) {
    let host = "unparsed";
    try {
      host = new URL(value).host;
    } catch {
      // keep the placeholder: the URL itself is never printed
    }
    out.push(`${where} (host ${host})`);
  } else if (Array.isArray(value)) value.forEach((v, i) => authUrls(v, `${where}[${i}]`, out));
  else if (isRecord(value)) for (const [k, v] of Object.entries(value)) authUrls(v, where === "" ? k : `${where}.${k}`, out);
}

/** FAIL lines for a browser launched under [pid]; INFO lines for the auth URLs the reads carried. */
function browserWall(pid: number, payloads: Payloads): { lines: string[]; failed: boolean } {
  const lines: string[] = [];
  const urls: string[] = [];
  for (const [id, body] of payloads) authUrls(body, id, urls);
  for (const url of urls) lines.push(`AUTH-URL ${url}: carried by a read; no process may be spawned for it`);
  const launched = descendants(pid).filter((p) => LAUNCHER.test(p.cmd));
  for (const p of launched) {
    lines.push(`FAIL browser wall: the booted daemon spawned ${p.cmd.split("\0")[0]} (pid ${p.pid}) while its console routes were read`);
  }
  lines.push(`BROWSER WALL ${launched.length === 0 ? "held" : "BROKEN"}: ${urls.length} auth URL(s) read, ${launched.length} launcher process(es) under pid ${pid}`);
  return { lines, failed: launched.length > 0 };
}

// ── the selftest: prove every checked route can red ───────────────────────────────────────────

function renameTarget(checker: ts.TypeChecker, site: CallSite, body: unknown): string | null {
  const shape = shapeOf(checker, strip(checker, site.type).core);
  if (shape.kind !== "object" || !isRecord(body)) return null;
  const prop = shape.props.find((p) => p.name in body && !optional(checker, p));
  return prop?.name ?? null;
}

/** Renames, in place, the first present non-optional key found inside an array element, and
 *  returns the path the check will print for it. */
function renameNested(checker: ts.TypeChecker, type: ts.Type, value: unknown, where: string, inArray: boolean, depth: number): string | null {
  if (depth > MAX_DEPTH) return null;
  const shape = shapeOf(checker, strip(checker, type).core);
  if (shape.kind === "array" && Array.isArray(value)) {
    for (let i = 0; i < value.length; i += 1) {
      const hit = renameNested(checker, shape.element, value[i], `${where}[${i}]`, true, depth + 1);
      if (hit !== null) return hit;
    }
    return null;
  }
  if (!isRecord(value)) return null;
  const members = shape.kind === "union" ? shape.members.map((m) => shapeOf(checker, m)) : [shape];
  for (const member of members) {
    if (member.kind !== "object") continue;
    for (const prop of member.props) {
      if (!(prop.name in value) || value[prop.name] === null) continue;
      const path = where === "" ? prop.name : `${where}.${prop.name}`;
      if (inArray && !optional(checker, prop)) {
        value[`${prop.name}_renamed`] = value[prop.name];
        delete value[prop.name];
        return path;
      }
      const hit = renameNested(checker, checker.getTypeOfSymbol(prop), value[prop.name], path, inArray, depth + 1);
      if (hit !== null) return hit;
    }
  }
  return null;
}

async function selftest(checker: ts.TypeChecker, calls: CallSite[], fetches: FetchSite[], payloads: Payloads): Promise<boolean> {
  const baseline = await run(checker, calls, fetches, replaySource(payloads));
  // An arm counts only a FAIL line the baseline did not already print, so a red baseline (the
  // defects this check exists to find) cannot make an arm look red for free.
  const known = new Set(baseline.lines.filter((l) => l.startsWith("FAIL ")));
  const fresh = (out: Outcome, prefix: string): string[] =>
    out.lines.filter((l) => l.startsWith(prefix) && !known.has(l));
  let ok = true;
  console.log(
    `SELFTEST unmutated run: ${known.size} finding line(s), excluded from the arms (run without --selftest to read them)`,
  );
  const routes = [...new Set(baseline.checked.map((s) => s.id))];
  let arms = 0;
  for (const id of routes) {
    const site = calls.find((s) => s.id === id);
    const body = payloads.get(id);
    if (site === undefined) continue;
    const key = renameTarget(checker, site, body);
    if (key !== null && isRecord(body)) {
      const renamed = new Map(payloads);
      const { [key]: moved, ...rest } = body;
      renamed.set(id, { ...rest, [`${key}_renamed`]: moved });
      const out = await run(checker, calls, fetches, replaySource(renamed));
      const named = fresh(out, `FAIL ${site.id} `).some((l) => l.includes(`MISSING ${key}:`));
      arms += 1;
      if (!(out.failed && named)) ok = false;
      console.log(`SELFTEST rename ${id} ${key} -> ${key}_renamed: ${out.failed && named ? "red, named" : "NOT RED BY NAME"}`);
    } else {
      console.log(`SELFTEST rename ${id}: no present non-optional top-level key to rename (every key optional)`);
    }
    const clone: unknown = structuredClone(body);
    const nested = renameNested(checker, site.type, clone, "", false, 0);
    if (nested !== null) {
      const renamed = new Map(payloads);
      renamed.set(id, clone);
      const out = await run(checker, calls, fetches, replaySource(renamed));
      const named = fresh(out, `FAIL ${site.id} `).some((l) => l.includes(`MISSING ${nested}:`));
      arms += 1;
      if (!(out.failed && named)) ok = false;
      console.log(`SELFTEST nested rename ${id} ${nested}: ${out.failed && named ? "red, named" : "NOT RED BY NAME"}`);
    } else {
      console.log(`SELFTEST nested rename ${id}: no present non-optional key inside an array element`);
    }
    const emptied = new Map(payloads);
    emptied.set(id, {});
    const out = await run(checker, calls, fetches, replaySource(emptied));
    const vacuous = fresh(out, `FAIL ${site.id} `).length > 0;
    arms += 1;
    if (!(out.failed && vacuous)) ok = false;
    console.log(`SELFTEST empty {} ${id}: ${out.failed && vacuous ? "red" : "NOT RED"}`);
  }
  if (arms === 0) ok = false;
  console.log(`SELFTEST ${ok ? "PASS" : "FAIL"}: ${arms} arm(s) over ${routes.length} checked route(s)`);
  return ok;
}

// ── main ───────────────────────────────────────────────────────────────────────────────────────

function argOf(flag: string): string | undefined {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

async function main(): Promise<number> {
  const program = loadProgram();
  const checker = program.getTypeChecker();
  const { calls, fetches } = enumerate(program);
  const replayDir = argOf("--replay");
  const captureDir = argOf("--capture");
  const payloads: Payloads = new Map();
  if (replayDir !== undefined) {
    for (const site of calls) {
      const file = join(replayDir, `${site.id.replaceAll("/", "__")}.json`);
      if (existsSync(file)) payloads.set(site.id, JSON.parse(readFileSync(file, "utf8")));
    }
    const out = await run(checker, calls, fetches, replaySource(payloads));
    for (const line of out.lines) console.log(line);
    console.log(out.failed ? "console-wire-keys: FAIL" : "console-wire-keys: PASS");
    return out.failed ? 1 : 0;
  }
  const jar = argOf("--boot");
  const daemon = jar !== undefined
    ? await boot(resolve(jar))
    : await attach(argOf("--control") ?? "http://127.0.0.1:3096", argOf("--key-file") ?? liveMgmtKeyFile());
  try {
    const out = await run(checker, calls, fetches, httpSource(daemon, payloads));
    if (daemon.pid !== null) {
      const wall = browserWall(daemon.pid, payloads);
      out.lines.push(...wall.lines);
      if (wall.failed) out.failed = true;
    }
    const grading = process.argv.includes("--selftest");
    if (!grading) for (const line of out.lines) console.log(line);
    if (captureDir !== undefined) {
      mkdirSync(captureDir, { recursive: true });
      for (const [id, body] of payloads) {
        writeFileSync(join(captureDir, `${id.replaceAll("/", "__")}.json`), `${JSON.stringify(body, null, 2)}\n`);
      }
    }
    if (grading) {
      const ok = await selftest(checker, calls, fetches, payloads);
      console.log(ok ? "console-wire-keys selftest: PASS" : "console-wire-keys selftest: FAIL");
      return ok ? 0 : 1;
    }
    console.log(out.failed ? "console-wire-keys: FAIL" : "console-wire-keys: PASS");
    return out.failed ? 1 : 0;
  } finally {
    await daemon.stop();
  }
}

// Behind import.meta.main (the checks/no-python.ts idiom, for its stated reason): importing this
// module must TAKE A READING, not boot a daemon and run the whole wire check. StateDirAgreementTest
// imports liveStateDir to pin it against StatePaths, which is only possible with this guard.
if (import.meta.main) process.exit(await main());
