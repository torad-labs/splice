// rv-connect: the connector that serves splice's own pages reads a session from splice's API with the console key this
// browser holds: the newest transcript page first, then what follows as it is written (soon after each turn starts and
// each turn ends, so a tool's result arrives while the next reply is still being written), and
// the live feed read as a stream (fetch, so the key goes in a header), each event handed on as {event, data} in order,
// pings dropped. It joins again when the stream drops, says when this browser is locked out, keeps reading history when
// a splice serves no feed, and reads older pages when asked.
import { afterAll, beforeAll, expect, test } from "bun:test";
import { expect as pw, type Browser, type Page } from "@playwright/test";
import { browser, KEY, open, SESSION, stub, turnEv, type Msg, type Opened, type Stub } from "./lib/rv.ts";

let b: Browser;
beforeAll(async () => {
  b = await browser();
}, 30_000);
afterAll(async () => {
  await b?.close();
});

interface Call {
  kind: "history" | "live";
  messages?: Msg[];
  events?: { event: string; data: Record<string, unknown> }[];
}
const calls = (page: Page) => page.evaluate(() => (window as unknown as { CALLS: Call[] }).CALLS);
const handed = async (page: Page) => (await calls(page)).filter((c) => c.kind === "history").flatMap((c) => c.messages!.map((m) => m.index));
const events = async (page: Page) => (await calls(page)).filter((c) => c.kind === "live").flatMap((c) => c.events!);
const mk = (n: number, from = 0): Msg[] => Array.from({ length: n }, (_, i) => ({ index: from + i, role: i % 2 ? "assistant" : "user", text: `made-up line ${from + i}` }) as Msg);

async function connected(st: Stub, session = SESSION, key = true): Promise<Opened> {
  const o = await open(b, st, `/probe/connect.html?session=${session}`, undefined, "night", { key });
  await o.page.waitForFunction(() => (window as unknown as { CONN_READY?: boolean }).CONN_READY === true, null, { timeout: 10_000 });
  return o;
}

test("it reads the newest page and the live feed with the console key, and hands on every event in order", async () => {
  const st = stub({ transcripts: { [SESSION]: mk(6) } });
  const o = await connected(st);
  try {
    await pw.poll(() => handed(o.page), { timeout: 5_000 }).toEqual([0, 1, 2, 3, 4, 5]);
    await pw.poll(() => st.followers(SESSION), { timeout: 5_000 }).toBe(1);
    const reads = st.seen.filter((s) => s.path.startsWith(`/api/sessions/${SESSION}/`));
    expect(reads.length).toBeGreaterThanOrEqual(2);
    for (const r of reads) expect(r.auth).toBe(`Bearer ${KEY}`);
    expect(reads.find((r) => r.path.endsWith("/transcript"))!.query).toContain("before=end");
    st.push(SESSION, "turn", turnEv("T1", "msg_1").data);
    st.ping(SESSION);
    st.push(SESSION, "text", { turn: "T1", index: 0, delta: "Hello " });
    st.push(SESSION, "text", { turn: "T1", index: 0, delta: "harbor." });
    await pw.poll(async () => (await events(o.page)).map((e) => e.event), { timeout: 5_000 }).toEqual(["hello", "turn", "text", "text"]);
    const got = await events(o.page);
    expect(got[0]!.data).toEqual({ session: SESSION, live: [] });
    expect(got[1]!.data.message).toBe("msg_1");
    expect(got.slice(2).map((e) => e.data.delta).join("")).toBe("Hello harbor.");
    expect(o.errors).toEqual([]);
    expect(await o.csp()).toEqual([]);
  } finally {
    await o.close();
    st.stop();
  }
}, 60_000);

test("soon after a turn ends, and soon after one starts, it reads what follows the last message it has", async () => {
  const st = stub({ transcripts: { [SESSION]: mk(4) } });
  const o = await connected(st);
  try {
    await pw.poll(() => handed(o.page), { timeout: 5_000 }).toEqual([0, 1, 2, 3]);
    await pw.poll(() => st.followers(SESSION), { timeout: 5_000 }).toBe(1);
    st.transcripts[SESSION]!.push(...mk(2, 4));
    const sent = Date.now();
    st.push(SESSION, "end", { turn: "T1", stop_reason: "end_turn", error: null });
    await pw.poll(async () => (await handed(o.page)).includes(5), { timeout: 2_000 }).toBe(true);
    expect(Date.now() - sent).toBeLessThan(2_000);
    expect(st.seen.some((s) => s.path.endsWith("/transcript") && (s.query.includes("cursor=after%3A3") || s.query.includes("cursor=after:3")))).toBe(true);
    // a turn starting means the tool results the last one waited on are written: it reads them then
    st.transcripts[SESSION]!.push(...mk(1, 6));
    const started = Date.now();
    st.push(SESSION, "turn", turnEv("T2", "msg_2").data);
    await pw.poll(async () => (await handed(o.page)).includes(6), { timeout: 2_000 }).toBe(true);
    expect(Date.now() - started).toBeLessThan(2_000);
  } finally {
    await o.close();
    st.stop();
  }
}, 60_000);

test("when the feed drops it joins again and hands on the new hello", async () => {
  const st = stub({ transcripts: { [SESSION]: mk(2) } });
  const o = await connected(st);
  try {
    await pw.poll(() => st.followers(SESSION), { timeout: 5_000 }).toBe(1);
    st.drop(SESSION);
    await pw.poll(() => st.seen.filter((s) => s.path.endsWith("/live")).length, { timeout: 8_000 }).toBe(2);
    await pw.poll(async () => (await events(o.page)).filter((e) => e.event === "hello").length, { timeout: 8_000 }).toBe(2);
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
    st.stop();
  }
}, 60_000);

test("a browser without a key splice takes is told so, and a splice with no feed still serves history", async () => {
  const locked = stub({ transcripts: { [SESSION]: mk(2) } });
  const o = await connected(locked, SESSION, false);
  try {
    await pw.poll(() => o.page.evaluate(() => (window as unknown as { LOCKED: number }).LOCKED), { timeout: 5_000 }).toBeGreaterThan(0);
    expect(o.errors).toEqual([]);
  } finally {
    await o.close();
    locked.stop();
  }
  const nofeed = stub({ transcripts: { [SESSION]: mk(3) } });
  nofeed.liveStatus[SESSION] = 404;
  const p = await connected(nofeed);
  try {
    await pw.poll(() => handed(p.page), { timeout: 5_000 }).toEqual([0, 1, 2]);
    await p.page.waitForTimeout(4_000);
    expect(nofeed.seen.filter((s) => s.path.endsWith("/live")).length).toBe(1);
    expect(await p.page.evaluate(() => (window as unknown as { LOCKED: number }).LOCKED)).toBe(0);
    expect(p.errors).toEqual([]);
  } finally {
    await p.close();
    nofeed.stop();
  }
}, 60_000);

test("it says whether older messages remain and reads the page before when asked", async () => {
  const st = stub({ transcripts: { [SESSION]: mk(150) } });
  const o = await connected(st);
  try {
    await pw.poll(async () => (await handed(o.page)).length, { timeout: 5_000 }).toBe(100);
    expect(Math.min(...(await handed(o.page)))).toBe(50);
    await pw.poll(() => o.page.evaluate(() => (window as unknown as { EARLIER: boolean[] }).EARLIER.at(-1)), { timeout: 5_000 }).toBe(true);
    await o.page.evaluate(() => (window as unknown as { CONN: { earlier: () => Promise<unknown> } }).CONN.earlier());
    await pw.poll(async () => new Set(await handed(o.page)).size, { timeout: 5_000 }).toBe(150);
    await pw.poll(() => o.page.evaluate(() => (window as unknown as { EARLIER: boolean[] }).EARLIER.at(-1)), { timeout: 5_000 }).toBe(false);
    // closed, it hands on nothing more
    await o.page.evaluate(() => (window as unknown as { CONN: { close: () => void } }).CONN.close());
    const before = (await events(o.page)).length;
    st.push(SESSION, "text", { turn: "T9", index: 0, delta: "after close" });
    await o.page.waitForTimeout(1_000);
    expect((await events(o.page)).length).toBe(before);
  } finally {
    await o.close();
    st.stop();
  }
}, 60_000);
