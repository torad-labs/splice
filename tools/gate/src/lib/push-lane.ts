// The push lane (load cut 2 of the temple plan of 2026-10-19, approved by Marcos). Seats push one shared branch, so pushes queue
// behind the one being judged, and a later push holds the commits of the ones before it. Only the newest queued push of a branch is
// judged. An older one waits while a newer push that holds its commit is queued, judged or sent; once that push lands, the older
// commit is on the remote, so its push is told it was carried and sends nothing. When the newest fails, the next older one is
// judged. A push that waited while another landed without its commit is stale: git sends the old value it read when it connected,
// and the remote refuses an update from a value the branch no longer holds, so it ends before any judgement. A push also waits while
// git sends one that already passed, since that one may land first and make it stale.
//
// The lane is a folder under the git directory with one file per queued push. A push stands for its git process, which runs the
// hook and then sends: its file counts while that process runs, so a push that passed is waited for until git has sent it, and one
// whose git died counts no more. A send that runs past SEND_WAIT_MS is taken as hung and waited for no longer. What landed is
// read from the remote-tracking branch, which git moves when a push lands.
import { spawnSync } from "node:child_process";
import { mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { type PrePushTree, prePushHome } from "./prepush-tree.ts";

/** How long a push waits for the build tree once it is the one to judge: the tree's own lock wait. */
const TREE_WAIT_MS = 30 * 60 * 1000;
const POLL_MS = 2000;
/** How long a push that passed is waited for while git sends it. */
const SEND_WAIT_MS = 10 * 60 * 1000;
const ZERO_SHA = /^0+$/;

/** A process, told apart from a later one that reuses its pid by when it started. */
export interface LaneOwner {
  readonly pid: number;
  readonly start: string;
}

export interface LaneOptions {
  /** The process whose life is the push's: `git push`, which runs the hook and then sends. The hook's parent when absent. */
  readonly owner?: LaneOwner;
  /** Whether a queued push's process still runs. Read from /proc when absent. */
  readonly alive?: (owner: LaneOwner) => boolean;
  /** How often a waiting push looks at the lane again. */
  readonly pollMs?: number;
  /** How long a push that passed is waited for while its git sends it. A send takes seconds; one past this is taken as hung. */
  readonly sendWaitMs?: number;
}

/** One branch update, as git's stdin names it, and the remote it goes to. */
export interface LanePush {
  readonly remoteName: string;
  readonly remoteRef: string;
  readonly tip: string;
  /** The sha the remote held when git connected: the old value the remote checks the update against. */
  readonly remote: string;
}

export type LaneOutcome =
  | { readonly kind: "judge"; readonly tree: PrePushTree }
  /** The remote branch holds the tip already, inside [by]. */
  | { readonly kind: "carried"; readonly by: string }
  /** The remote branch moved to [now] past the push's old value without its tip, so the remote would refuse the push. */
  | { readonly kind: "stale"; readonly now: string };

export interface Lane {
  /** Waits for this push's turn: the tree to judge in, or the reason it has nothing to judge. */
  queue(): Promise<LaneOutcome>;
  /** The hook passed the push: git sends it now, and the lane holds it until git ends. */
  passed(): void;
  /** The push ends here: it sends nothing. */
  leave(): void;
}

/** One queued push, as its file in the lane holds it. */
interface Entry extends LanePush {
  readonly owner: LaneOwner;
  /** When it queued, in ms: of two pushes of one commit, the first is judged. */
  readonly at: number;
  readonly state: "queued" | "judging" | "passed";
  /** When it took that state, in ms. */
  readonly since: number;
}

/** The process [pid] as the lane tells it apart, or undefined when it does not run or cannot be read. */
export function ownerOf(pid: number): LaneOwner | undefined {
  try {
    const stat = readFileSync(`/proc/${pid}/stat`, "utf8");
    // The fields after the command name, which is in parentheses and may hold spaces: state is the first, start time the 20th.
    const start = stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19];
    return start === undefined ? undefined : { pid, start };
  } catch {
    return undefined;
  }
}

/** The git process that runs this hook (the shim execs it, so git is its parent), or undefined when the parent is not git. */
function gitParent(): LaneOwner | undefined {
  const owner = ownerOf(process.ppid);
  if (owner === undefined) return undefined;
  try {
    return readFileSync(`/proc/${owner.pid}/comm`, "utf8").trim() === "git" ? owner : undefined;
  } catch {
    return undefined;
  }
}

/** The lane for [push], or undefined when the push is not one update of a branch the remote has and this checkout tracks, or
 *  when its git process cannot be told: those keep today's path. [take] takes the build tree when it is free, at once. */
export function openLane(repoRoot: string, push: LanePush, take: (sha: string) => PrePushTree | undefined, options: LaneOptions = {}): Lane | undefined {
  if (!push.remoteRef.startsWith("refs/heads/") || ZERO_SHA.test(push.remote)) return undefined;
  const tracking = `refs/remotes/${push.remoteName}/${push.remoteRef.slice("refs/heads/".length)}`;
  const first = commitOf(repoRoot, tracking);
  if (first === undefined) return undefined;
  const owner = options.owner ?? gitParent();
  if (owner === undefined) return undefined;
  const alive = options.alive ?? ((other: LaneOwner) => ownerOf(other.pid)?.start === other.start);
  const pollMs = options.pollMs ?? POLL_MS;
  const sendWaitMs = options.sendWaitMs ?? SEND_WAIT_MS;
  const dir = laneDir(repoRoot);
  if (dir === undefined) return undefined;
  const name = `${owner.pid}-${owner.start}.json`;
  const file = join(dir, name);
  const at = Date.now();
  const ancestor = (older: string, newer: string): boolean =>
    spawnSync("git", ["merge-base", "--is-ancestor", older, newer], { cwd: repoRoot }).status === 0;

  /** Puts this push in the lane at [state]. One the lane cannot hold is waited for by no one, as before the lane. */
  const write = (state: Entry["state"]): void => {
    try {
      const entry: Entry = { ...push, owner, at, state, since: Date.now() };
      const temp = `${file}.${process.pid}.tmp`;
      writeFileSync(temp, JSON.stringify(entry));
      renameSync(temp, file);
    } catch {
      // The other pushes do not see this one, and judge as they did before the lane.
    }
  };
  const leave = (): void => rmSync(file, { force: true });

  /** The other pushes of this branch whose git still runs. A file whose git has ended is removed. */
  const queued = (): { readonly entry: Entry; readonly name: string }[] => {
    const found: { entry: Entry; name: string }[] = [];
    let names: string[];
    try {
      names = readdirSync(dir);
    } catch {
      return found;
    }
    for (const other of names) {
      if (!other.endsWith(".json") || other === name) continue;
      let entry: Entry;
      try {
        entry = JSON.parse(readFileSync(join(dir, other), "utf8")) as Entry;
      } catch {
        continue;
      }
      if (!alive(entry.owner)) rmSync(join(dir, other), { force: true });
      else if (entry.remoteName === push.remoteName && entry.remoteRef === push.remoteRef) found.push({ entry, name: other });
    }
    return found;
  };

  /** A newer push that holds this one's commit, or the first-queued push of the same commit. */
  const carries = (other: { readonly entry: Entry; readonly name: string }): boolean =>
    other.entry.tip === push.tip ? other.entry.at < at || (other.entry.at === at && other.name < name) : ancestor(push.tip, other.entry.tip);

  /** What reached the remote branch while this push queued: its commit, or a move past its old value without its commit. A
   *  remote-tracking branch that has not moved since says nothing: it can lag the remote, or lead it after a force push. */
  const landed = (): Exclude<LaneOutcome, { kind: "judge" }> | undefined => {
    const now = commitOf(repoRoot, tracking);
    if (now === undefined || now === first || now === push.remote) return undefined;
    if (ancestor(push.tip, now)) return { kind: "carried", by: now };
    if (ancestor(push.remote, now)) return { kind: "stale", now };
    return undefined;
  };

  return {
    async queue() {
      write("queued");
      let said = "";
      const say = (line: string): void => {
        if (line !== said) console.error((said = line));
      };
      let treeWaitMs = 0;
      const givenUp = new Set<string>();
      /** A push that passed and whose git has sent it for longer than a send takes: it is not waited for. */
      const hung = (other: { readonly entry: Entry; readonly name: string }): boolean => {
        if (other.entry.state !== "passed" || Date.now() - other.entry.since < sendWaitMs) return false;
        if (!givenUp.has(other.name)) {
          givenUp.add(other.name);
          console.error(`── push lane: ${short(push.tip)} stops waiting for ${short(other.entry.tip)}, which git has been sending for ${Math.round(sendWaitMs / 1000)} s ──`);
        }
        return true;
      };
      for (;;) {
        const end = landed();
        if (end !== undefined) {
          leave();
          return end;
        }
        const others = queued().filter((other) => !hung(other));
        const carriers = others.filter(carries).map((other) => other.entry);
        const sending = others.map((other) => other.entry).filter((entry) => entry.state === "passed" && entry.tip !== push.tip);
        let waitingForTree = false;
        if (carriers.length > 0) {
          const newest = carriers.find((entry) => !carriers.some((other) => other.tip !== entry.tip && ancestor(entry.tip, other.tip))) ?? carriers[0];
          say(`── push lane: ${short(push.tip)} waits; ${short(newest?.tip ?? "")} is queued after it and holds its commit, so that push is judged instead ──`);
        } else if (sending.length > 0) {
          say(`── push lane: ${short(push.tip)} waits while git sends ${short(sending[0]?.tip ?? "")}, which passed and may land first ──`);
        } else {
          const tree = take(push.tip);
          if (tree !== undefined) {
            const after = landed();
            if (after !== undefined) {
              tree.release();
              leave();
              return after;
            }
            write("judging");
            return { kind: "judge", tree };
          }
          say(`── push lane: ${short(push.tip)} is the newest queued push; it waits for the pre-push build tree ──`);
          waitingForTree = true;
          if (treeWaitMs >= TREE_WAIT_MS) throw new Error(`the pre-push build tree stayed busy for ${Math.round(TREE_WAIT_MS / 1000)} s`);
        }
        const slept = Date.now();
        await Bun.sleep(pollMs);
        if (waitingForTree) treeWaitMs += Date.now() - slept;
      }
    },
    passed() {
      write("passed");
    },
    leave,
  };
}

const short = (sha: string): string => sha.slice(0, 9);

/** The lane's folder, made when missing, or undefined when it cannot be kept: a push then takes today's path rather than fail. */
function laneDir(repoRoot: string): string | undefined {
  try {
    const dir = join(prePushHome(repoRoot), "lane");
    mkdirSync(dir, { recursive: true });
    return dir;
  } catch {
    return undefined;
  }
}

/** The commit [ref] points at, or undefined when it does not resolve. */
function commitOf(root: string, ref: string): string | undefined {
  const read = spawnSync("git", ["rev-parse", "--verify", "--quiet", `${ref}^{commit}`], { cwd: root, encoding: "utf8" });
  const sha = read.stdout?.trim() ?? "";
  return read.status === 0 && sha !== "" ? sha : undefined;
}
