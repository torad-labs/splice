// cp-diagnostics: a person whose team provider does not work can see why without reading logs. `splice claude` (and
// its --json `heads`) names each head of the door with its provider: whether it is listed in /model or left out and
// why, its credential (held and until when, none, or failed and the command's own words), and the last error its
// provider answered. `splice doctor` names a provider on command auth with its credential's state and how to sign in.
// A row of /api/perf/turns carries the provider of the head that ran it, as every head's does.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { rows } from "../desktop/lib/desktop.ts";
import { Splice, freePort, json, until } from "../server/lib/harness.ts";
import { ask, session, toDoor } from "../door/lib/claude.ts";
import { Gateway, SELECTOR, TeamCli, acme, withCli } from "./lib/team.ts";

setDefaultTimeout(120_000);

interface HeadState {
  head: string;
  command: string;
  provider: string;
  listed: boolean;
  reason?: string;
  credential?: { state: "held" | "none" | "failed"; expires_at?: string; error?: string };
  last_error?: { at: string; status: number; message: string };
}

let cli: TeamCli;
let gw: Gateway;
let s: Splice;

function heads(): HeadState[] {
  const r = s.cli(["claude", "--json"]);
  expect(r.code).toBe(0);
  return json<{ heads?: HeadState[] }>(r).heads ?? [];
}

const acmeHead = () => heads().find((h) => h.head === "acme");

beforeAll(async () => {
  cli = new TeamCli();
  cli.signIn();
  gw = new Gateway(cli).start();
  s = await Splice.boot(acme({ url: gw.url, port: await freePort(), cli, provider: "picker = true" }), withCli(cli));
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  gw?.stop();
  cli?.dispose();
});

describe("cp-diagnostics", () => {
  test("splice claude names the head, its provider, that it is listed, and the credential it holds until when", async () => {
    expect((await ask(await session(s), { model: SELECTOR })).status).toBe(200);
    const h = acmeHead();
    expect(h).toBeDefined();
    expect(h!.provider).toBe("acme");
    expect(h!.listed).toBe(true);
    expect(h!.credential?.state).toBe("held");
    expect(Number.isNaN(Date.parse(h!.credential?.expires_at ?? ""))).toBe(false);
    const text = s.cli(["claude"]);
    expect(text.out).toContain("acme");
  });

  test("the last error the provider answered is named, with its status", async () => {
    gw.next.push({ status: 503, message: "GLM-5.3-Flash is loading.", headers: { "retry-after": "5" } });
    await ask(await session(s), { model: SELECTOR });
    await until("splice claude names the last error", () => acmeHead()?.last_error?.status === 503);
    expect(acmeHead()!.last_error!.message).toContain("loading");
  });

  test("a row of /api/perf/turns carries the provider", async () => {
    const id = crypto.randomUUID();
    expect((await ask(await session(s), { model: SELECTOR, session: id })).status).toBe(200);
    let got: Awaited<ReturnType<typeof rows>> = [];
    await until("the turn's row is recorded", async () => (got = await rows(s, "acme", id)).length > 0);
    expect(got[0]!.provider).toBe("acme");
  });

  test("a credential command that fails is named with its own words, in splice claude and splice doctor", async () => {
    cli.failWith("acme-ai: your Acme SSO session ended");
    await s.restart();
    toDoor(s);
    await ask(await session(s), { model: SELECTOR });
    const h = acmeHead();
    expect(h!.credential?.state).toBe("failed");
    expect(h!.credential?.error ?? "").toContain("your Acme SSO session ended");
    const claude = s.cli(["claude"]);
    expect(claude.out).toContain("your Acme SSO session ended");
    const doctor = s.cli(["doctor"]);
    const said = `${doctor.out}\n${doctor.err}`;
    expect(said).toContain("acme");
    expect(said).toContain("splice login acme");
    cli.failWith(null);
  });
});
