// A request whose source round splice cut (code mode) carries no tokens; the next request of its session carries them.
// The Requests page says so on both sides, through the real page against a stub daemon. A relation between rows is a mark
// in the row (Requests' goal, Oct 28), its words its spoken name and under the pointer: a door from the cut request to the
// one that took its tokens ("Counted on the next request"), the way back on that one ("Counts 1 earlier request"), and
// for a finished request with no tokens and no carrier in the list a dash whose words under the pointer are "No usage
// reported".
import { afterAll, beforeAll, test } from "bun:test";
import { chromium, expect, type Browser } from "@playwright/test";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../gate/src/lib/repo.ts";

const consoleDir = join(layout().repoRoot, "app/src/main/resources/console");
const now = Date.now();
const row = (turn: string, agoS: number, extra: Record<string, unknown>) => ({
  turn, turn_id: turn, ts: now - agoS * 1000, outcome: "ok", model: "gpt-6.1-sol", session: "aaaaaaaa", session_id: "aaaaaaaa-1", total: 9000,
  attempts: 1, retries: 0, account: "primary", cost_usd: null, cost_reason: "plan", ...extra,
});
const tokens = { in_tokens: 169600, out_tokens: 14, cached_tokens: 169216 };
const rows = [
  row("carrier", 20, { ...tokens, cut_source_rounds: 1 }),
  row("cutrow", 30, {}),
  row("plain", 60, { ...tokens }),
  row("orphan", 90, { session: "bbbbbbbb", session_id: "bbbbbbbb-2" }),
  // an older cut request whose tokens never came back: it must not change how the later group reads
  row("staleCut", 200, { session: "cccccccc", session_id: "cccccccc-3" }),
  row("staleTok", 170, { ...tokens, session: "cccccccc", session_id: "cccccccc-3" }),
  // two cut requests, then two carriers holding one each: which carrier took which cannot be known
  row("pairA", 140, { session: "cccccccc", session_id: "cccccccc-3" }),
  row("pairB", 130, { session: "cccccccc", session_id: "cccccccc-3" }),
  row("pairC", 120, { ...tokens, cut_source_rounds: 1, session: "cccccccc", session_id: "cccccccc-3" }),
  row("pairD", 110, { ...tokens, cut_source_rounds: 1, session: "cccccccc", session_id: "cccccccc-3" }),
];

const answers: Record<string, unknown> = {
  "/api/heads": { heads: [{ key: "codex", label: "codex" }] },
  "/api/models": { heads: [] },
  "/api/sessions": { sessions: [] },
  "/api/keys": { keys: [] },
  "/api/topology": { topology: { providers: {} } },
  "/api/heads/codex/capture": { enabled: false, retention_days: 7 },
  "/api/config": {},
  "/api/perf/turns": { heads: [{ key: "codex", label: "codex", count: rows.length, returned: rows.length, truncated: false, rows, usage: { models: [], accounts: [], sessions: [] } }] },
};

const server = Bun.serve({
  port: 0,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path in answers) return Response.json(answers[path]);
    const file = join(consoleDir, path === "/" ? "requests.html" : path.slice(1));
    return existsSync(file) && file.startsWith(consoleDir) ? new Response(Bun.file(file)) : Response.json({}, { status: 404 });
  },
});

let browser: Browser;
beforeAll(async () => {
  browser = await chromium.launch({
    executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
    args: ["--no-sandbox", "--disable-background-networking"],
  });
}, 20_000);
afterAll(async () => {
  await browser.close();
  server.stop(true);
});

test("a cut request names the one that took its tokens, that one names it back, and an unlinked one says no usage", async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`http://127.0.0.1:${server.port}/requests.html`);
    const card = (turn: string) => page.locator(`article[data-open="codex:${turn}"]`);
    // a relation mark by its words, and the dash a request with no counts draws in its tokens' place
    const counted = (turn: string) => card(turn).locator('.mk[title="Counted on the next request"]');
    const counts = (turn: string) => card(turn).locator('.mk[title="Counts 1 earlier request"]');
    const noUsage = (turn: string) => card(turn).locator('.tk .nr[title="No usage reported"]');
    await expect(card("cutrow")).toBeVisible({ timeout: 20_000 });

    // the cut request: a door to the carrier, named by its words
    await expect(counted("cutrow")).toHaveCount(1);
    await expect(card("cutrow").getByRole("button", { name: "Counted on the next request" })).toHaveCount(1);
    expect(await card("cutrow").locator("button.door").getAttribute("data-open")).toBe("codex:carrier");
    // the carrier: the way back, and its own tokens still drawn, as its figure in the Tokens column whose header says In
    // once (Requests' goal, Oct 26: "What a column holds is said once, over it"; the rows lost "tokens in")
    await expect(counts("carrier")).toHaveCount(1);
    await expect(card("carrier").getByRole("button", { name: "Counts 1 earlier request" })).toHaveCount(1);
    expect(await card("carrier").locator("button.door").getAttribute("data-open")).toBe("codex:cutrow");
    await expect(card("carrier").locator(".tk .v").first()).toHaveText("170K");
    await expect(page.locator(".rqhead .tk")).toContainText("In");
    // a finished request with no tokens and no carrier in the list is a dash saying so, with no mark and no door
    await expect(noUsage("orphan")).toHaveCount(1);
    await expect(card("orphan").locator(".mk")).toHaveCount(0);
    expect(await card("orphan").locator("button.door").count()).toBe(0);
    // a request with its own tokens and no cut draws neither mark nor dash
    await expect(card("plain").locator(".mk")).toHaveCount(0);
    await expect(card("plain").locator(".tk .nr")).toHaveCount(0);

    // two cut requests and two carriers: both say they were counted later, none is pinned to a carrier by a guess
    for (const t of ["pairA", "pairB"]) {
      await expect(counted(t)).toHaveCount(1);
      expect(await card(t).locator("button.door").count()).toBe(0);
    }
    for (const t of ["pairC", "pairD"]) {
      await expect(counts(t)).toHaveCount(1);
      expect(await card(t).locator("button.door").count()).toBe(0);
    }

    await expect(noUsage("staleCut")).toHaveCount(1);
    await expect(card("staleCut").locator(".mk")).toHaveCount(0);
    expect(await card("staleCut").locator("button.door").count()).toBe(0);

    // the door selects the carrier
    await card("cutrow").locator("button.door").click();
    await expect(card("carrier")).toHaveAttribute("aria-current", "true");
  } finally {
    await page.close();
  }
}, 60_000);  // drives the page through several rows; bun's 5 s default turned it red on a loaded machine (Oct 11)
