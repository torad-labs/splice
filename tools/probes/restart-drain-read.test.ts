// What the drain reader reports for the route's own JSON shape, recorded in fixtures/. The restart is at 1000000040000.
import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { readDrain, report, type Fetcher } from "./restart-drain-read";

const recorded = JSON.parse(readFileSync(join(import.meta.dir, "fixtures/turns-around-a-restart.json"), "utf8")) as { heads: Record<string, unknown> };
const restart = 1_000_000_040_000;
const fetcher: Fetcher = async (path) => {
  if (path === "/api/heads") return { heads: Object.keys(recorded.heads).map((key) => ({ key })) };
  const head = new URL(`http://x${path}`).searchParams.get("head") ?? "";
  return recorded.heads[head];
};

test("restarted turns are split by when they arrived and whether the budget cut them, and empty retries are summed", async () => {
  const counts = await readDrain(fetcher, restart, 120_000, 300_000);
  expect(counts.claudex).toEqual({ before: 1, during: 2, budget: 1, emptyRetries: 3, partial: false });
  expect(counts["claude-splice"]).toEqual({ before: 0, during: 0, budget: 0, emptyRetries: 0, partial: true });
});

test("the report names counts and carries no row, id or body", async () => {
  const text = report(await readDrain(fetcher, restart, 120_000, 300_000), restart);
  expect(text).toContain("all heads: before the drain 1, during the drain 2, cut at the budget 1, empty_retries 3");
  expect(text).toContain("claude-splice: before the drain 0, during the drain 0, cut at the budget 0, empty_retries 0 (window held more than 2,000 rows");
  expect(text).not.toContain("error:restarted");
});
