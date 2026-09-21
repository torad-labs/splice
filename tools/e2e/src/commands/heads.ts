/**
 * `e2e heads [--tier 1|2|all|perf-oracle|mcp-oracle|plant-oracle] [--head KEY] [--list] [--selftest]`
 * `e2e heads reasoning-cache` (alias `--probe reasoning-cache`)
 * — FULL-STACK E2E OVER EVERY CONFIGURED HEAD (codex, grok, kimi, ...), plus the reasoning-cache
 * wire probe. Merged from checks/e2e/heads-e2e.sh, checks/e2e/heads-e2e-selftest.sh and
 * checks/e2e/reasoning-cache-probe.sh (restructure PR 5): one verb, no shell, and the whole
 * selftest suite is now bun test arms in tools/e2e/test/heads.test.ts, reachable as `--selftest`.
 *
 * Head-agnostic by design: heads are DISCOVERED from the live daemon (/api/heads), so adding a
 * head to ~/.config/splice/splice.toml makes it run here with zero harness changes. Discovery is
 * the ONLY roster: a hardcoded want-list of "interesting but absent" heads used to sit here and
 * rotted into a false report — it named `kimi` while the configured key is `claude-kimi`
 * (splice.toml:196), so every full run printed "kimi: no head configured" about a head that
 * exists and works. A head that is genuinely missing is missing from splice.toml, which is the
 * operator's own file; the harness has no business second-guessing it.
 *
 *   tier 1  wire probe   — real streaming turn straight at the head port; validates the Anthropic
 *                          SSE contract + latency budgets client-side (stream_probe.ts), plus a
 *                          count_tokens sanity call. Cheap, provider-billed, seconds per head.
 *   tier 2  tmux drive   — launches the head's REAL Claude Code wrapper (claudex / claude-grok /
 *                          claude-kimi …) inside an isolated tmux server, answers first-run
 *                          prompts, plants an OPERATOR-SHAPED tool surface (a real stdio MCP
 *                          server whose composed tool name is over 64 chars — see
 *                          plantOverlongMcp), sends live prompts, asserts the answers render
 *                          and that the tool surface really formed, then runs the perf-JSONL
 *                          oracle (perfRowsOk) over the drive window.
 *
 * COST — tier 2 spends REAL provider quota on EVERY head, deliberately and without a gate,
 * including a client-auth head. That is not an oversight of tier 1's credential gate: the two
 * protect different things. Tier 1's gate exists because probing a client-auth head with $MGMT
 * would ship the daemon's own management key to the vendor (see probeBearer) — a LEAK. Tier 2
 * cannot leak it (LaunchService withholds ANTHROPIC_AUTH_TOKEN from such a head, so the wrapper
 * rides the operator's own `claude` login), it only spends. Every other head tier 2 drives spends
 * an OAuth subscription too, so gating the client-auth one alone would single out a cost that is
 * already universal. Instead the spend is announced per head at dispatch time — see tier2().
 *
 *   (perf-oracle:  selftest hook — tier 2's perf gate alone, over E2E_PERF_SINCE/E2E_PERF_WANT)
 *   (mcp-oracle:   selftest hook — tier 2's tool-surface gate alone, over E2E_MCP_SCRATCH)
 *   (plant-oracle: selftest hook — plant the MCP server + its enable settings into E2E_MCP_SCRATCH)
 * Env:
 *   E2E_TTFB_MS / E2E_FIRST_DELTA_MS / E2E_TOTAL_MS / E2E_GAP_MS   latency budgets (ms)
 *   E2E_MODEL_<HEADKEY>   full discovery model id override (default: cheapest-looking row)
 *   E2E_CHEAP_MODEL_RE    override the cheap-tier model regex (default below)
 *   E2E_KEEP_TMUX=1       keep the tmux session + scratch dir on failure for post-mortem
 *   E2E_RECEIPT_DIR       redirect tier-1 receipt emission (DR-111 — see emitReceipt)
 *   SPLICE_E2E_CLIENT_TOKEN  a REAL caller credential for client-auth heads. Without it those
 *                         heads SKIP tier 1 rather than be probed with the mgmt key — see
 *                         probeBearer() for why that would ship the key to the vendor. Setting
 *                         it to the mgmt key is a FATAL preflight error, not a shortcut.
 *
 * EXIT  0 = no FAIL row (a SKIP is not a FAIL); 1 = a FAIL row, or a harness-FATAL; 2 = bad argv,
 *       or a missing fat jar on the reasoning-cache arm (the same HARNESS_EXIT vocabulary as
 *       `e2e oracle` and `e2e code-mode`). No command here calls process.exit — the verb returns
 *       a status to tools/e2e/index.ts, and a signalled child reports as a shell would
 *       (128+signum, tools/gate/src/lib/status.ts).
 */
import {
  appendFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import net from "node:net";
import { homedir, tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { exitStatusOf } from "../../../gate/src/lib/status.ts";
import { layout } from "../../../gate/src/lib/repo.ts";

export const usage =
  "heads [--tier 1|2|all|perf-oracle|mcp-oracle|plant-oracle] [--head KEY] [--list] [--selftest] [--probe reasoning-cache]   " +
  "full-stack e2e over every configured head; `heads reasoning-cache` is the gateway-held reasoning-cache wire probe";

/** The exit status for a harness failure — a fact about the RUN, never a verdict about a head.
 *  Same vocabulary as `e2e oracle` and `e2e code-mode`. */
export const HARNESS_EXIT = 2;

/** A harness-FATAL: the shell's `exit 1` after a FATAL line, carried as a throw so no arm has to
 *  invent a return path for it. `probe_bearer`'s FATAL could not `exit` from inside a command
 *  substitution's subshell (DR-49a); a throw has no subshell to die in. */
class FatalError extends Error {}

// ── the state root ───────────────────────────────────────────────────────────
/** V4-177: the same state-root rule as StatePaths.kt, app/src/main/dist/bin/splice-launch and console-wire-keys.ts —
 *  SPLICE_STATE_DIR, then the pre-0.4 CLAUDEX_STATE_DIR, then ~/.splice/state, adopting
 *  ~/.claude-codex/state in place when that is the only root on the box.
 *
 *  Exported under this name because StateLayoutDoctorTest drives EVERY copy of the rule through
 *  production and pins this file by path: a copy that picks the other root reports "mgmt-key not
 *  found" on a healthy install, or cold-starts a second daemon against an empty state dir while
 *  the real one is serving. */
export function liveStateDir(home: string = homedir(), env: Record<string, string | undefined> = process.env): string {
  // PER VARIABLE, and blank is not an answer. A variable holding only whitespace is NOT an answer
  // either: `-n` called " " set, Kotlin's isNotBlank does not, and StatePaths blank-checks PER
  // VARIABLE — so SPLICE_STATE_DIR=" " in a unit file made a shell copy read " /mgmt-key" relative
  // to CWD and report "mgmt-key not found" on a perfectly healthy install. `??` is wrong the other
  // way: it falls through only on null/undefined, so `export SPLICE_STATE_DIR=` gave "" and skipped
  // CLAUDEX_STATE_DIR entirely.
  for (const name of ["SPLICE_STATE_DIR", "CLAUDEX_STATE_DIR"]) {
    const value = env[name];
    if (value !== undefined && value.trim() !== "") return value;
  }
  // Adoption needs POSITIVE evidence on both sides, the rule StatePaths' three-valued probe
  // follows: only proven absence may start a fresh root, and the pre-0.4 root must be proven to be
  // a DIRECTORY. "cannot be stat-ed" is not absence — an unreadable ~/.splice would otherwise adopt
  // the pre-0.4 root here while the daemon declines and warns — and a REGULAR FILE at either path
  // is not a state root either.
  // ENOENT alone is absence. `throwIfNoEntry: false` answers undefined on ENOTDIR too — a REGULAR
  // FILE where ~/.splice belongs — and adopted the pre-0.4 root where StatePaths declines.
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

// ── the run report ───────────────────────────────────────────────────────────
const note = (line: string): void => {
  process.stderr.write(`${line}\n`);
};

class Report {
  readonly pass: string[] = [];
  readonly fail: string[] = [];
  readonly skip: string[] = [];

  ok(what: string): void {
    this.pass.push(what);
    note(`  ✓ ${what}`);
  }

  bad(what: string, why: string): void {
    this.fail.push(`${what}: ${why}`);
    note(`  ✗ ${what} — ${why}`);
  }

  // ⊘ not ✓ or -: a skip used to look like a quiet pass in a long log, which is how
  // muse was skipped all campaign. The word SKIP is in the live line, not only the summary.
  skipped(what: string, why: string): void {
    this.skip.push(`${what}: ${why}`);
    note(`  ⊘ SKIP ${what} — ${why}`);
  }

  summarize(): number {
    note("");
    note("── e2e summary ──");
    note(`  pass: ${this.pass.length}  fail: ${this.fail.length}  skip: ${this.skip.length}`);
    if (this.skip.length !== 0) note(`  ⚠ ${this.skip.length} skipped — a skip is not a pass`);
    for (const s of this.skip) note(`  SKIP ${s}`);
    for (const f of this.fail) note(`  FAIL ${f}`);
    return this.fail.length === 0 ? 0 : 1;
  }
}

// ── the request-byte contract receipt (#924 Phase 1) ─────────────────────────
// On a tier-1 200, drop a receipt beside the goldens. The FULL binding — sha256 of the exact
// UPSTREAM request bytes the head sent, checked against sha256(builderOutput) so a blind
// golden-regenerate can't go green — needs a head-side upstream-request tap that does NOT exist yet
// (the head doesn't surface the bytes its RequestBuilder produced). Until that lands, this records
// what IS observable client-side and marks contract_bound=false. See docs/architecture/request-byte-contracts.md for the
// tap + the enforcement it unlocks. This makes the receipt file + emission point real, not the
// binding — so wiring the tap is a localized change.
// DR-111: E2E_RECEIPT_DIR redirects emission for harness selftests — a loopback run against a
// real head KEY must never fabricate that head's in-repo receipt (the binding would grade fakes).
export const RECEIPT_NOTE =
  "upstream-request-bytes tap not wired; sha256(builderOutput)==receipt.hash inactive — see docs/architecture/request-byte-contracts.md";

function receiptDir(root: string): string {
  return process.env.E2E_RECEIPT_DIR || join(root, "tools/e2e/receipts");
}

function emitReceipt(dir: string, key: string, model: string, httpStatus: number): void {
  mkdirSync(dir, { recursive: true });
  const path = join(dir, `${key}.json`);
  writeFileSync(
    path,
    JSON.stringify(
      {
        head: key,
        model,
        http_status: httpStatus,
        observed_at: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
        contract_bound: false,
        note: RECEIPT_NOTE,
      },
      null,
      2,
    ) + "\n",
  );
  note(`    receipt: ${path} (contract_bound=false — see docs/architecture/request-byte-contracts.md)`);
}

// ── HTTP, with the credential off argv ───────────────────────────────────────
// Credentials never ride on argv. Every argument of a running process is world-readable through
// /proc/<pid>/cmdline, so `ps -ef` during a probe exposed the daemon management key, and on a
// client-auth head the caller's own vendor token. The shell fed curl a config file on STDIN for
// exactly that reason (review 2026-08-28, PR 99, comment 31); in-process fetch has no argv and no
// config file at all, which is the same property bought more cheaply. The ONE child that still
// needs the bearer — stream_probe.ts — receives it through the environment, never as a flag.
interface Fetched {
  status: number;
  body: string;
}

async function authed(bearer: string, url: string, timeoutMs: number, init: RequestInit = {}): Promise<Fetched> {
  const res = await fetch(url, {
    ...init,
    headers: { ...(init.headers as Record<string, string> | undefined), Authorization: `Bearer ${bearer}` },
    signal: AbortSignal.timeout(timeoutMs),
  });
  return { status: res.status, body: await res.text() };
}

async function reachable(url: string, timeoutMs: number): Promise<boolean> {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
    await res.text();
    return true;
  } catch {
    return false;
  }
}

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

// ── discovery ────────────────────────────────────────────────────────────────
export interface Head {
  key: string;
  label: string;
  port: string;
  healthy: string;
  authKind: string;
}

const DISCOVERY_FIELDS = ["key", "label", "port", "healthy", "authKind"] as const;

/**
 * authKind is load-bearing, not decoration: it is the ONLY thing that tells tier 1 whether a head
 * holds a splice credential or forwards the caller's own upstream (see probeBearer). A daemon too
 * old to report the field is a HARD failure rather than a default — guessing "probably not
 * client-auth" is exactly the assumption that leaks the mgmt key.
 */
export function parseHeads(payload: string): Head[] {
  const rows = (JSON.parse(payload) as { heads?: Array<Record<string, unknown>> }).heads ?? [];
  return rows.map((h) => {
    const missing = DISCOVERY_FIELDS.filter((k) => !(k in h));
    if (missing.length) {
      throw new FatalError(
        `/api/heads row ${JSON.stringify(h["key"] ?? null)} lacks ${JSON.stringify(missing)} — daemon predates this harness`,
      );
    }
    return Object.fromEntries(DISCOVERY_FIELDS.map((k) => [k, String(h[k])])) as unknown as Head;
  });
}

// The cheap tier of every dialect this harness can meet. `haiku` was the missing one and it was a
// COST TRAP, not a cosmetic gap: the Anthropic catalog is fable/opus/sonnet/haiku
// (app/src/main/resources/splice.example.toml:275-289), none of which matched `mini|spark|flash|lite`, so an
// anthropic-passthrough head fell through to rows[0] — claude-fable-5, simultaneously the most
// expensive row and the head's pinned_model. Verified live: grok (grok-4.6/4.5/4.3) and kimi
// (k3-256k/kimi-for-coding/k3[1m]) match nothing either and take that same fallback today.
export const DEFAULT_CHEAP_MODEL_RE = "haiku|mini|spark|flash|lite|nano";

/** The model a probe will spend on, and WHY it was chosen. No silent fallback: tier 1 spends real
 *  provider quota, so a run that cannot find a cheap row must SAY it is about to bill the catalog
 *  head — the failure mode this replaces was invisible. */
export function pickModel(catalog: string, cheapRe: string): { model: string; why: string } {
  const rows = ((JSON.parse(catalog) as { data?: Array<{ id: string }> }).data ?? []).map((d) => d.id);
  if (!rows.length) throw new FatalError("/v1/models returned an empty catalog");
  const cheap = rows.filter((r) => new RegExp(cheapRe).test(r));
  const why = cheap.length
    ? `cheap tier, matched /${cheapRe}/`
    : `NO row matched /${cheapRe}/ — falling back to the catalog head, the MOST EXPENSIVE row of ${JSON.stringify(rows)}`;
  return { model: (cheap[0] ?? rows[0])!, why };
}

/**
 * The bearer a tier-1 probe presents to a head, or a refusal when it must not be probed.
 *
 * SAFETY (HD-15): a client-auth head holds NO splice credential. ClientAuth.authorize()
 * short-circuits to true for it (`if (deps.forwardClientAuth) return true`) and
 * ClientAuth.forwardedClientHeaders copies the inbound Authorization header VERBATIM to the
 * vendor, via TurnPreparation. Both live in splice/gateway/head/ClientAuth.kt. Presenting
 * $MGMT there would ship the daemon's own 32-byte management key to api.anthropic.com — and
 * ClientAuthProvider.allowRefreshAfterFailure is false (ClientAuthProvider.kt:38), so it surfaces
 * as a bare 401 that reads like a product bug. Such a head is probed ONLY with a real caller
 * credential, or not at all.
 * ALLOWLIST, not a blocklist. `authKind !== "client"` recognized exactly one dangerous value and
 * treated every other string as safe, including strings nobody has verified — a gate that fails
 * OPEN, eight lines below discovery's own law that guessing is what leaks the key. authKind is
 * ctx.providerCfg.auth.kind, the operator's raw TOML string (ManagedHeadFactory.kt:58), and
 * AuthKindRegistry.from() deliberately tolerates a custom kind by returning null, so an
 * unrecognized value here is reachable by config alone. Whether such a head forwards the caller's
 * Authorization upstream is exactly what we do not know, so it is refused rather than probed with
 * $MGMT. Review 2026-08-28 (PR 99, comment 3).
 *
 * "skip" is the legit client-auth skip; the unknown kind THROWS (DR-49a): the shell's `exit 1`
 * died inside tier1's command-substitution subshell, the parent read the same rc as the legit
 * skip, and the FATAL text scrolled past as decoration on a run that exited 0.
 */
export function probeBearer(authKind: string, mgmt: string, clientToken: string | undefined): string | "skip" {
  switch (authKind) {
    case "client":
      return clientToken ? clientToken : "skip";
    case "chatgpt-oauth":
    case "grok-oauth":
    case "kimi-oauth":
    case "muse-oauth":
    case "api-key":
      return mgmt;
    default:
      throw new FatalError(
        `unrecognized authKind '${authKind}' — refusing to probe. A head whose auth kind this\n` +
          "       harness does not know may forward the Authorization header upstream, so\n" +
          "       presenting $MGMT could leak the daemon management key to a vendor. Add the\n" +
          "       kind to probeBearer() once you have confirmed which side holds the credential.",
      );
  }
}

// ── the perf oracle ──────────────────────────────────────────────────────────
/**
 * The tier-2 oracle over the head's perf JSONL. Three assertions on the drive window:
 *   · at least `want` rows with outcome=ok landed          — a turn happened
 *   · no UNRECOVERED non-ok row landed                     — …and nothing stayed broken alongside it.
 *     Filtering to outcome=="ok" (as this once did) made a failed turn's row structurally
 *     unreadable, so a head that was alive but WRONG could not be failed by anything here.
 *   · the retry counters on every ok row are clean         — …without fighting to get there
 *
 * WHY "unrecovered" and not "any non-ok". The window is per-head WALL-CLOCK and a perf row carries
 * no session/PID discriminator, so it cannot be narrowed to the harness's own turns — a plain
 * "any non-ok row fails" reds on traffic the harness never sent. Two classes, both real here:
 *   · client_abort is recorded when the CLIENT went away — TurnDriver.kt:227 and :247, and
 *     TurnPipeline.kt:52 (TurnOutcome.ClientAbandoned). Never a head defect; an operator pressing
 *     Esc in another TUI during tier 2's multi-minute window would red the head. Live census:
 *     136 on claude-kimi, 71 on claudex. It is EXCLUDED from the fail set and reported as info.
 *   · a transient upstream 5xx that Claude Code retried successfully writes one non-ok row AND a
 *     following ok row. User-visible outcome is success, so failing it is a false red.
 * A non-ok row therefore counts as RECOVERED iff the next row OF THE SAME MODEL in the window is
 * outcome=ok — precisely "the retry worked". Measured over the live JSONLs, that adjacency is the
 * dominant shape of a blip (claudex: 63% of failure runs are a single row, p50 1735ms from the
 * failure to the next ok) while a genuinely sick head produces RUNS (claude-kimi's bad period:
 * runs of 12, 44, 83, 149 consecutive failures).
 * SAME MODEL is load-bearing (DR-49b): whole-file adjacency paired unrelated turns, and a healthy
 * model's interleaved oks pardoned EVERY failure of a broken one (fail-A, ok-B, fail-A, ok-B read
 * as zero unrecovered — proven red by the selftest fixture). `model` is the only relatedness key a
 * perf row carries (PerfStats.kt: no session/PID), so same-model concurrent traffic still
 * adjacency-pairs — the residual is stated, not solved. Lane-scoping only ever moves rows toward
 * unrecovered (live claudex window: 93 -> 89 recovered, 256 -> 260 unrecovered), so it cannot
 * newly pardon anything. Teeth check against the very window that first proved this assertion
 * (claudex ts>=1786930524162; the snapshot measured here was 107 rows, 91 ok / 16 non-ok across
 * THREE models, run lengths 1,1,1,1,5,7): whole-file pairing scored it 11 unrecovered / 5
 * pardoned, and lane-scoping reds HARDER on the same rows — 16 unrecovered / 0 pardoned, because
 * every one of those 5 pardons was itself a cross-model adjacency, the exact lie this fix closes.
 * A trailing failure with nothing after it in its lane is unrecovered by construction, so a head
 * that dies at the end of the window still reds.
 *
 * Counter semantics are verified against TurnPerf and ~200k live rows, because the obvious
 * assertions are wrong in two different ways:
 *   · TurnPerf.add() DROPS a zero delta (core/perf/TurnPerf.kt, pinned by TurnPerfTest's
 *     `RETRIES !in snap.counters`), so retries/refreshes are ABSENT on a clean turn, never 0.
 *   · `attempts` is written by UpstreamClient.kt:229, which the WebSocket runner bypasses entirely
 *     — live census: present on 99% of claude-kimi/claude-grok rows but only 4% of claudex's.
 *     So assert the VALUE where the field exists; requiring its PRESENCE would red every ws head.
 *   Hence `row[name] ?? clean` — absent reads as compliant, a written value must be right.
 *   · `search_rounds` is legitimately 1-3 on a healthy responses head (tool_search deferral, 493
 *     live claudex rows) — it is REPORTED, never asserted.
 */
export function perfRowsOk(path: string, since: number, want: number): { ok: boolean; verdict: string } {
  type Row = Record<string, unknown>;
  const rows: Row[] = [];
  let text = "";
  try {
    text = readFileSync(path, "utf8");
  } catch {
    text = "";
  }
  for (const line of text.split("\n")) {
    if (!line.trim()) continue;
    try {
      rows.push(JSON.parse(line) as Row);
    } catch {
      continue;
    }
  }
  const inWindow = rows.filter((r) => Number(r["ts"] ?? 0) >= since);
  inWindow.sort((a, b) => Number(a["ts"] ?? 0) - Number(b["ts"] ?? 0));
  const ok = inWindow.filter((r) => r["outcome"] === "ok");
  const aborts = inWindow.filter((r) => r["outcome"] === "client_abort").length;

  const tally = (outcomes: string[]): string => {
    const seen = new Map<string, number>();
    for (const o of outcomes) seen.set(o, (seen.get(o) ?? 0) + 1);
    return [...seen.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([k, v]) => `${k}x${v}`).join(", ");
  };

  // DR-49b: pair within the MODEL lane, never across the whole file — see the SAME MODEL
  // paragraph above for why raw adjacency lied green under interleaved concurrent traffic.
  const lanes = new Map<string, Row[]>();
  for (const r of inWindow) {
    const lane = String(r["model"] ?? "");
    const bucket = lanes.get(lane);
    if (bucket) bucket.push(r);
    else lanes.set(lane, [r]);
  }
  const unrecovered: string[] = [];
  const recovered: string[] = [];
  for (const lane of lanes.values()) {
    lane.forEach((r, i) => {
      const o = String(r["outcome"] ?? "");
      if (o === "ok" || o === "client_abort") return;
      const nxt = i + 1 < lane.length ? lane[i + 1]!["outcome"] : null;
      (nxt === "ok" ? recovered : unrecovered).push(o);
    });
  }

  const problems: string[] = [];
  if (ok.length < want) problems.push(`only ${ok.length} ok perf rows since window start (want >= ${want})`);
  if (unrecovered.length) problems.push("unrecovered non-ok rows in window: " + tally(unrecovered));
  for (const [name, clean] of [["attempts", 1], ["retries", 0], ["refreshes", 0]] as const) {
    const off = ok.map((r) => r[name]).filter((v) => (v ?? clean) !== clean);
    if (off.length) {
      const distinct = [...new Set(off.map((v) => Number(v)))].sort((a, b) => a - b);
      problems.push(`${name}=[${distinct.join(", ")}] on ${off.length}/${ok.length} ok rows (want ${clean})`);
    }
  }
  if (problems.length) return { ok: false, verdict: problems.join("; ") };

  const worst = ok.reduce((m, r) => Math.max(m, Number(r["total"] ?? 0)), 0);
  const carried = ok.filter((r) => "attempts" in r).length;
  const rounds = [...new Set(ok.filter((r) => "search_rounds" in r).map((r) => Number(r["search_rounds"])))].sort(
    (a, b) => a - b,
  );
  return {
    ok: true,
    verdict:
      `${ok.length} ok rows / 0 unrecovered non-ok, slowest total=${worst}ms, ` +
      `attempts==1 on ${carried}/${ok.length} rows carrying it, retries=0, refreshes=0` +
      (rounds.length ? `, search_rounds=[${rounds.join(", ")}] (informational)` : "") +
      (recovered.length ? `, retried-then-ok: ${tally(recovered)} (informational)` : "") +
      (aborts ? `, client_abort x${aborts} (informational — client went away)` : ""),
  };
}

/** The pass/fail wrapper around perfRowsOk — shared by tier 2 and the perf-oracle tier so the
 *  selftest exercises the exact gate tier 2 runs, not a lookalike. */
function perfGate(report: Report, stateDir: string, key: string, since: number, want: number): void {
  const { ok, verdict } = perfRowsOk(join(stateDir, `${key}-perf.jsonl`), since, want);
  if (ok) {
    note(`    perf: ${verdict}`);
    report.ok(`${key}/perf-rows`);
  } else {
    report.bad(`${key}/perf-rows`, verdict);
  }
}

// ── the operator-shaped tool surface ─────────────────────────────────────────
// Operator 2026-09-15: claude-muse 400d with "name must be at most 64 characters, got 68".
// The live offender was this composed MCP tool name. Tier 2 used to launch in an empty
// mktemp dir, so the session never carried an operator-shaped tool surface. Planting this
// name into the scratch dir is the cheapest honest stand-in that does not depend on which
// plugins happen to be installed. Length is load-bearing: keep it over 64.
//
// THE NAME IS COMPOSED, NOT DECLARED. Claude Code spells an MCP tool mcp__<server key>__<tool>,
// so the 68 characters come from the .mcp.json KEY plus the name the server advertises in its
// tools/list — never from a string anybody writes out in full. Both halves live here; the server
// owns only its short half (tools/e2e/fixtures/mcp_overlong_tool_server.ts).
//
// REDO 2026-09-17 — the first version of this arm was inert. It planted an INLINE `-c` one-liner
// that exits before the first byte of the stdio handshake, and it wrote no settings, so the
// project MCP server was never even enabled. Claude Code registered zero tools, the 68-char name
// never reached the wire, and the arm could not have caught the 400 it exists to catch. Both
// halves are fixed below: a REAL server, and the settings line that enables it non-interactively.
export const OVERLONG_MCP_SERVER = "plugin_desktop-commander_desktop-commander";
export const OVERLONG_MCP_TOOL = "read_process_output";
export const OVERLONG_TOOL_NAME = `mcp__${OVERLONG_MCP_SERVER}__${OVERLONG_MCP_TOOL}`;
export const OVERLONG_MCP_LOG_NAME = "mcp-handshake.jsonl";
export const OVERLONG_MCP_SERVER_SCRIPT = "tools/e2e/fixtures/mcp_overlong_tool_server.ts";

/**
 * Plants the server AND enables it. A project-scoped .mcp.json is INERT on its own: Claude Code
 * asks the operator to approve it on first sight, and tier 2 drives a TUI with nobody to answer,
 * so an unapproved server silently contributes no tools. `enabledMcpjsonServers` names this one
 * server; `enableAllProjectMcpServers` is the blanket form of the same permission — both are read
 * from project settings (scratch/.claude/settings.local.json, the same file Claude Code writes
 * itself when a human clicks approve). VERIFIED SEPARATELY against Claude Code 2.1.257 on
 * 2026-09-17: each key ALONE makes the client spawn the planted server and pull its tools (the
 * server's handshake receipt lands either way), so neither is decoration and either one is a
 * sufficient enable — which is why the selftest accepts either. Both are written because the
 * scratch dir is thrown away at the end of the head, so there is nothing to keep tidy.
 *
 * `claude mcp list` is NOT the oracle here and says "Pending approval" for this server no matter
 * what these settings say: it reports the per-project approval recorded in ~/.claude.json, which
 * the harness deliberately does not touch (it is the operator's own global file). The oracle is
 * whether the server is actually spawned — which is what mcpSurfaceOk reads.
 */
export function plantOverlongMcp(scratch: string, root: string): void {
  // The server's path rides into the args array as an ABSOLUTE path: the server is spawned with
  // the scratch as cwd today, but the receipt the gate reads must not depend on that staying true.
  writeFileSync(
    join(scratch, ".mcp.json"),
    JSON.stringify(
      {
        mcpServers: {
          [OVERLONG_MCP_SERVER]: {
            command: "bun",
            args: [join(root, OVERLONG_MCP_SERVER_SCRIPT)],
            env: { SPLICE_E2E_MCP_LOG: join(scratch, OVERLONG_MCP_LOG_NAME) },
          },
        },
      },
      null,
      2,
    ) + "\n",
  );
  const settings = join(scratch, ".claude");
  mkdirSync(settings, { recursive: true });
  writeFileSync(
    join(settings, "settings.local.json"),
    JSON.stringify({ enabledMcpjsonServers: [OVERLONG_MCP_SERVER], enableAllProjectMcpServers: true }, null, 2) + "\n",
  );
}

/**
 * The receipt that proves the tool surface was REAL, read from the JSONL the planted server
 * appends per JSON-RPC method it serves. Two facts are asserted, and only these two are
 * observable: Claude Code completed the `initialize` handshake with the server, and it pulled
 * `tools/list` and was answered with OVERLONG_MCP_TOOL. Since Claude Code composes
 * mcp__<server key>__<advertised tool>, a served tools/list IS the 68-char name entering this
 * session's tool surface — the surface the operator's muse turn carried when it 400d.
 *
 * WHY NOT THE WIRE BYTES. The stronger receipt — the exact request the head sent upstream — needs
 * the head-side tap docs/architecture/request-byte-contracts.md describes and that does not exist yet (the same gap
 * emitReceipt marks contract_bound=false for), and a perf row carries no tool names at all
 * (PerfStats.kt writes ts/model/outcome/marks/counters). So this gate asserts the name entered
 * the session and the turn assertions assert the head answered anyway: an unshortened over-cap
 * name comes back 400 and turn 1 never renders ANSWER=42. Together that is the catch.
 */
export function mcpSurfaceOk(scratch: string): { ok: boolean; verdict: string } {
  const path = join(scratch, OVERLONG_MCP_LOG_NAME);
  if (!existsSync(path)) {
    return {
      ok: false,
      verdict:
        `no MCP handshake receipt at ${path} — Claude Code never spawned the planted server ` +
        "(is it enabled in the scratch settings?)",
    };
  }
  const rows: Array<Record<string, unknown>> = [];
  for (const line of readFileSync(path, "utf8").split("\n")) {
    if (!line.trim()) continue;
    try {
      rows.push(JSON.parse(line) as Record<string, unknown>);
    } catch {
      continue;
    }
  }
  const methods = rows.map((r) => String(r["method"] ?? ""));
  if (!methods.includes("initialize")) {
    return {
      ok: false,
      verdict:
        `MCP receipt has no initialize row (methods: ${methods.length ? JSON.stringify(methods) : "<none>"}) — ` +
        "the stdio handshake never completed",
    };
  }
  const listed = rows
    .filter((r) => r["method"] === "tools/list")
    .flatMap((r) => ((r["tools"] as string[] | undefined) ?? []));
  if (!listed.length) {
    return {
      ok: false,
      verdict:
        `MCP receipt has no tools/list row (methods: ${JSON.stringify(methods)}) — the server initialized but its ` +
        "tools never entered the session",
    };
  }
  if (!listed.includes(OVERLONG_MCP_TOOL)) {
    return {
      ok: false,
      verdict:
        `MCP server advertised ${JSON.stringify(listed)}, not ${JSON.stringify(OVERLONG_MCP_TOOL)} — ` +
        `the composed name is no longer ${JSON.stringify(OVERLONG_TOOL_NAME)}`,
    };
  }
  return {
    ok: true,
    verdict:
      `initialize + tools/list served; ${JSON.stringify(OVERLONG_MCP_TOOL)} advertised, so ` +
      `${JSON.stringify(OVERLONG_TOOL_NAME)} (${OVERLONG_TOOL_NAME.length} chars) entered the session tool surface`,
  };
}

/** pass/fail wrapper, shared by tier 2 and the mcp-oracle tier so the selftest exercises the exact
 *  gate tier 2 runs (same shape as perfGate above, for the same reason). */
function mcpSurfaceGate(report: Report, key: string, scratch: string): void {
  const { ok, verdict } = mcpSurfaceOk(scratch);
  if (ok) {
    note(`    tool surface: ${verdict}`);
    report.ok(`${key}/mcp-tool-surface`);
  } else {
    report.bad(`${key}/mcp-tool-surface`, verdict);
  }
}

// ── tier 1: wire probe ───────────────────────────────────────────────────────
interface Context {
  root: string;
  stateDir: string;
  control: string;
  mgmt: string;
  clientToken: string | undefined;
  receipts: string;
  report: Report;
}

async function tier1(ctx: Context, head: Head): Promise<void> {
  const { key, port, authKind } = head;
  // The credential decision comes FIRST — before /v1/models, before the turn, before count_tokens.
  // Every one of those presents a bearer to the head, so there is no safe "probe a little" state.
  // An unrecognized kind THROWS out of here as the harness-FATAL its text promises (DR-49a).
  const bearer = probeBearer(authKind, ctx.mgmt, ctx.clientToken);
  if (bearer === "skip") {
    ctx.report.skipped(
      `${key}/wire`,
      "client-auth head, no caller credential supplied (set SPLICE_E2E_CLIENT_TOKEN to probe it)",
    );
    ctx.report.skipped(`${key}/count_tokens`, "client-auth head, no caller credential supplied");
    return;
  }
  const modelVar = `E2E_MODEL_${key.toUpperCase().replaceAll("-", "_")}`;
  let model = process.env[modelVar] ?? "";
  let why = `${modelVar} override`;
  if (!model) {
    let picked: { model: string; why: string };
    try {
      // /v1/models sits behind the head's authorize() like every other head route, so discovery must
      // present a credential — the SAME one the probe itself will send, never unconditionally $MGMT.
      // Without it the head correctly answers authentication_error and discovery died on a KeyError.
      const catalog = await authed(bearer, `http://127.0.0.1:${port}/v1/models`, 5000);
      picked = pickModel(catalog.body, process.env.E2E_CHEAP_MODEL_RE || DEFAULT_CHEAP_MODEL_RE);
    } catch {
      ctx.report.bad(`${key}/wire`, "model discovery failed");
      return;
    }
    model = picked.model;
    why = picked.why;
  }
  note(`[${key}] tier1 wire probe on :${port} model=${model}`);
  note(`    model choice: ${why}`);
  const probe = Bun.spawnSync(
    [
      process.execPath,
      join(ctx.root, "tools/e2e/probes/stream_probe.ts"),
      "--head", key,
      "--port", port,
      "--model", model,
      "--ttfb-ms", process.env.E2E_TTFB_MS || "20000",
      "--first-delta-ms", process.env.E2E_FIRST_DELTA_MS || "45000",
      "--total-ms", process.env.E2E_TOTAL_MS || "120000",
      "--gap-ms", process.env.E2E_GAP_MS || "30000",
    ],
    { env: { ...process.env, SPLICE_PROBE_BEARER: bearer }, stdio: ["ignore", "pipe", "inherit"] },
  );
  const summary = probe.stdout.toString().trim();
  if (exitStatusOf(probe) === 0) {
    note(`    ${summary}`);
    ctx.report.ok(`${key}/wire`);
    emitReceipt(ctx.receipts, key, model, 200);
  } else {
    note(`    ${summary || "<no output>"}`);
    let why2 = "probe crashed";
    try {
      why2 = ((JSON.parse(summary) as { violations: string[] }).violations ?? []).join("; ").slice(0, 300);
    } catch {
      /* the probe crashed before it could write its one-line JSON summary */
    }
    ctx.report.bad(`${key}/wire`, why2);
  }
  // DR-113: a transport failure (refused/reset/timeout) errexited the WHOLE harness with curl's
  // exit code instead of recording a per-head fail — the parse below only ever saw payloads that
  // arrived rc=0. Same set -e family as DR-110/DR-49a; in-process that is a rejected promise, and
  // it is caught HERE rather than at the top.
  let ct: string;
  try {
    const res = await authed(bearer, `http://127.0.0.1:${port}/v1/messages/count_tokens`, 10_000, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ model, messages: [{ role: "user", content: "hello" }] }),
    });
    ct = res.body;
  } catch (e) {
    ct = `TRANSPORT FAILURE: count_tokens ${(e as Error).message}`;
  }
  let counted = false;
  try {
    counted = Number.isInteger((JSON.parse(ct) as { input_tokens: unknown }).input_tokens);
  } catch {
    counted = false;
  }
  if (counted) ctx.report.ok(`${key}/count_tokens`);
  else ctx.report.bad(`${key}/count_tokens`, `bad payload: ${ct.slice(0, 120)}`);
}

// ── tier 2: tmux TUI drive ───────────────────────────────────────────────────
const TMUX_SOCK = "splice-e2e";

function tmux(args: string[]): { status: number; stdout: string } {
  const child = Bun.spawnSync(["tmux", "-L", TMUX_SOCK, ...args], { stdio: ["ignore", "pipe", "pipe"] });
  return { status: exitStatusOf(child), stdout: child.stdout.toString() };
}

function pane(sess: string): string {
  const r = tmux(["capture-pane", "-pt", sess, "-S", "-160"]);
  return r.status === 0 ? r.stdout : "";
}

/**
 * Wait until the pane matches `want`. Auto-answers first-run dialogs along the way. Returns
 * "ready", "timeout" or "auth". NB: the first-run TRUST dialog draws its selection cursor
 * with the SAME `❯` glyph the input prompt uses — so readiness MUST key on the main-screen status
 * bar ("bypass permissions" / "for shortcuts"), never on `❯` (that false-matched the trust screen
 * and the harness typed prompts into a dialog that swallowed them).
 */
async function waitPane(sess: string, deadlineS: number, want: RegExp): Promise<"ready" | "timeout" | "auth"> {
  const end = Date.now() + deadlineS * 1000;
  while (Date.now() < end) {
    const p = pane(sess);
    // dialogs first — they can sit UNDER a spurious readiness match otherwise
    // The trust dialog's DEFAULT SELECTION IS "No, exit" (verified against Claude Code 2.1.257 in
    // a fresh mktemp dir, which is the only kind of dir tier 2 ever launches in — so it is always
    // untrusted and this dialog is always drawn). A bare Enter therefore ANSWERED NO and killed
    // the wrapper before a single turn; the drive then died in waitPane's 90s timeout as "TUI
    // never became ready", which reads like a head problem and is not one. `--dangerously-skip-
    // permissions` does not skip it either. So: move the cursor, then CONFIRM the trusting option
    // is the selected one before pressing Enter — a reordered or re-worded dialog stalls to the
    // timeout instead of silently answering No again.
    if (/trust this folder|do you trust/i.test(p)) {
      tmux(["send-keys", "-t", sess, /❯[ \t]*Yes/.test(p) ? "Enter" : "Down"]);
      await sleep(1000);
      continue;
    }
    if (/text style|theme to use|choose the text/i.test(p)) {
      tmux(["send-keys", "-t", sess, "Enter"]);
      await sleep(1000);
      continue;
    }
    if (/sign in|\/login to authenticate|run: .* login|not logged in/i.test(p)) return "auth";
    if (want.test(p)) return "ready";
    await sleep(1000);
  }
  return "timeout";
}

async function sendPrompt(sess: string, text: string): Promise<void> {
  tmux(["send-keys", "-t", sess, "-l", text]);
  await sleep(300);
  tmux(["send-keys", "-t", sess, "Enter"]);
}

function tier2Cleanup(key: string, sess: string, scratch: string, keep = false): void {
  if (process.env.E2E_KEEP_TMUX || keep) {
    note(`    (kept tmux session '${sess}' on socket -L ${TMUX_SOCK} and ${scratch})`);
    return;
  }
  try {
    writeFileSync(join(tmpdir(), `splice-e2e-${key}-pane.txt`), pane(sess));
  } catch {
    /* a pane that cannot be captured is not worth failing a cleanup over */
  }
  tmux(["kill-session", "-t", sess]);
  rmSync(scratch, { recursive: true, force: true });
}

async function tier2(ctx: Context, head: Head): Promise<void> {
  const { key, label, authKind } = head;
  const sess = `e2e-${key}`;
  if (exitStatusOf(Bun.spawnSync(["sh", "-c", `command -v ${JSON.stringify(label)}`], { stdio: ["ignore", "pipe", "pipe"] })) !== 0) {
    ctx.report.skipped(`${key}/tui`, `wrapper '${label}' not on PATH (run: splice install)`);
    return;
  }
  // Deliberate, announced spend — not a gap in tier 1's credential gate. See the COST note in the
  // file header: tier 2 cannot leak the mgmt key on a client-auth head (LaunchService withholds
  // ANTHROPIC_AUTH_TOKEN, so the wrapper rides the operator's own login), it can only bill it.
  if (authKind === "client") {
    note("    NOTE: client-auth head — these 2 turns bill YOUR personal Anthropic subscription, not a splice credential");
  }
  const scratch = mkdtempSync(join(tmpdir(), `splice-e2e-${key}.`));
  plantOverlongMcp(scratch, ctx.root);
  // MILLISECONDS, not seconds. A second-resolution window let any row written earlier in that same
  // second fall inside it — and under `--tier all` the gap between tier 1's own perf row and this
  // line is one count_tokens call plus a mkdtemp, tens of ms. That bled tier 1 into tier 2 nearly
  // always: a tier-1 failure was re-reported as a tier-2 perf-rows failure for one event, and a
  // passing tier-1 ok row counted toward tier 2's ">= 2 ok rows", so tier 2 could go green having
  // seen only one of its own two turns.
  const startMs = Date.now();
  note(`[${key}] tier2 tmux drive: launching '${label}' in ${scratch}`);
  tmux(["kill-session", "-t", sess]);
  // keep the pane alive after exit so a crash is post-mortem-able
  tmux(["new-session", "-d", "-s", sess, "-x", "200", "-y", "50", "-c", scratch,
    `sh -c '${label}; echo E2E_WRAPPER_EXITED=$?; sleep 600'`]);

  // DR-110: the not-logged-in verdict must survive as a SKIP — the shell's bare call errexited the
  // harness before its rc was read, so the README-promised SKIP path never ran: the first
  // not-logged-in head killed the run mid-roster (no summary, no cleanup, tmux session and scratch
  // leaked).
  const ready = await waitPane(sess, 90, /bypass permissions|for shortcuts/);
  if (ready === "auth") {
    ctx.report.skipped(`${key}/tui`, "head not logged in");
    tier2Cleanup(key, sess, scratch, true);
    return;
  }
  if (ready !== "ready") {
    ctx.report.bad(`${key}/tui`, "TUI never became ready (90s)");
    tier2Cleanup(key, sess, scratch);
    return;
  }

  // The expected answers (ANSWER=42 / SECOND=DONE) deliberately do NOT appear in the prompt text,
  // so a match is the model's RESPONSE, never the echoed input line.
  await sendPrompt(sess, "Compute six times seven and reply with exactly ANSWER= followed by the number.");
  const turn1 = await waitPane(sess, 150, /ANSWER=42/);
  // BEFORE the early return, deliberately. When the over-long name is what broke the turn, the
  // handshake receipt is the diagnosis — a return that discards it leaves "no ANSWER=42" as the
  // only evidence, which is exactly the shape the operator's unexplained 400 already had.
  mcpSurfaceGate(ctx.report, key, scratch);
  if (turn1 !== "ready") {
    ctx.report.bad(`${key}/tui`, "no ANSWER=42 within 150s");
    tier2Cleanup(key, sess, scratch);
    return;
  }
  ctx.report.ok(`${key}/tui-turn1`);

  await sendPrompt(sess, "Reply with exactly the word SECOND followed by an equals sign and the word DONE.");
  if ((await waitPane(sess, 150, /SECOND=DONE/)) !== "ready") {
    ctx.report.bad(`${key}/tui`, "no SECOND=DONE within 150s (multi-turn)");
    tier2Cleanup(key, sess, scratch);
    return;
  }
  ctx.report.ok(`${key}/tui-turn2`);

  perfGate(ctx.report, ctx.stateDir, key, startMs, 2);
  tier2Cleanup(key, sess, scratch);
}

// ── the heads run ────────────────────────────────────────────────────────────
function required(name: string, what: string): string {
  const v = process.env[name];
  if (!v) throw new FatalError(`set ${name} (${what})`);
  return v;
}

async function runHeads(tier: string, onlyHead: string, list: boolean): Promise<number> {
  const root = layout().repoRoot;
  const stateDir = liveStateDir();
  const controlPort = process.env.SPLICE_CONTROL_PORT || "3096";
  const control = `http://127.0.0.1:${controlPort}`;
  const report = new Report();

  // ── preflight ──────────────────────────────────────────────────────────────
  if (!(await reachable(`${control}/health`, 3000))) {
    note("daemon down — cold-starting (same recipe as the CLI)");
    const opts = (process.env.SPLICE_JVM_OPTS || "-Xmx1024m -XX:+UseStringDeduplication").split(/\s+/).filter(Boolean);
    Bun.spawn(["java", ...opts, "-jar", join(homedir(), ".local/share/splice/splice.jar"), "daemon"], {
      stdio: ["ignore", "ignore", "ignore"],
    }).unref();
    for (let i = 0; i < 60; i++) {
      if (await reachable(`${control}/health`, 2000)) break;
      await sleep(250);
    }
  }
  if (!(await reachable(`${control}/health`, 3000))) {
    throw new FatalError(`control plane not answering on :${controlPort}`);
  }
  let mgmt = "";
  try {
    mgmt = readFileSync(join(stateDir, "mgmt-key"), "utf8");
  } catch {
    mgmt = "";
  }
  if (!mgmt) throw new FatalError(`mgmt-key missing at ${join(stateDir, "mgmt-key")}`);

  // The whole point of SPLICE_E2E_CLIENT_TOKEN is that it is NOT the mgmt key: it rides into the
  // exact Authorization header a client-auth head forwards verbatim to api.anthropic.com
  // (ClientAuth.forwardedClientHeaders). Reaching for "the token the harness already has" would
  // re-create the leak this gate exists to prevent, so refuse before a single byte reaches a head.
  const clientToken = process.env.SPLICE_E2E_CLIENT_TOKEN || undefined;
  if (clientToken !== undefined && clientToken === mgmt) {
    throw new FatalError(
      "SPLICE_E2E_CLIENT_TOKEN is the daemon mgmt key. A client-auth head forwards that header " +
        "VERBATIM to the vendor — supply a REAL caller credential or unset it.",
    );
  }

  // ── discovery ──────────────────────────────────────────────────────────────
  const discovered = await authed(mgmt, `${control}/api/heads`, 5000);
  const heads = parseHeads(discovered.body);
  if (!heads.length) throw new FatalError("/api/heads returned no heads");

  if (list) {
    for (const h of heads) {
      process.stdout.write(`${h.key}\t${h.label}\t${h.port}\t${h.healthy}\t${h.authKind}\n`);
    }
    return 0;
  }

  const ctx: Context = { root, stateDir, control, mgmt, clientToken, receipts: receiptDir(root), report };

  let matched = false;
  for (const head of heads) {
    if (onlyHead && head.key !== onlyHead) continue;
    matched = true;
    if (head.healthy !== "True" && head.healthy !== "true") {
      report.bad(head.key, "head reported unhealthy by /api/heads");
      continue;
    }
    note(`== head: ${head.key} (label=${head.label} port=${head.port} auth=${head.authKind})`);
    switch (tier) {
      case "1":
        await tier1(ctx, head);
        break;
      case "2":
        await tier2(ctx, head);
        break;
      case "all":
        await tier1(ctx, head);
        await tier2(ctx, head);
        break;
      // Selftest hook (DR-49b): run tier 2's perf gate alone over an E2E_PERF_SINCE/WANT window,
      // so the oracle's pairing rules are red/green provable without a tmux drive or provider spend.
      case "perf-oracle":
        perfGate(
          report,
          stateDir,
          head.key,
          Number(required("E2E_PERF_SINCE", "epoch ms")),
          Number(required("E2E_PERF_WANT", "min ok rows")),
        );
        break;
      // Selftest hook (V4-33), same shape and same reason as perf-oracle: run tier 2's tool-surface
      // gate alone over an E2E_MCP_SCRATCH dir, so the gate is red/green provable against a receipt
      // written by the REAL server without a tmux drive or provider spend.
      case "mcp-oracle":
        mcpSurfaceGate(report, head.key, required("E2E_MCP_SCRATCH", "scratch dir holding the handshake receipt"));
        break;
      // Selftest hook (V4-33): run the PLANT alone into E2E_MCP_SCRATCH. The selftest then reads the
      // config this produced and spawns the server from it — command, args and env exactly as
      // planted, nothing retyped. A check that greps this file for the word "enabledMcpjsonServers"
      // passes on the COMMENT that explains it; only running the plant can tell the two apart.
      case "plant-oracle":
        plantOverlongMcp(required("E2E_MCP_SCRATCH", "dir to plant into"), root);
        report.ok(`${head.key}/mcp-plant`);
        break;
      default:
        process.stderr.write(`bad --tier ${tier}\n`);
        return HARNESS_EXIT;
    }
  }

  if (onlyHead && !matched) report.bad(onlyHead, `requested head '${onlyHead}' was not returned by /api/heads`);

  // leave no stray tmux server when every session was cleaned
  if (tmux(["list-sessions"]).status !== 0) tmux(["kill-server"]);

  return report.summarize();
}

// =============================================================================================
// REASONING-CACHE PROBE — RC-6: live proof of the gateway-held reasoning cache.
//
// The 2026-07-23 single-shot probe validated one tool call and missed the amnesia class entirely.
// This probe drives LIVE multi-tool agentic turns through a real daemon built from this tree and
// asserts at the level that failed us: the UPSTREAM REQUEST BYTES. A local recording mock stands in
// for chatgpt.com (the item's sanctioned "local echo probe" — the daemon has no request-bytes tap),
// so every body the head sends upstream is captured and assertable, deterministically, with no
// credentials and no provider bill.
//
// Passes (fresh isolated daemon each — ports/state/config never touch the operator's live daemon):
//   ON   reasoning_cache = true (default)
//     A  3-tool task, 2 fan-out rounds: round 1 answers with ONE reasoning envelope + TWO parallel
//        function_calls; the follow-up request must carry that envelope exactly once, immediately
//        before the FIRST function_call (INJECT-ONCE-PER-TURN), and round 3 must carry both turns'
//        envelopes each in-position, none duplicated.
//   B    staleness recovery: the follow-up carrying the envelope is answered 400
//        invalid_encrypted_content; the daemon must retry ONCE with reasoning stripped and the
//        client must still see a clean successful turn (NEVER-BELOW-STATUS-QUO).
//   OFF  reasoning_cache = false: the follow-up request must carry ZERO reasoning items — the
//        status-quo amnesia wire state, recorded as the "before" of the comparison.
//
// Env:  RCP_JAR=<path>       the daemon jar (default: app/build/libs/app-all.jar). AN INPUT, NEVER
//                            BUILT FROM HERE — the shell fell back to a nested `:app:shadowJar`,
//                            and inside the gate the gradle slot is already held, so a nested
//                            gradle deadlocks or double-builds. A missing jar REFUSES, naming its
//                            producer (HARNESS_EXIT), exactly as `e2e oracle` and `e2e code-mode`.
//       RCP_CONTROL_PORT / RCP_HEAD_PORT / RCP_MOCK_PORT   defaults 3496 / 3499 / 3497
//       RCP_KEEP=1           keep the scratch dir (configs, daemon logs, recorded requests)
// =============================================================================================

export interface UpstreamRow {
  seq: number;
  scenario: string;
  outputs: number;
  status: number;
  include: string[] | null;
  input: Array<Record<string, unknown>>;
}

const COMPLETED = {
  type: "response.completed",
  response: { usage: { input_tokens: 10, output_tokens: 5, output_tokens_details: { reasoning_tokens: 3 } } },
};

const reasoningEvents = (rid: string, env: string): unknown[] => [
  { type: "response.reasoning_summary_text.delta", output_index: 0, delta: `plan ${rid} ` },
  { type: "response.output_item.done", output_index: 0, item: { type: "reasoning", id: rid, encrypted_content: env } },
];

const fnCall = (idx: number, callId: string, name: string): unknown[] => [
  { type: "response.output_item.added", output_index: idx, item: { type: "function_call", call_id: callId, name } },
  { type: "response.function_call_arguments.delta", output_index: idx, delta: "{}" },
  { type: "response.function_call_arguments.done", output_index: idx },
];

const textEvents = (idx: number, s: string): unknown[] => [
  { type: "response.output_item.added", output_index: idx, item: { type: "message" } },
  { type: "response.output_text.delta", output_index: idx, delta: s },
];

/** The scripted upstream. Scenario comes from the user text; the round comes from how many
 *  function_call_output items the transcript carries. Exported so the arms are red/green provable
 *  without a daemon. */
export function plan(scenario: string, outputs: number, hasReasoning: boolean): { status: number; payload: unknown } {
  if (scenario === "A") {
    if (outputs === 0) {
      return {
        status: 200,
        payload: [
          ...reasoningEvents("rs_a1", "env_a1"),
          ...fnCall(1, "call_a1a", "lookup_alpha"),
          ...fnCall(2, "call_a1b", "lookup_beta"),
          COMPLETED,
        ],
      };
    }
    if (outputs === 2) {
      return { status: 200, payload: [...reasoningEvents("rs_a2", "env_a2"), ...fnCall(1, "call_a2", "lookup_gamma"), COMPLETED] };
    }
    return { status: 200, payload: [...textEvents(0, "FINAL"), COMPLETED] };
  }
  if (scenario === "B") {
    if (outputs === 0) {
      return { status: 200, payload: [...reasoningEvents("rs_b1", "env_b1"), ...fnCall(1, "call_b1", "lookup_alpha"), COMPLETED] };
    }
    if (hasReasoning) {
      return {
        status: 400,
        payload: {
          error: { type: "invalid_request_error", message: "invalid_encrypted_content: could not decrypt reasoning item" },
        },
      };
    }
    return { status: 200, payload: [...textEvents(0, "RECOVERED"), COMPLETED] };
  }
  if (scenario === "OFF") {
    if (outputs === 0) {
      return { status: 200, payload: [...reasoningEvents("rs_o1", "env_o1"), ...fnCall(1, "call_o1", "lookup_alpha"), COMPLETED] };
    }
    return { status: 200, payload: [...textEvents(0, "FINAL"), COMPLETED] };
  }
  return { status: 500, payload: { error: { message: `mock: no scenario tag in request (${JSON.stringify(scenario)})` } } };
}

/**
 * The acceptance: assert the reasoning items on the UPSTREAM WIRE — presence, identity, position
 * (immediately before the turn's FIRST function_call), inject-once, strip-on-400, and the
 * cache-off 'before' state. Returns the failures (empty = green) and the two summary lines.
 */
export function assertWire(rows: UpstreamRow[]): { failures: string[]; summary: string[] } {
  const failures: string[] = [];
  const ok = (cond: boolean, msg: string): void => {
    if (!cond) failures.push(msg);
  };
  const pick = (scenario: string, outputs: number, status?: number): UpstreamRow[] => {
    const got = rows.filter(
      (r) => r.scenario === scenario && r.outputs === outputs && (status === undefined || r.status === status),
    );
    ok(got.length > 0, `no recorded request for scenario ${scenario} outputs=${outputs}${status === undefined ? "" : ` status=${status}`}`);
    return got;
  };
  const reasoningIds = (row: UpstreamRow): string[] =>
    row.input.filter((it) => it["type"] === "reasoning").map((it) => String(it["id"]));
  const idxOf = (row: UpstreamRow, type: string, field?: [string, string]): number | null => {
    const i = row.input.findIndex((it) => it["type"] === type && (!field || it[field[0]] === field[1]));
    return i < 0 ? null : i;
  };
  const inPosition = (row: UpstreamRow, rid: string, env: string, callId: string, label: string): void => {
    const ri = idxOf(row, "reasoning", ["id", rid]);
    const ci = idxOf(row, "function_call", ["call_id", callId]);
    ok(ri !== null, `${label}: reasoning ${rid} missing`);
    ok(ci !== null, `${label}: function_call ${callId} missing`);
    if (ri !== null && ci !== null) {
      ok(ci === ri + 1, `${label}: ${rid} at ${ri} not immediately before ${callId} at ${ci}`);
      ok(row.input[ri]!["encrypted_content"] === env, `${label}: ${rid} envelope mismatch`);
    }
  };

  // ON / A round 1: fresh turn — nothing to inject; include must request the envelopes back.
  for (const r of pick("A", 0)) {
    ok(reasoningIds(r).length === 0, `A r1 (seq ${r.seq}): fresh turn carries reasoning ${JSON.stringify(reasoningIds(r))}`);
    ok(
      (r.include ?? []).includes("reasoning.encrypted_content"),
      `A r1 (seq ${r.seq}): include lacks reasoning.encrypted_content — cache can never fill`,
    );
  }

  // ON / A round 2 (both parallel results in): ONE envelope, before the FIRST of the two calls.
  for (const r of pick("A", 2)) {
    ok(
      JSON.stringify(reasoningIds(r)) === JSON.stringify(["rs_a1"]),
      `A r2 (seq ${r.seq}): reasoning ids ${JSON.stringify(reasoningIds(r))}, wanted exactly ["rs_a1"]`,
    );
    inPosition(r, "rs_a1", "env_a1", "call_a1a", `A r2 (seq ${r.seq})`);
    const a = idxOf(r, "function_call", ["call_id", "call_a1a"]);
    const b = idxOf(r, "function_call", ["call_id", "call_a1b"]);
    if (a !== null && b !== null) {
      ok(b === a + 1, `A r2 (seq ${r.seq}): second parallel call not adjacent — item between? (inject-once violated)`);
    }
  }

  // ON / A round 3: both turns' envelopes, each in-position, none duplicated.
  for (const r of pick("A", 3)) {
    ok(
      JSON.stringify([...reasoningIds(r)].sort()) === JSON.stringify(["rs_a1", "rs_a2"]),
      `A r3 (seq ${r.seq}): reasoning ids ${JSON.stringify(reasoningIds(r))}, wanted [rs_a1, rs_a2]`,
    );
    inPosition(r, "rs_a1", "env_a1", "call_a1a", `A r3 (seq ${r.seq})`);
    inPosition(r, "rs_a2", "env_a2", "call_a2", `A r3 (seq ${r.seq})`);
  }

  // ON / B: the poisoned follow-up 400s, the retry is immediate, stripped, and otherwise intact.
  const bad = pick("B", 1, 400);
  const good = rows.filter((r) => r.scenario === "B" && r.outputs === 1 && r.status === 200);
  ok(bad.length === 1, `B: wanted exactly one 400'd request, got ${bad.length}`);
  ok(good.length === 1, `B: wanted exactly one stripped retry, got ${good.length}`);
  if (bad.length === 1 && good.length === 1) {
    const b0 = bad[0]!;
    const g0 = good[0]!;
    ok(JSON.stringify(reasoningIds(b0)) === JSON.stringify(["rs_b1"]), `B (seq ${b0.seq}): 400'd body should carry rs_b1`);
    ok(g0.seq === b0.seq + 1, "B: stripped retry was not the immediate next request");
    ok(reasoningIds(g0).length === 0, `B (seq ${g0.seq}): retry still carries reasoning`);
    ok(idxOf(g0, "function_call", ["call_id", "call_b1"]) !== null, `B (seq ${g0.seq}): retry lost the function_call`);
    const kept = b0.input.filter((it) => it["type"] !== "reasoning");
    ok(JSON.stringify(kept) === JSON.stringify(g0.input), `B (seq ${g0.seq}): retry differs beyond the stripped reasoning`);
  }

  // OFF: the status-quo 'before' — the follow-up is amnesiac on the wire.
  for (const r of pick("OFF", 1)) {
    ok(
      reasoningIds(r).length === 0,
      `OFF r2 (seq ${r.seq}): cache off must carry zero reasoning, got ${JSON.stringify(reasoningIds(r))}`,
    );
  }

  if (failures.length) return { failures, summary: [] };
  const onR2 = rows.filter((r) => r.scenario === "A" && r.outputs === 2)[0]!;
  return {
    failures,
    summary: [
      "  wire: before (cache off) round-2 carries 0 reasoning items — the model re-plans blind",
      `  wire: after (cache on) round-2 carries ${JSON.stringify(reasoningIds(onR2))} in-position before its ` +
        "function_call; 400 staleness strips-and-retries once; fresh turns untouched",
    ],
  };
}

/** Dummy ChatGPT auth: CodexAuthProvider reads the expiry from the access token's own `exp` JWT
 *  claim; a far-future claim means it never attempts a refresh, and the mock ignores the bearer. */
function probeAuthJson(): string {
  const b64url = (d: unknown): string => Buffer.from(JSON.stringify(d)).toString("base64url");
  const jwt = `${b64url({ alg: "none" })}.${b64url({ exp: 4102444800 })}.probe`;
  return JSON.stringify({
    tokens: { access_token: jwt, refresh_token: "rt_probe", account_id: "acct_probe" },
    last_refresh: "2026-01-01T00:00:00Z",
  });
}

function probeConfig(opts: { controlPort: number; headPort: number; mockPort: number; authFile: string; cache: boolean }): string {
  return `[daemon]
control_port = ${opts.controlPort}
show_reasoning = "text"
summary = "detailed"
replay_reasoning = false

[providers.codex]
dialect = "openai-responses"
base_url = "http://127.0.0.1:${opts.mockPort}"
auth = { kind = "chatgpt-oauth", file = "${opts.authFile}" }
quirks = { store = false, account_id_header = true, cache_key = "first-message-hash", effort_ceiling = "max", summary_field = true, reasoning_cache = ${opts.cache} }
[[providers.codex.models]]
id = "gpt-5.6-sol"
label = "Codex 5.6 Sol"
context_window = 400000

[heads.claudex]
provider = "codex"
port = ${opts.headPort}
discovery_prefix = "claude-codex--"
pinned_model = "gpt-5.6-sol"
[heads.claudex.claude]
command = "claudex"
`;
}

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

/** Anthropic-dialect client for one scripted agentic turn chain: POST /v1/messages, execute every
 *  tool_use with a scripted result, loop until end_turn. Asserts the CLIENT-side contract (no error
 *  events, expected final text, expected tool count); the wire truth is asserted from the recorder. */
async function probeClient(port: number, scenario: string, expectFinal: string, expectTools: number, mgmt: string): Promise<void> {
  const TOOLS = ["lookup_alpha", "lookup_beta", "lookup_gamma"].map((name) => ({
    name,
    description: "probe tool",
    input_schema: { type: "object", properties: {} },
  }));
  const messages: Array<Record<string, unknown>> = [
    { role: "user", content: `SCENARIO-${scenario} run the scripted task` },
  ];
  const toolsSeen: string[] = [];
  let finalText: string | null = null;

  for (let round = 0; round < 6; round++) {
    const res = await fetch(`http://127.0.0.1:${port}/v1/messages`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "anthropic-version": "2023-06-01", "x-api-key": mgmt },
      body: JSON.stringify({ model: "gpt-5.6-sol", max_tokens: 512, stream: true, messages, tools: TOOLS }),
      signal: AbortSignal.timeout(90_000),
    });
    const payload = await res.text();
    if (res.status !== 200) throw new FatalError(`head returned ${res.status}: ${payload.slice(0, 400)}`);
    interface Block {
      type?: string;
      name?: string;
      id?: string;
      text?: string;
      _text: string;
      _json: string;
    }
    const blocks = new Map<number, Block>();
    let stopReason: string | null = null;
    for (const frame of payload.split("\n\n")) {
      for (const line of frame.split("\n")) {
        if (!line.startsWith("data:")) continue;
        const ev = JSON.parse(line.slice(5).trim()) as Record<string, unknown>;
        const t = ev["type"];
        if (t === "error") throw new FatalError(`CLIENT-FAIL[${scenario}]: error event on the wire: ${JSON.stringify(ev).slice(0, 400)}`);
        if (t === "content_block_start") {
          blocks.set(Number(ev["index"]), { ...(ev["content_block"] as object), _text: "", _json: "" } as Block);
        } else if (t === "content_block_delta") {
          const d = ev["delta"] as Record<string, unknown>;
          const b = blocks.get(Number(ev["index"]));
          if (!b) continue;
          if (d["type"] === "text_delta") b._text += String(d["text"]);
          else if (d["type"] === "input_json_delta") b._json += String(d["partial_json"]);
        } else if (t === "message_delta") {
          stopReason = String((ev["delta"] as Record<string, unknown>)["stop_reason"] ?? "") || stopReason;
        }
      }
    }
    const content: Array<Record<string, unknown>> = [];
    const results: Array<Record<string, unknown>> = [];
    for (const [, b] of [...blocks.entries()].sort(([a], [c]) => a - c)) {
      if (b.type === "text" && b._text) content.push({ type: "text", text: b._text });
      else if (b.type === "tool_use") {
        toolsSeen.push(String(b.name));
        content.push({ type: "tool_use", id: b.id, name: b.name, input: JSON.parse(b._json || "{}") });
        results.push({ type: "tool_result", tool_use_id: b.id, content: "ok" });
      }
    }
    if (stopReason === "tool_use") {
      messages.push({ role: "assistant", content });
      messages.push({ role: "user", content: results });
      continue;
    }
    finalText = content.filter((c) => c["type"] === "text").map((c) => String(c["text"])).join("");
    break;
  }

  if (finalText === null) throw new FatalError(`CLIENT-FAIL[${scenario}]: turn chain never reached end_turn`);
  if (!finalText.includes(expectFinal)) {
    throw new FatalError(`CLIENT-FAIL[${scenario}]: final text ${JSON.stringify(finalText)} missing ${JSON.stringify(expectFinal)}`);
  }
  if (toolsSeen.length !== expectTools) {
    throw new FatalError(`CLIENT-FAIL[${scenario}]: ${toolsSeen.length} tool calls, wanted ${expectTools}: ${JSON.stringify(toolsSeen)}`);
  }
  note(`  client[${scenario}]: ${expectTools} tool(s) executed, final ${JSON.stringify(expectFinal)} — ok`);
}

async function reasoningCacheProbe(): Promise<number> {
  const controlPort = Number(process.env.RCP_CONTROL_PORT || 3496);
  const headPort = Number(process.env.RCP_HEAD_PORT || 3499);
  const mockPort = Number(process.env.RCP_MOCK_PORT || 3497);

  // THE JAR REFUSAL COMES FIRST, before a port is probed or a byte is written: the shell fell back
  // to a nested `./gradlew :app:shadowJar`, and inside the gate the gradle slot is already held, so
  // a nested gradle deadlocks or double-builds. Same contract, word for word, as oracle.ts and
  // code-mode.ts — name the producer, spawn nothing.
  const jar = process.env.RCP_JAR || join(layout().buildRoot, "app/build/libs/app-all.jar");
  if (!existsSync(jar)) {
    process.stderr.write(
      `HARNESS FAILURE: fat jar missing at ${jar} — build it first ` +
        "(bun tools/gate slot <label> -- :app:shadowJar) or pass RCP_JAR\n",
    );
    return HARNESS_EXIT;
  }

  for (const p of [controlPort, headPort, mockPort]) {
    if (await portInUse(p)) {
      note(`FATAL: port ${p} is in use — pick alternates via RCP_*_PORT (never the live daemon's 3096-3102)`);
      return 1;
    }
  }

  const scratch = mkdtempSync(join(tmpdir(), "splice-rcache-probe."));
  const record = join(scratch, "requests.jsonl");
  writeFileSync(record, "");
  const authFile = join(scratch, "auth.json");
  writeFileSync(authFile, probeAuthJson());
  mkdirSync(join(scratch, "state-on"), { recursive: true });
  mkdirSync(join(scratch, "state-off"), { recursive: true });
  const configOn = join(scratch, "splice-on.toml");
  const configOff = join(scratch, "splice-off.toml");
  writeFileSync(configOn, probeConfig({ controlPort, headPort, mockPort, authFile, cache: true }));
  writeFileSync(configOff, probeConfig({ controlPort, headPort, mockPort, authFile, cache: false }));

  // ── the recording mock upstream ────────────────────────────────────────────
  let seq = 0;
  const mock = Bun.serve({
    hostname: "127.0.0.1",
    port: mockPort,
    async fetch(req) {
      const raw = await req.text();
      const body = JSON.parse(raw || "{}") as { input?: unknown[]; include?: string[] };
      const scenario = ["OFF", "A", "B"].find((s) => raw.includes(`SCENARIO-${s}`)) ?? "?";
      const items = (body.input ?? []) as Array<Record<string, unknown>>;
      const outputs = items.filter((it) => it && it["type"] === "function_call_output").length;
      const hasReasoning = items.some((it) => it && it["type"] === "reasoning");
      const { status, payload } = plan(scenario, outputs, hasReasoning);
      seq += 1;
      appendFileSync(
        record,
        JSON.stringify({ seq, scenario, outputs, status, include: body.include ?? null, input: items }) + "\n",
      );
      if (status === 200) {
        const data = (payload as unknown[]).map((e) => `event: ${(e as { type: string }).type}\ndata: ${JSON.stringify(e)}\n\n`).join("");
        return new Response(data, { headers: { "Content-Type": "text/event-stream" } });
      }
      return new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json" } });
    },
  });

  let daemon: ReturnType<typeof Bun.spawn> | null = null;
  const startDaemon = async (config: string, stateDir: string, log: string): Promise<void> => {
    const opts = (process.env.SPLICE_JVM_OPTS || "-Xmx512m").split(/\s+/).filter(Boolean);
    const out = Bun.file(log).writer();
    daemon = Bun.spawn(["java", ...opts, "-jar", jar, "daemon"], {
      env: { ...process.env, SPLICE_CONFIG: config, CLAUDEX_STATE_DIR: stateDir },
      stdio: ["ignore", "pipe", "pipe"],
    });
    void (async () => {
      for await (const chunk of daemon!.stdout as ReadableStream<Uint8Array>) out.write(chunk);
      await out.end();
    })();
    for (let i = 0; i < 120; i++) {
      if (await reachable(`http://127.0.0.1:${headPort}/v1/models`, 2000)) return;
      if (daemon.exitCode !== null) throw new FatalError(`daemon died on boot (log: ${log})`);
      await sleep(250);
    }
    throw new FatalError(`head :${headPort} never became ready (log: ${log})`);
  };
  const stopDaemon = async (): Promise<void> => {
    if (!daemon) return;
    daemon.kill("SIGTERM");
    await daemon.exited;
    daemon = null;
    for (let i = 0; i < 40; i++) {
      if (!(await portInUse(controlPort)) && !(await portInUse(headPort))) return;
      await sleep(250);
    }
    throw new FatalError("daemon ports never freed after kill");
  };

  try {
    note("== pass ON (reasoning_cache = true) — scenarios A (3-tool, parallel round) + B (400 strip-retry)");
    await startDaemon(configOn, join(scratch, "state-on"), join(scratch, "daemon-on.log"));
    let mgmt = readFileSync(join(scratch, "state-on", "mgmt-key"), "utf8").trim();
    await probeClient(headPort, "A", "FINAL", 3, mgmt);
    await probeClient(headPort, "B", "RECOVERED", 1, mgmt);
    await stopDaemon();

    note("== pass OFF (reasoning_cache = false) — status-quo wire state");
    await startDaemon(configOff, join(scratch, "state-off"), join(scratch, "daemon-off.log"));
    mgmt = readFileSync(join(scratch, "state-off", "mgmt-key"), "utf8").trim();
    await probeClient(headPort, "OFF", "FINAL", 1, mgmt);
    await stopDaemon();

    const rows = readFileSync(record, "utf8").split("\n").filter((l) => l.trim()).map((l) => JSON.parse(l) as UpstreamRow);
    note(`== wire assertions over ${rows.length} recorded upstream requests`);
    const { failures, summary } = assertWire(rows);
    if (failures.length) {
      note("WIRE ASSERTIONS FAILED:");
      for (const f of failures) note(`  ✗ ${f}`);
      return 1;
    }
    for (const line of summary) note(line);
    note("reasoning-cache-probe: PASS");
    return 0;
  } finally {
    if (daemon) (daemon as { kill: (s: string) => void }).kill("SIGKILL");
    mock.stop(true);
    if (process.env.RCP_KEEP) note(`(kept scratch dir: ${scratch})`);
    else rmSync(scratch, { recursive: true, force: true });
  }
}

// ── the verb ─────────────────────────────────────────────────────────────────
/** `--selftest` is this verb's own bun test file; the status is what a shell would report. */
function selftest(): number {
  const file = resolve(import.meta.dir, "../../test/heads.test.ts");
  const child = Bun.spawnSync([process.execPath, "test", file], {
    cwd: layout().repoRoot,
    stdio: ["inherit", "inherit", "inherit"],
  });
  return exitStatusOf(child);
}

export async function heads(argv: readonly string[]): Promise<number> {
  const args = [...argv];
  let tier = "all";
  let onlyHead = "";
  let list = false;
  let probe = "";
  if (args[0] === "reasoning-cache") {
    probe = args.shift()!;
  }
  while (args.length) {
    const a = args.shift()!;
    switch (a) {
      case "--tier":
        tier = args.shift() ?? "";
        break;
      case "--head":
        onlyHead = args.shift() ?? "";
        break;
      case "--list":
        list = true;
        break;
      case "--selftest":
        return selftest();
      case "--probe":
        probe = args.shift() ?? "";
        break;
      default:
        process.stderr.write(`unknown arg: ${a}\n`);
        return HARNESS_EXIT;
    }
  }
  try {
    if (probe) {
      if (probe !== "reasoning-cache") {
        process.stderr.write(`e2e heads: no such probe "${probe}" — expected reasoning-cache\n`);
        return HARNESS_EXIT;
      }
      return await reasoningCacheProbe();
    }
    return await runHeads(tier, onlyHead, list);
  } catch (e) {
    if (e instanceof FatalError) {
      note(`FATAL: ${e.message}`);
      return 1;
    }
    throw e;
  }
}
