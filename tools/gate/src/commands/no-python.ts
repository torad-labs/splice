// `gate no-python` — the no-Python wall as the gate leg; `gate no-python --guard` as the PreToolUse
// hook. One library (src/lib/no-python.ts), two lifecycles, and the failure policies differ ON
// PURPOSE: the gate leg fails closed (a census that could not run is exit 2, never a pass); the
// guard fails OPEN on its own crash — it allows the write and says so on stderr, because the gate
// leg still fails the build on the same content, so the cost of a bug is a late failure, which is
// exactly the status quo the guard improves on. Failing closed there would block every Write in the
// repo on a typo in a hook.
//
// Exit 2 is the PreToolUse blocking contract: the tool call is refused and stderr is handed back
// as the reason. Exit 0 with output would merely be a note nobody has to act on.
import { WallError, guardVerdict, wall } from "../lib/no-python.ts";
import { layout } from "../lib/repo.ts";

export const usage = "no-python [--guard]                  the no-Python wall; --guard judges a PreToolUse event on stdin";

export async function noPython(argv: readonly string[]): Promise<number> {
  if (argv.length === 1 && argv[0] === "--guard") return guard();
  if (argv.length > 0) {
    console.error(`gate no-python: unknown argument(s) ${argv.join(" ")} — only --guard is accepted`);
    return 2;
  }
  try {
    const report = wall(layout().repoRoot);
    for (const line of report.lines) console.log(line);
    if (report.problems.length) {
      console.error(`\nFAIL: no-python wall — ${report.problems.length} problem(s):`);
      for (const p of report.problems) console.error("  x " + p);
      return 1;
    }
    console.log(`\n${report.summary}`);
    return 0;
  } catch (e) {
    if (e instanceof WallError) {
      console.error(e.message);
      return e.exit;
    }
    throw e;
  }
}

async function guard(): Promise<number> {
  try {
    const raw = await Bun.stdin.text();
    if (!raw.trim()) return 0;
    const verdict = guardVerdict(layout().repoRoot, JSON.parse(raw) as Parameters<typeof guardVerdict>[1]);
    if (verdict === null) return 0;
    console.error(verdict);
    return 2;
  } catch (e) {
    console.error(`no-python guard: allowing the write, guard itself failed (${e}) — \`gate no-python\` still gates the build`);
    return 0;
  }
}
