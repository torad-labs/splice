// gw-retire: `splice claude retire <command>` (or `--all`) moves a head's command and every setting splice wrote in
// the head's own Claude folder into a dated backup and removes them, leaving the folder's sessions and the person's own
// files where they are and naming those files. It refuses a head a live session still runs on, naming the session,
// and changes nothing. The head's provider and login keep serving the gateway under the same names, nothing brings the
// command back by itself, and `splice claude restore <command>` (or `--all`) puts it back exactly as it was.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, readlinkSync, rmSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import { lane, session, toDoor } from "../door/lib/claude.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort, json, mockHead, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { gatewayTurn, jsonFile, launchOf, register, stand } from "./lib/gateway.ts";
import { claudeHeadToml } from "./lib/heads.ts";

setDefaultTimeout(240_000);

const ASTRA = "claude-astra--gpt-6-astra";
const BOREALIS = "claude-borealis--borealis-large";

const logins = `/tmp/claude-1000/catalog-retire-${process.pid}`;
let backend: CodexBackend;
let borealis: Upstream;
let anthropic: Provider;
let s: Splice;
let bin = "";
const standing: { stop: () => void }[] = [];

/** Every entry under `dir`, by its path from there: a link by where it points, a file by its bytes, a folder as such. */
function snapshot(dir: string): Record<string, string> {
  const out: Record<string, string> = {};
  const walk = (d: string) => {
    for (const name of readdirSync(d)) {
      const p = join(d, name);
      const st = lstatSync(p);
      const key = relative(dir, p);
      if (st.isSymbolicLink()) out[key] = `link ${readlinkSync(p)}`;
      else if (st.isDirectory()) {
        out[key] = "dir";
        walk(p);
      } else out[key] = `file ${readFileSync(p).toString("base64")}`;
    }
  };
  if (existsSync(dir)) walk(dir);
  return out;
}

/** The command link splice laid in the install's bin folder, or null when there is none. */
function link(command: string): string | null {
  const p = join(bin, command);
  try {
    return lstatSync(p).isSymbolicLink() ? readlinkSync(p) : "not a link";
  } catch {
    return null;
  }
}

const folder = (key: string) => join(s.home, `.claude-${key}`);
const said = (r: { out: string; err: string }) => (r.out + r.err).replace(/\x1b\[[0-9;]*m/g, "");

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  borealis = new Upstream("borealis", "borealis-large").start();
  anthropic = new Provider("anthropic").start();
  s = await Splice.create(
    claudeHeadToml({ port: await freePort(), url: anthropic.url }) +
      gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, model: "borealis-large" }),
    gptEnv(backend.url),
  );
  await s.start();
  bin = join(s.home, ".local", "bin");
  const installed = s.cli(["install"]);
  expect(installed.code).toBe(0);
  // each head's folder as its launches leave it, with a file of the person's and a session in it
  await launchOf(s, "claude-astra");
  await launchOf(s, "claude-borealis");
  writeFileSync(join(folder("astra"), "my-notes.md"), "mine, not splice's\n");
  mkdirSync(join(folder("astra"), "projects", "-work"), { recursive: true });
  writeFileSync(join(folder("astra"), "projects", "-work", "a-session.jsonl"), '{"type":"user"}\n');
}, 300_000);

afterAll(async () => {
  for (const x of standing) x.stop();
  await s?.dispose();
  backend?.stop();
  borealis?.stop();
  anthropic?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("gw-retire", () => {
  let before: Record<string, string> = {};
  let linkBefore: string | null = null;

  test("a head a live session runs on is refused, the session named, and nothing changes", async () => {
    const p = stand();
    standing.push(p);
    const session = crypto.randomUUID();
    await launchOf(s, "claude-astra", { launch: p.pid });
    linkBefore = link("claude-astra");
    expect(linkBefore).not.toBeNull();
    before = snapshot(folder("astra"));
    register(join(s.home, ".claude"), p.pid, session, s.home);
    await until("splice sessions lists it", () => json<{ sessions: { sessionId?: string }[] }>(s.cli(["sessions", "--json"])).sessions.some((e) => e.sessionId === session), 30_000);
    const r = s.cli(["claude", "retire", "claude-astra"]);
    expect(r.code).not.toBe(0);
    expect(said(r)).toContain(session);
    expect(link("claude-astra")).toBe(linkBefore);
    expect(snapshot(folder("astra"))).toEqual(before);
    p.stop();
  });

  test("retiring removes the command and splice's settings, keeps a dated backup, and leaves the person's files", async () => {
    await until("the head is retired once no session runs on it", () => s.cli(["claude", "retire", "claude-astra"]).code === 0 || link("claude-astra") === null, 60_000);
    expect(link("claude-astra")).toBeNull();
    const settings = jsonFile(join(folder("astra"), "settings.json"));
    for (const k of ["availableModels", "enforceAvailableModels", "modelPicker", "modelOverrides", "env"]) expect([k, k in settings]).toEqual([k, false]);
    expect("additionalModelOptionsCache" in jsonFile(join(folder("astra"), ".claude.json"))).toBe(false);
    expect(readFileSync(join(folder("astra"), "my-notes.md"), "utf8")).toBe("mine, not splice's\n");
    expect(existsSync(join(folder("astra"), "projects", "-work", "a-session.jsonl"))).toBe(true);
    const backups = join(s.state, "head-backups", "astra");
    expect(existsSync(backups) && readdirSync(backups).length > 0).toBe(true);
    // the other head is untouched
    expect(link("claude-borealis")).not.toBeNull();
  });

  test("the retired head's models stay in the gateway's list under the same names, and still run", async () => {
    toDoor(s);
    expect((lane(s).models ?? []).map((m) => m.model)).toContain(ASTRA);
    const marker = `gw-retire-${crypto.randomUUID()}`;
    // through the gateway as `claude` reaches it, Mantle's way or gateway mode's (gw-claude, built beside this)
    const x = await session(s);
    expect((await gatewayTurn({ base: x.url, key: x.key }, ASTRA, { content: marker })).status).toBe(200);
    expect(backend.turnsWith(marker).length).toBe(1);
  });

  test("nothing brings the command back by itself: install leaves it out, and a launch of it is refused saying how", async () => {
    expect(s.cli(["install"]).code).toBe(0);
    expect(link("claude-astra")).toBeNull();
    const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd: s.home, launch: String(process.pid) });
    const r = await s.api("/launch/claude-astra", { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
    expect(r.status).not.toBe(200);
    expect(await r.text()).toContain("splice claude restore claude-astra");
  });

  test("restoring puts the command and the folder back exactly as they were", () => {
    const r = s.cli(["claude", "restore", "claude-astra"]);
    expect([r.code, said(r)]).toEqual([0, said(r)]);
    expect(link("claude-astra")).toBe(linkBefore);
    expect(snapshot(folder("astra"))).toEqual(before);
  });

  test("--all retires every head, claude's own head putting claude on the gateway, and --all restores them", () => {
    expect(s.cli(["claude", "head"]).code).toBe(0);
    const astra = snapshot(folder("astra"));
    const boreal = snapshot(folder("borealis"));
    const r = s.cli(["claude", "retire", "--all"]);
    expect([r.code, said(r)]).toEqual([0, said(r)]);
    expect(link("claude-astra")).toBeNull();
    expect(link("claude-borealis")).toBeNull();
    expect(lane(s).lane).toBe("door");
    expect((lane(s).models ?? []).map((m) => m.model)).toContain(BOREALIS);
    const back = s.cli(["claude", "restore", "--all"]);
    expect([back.code, said(back)]).toEqual([0, said(back)]);
    expect(link("claude-astra")).not.toBeNull();
    expect(link("claude-borealis")).not.toBeNull();
    expect(snapshot(folder("astra"))).toEqual(astra);
    expect(snapshot(folder("borealis"))).toEqual(boreal);
    expect(lane(s).lane).toBe("head");
  });
});
