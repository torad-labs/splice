// The included builds a settings.gradle.kts declares, read from its syntax tree.
//
// A regex over the file's text silently drops any declaration it was not written for, and reads calls that sit in a
// comment or a string. A build that drops out of the denominator turns every coverage proof green for the source it
// holds, so ast-grep's Kotlin grammar finds each call. Every call named `includeBuild` is found, whatever its receiver,
// arguments or trailing block, because a pattern for one shape silently skips the others. A call decodes when its one
// argument is a plain string literal, or `file("...")` of one, with or without `settings.` and a configuration block.
// Any other spelling FAILS the proof by naming the call, so a new spelling is a visible edit here and never a silent gap.
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { astGrepBin } from "./astgrep.ts";

interface CallMatch {
  readonly line: number;
  readonly text: string;
}

const DIRECTORY = String.raw`"([^"\\$]+)"`;
const BLOCK = String.raw`\s*(?:\{[\s\S]*\})?`;
const PLAIN = new RegExp(String.raw`^(?:settings\s*\.\s*)?includeBuild\s*\(\s*${DIRECTORY}\s*\)${BLOCK}$`);
const FILE = new RegExp(
  String.raw`^(?:settings\s*\.\s*)?includeBuild\s*\(\s*file\s*\(\s*${DIRECTORY}\s*\)\s*\)${BLOCK}$`,
);

/** Every call whose callee is named includeBuild, qualified or not. */
const CALLS_RULE = `id: include-build
language: kotlin
rule:
  kind: call_expression
  regex: '^(?:[A-Za-z_]\\w*\\s*\\.\\s*)*includeBuild\\s*[({]'
`;

/** The directory an `includeBuild` call names in a spelling this reader decodes, else null. */
function directoryOf(call: string): string | null {
  return (PLAIN.exec(call) ?? FILE.exec(call))?.[1] ?? null;
}

function callsOf(repoRoot: string, settingsText: string): CallMatch[] {
  const dir = mkdtempSync(join(tmpdir(), "gate-settings-"));
  try {
    const file = join(dir, "settings.gradle.kts");
    const rule = join(dir, "include-build.yml");
    writeFileSync(file, settingsText);
    writeFileSync(rule, CALLS_RULE);
    const proc = Bun.spawnSync([astGrepBin(repoRoot), "scan", "--rule", rule, "--json=compact", file], {
      stdout: "pipe",
      stderr: "pipe",
    });
    // `scan` exits 0 with an empty array when no call matched, which is an answer (none declared).
    if (proc.exitCode !== 0) {
      throw new Error(`gate: ast-grep could not read settings.gradle.kts (exit ${proc.exitCode}): ${proc.stderr.toString().trim()}`);
    }
    const hits = JSON.parse(proc.stdout.toString() || "[]") as { range: { start: { line: number } }; text: string }[];
    return hits.map((h) => ({ line: h.range.start.line + 1, text: h.text }));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** Every directory `includeBuild(...)` names, in declaration order, each once. Throws on a call it cannot resolve. */
export function includedBuildDirs(repoRoot: string, settingsText: string, settingsPath = "settings.gradle.kts"): string[] {
  const dirs: string[] = [];
  for (const call of callsOf(repoRoot, settingsText)) {
    const dir = directoryOf(call.text);
    if (dir === null) {
      throw new Error(
        `gate: ${settingsPath}:${call.line} ${call.text.split("\n")[0]} cannot be resolved from the syntax tree — ` +
          `spell the directory as a plain string literal, or teach gradle-settings.ts the new spelling`,
      );
    }
    if (!dirs.includes(dir)) dirs.push(dir);
  }
  return dirs;
}
