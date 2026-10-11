// `gate allowlist --check | --write` — the secret-scan allowlist, generated from its TOML source.
// The generator and its reasons live in src/lib/allowlist.ts; this is the boundary.
import { AllowlistError, OUTPUT, SOURCE, check, write } from "../lib/allowlist.ts";
import { layout } from "../lib/repo.ts";

export const usage = "allowlist --check | --write            the secret-scan allowlist: verify the committed .txt, or regenerate it";

export function allowlist(argv: readonly string[]): number {
  const checking = argv.includes("--check");
  const writing = argv.includes("--write");
  if (argv.length !== 1 || checking === writing) {
    console.error("usage: bun tools/gate allowlist --check | --write");
    console.error("  one mode is required: --check verifies the committed file, --write regenerates it.");
    return 2;
  }
  const root = layout().repoRoot;
  try {
    if (writing) {
      console.log(`wrote ${OUTPUT} (${write(root)} lines)`);
      return 0;
    }
    const result = check(root);
    if (result.stale) {
      console.error(`${OUTPUT} is STALE or hand-edited.\n  It is generated from ${SOURCE}.\n  Run: bun tools/gate allowlist --write`);
      return 1;
    }
    console.log(`  secret-scan allowlist: generated output matches (${result.lines} lines)`);
    return 0;
  } catch (failure) {
    if (!(failure instanceof AllowlistError)) throw failure;
    console.error(`secret-scan-allow: ${failure.message}`);
    return 1;
  }
}
