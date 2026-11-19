// nd-look: tools/console-look opens a console page on a private display of its own (Xvfb, never the person's screen),
// at the width asked, takes a screenshot from that display, reads the page's text, and with --live lets no write leave
// the browser: every request that is not a GET (the console sign-in trade aside) is refused before it is sent, and
// listed.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { daemon, ROOT, world, type Daemon } from "./lib/deck.ts";

const look = (...args: string[]) => spawnSync("bun", ["tools/console-look", ...args], { cwd: ROOT, encoding: "utf8", timeout: 120_000 });
const started = (r: ReturnType<typeof look>) => {
  expect(r.status, `${r.stdout}${r.stderr}`).toBe(0);
  const line = r.stdout.trim().split("\n").pop() || "{}";
  const id = (JSON.parse(line) as { id?: string }).id;
  expect(typeof id).toBe("string");
  return id!;
};

let d: Daemon;
let out: string;
const ids: string[] = [];
beforeAll(() => {
  d = daemon(world());
  out = mkdtempSync(join(tmpdir(), "console-look-check-"));
});
afterAll(() => {
  for (const id of ids) look("stop", id);
  d?.stop();
  rmSync(out, { recursive: true, force: true });
});

test("a page opened at 3840 on its own display is screenshotted at 3840 and its text read", () => {
  const id = started(look("start", "--width", "3840", "--height", "2160", `${d.url}/probe/writes.html`));
  ids.push(id);
  const png = join(out, "wide.png");
  const shot = look("shot", id, png);
  expect(shot.status, `${shot.stdout}${shot.stderr}`).toBe(0);
  expect(existsSync(png)).toBe(true);
  const bytes = readFileSync(png);
  expect(bytes.subarray(1, 4).toString("ascii")).toBe("PNG");
  expect([bytes.readUInt32BE(16), bytes.readUInt32BE(20)]).toEqual([3840, 2160]);
  const text = look("text", id);
  expect(text.status, text.stderr).toBe(0);
  expect(text.stdout).toContain("Writes");
  const stop = look("stop", id);
  expect(stop.status, stop.stderr).toBe(0);
  ids.pop();
}, 180_000);

test("with --live, no write leaves the browser, and each refused one is listed", async () => {
  d.seen.length = 0;
  const id = started(look("start", "--live", "--width", "1440", "--height", "900", `${d.url}/probe/writes.html`));
  ids.push(id);
  await Bun.sleep(3_000);
  expect(d.seen.some((s) => s.method === "GET" && s.path === "/api/heads")).toBe(true);
  expect(d.seen.filter((s) => s.method !== "GET" && s.method !== "HEAD")).toEqual([]);
  const blocked = look("blocked", id);
  expect(blocked.status, blocked.stderr).toBe(0);
  expect(blocked.stdout).toContain("POST /api/sessions/probe-session/say");
  expect(blocked.stdout).toContain("PATCH /api/config");
  look("stop", id);
  ids.pop();
}, 180_000);
