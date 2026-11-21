// Activity (Night Deck), where the old Requests page's content now lives: the latest requests in the page's outcome words,
// a removed command's requests listed under its own name, the doors other pages open the list with asked of the daemon
// (?why=queued, Waited in line; ?order=price, Highest price), a request's glass with what it was worth on a plan, how long
// it waited in line and how many requests its command was running when it arrived, and its full request read only once
// opened; a model in the address narrows every figure (rewritten from usage-model-filter); "/" searches what was sent and returned. Through the real page against a stub daemon with made-up rows. (Rewritten from requests-row-words,
// requests-order, requests-door-exact, waited-in-line, requests-inflight, requests-plan-value, request-document and
// removed-command, which pinned the old Requests page; money, requests-bysession-track, requests-compare-group,
// requests-cut, requests-group-doors, requests-login-names and unsent-figures pinned its own drawing and went with it.)
import { afterAll, beforeAll, expect as bunExpect, test } from "bun:test";
import { expect, type Browser, type Page } from "@playwright/test";
import { launch, stub, type Stub } from "./deck-stub.ts";

const NOW = Date.now(), MIN = 60_000;
const row = (turn: string, agoMin: number, over: Record<string, unknown> = {}) => ({
  turn, turn_id: turn, ts: NOW - agoMin * MIN, outcome: "ok", model: "made-up-1", session: "s1aaaaaa", total: 3_000, first_byte: 900,
  arrival_to_upstream_write_ms: 30, in_tokens: 10_000, cached_tokens: 8_000, out_tokens: 400, cost_usd: 0.02, retries: 0, ...over,
});
const ROWS: Record<string, unknown[]> = {
  acme: [
    row("t-plan", 2, { cost_usd: null, plan_value_usd: 1.25, cost_reason: "plan", account_label: "Harbor work", admit_held_ms: 900, inflight: 21 }),
    row("t-429", 5, { outcome: "error:rate-limited", out_tokens: 0, first_byte: null }),
  ],
  gone: [row("t-gone", 9, { model: "made-up-old" })],
};
const outline = { kind: "turn", ts: NOW, client: { headers: { "x-made-up": "1" }, body_deferred: true }, answer: { status: 200, body_deferred: true } };

let s: Stub;
let browser: Browser;
beforeAll(async () => {
  s = stub({
    "/api/heads": { heads: [{ key: "acme", label: "claude-acme", gate: { inflight: 2 } }] },
    "/api/heads/retired": { retired: [{ key: "gone", command: "claude-gone" }] },
    "/api/status": { registry: [{ key: "acme", label: "claude-acme", family: "anthropic" }] },
    "/api/models": { heads: [{ head: "acme", models: [{ id: "made-up-1", label: "Made Up 1" }] }] },
    "/api/sessions": { sessions: [{ session_id: "s1aaaaaa-0000", name: "chart-writer", head: "acme", availability: "live", project: { name: "harbor" } }] },
    "/api/perf/turns": (url: URL) => {
      const head = url.searchParams.get("head") ?? "";
      const rows = (ROWS[head] ?? []) as { ts: number }[];
      return { heads: [{ key: head, label: head, count: rows.length, returned: rows.length, truncated: false, rows }] };
    },
    "/api/heads/acme/trace": { records: [outline] },
    "/api/heads/acme/trace/document": (url: URL) => (url.searchParams.get("section") === "answer"
      ? { type: "object", children: [{ key: "output", type: "string", value: "made-up answer", complete: true }], next: null }
      : { type: "object", children: [{ key: "prompt", type: "string", value: "made-up prompt", complete: true }], next: null }),
    "/api/heads/acme/capture": { enabled: true, retention_days: 7 },
    "/api/heads/acme/trace/search": (url: URL) => ({ hits: url.searchParams.get("q") === "tide table"
      ? [{ turn: "t-old", ts: NOW - 3 * 86_400_000, model: "made-up-1", outcome: "ok", where: "sent", text: "read the tide table first", total: 2_000 }] : [] }),
  });
  browser = await launch();
}, 30_000);
afterAll(async () => {
  await browser?.close();
  s?.stop();
});

async function at(address = ""): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto(`${s.url}/activity.html${address}`);
  await expect(page.locator(".rw").first()).toBeVisible({ timeout: 10_000 });
  return page;
}
const asked = (path: string) => s.calls.filter((c) => c.path === path).map((c) => c.query);
const glass = (page: Page) => page.locator(".lens:not([hidden])");

test("the latest requests read in the page's words, a removed command's under its own name, and nothing live is asked of it", async () => {
  const page = await at();
  try {
    await expect(page.locator(".rw")).toHaveCount(3);
    await expect(page.locator(".rw.is-bad")).toContainText("Rate limited");
    await page.locator(".rw", { hasText: "made-up-old" }).click();
    await expect(glass(page)).toContainText("claude-gone · removed");
    bunExpect(s.calls.filter((c) => /^\/api\/heads\/gone\/(turns\/live|capture|trace)/.test(c.path))).toEqual([]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a request's glass says what it was worth on a plan, how long it waited in line and what its command was running", async () => {
  const page = await at("#request=t-plan");
  try {
    await expect(glass(page)).toContainText("API value · on your plan");
    await expect(glass(page)).toContainText("$1.25");
    await expect(glass(page).locator(".lx-kv div", { hasText: "Waited in line" })).toContainText("900 ms");
    await expect(glass(page).locator(".lx-kv div", { hasText: "Running when it arrived" })).toContainText("21");
    await expect(glass(page)).toContainText("Harbor work");
  } finally {
    await page.close();
  }
}, 30_000);

test("the full request is read only once opened, and each body only once pressed", async () => {
  const page = await at("#request=t-plan");
  try {
    bunExpect(asked("/api/heads/acme/trace")).toHaveLength(0);
    await glass(page).getByRole("button", { name: "Full request" }).click();
    await expect(page.locator(".lens.xwide:not([hidden])")).toBeVisible();
    await expect.poll(() => asked("/api/heads/acme/trace").map((q) => q.get("view"))).toEqual(["outline"]);
    bunExpect(asked("/api/heads/acme/trace/document")).toHaveLength(0);
    await glass(page).getByRole("radio", { name: "Returned" }).click();
    await glass(page).getByText("Response", { exact: true }).click();
    await expect(glass(page)).toContainText("made-up answer");
    bunExpect(asked("/api/heads/acme/trace/document").map((q) => q.get("section"))).toEqual(["answer"]);
  } finally {
    await page.close();
  }
}, 30_000);

test("a door asks the daemon for what it names, and Highest price asks for each command's dearest", async () => {
  s.calls.length = 0;
  let page = await at("?why=queued");
  try {
    await expect(page.locator(".ac-filter", { hasText: "Waited in line" })).toBeVisible();
    await expect.poll(() => asked("/api/perf/turns").some((q) => q.get("usage") === "0" && q.get("queued_ms") === "0")).toBe(true);
  } finally {
    await page.close();
  }
  s.calls.length = 0;
  page = await at();
  try {
    await page.getByRole("radio", { name: "Highest price" }).click();
    await expect.poll(() => asked("/api/perf/turns").some((q) => q.get("order") === "price")).toBe(true);
    bunExpect(new URL(page.url()).searchParams.get("order")).toBe("price");
  } finally {
    await page.close();
  }
}, 40_000);

test("/ searches what was sent and returned, and lists a request the newest rows do not hold", async () => {
  const page = await at();
  try {
    await page.keyboard.press("/");
    await page.locator(".find input").fill("tide table");
    await page.locator(".find-list button", { hasText: "Sent or returned" }).click();
    await expect(page.locator(".rw")).toHaveCount(1);
    await expect(page.locator(".rw")).toContainText("read the tide table first");
    bunExpect(new URL(page.url()).searchParams.get("q")).toBe("tide table");
  } finally {
    await page.close();
  }
}, 30_000);

test("a model in the address narrows every figure and list: the daemon is asked for that model's requests alone", async () => {
  s.calls.length = 0;
  const page = await at("?model=made-up-1");
  try {
    await expect(page.locator(".ac-filter")).toContainText("Made Up 1");
    await expect.poll(() => asked("/api/perf/turns").filter((q) => q.get("n") === "1").map((q) => q.get("model"))).toContain("made-up-1");
    bunExpect(asked("/api/perf/turns").filter((q) => q.get("n") === "1").every((q) => q.get("model") === "made-up-1")).toBe(true);
  } finally {
    await page.close();
  }
}, 30_000);
