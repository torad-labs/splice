// nd-own: tools/console-own starts a Splice of the campaign's own from this checkout's build, in a home folder of its
// own with a made-up configuration (made-up providers on a local stand-in upstream), so a write the console makes can be
// tried without touching the Splice running on the machine. It answers with requests already recorded, a team, and a
// probe session that is live, and `down` stops it and removes its home.
import { afterAll, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { ROOT } from "./lib/deck.ts";

const own = (...args: string[]) => spawnSync("bun", ["tools/console-own", ...args], { cwd: ROOT, encoding: "utf8", timeout: 1_800_000 });
let up: { home: string; console: string; key: string; launch: string } | null = null;
afterAll(() => { if (up) own("down", up.home); });

const read = async (path: string) => {
  const res = await fetch(`${up!.console}${path}`, { headers: { authorization: `Bearer ${up!.key}` } });
  expect(res.status, path).toBe(200);
  return (await res.json()) as Record<string, unknown>;
};

test("up starts a Splice of its own with requests, a team and a live probe session; down removes it", async () => {
  const r = own("up", "--json");
  expect(r.status, `${r.stdout}${r.stderr}`.slice(-4000)).toBe(0);
  up = JSON.parse(r.stdout.trim().split("\n").pop() || "{}");
  expect(existsSync(up!.home)).toBe(true);
  expect(up!.console).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/);
  expect(up!.console).not.toBe("http://127.0.0.1:3096");
  expect(existsSync(up!.launch)).toBe(true);

  const sessions = (await read("/api/sessions")).sessions as { availability?: string }[];
  expect(sessions.some((s) => s.availability === "live")).toBe(true);
  // /api/perf/turns answers one head at a time (PerfRoutes.kt refuses a read that names none), so read each head
  // /api/heads lists and add them up. Among the rows, at least one is the stand-in's 429.
  const keys = ((await read("/api/heads")).heads as { key: string }[]).map((h) => h.key);
  expect(keys.length).toBeGreaterThan(0);
  const rows: { outcome?: string }[] = [];
  for (const key of keys) {
    const heads = (await read(`/api/perf/turns?head=${encodeURIComponent(key)}&since=0&n=50`)).heads as { rows?: { outcome?: string }[] }[];
    for (const h of heads) rows.push(...(h.rows ?? []));
  }
  expect(rows.length).toBeGreaterThan(0);
  expect(rows.some((r) => String(r.outcome ?? "").startsWith("error")), "no request failed with the stand-in's 429").toBe(true);
  const teams = (await read("/api/teams")).teams as unknown[];
  expect(teams.length).toBeGreaterThan(0);

  const down = own("down", up!.home);
  expect(down.status, `${down.stdout}${down.stderr}`).toBe(0);
  expect(existsSync(up!.home)).toBe(false);
  const after = await fetch(`${up!.console}/api/heads`).then(() => "answered", () => "closed");
  expect(after).toBe("closed");
  up = null;
}, 1_800_000);
