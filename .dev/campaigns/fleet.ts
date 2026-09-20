// NEW: V4-143 D2 — splice's fleet surface, ported from .dev/campaigns/manifest.py before it is deleted.
//
// Everything here is splice-only, which is why it is not in the vendored ledger.ts (VENDORED.md keeps
// those modules close to upstream so a re-vendor stays a diff). Two things live here:
//
//   THE JOURNAL. manifest.py appends one line per note, set-status and claim to the fleet journal
//   ($TORAD_FLEET_ROOT, default ~/.torad, then journal/events.jsonl), and torad's gym builds its
//   trajectory corpus from those claim -> set-status arcs (TrajectoryJoin.kt, RetroTrajectorySegmenter.kt).
//   16,306 lines when this was ported, 15,033 of them this CLI's. The writer is byte-compatible with
//   manifest.py's: the same mkdir lock the journal's Kotlin and Node producers take, the seq read
//   under that lock, py's json.dumps separators, the same canonical-ledger gate and the same
//   fail-open posture — a journal failure never breaks the ledger write it follows. Unlike py it
//   says so on stderr.
//
//   THE PORTED VERBS. verdict, handover, gym-kpi and events, with manifest.py's semantics, and
//   `next --claim`, which is what manifest.py's next-packet was FOR: pull the first eligible row,
//   claim it, print its packet (orchestrator ruling 2026-09-18, recorded on V4-143).
//
// torad's own audit (docs/research/fleet-quality/CLI-AUDIT.md:108) records this exact migration
// shipping once with the journal silently dead: after the move the canonical-ledger test was always
// false, every verb mutated the ledger and wrote nothing, and nothing caught it because the tests ran
// in the pre-migration layout. The gate below compares the ledger's directory with THIS file's, and
// checks/campaign-cli-selftest.sh proves a line lands with manifest.py absent.
import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, realpathSync, rmdirSync, statSync, writeSync } from "node:fs";
import { createConnection } from "node:net";
import { homedir } from "node:os";
import { basename, dirname, join } from "node:path";
import { findBlock, type Item, type ItemBlock, LedgerError, locateItems, mutate, notesOf, parseOrThrow } from "./ledger-core.ts";
import { claimNote, fenceOverlap, lastClaimOwner, type LedgerEvent, main, retirementMarker, withField, withNote } from "./ledger.ts";

const HERE = import.meta.dir;

// ── the journal ──────────────────────────────────────────────────────────────────────────────

const LOCK_RETRY_MS = 5;
const LOCK_TIMEOUT_MS = 2_000;
const LOCK_STALE_MS = 5_000;

function fleetRoot(): string {
  return process.env.TORAD_FLEET_ROOT || join(homedir(), ".torad");
}

export function journalPath(): string {
  return join(fleetRoot(), "journal", "events.jsonl");
}

/** manifest.py's `_is_canonical_ledger`: the ledger lives beside the CLI. Scratch copies never journal. */
export function isCanonicalLedger(ledgerPath: string): boolean {
  try {
    return dirname(realpathSync(ledgerPath)) === realpathSync(HERE);
  } catch {
    return false;
  }
}

/** Python's `json.dumps(value, ensure_ascii=False)`: the same escapes as JSON, with ", " and ": " between. */
export function pyJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(pyJson).join(", ")}]`;
  if (value !== null && typeof value === "object") {
    return `{${Object.entries(value).map(([key, v]) => `${JSON.stringify(key)}: ${pyJson(v)}`).join(", ")}}`;
  }
  return JSON.stringify(value);
}

/** The lock every journal producer takes (FleetJournal.kt, journal.mjs, manifest.py): an atomic mkdir. */
function acquireLock(lockPath: string): void {
  const deadline = Date.now() + LOCK_TIMEOUT_MS;
  for (;;) {
    try {
      mkdirSync(lockPath);
      return;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
      let stale = false;
      try { stale = Date.now() - statSync(lockPath).mtimeMs > LOCK_STALE_MS; } catch { stale = false; }
      if (stale) {
        try { rmdirSync(lockPath); } catch { /* another producer removed it first */ }
        continue;
      }
      if (Date.now() > deadline) throw new Error(`timed out waiting for journal lock at ${lockPath}`);
      Bun.sleepSync(LOCK_RETRY_MS);
    }
  }
}

function lastSeq(path: string): number {
  if (!existsSync(path)) return -1;
  let last = -1;
  for (const raw of readFileSync(path, "utf8").split("\n")) {
    const line = raw.trim();
    if (line === "") continue;
    try {
      const seq = (JSON.parse(line) as { seq?: unknown }).seq;
      if (Number.isInteger(seq) && (seq as number) > last) last = seq as number;
    } catch { /* a torn line mints no seq */ }
  }
  return last;
}

/** Append one record; `build` receives the seq and timestamp so both are minted under the lock. */
function appendJournal(ledgerPath: string, build: (seq: number, ts: string) => Record<string, unknown>): void {
  if (!isCanonicalLedger(ledgerPath)) return;
  const path = journalPath();
  try {
    mkdirSync(dirname(path), { recursive: true });
    const lockPath = `${path}.lock`;
    acquireLock(lockPath);
    try {
      const line = `${pyJson(build(lastSeq(path) + 1, new Date().toISOString()))}\n`;
      const fd = openSync(path, "a");
      try {
        writeSync(fd, line);
        fsyncSync(fd);
      } finally {
        closeSync(fd);
      }
    } finally {
      try { rmdirSync(lockPath); } catch { /* already gone */ }
    }
  } catch (error) {
    console.error(`journal: not written (${error instanceof Error ? error.message : String(error)}) — the ledger write stands`);
  }
}

// ── who is writing: manifest.py's `_canonical_seat` ──────────────────────────────────────────

function frame(payload: unknown): Buffer {
  const body = Buffer.from(JSON.stringify(payload), "utf8");
  const head = Buffer.alloc(4);
  head.writeUInt32BE(body.length);
  return Buffer.concat([head, body]);
}

/** seatd's C1 alive(seat) probe. Any failure is "could not verify", never an answer. */
function seatdAlive(seat: string, socketPath: string, token: string): Promise<boolean> {
  return new Promise((resolvePromise, reject) => {
    const socket = createConnection({ path: socketPath });
    let buffer = Buffer.alloc(0);
    let stage = 0;
    const fail = (error: Error): void => { socket.destroy(); reject(error); };
    socket.setTimeout(1_000, () => fail(new Error("seatd probe timed out")));
    socket.on("error", fail);
    socket.on("connect", () => socket.write(frame({ token })));
    socket.on("data", (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 4 && buffer.length >= 4 + buffer.readUInt32BE(0)) {
        const length = buffer.readUInt32BE(0);
        const reply = JSON.parse(buffer.subarray(4, 4 + length).toString("utf8")) as { ok?: boolean; reason?: unknown; result?: { alive?: unknown } };
        buffer = buffer.subarray(4 + length);
        if (!reply.ok) return fail(new Error(`seatd refused: ${String(reply.reason)}`));
        if (stage === 0) {
          stage = 1;
          socket.write(frame({ verb: "alive", args: { seat } }));
        } else {
          socket.end();
          if (typeof reply.result?.alive !== "boolean") return fail(new Error("malformed alive() result"));
          return resolvePromise(reply.result.alive);
        }
      }
    });
  });
}

let seatMemo: Promise<string | null> | undefined;

/** TORAD_SEAT verified by seatd, else the tmux session name, else null — manifest.py's order. */
export function canonicalSeat(): Promise<string | null> {
  seatMemo ??= (async () => {
    const seat = process.env.TORAD_SEAT;
    const token = process.env.TORAD_SEAT_TOKEN;
    const socketPath = join(fleetRoot(), "seatd.sock");
    if (seat && token && existsSync(socketPath)) {
      try {
        if (await seatdAlive(seat, socketPath, token)) return seat;
      } catch { /* unverified: fall through to tmux */ }
    }
    if (!process.env.TMUX) return null;
    const tmux = Bun.spawnSync(["tmux", "display-message", "-p", "#S"], { stdout: "pipe", stderr: "pipe", timeout: 3_000 });
    if (tmux.exitCode !== 0) return null;
    return tmux.stdout.toString().trim() || null;
  })();
  return seatMemo;
}

// ── claim lineage: manifest.py's `_claim_policy_lineage` ─────────────────────────────────────

const MODEL_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:/-]*$/;

/** Python's Path ordering: component by component, not as one string. */
function pathOrder(a: string, b: string): number {
  const pa = a.split("/");
  const pb = b.split("/");
  for (let i = 0; i < Math.min(pa.length, pb.length); i += 1) {
    if (pa[i] !== pb[i]) return pa[i]! < pb[i]! ? -1 : 1;
  }
  return pa.length - pb.length;
}

function transcriptCandidates(sessionId: string): string[] {
  const home = homedir();
  const roots: Array<[string, string]> = [
    [join(home, ".claude", "projects"), `*/${sessionId}.jsonl`],
    [join(home, ".codex", "sessions"), `**/*${sessionId}*.jsonl`],
    [join(home, ".torad", "turns"), `**/*${sessionId}*.jsonl`],
  ];
  for (const extra of (process.env.TORAD_TRANSCRIPT_ROOTS ?? "").split(":")) {
    if (extra.trim() !== "") roots.push([extra.trim(), `**/*${sessionId}*.jsonl`]);
  }
  const out: string[] = [];
  for (const [root, pattern] of roots) {
    try {
      if (!statSync(root).isDirectory()) continue;
      const found = [...new Bun.Glob(pattern).scanSync({ cwd: root, absolute: true, onlyFiles: true })];
      out.push(...found.sort(pathOrder));
    } catch { /* an unreadable root is skipped, as py skips it */ }
  }
  return out;
}

/** The last plausible model stamped in a transcript; the claim carries no time, so the last wins. */
function lastTranscriptModel(path: string): string | null {
  let model: string | null = null;
  let text: string;
  try { text = readFileSync(path, "utf8"); } catch { return null; }
  for (const raw of text.split("\n")) {
    if (!raw.includes('"model"')) continue;
    let obj: unknown;
    try { obj = JSON.parse(raw); } catch { continue; }
    if (obj === null || typeof obj !== "object" || Array.isArray(obj)) continue;
    const record = obj as Record<string, unknown>;
    for (const holder of [record.message, record.payload, record]) {
      if (holder !== null && typeof holder === "object" && typeof (holder as Record<string, unknown>).model === "string") {
        const candidate = ((holder as Record<string, unknown>).model as string).trim();
        if (candidate !== "" && MODEL_ID_RE.test(candidate)) model = candidate;
        break;
      }
    }
  }
  return model;
}

function policyLineage(sessionId: string): Record<string, string> | null {
  const lineage: Record<string, string> = {};
  const envModel = (process.env.TORAD_SEAT_MODEL ?? "").trim();
  if (envModel !== "") {
    lineage.modelId = envModel;
    lineage.modelIdBasis = "env";
  } else if (sessionId !== "") {
    for (const path of transcriptCandidates(sessionId)) {
      const model = lastTranscriptModel(path);
      if (model !== null) {
        lineage.modelId = model;
        lineage.modelIdBasis = "transcript";
        break;
      }
    }
  }
  const head = Bun.spawnSync(["git", "rev-parse", "HEAD"], { stdout: "pipe", stderr: "pipe", timeout: 3_000 });
  if (head.exitCode === 0 && head.stdout.toString().trim() !== "") lineage.baseSha = head.stdout.toString().trim();
  return Object.keys(lineage).length > 0 ? lineage : null;
}

// ── lease: manifest.py's `_parse_lease_clause` ───────────────────────────────────────────────

const LEASE_FIELDS = new Set(["mem", "cpu", "wall", "tier", "gpu"]);

function malformedLease(reason: string): never {
  throw new LedgerError(`malformed --lease clause: ${reason}`);
}

function positiveLeaseInteger(value: string, field: string, maximum?: number): void {
  if (!/^[0-9]+$/.test(value)) malformedLease(`${field} must be a positive decimal integer`);
  const parsed = Number(value);
  if (parsed <= 0) malformedLease(`${field} must be greater than zero`);
  if (maximum !== undefined && parsed > maximum) malformedLease(`${field} must be at most ${maximum}`);
}

export function parseLease(raw: string): Record<string, string> {
  if (raw === "" || raw !== raw.trim()) malformedLease("the clause must be non-empty and contain no surrounding whitespace");
  const declared: Record<string, string> = {};
  for (const component of raw.split(",")) {
    if (component === "" || component !== component.trim() || component.split("=").length !== 2) {
      malformedLease("each component must be exactly key=value with no whitespace");
    }
    const [key, value] = component.split("=") as [string, string];
    if (!LEASE_FIELDS.has(key)) malformedLease(`unknown field '${key}'`);
    if (key in declared) malformedLease(`duplicate field '${key}'`);
    if (value === "") malformedLease(`${key} requires a value`);
    if (key === "mem") {
      if (value.length < 2 || !"KMGT".includes(value.at(-1)!)) malformedLease("mem must be a positive integer followed by K, M, G, or T");
      positiveLeaseInteger(value.slice(0, -1), key);
    } else if (key === "cpu") {
      positiveLeaseInteger(value, key, 10_000);
    } else if (key === "wall") {
      if (value.length < 2 || !"smhd".includes(value.at(-1)!)) malformedLease("wall must be a positive integer followed by s, m, h, or d");
      positiveLeaseInteger(value.slice(0, -1), key);
    } else if (key === "tier") {
      if (!["A", "B", "C"].includes(value)) malformedLease("tier must be A, B, or C");
    } else {
      positiveLeaseInteger(value, key);
    }
    declared[key] = value;
  }
  return declared;
}

function flagValue(argv: readonly string[], name: string): string | null {
  const index = argv.indexOf(`--${name}`);
  return index === -1 ? null : (argv[index + 1] ?? null);
}

// ── the emit hook ledger.ts calls after each write ───────────────────────────────────────────

export async function journalLedgerEvent(event: LedgerEvent): Promise<void> {
  if (!isCanonicalLedger(event.ledgerPath)) return;
  const record = (verb: string, seat: string, extra: Record<string, unknown> = {}) =>
    appendJournal(event.ledgerPath, (seq, ts) => ({ seq, ts, episodeLabel: event.itemId, kind: "manifest_verb", verb, itemId: event.itemId, seat, ...extra }));
  if (event.verb === "claim") {
    const seat = event.seat ?? "unknown";
    const lineage = policyLineage(seat);
    record("claim", seat, lineage === null ? {} : { policyLineage: lineage });
    const lease = flagValue(event.args, "lease");
    if (lease !== null) {
      const declared = parseLease(lease);
      appendJournal(event.ledgerPath, (seq, ts) => ({ seq, ts, episodeLabel: event.itemId, kind: "lease-declared", itemId: event.itemId, seat, declared }));
    }
    return;
  }
  const seat = (await canonicalSeat()) ?? "unknown";
  if (event.verb === "set-status") record("set-status", seat, { status: event.status });
  else record("note", seat);
}

// ── verbs ────────────────────────────────────────────────────────────────────────────────────

const IDENTITY_RE = /^[A-Za-z0-9._@:-]+$/;

function identity(value: string | null, label: string): string {
  if (value === null || !IDENTITY_RE.test(value)) throw new LedgerError(`${label} must match ${IDENTITY_RE.source} (rejected: ${value === null ? "nothing" : JSON.stringify(value)})`);
  return value;
}

function oneLine(label: string, value: string): string {
  if (/[\r\n]/.test(value)) throw new LedgerError(`${label} must be a single line (newlines are refused: they would inject TOML into the ledger)`);
  return value;
}

function ownerOf(lines: readonly string[], block: ItemBlock): string | undefined {
  return block.item.claimedBy ?? (block.item.status === "in_flight" ? lastClaimOwner(lines, block) : undefined);
}

function assertNotRetired(lines: readonly string[], block: ItemBlock, override: boolean): void {
  const marker = retirementMarker(notesOf(lines, block));
  if (marker !== null && !override) {
    throw new LedgerError(`${block.item.id} carries a RETIREMENT marker in its own notes — refusing without an explicit override.\n  ${marker}\n` +
      `If this is a fresh, operator-approved re-queue, pass --override-retired — or record the lift: note ${block.item.id} "RETIRE-LIFTED: <ruling>"`);
  }
}

/** A live owner hands its claim to another seat: the row stays in_flight, the owner changes. */
async function handover(ledgerPath: string, rest: readonly string[]): Promise<number> {
  const id = rest[0];
  if (id === undefined || id.startsWith("--")) throw new LedgerError("handover requires <ID> --to <seat> --by <owner-seat>");
  const to = identity(flagValue(rest, "to"), "--to");
  const by = identity(flagValue(rest, "by"), "--by");
  const override = rest.includes("--override-retired");
  await mutate(ledgerPath, (current) => {
    const block = findBlock(locateItems(current), id);
    assertNotRetired(current, block, override);
    if (block.item.status !== "in_flight") throw new LedgerError(`item ${id} is ${block.item.status} — handover only in_flight items`);
    const owner = ownerOf(current, block);
    if (owner !== by) {
      throw new LedgerError(`item ${id} is owned by ${owner ?? "nobody"}; you are ${by} — handover must be run by the owner seat; if the owner is dead, release ${id} first`);
    }
    const at = new Date();
    let next = withField(current, block, "claimed_by", to);
    next = withField(next, findBlock(locateItems(next), id), "claimed_at", at.toISOString());
    return withNote(next, findBlock(locateItems(next), id), `${claimNote(to, at)} handover-from=${by}`);
  });
  console.log(`${id} -> handed over to ${to}`);
  appendJournal(ledgerPath, (seq, ts) => ({ seq, ts, episodeLabel: id, kind: "manifest_verb", verb: "handover", itemId: id, seat: by }));
  return 0;
}

const VERDICT_RE = /^VERDICT: outcome=(\w+)/;

/** The note body after manifest.py's `# [date] ` prefix, or null for a line that is not a dated note. */
function noteBody(note: string): string | null {
  if (!note.startsWith("# [")) return null;
  const at = note.indexOf("] ");
  return at === -1 ? null : note.slice(at + 2).trim();
}

/** ONE-REVIEW-PER-ROW: accepted | blocked | redo -> accepted | redo -> blocked, nothing else. */
function oneReviewPerRow(notes: readonly string[], id: string, outcome: string): string | null {
  const prior = notes.map((note) => VERDICT_RE.exec(noteBody(note) ?? "")?.[1]).filter((o): o is string => o !== undefined);
  if (prior.some((o) => o === "accepted" || o === "blocked")) {
    return `${id} already carries a terminal verdict (${prior.join(", ")}) — ONE-REVIEW-PER-ROW: a closed row is never re-reviewed. ` +
      "Reopening is an operator act (dated note + set-status), never a second verdict.";
  }
  if (outcome === "redo" && prior.includes("redo")) {
    return `${id} already has a redo verdict — ONE-REVIEW-PER-ROW: one adversarial review round per item. ` +
      "Fix the named gap forward and close with accepted|blocked; a second redo is a second review round.";
  }
  return null;
}

/** The typed review verdict: a dated ledger note and the orchestrator_verdict journal record, one act. */
async function verdict(ledgerPath: string, rest: readonly string[]): Promise<number> {
  const id = rest[0];
  if (id === undefined || id.startsWith("--")) throw new LedgerError("verdict requires <ID> --outcome accepted|redo|blocked [--gap TEXT] [--contradiction LOCUS] [--env-failure]");
  const outcome = flagValue(rest, "outcome");
  if (outcome !== "accepted" && outcome !== "redo" && outcome !== "blocked") throw new LedgerError("verdict --outcome must be accepted|redo|blocked");
  const gapRaw = flagValue(rest, "gap");
  const gap = gapRaw === null || gapRaw.trim() === "" ? null : oneLine("--gap", gapRaw.trim());
  const contradictionRaw = flagValue(rest, "contradiction");
  const locus = contradictionRaw === null ? null : oneLine("--contradiction", contradictionRaw.trim());
  const envFailure = rest.includes("--env-failure");
  if (outcome === "redo" && gap === null) throw new LedgerError("a redo verdict requires --gap <the named gap> (#948 §2b redo-not-accept)");
  if (outcome === "accepted" && gap !== null) throw new LedgerError("an accepted verdict must not carry --gap");
  if (contradictionRaw !== null && !locus) throw new LedgerError("--contradiction requires a non-empty locus");
  const parts = [`VERDICT: outcome=${outcome}`];
  if (gap) parts.push(`gap=${gap}`);
  if (locus) parts.push(`CONTRADICTION locus=${locus}`);
  if (envFailure) parts.push("env-failure=true");
  const text = parts.join(" ");
  const seat = (await canonicalSeat()) ?? "unknown";
  let owner: string | undefined;
  await mutate(ledgerPath, (current) => {
    const block = findBlock(locateItems(current), id);
    const refusal = oneReviewPerRow(notesOf(current, block), id, outcome);
    if (refusal !== null) throw new LedgerError(refusal);
    owner = lastClaimOwner(current, block) ?? block.item.claimedBy;
    return withNote(current, block, text);
  });
  appendJournal(ledgerPath, (seq, ts) => ({
    seq, ts, episodeLabel: id, kind: "orchestrator_verdict", itemId: id, seat, outcome,
    gapNamed: gap, contradictionFound: locus !== null, contradictionLocus: locus, envFailure,
  }));
  console.log(`verdict recorded on ${id}: ${text}`);
  const stem = basename(ledgerPath).replace(/\.toml$/, "");
  console.log(`NOW POKE THE BUILDER (one action with this record, #991 §1): mcp__torad-fleet__sendMessage to='${owner ?? "<builder-seat>"}' message='${id} verdict: ${outcome} — see ledger (${stem})'`);
  return 0;
}

/** Python's `%.1f`: correct rounding of the double, half-even on an exact tie. */
function fixed1(x: number): string {
  const twenty = x * 20;
  if (Number.isInteger(twenty) && Math.abs(twenty) % 2 === 1) {
    const down = Math.floor(x * 10);
    return ((down % 2 === 0 ? down : down + 1) / 10).toFixed(1);
  }
  return x.toFixed(1);
}

function pct(numerator: number, denominator: number): string {
  return denominator === 0 ? "n/a" : `${fixed1((numerator * 100) / denominator)}%`;
}

function closedItems(ledgerPaths: readonly string[]): Set<string> {
  const closed = new Set<string>();
  for (const path of ledgerPaths) {
    let current: string | null = null;
    let text: string;
    try { text = readFileSync(path, "utf8"); } catch { continue; }
    for (const raw of text.split("\n")) {
      const line = raw.trim();
      if (line.startsWith('id = "') && line.endsWith('"')) current = line.slice(6, -1);
      else if (line.startsWith('status = "') && current) {
        const status = line.slice(10, -1);
        if (status === "done" || status === "verified") closed.add(current);
      }
    }
  }
  return closed;
}

function journalEntries(): Array<{ raw: string; entry: Record<string, unknown> }> | null {
  let text: string;
  try { text = readFileSync(journalPath(), "utf8"); } catch { return null; }
  const out: Array<{ raw: string; entry: Record<string, unknown> }> = [];
  for (const rawLine of text.split("\n")) {
    const raw = rawLine.trim();
    if (raw === "") continue;
    try {
      const entry = JSON.parse(raw) as unknown;
      if (entry !== null && typeof entry === "object" && !Array.isArray(entry)) out.push({ raw, entry: entry as Record<string, unknown> });
    } catch { /* a torn line is skipped */ }
  }
  return out;
}

/** The campaign KPI, read-only over the journal and every ledger beside this one. */
function gymKpi(ledgerPath: string): number {
  const now = new Date();
  const windowStart = now.getTime() - 7 * 86_400_000;
  const dir = dirname(realpathSync(ledgerPath));
  const ledgerPaths = [...new Bun.Glob("*.toml").scanSync({ cwd: dir, absolute: true })].sort();
  const closed = closedItems(ledgerPaths);
  const entries = journalEntries();
  if (entries === null) throw new LedgerError("no readable journal — the KPI instrument needs the C4 journal");
  const verdictItems = new Set<string>();
  const backfilledItems = new Set<string>();
  const claims: Array<[string | null, boolean, boolean]> = [];
  const stamped = new Set<string>();
  const active = new Set<string>();
  let contradictions = 0;
  for (const { entry } of entries) {
    const ts = typeof entry.ts === "string" ? Date.parse(entry.ts) : Number.NaN;
    const inWindow = !Number.isNaN(ts) && ts >= windowStart;
    if (entry.kind === "orchestrator_verdict") {
      if (typeof entry.itemId === "string") {
        verdictItems.add(entry.itemId);
        if (entry.backfilled === true) backfilledItems.add(entry.itemId);
      }
      if (entry.contradictionFound === true) contradictions += 1;
    } else if (entry.kind === "manifest_verb" && entry.verb === "claim") {
      const lineage = entry.policyLineage;
      const hasModel = lineage !== null && typeof lineage === "object" && !Array.isArray(lineage) && Boolean((lineage as Record<string, unknown>).modelId);
      claims.push([typeof entry.itemId === "string" ? entry.itemId : null, inWindow, hasModel]);
    } else if (entry.kind === "seatd_lifecycle" && entry.event === "policy_lineage_stamped") {
      if (typeof entry.episodeLabel === "string" && entry.episodeLabel.trim() !== "") stamped.add(entry.episodeLabel);
    }
    const label = entry.episodeLabel;
    if (inWindow && entry.backfilled !== true && typeof label === "string" && label.trim() !== "") active.add(label);
  }
  const attributed = (item: string | null, hasModel: boolean): boolean => hasModel || (item !== null && stamped.has(item));
  const withModel = claims.filter(([item, , model]) => attributed(item, model)).length;
  const inWindow = claims.filter(([, w]) => w);
  const inWindowWithModel = inWindow.filter(([item, , model]) => attributed(item, model)).length;
  const covered = [...closed].filter((id) => verdictItems.has(id)).length;
  const coveredBackfilled = [...closed].filter((id) => backfilledItems.has(id)).length;
  console.log(`gym-kpi @ ${now.toISOString().replace(/\.\d{3}Z$/, "+00:00")} (window: 7d, ledgers: ${ledgerPaths.length})`);
  console.log(`  verdict-coverage:      ${covered}/${closed.size} closed items typed-verdicted (${pct(covered, closed.size)}; ${coveredBackfilled} via backfill)`);
  console.log(`  lineage-completeness:  overall ${withModel}/${claims.length} claims lineage-attributed (${pct(withModel, claims.length)}); 7d ${inWindowWithModel}/${inWindow.length} (${pct(inWindowWithModel, inWindow.length)})`);
  console.log(`  trajectories-7d:       ${active.size} distinct episodes active`);
  console.log(`  honesty-yield:         ${contradictions} typed contradiction record(s) all-time`);
  return 0;
}

/** NOTIFY-1's read-only journal query: the orchestrator's Monitor condition and its drain. Exit 0 = matches. */
function events(rest: readonly string[]): number {
  const options: Record<string, string> = {};
  for (let i = 0; i < rest.length; i += 2) {
    const name = rest[i]!;
    if (!name.startsWith("--") || rest[i + 1] === undefined) throw new LedgerError("usage: events [--after-seq N] [--exclude-seat S] [--verbs v1,v2]");
    options[name.slice(2)] = rest[i + 1]!;
  }
  const afterText = options["after-seq"];
  if (afterText !== undefined && !/^[+-]?\d+$/.test(afterText.trim())) throw new LedgerError(`--after-seq must be an integer, got '${afterText}'`);
  const after = afterText === undefined ? -1 : Number.parseInt(afterText.trim(), 10);
  const wanted = new Set((options.verbs ?? "set-status,claim,handover").split(",").map((v) => v.trim()).filter(Boolean));
  const excluded = (options["exclude-seat"] ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  const matches: string[] = [];
  for (const { raw, entry } of journalEntries() ?? []) {
    if (!Number.isInteger(entry.seq) || (entry.seq as number) <= after) continue;
    if (entry.kind !== "manifest_verb" || !wanted.has(String(entry.verb))) continue;
    const seat = String(entry.seat ?? "");
    if (excluded.some((ex) => seat === ex || seat.startsWith(`${ex}~`))) continue;
    matches.push(raw);
  }
  for (const line of matches) console.log(line);
  return matches.length > 0 ? 0 : 1;
}

/** POSIX shlex.split: quotes and backslashes; an unclosed quote throws, as py's ValueError. */
function shlexSplit(text: string): string[] {
  const out: string[] = [];
  let token = "";
  let inToken = false;
  let quote: '"' | "'" | null = null;
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i]!;
    if (quote === "'") {
      if (c === "'") quote = null; else token += c;
    } else if (quote === '"') {
      if (c === '"') quote = null;
      else if (c === "\\" && (text[i + 1] === '"' || text[i + 1] === "\\")) { token += text[i + 1]; i += 1; }
      else token += c;
    } else if (/\s/.test(c)) {
      if (inToken) { out.push(token); token = ""; inToken = false; }
    } else if (c === "'" || c === '"') {
      quote = c; inToken = true;
    } else if (c === "\\") {
      if (i + 1 >= text.length) throw new Error("No escaped character");
      token += text[i + 1]; i += 1; inToken = true;
    } else {
      token += c; inToken = true;
    }
  }
  if (quote !== null) throw new Error("No closing quotation");
  if (inToken) out.push(token);
  return out;
}

/** manifest.py's `_packet_eligible`: the row-level reasons a packet must not be handed out. */
function packetIneligible(item: Item): string | null {
  if (item.files.length === 0 || item.files.some((f) => f.trim() === "")) return "item missing files";
  if (item.verify.trim() === "") return "item missing verify";
  if (item.verify.trim().startsWith("TBD")) return "item verify starts with TBD";
  let tokens: string[];
  try { tokens = shlexSplit(`${item.title} ${item.verify}`); } catch { tokens = `${item.title} ${item.verify}`.split(/\s+/); }
  if (tokens.some((t) => ["rm", "delete", "clean", "mv", "move"].includes(t)) && item.files.some((f) => f.includes("*"))) {
    return "destructive item with un-enumerated fence";
  }
  return null;
}

function campaignNext(text: string, ledgerPath: string): string[] {
  const parsed = parseOrThrow(text, ledgerPath) as { campaign?: { next?: unknown } };
  const next = parsed.campaign?.next;
  return Array.isArray(next) ? next.filter((v): v is string => typeof v === "string") : [];
}

/**
 * THE PULL: what manifest.py's next-packet was for (orchestrator ruling 2026-09-18). Candidates are
 * the todo rows campaign.next lists, in its order, then every other todo row in ledger order —
 * curation is a preference, not a gate. A row is offered only if it is not retired, passes packet
 * eligibility, and shares no fence with an in_flight row, so the claim is never handed a row it
 * refuses; a claim that still refuses (another seat's review milestone) moves on to the next.
 * No review-debt gate: review runs once, at deliver. Nothing eligible exits 1 with every reason.
 */
async function nextClaim(ledgerPath: string, seat: string): Promise<number> {
  const text = await Bun.file(ledgerPath).text();
  const lines = text.split("\n");
  const blocks = locateItems(lines);
  const byId = new Map(blocks.map((b) => [b.item.id, b]));
  const queued = campaignNext(text, ledgerPath);
  const order = [...queued.filter((id) => byId.get(id)?.item.status === "todo"), ...blocks.filter((b) => b.item.status === "todo" && !queued.includes(b.item.id)).map((b) => b.item.id)];
  const inFlight = blocks.filter((b) => b.item.status === "in_flight");
  const reasons: string[] = [];
  for (const id of order) {
    const block = byId.get(id)!;
    const marker = retirementMarker(notesOf(lines, block));
    if (marker !== null) { reasons.push(`${id}: retired`); continue; }
    const ineligible = packetIneligible(block.item);
    if (ineligible !== null) { reasons.push(`${id}: ${ineligible}`); continue; }
    if (block.item.claimedBy !== undefined && block.item.claimedBy !== seat) { reasons.push(`${id}: claimed by ${block.item.claimedBy}`); continue; }
    const blocker = inFlight.find((peer) => fenceOverlap(block.item.files, peer.item.files).length > 0);
    if (blocker !== undefined) { reasons.push(`${id}: fence overlaps with in_flight ${blocker.item.id}`); continue; }
    try {
      await main([ledgerPath, "claim", id, "--seat", seat]);
    } catch (error) {
      // The one row-level refusal no filter above can see: another seat holds this row's review
      // milestone. Anything else — provenance, the lock — is the ledger's, not the row's, and a
      // pull that recorded it as a reason and moved on would report "nothing eligible" about a
      // ledger it could not write at all.
      if (!(error instanceof LedgerError) || !error.message.startsWith("one builder works a review milestone")) throw error;
      reasons.push(`${id}: ${error.message.split("\n")[0]}`);
      continue;
    }
    return await main([ledgerPath, "packet", id]);
  }
  console.error(`queue empty or blocked: ${reasons.length === 0 ? "no todo rows" : reasons.join("; ")}`);
  return 1;
}

// ── fence instruments (orchestrator ruling 2026-09-18: each is a law's instrument) ─────────────

/** Python's `repr()` of a str, which manifest.py prints patterns and fence entries with. */
function pyRepr(s: string): string {
  const quote = s.includes("'") && !s.includes('"') ? '"' : "'";
  let out = quote;
  for (const ch of s) {
    const code = ch.codePointAt(0)!;
    if (ch === "\\") out += "\\\\";
    else if (ch === quote) out += `\\${quote}`;
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else if (code < 0x20 || code === 0x7f) out += `\\x${code.toString(16).padStart(2, "0")}`;
    else out += ch;
  }
  return out + quote;
}

function gitRoot(ledgerPath: string): string | null {
  const r = Bun.spawnSync(["git", "-C", dirname(realpathSync(ledgerPath)), "rev-parse", "--show-toplevel"], { stdout: "pipe", stderr: "pipe" });
  const out = r.stdout.toString().trim();
  return r.exitCode === 0 && out !== "" ? out : null;
}

/** The raw item table as filed, the way manifest.py reads it with tomllib. */
function rawItems(ledgerPath: string): Array<Record<string, unknown>> {
  const parsed = parseOrThrow(readFileSync(ledgerPath, "utf8"), ledgerPath) as { items?: unknown };
  return Array.isArray(parsed.items) ? parsed.items.filter((i): i is Record<string, unknown> => i !== null && typeof i === "object") : [];
}

function rawItem(ledgerPath: string, id: string): Record<string, unknown> {
  const item = rawItems(ledgerPath).find((i) => i.id === id);
  if (item === undefined) throw new LedgerError(`item '${id}' not found`);
  return item;
}

const FENCE_CHECK_EXCLUDE_DIRS = [".git", "node_modules", ".gradle", "build", "dist"];

/** Every tree file whose CONTENT names `pattern` literally must sit inside the row's fence. */
function fenceCheck(ledgerPath: string, id: string, pattern: string): number {
  const files = rawItem(ledgerPath, id).files;
  if (!Array.isArray(files)) throw new LedgerError(`item '${id}' has no files= fence`);
  const fence = files.filter((f): f is string => typeof f === "string");
  const root = gitRoot(ledgerPath) ?? process.cwd();
  const grep = Bun.spawnSync(["grep", "-rlIF", ...FENCE_CHECK_EXCLUDE_DIRS.flatMap((d) => ["--exclude-dir", d]), "--", pattern, "."], { cwd: root, stdout: "pipe", stderr: "pipe" });
  if (grep.exitCode !== 0 && grep.exitCode !== 1) throw new LedgerError(`fence-check grep failed: ${grep.stderr.toString().trim()}`);
  const hits = grep.stdout.toString().split("\n").map((l) => l.trim()).filter(Boolean).map((l) => (l.startsWith("./") ? l.slice(2) : l)).sort();
  const shown = `fence-check ${id} ${pyRepr(pattern)}`;
  if (hits.length === 0) {
    console.log(`PASS: ${shown} — 0 matches in the tree (zero-match: double-check the pattern is correct).`);
    return 0;
  }
  // manifest.py's `_fence_covers_file`, deliberately narrower than the overlap normalizer:
  // an entry covers a file it equals, or one beneath it when it ends in '/'.
  const uncovered = hits.filter((f) => !fence.some((e) => f === e || (e.endsWith("/") && f.startsWith(e))));
  if (uncovered.length > 0) {
    console.log(`FAIL: ${shown} — ${uncovered.length} of ${hits.length} matching file(s) NOT covered by the fence:`);
    for (const f of uncovered) console.log(`  - ${f}`);
    return 1;
  }
  console.log(`PASS: ${shown} — all ${hits.length} matching file(s) covered by the fence.`);
  return 0;
}

/** Inventory of fence entries that are bare directories (a trailing '/', no glob): non-blocking. */
function scanBareFences(ledgerPath: string): number {
  const found: Array<[string, string[]]> = [];
  for (const item of rawItems(ledgerPath)) {
    if (typeof item.id !== "string" || !Array.isArray(item.files)) continue;
    const bare = item.files.filter((f): f is string => typeof f === "string" && ((n) => n.endsWith("/") && !n.includes("*"))(f.trim().replaceAll("\\", "/")));
    if (bare.length > 0) found.push([item.id, bare]);
  }
  if (found.length === 0) {
    console.log("scan-bare-fences: OK — no bare-directory fence entries.");
    return 0;
  }
  const total = found.reduce((n, [, entries]) => n + entries.length, 0);
  console.log(`scan-bare-fences: ${found.length} item(s), ${total} bare-directory fence entry(ies) (inventory, non-blocking):`);
  for (const [id, entries] of found) for (const entry of entries) console.log(`  ${id}: ${pyRepr(entry)}`);
  return 0;
}

function normalizeScope(scope: string): string {
  let n = scope.replaceAll("\\", "/").trim();
  if (n === "") return "";
  if (n.endsWith("/**")) n = n.slice(0, -3);
  else if (n.endsWith("*")) n = n.slice(0, -1);
  return n.replace(/\/+$/, "");
}

const ATTEST_EXCLUDED = [".claude/ledger-diffs/", ".claude/state/", ".dev/campaigns/traces/"];

/**
 * G52: every uncommitted path INSIDE this row's fence, from `git status` — a file the row created or
 * changed that no commit carries yet. The campaign ledgers themselves are reported as `warned`, not
 * `dirty`: they are always dirty mid-campaign and that is not the row's missing code. Exit 1 on dirty.
 */
function fenceUncommitted(ledgerPath: string, id: string): number {
  const item = rawItems(ledgerPath).find((i) => i.id === id);
  if (item === undefined) throw new LedgerError(`item '${id}' not found in ${ledgerPath}`);
  const files = Array.isArray(item.files) ? item.files.map(String) : [];
  const repo = gitRoot(ledgerPath);
  if (repo === null || files.length === 0) {
    console.log(pyJson({ dirty: [], warned: [], available: repo !== null }));
    return 0;
  }
  const status = Bun.spawnSync(["git", "status", "--porcelain"], { cwd: repo, stdout: "pipe", stderr: "pipe" });
  const paths = status.stdout.toString().split("\n").filter((l) => l.trim() !== "").map((l) => {
    const entry = l.slice(3);
    return entry.includes(" -> ") ? entry.split(" -> ")[1]! : entry;
  });
  const scopes = files.map(normalizeScope).filter(Boolean);
  const matched = paths.filter((p) => {
    const n = p.replaceAll("\\", "/");
    return scopes.some((s) => n === s || n.startsWith(`${s}/`));
  });
  const excluded = (p: string): boolean => {
    const n = p.replaceAll("\\", "/");
    return ATTEST_EXCLUDED.some((prefix) => n.startsWith(prefix)) || (n.startsWith(".dev/campaigns/") && n.endsWith(".toml"));
  };
  const dirty = matched.filter((p) => !excluded(p));
  console.log(pyJson({ dirty, warned: matched.filter(excluded), available: true }));
  return dirty.length > 0 ? 1 : 0;
}

// ── selftest ─────────────────────────────────────────────────────────────────────────────────

/**
 * The ported verbs, run through splice's real entry in a throwaway POST-deletion layout: every .ts
 * beside this file, no manifest.py, a git repo with one commit (claim lineage reads HEAD), and a
 * scratch TORAD_FLEET_ROOT so nothing reaches the real journal. The layout is the point: torad's
 * journal died in exactly the layout its tests never ran (CLI-AUDIT.md:108).
 */
export async function fleetSelftest(): Promise<number> {
  const { mkdtempSync, readdirSync, rmSync, copyFileSync } = await import("node:fs");
  const { tmpdir } = await import("node:os");
  const root = mkdtempSync(join(process.env.TMPDIR ?? tmpdir(), "fleet-selftest-"));
  const dir = join(root, ".dev", "campaigns");
  mkdirSync(dir, { recursive: true });
  for (const name of readdirSync(HERE)) if (name.endsWith(".ts")) copyFileSync(join(HERE, name), join(dir, name));
  const fleetRootDir = join(root, "fleet");
  const ledger = join(dir, "fleet-selftest.toml");
  const journal = join(fleetRootDir, "journal", "events.jsonl");
  const env = { ...process.env, TORAD_FLEET_ROOT: fleetRootDir, TORAD_SEAT: "", TORAD_SEAT_TOKEN: "", TMUX: "", TORAD_SEAT_MODEL: "", LEDGER_ORCHESTRATOR: "1", LEDGER_SEAT: "" };
  const sh = (...args: string[]) => Bun.spawnSync(args, { cwd: root, env, stdout: "pipe", stderr: "pipe" });
  const run = (...args: string[]): { code: number; out: string } => {
    const r = Bun.spawnSync(["bun", join(dir, "manifest.ts"), ledger, ...args], { cwd: root, env, stdout: "pipe", stderr: "pipe" });
    return { code: r.exitCode ?? 1, out: r.stdout.toString() + r.stderr.toString() };
  };
  const records = (): Array<Record<string, unknown>> =>
    existsSync(journal) ? readFileSync(journal, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l) as Record<string, unknown>) : [];
  const rawLines = (): string[] => (existsSync(journal) ? readFileSync(journal, "utf8").split("\n").filter(Boolean) : []);
  let checks = 0;
  let failures = 0;
  const check = (label: string, ok: boolean, detail = ""): void => {
    checks += 1;
    if (ok) return;
    failures += 1;
    console.error(`  FAIL  ${label}${detail === "" ? "" : `\n        ${detail.slice(0, 400)}`}`);
  };
  console.log("fleet selftest");
  try {
    sh("git", "init", "-q");
    sh("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "base");
    const head = sh("git", "rev-parse", "HEAD").stdout.toString().trim();
    check("the layout carries no manifest.py (the post-deletion layout)", !existsSync(join(dir, "manifest.py")));
    check("init creates the ledger", run("init", "fleet selftest", "--rows", "20").code === 0);
    const add = (id: string, title: string, files: string, verify = "true") => run("add", "--id", id, "--phase", "f1", "--title", title, "--verify", verify, "--files", files);
    for (const [id, title, files, verify] of [
      ["F0", "the in_flight peer", "src/shared.ts", "true"],
      ["F1", "retired row", "src/f1.ts", "true"],
      ["F2", "no verify yet", "src/f2.ts", "TBD later"],
      ["F3", "shares a fence with F0", "src/shared.ts", "true"],
      ["F4", "rm the old fixtures", "src/old/*", "true"],
      ["F5", "eligible, in ledger order", "src/f5.ts", "true"],
      ["F6", "eligible and queued", "src/f6.ts", "true"],
    ] as const) add(id, title, files, verify);

    // the journal of the vendored verbs, through the emit hooks
    run("note", "F0", "a first note");
    const first = rawLines()[0] ?? "";
    check("a note journals manifest.py's exact line shape",
      new RegExp(`^\\{"seq": 0, "ts": "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z", "episodeLabel": "F0", "kind": "manifest_verb", "verb": "note", "itemId": "F0", "seat": "unknown"\\}$`).test(first), first);
    run("claim", "F0", "--session", "seat-a");
    const claimLine = records().at(-1) ?? {};
    check("claim journals the seat and a policyLineage carrying HEAD",
      claimLine.verb === "claim" && claimLine.seat === "seat-a" && (claimLine.policyLineage as Record<string, unknown> | undefined)?.baseSha === head, JSON.stringify(claimLine));
    check("seq is minted from the journal's tail", records().map((r) => r.seq).join(",") === "0,1");
    run("set-status", "F0", "blocked");
    run("set-status", "F0", "in_flight");
    check("set-status journals the status value", records().at(-1)?.status === "in_flight" && records().at(-1)?.verb === "set-status");
    const scratchCopy = join(root, "elsewhere.toml");
    copyFileSync(ledger, scratchCopy);
    const before = rawLines().length;
    Bun.spawnSync(["bun", join(dir, "manifest.ts"), scratchCopy, "note", "F0", "on a scratch copy"], { cwd: root, env, stdout: "pipe", stderr: "pipe" });
    check("a ledger outside the CLI's directory journals nothing", rawLines().length === before);

    // lease
    const bytes = readFileSync(ledger, "utf8");
    check("a malformed --lease is refused before the claim writes",
      run("claim", "F5", "--session", "seat-b", "--lease", "mem=12X").out.includes("malformed --lease") && readFileSync(ledger, "utf8") === bytes);
    Bun.spawnSync(["bun", join(dir, "manifest.ts"), ledger, "claim", "F6", "--session", "seat-l", "--lease", "mem=12G,cpu=8,wall=20m,tier=B"], { cwd: root, env: { ...env, TORAD_SEAT_MODEL: "model-x" }, stdout: "pipe", stderr: "pipe" });
    const [leaseClaim, leaseLine] = rawLines().slice(-2);
    check("TORAD_SEAT_MODEL names the model in the claim's lineage, in manifest.py's key order",
      (leaseClaim ?? "").includes(`"policyLineage": {"modelId": "model-x", "modelIdBasis": "env", "baseSha": "${head}"}`), leaseClaim);
    check("a lease is journalled after its claim, as declared",
      (leaseLine ?? "").includes(`"kind": "lease-declared", "itemId": "F6", "seat": "seat-l", "declared": {"mem": "12G", "cpu": "8", "wall": "20m", "tier": "B"}`), leaseLine);
    run("release", "F6");

    // handover
    check("handover refuses a seat that does not own the row", run("handover", "F0", "--to", "seat-b", "--by", "seat-x").out.includes("owned by seat-a"));
    check("handover refuses a row that is not in_flight", run("handover", "F5", "--to", "seat-b", "--by", "seat-a").out.includes("handover only in_flight"));
    const handed = run("handover", "F0", "--to", "seat-b", "--by", "seat-a");
    const got = run("get", "F0").out;
    check("handover moves the owner, keeps in_flight, and writes manifest.py's handover note",
      handed.code === 0 && got.includes("[in_flight]") && got.includes("claim  : seat-b") && /CLAIM: owner=seat-b at=\S+Z handover-from=seat-a/.test(got), got);
    check("handover journals the handing seat", records().at(-1)?.verb === "handover" && records().at(-1)?.seat === "seat-a");

    // verdict
    check("an accepted verdict may not carry --gap", run("verdict", "F5", "--outcome", "accepted", "--gap", "x").out.includes("must not carry --gap"));
    check("a redo verdict needs its named gap", run("verdict", "F5", "--outcome", "redo").out.includes("requires --gap"));
    check("a note may not author the verdict sigil", run("note", "F5", "VERDICT: outcome=accepted").out.includes("verdict sigil"));
    const redo = run("verdict", "F0", "--outcome", "redo", "--gap", "the named gap");
    check("a redo verdict writes its note and pokes the owner", redo.code === 0 && run("get", "F0").out.includes("VERDICT: outcome=redo gap=the named gap") && redo.out.includes("to='seat-b'"), redo.out);
    check("the verdict record is typed, in manifest.py's key order",
      (rawLines().at(-1) ?? "").endsWith(`"kind": "orchestrator_verdict", "itemId": "F0", "seat": "unknown", "outcome": "redo", "gapNamed": "the named gap", "contradictionFound": false, "contradictionLocus": null, "envFailure": false}`), rawLines().at(-1));
    check("ONE-REVIEW-PER-ROW: a second redo is refused", run("verdict", "F0", "--outcome", "redo", "--gap", "again").out.includes("already has a redo"));
    check("redo then accepted is allowed", run("verdict", "F0", "--outcome", "accepted", "--contradiction", "report vs journal").code === 0);
    check("nothing follows a terminal verdict", run("verdict", "F0", "--outcome", "blocked").out.includes("terminal verdict"));

    // next --claim
    run("note", "F1", "RETIRED: folded into F5");
    // A campaign queue naming F6 (todo) before F0 (in_flight): init writes no [campaign] table.
    const { appendFileSync } = await import("node:fs");
    appendFileSync(ledger, '\n[campaign]\nnext = ["F6", "F0"]\n');
    const refused = run("next", "--claim", "seat-c");
    check("a ledger-wide refusal (a raw edit breaking provenance) aborts the pull loudly, not as a row reason",
      refused.code === 1 && refused.out.includes("provenance") && !refused.out.includes("queue empty"), refused.out);
    run("reattest");
    check("the fixture's campaign queue parses", campaignNext(readFileSync(ledger, "utf8"), ledger).join(",") === "F6,F0" && run("validate").code === 0);
    const pulled = run("next", "--claim", "seat-c");
    check("next --claim takes the queued todo row first, claims it and prints its packet",
      pulled.code === 0 && pulled.out.includes("F6 claimed by seat-c") && run("get", "F6").out.includes("[in_flight]") && pulled.out.includes("F6"), pulled.out);
    const second = run("next-packet", "--session", "seat-d");
    check("then the first eligible todo row in ledger order, past every ineligible one (next-packet spelling)",
      second.code === 0 && second.out.includes("F5 claimed by seat-d"), second.out);
    const empty = run("next", "--claim", "seat-e");
    check("nothing eligible exits 1 with one reason per candidate",
      empty.code === 1 && ["F1: retired", "F2: item verify starts with TBD", "F3: fence overlaps with in_flight F0", "F4: destructive item with un-enumerated fence"].every((r) => empty.out.includes(r)), empty.out);

    // read-only instruments
    const kpi = run("gym-kpi").out;
    check("gym-kpi reports over the journal", /verdict-coverage: +0\/\d+ closed items/.test(kpi) && /lineage-completeness: +overall 1\/\d+ claims/.test(kpi), kpi);
    const ev = run("events", "--after-seq", "1", "--verbs", "handover");
    check("events prints the matching journal lines and exits 0", ev.code === 0 && ev.out.trim().split("\n").length === 1 && ev.out.includes('"verb": "handover"'), ev.out);
    check("events with no match exits 1", run("events", "--after-seq", "999999").code === 1);

    // fence instruments. The probe literal is assembled here so this file, copied into the layout,
    // does not name it.
    const probe = ["FENCE", "PROBE", "7731"].join("_");
    mkdirSync(join(root, "src", "fc"), { recursive: true });
    mkdirSync(join(root, "src", "fu"), { recursive: true });
    await Bun.write(join(root, "src", "fc", "inside.ts"), `// ${probe}\n`);
    await Bun.write(join(root, "src", "fc", "sibling.ts"), `// ${probe}\n`);
    await Bun.write(join(root, "src", "outside.ts"), `// ${probe}\n`);
    await Bun.write(join(root, "src", "fu", "keep.ts"), "// tracked\n");
    add("FC1", "fence-check probe", "src/fc/inside.ts");
    add("FC2", "a bare directory fence", "src/fc/");
    add("FU1", "fence-uncommitted probe", "src/fu/**,.dev/campaigns/fleet-selftest.toml");
    const narrow = run("fence-check", "FC1", probe);
    check("fence-check names every file carrying the literal outside the fence, a sibling of a fenced file included, exit 1",
      narrow.code === 1 && narrow.out.includes(`FAIL: fence-check FC1 '${probe}' — 2 of 3 matching file(s) NOT covered by the fence:\n  - src/fc/sibling.ts\n  - src/outside.ts`), narrow.out);
    check("fence-check: an entry ending in '/' covers the files beneath it",
      run("fence-check", "FC2", probe).out.includes("1 of 3 matching file(s) NOT covered") && !run("fence-check", "FC2", probe).out.includes("src/fc/inside.ts"));
    check("fence-check of a literal nothing carries passes and says to double-check it",
      run("fence-check", "FC1", ["NOTHING", "CARRIES", "7732"].join("_")).out.includes("0 matches in the tree (zero-match"));
    const bare = run("scan-bare-fences").out;
    check("scan-bare-fences lists a bare directory entry in manifest.py's form, and not a glob",
      bare.includes("  FC2: 'src/fc/'") && !bare.includes("src/fu/**") && bare.includes("1 item(s), 1 bare-directory"), bare);
    sh("git", "add", "-A");
    sh("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "fixture lands");
    check("fence-uncommitted is clean once the row's files are committed", run("fence-uncommitted", "FU1").out.trim() === '{"dirty": [], "warned": [], "available": true}');
    await Bun.write(join(root, "src", "fu", "new.ts"), "// never committed\n");
    run("note", "FU1", "the ledger is now dirty too");
    const fu = run("fence-uncommitted", "FU1");
    check("fence-uncommitted: a new file inside the fence is dirty (exit 1), the ledger itself only warned",
      fu.code === 1 && fu.out.trim() === '{"dirty": ["src/fu/new.ts"], "warned": [".dev/campaigns/fleet-selftest.toml"], "available": true}', fu.out);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
  console.log(`${checks - failures}/${checks} checks passed`);
  return failures === 0 ? 0 : 1;
}

export const FLEET_USAGE = [
  "fleet (splice, ported from manifest.py)",
  "  next --claim <seat>               pull: claim the first eligible todo row and print its packet",
  "                                    (next-packet --session <seat> is the same verb)",
  "  handover <ID> --to <seat> --by <owner-seat>   a live owner hands its claim over",
  "  verdict <ID> --outcome accepted|redo|blocked [--gap T] [--contradiction L] [--env-failure]",
  "                                    the typed review verdict: a ledger note plus its journal record",
  "  gym-kpi                           the campaign KPI over the fleet journal (read-only)",
  "  events [--after-seq N] [--exclude-seat S] [--verbs v1,v2]   journal query; exit 0 = matches",
  "  fence-check <ID> <pattern>        every tree file naming <pattern> literally is inside <ID>'s fence; exit 1 if not",
  "  scan-bare-fences                  inventory of bare-directory fence entries (non-blocking)",
  "  fence-uncommitted <ID>            uncommitted paths inside <ID>'s fence, as JSON; exit 1 if any",
  "  release-stale --dry-run [--minutes N]   stale-claims: the claims release-stale would release",
].join("\n");

/** The verbs this file owns, or null to let the vendored CLI answer. */
export async function runFleetVerb(ledgerPath: string, command: string, rest: readonly string[]): Promise<number | null> {
  switch (command) {
    case "next":
      if (!rest.includes("--claim")) return null;
      return await nextClaim(ledgerPath, identity(flagValue(rest, "claim"), "--claim"));
    case "next-packet":
      return await nextClaim(ledgerPath, identity(flagValue(rest, "session"), "--session"));
    case "handover":
      return await handover(ledgerPath, rest);
    case "verdict":
      return await verdict(ledgerPath, rest);
    case "gym-kpi":
      return gymKpi(ledgerPath);
    case "events":
      return events(rest);
    case "fence-check":
      if (rest.length !== 2) throw new LedgerError("fence-check requires <ID> <pattern>");
      return fenceCheck(ledgerPath, rest[0]!, rest[1]!);
    case "scan-bare-fences":
      return scanBareFences(ledgerPath);
    case "fence-uncommitted":
      if (rest.length !== 1) throw new LedgerError("fence-uncommitted requires <ID>");
      return fenceUncommitted(ledgerPath, rest[0]!);
    case "claim": {
      const lease = flagValue(rest, "lease"); // refused BEFORE the claim writes, as manifest.py does
      if (lease !== null) parseLease(lease);
      return null;
    }
    case "note": {
      // Only `verdict` may author the verdict sigil: a note that opens with it would lock the row's
      // one review without a verdict ever being recorded (manifest.py:3813).
      let body = rest[1] ?? "";
      for (let i = 0; i < 3 && /^\[\d{4}-\d{2}-\d{2}\] /.test(body); i += 1) body = body.slice(13);
      if (VERDICT_RE.test(body.trim())) {
        throw new LedgerError(`a note may not open with the verdict sigil (${JSON.stringify(body.trim().slice(0, 60))}) — only \`verdict\` writes that line. Reword the note, or record the verdict with \`verdict <ID> --outcome ...\`.`);
      }
      return null;
    }
    default:
      return null;
  }
}
