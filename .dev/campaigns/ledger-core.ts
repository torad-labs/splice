/**
 * LEDGER CORE — parsing, locking, and line-surgical writes.
 *
 * This is a Bun/TypeScript port of the DOCTRINE behind torad-fleet's .dev/campaigns/manifest.py
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
 * each other, so while both CLIs can run, a manifest.py seat and a bun seat would each believe they
 * held the ledger. So this also takes a real flock(2) on the ledger file, through bun:ffi —
 * proved both ways in two processes (bun holds -> manifest.py blocked; manifest.py holds -> bun
 * blocked).
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

async function acquireLock(ledgerPath: string, timeoutMs = LOCK_TIMEOUT_MS): Promise<HeldLock> {
  const lockPath = await acquireSidecar(ledgerPath, timeoutMs);
  // A ledger not yet on disk (init) has nothing for manifest.py to contend: that CLI refuses a
  // missing path before it ever locks.
  if (!coexistsWithPython(ledgerPath) || !existsSync(ledgerPath)) return { lockPath, fd: null };
  let fd: number | null = null;
  try {
    fd = openSync(ledgerPath, "r+");
    const deadline = Date.now() + timeoutMs;
    while (flockSymbols().flock(fd, LOCK_EX | LOCK_NB) !== 0) {
      if (Date.now() > deadline) {
        throw new LedgerError(
          `could not flock ${ledgerPath} after ${timeoutMs}ms — a manifest.py seat is writing`,
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

async function acquireSidecar(ledgerPath: string, timeoutMs = LOCK_TIMEOUT_MS): Promise<string> {
  const lockPath = `${ledgerPath}.lock`;
  const deadline = Date.now() + timeoutMs;

  for (;;) {
    let handle: number;
    try {
      handle = openSync(lockPath, "wx");
    } catch (error) {
      // Only an existing lock is a peer's. Anything else (the ledger's directory missing, a read-only
      // tree) has no lock file to age, so treating it as a stale one spun here forever at full CPU.
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") {
        throw new LedgerError(`cannot create the lock ${lockPath}: ${(error as Error).message}`);
      }
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
          `could not lock ${ledgerPath} after ${timeoutMs}ms — another seat is writing`,
        );
      }
      await Bun.sleep(LOCK_RETRY_MS);
      continue;
    }
    try {
      writeSync(handle, `${process.pid}\n${new Date().toISOString()}\n`);
    } catch (error) {
      releaseLock({ lockPath, fd: null }); // never leave a lock behind: it wedges every seat for STALE_LOCK_MS
      throw new LedgerError(`cannot write the lock ${lockPath}: ${(error as Error).message}`);
    } finally {
      closeSync(handle);
    }
    return lockPath;
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

/**
 * NO PROVENANCE PROOF (operator, 2026-09-26: "eliminate this hash ceremony from the ledger"). The
 * `.cli-sha256` sidecar bound each ledger to the bytes this CLI last wrote, so every write became a
 * PAIR a seat had to lock, stage and commit together, and a pair that drifted wedged every mutation
 * until the orchestrator ran `reattest`. Git already records every write: the CLI commits its own
 * (commitWrites), and the raw-edit guard (08_manifest_single_channel) keeps hands off the file.
 */
export async function readLines(ledgerPath: string): Promise<string[]> {
  const lockPath = await acquireLock(ledgerPath);
  try {
    return (await Bun.file(ledgerPath).text()).split("\n");
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

/** Every ledger this process wrote, committed once by the entry point (commitWrites). */
const writtenLedgers = new Set<string>();

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
      writtenLedgers.add(resolve(ledgerPath));
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
 * waiting on the old one then holds a lock nothing else contends — measured: manifest.py holding,
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

const COMMIT_RETRY_MS = 200;
/** How long a held index lock is waited out before the write is deferred. The ledger lock is held
 *  only for each attempt, never across the wait, so peers' writes go on meanwhile. */
const COMMIT_TIMEOUT_MS = 5_000;

/**
 * THE CLI COMMITS ITS OWN WRITES (operator, 2026-09-26: "I hate how this ledger has so many steps").
 * A seat wrapped every write in a seat lock on the ledger and one on its proof, `git add` of the
 * pair, a commit, and two lock releases whose `rmdir "$L/$T"` trips Claude Code's dangerous-removal
 * check, which no permission rule and no bypass mode can pre-approve. The entry point calls this
 * once, after the command, so a write is ONE command and nobody locks, stages or commits a ledger:
 *   - each ledger this process wrote and git tracks is committed BY PATH (`git commit -- <ledger>`),
 *     so a peer's staged files never ride along (the shared-index law);
 *   - under the ledger's own write lock, so the commit carries exactly the bytes on disk and a peer's
 *     write lands in the next commit instead of racing this one;
 *   - a ledger git does not track (a scratch copy, a fresh `init`, a fixture) is left alone, said out
 *     loud when it sits beside this CLI, and one with nothing left to commit (a peer's commit already
 *     carried the write) is skipped;
 *   - a commit that cannot land (the index lock or the HEAD ref held past the timeout, a merge in
 *     progress, a detached HEAD, the ledger lock not free in time) is reported and deferred, never
 *     retried by re-running the verb,
 *     which would write twice: the next write commits the whole file. So this never throws: the
 *     write already landed, and an exit 1 after it would invite exactly that re-run.
 */
export async function commitWrites(argv: readonly string[], lockTimeoutMs = LOCK_TIMEOUT_MS): Promise<void> {
  const subject = commitSubject(argv);
  for (const ledgerPath of writtenLedgers) {
    const git = (...args: string[]) =>
      Bun.spawnSync(["git", "-C", dirname(ledgerPath), ...args], { stdout: "pipe", stderr: "pipe" });
    const tracked = git("ls-files", "--error-unmatch", "--", ledgerPath);
    if (tracked.exitCode !== 0) {
      // A scratch copy (the gate's /tmp ledgers, the selftests' fixtures) is untracked by design. A
      // campaign ledger beside this CLI that git does not track, or cannot read, is a write nobody
      // will ever commit, so it is said out loud.
      if (resolve(dirname(ledgerPath)) === import.meta.dir) {
        const why = tracked.exitCode === 1
          ? "git does not track it: git add it once and the next write commits it"
          : `git cannot read it: ${tracked.stderr.toString().trim().split("\n")[0]}`;
        console.error(`WARNING: ${ledgerPath} is written but not committed (${why})`);
      }
      continue;
    }
    const deferred = await commitOne(ledgerPath, subject, git, lockTimeoutMs);
    if (deferred !== null) {
      console.error(`WARNING: ${ledgerPath} is written but not committed (${deferred}); the next ledger write commits it`);
    }
  }
  writtenLedgers.clear();
}

/** One ledger's commit, or why it is deferred. Each attempt takes the ledger lock, checks there is
 *  still something to commit, tries once and releases; a held index lock is waited out OUTSIDE the
 *  ledger lock, so a peer's write never queues behind this commit's retries. */
async function commitOne(
  ledgerPath: string,
  subject: string,
  git: (...args: string[]) => { exitCode: number | null; stderr: Buffer },
  lockTimeoutMs: number,
): Promise<string | null> {
  const deadline = Date.now() + COMMIT_TIMEOUT_MS;
  for (;;) {
    let held: HeldLock;
    try {
      held = await acquireLock(ledgerPath, lockTimeoutMs);
    } catch (error) {
      return error instanceof Error ? error.message : String(error);
    }
    let why: string;
    try {
      if (git("diff", "--quiet", "HEAD", "--", ledgerPath).exitCode === 0) return null;
      // git commits on a detached HEAD without a word, into a commit no branch holds, and a later
      // checkout drops the write from the ledger: left uncommitted, it at least stays in the tree.
      if (git("symbolic-ref", "-q", "HEAD").exitCode !== 0) return "HEAD is detached, so the commit would land on no branch";
      const commit = git("commit", "--quiet", "-m", subject, "--", ledgerPath);
      if (commit.exitCode === 0) return null;
      why = commit.stderr.toString().trim();
    } finally {
      releaseLock(held);
    }
    // A peer's commit holds the index lock, or moves HEAD between this commit's read of it and its
    // update ("cannot lock ref 'HEAD'"); both pass in moments, so both are retried.
    if (!/index\.lock|cannot lock ref/.test(why) || Date.now() > deadline) return why.split("\n")[0] ?? why;
    await Bun.sleep(COMMIT_RETRY_MS);
  }
}

/** `chore(ledger): <verb> <target>`, from the CLI's own argv (`<ledger> <verb> [args]`): the row a
 *  verb names, `--id` for `add`, or a text verb's first words. */
function commitSubject(argv: readonly string[]): string {
  const [, verb = "write", ...rest] = argv;
  const idAt = rest.indexOf("--id");
  const target = idAt >= 0 ? rest[idAt + 1] : rest.find((arg) => !arg.startsWith("--"));
  return `chore(ledger): ${verb}${target === undefined ? "" : ` ${target}`}`.replace(/\s+/g, " ").trim().slice(0, 100);
}

/** Escape a value for a TOML basic string. */
export function toml(value: string): string {
  return `"${value.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}
