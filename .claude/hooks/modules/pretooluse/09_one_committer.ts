/** §one-committer — git add/commit/push is the orchestrator's word, enforced at the Bash seam.
 *
 *  Seat law: one-committer. Builders never `git add`, `git commit`, or `git push`; the
 *  orchestrator stages the receipt's fence and commits locally, then pushes once per milestone.
 *  This module blocks a Bash command that opens with such a git write unless ONE of two grants
 *  holds, both read from the command AS WRITTEN (a variable exported by an earlier Bash call is
 *  invisible to this guard — each tool call is one event with no shell state):
 *
 *    1. `LEDGER_ORCHESTRATOR=1` as an inline env prefix on the git command, or
 *    2. the console-handover carve-out: every affected path lies inside splice-design's own fence
 *       (`webui/`, `.dev/web-console/`, `.dev/campaigns/web-console.toml`, root `package-lock.json`).
 *
 *  Rule 2 is a PATH rule, not an identity rule: the hook cannot see a reliable seat, so it admits
 *  splice-design's WORK by the fence that only splice-design holds. One affected path outside the
 *  set refuses the whole write BY NAME. Fail-closed: an empty or unreadable affected-path set is
 *  refused — a bare `git add`, a `git commit` with nothing staged, and any `git push` (no local
 *  path set) are all refused unless the orchestrator grant is present.
 *
 *  The git-detection regex is the upstream patch's own (grailseeker-scout 0b9a66d, vendored
 *  2026-09-18): `git` at the start of a command or after a shell separator (`; & | ( then do`),
 *  with an optional `-C <repo>`. Run on the unquoted skeleton (tool_input.D16) so prose inside a
 *  `-m "..."` or a script literal never trips it.
 */
import { spawnSync } from "node:child_process";

import { HookResult, hookResult } from "../../orchestrator/result";
import { commandOf, isBash, unquotedSkeleton } from "../../lib/tool_input";

export const MODULE_NAME = "09_one_committer";
const ORCH_ENV = "LEDGER_ORCHESTRATOR";

// A git add/commit/push that opens a command or follows a shell separator, with an optional
// `-C <repo>`. `\b` after the verb stops `commit-msg`/`pushd`-style false positives.
const GIT_WRITE_RE = /(?:^|[;&|(]\s*|\bthen\s+|\bdo\s+)git\s+(?:-C\s+\S+\s+)?(?:add|commit|push)\b/;

// The ONLY inline sanction: an env-assignment prefix on THIS command, optionally after other
// VAR=val assignments, immediately preceding the `git` token.
const INLINE_PREFIX_RE = /^\s*(?:[A-Za-z_][A-Za-z0-9_]*=\S+\s+)*LEDGER_ORCHESTRATOR=1\s+/;

// Console handover (operator ruling 2026-09-18): splice-design lands its own rows on this
// branch, limited to exactly this path set. A write whose affected paths all live inside it is
// admitted; one outside it refuses the write by name.
const HANDOVER_EXACT: ReadonlySet<string> = new Set(["package-lock.json", ".dev/campaigns/web-console.toml"]);
const HANDOVER_DIRS: readonly string[] = ["webui/", ".dev/web-console/"];

const BROAD_ADD_FLAGS: ReadonlySet<string> = new Set([".", "-A", "--all", "-u", "--update"]);

type HookEvent = Record<string, unknown>;

export function applies(data: HookEvent): boolean {
  return isBash(data);
}

/** POSIX word splitting, the port of Python's shlex.split(command).
 *
 *  It exists because the guard must read the command's PATH TOKENS, not its text: a quoted path
 *  with a space in it is ONE token, and a path that merely looks like `git` inside a `-m "..."`
 *  argument is not a token at all. Returns null where Python raised ValueError (an unbalanced
 *  quote), because the caller's fallback — a plain whitespace split — is part of the behaviour,
 *  not an error path. */
function shlexSplit(command: string): string[] | null {
  const tokens: string[] = [];
  let current = "";
  let hasToken = false;
  let i = 0;
  const push = (): void => {
    if (hasToken) {
      tokens.push(current);
      current = "";
      hasToken = false;
    }
  };
  while (i < command.length) {
    const ch = command[i];
    if (/\s/.test(ch)) {
      push();
      i += 1;
      continue;
    }
    if (ch === "'") {
      const end = command.indexOf("'", i + 1);
      if (end < 0) return null;
      current += command.slice(i + 1, end);
      hasToken = true;
      i = end + 1;
      continue;
    }
    if (ch === '"') {
      let j = i + 1;
      let closed = false;
      while (j < command.length) {
        if (command[j] === "\\" && j + 1 < command.length) {
          current += command[j + 1];
          j += 2;
          continue;
        }
        if (command[j] === '"') {
          closed = true;
          break;
        }
        current += command[j];
        j += 1;
      }
      if (!closed) return null;
      hasToken = true;
      i = j + 1;
      continue;
    }
    if (ch === "\\") {
      if (i + 1 < command.length) {
        current += command[i + 1];
        hasToken = true;
        i += 2;
        continue;
      }
      i += 1;
      continue;
    }
    current += ch;
    hasToken = true;
    i += 1;
  }
  push();
  return tokens;
}

/** True iff the command AS WRITTEN opens with `[... VAR=val] LEDGER_ORCHESTRATOR=1 git`.
 *
 *  The prefix must immediately precede the `git` token: `LEDGER_ORCHESTRATOR=1 && git add`
 *  (a var set as its own command before a separator) and `git add x LEDGER_ORCHESTRATOR=1`
 *  (a var passed as an ARGUMENT) both fail this, deliberately — the grant is a prefix, not a
 *  presence anywhere in the command. */
function grantedInline(command: string): boolean {
  const stripped = command.replace(/^\s+/, "");
  const match = INLINE_PREFIX_RE.exec(stripped);
  if (match === null) return false;
  return stripped.slice(match[0].length).replace(/^\s+/, "").startsWith("git");
}

function inHandoverSet(rel: string): boolean {
  const normalized = rel.replace(/\\/g, "/").replace(/^\/+/, "");
  if (HANDOVER_EXACT.has(normalized)) return true;
  return HANDOVER_DIRS.some((dir) => normalized.startsWith(dir));
}

function stagedPaths(cwd: string): string[] | null {
  const proc = spawnSync("git", ["-C", cwd || ".", "diff", "--cached", "--name-only", "-z"], {
    encoding: "utf8",
    timeout: 5000,
    maxBuffer: 64 * 1024 * 1024,
  });
  if (proc.error || proc.status !== 0) return null;
  return (proc.stdout || "").split("\0").filter((p) => p !== "");
}

/** (verb, path_tokens) for a `git [-C repo] add|commit|push` invocation, or null. The
 *  caller has already confirmed a git write via the skeleton regex; this extracts the verb and
 *  its trailing tokens from the RAW command so quoted paths resolve correctly. */
function gitWriteParts(command: string): { verb: string; pathTokens: string[] } | null {
  const tokens = shlexSplit(command) ?? command.split(/\s+/);
  for (let index = 0; index < tokens.length; index += 1) {
    if (tokens[index] !== "git") continue;
    let j = index + 1;
    if (j < tokens.length && tokens[j] === "-C" && j + 1 < tokens.length) j += 2;
    if (j < tokens.length && ["add", "commit", "push"].includes(tokens[j])) {
      return { verb: tokens[j], pathTokens: tokens.slice(j + 1) };
    }
    return null;
  }
  return null;
}

/** (paths, broad). `broad` is True when the affected set is empty, unreadable, or so wide
 *  (`.`/`-A`/bare add) that the handover carve-out must refuse it fail-closed. */
function affectedPaths(data: HookEvent, command: string): { paths: string[]; broad: boolean } {
  const parts = gitWriteParts(command);
  if (parts === null) return { paths: [], broad: true };
  const { verb, pathTokens } = parts;
  if (verb === "push") return { paths: [], broad: true };
  if (verb === "commit") {
    const staged = stagedPaths(String(data.cwd ?? ""));
    if (staged === null || staged.length === 0) return { paths: [], broad: true };
    return { paths: staged, broad: false };
  }
  // add
  let flags: string[];
  let explicit: string[];
  if (pathTokens.includes("--")) {
    const split = pathTokens.indexOf("--");
    flags = pathTokens.slice(0, split);
    explicit = pathTokens.slice(split + 1);
  } else {
    flags = pathTokens.filter((t) => t.startsWith("-"));
    explicit = pathTokens.filter((t) => !t.startsWith("-"));
  }
  const broad =
    flags.some((t) => BROAD_ADD_FLAGS.has(t)) || explicit.some((t) => t === ".");
  return { paths: explicit, broad };
}

export function run(data: HookEvent): HookResult | null {
  const command = commandOf(data);
  if (!command.trim()) return null;
  const skeleton = unquotedSkeleton(command);
  if (!GIT_WRITE_RE.test(skeleton)) return null;
  if (grantedInline(command)) return null;
  const { paths, broad } = affectedPaths(data, command);
  const offending = paths.filter((p) => !inHandoverSet(p));
  if (!broad && paths.length > 0 && offending.length === 0) {
    return null; // console handover: every affected path is inside splice-design's fence
  }
  let reason =
    "§one-committer — git add/commit/push is the orchestrator's word\n\n" +
    `  command: ${command.trim()}\n`;
  if (offending.length > 0) {
    reason += `  paths outside the console-handover fence: ${offending.join(", ")}\n\n`;
  } else {
    reason += "\n";
  }
  reason +=
    "Builders never git add/commit/push; the orchestrator stages the fence and commits\n" +
    "locally, and pushes once per milestone. splice-design's own rows (webui/, " +
    ".dev/web-console/,\n" +
    ".dev/campaigns/web-console.toml, package-lock.json) are admitted path-by-path. For\n" +
    "anything else, run with the grant inline ON THIS command — a variable exported by an\n" +
    "earlier Bash call does not satisfy this guard:\n\n" +
    `  ${ORCH_ENV}=1 ${command.trim()}`;
  return hookResult("block", reason, MODULE_NAME);
}

// R1: a security gate fails closed — a crash in this module blocks the tool call rather than
// letting a git write through silently.
export const FAIL_CLOSED = true;
