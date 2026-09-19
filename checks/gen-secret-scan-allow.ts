#!/usr/bin/env bun
/**
 * Generate .github/secret-scan-allow.txt from .github/secret-scan-allow.toml.
 *
 * WHY THIS EXISTS (brain concept #924 — "you make drift not compile").
 *
 * The allowlist is consumed as `grep -vEf`, so every line of it is a live regex, prose included.
 * That format lies: it looks like a config file with `#` comments, and every prior — human and
 * model — says `#` is inert. Three hazards shipped on that prior in PR #81, two of them into the
 * SAME FILE in the SAME PR hours apart, because the first was fixed as an instance rather than as
 * a class. Canaries and review CAUGHT them; nothing PREVENTED them.
 *
 * This generator moves all three from detected to inexpressible:
 *
 *     hazard                          before                    after
 *     unanchored entry                hand-written, hopefully   generator applies ^(...)$
 *     prose as a live regex           `#` looks like a comment  no prose slot exists at all
 *     invalid ERE breaks the file     silent until CI           generation fails
 *     alternation escapes the anchors generator applied ^...$   pattern is BRACKETED (DR-188)
 *
 * DR-188 is the same hazard as the first row, under a spelling the first fix did not cover: `|` has
 * the LOWEST precedence in ERE, so `^...a|b...$` is `(^...a)` OR `(b...$)` and the anchors bind only
 * the outermost alternatives. The generator now wraps the pattern in a group, and refuses a pattern
 * that would close that group itself. The row above it is why this file exists at all — a hazard
 * fixed as an instance re-derives itself as a class — and DR-188 is that sentence coming true a
 * second time about this very table.
 *
 * The remaining hand-edit risk — someone editing the .txt directly — is closed by `--check`, which
 * the gate runs (the same regenerate-and-diff idiom already used for webui/dist).
 *
 * Usage:
 *     bun checks/gen-secret-scan-allow.ts --write   # write the .txt
 *     bun checks/gen-secret-scan-allow.ts --check   # verify the committed .txt is current
 *
 * THERE IS NO BARE MODE, AND THAT IS DELIBERATE. This file used to write the allowlist on any
 * invocation it did not recognise, so a mis-invocation silently REGENERATED the artifact it guards —
 * a checker editing its own subject is worse than a false green, because the diff it leaves behind
 * looks like an intentional update. `--check` is the gate's mode and `--write` is the explicit one;
 * anything else is misuse, and misuse exits non-zero without touching a file.
 */
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SRC = resolve(ROOT, ".github", "secret-scan-allow.toml");
const OUT = resolve(ROOT, ".github", "secret-scan-allow.txt");

// A hit reaches the allowlist as `grep -nIE` output: `<line>:<content>`. Every emitted pattern is
// bound to a WHOLE such line, so an exemption can never match a line that merely CONTAINS the
// declaration — which is how appending a credential to it bypassed the scan.
const PREFIX = "^[0-9]+:[[:space:]]*";
const SUFFIX = "[[:space:]]*$";

// The one prose line in the output. Anchored at ^# so it is inert: a hit always begins with a
// DIGIT, so this can never match one. It is generated rather than typed for exactly the reason
// this whole script exists.
const HEADER = "^# GENERATED from secret-scan-allow.toml by checks/gen-secret-scan-allow.ts. DO NOT EDIT.";

const USAGE = "usage: bun checks/gen-secret-scan-allow.ts --check | --write";

function die(msg: string): never {
  process.stderr.write(`secret-scan-allow: ${msg}\n`);
  process.exit(1);
}

/** Return grep's error for an invalid ERE, or null. grep is the authority here, not a JS regex —
 *  the consumer is grep, and the two dialects disagree (an unbalanced `(` is fatal to grep and
 *  merely different in JS). */
function validEre(pattern: string): string | null {
  const proc = spawnSync("grep", ["-E", "--", pattern], { input: "x\n" });
  // 0 = matched, 1 = no match; both mean the pattern compiled. 2 = bad regex.
  return proc.status !== null && proc.status > 1
    ? proc.stderr.toString().trim() || "invalid extended regex"
    : null;
}

function render(): string {
  if (!existsSync(SRC)) die(`missing source: ${SRC}`);
  const data = Bun.TOML.parse(readFileSync(SRC, "utf8")) as { exemption?: Record<string, unknown>[] };
  const entries = data.exemption ?? [];
  if (entries.length === 0) die("no [[exemption]] entries — refusing to emit an empty allowlist");

  const lines = [HEADER];
  entries.forEach((entry, i0) => {
    const i = i0 + 1;
    const pattern = typeof entry.pattern === "string" ? entry.pattern : undefined;
    const reason = String(entry.reason ?? "").trim();
    if (!pattern) die(`exemption ${i}: missing \`pattern\``);
    if (!reason) die(`exemption ${i}: missing \`reason\` — an unexplained exemption is not reviewable`);
    // Anchoring belongs to the generator. A pattern that brings its own would let an entry
    // widen its own reach, which is hazard 1 all over again.
    if (pattern.startsWith("^") || pattern.endsWith("$")) {
      die(`exemption ${i}: \`pattern\` must not carry anchors; the generator adds them`);
    }
    if (pattern.includes("\n")) die(`exemption ${i}: \`pattern\` must be a single line`);

    // DR-188: the pattern is BRACKETED, and that group is what makes the anchors mean anything.
    // ERE gives `|` the LOWEST precedence, so a bare `{PREFIX}a|b{SUFFIX}` parses as
    // `(^…a)` OR `(b…$)`: the anchors bind only the first and last alternatives and every branch
    // between them floats free. A pattern of `a|b` therefore reintroduced hazard 1 — a credential
    // appended to the first branch, or prepended to the second, was silently exempted — while the
    // anchor check below passed it (it carries no leading `^` and no trailing `$`), the selftest's
    // structural rule called it "fully anchored" (it does start with `^` and end with `$`), and
    // the canaries missed it whenever the branches did not collide with the canary corpus. Three
    // guards, three misses, on the one hazard this generator exists to make inexpressible.
    //
    // The group cannot be escaped, and the reason is the BARE-pattern half of the ERE check
    // below — not a paren walk of our own. A pattern would break out by closing the added group
    // itself (`a)|(b` becomes `(a)|(b)`, whose second alternative floats free again), and that
    // requires an unmatched `)`, which grep rejects outright. The GENERATED line for such a
    // pattern is perfectly valid, so it is validating the pattern ON ITS OWN that closes this —
    // drop that half and the breakout reopens. The selftest pins it; a paren walk here would be
    // a check that cannot fire, which is the defect this file's own subject matter is about.
    const line = `${PREFIX}(${pattern})${SUFFIX}`;
    for (const [candidate, what] of [
      [pattern, "pattern"],
      [line, "generated line"],
    ] as const) {
      const err = validEre(candidate);
      if (err) die(`exemption ${i}: ${what} is not a valid ERE (${err}): ${candidate}`);
    }
    lines.push(line);
  });

  return lines.join("\n") + "\n";
}

function main(argv: string[]): number {
  const check = argv.includes("--check");
  const write = argv.includes("--write");
  if (check === write) {
    process.stderr.write(`${USAGE}\n`);
    process.stderr.write(
      "  one mode is required: --check verifies the committed file, --write regenerates it.\n",
    );
    return 2;
  }
  const text = render();
  if (check) {
    const current = existsSync(OUT) ? readFileSync(OUT, "utf8") : "";
    if (current !== text) {
      process.stderr.write(
        "secret-scan-allow.txt is STALE or hand-edited.\n" +
          "  It is generated from .github/secret-scan-allow.toml.\n" +
          "  Run: bun checks/gen-secret-scan-allow.ts --write\n",
      );
      return 1;
    }
    process.stdout.write(
      `  secret-scan allowlist: generated output matches (${text.split("\n").length - 1} lines)\n`,
    );
    return 0;
  }
  writeFileSync(OUT, text);
  process.stdout.write(`wrote .github/secret-scan-allow.txt (${text.split("\n").length - 1} lines)\n`);
  return 0;
}

process.exit(main(process.argv.slice(2)));
