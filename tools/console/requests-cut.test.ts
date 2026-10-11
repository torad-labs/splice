// A request whose source round splice cut (code mode) carries no tokens; the next request of its session carries them.
// The Requests page says so on both sides, through the real page against a stub daemon: a door from the cut request to the
// one that took its tokens ("Counted on the next request"), the way back on that one ("with 1 earlier request"), and a
// plain "No usage reported" for a finished request with no tokens and no carrier in the list.
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
    await expect(card("cutrow")).toBeVisible({ timeout: 20_000 });

    // the cut request: a door to the carrier
    await expect(card("cutrow")).toContainText("Counted on the next request");
    expect(await card("cutrow").locator("button.door").getAttribute("data-open")).toBe("codex:carrier");
    // the carrier: the way back, and its own tokens still drawn
    await expect(card("carrier")).toContainText("with 1 earlier request");
    expect(await card("carrier").locator("button.door").getAttribute("data-open")).toBe("codex:cutrow");
    await expect(card("carrier")).toContainText("tokens in");
    // a finished request with no tokens and no carrier in the list says so plainly, with no door
    await expect(card("orphan")).toContainText("No usage reported");
    expect(await card("orphan").locator("button.door").count()).toBe(0);
    // a request with its own tokens and no cut says neither
    await expect(card("plain")).not.toContainText("earlier request");
    await expect(card("plain")).not.toContainText("Counted on");
    await expect(card("plain")).not.toContainText("No usage reported");

    // two cut requests and two carriers: both say they were counted later, none is pinned to a carrier by a guess
    for (const t of ["pairA", "pairB"]) {
      await expect(card(t)).toContainText("Counted on the next request");
      expect(await card(t).locator("button.door").count()).toBe(0);
    }
    for (const t of ["pairC", "pairD"]) {
      await expect(card(t)).toContainText("with 1 earlier request");
      expect(await card(t).locator("button.door").count()).toBe(0);
    }

    // the door selects the carrier
    await card("cutrow").locator("button.door").click();
    await expect(card("carrier")).toHaveAttribute("aria-current", "true");
  } finally {
    await page.close();
  }
});
