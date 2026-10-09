// The included builds a settings.gradle.kts declares, read from its syntax tree.
//
// A regex over the file's text silently drops any declaration it was not written for, and reads calls that sit in a
// comment or a string. A build that drops out of the denominator turns every coverage proof green for the source it
// holds, so ast-grep's Kotlin grammar finds each call. The one argument decoded is a plain string literal, the spelling
// this repo uses. Any other spelling FAILS the proof by naming the call, so a new spelling is a visible edit here and
// never a silent gap.
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { astGrepBin } from "./astgrep.ts";

interface CallMatch {
  readonly line: number;
  readonly argument: string;
}

/** The directory an `includeBuild` argument names when it is a plain string literal, else null. */
function directoryOf(argument: string): string | null {
  return /^"([^"\\$]+)"$/.exec(argument.trim())?.[1] ?? null;
}

function callsOf(repoRoot: string, settingsText: string): CallMatch[] {
  const dir = mkdtempSync(join(tmpdir(), "gate-settings-"));
  try {
    const file = join(dir, "settings.gradle.kts");
    writeFileSync(file, settingsText);
    const proc = Bun.spawnSync(
      [astGrepBin(repoRoot), "run", "--pattern", "includeBuild($A)", "--lang", "kotlin", "--json=compact", file],
      { stdout: "pipe", stderr: "pipe" },
    );
    // `run --pattern` exits 1 with nothing on stderr when no call matched, which is an answer (none declared).
    if (proc.exitCode !== 0 && !(proc.exitCode === 1 && proc.stderr.toString().trim() === "")) {
      throw new Error(`gate: ast-grep could not read settings.gradle.kts (exit ${proc.exitCode}): ${proc.stderr.toString().trim()}`);
    }
    const hits = JSON.parse(proc.stdout.toString() || "[]") as {
      range: { start: { line: number } };
      metaVariables: { single: { A?: { text: string } } };
    }[];
    return hits.map((h) => ({ line: h.range.start.line + 1, argument: h.metaVariables.single.A?.text ?? "" }));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** Every directory `includeBuild(...)` names, in declaration order, each once. Throws on a call it cannot resolve. */
export function includedBuildDirs(repoRoot: string, settingsText: string, settingsPath = "settings.gradle.kts"): string[] {
  const dirs: string[] = [];
  for (const call of callsOf(repoRoot, settingsText)) {
    const dir = directoryOf(call.argument);
    if (dir === null) {
      throw new Error(
        `gate: ${settingsPath}:${call.line} includeBuild(${call.argument}) cannot be resolved from the syntax tree — ` +
          `spell the directory as a plain string literal, or teach gradle-settings.ts the new spelling`,
      );
    }
    if (!dirs.includes(dir)) dirs.push(dir);
  }
  return dirs;
}
