/**
 * `e2e oracle [replay] [--scenario NAME] [--keep] [--json OUT] [--artifact JAR] [--fixtures DIR]`
 * — GRADE THE KOTLIN GATEWAY AGAINST THE ORACLE. Ported from
 * .dev/campaigns/proxy-hardening/oracle/replay.mjs (restructure PR 5); `e2e oracle capture` and
 * `e2e oracle check` carry capture.mjs's provenance.
 *
 * WHAT  Boots the REAL Kotlin daemon (fat jar) against the SAME vendored mock upstream the
 *       oracle was captured from, drives every frozen fixture's client_request at the codex
 *       head, and byte-compares BOTH wire directions against the recording:
 *
 *           fixture.client_request ──▶ [ Kotlin gateway ] ──▶ observed upstream requests
 *                                          │                        vs expected_upstream_requests
 *                                          ▼
 *                                  observed client SSE  vs  expected_client_sse (canonicalized)
 *
 * DISCIPLINE (mirrors the capture, deliberately):
 *   - the mock is the byte-identical extraction from server/test/codex-proxy.test.mjs, vendored at
 *     fixtures/oracle/mock-upstream.vendored.mjs, and its sha256 MUST match _manifest.json — a
 *     moved or edited mock invalidates the oracle, fail closed;
 *   - canonicalization is the manifest-declared rule set (msg_<digits> -> msg_CANON, and the
 *     gateway's SSE keepalive comment lines dropped), applied to the observed stream exactly as
 *     capture applied it to the recording;
 *   - a divergence is never "close enough": it is a byte diff, and it must be classified in
 *     expectations.toml (kotlin-wrong | sanctioned-with-authority), never suppressed here.
 *     [[divergence]] rows in expectations.toml are honored FIELD-WISE for upstream requests:
 *     a mismatch confined to a sanctioned field whose observed value equals the row's
 *     expected_without_session_header still passes (the runner sends NO session header, so the
 *     frozen fallback bytes are what a faithful gateway must reproduce — see the row's note).
 *   - the fat jar is an INPUT, never built from here (replay.mjs used to fall back to a nested
 *     `./gradlew :app:shadowJar`; inside the gate the gradle slot is already held, so a nested
 *     gradle deadlocks or double-builds). A missing jar is a harness failure that names the
 *     producer.
 *
 * EXIT  0 = every replayed scenario byte-matches (or is sanctioned-field-only); 1 = divergence;
 *       2 = harness failure (mock drift, tampered fixture, missing jar, boot failure) — NOT a
 *       verdict about the gateway.
 *
 * CAPTURE (provenance, INOPERABLE SINCE 2026-08-10 BY DESIGN). capture.mjs drove the LEGACY Node
 * stack (server/) through every scenario its own mock upstream defined and recorded BOTH wire
 * directions byte-exactly into the fixtures; `--check` re-captured to a temp dir and diffed the
 * corpus, the mock's sha256 and the roster both ways (nonzero on ANY drift). P8-CUT deleted
 * server/, so `oracle capture` and `oracle check` exit 2 with that explanation rather than a
 * confusing module-not-found. The fixtures are frozen; re-capturing would require restoring
 * server/ from git history and the capture script
 * (git show a0ae88c3:.dev/campaigns/proxy-hardening/oracle/capture.mjs). That script is what lets
 * this replay claim the fixtures were "not written to flatter the Kotlin port": they were recorded
 * from a known-good implementation (104/104, `cd server && node --test`).
 */
import http from "node:http";
import net from "node:net";
import { execFileSync, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { once } from "node:events";
import {
  closeSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { layout } from "../../../gate/src/lib/repo.ts";

export const usage =
  "oracle [replay] [--scenario NAME] [--keep] [--json OUT] [--artifact JAR] [--fixtures DIR]   " +
  "replay the frozen oracle against the built daemon; `oracle capture|check` are the frozen corpus's provenance";

/** Where the frozen corpus lives: fixtures, manifest, expectations and the vendored mock, flat. */
export const ORACLE_DIR = resolve(import.meta.dir, "../../fixtures/oracle");
/** The fat jar the gate builds; the replay's input, never its product. */
export const DEFAULT_JAR = "app/build/libs/app-all.jar";
const VENDORED_NAME = "mock-upstream.vendored.mjs";
const EXPECTATIONS_NAME = "expectations.toml";
const MANIFEST_NAME = "_manifest.json";
/** The mock region, delimited by markers that have been stable across the port. */
const START = "import http from 'node:http';";
const END = "mock.listen(0, '127.0.0.1');";

const EMPTY_LOCK_STALE_MS = 30_000; // >> the create->write window; only a crashed pre-write lock survives it
const REQUEST_TIMEOUT_MS = 60_000; // a replayed fixture answers in ms; 60s means WEDGED, not slow
const HEAD_PORT = 39490; // CI-hermetic fixed scratch ports (OSS-M pattern)
const CONTROL_PORT = 39491;
export const HARNESS_EXIT = 2;
export const DIVERGENCE_EXIT = 1;

// Two rules, both declared in _manifest.json:
//   1. message ids are canonicalized exactly as capture did;
//   2. the gateway's SSE keepalive COMMENT lines (": ping\n\n", ClientChannel.SSE_KEEPALIVE_COMMENT)
//      are dropped. They are SSE-spec comments, invisible to any consumer, and the reference never
//      wrote them; their COUNT is pure wall clock (a 2s pinger against however long a turn spends
//      in retry backoff), which is why the `failed` row flipped between 2 and 3 of them run to run
//      once the re-anchor budget grew to five (2026-09-03). Real `event: ping` frames stay pinned.
const CANON_RULES: ReadonlyArray<{ re: RegExp; to: string }> = [
  { re: /msg_\d+(?:_\d+)?/g, to: "msg_CANON" },
  { re: /^: ping\n\n/gm, to: "" },
];
export const canonicalize = (t: string): string => CANON_RULES.reduce((x, r) => x.replace(r.re, r.to), t);

class HarnessError extends Error {}

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
interface Fixture {
  scenario: string;
  client_request: Json;
  expected_upstream_requests: Json[];
  expected_client_status: number;
  expected_client_sse: string;
}
interface Manifest {
  mock_region_sha256: string;
  scenarios?: Record<string, { sha256: string }>;
}
export interface DiffEntry {
  path: string;
  exp: unknown;
  obs: unknown;
}
export interface SanctionedField {
  field: string;
  without?: string;
  pinnedValue?: string;
}
export interface SanctionedScenario {
  pinnedSha?: string;
  pinUpstream?: string;
  pinnedUpstreamSha?: string;
}

// ── the vendored mock: same extraction, same integrity gate as capture ──────
function regionFrom(src: string, where: string): string {
  const a = src.indexOf(START);
  const b = src.indexOf(END);
  if (a < 0 || b < 0 || b <= a) throw new HarnessError(`mock markers not found in ${where}`);
  return src.slice(a, b);
}

/** The vendored mock's pinned region and its sha256 — the integrity gate against _manifest.json. */
export function extractMock(oracleDir: string): { region: string; sha256: string } {
  const vendored = join(oracleDir, VENDORED_NAME);
  if (!existsSync(vendored)) throw new HarnessError(`vendored mock missing: ${vendored}`);
  const region = regionFrom(readFileSync(vendored, "utf8"), vendored);
  return { region, sha256: createHash("sha256").update(region).digest("hex") };
}

/**
 * The corpus's integrity checks, shared by the replay and its test: the vendored mock region must
 * hash to the manifest's pin and every enrolled fixture must hash to its recorded sha256. Returns
 * the FATAL diagnostic, or null when the corpus is intact.
 */
export function corpusDrift(oracleDir: string): string | null {
  const manifest = JSON.parse(readFileSync(join(oracleDir, MANIFEST_NAME), "utf8")) as Manifest;
  const { sha256 } = extractMock(oracleDir);
  if (sha256 !== manifest.mock_region_sha256) {
    return (
      `FATAL mock drift: live region ${sha256.slice(0, 16)}… vs manifest ${String(manifest.mock_region_sha256).slice(0, 16)}…\n` +
      "the vendored mock no longer matches what the oracle was captured from — re-examine before trusting any replay"
    );
  }
  // fixture integrity — same NO-SAVED-TRUTH rule the wall enforces
  for (const [name, meta] of Object.entries(manifest.scenarios ?? {})) {
    const digest = createHash("sha256").update(readFileSync(join(oracleDir, `${name}.json`))).digest("hex");
    if (digest !== meta.sha256) return `FATAL fixture tampered: ${name}.json sha256 mismatch vs ${MANIFEST_NAME}`;
  }
  return null;
}

/**
 * The corpus's ENROLMENT checks, shared by the replay and its test — the half that grades the
 * expectations table against the fixture directory rather than against itself.
 *
 * Retired here from cx_19_oracle_replay.ts (2026-09-21). The wall and this function ask the same
 * questions; the difference that matters is WHERE the denominator comes from. `readdirSync` of the
 * fixture directory is the source, so a scenario captured and never enrolled is visible, and so is
 * a row that outlived its fixture. A checker whose denominator is the expectations table cannot
 * fail for what the table omits, which is the whole reason bun#34441 ("wasn't counted and wasn't
 * protected against regression") is quoted at the top of that file.
 *
 * Status is a CLOSED vocabulary. `not-yet-replayed` and `kotlin-wrong` are the honest birth and
 * bug states — they are legitimate to write down and illegitimate to ship, so they red here rather
 * than being silently tolerated. Every disposition carries its reason: a `passing` row cites its
 * proof, a `sanctioned` row cites its authority AND pins the bytes, an `[[excluded]]` row says why
 * it was never frozen. A blank reason is an absence wearing a label.
 *
 * NOT covered, because the table cannot know it: a DELETED [[divergence]] row. Nothing states how
 * many divergences should exist, so this check and the wall it replaces are both green on it. The
 * replay is that row's denominator — the diff it sanctioned goes unsanctioned the moment it leaves
 * (oracle.test.ts pins both directions).
 *
 * Returns the FATAL diagnostic (every problem, one per line), or null when enrolment is sound.
 */
export function enrolmentDrift(oracleDir: string): string | null {
  const text = readExpectations(oracleDir);
  const onDisk = new Set(
    readdirSync(oracleDir)
      .filter((f) => f.endsWith(".json") && f !== MANIFEST_NAME)
      .map((f) => f.replace(/\.json$/, "")),
  );
  const problems: string[] = [];
  const blocks = (tag: string) => text.split(new RegExp(`^\\[\\[${tag}\\]\\]$`, "m")).slice(1);

  const enrolled = new Set<string>();
  for (const block of blocks("scenario")) {
    const name = tomlStr(block, "name");
    if (name === undefined) {
      problems.push("a [[scenario]] row has no name — it can grade nothing");
      continue;
    }
    enrolled.add(name);
    if (!onDisk.has(name)) {
      problems.push(`NO FIXTURE   ${name}: enrolled row names a fixture that is not on disk`);
    }
    const status = tomlStr(block, "status");
    switch (status) {
      case "passing":
        if (!tomlStr(block, "proof")) {
          problems.push(`UNPROVEN     ${name}: passing with no proof — a verdict with nothing behind it`);
        }
        break;
      case "sanctioned":
        if (!tomlStr(block, "authority")) {
          problems.push(`UNCITED      ${name}: sanctioned divergence with no authority`);
        }
        if (!tomlStr(block, "pinned_sha256")) {
          problems.push(`UNPINNED     ${name}: sanctioned divergence with no pinned bytes — an unmonitored hole`);
        }
        break;
      case "not-yet-replayed":
        problems.push(`UNREPLAYED   ${name}: captured but never graded — the fixture is inert (bun#34441)`);
        break;
      case "kotlin-wrong":
        problems.push(`KOTLIN-WRONG ${name}: an open bug, by this file's own definition`);
        break;
      default:
        problems.push(`UNKNOWN      ${name}: status ${status === undefined ? "(absent)" : JSON.stringify(status)} is not one this table defines`);
    }
  }
  for (const block of blocks("excluded")) {
    const name = tomlStr(block, "name");
    if (name === undefined) {
      problems.push("an [[excluded]] row has no name — nothing is excluded by it");
      continue;
    }
    enrolled.add(name);
    if (onDisk.has(name)) {
      problems.push(`CONTRADICTED ${name}: excluded from the corpus, yet a fixture for it is on disk`);
    }
    if (!tomlStr(block, "reason")) {
      problems.push(`UNEXPLAINED  ${name}: excluded with no reason — a disposition with nothing in it`);
    }
  }
  for (const block of blocks("divergence")) {
    const field = tomlStr(block, "field");
    if (field === undefined) {
      problems.push("a [[divergence]] row names no field — it sanctions nothing");
      continue;
    }
    // Same law as a sanctioned scenario, through the same branch: cite what authorised it, pin the
    // expected bytes. Before 2026-08-07 a loader that read only the scenario array made a
    // divergence block a comment with TOML syntax.
    if (tomlStr(block, "status") !== "sanctioned") {
      problems.push(`UNKNOWN      ${field}: a [[divergence]] row exists only to sanction, so its status must say so`);
      continue;
    }
    if (!tomlStr(block, "authority")) {
      problems.push(`UNCITED      ${field}: sanctioned divergence with no authority`);
    }
    if (!tomlStr(block, "pinned_sha256")) {
      problems.push(`UNPINNED     ${field}: sanctioned divergence with no pinned bytes`);
    }
  }
  for (const name of [...onDisk].sort()) {
    if (!enrolled.has(name)) {
      // The remedy is named because the obvious one is catastrophic: `oracle capture` has been
      // INOPERABLE since server/ was cut on 2026-08-10, so a fixture deleted to make a gate green
      // is a recording nobody can re-take. Enrol it or exclude it; never delete it.
      problems.push(
        `UNENROLLED   ${name}: fixture on disk with no expectations row — captured and unprotected. ` +
          "Add a [[scenario]] row, or an [[excluded]] row with a reason — do NOT delete the fixture, " +
          "it cannot be re-captured",
      );
    }
  }
  return problems.length === 0 ? null : `FATAL enrolment drift:\n  ${problems.join("\n  ")}`;
}

/** True when something is already listening — used to fail closed on a leaked daemon. */
function portInUse(port: number): Promise<boolean> {
  return new Promise((res) => {
    const sock = new net.Socket();
    sock.setTimeout(400);
    sock.once("connect", () => {
      sock.destroy();
      res(true);
    });
    sock.once("timeout", () => {
      sock.destroy();
      res(false);
    });
    sock.once("error", () => res(false));
    sock.connect(port, "127.0.0.1");
  });
}

/** Atomic exclusive run lock via O_EXCL (portable; node has no flock). Returns true on acquire.
 *  A lock naming a DEAD pid is reclaimed — an interrupted run that never ran its exit handler must
 *  not wedge every future run. A lock held by a live pid, or one too young to rule out a competing
 *  run mid-create, → refuse (F6). */
function acquireRunLock(lockPath: string): boolean {
  try {
    const fd = openSync(lockPath, "wx"); // wx = O_CREAT|O_EXCL: fails if the file exists
    writeFileSync(fd, String(process.pid));
    closeSync(fd);
    return true;
  } catch {
    // FAIL CLOSED on an unreadable owner. openSync+writeFileSync are two syscalls, so a competing
    // run can read this file in the microseconds after it is CREATED and before the pid lands.
    // Treating that empty read as "no owner => dead" let the second run delete a LIVE run's lock
    // and proceed, putting both into the preflight where one SIGKILLs the other's daemon as
    // "leaked" — reinstating the mutual assassination this lock was written to stop.
    // Only a lock naming a pid that is genuinely gone is reclaimable; an empty lock is reclaimed
    // solely once it is old enough that no in-flight create could still be mid-write.
    const raw = runCatchRead(lockPath).trim();
    const owner = Number(raw);
    const reclaimable = raw === "" ? ageMs(lockPath) > EMPTY_LOCK_STALE_MS : Number.isInteger(owner) && owner > 0 && !pidAlive(owner);
    if (!reclaimable) return false;
    try {
      rmSync(lockPath, { force: true });
    } catch {
      /* raced */
    }
    try {
      const fd = openSync(lockPath, "wx");
      writeFileSync(fd, String(process.pid));
      closeSync(fd);
      return true;
    } catch {
      return false;
    }
  }
}

/** Age of [p] in ms; Infinity when it cannot be stat'd (a vanished lock is maximally stale). */
function ageMs(p: string): number {
  try {
    return Date.now() - statSync(p).mtimeMs;
  } catch {
    return Infinity;
  }
}
function runCatchRead(p: string): string {
  try {
    return readFileSync(p, "utf8");
  } catch {
    return "";
  }
}
function pidAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === "EPERM";
  }
}

/** SIGKILL a leaked ORACLE daemon holding [port]; true if one was killed. Scoped by cmdline to
 *  the build-tree jar (app-all.jar) — the production daemon runs ~/.local/share/splice/splice.jar,
 *  so the real gateway on :3099/:3096 is unreachable from here by construction. Only reached while
 *  we hold the run lock, so a squatter here is a prior interrupted run, never a live concurrent one. */
function killLeakedOracleDaemon(port: number): boolean {
  try {
    const out = execFileSync("ss", ["-ltnpH", `( sport = :${port} )`], { encoding: "utf8" });
    let killed = false;
    for (const pid of new Set([...out.matchAll(/pid=(\d+)/g)].map((m) => m[1]!))) {
      const cmd = readFileSync(`/proc/${pid}/cmdline`, "utf8").replace(/\0/g, " ");
      if (cmd.includes("app-all.jar") && cmd.includes("daemon")) {
        console.error(`[preflight] killing oracle daemon pid ${pid} leaked by an earlier run (held :${port})`);
        process.kill(Number(pid), "SIGKILL");
        killed = true;
      }
    }
    return killed;
  } catch {
    return false;
  }
}

// ── minimal field-wise deep diff for upstream request objects ────────────────
export function jsonDiff(exp: unknown, obs: unknown, path = "", out: DiffEntry[] = []): DiffEntry[] {
  if (typeof exp !== typeof obs || (exp === null) !== (obs === null)) {
    out.push({ path, exp, obs });
    return out;
  }
  if (Array.isArray(exp)) {
    if (!Array.isArray(obs) || exp.length !== obs.length) {
      out.push({ path: `${path}.length`, exp: exp.length, obs: Array.isArray(obs) ? obs.length : typeof obs });
      return out;
    }
    exp.forEach((v, i) => jsonDiff(v, obs[i], `${path}[${i}]`, out));
    return out;
  }
  if (exp && typeof exp === "object") {
    const e = exp as Record<string, unknown>;
    const o = (obs ?? {}) as Record<string, unknown>;
    for (const k of new Set([...Object.keys(e), ...Object.keys(o)])) {
      if (!(k in e)) out.push({ path: `${path}.${k}`, exp: "<absent>", obs: o[k] });
      else if (!(k in o)) out.push({ path: `${path}.${k}`, exp: e[k], obs: "<absent>" });
      else jsonDiff(e[k], o[k], `${path}.${k}`, out);
    }
    return out;
  }
  if (exp !== obs) out.push({ path, exp, obs });
  return out;
}

// expectations.toml rows, parsed leniently (comments carry the prose; we only need the machine
// fields). A full TOML parser is deliberately NOT vendored; the wall re-parses with tomllib.
function tomlStr(block: string, key: string): string | undefined {
  return (
    new RegExp(`^${key}\\s*=\\s*"([^"]+)"`, "m").exec(block)?.[1] ??
    new RegExp(`^${key}\\s*=\\s*'([^']+)'`, "m").exec(block)?.[1]
  );
}
export function sanctionedFields(text: string): SanctionedField[] {
  const rows: SanctionedField[] = [];
  for (const block of text.split(/^\[\[divergence\]\]$/m).slice(1)) {
    const field = tomlStr(block, "field");
    if (field && tomlStr(block, "status") === "sanctioned") {
      const row: SanctionedField = { field };
      const without = tomlStr(block, "expected_without_session_header");
      const pinnedValue = tomlStr(block, "pinned_value");
      if (without !== undefined) row.without = without;
      if (pinnedValue !== undefined) row.pinnedValue = pinnedValue;
      rows.push(row);
    }
  }
  return rows;
}
export function sanctionedScenarios(text: string): Record<string, SanctionedScenario> {
  const rows: Record<string, SanctionedScenario> = {};
  for (const block of text.split(/^\[\[scenario\]\]$/m).slice(1)) {
    const name = tomlStr(block, "name");
    if (name && tomlStr(block, "status") === "sanctioned") {
      const row: SanctionedScenario = {};
      const pinnedSha = tomlStr(block, "pinned_sha256");
      const pinUpstream = tomlStr(block, "pin_upstream");
      const pinnedUpstreamSha = tomlStr(block, "pinned_upstream_sha256");
      if (pinnedSha !== undefined) row.pinnedSha = pinnedSha;
      if (pinUpstream !== undefined) row.pinUpstream = pinUpstream;
      if (pinnedUpstreamSha !== undefined) row.pinnedUpstreamSha = pinnedUpstreamSha;
      rows[name] = row;
    }
  }
  return rows;
}
function readExpectations(oracleDir: string): string {
  const p = join(oracleDir, EXPECTATIONS_NAME);
  return existsSync(p) ? readFileSync(p, "utf8") : "";
}

// a diff entry is sanctioned iff its path matches a sanctioned field pattern
// (pattern like "expected_upstream_requests[].prompt_cache_key" -> path suffix ".prompt_cache_key")
// AND the observed value equals the row's pin — a sanction is still a pin, never a wildcard.
export function isSanctioned(entry: DiffEntry, sanctioned: readonly SanctionedField[]): boolean {
  return sanctioned.some((s) => {
    const leaf = s.field.split(".").pop();
    if (!entry.path.endsWith(`.${leaf}`)) return false;
    if (s.pinnedValue !== undefined) return JSON.stringify(entry.obs) === s.pinnedValue;
    // A sanction with NO runner-readable pin sanctioned EVERY observed value at that leaf, at any
    // depth — the wildcard the comment above says cannot exist. It passed the wall too, because the
    // wall checks `pinned_sha256` while the runner reads `pinned_value` / the without-header value:
    // two checkers, different fields, both green. Fail closed here; the wall now enforces that the
    // sha256 is the hash OF the runner-readable pin, so the two can no longer drift apart.
    if (s.without === undefined) return false;
    return entry.obs === s.without;
  });
}

export function gradeUpstream(expected: Json[], observed: unknown[], sanctioned: readonly SanctionedField[], problems: string[]): void {
  if (observed.length !== expected.length) {
    problems.push(`upstream request count: expected ${expected.length}, got ${observed.length}`);
    return;
  }
  expected.forEach((expReq, i) => {
    for (const d of jsonDiff(expReq, observed[i], `upstream[${i}]`)) {
      if (!isSanctioned(d, sanctioned)) {
        problems.push(`${d.path}: expected ${JSON.stringify(d.exp)?.slice(0, 120)}, got ${JSON.stringify(d.obs)?.slice(0, 120)}`);
      }
    }
  });
}

function post(port: number, body: Json, bearer: string, path = "/v1/messages"): Promise<{ status: number; sse: string }> {
  // Bearer = the daemon's mgmt key: HeadServer.authorize admits it for a turn beside the turn key
  // launched wrappers receive as ANTHROPIC_AUTH_TOKEN (v0.4.0). NO x-claude-code-session-id on purpose — the
  // frozen fixtures carry no headers, so the cache-key fallback must reproduce (divergence note).
  return new Promise((res, rej) => {
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path,
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` },
        // Without this the harness HANGS FOREVER on exactly the failure it exists to detect:
        // the 91h wedge accepted every connection and answered none, so an untimed read waits
        // out the heat death rather than reporting the wedge. Generous enough that a slow-but-
        // working replay never trips it.
        timeout: REQUEST_TIMEOUT_MS,
      },
      (r) => {
        let t = "";
        r.on("data", (c) => {
          t += c;
        });
        r.on("end", () => res({ status: r.statusCode ?? 0, sse: t }));
      },
    );
    req.on("timeout", () => {
      req.destroy(new HarnessError(`no response from :${port}${path} in ${REQUEST_TIMEOUT_MS}ms — accepted but never answered (the wedge signature)`));
    });
    req.on("error", rej);
    req.end(JSON.stringify(body));
  });
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
async function waitHttp(port: number, path: string, tries = 120): Promise<void> {
  for (let i = 0; i < tries; i++) {
    try {
      await new Promise<void>((res, rej) => {
        const q = http.get({ host: "127.0.0.1", port, path, timeout: 1000 }, (r) => {
          r.resume();
          res();
        });
        q.on("error", rej);
        q.on("timeout", () => {
          q.destroy();
          rej(new Error("t/o"));
        });
      });
      return;
    } catch {
      await sleep(250);
    }
  }
  throw new HarnessError(`nothing answering on :${port}${path} after ${tries / 4}s`);
}

interface ReplayOptions {
  oracleDir: string;
  jar: string;
  only: string | null;
  keep: boolean;
  jsonOut: string | null;
}

/** The provenance arms. capture.mjs exited 2 here before doing anything else, and so does this. */
function provenance(verb: "capture" | "check"): number {
  console.error(
    `oracle ${verb} is INOPERABLE — server/ was deleted on 2026-08-10 (P8-CUT).\n` +
      "The 11 fixtures it produced are frozen and still verified by `bun tools/e2e oracle` (npm run oracle:replay).\n" +
      "The capture script is kept as their provenance (git show a0ae88c3:.dev/campaigns/proxy-hardening/oracle/capture.mjs).\n" +
      "To re-capture, restore server/ from git history first.",
  );
  return HARNESS_EXIT;
}

export async function oracle(argv: readonly string[]): Promise<number> {
  const args = [...argv];
  const verb = args[0] && !args[0].startsWith("-") ? args.shift()! : "replay";
  if (verb === "capture" || verb === "check") return provenance(verb);
  if (verb !== "replay") {
    console.error(`e2e oracle: no such arm "${verb}" — expected replay, capture or check`);
    return HARNESS_EXIT;
  }
  const flag = (n: string) => args.includes(n);
  // A value-taking option that is present but valueless (last on the line, followed by another
  // option, or an empty string) is refused HERE: read as absent, `--artifact` alone silently judged the default jar and
  // `--fixtures` alone the frozen corpus, and a replay against the wrong input reads as a verdict.
  const valueless: string[] = [];
  const opt = (n: string): string | null => {
    const i = args.indexOf(n);
    if (i < 0) return null;
    const value = args[i + 1];
    if (value === undefined || value.length === 0 || value.startsWith("-")) {
      valueless.push(n);
      return null;
    }
    return value;
  };
  const fixtures = opt("--fixtures");
  const artifact = opt("--artifact");
  const only = opt("--scenario");
  const jsonOut = opt("--json");
  if (valueless.length > 0) {
    console.error(`e2e oracle: ${valueless.join(", ")} ${valueless.length === 1 ? "takes" : "take"} a value — a bare or empty option is refused, never read as the default`);
    return HARNESS_EXIT;
  }
  const options: ReplayOptions = {
    oracleDir: fixtures !== null ? resolve(fixtures) : ORACLE_DIR,
    jar: artifact !== null ? resolve(artifact) : join(layout().buildRoot, DEFAULT_JAR),
    only,
    keep: flag("--keep"),
    jsonOut,
  };
  try {
    return await replay(options);
  } catch (e) {
    // A harness-tagged throw is a HARNESS problem, not a gateway verdict — report it the same way
    // the in-run failures are reported, so a leaked-port refusal reads as infrastructure and never
    // as "the gateway diverged".
    if (e instanceof HarnessError) {
      console.error(`HARNESS FAILURE: ${e.message}`);
      console.error("\nreplay: 0/0 scenarios byte-match the oracle (HARNESS FAILURE — not a gateway verdict)");
    } else {
      console.error(e);
    }
    return HARNESS_EXIT;
  }
}

async function replay({ oracleDir, jar, only, keep, jsonOut }: ReplayOptions): Promise<number> {
  // Integrity first (are the bytes the captured bytes), then enrolment (is every captured scenario
  // actually being graded). Both are harness failures, not gateway verdicts: a corpus that cannot
  // be trusted produces no verdict at all.
  const drift = corpusDrift(oracleDir) ?? enrolmentDrift(oracleDir);
  if (drift !== null) {
    console.error(drift);
    return HARNESS_EXIT;
  }
  const { region } = extractMock(oracleDir);

  // The jar is an input. The gate builds it (clean check runs :app:shadowJar) and holds the gradle
  // slot while this runs, so building it from here would be a nested gradle under a held slot.
  if (!existsSync(jar)) {
    throw new HarnessError(`fat jar missing at ${jar} — build it first (bun tools/gate slot <label> -- :app:shadowJar) or pass --artifact`);
  }

  const tmp = mkdtempSync(join(tmpdir(), "splice-replay-"));
  const mockPath = join(tmp, "vendored_mock.mjs");
  writeFileSync(
    mockPath,
    region + `\nmock.listen(0, '127.0.0.1');\nexport { mock, upstreamAuths, upstreamBodies, abortedScenarios, AUTH_PATH, stateRoot };\n`,
  );
  const m = (await import(pathToFileURL(mockPath).href)) as {
    mock: http.Server;
    upstreamBodies: Array<{ body: unknown }>;
    AUTH_PATH: string;
  };
  // The captured mock predates every GET the daemon now sends this origin — the quota poller's
  // /backend-api/wham/usage (2026-09-02), the model discovery's /models (2026-09-22) — and it
  // JSON-parses every request body, so an empty GET body threw inside it and the request was never
  // answered: the discovery GET then held the daemon's boot for its whole timeout, past the 30s
  // health wait. The vendored handler serves only POSTs, so EVERY GET is answered 404 here, BEFORE
  // it: the replay keeps its captured shape (no quota snapshot, no discovered models, no extra
  // upstream request recorded) and the pinned mock region is untouched.
  const vendoredHandler = m.mock.listeners("request")[0] as (req: http.IncomingMessage, res: http.ServerResponse) => void;
  m.mock.removeAllListeners("request");
  m.mock.on("request", (req, res) => {
    if (req.method === "GET") {
      res.writeHead(404);
      res.end();
      return;
    }
    vendoredHandler(req, res);
  });
  await once(m.mock, "listening");
  const mockPort = (m.mock.address() as net.AddressInfo).port;

  mkdirSync(join(tmp, "state"), { recursive: true });
  writeFileSync(
    join(tmp, "splice.toml"),
    `# hermetic replay topology — generated by tools/e2e oracle, mirrors capture env exactly
[daemon]
control_port = ${CONTROL_PORT}
show_reasoning = "text"
summary = "detailed"
effort = "high"
replay_reasoning = true

[providers.codex]
dialect = "openai-responses"
base_url = "http://127.0.0.1:${mockPort}"
auth = { kind = "chatgpt-oauth", file = "${m.AUTH_PATH}" }
quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true }

[[providers.codex.models]]
id = "gpt-5-codex"
label = "Codex (oracle)"
# 272000 = the legacy Node resolveContextWindow DEFAULT (server/test/launcher.test.mjs:74) —
# the capture-time reference had no roster entry for gpt-5-codex, so the oracle's message_start
# usage rides that default and a faithful replay must too.
context_window = 272000

[heads.claudex]
provider = "codex"
port = ${HEAD_PORT}
discovery_prefix = "claude-codex--"
pinned_model = "gpt-5-codex"
[heads.claudex.claude]
command = "claudex"
`,
  );

  const env: Record<string, string> = Object.fromEntries(
    Object.entries(process.env).filter((kv): kv is [string, string] => kv[1] !== undefined && !/^(CLAUDEX_|CODEX_|SPLICE_|CHATGPT_)/.test(kv[0])),
  );
  Object.assign(env, {
    SPLICE_CONFIG: join(tmp, "splice.toml"),
    CLAUDEX_STATE_DIR: join(tmp, "state"),
    CLAUDEX_STREAM_IDLE_MS: "700",
    CLAUDEX_UPSTREAM_RETRIES: "2",
    CLAUDEX_SHOW_REASONING: "text",
    CLAUDEX_REASONING_EFFORT: "high",
    CLAUDEX_REASONING_SUMMARY: "detailed",
    CLAUDEX_REPLAY_REASONING: "1",
    CODEX_OAUTH_TOKEN_URL: `http://127.0.0.1:${mockPort}/oauth/token`,
  });

  // EXCLUSIVE RUN LOCK (F6, 2026-08-12). The ports are FIXED and shared, so two concurrent runs
  // cannot coexist. Before this lock the second run's preflight SIGKILLed the FIRST run's LIVE
  // daemon (mislabelled "leaked"), so two CI jobs on one runner mutually assassinated at ~50%.
  // An exclusive, non-blocking lock makes concurrent = fail-fast-and-refuse; only after we hold it
  // does the preflight treat a port-squatter as a genuine leak from an interrupted PRIOR run.
  const lockPath = join(tmpdir(), "splice-oracle-replay.lock");
  if (!acquireRunLock(lockPath)) {
    throw new HarnessError("another oracle replay holds the run lock — concurrent runs share fixed ports; retry when it finishes");
  }
  process.on("exit", () => {
    try {
      rmSync(lockPath, { force: true });
    } catch {
      /* already gone */
    }
  });

  // PREFLIGHT (2026-08-11). Now that we hold the lock, any daemon still on these ports is a leak
  // from an interrupted PRIOR run, never a live concurrent one. If the squatter is OUR OWN oracle
  // daemon (app-all.jar cmdline — production runs splice.jar, so this can never touch the real
  // gateway), kill it and continue. Anything else on the port: refuse loudly.
  for (const port of [HEAD_PORT, CONTROL_PORT]) {
    if (!(await portInUse(port))) continue;
    if (killLeakedOracleDaemon(port)) {
      for (let i = 0; i < 30 && (await portInUse(port)); i++) await sleep(100);
    }
    if (await portInUse(port)) {
      throw new HarnessError(`port ${port} is held by a process that is not a leaked oracle daemon — refusing to validate against another process's gateway`);
    }
  }

  const logFd = join(tmp, "daemon.stdout.log");
  const daemon = spawn("java", ["-Xmx1024m", "-jar", jar, "daemon"], { env, stdio: ["ignore", "pipe", "pipe"] });
  let dlog = "";
  daemon.stdout.on("data", (c) => {
    dlog += c;
  });
  daemon.stderr.on("data", (c) => {
    dlog += c;
  });
  const dead = new Promise<number | null>((r) => daemon.once("exit", (code) => r(code)));
  // LAST LINE OF DEFENSE against a leaked daemon. process.on('exit') covers a normal return and
  // an uncaught throw (the zstd-crash shape), but NOT a signal — node's default SIGINT/SIGTERM
  // termination skips 'exit' handlers (verified 2026-08-12). So Ctrl-C mid-run — the exact
  // "earlier interrupted run" the preflight self-heal exists for — needs its own handlers. With
  // both, an interrupted run kills its own daemon; the preflight is the backstop, not the only net.
  const killDaemon = () => {
    try {
      daemon.kill("SIGKILL");
    } catch {
      /* already gone */
    }
  };
  process.on("exit", killDaemon);
  for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
    process.on(sig, () => {
      killDaemon();
      process.exit(130);
    });
  }
  // A rejecting twin of `dead` for the boot race ONLY. It must be marked handled immediately:
  // if health wins the race, the daemon's LATER exit (including our own SIGTERM in cleanup)
  // still triggers this rejection, and an unhandled rejection is fatal in modern node — the
  // process died mid-cleanup with a bare "Node.js v24.16.0" tail, a 0-byte daemon.stdout.log,
  // and a leaked daemon holding the fixed ports. That was the entire leak chain.
  const deadBeforeHealthy = dead.then((code) => {
    throw new HarnessError(`daemon exited (${code}) before healthy`);
  });
  deadBeforeHealthy.catch(() => {}); // mark handled; the race keeps its own rejecting reference

  interface Verdict {
    pass: boolean;
    sanctioned?: boolean;
    problems: string[];
    observed_status: number;
    observed_sse: string;
    observed_upstream: unknown[];
  }
  const verdicts: Record<string, Verdict> = {};
  let exit = 0;
  try {
    await Promise.race([
      // Head waits carry the same 30s budget as control: right after gate.sh's `clean check` +
      // inline shadowJar the JVM boots under full gradle-daemon load, and the head listener
      // (which binds AFTER control) blew a 10s ceiling — a slow boot is not a dead daemon.
      (async () => {
        await waitHttp(CONTROL_PORT, "/health");
        await waitHttp(HEAD_PORT, "/health").catch(() => waitHttp(HEAD_PORT, "/"));
      })(),
      deadBeforeHealthy,
    ]);

    const bearer = readFileSync(join(tmp, "state", "mgmt-key"), "utf8").trim();
    const expectations = readExpectations(oracleDir);
    const sanctioned = sanctionedFields(expectations);
    const roster = readdirSync(oracleDir)
      .filter((f) => f.endsWith(".json") && f !== MANIFEST_NAME)
      .map((f) => f.replace(/\.json$/, ""))
      .sort();

    const sanctionedRows = sanctionedScenarios(expectations);
    // A typo'd --scenario used to skip every iteration and exit 0 on "0/0 byte-match": a
    // verification gate whose vacuous case is GREEN. Name it before grading anything.
    if (only && !roster.includes(only)) {
      throw new HarnessError(`--scenario ${only} is not in the roster (${roster.join(", ")})`);
    }
    for (const name of roster) {
      if (only && name !== only) continue;
      const fx = JSON.parse(readFileSync(join(oracleDir, `${name}.json`), "utf8")) as Fixture;
      const before = m.upstreamBodies.length;
      const out = await post(HEAD_PORT, fx.client_request, bearer);
      const observedUpstream = m.upstreamBodies.slice(before).map((x) => x.body);
      const problems: string[] = [];

      // A sanctioned scenario pins BOTH directions. Rows whose upstream request remains reference-
      // compatible set pin_upstream="fixture" and keep the ordinary deep diff; rows such as truncated
      // deliberately changed request count/shape and pin the authorized upstream array by hash.
      const sanction = sanctionedRows[name];
      if (sanction?.pinnedSha) {
        const gotSse = canonicalize(out.sse);
        const sha = createHash("sha256").update(gotSse).digest("hex");
        if (out.status !== fx.expected_client_status) problems.push(`client status: expected ${fx.expected_client_status}, got ${out.status}`);
        if (sha !== sanction.pinnedSha) {
          problems.push(`sanctioned bytes drifted: pinned ${sanction.pinnedSha.slice(0, 16)}…, observed ${sha.slice(0, 16)}… — the divergence is no longer the one that was authorised`);
        }
        if (sanction.pinUpstream === "fixture") {
          gradeUpstream(fx.expected_upstream_requests, observedUpstream, sanctioned, problems);
        } else if (sanction.pinnedUpstreamSha) {
          const upstreamSha = createHash("sha256").update(JSON.stringify(observedUpstream)).digest("hex");
          if (upstreamSha !== sanction.pinnedUpstreamSha) {
            problems.push(`sanctioned upstream bytes drifted: pinned ${sanction.pinnedUpstreamSha.slice(0, 16)}…, observed ${upstreamSha.slice(0, 16)}…`);
          }
        } else {
          problems.push("sanctioned scenario has no upstream pin — refusing a one-direction-only verdict");
        }
        verdicts[name] = { pass: problems.length === 0, sanctioned: true, problems, observed_status: out.status, observed_sse: gotSse, observed_upstream: observedUpstream };
        console.log(problems.length === 0 ? `  ✓ ${name} (sanctioned — pinned bytes hold)` : `  ✗ ${name}`);
        for (const p of problems) console.log(`      ${p}`);
        if (problems.length) exit = DIVERGENCE_EXIT;
        continue;
      }

      if (out.status !== fx.expected_client_status) problems.push(`client status: expected ${fx.expected_client_status}, got ${out.status}`);

      const gotSse = canonicalize(out.sse);
      if (gotSse !== fx.expected_client_sse) {
        const a = fx.expected_client_sse;
        const b = gotSse;
        let i = 0;
        while (i < Math.min(a.length, b.length) && a[i] === b[i]) i++;
        problems.push(`client SSE diverges at byte ${i} (expected ${a.length}B, got ${b.length}B)`);
        problems.push(`  expected …${JSON.stringify(a.slice(Math.max(0, i - 60), i + 100))}`);
        problems.push(`  observed …${JSON.stringify(b.slice(Math.max(0, i - 60), i + 100))}`);
      }

      gradeUpstream(fx.expected_upstream_requests, observedUpstream, sanctioned, problems);

      verdicts[name] = { pass: problems.length === 0, problems, observed_status: out.status, observed_sse: gotSse, observed_upstream: observedUpstream };
      console.log(problems.length === 0 ? `  ✓ ${name}` : `  ✗ ${name}`);
      for (const p of problems) console.log(`      ${p}`);
      if (problems.length) exit = DIVERGENCE_EXIT;
    }
  } catch (e) {
    console.error(`HARNESS FAILURE: ${(e as Error).message}`);
    exit = HARNESS_EXIT;
  } finally {
    // SIGTERM, bounded wait, then SIGKILL — and only trust the PORTS, not kill()'s return.
    daemon.kill("SIGTERM");
    await Promise.race([dead, sleep(5000)]);
    if (daemon.exitCode === null) daemon.kill("SIGKILL");
    await Promise.race([dead, sleep(2000)]);
    for (let i = 0; i < 20 && ((await portInUse(HEAD_PORT)) || (await portInUse(CONTROL_PORT))); i++) await sleep(100);
    if (await portInUse(HEAD_PORT)) {
      console.error(`WARNING: :${HEAD_PORT} still held after cleanup — the next replay will self-heal it.`);
    }
    m.mock.close();
    writeFileSync(logFd, dlog);
  }

  if (jsonOut) writeFileSync(jsonOut, JSON.stringify({ replayed_at: new Date().toISOString(), verdicts }, null, 2) + "\n");

  const total = Object.keys(verdicts).length;
  const passed = Object.values(verdicts).filter((v) => v.pass).length;
  // Grading NOTHING is not passing. An emptied/renamed fixtures dir, or any future filter that
  // matches no scenario, otherwise prints "0/0 byte-match" and exits 0 — the gate certifying a
  // run in which it verified nothing at all.
  if (total === 0) {
    console.error("HARNESS FAILURE: no scenario was graded — the oracle verified nothing");
    console.log("\nreplay: 0/0 scenarios byte-match the oracle (HARNESS FAILURE — not a gateway verdict)");
    if (keep) console.log(`scratch kept for post-mortem: ${tmp} (daemon log: ${logFd})`);
    return HARNESS_EXIT;
  }
  console.log(`\nreplay: ${passed}/${total} scenarios byte-match the oracle${exit === HARNESS_EXIT ? " (HARNESS FAILURE — not a gateway verdict)" : ""}`);
  if (exit !== 0 || keep) console.log(`scratch kept for post-mortem: ${tmp} (daemon log: ${logFd})`);
  else rmSync(tmp, { recursive: true, force: true });
  return exit;
}
