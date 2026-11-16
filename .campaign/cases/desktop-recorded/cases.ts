// desktop-recorded: a turn that came through the door is recorded as its head's turn, with the model that ran, and
// says which door it came through; other rows carry no door; and `splice desktop --json` names the last turn that
// came through the door.
//
// Two heads in the door. astra is the default model's head, and its haiku tier runs astra-small; borealis is the
// other. The cases run in the order they are written.

import { Splice, freePort } from "../../checks/server/lib/harness.ts";
import { Upstream } from "../../checks/server/lib/upstream.ts";
import { check, must, once, run, same, show } from "../desktop-lib/cases.ts";
import { type Desk, type Door, type PerfRow, answer, ask, askHead, desk, desktopOn, headRow, headRows, keyHead, launch } from "../desktop-lib/door.ts";

const ASTRA_LARGE = "claude-astra--astra-large";
const BOREALIS_SMALL = "claude-borealis--borealis-small";
const HAIKU = "anthropic.claude-haiku-5-5";

const astra = new Upstream("astra", "astra-large");
const borealis = new Upstream("borealis", "borealis-large");
let live: Splice | undefined;

const booted = once(async () => {
  for (const up of [astra, borealis]) up.start();
  live = await Splice.boot(
    keyHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, models: [{ id: "astra-large" }, { id: "astra-small" }], slots: { haiku: "astra-small" } }) +
      keyHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, models: [{ id: "borealis-large" }, { id: "borealis-small" }] }),
  );
  return live;
});

/** Desktop on, with astra-large the default model. */
const open = once(async (): Promise<{ s: Splice; d: Door }> => {
  const s = await booted();
  return { s, d: desktopOn(s, "--model", ASTRA_LARGE) };
});

/** One turn through the door that names `model`, in a session of its own, and the session's row on head `key`. */
async function through(model: string, key: string): Promise<{ session: string; sent: number; row: PerfRow }> {
  const { s, d } = await open();
  const session = crypto.randomUUID();
  const sent = Date.now();
  const t = await ask(d, { model, session });
  must(t.status === 200, `the turn naming ${model} was answered ${answer(t)}`);
  return { session, sent, row: await headRow(s, key, session) };
}

/** `last_turn`, once it names `session`. */
async function lastOf(session: string): Promise<NonNullable<Desk["last_turn"]>> {
  const { s } = await open();
  let last = desk(s).last_turn;
  for (const end = Date.now() + 10_000; last?.session_id !== session && Date.now() < end; ) {
    await Bun.sleep(300);
    last = desk(s).last_turn;
  }
  must(last !== null && last !== undefined, "last_turn is null after a turn came through the door");
  must(last.session_id === session, `last_turn names the session ${show(last.session_id)}, and the last turn through the door was of ${session}`);
  return last;
}

// ---------- before any turn ----------

check("before any turn has come through the door last_turn is null", async () => {
  const first = desk((await open()).s);
  must("last_turn" in first, `splice desktop --json gives no last_turn: ${show(first)}`);
  must(first.last_turn === null, `last_turn is ${show(first.last_turn)}`);
});

// ---------- the row ----------

/** A turn that names a selector of borealis. */
const picked = once(() => through(BOREALIS_SMALL, "borealis"));

check("a turn through the door is on /api/perf/turns as its head's row", async () => {
  const { s } = await open();
  const { session } = await picked();
  same(await headRows(s, "astra", session), [], "the session's rows on the head the turn did not run on");
});

check("the row of a turn through the door carries the model that ran", async () => {
  const { row } = await picked();
  must(String(row.model).includes("borealis-small"), `the row's model is ${show(row.model)}`);
});

check("the row of a turn through the door says door is desktop", async () => {
  const { row } = await picked();
  must(row.door === "desktop", `the row's door is ${show(row.door)}`);
});

/** A turn that names a tier, in a session that has named no selector: it runs astra's model for the tier. */
const tiered = once(() => through(HAIKU, "astra"));

check("the row of a turn that named a tier carries the model that ran", async () => {
  const { row } = await tiered();
  must(String(row.model).includes("astra-small"), `the row's model is ${show(row.model)}`);
  must(row.door === "desktop", `the row's door is ${show(row.door)}`);
});

check("the row of a turn that did not come through the door carries no door", async () => {
  const { s } = await open();
  const l = await launch(s, "claude-astra");
  const session = crypto.randomUUID();
  const t = await askHead(l, { model: "astra-large", session });
  must(t.status === 200, `the turn on the head's own route was answered ${answer(t)}`);
  const row = await headRow(s, "astra", session);
  must(row.door === undefined || row.door === null, `the row's door is ${show(row.door)}`);
});

// ---------- last_turn ----------

/** The newest turn through the door at this point: one that named a tier, and what `last_turn` says of it. */
const latest = once(async () => {
  const turned = await through(HAIKU, "astra");
  return { ...turned, last: await lastOf(turned.session) };
});

check("last_turn gives the session of the last turn through the door", async () => {
  await latest();
});

check("last_turn gives the head the turn ran on", async () => {
  const { last } = await latest();
  must(last.head === "astra", `last_turn's head is ${show(last.head)}`);
});

check("last_turn gives model_asked as the request named it", async () => {
  const { last } = await latest();
  must(last.model_asked === HAIKU, `last_turn's model_asked is ${show(last.model_asked)}, and the request named ${HAIKU}`);
});

check("last_turn gives the model that ran", async () => {
  const { last } = await latest();
  must(String(last.model).includes("astra-small"), `last_turn's model is ${show(last.model)}`);
});

check("last_turn gives when the turn came", async () => {
  const { last, sent } = await latest();
  const at = typeof last.at === "number" ? last.at : Date.parse(String(last.at));
  must(Number.isFinite(at), `last_turn's at is ${show(last.at)}`);
  must(at >= sent - 5000 && at <= Date.now() + 5000, `last_turn's at is ${show(last.at)}, and the turn was sent at ${new Date(sent).toISOString()}`);
});

check("last_turn follows the newest turn through the door", async () => {
  const { session } = await through(BOREALIS_SMALL, "borealis");
  const last = await lastOf(session);
  must(last.head === "borealis", `last_turn's head is ${show(last.head)}`);
  must(last.model_asked === BOREALIS_SMALL, `last_turn's model_asked is ${show(last.model_asked)}`);
});

check("a turn on a head's own route is not the last turn through the door", async () => {
  const { s } = await open();
  const { session } = await through(BOREALIS_SMALL, "borealis");
  await lastOf(session);
  const l = await launch(s, "claude-astra");
  const own = crypto.randomUUID();
  const t = await askHead(l, { model: "astra-large", session: own });
  must(t.status === 200, `the turn on the head's own route was answered ${answer(t)}`);
  await headRow(s, "astra", own);
  must(desk(s).last_turn?.session_id === session, `last_turn names the session ${show(desk(s).last_turn?.session_id)}, and the last turn through the door was of ${session}`);
});

await run(async () => {
  await live?.dispose();
  for (const up of [astra, borealis]) up.stop();
});
