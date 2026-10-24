// `gate typecheck` — every tracked TypeScript file in a typechecked program, no file gaining a type
// error (src/lib/typecheck.ts). Fails closed: a census that could not run is exit 2, never a pass.
import { WallError, wall } from "../lib/typecheck.ts";
import { layout } from "../lib/repo.ts";

export const usage = "typecheck                            tsc over every program; every tracked .ts covered, no new type errors";

export function typecheck(argv: readonly string[]): number {
  if (argv.length > 0) {
    console.error(`gate typecheck: unknown argument(s) ${argv.join(" ")} — it takes none`);
    return 2;
  }
  try {
    const report = wall(layout().repoRoot);
    for (const line of report.lines) console.log(line);
    if (report.problems.length) {
      console.error(`\nFAIL: typecheck wall — ${report.problems.length} problem(s):`);
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
