// The canaries over .github/secret-scan-allow.txt and the generator's own arms
// (checks/secret-scan-allow-selftest.sh until PR 6).
//
// WHY THE CANARIES EXIST. The allowlist is applied by the org scan as `grep -vEf`, so EVERY line in
// it is a live regex — the prose included, because `grep -f` has no comment syntax. A single
// careless line silently disables secret scanning while CI stays green, which is the worst possible
// failure shape: invisible, and it removes a control rather than breaking a build. It happened three
// times in one PR (#81): an UNANCHORED exemption, bare `#` separators matching any hit containing a
// `#`, and an unbalanced `(` in prose making the whole file an INVALID pattern set. None was caught
// by review; all three are caught by a planted canary in under a second.
//
// The scan feeds `grep -nIE` output, so a hit always arrives as `<line>:<content>` and therefore
// begins with a DIGIT. Every canary below is written in that shape, and ASSEMBLED AT RUN TIME —
// a credential-shaped assignment sitting in this file would trip the very scan this proves.
import { describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AllowlistError, HEADER, OUTPUT, check, render, validEre } from "../src/lib/allowlist.ts";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const allowPath = join(repoRoot, OUTPUT);

/** True when grep -vEf lets the hit THROUGH (reported); false when the allowlist exempts it. */
function reported(hit: string, allowlist: string): boolean {
  const proc = spawnSync("grep", ["-vEf", allowlist], { input: `${hit}\n` });
  if (proc.status !== null && proc.status > 1) throw new Error(`grep rejected ${allowlist}: ${proc.stderr}`);
  return proc.status === 0;
}

describe("the committed allowlist", () => {
  test("is exactly what its source renders to", () => {
    expect(check(repoRoot)).toEqual({ lines: readFileSync(allowPath, "utf8").split("\n").length - 1, stale: false });
  });

  test("carries no blank line, only valid EREs, and only bracketed fully anchored exemptions", () => {
    const lines = readFileSync(allowPath, "utf8").split("\n");
    expect(lines.at(-1)).toBe("");
    for (const [index, line] of lines.slice(0, -1).entries()) {
      const where = `line ${index + 1}`;
      // A blank line is an empty regex and matches EVERY hit.
      expect(/^\s*$/.test(line), `${where} is blank — an empty regex matches every hit`).toBe(false);
      // An invalid ERE anywhere makes grep reject the WHOLE file.
      expect(validEre(line), `${where} is not a valid ERE (breaks the entire file)`).toBeNull();
      if (line.startsWith("^#")) continue; // inert prose: a hit starts with a digit
      // DR-188: "starts with ^, ends with $" is NOT fully anchored — `|` binds looser than the
      // anchors. Check the whole emitted shape, so a hand edit that drops the group fails here.
      expect(/^\^\[0-9\]\+:\[\[:space:\]\]\*\(.*\)\[\[:space:\]\]\*\$$/.test(line), `${where} is not a bracketed, fully anchored exemption: [${line}]`).toBe(true);
    }
  });

  // Split keywords from their `=` so the SOURCE stays clean while the assembled strings stay
  // byte-identical to what the scan sees in real code.
  const K = "_KEY";
  const S = "_SECRET";
  const EQ = " = ";
  const UIT = "pw@";

  test("every planted credential is still reported", () => {
    const mustReport = [
      `43:    const val CUSTOM_API${K}_RESPONSES${EQ}"customApiKeyResponses"; val OPENROUTER_API${K}${EQ}"sk-or-v1-canary000000000"`,
      `44:    const val CLIENT${S}${EQ}"canary-client-secret-000"`,
      `77:  # leftover from debugging: api${K}${EQ}"sk-live-canary00000000000"`,
      `78:  AWS${S}_ACCESS${K}${EQ}"canary00000000000000000000000000000000000" # rotate me`,
      `79:export GITHUB_TOKEN="ghp_canary0000000000000000000000000000"`,
    ];
    for (const hit of mustReport) expect(reported(hit, allowPath), `SILENTLY EXEMPTED (scan blinded): ${hit}`).toBe(true);
  });

  // Every declaration this allowlist exists for, one arm each. An exemption with no arm here is an
  // exemption nothing notices going stale: the entry keeps exempting a line that no longer exists,
  // which is room for a future credential to hide in.
  test("every intended exemption still applies", () => {
    const mustExempt = [
      `42:    const val CUSTOM_API${K}_RESPONSES${EQ}"customApiKeyResponses"`,
      `48:        base_url${EQ}"https://user:hunter2${UIT}chatgpt.example.invalid/backend-api/codex"`,
      `312:                "runtime at https://user:hunter2${UIT}chatgpt.example.invalid/backend-api/codex answered; " +`,
      `26:        val secret${EQ}"API${K}=".toByteArray() + junk + "!".toByteArray()`,
      `55:private const val UPSTREAM${S}${EQ}"splice-held-upstream-secret"`,
    ];
    for (const hit of mustExempt) expect(reported(hit, allowPath), `intended exemption no longer applies: ${hit}`).toBe(false);
  });
});

// DR-188: nothing anywhere exercised the GENERATOR — every rejection path (anchors, missing
// reason, invalid ERE) was a wall nobody had watched fail. These arms drive it on fixture sources.
describe("the generator", () => {
  // A pattern is a TOML literal string, so it may not contain a single quote; none below does.
  const source = (pattern: string) => `[[exemption]]\npattern = '${pattern}'\nreason = 'fixture arm'\n`;
  const refuses = (toml: string, phrase: string) => {
    expect(() => render(toml)).toThrow(AllowlistError);
    expect(() => render(toml)).toThrow(phrase);
  };

  test("G1: an alternation is accepted, and the emitted line BINDS it", () => {
    // Before bracketing the same input produced a line that silently exempted anything appended
    // to the first branch or prepended to the second.
    const text = render(source('val fixtureA = "aaa"|val fixtureB = "bbb"'));
    expect(text.startsWith(`${HEADER}\n`)).toBe(true);
    const dir = mkdtempSync(join(tmpdir(), "gate-allowlist-"));
    const emitted = join(dir, "allow.txt");
    writeFileSync(emitted, text);
    try {
      for (const probe of [
        '99:  AWS_SECRET_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"; val fixtureB = "bbb"',
        '98:  val fixtureA = "aaa" ; token = "ghp_canary0000000000000000000000000000"',
      ]) {
        expect(reported(probe, emitted), `an alternation exemption still swallows a whole hit line: ${probe}`).toBe(true);
      }
      // ...and the branches themselves must still be exempted, or the fix broke what it protects.
      expect(reported('10:  val fixtureB = "bbb"', emitted), "bracketing broke a legitimate alternation branch").toBe(false);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  test("G2: the breakout is refused — the BARE pattern is validated as an ERE, not a paren walk", () => {
    // `a)|(b` would close the group the generator adds and float free again; the generated line
    // `(a)|(b)` is perfectly valid, so this arm pins that the bare half of the ERE check stays.
    refuses(source('val a = "x")|(val b = "y"'), "pattern is not a valid ERE");
  });

  test("G3-G6: an anchor of its own, an invalid ERE, a missing reason and an empty list are refused", () => {
    refuses(source('^val a = "x"'), "must not carry anchors");
    refuses(source('val a = "x"('), "not a valid ERE");
    refuses(`[[exemption]]\npattern = 'val a = "x"'\n`, "missing `reason`");
    refuses("# no exemptions at all\n", "refusing to emit an empty allowlist");
  });
});
