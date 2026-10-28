// An open session on Sessions as he reads it at his width (Oct 27; Marcos, Oct 26: "scrolling on the chat
// gets crampped ... the tool call ui is very busy, it also seems like we're wasting a lot of horizontal space"). The
// session he opens takes the board's whole width under its own row, so no block stands beside it and no column runs
// empty beside it; in a plate with the room (150rem), each run of calls stands beside the words it followed, so the words
// read down one column; in a plate from 100rem the details stand in a rail beside the conversation and the calls stand
// under their words (Sessions' goal, Oct 28); and Earlier shows the newest few namesakes with the rest behind one count.
// Through the real Sessions page against a stub daemon, measured in the rendered DOM.
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { chromium, expect, type Browser, type Page } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const MIN = 60_000;
type Row = Record<string, unknown>;
const row = (id: string, name: string, p: string, ago: number, over: Row = {}): Row => ({
  session_id: id, name, head: "alpha", route: "head", availability: "live", status: "idle", cwd: `/work/${p}/${id}`, title: null,
  last: { role: "assistant", tool: null, text: `${name} said its last line.`, ts: now - ago * MIN },
  updated_at: new Date(now - ago * MIN).toISOString(),
  project: { id: `/work/${p}`, name: p, label: p, source: "folder", folder: "/work" }, ...over,
});
// five projects, so the board stands in as many columns as the width holds, with blocks before and after the one opened
const sessions: Row[] = [
  row("a-1", "anchor", "atlas", 1), row("a-2", "anvil", "atlas", 3), row("a-3", "apex", "atlas", 5),
  row("b-1", "beacon", "birch", 2), row("b-2", "bramble", "birch", 4),
  row("c-1", "cinder", "cobalt", 6), row("c-2", "comet", "cobalt", 7),
  row("d-1", "delta", "dune", 8),
  row("e-1", "ember", "elm", 9), row("e-2", "echo", "elm", 10),
  // one live session with six ended namesakes before it
  row("n-0", "lenovo", "elm", 11),
  ...Array.from({ length: 6 }, (_, i) => row(`n-${i + 1}`, "lenovo", "elm", 60 * (i + 2), { availability: "gone" })),
];
const call = (index: number, id: string, tool: string, input: Record<string, unknown>, ts: number) => [
  { index, role: "assistant", tool, tool_use_id: id, text: JSON.stringify(input), ts },
  { index: index + 1, role: "tool", tool_use_id: id, text: `${tool} printed its answer.`, ts: ts + 1_000 },
];
const transcript = {
  path: "/home/me/.claude/projects/birch/b-1.jsonl", earlier: null, moves: [], unmoved: [], unparseable_lines: 0,
  messages: [
    { index: 0, role: "user", text: "Read the parser and tell me what breaks.", ts: now - 9 * MIN },
    { index: 1, role: "assistant", text: "I will read the parser first.", ts: now - 8 * MIN },
    ...call(2, "t1", "Bash", { command: "ls src", description: "List the sources" }, now - 8 * MIN + 2_000),
    ...call(4, "t2", "Read", { file_path: "/work/birch/b-1/src/parse.ts" }, now - 8 * MIN + 4_000),
    ...call(6, "t3", "Bash", { command: "bun test parse", description: "Run the parser's tests" }, now - 8 * MIN + 6_000),
    { index: 8, role: "assistant", text: "The parser drops the last token when a line ends in a comment.", ts: now - 7 * MIN },
  ],
};
// three commands to continue on, each on one account with its own room left; the third offers thirty models
const models = [
  { head: "alpha", models: [{ id: "a-1", label: "Alpha One" }, { id: "a-2", label: "Alpha Two" }] },
  { head: "beta", models: [{ id: "b-1", label: "Beta One" }, { id: "b-2", label: "Beta Two" }, { id: "b-3", label: "Beta Three" }] },
  { head: "gamma", models: Array.from({ length: 30 }, (_, i) => ({ id: `g-${i + 1}`, label: `Gamma Model ${i + 1}` })) },
];
const later = Math.floor(now / 1000) + 3 * 3600;
const account = (head: string, name: string, five: number, week: number) => ({
  heads: [head], display_name: name, label: name, credential_present: true,
  five_hour_used_percent: five, five_hour_reset_epoch_seconds: later, seven_day_used_percent: week, seven_day_reset_epoch_seconds: later + 86_400,
});
const accounts = [account("alpha", "Alpha Account", 5, 12), account("beta", "Beta Account", 41, 10), account("gamma", "Gamma Account", 20, 3)];
const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/api/sessions") return Response.json({ reading: true, sessions });
    if (path === "/api/heads") return Response.json({ heads: [{ key: "alpha", label: "claude-alpha" }, { key: "beta", label: "claude-beta" }, { key: "gamma", label: "claude-gamma" }] });
    if (path === "/api/models") return Response.json({ heads: models });
    if (path === "/api/accounts") return Response.json({ accounts });
    if (path === "/api/keys") return Response.json({ keys: [] });
    if (/^\/api\/sessions\/[^/]+\/transcript$/.test(path)) return Response.json(transcript);
    if (path.startsWith("/api/")) return Response.json({});
    const file = join(consoleDir, path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : new Response("no", { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox", "--disable-background-networking"] });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

async function opened(width: number, height: number, scale: number, id: string): Promise<Page> {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
  await page.goto(`http://127.0.0.1:${server.port}/sessions.html#${id}`);
  await expect(page.locator("section.pane header .who .name")).toBeVisible({ timeout: 15_000 });
  await expect(page.locator(".pane .log .msg.agent").first()).toBeVisible({ timeout: 15_000 });
  return page;
}
type Box = { left: number; right: number; top: number; bottom: number };
const boxOf = (page: Page, sel: string) => page.locator(sel).first().evaluate((e) => {
  const r = e.getBoundingClientRect();
  return { left: r.left, right: r.right, top: r.top + scrollY, bottom: r.bottom + scrollY };
});

/** The open session's plate, its own block's header, and every other block, in page coordinates. */
async function board(page: Page) {
  const list = await boxOf(page, ".list");
  const plate = await boxOf(page, "section.pane");
  const head = await boxOf(page, ".pgroup:has(section.pane) > .phead");
  const blocks: (Box & { name: string })[] = await page.locator(".pgroup:not(:has(section.pane))").evaluateAll((els) => els.map((e) => {
    const r = e.getBoundingClientRect();
    return { name: e.querySelector(".pname")?.textContent ?? "", left: r.left, right: r.right, top: r.top + scrollY, bottom: r.bottom + scrollY };
  }));
  const beside = blocks.filter((b) => b.bottom > plate.top + 1 && b.top < plate.bottom - 1);
  return { list, plate, head, blocks, beside };
}

test("at his width the open session spans the board under its row, the space beside the first block is filled, and the rest continue below it", async () => {
  const page = await opened(2548, 1240, 1.5, "b-1");
  try {
    const { list, plate, head, blocks, beside } = await board(page);
    bunExpect(plate.left).toBeLessThanOrEqual(list.left + 2);
    bunExpect(plate.right).toBeGreaterThanOrEqual(list.right - 2);
    bunExpect(head.bottom).toBeLessThanOrEqual(plate.top + 1); // its own block's header stands over it: it opened in place
    bunExpect(beside.map((b) => b.name)).toEqual([]);
    // the plate takes both columns, so it waits for the taller of the blocks above it; the next block stands in the column the
    // first leaves free, not under the plate, and the rest continue below it
    bunExpect(blocks.filter((b) => b.top >= plate.bottom - 1).map((b) => b.name).sort()).toEqual(["dune", "elm"]);
    bunExpect(blocks.filter((b) => b.bottom <= plate.top + 1).map((b) => b.name).sort()).toEqual(["atlas", "cobalt"]);
  } finally {
    await page.close();
  }
}, 40_000);

test("at 3822 the open session takes every column but one, and the other blocks run down that one beside it", async () => {
  const page = await opened(3822, 1860, 1, "b-1");
  try {
    const { list, plate, head, beside } = await board(page);
    bunExpect(plate.right - plate.left).toBeGreaterThanOrEqual(0.6 * (list.right - list.left));
    bunExpect(head.bottom).toBeLessThanOrEqual(plate.top + 1);
    // what stands beside it is every other block, in the one column clear of it
    bunExpect(beside.map((b) => b.name).sort()).toEqual(["atlas", "cobalt", "dune", "elm"]);
    bunExpect(beside.filter((b) => b.right > plate.left + 1 && b.left < plate.right - 1).map((b) => b.name)).toEqual([]);
    bunExpect(new Set(beside.map((b) => Math.round(b.left))).size).toBe(1);
  } finally {
    await page.close();
  }
}, 40_000);

test("at his width each run of calls stands beside the words it followed", async () => {
  const page = await opened(2548, 1240, 1.5, "b-1");
  try {
    const words = await boxOf(page, '.pane .log .msg.agent:has-text("I will read the parser first.")');
    const run = await boxOf(page, ".pane .log .trail");
    bunExpect(run.left).toBeGreaterThanOrEqual(words.right - 1);
    bunExpect(run.top).toBeGreaterThanOrEqual(words.top - 2);
    bunExpect(run.top).toBeLessThan(words.bottom);
    // the words that came after the run read straight on in the same column, under the words before it
    const next = await boxOf(page, '.pane .log .msg.agent:has-text("drops the last token")');
    bunExpect(Math.abs(next.left - words.left)).toBeLessThanOrEqual(1);
    bunExpect(next.top).toBeGreaterThanOrEqual(words.bottom);
  } finally {
    await page.close();
  }
}, 40_000);

// A plate under 150rem keeps the words in one column with each run of calls under the words it followed; from 100rem the
// details stand in a rail beside that column, so the plate's width is used and no band of empty screen lies between them.
test("at 1440 the details stand in a rail beside the conversation, and each run of calls stands under the words it followed", async () => {
  const page = await opened(1440, 1240, 1, "b-1");
  try {
    const words = await boxOf(page, '.pane .log .msg.agent:has-text("I will read the parser first.")');
    const run = await boxOf(page, ".pane .log .trail");
    const next = await boxOf(page, '.pane .log .msg.agent:has-text("drops the last token")');
    const rail = await boxOf(page, ".pane .panetop");
    bunExpect(Math.abs(run.left - words.left)).toBeLessThanOrEqual(1);
    bunExpect(run.top).toBeGreaterThanOrEqual(words.bottom - 1);
    // the words after the run read straight on in the same column, under the run
    bunExpect(Math.abs(next.left - words.left)).toBeLessThanOrEqual(1);
    bunExpect(next.top).toBeGreaterThanOrEqual(run.bottom - 1);
    // the rail stands to the right of the whole conversation, level with its top
    bunExpect(rail.left).toBeGreaterThanOrEqual(Math.max(words.right, run.right, next.right) - 1);
    bunExpect(rail.top).toBeLessThan(words.top);
  } finally {
    await page.close();
  }
}, 40_000);

test("where the plate is narrow, a run of calls stands under the words it followed", async () => {
  const page = await opened(1000, 900, 1, "b-1");
  try {
    const words = await boxOf(page, '.pane .log .msg.agent:has-text("I will read the parser first.")');
    const run = await boxOf(page, ".pane .log .trail");
    bunExpect(run.top).toBeGreaterThanOrEqual(words.bottom - 1);
  } finally {
    await page.close();
  }
}, 40_000);

test("Continue on names each command once, with its account and room said once and each of its models a press under it", async () => {
  const page = await opened(2548, 1240, 1.5, "n-1");
  try {
    const title = await boxOf(page, "section.pane > .top");
    await page.getByRole("button", { name: "Continue on" }).click();
    const menu = page.locator("section.pane .menu.models");
    await expect(menu.getByRole("group", { name: "claude-alpha" }).getByRole("menuitem")).toHaveText(["Alpha One", "Alpha Two"]);
    await expect(menu.getByRole("group", { name: "claude-beta" }).getByRole("menuitem")).toHaveText(["Beta One", "Beta Two", "Beta Three"]);
    // the menu opens under the title line, which keeps its height
    const after = await boxOf(page, "section.pane > .top");
    bunExpect(Math.abs((after.bottom - after.top) - (title.bottom - title.top))).toBeLessThanOrEqual(1);
    bunExpect((await boxOf(page, "section.pane .menu.models")).top).toBeGreaterThanOrEqual(after.bottom - 1);
    // each command once, and its account and the share of its window used once, however many models it offers
    const text = await menu.innerText();
    const times = (w: string) => text.split(w).length - 1;
    bunExpect(["claude-beta", "Beta Account", "41%", "claude-alpha", "Alpha Account", "12%"].map(times)).toEqual([1, 1, 1, 1, 1, 1]);
  } finally {
    await page.close();
  }
}, 40_000);

test("a command with many models shows its first sixteen, and one count opens the rest in place with the menu still open", async () => {
  const page = await opened(2548, 1240, 1.5, "n-1");
  try {
    await page.getByRole("button", { name: "Continue on" }).click();
    const gamma = page.locator("section.pane .menu.models").getByRole("group", { name: "claude-gamma" });
    await expect(gamma.getByRole("menuitem")).toHaveCount(16);
    await gamma.getByRole("button", { name: /14 more/ }).click();
    await expect(gamma.getByRole("menuitem")).toHaveCount(30);
    await expect(gamma.getByRole("menuitem", { name: "Gamma Model 30" })).toBeVisible();
  } finally {
    await page.close();
  }
}, 40_000);

test("Earlier shows the newest three namesakes, and one count opens the rest in place", async () => {
  const page = await opened(2548, 1240, 1.5, "n-0");
  try {
    const earlier = page.locator(".pane .facts .fact", { hasText: "Earlier" });
    await expect(earlier.locator('a[href^="#n-"]')).toHaveCount(3);
    await earlier.getByRole("button", { name: /3 more/ }).click();
    await expect(earlier.locator('a[href^="#n-"]')).toHaveCount(6);
  } finally {
    await page.close();
  }
}, 40_000);
