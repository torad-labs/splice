// door-login: in a session splice launches, Claude Code's own /login is off and /login is splice's. It is in the
// session's menu, typing it opens the console's accounts page in the person's browser and sends the model nothing, and
// a head's /login does the same in place of that head's own sign-in. With no head in the door, a turn is refused with
// a message that names /login. (docs/specs/door-default.md, decisions 5 and 6.)

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { expanded, prompt } from "../speed/lib/session.ts";
import { type Desk, type Session, ask, desk, session, slash, toDoor, under } from "./lib/claude.ts";

const logins = `/tmp/claude-1000/door-login-${process.pid}`;
let backend: CodexBackend;
let s: Splice;
let d: Desk;

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  s = await Splice.create(
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }),
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

/** The person types `/login` in `x`, at a desk with `splice` on its PATH: what Claude Code submits is caught, or not. */
function typeLogin(x: Session): { blocked: boolean; reason: string } {
  const command = slash(x, "login");
  if (!command) throw new Error("the session offers no /login");
  return prompt({ ...x, env: { ...x.env, PATH: `${d.bin}:${x.env.PATH ?? ""}` } }, crypto.randomUUID(), expanded(command, ""));
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

  test("the session offers /login as splice's accounts, and nothing is laid in the person's own folder", async () => {
    const x = await session(s);
    const command = slash(x, "login");
    expect(command).not.toBeNull();
    expect(command!.meta.description ?? "").toMatch(/account/i);
    expect(command!.meta.description ?? "").toMatch(/splice/i);
    expect(under(join(s.home, ".claude"))).toEqual([]);
  });

  test("typing /login opens the accounts page in the browser and sends the model nothing", async () => {
    const x = await session(s);
    const before = d.opened().length;
    const turns = backend.turns().length;
    const said = typeLogin(x);
    expect(said.blocked).toBe(true);
    expect(said.reason).toMatch(/account/i);
    await expectAccounts(await opened(before));
    expect(backend.turns().length).toBe(turns);
  }, 60_000);

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

  test("with no head in the door, claude still starts, and a turn is refused with a message that names /login", async () => {
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
