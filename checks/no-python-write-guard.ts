#!/usr/bin/env bun
/**
 * THE WRITE-TIME HALF OF checks/no-python.ts — refuse the write, say why, before it lands.
 *
 * WHY A GATE LEG WAS NOT ENOUGH, measured rather than argued. On 2026-09-18 a webui commit
 * (1b56f197) added a fresh `python3` subprocess to density.mjs and the branch went red — but not
 * at write time, and not at commit time. It went red HOURS LATER, the next time somebody happened
 * to run the wall, and it was found by a builder converting an unrelated checker. splice-builder
 * put it exactly: "a commit adding a new invoker does not go red at commit time — it only shows
 * up when someone next runs the wall." A rule that fails late is a rule the tree teaches against
 * in the meantime, because the file sits there for hours being an example.
 *
 * The operator's ruling: "it should be a PreToolUse hook that refuses the write and returns with
 * a message." This is that, and it is deliberately the SAME CHECKER as the gate leg rather than a
 * second implementation of the same idea — it imports `namesPython`, `burndown` and `SELF` from
 * checks/no-python.ts (CLAUDE.md §17 law 4: one checker source, enforced twice). Two hand-written
 * copies of one rule drift, and then agree with each other while disagreeing with the tree, which
 * is the failure the whole wall family is named for.
 *
 * WHAT IT REFUSES, and nothing more:
 *   · a NEW .py file — one not already carried as debt in the burn-down;
 *   · a write whose TEXT runs or names python, into a file not already listed as an invoker.
 * An EXISTING .py stays writable: the burn-down rows are conversions in flight, and a guard that
 * blocked edits to the debt would block the work that removes it.
 *
 * IT FAILS OPEN, ON PURPOSE AND ONLY HERE. A crash in this guard allows the write and says so on
 * stderr, because the gate leg still fails the build on the same content: the cost of a bug is a
 * late failure, which is exactly today's status quo. The reverse — failing closed — would block
 * every Write in the repo on a typo in a hook. The gate leg is the fail-closed half; this is the
 * early-warning half, and they share one predicate so neither can drift from the other.
 */
import { resolve, relative } from "node:path";
import { existsSync } from "node:fs";
import { ALLOW, SELF, burndown, mismatchedRuntimes, namesPython, pendingStrip } from "./no-python.ts";

/** The caller surface whose invocations are literal command lines — the same one the wall's
 *  seventh census reads, so the two halves are the same rule and not two readings of it. */
const CALLER = /(?:\.sh|\.mjs|package\.json)$/;

const ROOT = resolve(import.meta.dir, "..");

/** The text this tool call would PUT INTO the file — never the file's current contents.
 *  An Edit is charged on `new_string` alone so that touching an unrelated line of a file that
 *  already mentions python is not refused; the gate leg owns the whole-file verdict. */
function proposedText(tool: string, input: Record<string, unknown>): string {
  if (tool === "Write") return String(input.content ?? "");
  if (tool === "Edit") return String(input.new_string ?? "");
  if (tool === "MultiEdit") {
    const edits = Array.isArray(input.edits) ? input.edits : [];
    return edits.map((e) => String((e as Record<string, unknown>)?.new_string ?? "")).join("\n");
  }
  return "";
}

function block(message: string): never {
  // Exit 2 is the PreToolUse blocking contract: the tool call is refused and stderr is handed
  // back as the reason. Exit 0 with output would merely be a note nobody has to act on.
  console.error(message);
  process.exit(2);
}

async function main(): Promise<number> {
  const raw = await Bun.stdin.text();
  if (!raw.trim()) return 0;
  const data = JSON.parse(raw) as { tool_name?: string; tool_input?: Record<string, unknown> };
  const tool = data.tool_name ?? "";
  if (tool !== "Write" && tool !== "Edit" && tool !== "MultiEdit") return 0;

  const input = data.tool_input ?? {};
  const filePath = String(input.file_path ?? "");
  if (!filePath) return 0;

  const rel = relative(ROOT, resolve(filePath));
  // Outside this repo (a scratch directory, another worktree) is not this wall's business.
  if (!rel || rel.startsWith("..")) return 0;
  if (SELF.has(rel)) return 0;

  const list = burndown();
  const files = new Set(list.files ?? []);
  const invokers = new Set(list.invokers ?? []);

  if (rel.endsWith(".py")) {
    if (files.has(rel)) return 0; // existing debt: conversions have to be able to edit it
    block(
      `REFUSED — this repo has no Python; tooling is bun/TypeScript.\n\n` +
        `  ${rel} is a NEW .py file.\n\n` +
        `Write it as .ts and run it with bun. Adding it to ${ALLOW} is NOT available: that list is a\n` +
        `dated burn-down of what already existed, it may only shrink, and checks/no-python.ts grades\n` +
        `it against its own first commit in git — so a line added there fails the build by name.\n` +
        `If this is a throwaway, put it in a scratch directory outside the worktree instead.`,
    );
  }

  // BEFORE the invoker exemption below, because the file this actually happens to is a LISTED
  // invoker: a seat converting wall.py to wall.ts edits the caller, changes the filename and
  // leaves the interpreter. Being on the invokers list earns an exemption from naming python;
  // it never earns an exemption from naming it in front of a .ts.
  const crossed = CALLER.test(rel) ? mismatchedRuntimes(proposedText(tool, input)) : [];
  if (crossed.length) {
    block(
      `REFUSED — wrong runtime for the file's extension.\n\n` +
        `  ${rel}\n    ` +
        crossed.join("\n    ") +
        `\n\nThis is a half-finished conversion: the filename moved and the interpreter did not. It would\n` +
        `not fail here — the path resolves and the file exists, so every census on the no-python wall\n` +
        `stays green — it would fail later at run time with a syntax error that reads like a broken\n` +
        `script rather than a broken call. Change the interpreter to match the extension.`,
    );
  }

  // The same third disposition the wall's invoker census applies, and it has to be here too or
  // the two halves disagree about the same file: a hook module that calls the still-Python
  // manifest tool would be refused at write time and passed at gate time. Only entries whose
  // tool still exists AND is still burn-down debt strip anything.
  let charged = proposedText(tool, input);
  for (const p of list.pendingTools ?? []) {
    if (!existsSync(p.tool) || !(list.files ?? []).includes(p.tool)) continue;
    if (p.callers.includes(rel)) charged = pendingStrip(charged, p.tool);
  }
  if (namesPython(charged) && !invokers.has(rel)) {
    block(
      `REFUSED — this repo has no Python; tooling is bun/TypeScript.\n\n` +
        `  ${rel} is not a listed invoker, and this write makes it run or name python.\n\n` +
        `If it SHELLS OUT: do the work in bun instead. The last file to do this decoded a PNG through\n` +
        `a subprocess; zlib and forty lines of filter cases replaced it, byte-identical over seven\n` +
        `frames. If it is PROSE — a comment, a README, a command in a docstring — reword it. A\n` +
        `sentence naming the interpreter is what teaches the next session which language this repo\n` +
        `writes tooling in, and that is how every file on the burn-down got there.\n` +
        `Adding a line to ${ALLOW} is not the remedy; it is the violation the wall exists to catch.`,
    );
  }
  return 0;
}

try {
  process.exit(await main());
} catch (e) {
  console.error(`no-python-write-guard: allowing the write, guard itself failed (${e}) — checks/no-python.ts still gates the build`);
  process.exit(0);
}
