/**
 * LEDGER CORE — parsing, locking, and line-surgical writes.
 *
 * This is a Bun/TypeScript port of the DOCTRINE behind torad-fleet's dev/campaigns/manifest.py
 * (7,530 lines), not of its code. The fleet CLI grew a large fleet-specific surface — seats,
 * dispatch, journals, cost, signatures — that this repository has no use for yet. What was ported is
 * the part that is load-bearing everywhere (concepts #945, #948, #957):
 *
 *   1. THE LEDGER IS THE MEMORY. Contexts die, sessions compact, agents get killed. An item must
 *      be resumable from the ledger alone, so decisions and resume pointers live in the item text
 *      and in dated `#` notes beside it.
 *
 *   2. WRITES ARE LINE-SURGICAL, NEVER A SERIALIZER ROUND-TRIP. Parse-then-restringify is the
 *      obvious implementation and it is catastrophic here: every TOML serializer discards
 *      comments, and the comments ARE the memory. So the parser is used to FIND things and to
 *      VALIDATE the result; the file is edited as lines.
 *
 *   3. EVERY WRITE RE-PARSES AND ROLLS BACK ON FAILURE. Ledger integrity is structural, not a
 *      matter of being careful.
 *
 *   4. WRITES TAKE AN EXCLUSIVE LOCK. Concurrent seats otherwise collide constantly on
 *      "file modified since read"; atomicity kills the entire class.
 *
 *   5. `get` COSTS ~15 LINES, NOT A WHOLE-FILE READ. Token cost is the orchestrator's scarcest
 *      resource, and keyed retrieval is why the ledger can grow without becoming unaffordable.
 */

import { dlopen, FFIType } from "bun:ffi";
import {
  closeSync,
  existsSync,
  fsyncSync,
  ftruncateSync,
  openSync,
  renameSync,
  statSync,
  unlinkSync,
  writeSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";

export type ItemStatus = "todo" | "in_flight" | "blocked" | "done" | "verified";

export const ITEM_STATUSES: readonly ItemStatus[] = [
  "todo",
  "in_flight",
  "blocked",
  "done",
  "verified",
];

export function isItemStatus(candidate: string): candidate is ItemStatus {
  return (ITEM_STATUSES as readonly string[]).includes(candidate);
}

export type Item = {
  readonly id: string;
  readonly phase: string;
  readonly title: string;
  readonly files: readonly string[];
  readonly status: ItemStatus;
  readonly verify: string;
  readonly claimedBy?: string;
  readonly claimedAt?: string;
};

/** A located item: the parsed values plus the exact line span it occupies in the file. */
export type ItemBlock = {
  readonly item: Item;
  /** Index of the `[[items]]` line. */
  readonly start: number;
  /** Index one past the block's last line. */
  readonly end: number;
};

export class LedgerError extends Error {}

// ── locking ───────────────────────────────────────────────────────────────────────────────────

/**
 * O_EXCL lockfile. `openSync(path, "wx")` fails if the path exists, and that check-and-create is
 * atomic on every local filesystem — which is the whole requirement. A lock older than
 * STALE_LOCK_MS is assumed to belong to a killed process and is broken; agents die mid-write
 * often enough that a lock with no expiry would eventually wedge the ledger permanently.
 */
const STALE_LOCK_MS = 30_000;
const LOCK_RETRY_MS = 50;
const LOCK_TIMEOUT_MS = 10_000;

/**
 * VENDORING DELTA 1 (splice V4-143, 2026-09-18): COEXISTENCE WITH manifest.py. That CLI takes
 * `fcntl.flock` on the LEDGER FILE itself; the O_EXCL sidecar below and an flock DO NOT exclude
 * each other, so while both CLIs can run, a python seat and a bun seat would each believe they
 * held the ledger. So this also takes a real flock(2) on the ledger file, through bun:ffi —
 * proved both ways in two processes (bun holds -> python blocked; python holds -> bun blocked).
 * Active only for a ledger beside manifest.py, so the delta expires when that CLI is deleted.
 */
const COEXISTING_PY = join(import.meta.dir, "manifest.py");

/** True for a ledger manifest.py can also be writing: one that sits beside this file while that
 *  CLI still exists. A ledger elsewhere (the selftest's temp ledgers) keeps canonical semantics. */
export function coexistsWithPython(ledgerPath: string): boolean {
  return existsSync(COEXISTING_PY) && resolve(dirname(ledgerPath)) === import.meta.dir;
}

const LOCK_EX = 2;
const LOCK_NB = 4;
const LOCK_UN = 8;
let libc: { symbols: { flock: (fd: number, op: number) => number } } | null = null;

function flockSymbols(): { flock: (fd: number, op: number) => number } {
  libc ??= dlopen("libc.so.6", { flock: { args: [FFIType.i32, FFIType.i32], returns: FFIType.i32 } });
  return libc.symbols;
}

type HeldLock = { lockPath: string; fd: number | null };

async function acquireLock(ledgerPath: string): Promise<HeldLock> {
  const lockPath = await acquireSidecar(ledgerPath);
  // A ledger not yet on disk (init) has nothing for manifest.py to contend: that CLI refuses a
  // missing path before it ever locks.
  if (!coexistsWithPython(ledgerPath) || !existsSync(ledgerPath)) return { lockPath, fd: null };
  let fd: number | null = null;
  try {
    fd = openSync(ledgerPath, "r+");
    const deadline = Date.now() + LOCK_TIMEOUT_MS;
    while (flockSymbols().flock(fd, LOCK_EX | LOCK_NB) !== 0) {
      if (Date.now() > deadline) {
        throw new LedgerError(
          `could not flock ${ledgerPath} after ${LOCK_TIMEOUT_MS}ms — a manifest.py seat is writing`,
        );
      }
      await Bun.sleep(LOCK_RETRY_MS);
    }
    return { lockPath, fd };
  } catch (error) {
    // Never leave the sidecar behind: a leaked sidecar wedges every seat for STALE_LOCK_MS.
    if (fd !== null) closeSync(fd);
    releaseLock({ lockPath, fd: null });
    throw error;
  }
}

async function acquireSidecar(ledgerPath: string): Promise<string> {
  const lockPath = `${ledgerPath}.lock`;
  const deadline = Date.now() + LOCK_TIMEOUT_MS;

  for (;;) {
    try {
      const handle = openSync(lockPath, "wx");
      closeSync(handle);
      await Bun.write(lockPath, `${process.pid}\n${new Date().toISOString()}\n`);
      return lockPath;
    } catch {
      const age = Date.now() - (statSync(lockPath, { throwIfNoEntry: false })?.mtimeMs ?? 0);
      if (age > STALE_LOCK_MS) {
        try {
          unlinkSync(lockPath);
        } catch {
          // Another process broke the same stale lock first; retry the acquire.
        }
        continue;
      }
      if (Date.now() > deadline) {
        throw new LedgerError(
          `could not lock ${ledgerPath} after ${LOCK_TIMEOUT_MS}ms — another seat is writing`,
        );
      }
      await Bun.sleep(LOCK_RETRY_MS);
    }
  }
}

function releaseLock(held: HeldLock): void {
  if (held.fd !== null) {
    flockSymbols().flock(held.fd, LOCK_UN);
    closeSync(held.fd);
  }
  try {
    unlinkSync(held.lockPath);
  } catch {
    // Already gone (stale-broken by a peer). Nothing to release.
  }
}

// ── reading ───────────────────────────────────────────────────────────────────────────────────

async function assertProvenance(ledgerPath: string, text: string, required: boolean): Promise<void> {
  const proof = Bun.file(`${ledgerPath}.cli-sha256`);
  if (!(await proof.exists())) {
    // VENDORING DELTA 3: a ledger manifest.py also writes cannot carry a proof (see mutate), so
    // its absence there is the expected state rather than a tamper signal.
    if (required && !coexistsWithPython(ledgerPath)) throw new LedgerError(`ledger provenance missing: ${ledgerPath}`);
    return; // Explicit CLI use can initialize a legacy ledger; lifecycle reads cannot.
  }
  if ((await proof.text()).trim() !== new Bun.CryptoHasher("sha256").update(text).digest("hex")) {
    throw new LedgerError(`ledger provenance mismatch: ${ledgerPath}; restore the attested bytes before a CLI mutation`);
  }
}

export async function readLines(ledgerPath: string, attested = false): Promise<string[]> {
  const lockPath = await acquireLock(ledgerPath);
  try {
    const text = await Bun.file(ledgerPath).text();
    await assertProvenance(ledgerPath, text, attested);
    return text.split("\n");
  } finally {
    releaseLock(lockPath);
  }
}

/**
 * A READ-ONLY view that survives a wedged proof. A provenance mismatch used to refuse even `get`
 * and `list`, so the one seat trying to diagnose the wedge was blind (Eli F1, 2026-09-17). Reads
 * warn loudly on stderr and continue; every mutation and every attested (hook) read still refuses.
 */
export async function readLinesLoose(ledgerPath: string): Promise<string[]> {
  const lockPath = await acquireLock(ledgerPath);
  try {
    const text = await Bun.file(ledgerPath).text();
    try {
      await assertProvenance(ledgerPath, text, false);
    } catch (error) {
      console.error(`WARNING: ${error instanceof Error ? error.message : String(error)}\n  read-only view; mutations are refused until the orchestrator runs \`reattest\` after reading \`git diff -- ${ledgerPath}\``);
    }
    return text.split("\n");
  } finally {
    releaseLock(lockPath);
  }
}

/**
 * THE ONE SANCTIONED REPAIR. Re-binds the proof to the ledger bytes as they are, under the lock,
 * and returns the hash. The caller (the `reattest` verb) is orchestrator-gated and prints what
 * it is blessing; a kill between the two renames in `mutate` is the honest way to get here.
 */
export async function reattestLedger(ledgerPath: string): Promise<string> {
  const lockPath = await acquireLock(ledgerPath);
  try {
    const text = await Bun.file(ledgerPath).text();
    parseOrThrow(text, ledgerPath);
    const hash = new Bun.CryptoHasher("sha256").update(text).digest("hex");
    const proofPath = `${ledgerPath}.cli-sha256`;
    await Bun.write(`${proofPath}.${process.pid}.tmp`, hash + "\n");
    renameSync(`${proofPath}.${process.pid}.tmp`, proofPath);
    return hash;
  } finally {
    releaseLock(lockPath);
  }
}

/** Validate by parsing. Throws LedgerError with the parser's complaint if the file is malformed. */
export function parseOrThrow(text: string, ledgerPath: string): unknown {
  try {
    return Bun.TOML.parse(text);
  } catch (error) {
    throw new LedgerError(
      `${ledgerPath} is not valid TOML: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}

const ITEM_HEADER = /^\[\[items\]\]\s*$/;
const TABLE_HEADER = /^\s*\[/;

function scalar(line: string, key: string): string | null {
  const match = new RegExp(`^\\s*${key}\\s*=\\s*"((?:[^"\\\\]|\\\\.)*)"\\s*$`).exec(line);
  return match?.[1] === undefined ? null : match[1].replace(/\\"/g, '"');
}

function stringArray(line: string, key: string): readonly string[] | null {
  const match = new RegExp(`^\\s*${key}\\s*=\\s*\\[(.*)\\]\\s*$`).exec(line);
  if (match?.[1] === undefined) return null;
  const inner = match[1].trim();
  if (inner === "") return [];
  return inner
    .split(",")
    .map((piece) => piece.trim().replace(/^"|"$/g, ""))
    .filter((piece) => piece !== "");
}

/**
 * Locate every item block by scanning lines. Deliberately independent of the TOML parser: the
 * parser gives values but not line spans, and the line spans are what every write needs.
 */
export function locateItems(lines: readonly string[]): ItemBlock[] {
  const blocks: ItemBlock[] = [];

  for (let index = 0; index < lines.length; index += 1) {
    if (!ITEM_HEADER.test(lines[index] ?? "")) continue;

    let end = index + 1;
    while (end < lines.length && !TABLE_HEADER.test(lines[end] ?? "")) end += 1;

    // Trailing blank lines belong between blocks, not inside one — notes must append directly
    // beneath the item's last real line or they drift away from what they describe.
    let lastContent = end;
    while (lastContent > index + 1 && (lines[lastContent - 1] ?? "").trim() === "") {
      lastContent -= 1;
    }

    const body = lines.slice(index, lastContent);
    const rawStatus = body.map((line) => scalar(line, "status")).find((value) => value !== null);
    // `!== undefined` ONLY: the `find` predicate above is an inferred type predicate, so `null` is
    // already outside this union and the rule was right that the conjunct could never be true. The
    // value is our own scanner's, not a JSON read, so the declared type is the runtime here (MOD.38).
    const status = rawStatus !== undefined && isItemStatus(rawStatus) ? rawStatus : "todo";

    const claimedBy = body.map((line) => scalar(line, "claimed_by")).find((v) => v !== null);
    const claimedAt = body.map((line) => scalar(line, "claimed_at")).find((v) => v !== null);

    blocks.push({
      start: index,
      end: lastContent,
      item: {
        id: body.map((line) => scalar(line, "id")).find((v) => v !== null) ?? "",
        phase: body.map((line) => scalar(line, "phase")).find((v) => v !== null) ?? "",
        title: body.map((line) => scalar(line, "title")).find((v) => v !== null) ?? "",
        files: body.map((line) => stringArray(line, "files")).find((v) => v !== null) ?? [],
        status,
        verify: body.map((line) => scalar(line, "verify")).find((v) => v !== null) ?? "",
        ...(claimedBy != null ? { claimedBy } : {}),
        ...(claimedAt != null ? { claimedAt } : {}),
      },
    });

    index = end - 1;
  }

  return blocks;
}

export function findBlock(blocks: readonly ItemBlock[], id: string): ItemBlock {
  const found = blocks.find((block) => block.item.id === id);
  if (found === undefined) throw new LedgerError(`no item with id "${id}"`);
  return found;
}

/** The `#` comment lines inside an item block — its construction diary. */
export function notesOf(lines: readonly string[], block: ItemBlock): string[] {
  return lines
    .slice(block.start, block.end)
    .filter((line) => line.trimStart().startsWith("#"))
    .map((line) => line.trim());
}

/** The header comment block above the first table — where the laws live. */
export function headerLines(lines: readonly string[]): string[] {
  const header: string[] = [];
  for (const line of lines) {
    if (TABLE_HEADER.test(line)) break;
    header.push(line);
  }
  return header;
}

// ── writing ───────────────────────────────────────────────────────────────────────────────────

export function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * The only write path. Takes the lock, applies a pure line transform, validates the result by
 * re-parsing, and writes only if it parses — otherwise the original file is left untouched and
 * the caller gets the parser's complaint.
 *
 * The transform is pure and line-based on purpose. A callback that could reach for a TOML
 * serializer would strip every comment in the file, and the comments are the memory.
 */
export async function mutate(
  ledgerPath: string,
  transform: (lines: readonly string[]) => readonly string[],
): Promise<void> {
  const lockPath = await acquireLock(ledgerPath);
  try {
    const original = await Bun.file(ledgerPath).text();
    await assertProvenance(ledgerPath, original, false);
    const next = transform(original.split("\n")).join("\n");

    // Validate BEFORE writing: rollback is then simply "never wrote it".
    parseOrThrow(next, ledgerPath);

    /**
     * TEMP FILE + ATOMIC RENAME, not a direct write.
     *
     * `Bun.write` to the live path truncates first and then streams. A kill between those two —
     * a rate-limit death, an interrupt, an OOM — leaves a TRUNCATED ledger. And truncation here is
     * uniquely nasty: the regex scanner reads a half-file perfectly happily, reporting fewer items
     * rather than an error, so the campaign silently forgets work instead of failing loudly.
     * `validate` cross-checks the scanner against the TOML parser, but a clean cut at a block
     * boundary can satisfy both.
     *
     * `rename(2)` within a filesystem is atomic: a concurrent reader sees either the whole old file
     * or the whole new one, never a partial. The temp file sits in the same directory precisely so
     * the rename cannot cross a filesystem boundary and silently degrade to copy-then-delete.
     */
    const tempPath = `${ledgerPath}.${process.pid}.tmp`;
    try {
      if (lockPath.fd !== null) {
        writeInPlace(lockPath.fd, next);
      } else {
        await Bun.write(tempPath, next);
        renameSync(tempPath, ledgerPath);
      }
      // Publish proof before releasing the same lock used by attested readers. VENDORING DELTA 3
      // (splice V4-143): NOT on a ledger manifest.py also writes. That CLI never maintains a proof,
      // so one born here would be stale after its next write and every later mutation here would
      // refuse. Such a ledger stays proof-free until the cutover's `reattest` binds one.
      if (!coexistsWithPython(ledgerPath)) {
        const proofPath = `${ledgerPath}.cli-sha256`;
        await Bun.write(`${proofPath}.${process.pid}.tmp`, new Bun.CryptoHasher("sha256").update(next).digest("hex") + "\n");
        renameSync(`${proofPath}.${process.pid}.tmp`, proofPath);
      }
    } catch (error) {
      // A failed rename must not leave debris that a later glob mistakes for a ledger.
      try {
        unlinkSync(tempPath);
      } catch {
        // Never existed, or already gone. Either way there is nothing to clean up.
      }
      throw error;
    }
  } finally {
    releaseLock(lockPath);
  }
}

/**
 * VENDORING DELTA 2 (splice V4-143): write IN PLACE through the flocked descriptor, never rename.
 * An flock belongs to an INODE; a rename puts a new inode at the path, and a manifest.py seat
 * waiting on the old one then holds a lock nothing else contends — measured: python holding,
 * rename, and a bun probe on the path ACQUIRES. manifest.py writes in place for the same reason,
 * so while it exists this one must too. The whole buffer is written before the truncate, so the
 * file only ever shrinks at the very end. The rename path returns with the .py's deletion.
 */
function writeInPlace(fd: number, text: string): void {
  const bytes = Buffer.from(text, "utf8");
  let written = 0;
  while (written < bytes.length) written += writeSync(fd, bytes, written, bytes.length - written, written);
  ftruncateSync(fd, bytes.length);
  fsyncSync(fd);
}

/** Escape a value for a TOML basic string. */
export function toml(value: string): string {
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}
