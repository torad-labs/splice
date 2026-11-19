// nd-look: tools/console-look opens a console page on a private display of its own (Xvfb, never the person's screen),
// at the width asked, takes a screenshot from that display, reads the page's text, and with --live lets no write leave
// the browser: every request that is not a GET (the console sign-in trade aside) is refused before it is sent, and
// listed.
//
// The stub daemon runs in this process, so the tool is run with Bun.spawn and awaited: a blocking spawnSync would hold
// the thread the stub answers on, and the page could never load. Each test has its own stub, so the first test's page
// (which may post its writes, since it is not --live) never shows in the second test's record.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { daemon, ROOT, world, type Daemon } from "./lib/deck.ts";

interface Ran {
  status: number | null;
  stdout: string;
  stderr: string;
}

async function look(...args: string[]): Promise<Ran> {
  const p = Bun.spawn(["bun", "tools/console-look", ...args], { cwd: ROOT, stdout: "pipe", stderr: "pipe" });
  const timer = setTimeout(() => p.kill(), 120_000);
  const [stdout, stderr] = await Promise.all([new Response(p.stdout).text(), new Response(p.stderr).text()]);
  const status = await p.exited;
  clearTimeout(timer);
  return { status, stdout, stderr };
}

const started = (r: Ran) => {
  expect(r.status, `${r.stdout}${r.stderr}`).toBe(0);
  const line = r.stdout.trim().split("\n").pop() || "{}";
  const id = (JSON.parse(line) as { id?: string }).id;
  expect(typeof id).toBe("string");
  return id!;
};

/** Ask [probe] every half second until it says yes or [ms] pass; the last answer is returned either way. */
async function until<T>(probe: () => Promise<T>, ok: (t: T) => boolean, ms = 20_000): Promise<T> {
  const end = Date.now() + ms;
  let last = await probe();
  while (!ok(last) && Date.now() < end) {
    await Bun.sleep(500);
    last = await probe();
  }
  return last;
}

const stubs: Daemon[] = [];
const stub = () => {
  const d = daemon(world());
  stubs.push(d);
  return d;
};
let out: string;
const ids: string[] = [];
beforeAll(() => {
  out = mkdtempSync(join(tmpdir(), "console-look-check-"));
});
afterAll(async () => {
  for (const id of ids) await look("stop", id);
  for (const d of stubs) d.stop();
  rmSync(out, { recursive: true, force: true });
}, 120_000);

test("a page opened at 3840 on its own display is screenshotted at 3840 and its text read", async () => {
  const d = stub();
  const id = started(await look("start", "--width", "3840", "--height", "2160", `${d.url}/probe/writes.html`));
  ids.push(id);
  const text = await until(() => look("text", id), (r) => r.status === 0 && r.stdout.includes("Writes"));
  expect(text.status, text.stderr).toBe(0);
  expect(text.stdout).toContain("Writes");
  const png = join(out, "wide.png");
  const shot = await look("shot", id, png);
  expect(shot.status, `${shot.stdout}${shot.stderr}`).toBe(0);
  expect(existsSync(png)).toBe(true);
  const bytes = readFileSync(png);
  expect(bytes.subarray(1, 4).toString("ascii")).toBe("PNG");
  expect([bytes.readUInt32BE(16), bytes.readUInt32BE(20)]).toEqual([3840, 2160]);
  const stop = await look("stop", id);
  expect(stop.status, stop.stderr).toBe(0);
  ids.splice(ids.indexOf(id), 1);
}, 180_000);

test("with --live, no write leaves the browser, and each refused one is listed", async () => {
  const d = stub();
  const id = started(await look("start", "--live", "--width", "1440", "--height", "900", `${d.url}/probe/writes.html`));
  ids.push(id);
  // The page sends its GET and its two writes together; once the GET has arrived, give any write that got out a moment.
  await until(async () => d.seen.some((s) => s.method === "GET" && s.path === "/api/heads"), (y) => y);
  await Bun.sleep(1_500);
  expect(d.seen.some((s) => s.method === "GET" && s.path === "/api/heads")).toBe(true);
  expect(d.seen.filter((s) => s.method !== "GET" && s.method !== "HEAD")).toEqual([]);
  const blocked = await until(() => look("blocked", id), (r) => r.stdout.includes("POST /api/sessions/probe-session/say") && r.stdout.includes("PATCH /api/config"), 10_000);
  expect(blocked.status, blocked.stderr).toBe(0);
  expect(blocked.stdout).toContain("POST /api/sessions/probe-session/say");
  expect(blocked.stdout).toContain("PATCH /api/config");
  await look("stop", id);
  ids.splice(ids.indexOf(id), 1);
}, 180_000);
