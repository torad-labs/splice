// `release train <verb>` — splice's release train (the global laws' "When work reaches main" and the release-train
// skill), prepared on Oct 13 for the switch Marcos ruled comes after 0.4.0 merges to main. It is INERT until then:
// every verb but `route` refuses unless origin has the dev branch and this checkout's `git config splice.train` is
// "on", the same switch that turns pre-push's judgement off (tools/gate hook.ts). The driver is splice-platform.
//
//   roll                    the rolling check: the skill's train.sh follows dev in its kept tree under /tmp/train and runs
//                           the gate of record on each new head, scoped by --since to the last green commit
//   cut                     every 15 minutes: the newest green commit on dev, pushed as train/<time> with its PR to main
//   pick <sha> <train/...>  a blocker's fix, alone, onto a held cut: checked there, then pushed
//   route <from> <to>       each commit in from..to under the seat its message ends with, so a break goes to its seat
//   beta                    after a cut merges: the beta main names, published by `release beta` unless it already is
//
// The moves themselves are train.sh's, which every project shares; this file holds only splice's settings and the two
// steps train.sh has no notion of, routing by seat and the beta from main.
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { beta } from "./beta.ts";

export const usage =
  "train <roll|cut|pick <sha> <branch>|route <from> <to>|beta>   splice's release train, inert until dev exists and splice.train is on";

const DEV = "dev";
const MAIN = "main";
const TRAIN_SH = join(homedir(), ".claude", "skills", "release-train", "train.sh");
/** The gate of record over the train's tree: everything until a first green, then only what changed since it. */
const CHECK = 'bun tools/gate run ${TRAIN_BASE:+--since "$TRAIN_BASE"}';
/** A splice commit ends with the seat that made it, after any seat it was made for: "(splice-lead) (splice-platform)",
 *  or "(splice-lead, on splice-config's reading)". A message naming none is routed to splice-lead. */
const SEAT = /\((splice-[a-z0-9-]+)[^()]*\)\s*$/;
const BETA = /^\d+\.\d+\.\d+-beta\.\d+$/;

function git(root: string, args: readonly string[]): { ok: boolean; out: string } {
  const child = Bun.spawnSync(["git", ...args], { cwd: root, stdin: "ignore", stdout: "pipe", stderr: "pipe" });
  return { ok: child.exitCode === 0, out: child.stdout.toString().trim() };
}

/** Why the train may not run yet, or null once the switch is made. */
function notYet(root: string): string | null {
  if (git(root, ["config", "--get", "splice.train"]).out !== "on") {
    return "release train: off. It switches on after 0.4.0 merges to main: `git config splice.train on` (docs in this file's header)";
  }
  if (git(root, ["ls-remote", "--heads", "origin", DEV]).out === "") return `release train: origin has no ${DEV} branch yet`;
  if (!existsSync(TRAIN_SH)) return `release train: ${TRAIN_SH} is missing; the release-train skill provides it`;
  return null;
}

function trainSh(root: string, args: readonly string[]): number {
  const env = { ...Bun.env, TRAIN_REPO: root, TRAIN_DEV: DEV, TRAIN_MAIN: MAIN, TRAIN_CHECK: CHECK };
  return Bun.spawnSync([TRAIN_SH, ...args], { cwd: root, env, stdin: "ignore", stdout: "inherit", stderr: "inherit" }).exitCode ?? 1;
}

/** Each commit in from..to under its seat, one line per commit, so the driver sends each seat only its own. */
function route(root: string, from: string, to: string): number {
  const log = git(root, ["log", "--format=%h%x1f%s%x1f%B%x1e", `${from}..${to}`]);
  if (!log.ok) {
    console.error(`release train: cannot read ${from}..${to}`);
    return 1;
  }
  const bySeat = new Map<string, string[]>();
  for (const record of log.out.split("\x1e").map((r) => r.trim()).filter(Boolean)) {
    const [sha = "", subject = "", body = ""] = record.split("\x1f");
    const seat = body.trim().match(SEAT)?.[1] ?? subject.match(SEAT)?.[1] ?? "unnamed (ask splice-lead)";
    bySeat.set(seat, [...(bySeat.get(seat) ?? []), `${sha} ${subject.slice(0, 100)}`]);
  }
  for (const [seat, commits] of bySeat) {
    console.log(seat);
    for (const commit of commits) console.log(`  ${commit}`);
  }
  return 0;
}

/** The beta main names, cut once: a version already published waits for the next bump to reach main through dev. */
async function betaFromMain(root: string): Promise<number> {
  if (!git(root, ["fetch", "-q", "origin", MAIN]).ok) {
    console.error("release train: cannot fetch main");
    return 1;
  }
  const named = (JSON.parse(git(root, ["show", `origin/${MAIN}:package.json`]).out) as { version?: string }).version ?? "";
  if (!BETA.test(named)) {
    console.log(`release train: wait main names ${named || "no version"}, not a beta`);
    return 0;
  }
  if (Bun.spawnSync(["gh", "release", "view", `v${named}`], { cwd: root, stdout: "ignore", stderr: "ignore" }).exitCode === 0) {
    console.log(`release train: wait v${named} is published; the next beta's bump reaches main through ${DEV}`);
    return 0;
  }
  return beta([named], root);
}

export async function train(argv: readonly string[], root: string): Promise<number> {
  const [verb, ...rest] = argv;
  if (verb === "route") {
    if (rest.length !== 2) return usageError();
    return route(root, rest[0] ?? "", rest[1] ?? "");
  }
  const held = notYet(root);
  if (held !== null) {
    console.error(held);
    return 1;
  }
  switch (verb) {
    case "roll":
    case "cut":
      return rest.length === 0 ? trainSh(root, [verb]) : usageError();
    case "pick":
      return rest.length === 2 ? trainSh(root, ["pick", ...rest]) : usageError();
    case "beta":
      return rest.length === 0 ? betaFromMain(root) : usageError();
    default:
      return usageError();
  }
}

function usageError(): number {
  console.error(`usage: bun tools/release ${usage}`);
  return 2;
}
