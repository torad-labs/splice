// The Sharing page when splice.toml does not load (round 17 of PR #419's review). The daemon answers GET /api/share with
// its refusal then, 200 with ok false and the lines that say what is wrong with the file, as every Sharing row answers
// one. The goal: the page says those lines, and shows no switch or field that would act on a file the daemon cannot
// read, and once the file loads again it shows sharing as before; it never stops on a script error, whatever order the
// two come in. The file breaks and mends at any moment, so the property runs over a generated run of the daemon's
// answers, one each 5-second read, through the real page against a stub daemon, on the page's own clock.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { chromium, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const board = {
  machine: "torad-server",
  sharing: { enabled: true, port: 3097, listening: true, following: true, fingerprint: "sha256:aa", commands: [] },
  joined: [],
  remotes: [],
};
/** Lines the daemon may say about a file it cannot read, some with what a page must not take as markup. */
const LINES = [
  "splice.toml line 3: expected '=' after a key",
  "share.port: port 70000 is outside 1..65535; use a port in that range",
  "heads.<one>.provider: 'x' & \"y\" are not providers",
  "remotes.studio: the fingerprint must be sha256:<64 hex>",
];
type Answer = { refused: string[] } | "board";
let answer: Answer = "board";
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/share") {
      if (answer === "board") return Response.json(board);
      return Response.json({ ok: false, restart: false, said: answer.refused.map((text) => ({ text, bad: true })) });
    }
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path === "/" ? "sharing.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
  },
});

/** A fixed draw, so a failing run is the same run next time. */
let seed = 17;
const draw = (n: number) => { seed = (seed * 1_103_515_245 + 12_345) % 2_147_483_648; return seed % n; };
const drawn = (): Answer => {
  if (draw(2) === 0) return "board";
  const some = LINES.filter(() => draw(2) === 0);
  const one = draw(LINES.length);
  return { refused: some.concat(LINES.slice(one, one + 1)) };
};

let browser: Browser;
let page: Page;
const errors: string[] = [];
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.clock.install();
}, 40_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

/** What the page shows now: the text of its board, and whether the Sharing switch is on it. */
async function shown(): Promise<{ text: string; switch: boolean }> {
  return page.evaluate(() => ({
    text: document.getElementById("board")?.textContent ?? "",
    switch: document.querySelector('[data-act="share"]') != null,
  }));
}

/** Waits, up to a deadline, until the page shows what [answer] says, and answers what it shows then. */
async function showing(expected: Answer): Promise<{ text: string; switch: boolean }> {
  const until = Date.now() + 10_000;
  let now = await shown();
  const says = (s: { text: string; switch: boolean }) =>
    expected === "board" ? s.switch : !s.switch && expected.refused.every((l) => s.text.includes(l));
  while (!says(now) && Date.now() < until) { await Bun.sleep(20); now = await shown(); }
  return now;
}

test("whatever order the file breaks and mends in, the page says the daemon's lines or shows sharing, and never stops", async () => {
  const broken = { refused: LINES.filter((_, i) => i === 0 || i === 2) };
  answer = broken;
  await page.goto(`http://127.0.0.1:${server.port}/sharing.html`);
  const first = await showing(broken);
  expect(first.switch).toBe(false);
  for (const line of broken.refused) expect(first.text).toContain(line);

  for (let step = 0; step < 12; step++) {
    answer = drawn();
    await page.clock.runFor(5_000);
    const now = await showing(answer);
    if (answer === "board") {
      expect({ step, switch: now.switch }).toEqual({ step, switch: true });
      for (const line of LINES) expect(now.text).not.toContain(line);
    } else {
      expect({ step, switch: now.switch }).toEqual({ step, switch: false });
      for (const line of answer.refused) expect(now.text).toContain(line);
    }
  }
  expect(errors).toEqual([]);
}, 120_000);
