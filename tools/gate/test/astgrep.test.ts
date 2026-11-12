// A signalled ast-grep is 128+signum, not 1.
//
// `npm run gate:rules` (package.json:15) is a bash `&&` chain, and bash reports a child killed by a
// signal as 128+signum: a ^C'd or OOM-killed scan left that chain with 130 or 137. Bun splits the
// two apart — `exitCode` null, `signalCode` "SIGTERM" — so reading the status as `exitCode ?? 1`
// turned "the scan never finished" into "the rules failed", the one status a caller retries or
// reports as a wall violation. The fakes below are the smallest thing that can be killed by a
// signal, put where `astGrepBin` looks first.
import { afterAll, describe, expect, test } from "bun:test";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { runAstGrep } from "../src/lib/astgrep.ts";

const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

/** A repo root whose PINNED ast-grep is `body` — the binary node_modules/.bin resolution finds. */
function repoWithAstGrep(body: string): string {
  const dir = mkdtempSync(join(tmpdir(), "gate-astgrep-"));
  workspaces.push(dir);
  const bin = join(dir, "node_modules", ".bin");
  mkdirSync(bin, { recursive: true });
  writeFileSync(join(bin, "ast-grep"), `#!/usr/bin/env bash\n${body}\n`);
  chmodSync(join(bin, "ast-grep"), 0o755);
  return dir;
}

describe("running ast-grep", () => {
  for (const [signal, status] of [["TERM", 143], ["INT", 130]] as const) {
    test(`a child killed by SIG${signal} propagates ${status}, the status the npm chain reported`, () => {
      expect(runAstGrep(repoWithAstGrep(`kill -${signal} $$`), ["scan"])).toBe(status);
    });
  }

  test("an ordinary exit status still propagates unchanged", () => {
    // the other half of the mapping: a helper that answered 143 to everything would pass the two
    // tests above and turn every rule violation into a phantom SIGTERM
    expect(runAstGrep(repoWithAstGrep("exit 1"), ["scan"])).toBe(1);
    expect(runAstGrep(repoWithAstGrep("exit 0"), ["scan"])).toBe(0);
  });
});
