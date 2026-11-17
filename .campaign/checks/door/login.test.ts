// door-login: in a session splice launches, Claude Code's own /login is off and /login is splice's. It is the bare
// /login, in the session's menu: one command file, the only thing splice lays in the person's own Claude folder, and
// never over a /login the person wrote. Typing it opens the console's accounts page in the person's browser and sends
// the model nothing, and a head's /login does the same in place of that head's own sign-in. With no head in the door, a
// turn is refused with a message that names /login. (docs/specs/door-default.md, decisions 5 and 6.)
// (Changed Nov 17 after the builder's dispute: the check took a plugin folder's command for the bare /login, which
// Claude Code 2.1.326 offers only as /splice:login. Changed again the same day: the file also runs in a Claude Code
// splice did not start, which is accepted and said, and the hook is handed the bare command by 2.1.326.)

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { clientHead } from "../desktop/lib/desktop.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { expanded, prompt } from "../speed/lib/session.ts";
import { type Desk, SPLICE_LOGIN, type Session, ask, desk, session, slash, toDoor, under } from "./lib/claude.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const logins = `/tmp/claude-1000/door-login-${process.pid}`;
let backend: CodexBackend;
let s: Splice;
let d: Desk;

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  // the machine also has a head for claude, so splice claude head has somewhere to go back to
  s = await Splice.create(
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      clientHead({ key: "claude", command: "claude", port: await freePort(), url: backend.url }),
    gptEnv(backend.url),
  );
  await s.start();
  toDoor(s);
  d = desk(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
  rmSync(logins, { recursive: true, force: true });
});

/** What Claude Code hands the session's hooks when the person types `/login` in `x`: the bare command (2.1.326), and
 *  the command file's text. */
function handed(x: Session): string[] {
  const command = slash(x, "login");
  if (!command) throw new Error("the session offers no /login");
  return ["/login", expanded(command, "")];
}

/** `text` is submitted in `x`, at a desk with `splice` on its PATH: it is caught, or not. */
function submit(x: Session, text: string): { blocked: boolean; reason: string } {
  return prompt({ ...x, env: { ...x.env, PATH: `${d.bin}:${x.env.PATH ?? ""}` } }, crypto.randomUUID(), text);
}

/** The person types `/login` in `x`, as a Claude Code that hands its hooks the command file's text. */
function typeLogin(x: Session): { blocked: boolean; reason: string } {
  return submit(x, handed(x)[1]!);
}

/** Wait for the browser to be asked for one more page than `before`, and answer the page it was asked for. */
async function opened(before: number): Promise<string> {
  await until("the browser is asked to open a page", () => d.opened().length > before, 30_000);
  return d.opened()[before]!;
}

/** Expect `page` to be the single-use page that signs the browser in to this splice's console, which opens on its
 *  accounts. */
async function expectAccounts(page: string): Promise<void> {
  expect(page.startsWith("file://")).toBe(true);
  expect(readFileSync(fileURLToPath(page), "utf8")).toContain(`http://127.0.0.1:${s.controlPort}`);
  const first = await s.api("/", { redirect: "manual" });
  expect(first.headers.get("location") ?? "").toContain("accounts");
}

describe("door-login", () => {
  test("Claude Code's own /login and /logout are off in a session on the door", async () => {
    const x = await session(s);
    expect(x.sets.DISABLE_LOGIN_COMMAND).toBe("1");
    expect(x.sets.DISABLE_LOGOUT_COMMAND).toBe("1");
  });

  test("the session offers the bare /login as splice's accounts, and it is the one file splice lays in the person's folder", async () => {
    const x = await session(s);
    const command = slash(x, "login");
    expect(command).not.toBeNull();
    expect(command!.meta.description ?? "").toMatch(/account/i);
    expect(command!.meta.description ?? "").toMatch(/splice/i);
    expect(under(join(s.home, ".claude"))).toEqual([SPLICE_LOGIN]);
  });

  test("the file and splice claude name both ways on: splice's accounts, and Claude Code's own sign-in", async () => {
    // a Claude Code splice did not start runs the file too, and submits its text
    const command = slash(await session(s), "login");
    expect(command).not.toBeNull();
    expect(command!.body).toContain("splice console");
    expect(command!.body).toContain("claude auth login");
    const said = s.cli(["claude"]);
    expect(said.code).toBe(0);
    expect(said.out + said.err).toContain("/login");
    expect(said.out + said.err).toContain("claude auth login");
  });

  test("typing /login opens the accounts page in the browser and sends the model nothing", async () => {
    const x = await session(s);
    for (const text of handed(x)) {
      const before = d.opened().length;
      const turns = backend.turns().length;
      const said = submit(x, text);
      expect(said.blocked).toBe(true);
      expect(said.reason).toMatch(/account/i);
      await expectAccounts(await opened(before));
      expect(backend.turns().length).toBe(turns);
    }
  }, 90_000);

  test("a head's /login opens the same page, in place of that head's own sign-in", async () => {
    const h = await session(s, "claude-astra");
    expect(h.sets.DISABLE_LOGIN_COMMAND).toBe("1");
    const command = slash(h, "login");
    expect(command).not.toBeNull();
    expect(command!.meta.description ?? "").toMatch(/account/i);
    const before = d.opened().length;
    expect(typeLogin(h).blocked).toBe(true);
    await expectAccounts(await opened(before));
  }, 60_000);

  test("a /login the person wrote is left as it is, splice claude says so, and splice's comes back when theirs is gone", async () => {
    const file = join(s.home, ".claude", SPLICE_LOGIN);
    const theirs = "---\ndescription: My own login\n---\nDo my own thing.\n";
    rmSync(file, { force: true });
    mkdirSync(join(s.home, ".claude", "commands"), { recursive: true });
    writeFileSync(file, theirs);
    toDoor(s);
    await session(s);
    expect(readFileSync(file, "utf8")).toBe(theirs);
    const said = s.cli(["claude"]);
    expect(said.code).toBe(0);
    expect(said.out + said.err).toContain(SPLICE_LOGIN);

    rmSync(file);
    const command = slash(await session(s), "login");
    expect(command).not.toBeNull();
    expect(command!.meta.description ?? "").toMatch(/account/i);
  });

  test("splice claude head takes splice's /login out of the person's folder, and leaves one they wrote", async () => {
    const file = join(s.home, ".claude", SPLICE_LOGIN);
    await session(s);
    expect(existsSync(file)).toBe(true);
    expect(s.cli(["claude", "head"]).code).toBe(0);
    expect(existsSync(file)).toBe(false);

    const theirs = "---\ndescription: My own login\n---\nDo my own thing.\n";
    mkdirSync(join(s.home, ".claude", "commands"), { recursive: true });
    writeFileSync(file, theirs);
    toDoor(s);
    expect(s.cli(["claude", "head"]).code).toBe(0);
    expect(readFileSync(file, "utf8")).toBe(theirs);
  });

  test("with no head in the door, claude still starts, and a turn is refused with a message that names /login", async () => {
    // back on the door, with no /login of the person's in the folder, whatever the test before left
    rmSync(join(s.home, ".claude", SPLICE_LOGIN), { force: true });
    toDoor(s);
    rmSync(join(logins, "astra-auth.json"));
    const x = await session(s);
    expect(x.sets.CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(slash(x, "login")).not.toBeNull();
    let t = await ask(x);
    await until("the door has no head", async () => (t = await ask(x)).status !== 200, 30_000);
    const turns = backend.turns().length;
    t = await ask(x);
    expect(t.status).toBeGreaterThanOrEqual(400);
    expect(t.status).toBeLessThan(500);
    // not one Claude Code waits on and sends again
    expect([408, 409, 429]).not.toContain(t.status);
    expect(t.error).not.toBeNull();
    expect(t.error!.message).toContain("/login");
    expect(backend.turns().length).toBe(turns);
  }, 90_000);
});
