// nd-whole: the whole console is the Night Deck. The console folder holds the six approved pages and nothing of the
// old console that nothing uses: every stylesheet and script in it is one a page loads. Each page draws its shell at
// both widths and in both themes without an error, no page's own CSS or JS writes a raw value, and nothing in the
// console names a home folder. A Splice built from this checkout sends the old pages' addresses, query kept, to the page
// that took their content.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { type Browser } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { freePort, mockHead, Splice } from "../server/lib/harness.ts";
import { browser, CONSOLE, daemon, expectFrame, ROOT, SIX, world, type Daemon } from "./lib/deck.ts";

const files = readdirSync(CONSOLE).filter((f) => /\.(html|css|js)$/.test(f)).sort();
const pages = files.filter((f) => f.endsWith(".html"));

test("the console's pages are the six approved pages", () => {
  expect(pages).toEqual(SIX.map((s) => `${s.toLowerCase()}.html`).sort());
});

test("every stylesheet and script in the console is one a page loads", () => {
  const html = pages.map((p) => readFileSync(join(CONSOLE, p), "utf8")).join("\n");
  const unused = files.filter((f) => !f.endsWith(".html") && !new RegExp(`(src|href)="/?${f.replace(/[.]/g, "\\.")}"`).test(html));
  expect(unused).toEqual([]);
});

test("nothing in the console names a home folder", () => {
  const named = files.filter((f) => /\/home\/[a-z]|\/Users\/[A-Za-z]/.test(readFileSync(join(CONSOLE, f), "utf8")));
  expect(named).toEqual([]);
});

test("no page's own CSS or JS writes a raw length, colour or radius", () => {
  const r = spawnSync("bun", ["tools/console/deck-raw.ts"], { cwd: ROOT, encoding: "utf8" });
  expect(r.status, `${r.stdout}${r.stderr}`).toBe(0);
});

let b: Browser;
let d: Daemon;
beforeAll(async () => { b = await browser(); d = daemon(world()); }, 30_000);
afterAll(async () => { await b?.close(); d?.stop(); });

const SHELL: Record<string, { railL: boolean; railR: boolean; composer: boolean }> = {
  Sessions: { railL: false, railR: true, composer: true },
  Accounts: { railL: true, railR: true, composer: true },
  Teams: { railL: false, railR: false, composer: false },
  Providers: { railL: false, railR: false, composer: false },
  Activity: { railL: false, railR: false, composer: false },
  Settings: { railL: false, railR: false, composer: false },
};
for (const name of SIX) {
  test(`${name} draws its shell at 1440 and 3840, night and day, without an error`, async () => {
    await expectFrame(b, d, `${name.toLowerCase()}.html`, { current: name, ...SHELL[name]! });
  }, 90_000);
}

test("the old pages' addresses lead, query kept, to the page that took their content", async () => {
  const s = await Splice.boot(mockHead({ key: "acme", command: "claude-acme", port: await freePort(), url: "http://127.0.0.1:9", model: "acme-large-2" }));
  try {
    const base = `http://127.0.0.1:${s.controlPort}`;
    const to = async (path: string) => {
      const res = await fetch(`${base}${path}`, { redirect: "manual" });
      return { status: res.status, at: res.headers.get("location") };
    };
    for (const [from, want] of [
      ["/requests.html?session=aaaa1111", "/activity.html?session=aaaa1111"],
      ["/usage.html?win=30d", "/activity.html?win=30d"],
      ["/models.html?cmd=claude-acme", "/providers.html?cmd=claude-acme"],
      ["/compare.html", "/providers.html?open=compare"],
    ] as const) {
      const r = await to(from);
      expect([301, 302, 303, 307, 308], from).toContain(r.status);
      expect(r.at, from).toBe(want);
    }
    const page = await fetch(`${base}/activity.html`);
    expect(page.status).toBe(200);
  } finally {
    await s.dispose();
  }
}, 1_800_000);
