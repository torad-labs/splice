// nd-kit: the Night Deck kit is one source in the console. A page made of nothing but its own field (fixtures/probe.html)
// and the kit's files gets the room, the six-tab nav, the Night/Day switch saved where the console saves it, the Barlow and
// DM Mono faces from the console's own fonts, Splice's providers and models read live, and the 1440-to-3840 scale.
// The kit's no-raw-values check runs from the repo's tooling and refuses a raw length, colour or radius, or a kit
// token a page redefines.
import { afterAll, beforeAll, test } from "bun:test";
import { expect, type Browser } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { browser, CONSOLE, daemon, expectFrame, FIXTURES, NARROW, open, ROOT, WIDE, world, type Daemon } from "./lib/deck.ts";

let b: Browser;
let d: Daemon;
beforeAll(async () => {
  b = await browser();
  d = daemon(world());
}, 30_000);
afterAll(async () => {
  await b?.close();
  d?.stop();
});

test("the kit's files are in the console, each once", () => {
  for (const f of ["deck.css", "deck.js", "deck-lens.js", "deck-composer.js", "deck-common.js"]) expect(existsSync(join(CONSOLE, f)), f).toBe(true);
  for (const f of ["barlow-300.woff2", "barlow-400.woff2", "barlow-500.woff2", "dm-mono-400.woff2", "dm-mono-500.woff2"]) {
    expect(existsSync(join(CONSOLE, "fonts", f)), f).toBe(true);
  }
});

test("a page of only its own field gets the shell: six tabs, no rails or composer it did not ask for, one size at 1440 and 3840", async () => {
  await expectFrame(b, d, "probe/probe.html", { current: null, railL: false, railR: false, composer: false });
}, 60_000);

// Nov 21, 2026: this asked for 30 px at 3840, the root that followed the window's width and drew a 3840 px screen at
// twice the size. The root is the same on every window now, so the browser's zoom is what scales the page.
test("the root size is 15 px at 1440 and at 3840", async () => {
  for (const [size, px] of [[NARROW, 15], [WIDE, 15]] as const) {
    const o = await open(b, d, "probe/probe.html", size);
    try {
      expect(await o.page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize))).toBeCloseTo(px, 0);
    } finally {
      await o.close();
    }
  }
}, 30_000);

test("the text faces are the console's own Barlow and DM Mono", async () => {
  const o = await open(b, d, "probe/probe.html");
  try {
    const loaded = await o.page.evaluate(async () => {
      await document.fonts.ready;
      return [...document.fonts].filter((f) => f.status === "loaded").map((f) => f.family.replace(/["']/g, ""));
    });
    expect(loaded).toContain("Barlow");
    expect(loaded).toContain("DM Mono");
  } finally {
    await o.close();
  }
}, 30_000);

test("Day on the switch holds across a reload", async () => {
  const o = await open(b, d, "probe/probe.html");
  try {
    await o.page.locator('.switch [data-theme-set="day"]').click();
    await expect.poll(() => o.page.evaluate(() => document.documentElement.dataset.theme)).toBe("day");
    await o.page.reload({ waitUntil: "load" });
    expect(await o.page.evaluate(() => document.documentElement.dataset.theme)).toBe("day");
  } finally {
    await o.close();
  }
}, 30_000);

test("Splice's providers and models are read live, never from a snapshot", async () => {
  const o = await open(b, d, "probe/probe.html");
  try {
    const read = await o.page.evaluate(async () => {
      const S = (window as unknown as { SPLICE: { ready: Promise<unknown>; modelName(id: string): string; providerOf(c: string): unknown } }).SPLICE;
      await S.ready;
      return { name: S.modelName("acme-large-2"), acme: !!S.providerOf("claude-acme") };
    });
    expect(read).toEqual({ name: "Acme Large 2", acme: true });
  } finally {
    await o.close();
  }
}, 30_000);

const raw = (...files: string[]) => spawnSync("bun", ["tools/console/deck-raw.ts", ...files], { cwd: ROOT, encoding: "utf8" });

test("the no-raw-values check refuses raw values and a redefined kit token, and passes tokens", () => {
  const bad = raw(join(FIXTURES, "raw-bad.css"), join(FIXTURES, "raw-bad.js"));
  expect(bad.status).toBe(1);
  const said = `${bad.stdout}${bad.stderr}`;
  expect(said).toContain("12px");
  expect(said).toContain("#ff0000");
  expect(said).toContain("--ground");
  expect(said).toContain("raw-bad.js");
  expect(said).not.toContain("--probe-own");
  const good = raw(join(FIXTURES, "raw-good.css"), join(FIXTURES, "raw-good.js"));
  expect(good.status, `${good.stdout}${good.stderr}`).toBe(0);
});

test("every page's own CSS and JS in the console passes the no-raw-values check", () => {
  const all = raw();
  expect(all.status, `${all.stdout}${all.stderr}`).toBe(0);
});
