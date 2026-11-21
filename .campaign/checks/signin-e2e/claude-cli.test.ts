// signin-e2e claude-cli: `splice login <head>` on a Claude Code head signs another account in through the same sign-in
// the console's Sign in runs: it prints the page Claude Code names, opens that page in the browser, and hands Claude
// Code the code the person pastes in the terminal. Until this feature it answered that there is no browser login for
// that kind. Claude Code is a stand-in on the daemon's PATH that prints its page, reads the code and refuses it, so
// nothing reaches Anthropic. Boots Splice through the server harness, which reads Linux's /proc: it runs on the Linux
// build box (`rig remote run --to build@buildbox -- bun test <this file>`).

import { afterAll, beforeAll, expect, setDefaultTimeout, test } from "bun:test";
import { spawn } from "node:child_process";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { clientHead } from "../desktop/lib/desktop.ts";
import { desk, type Desk } from "../door/lib/claude.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";

setDefaultTimeout(300_000);

const PAGE =
  "https://claude.com/cai/oauth/authorize?code=true&client_id=made-up-client&response_type=code" +
  "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback&scope=user%3Aprofile&state=made-up-state";
const CODE = "made-up-code-4711#made-up-state";

let stand: string;
let s: Splice;
let d: Desk;

beforeAll(async () => {
  stand = mkdtempSync(join(tmpdir(), "signin-e2e-claude-"));
  // Claude Code's `auth login --claudeai`, as a Claude Code that opens no browser of its own: it prints the page whose
  // end is a code to paste back, takes the pasted code, and refuses it.
  writeFileSync(
    join(stand, "claude"),
    [
      "#!/bin/sh",
      'echo "Opening your browser to sign in."',
      `echo "If the browser didn't open, visit: ${PAGE}"`,
      "printf 'Paste code here if prompted > '",
      "IFS= read -r code || exit 1",
      `printf '%s\\n' "$code" > '${join(stand, "got")}'`,
      'echo "Login failed: made-up refusal"',
      "exit 1",
      "",
    ].join("\n"),
  );
  chmodSync(join(stand, "claude"), 0o755);
  s = await Splice.create(clientHead({ key: "claude", command: "claude", port: await freePort(), url: "http://127.0.0.1:9" }), {
    PATH: `${stand}:${process.env.PATH ?? ""}`,
  });
  await s.start();
  d = desk(s);
}, 900_000);

afterAll(async () => {
  await s?.dispose();
  if (stand) rmSync(stand, { recursive: true, force: true });
});

test("splice login on a Claude Code head prints and opens Claude's sign-in page, and hands Claude Code the pasted code", async () => {
  const p = spawn(join(d.bin, "splice"), ["login", "claude"], {
    cwd: s.home,
    env: { ...s.env(), PATH: `${d.bin}:${stand}:${process.env.PATH ?? ""}` },
    stdio: ["pipe", "pipe", "pipe"],
  });
  let out = "";
  p.stdout.on("data", (b) => (out += String(b)));
  p.stderr.on("data", (b) => (out += String(b)));
  const ended = new Promise<number>((resolve) => p.on("exit", (code) => resolve(code ?? -1)));
  try {
    await until("splice login prints Claude's sign-in page", () => out.includes(PAGE), 120_000).catch((e) => {
      throw new Error(`${e}\n--- splice login said:\n${out}`);
    });
    await until("the browser is asked to open that page", () => d.opened().includes(PAGE), 30_000);
    p.stdin.write(`${CODE}\n`);
    await until("Claude Code is handed the pasted code", () => existsSync(join(stand, "got")) && readFileSync(join(stand, "got"), "utf8").trim() === CODE, 60_000);
    const code = await ended;
    expect(code, `a sign-in Claude Code refused ends non-zero:\n${out}`).not.toBe(0);
    expect(out).not.toMatch(/signed in as/i);
  } finally {
    if (p.exitCode === null) p.kill("SIGKILL");
  }
});
