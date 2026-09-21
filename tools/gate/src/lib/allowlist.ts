// THE SECRET-SCAN ALLOWLIST, GENERATED — .github/secret-scan-allow.txt from
// .github/secret-scan-allow.toml (checks/gen-secret-scan-allow.ts and its shell canary until PR 6;
// the verb is src/commands/allowlist.ts, the canaries and generator arms test/allowlist.test.ts).
//
// WHY THIS EXISTS (brain concept #924 — "you make drift not compile"). The allowlist is consumed as
// `grep -vEf`, so every line of it is a live regex, prose included. That format lies: it looks like
// a config file with `#` comments, and every prior — human and model — says `#` is inert. Three
// hazards shipped on that prior in PR #81, two of them into the SAME FILE in the SAME PR hours
// apart, because the first was fixed as an instance rather than as a class. Canaries and review
// CAUGHT them; nothing PREVENTED them. The generator moves all three from detected to inexpressible:
//
//     hazard                          before                    after
//     unanchored entry                hand-written, hopefully   generator applies ^(...)$
//     prose as a live regex           `#` looks like a comment  no prose slot exists at all
//     invalid ERE breaks the file     silent until CI           generation fails
//     alternation escapes the anchors generator applied ^...$   pattern is BRACKETED (DR-188)
//
// DR-188 is the same hazard as the first row, under a spelling the first fix did not cover: `|` has
// the LOWEST precedence in ERE, so `^...a|b...$` is `(^...a)` OR `(b...$)` and the anchors bind only
// the outermost alternatives. The generator wraps the pattern in a group, and refuses a pattern that
// would close that group itself. The remaining hand-edit risk — someone editing the .txt directly —
// is closed by `--check`, which the gate runs (the regenerate-and-diff idiom).
//
// THERE IS NO BARE MODE, AND THAT IS DELIBERATE: this generator once wrote the allowlist on any
// invocation it did not recognise, so a mis-invocation silently REGENERATED the artifact it guards.
// `--check` is the gate's mode and `--write` the explicit one; anything else is misuse (exit 2).
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const SOURCE = ".github/secret-scan-allow.toml";
export const OUTPUT = ".github/secret-scan-allow.txt";

// A hit reaches the allowlist as `grep -nIE` output: `<line>:<content>`. Every emitted pattern is
// bound to a WHOLE such line, so an exemption can never match a line that merely CONTAINS the
// declaration — which is how appending a credential to it bypassed the scan.
export const PREFIX = "^[0-9]+:[[:space:]]*";
export const SUFFIX = "[[:space:]]*$";

// The one prose line in the output. Anchored at ^# so it is inert: a hit always begins with a
// DIGIT, so this can never match one. It is generated rather than typed for exactly the reason
// this whole file exists.
export const HEADER = "^# GENERATED from secret-scan-allow.toml by `bun tools/gate allowlist --write`. DO NOT EDIT.";

/** A source the generator refuses to render into an allowlist; the message names the entry and why. */
export class AllowlistError extends Error {}

/** grep's error for an invalid ERE, or null. grep is the authority here, not a JS regex — the
 *  consumer is grep, and the two dialects disagree (an unbalanced `(` is fatal to grep and merely
 *  different in JS). */
export function validEre(pattern: string): string | null {
  const proc = spawnSync("grep", ["-E", "--", pattern], { input: "x\n" });
  // 0 = matched, 1 = no match; both mean the pattern compiled. 2 = bad regex.
  return proc.status !== null && proc.status > 1 ? proc.stderr.toString().trim() || "invalid extended regex" : null;
}

/** The allowlist text for a TOML source, or an [AllowlistError]. */
export function render(toml: string): string {
  const data = Bun.TOML.parse(toml) as { exemption?: Record<string, unknown>[] };
  const entries = data.exemption ?? [];
  if (entries.length === 0) throw new AllowlistError("no [[exemption]] entries — refusing to emit an empty allowlist");

  const lines = [HEADER];
  entries.forEach((entry, i0) => {
    const i = i0 + 1;
    const pattern = typeof entry.pattern === "string" ? entry.pattern : undefined;
    const reason = String(entry.reason ?? "").trim();
    if (!pattern) throw new AllowlistError(`exemption ${i}: missing \`pattern\``);
    if (!reason) throw new AllowlistError(`exemption ${i}: missing \`reason\` — an unexplained exemption is not reviewable`);
    // Anchoring belongs to the generator. A pattern that brings its own would let an entry widen
    // its own reach, which is hazard 1 all over again.
    if (pattern.startsWith("^") || pattern.endsWith("$")) {
      throw new AllowlistError(`exemption ${i}: \`pattern\` must not carry anchors; the generator adds them`);
    }
    if (pattern.includes("\n")) throw new AllowlistError(`exemption ${i}: \`pattern\` must be a single line`);

    // DR-188: the pattern is BRACKETED, and that group is what makes the anchors mean anything.
    // ERE gives `|` the LOWEST precedence, so a bare `{PREFIX}a|b{SUFFIX}` parses as `(^…a)` OR
    // `(b…$)`: the anchors bind only the first and last alternatives and every branch between them
    // floats free. The group cannot be escaped, and the reason is the BARE-pattern half of the ERE
    // check below — not a paren walk of our own. A pattern would break out by closing the added
    // group itself (`a)|(b` becomes `(a)|(b)`, whose second alternative floats free again), and
    // that requires an unmatched `)`, which grep rejects outright. The GENERATED line for such a
    // pattern is perfectly valid, so validating the pattern ON ITS OWN is what closes this — drop
    // that half and the breakout reopens. The test pins it.
    const line = `${PREFIX}(${pattern})${SUFFIX}`;
    for (const [candidate, what] of [
      [pattern, "pattern"],
      [line, "generated line"],
    ] as const) {
      const err = validEre(candidate);
      if (err) throw new AllowlistError(`exemption ${i}: ${what} is not a valid ERE (${err}): ${candidate}`);
    }
    lines.push(line);
  });
  return lines.join("\n") + "\n";
}

function source(root: string): string {
  const path = join(root, SOURCE);
  if (!existsSync(path)) throw new AllowlistError(`missing source: ${SOURCE}`);
  return readFileSync(path, "utf8");
}

/** The gate's mode: the committed allowlist is exactly what the source renders to. */
export function check(root: string): { lines: number; stale: boolean } {
  const text = render(source(root));
  const outputPath = join(root, OUTPUT);
  const current = existsSync(outputPath) ? readFileSync(outputPath, "utf8") : "";
  return { lines: text.split("\n").length - 1, stale: current !== text };
}

/** The explicit mode: regenerate the allowlist; the line count written. */
export function write(root: string): number {
  const text = render(source(root));
  writeFileSync(join(root, OUTPUT), text);
  return text.split("\n").length - 1;
}
