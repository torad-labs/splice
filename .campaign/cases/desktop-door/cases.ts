// desktop-door: splice answers the Code tab of Claude Desktop under one address, /desktop, in Claude Code's Mantle
// dialect, and the model a request names picks the head.
//
// Three heads, in this order in splice.toml. astra and borealis are called with an API key splice holds, so they are
// in the door; corvus forwards the caller's own Claude login, so it is not.
//   astra     pinned astra-large;    its opus tier runs astra-small
//   borealis  pinned borealis-large; its opus tier runs borealis-mid, its haiku tier borealis-small, and it names no
//             model for sonnet
// Desktop is turned on with borealis-large as the default model, so a session that has named no selector is on
// borealis, which is not the first head.

import { Splice, type Turn, freePort, until } from "../../checks/server/lib/harness.ts";
import { Upstream } from "../../checks/server/lib/upstream.ts";
import { check, must, once, run, same, show } from "../desktop-lib/cases.ts";
import { answer, ask, askHead, clientHead, count, desktopOn, doorOf, keyHead, launch, listed, refused, runs, work } from "../desktop-lib/door.ts";

const ASTRA_LARGE = "claude-astra--astra-large";
const ASTRA_SMALL = "claude-astra--astra-small";
const BOREALIS_LARGE = "claude-borealis--borealis-large";
const BOREALIS_MID = "claude-borealis--borealis-mid";

const astra = new Upstream("astra", "astra-large");
const borealis = new Upstream("borealis", "borealis-large");
const corvus = new Upstream("corvus", "claude-opus-5-5");
let live: Splice | undefined;

const booted = once(async () => {
  for (const up of [astra, borealis, corvus]) up.start();
  live = await Splice.boot(
    keyHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, models: [{ id: "astra-large" }, { id: "astra-small" }], slots: { opus: "astra-small" } }) +
      keyHead({
        key: "borealis",
        command: "claude-borealis",
        port: await freePort(),
        url: borealis.url,
        models: [{ id: "borealis-large" }, { id: "borealis-mid" }, { id: "borealis-small" }],
        slots: { opus: "borealis-mid", haiku: "borealis-small" },
      }) +
      clientHead({ key: "corvus", command: "claude-corvus", port: await freePort(), url: corvus.url }),
  );
  return live;
});

/** Desktop on, with borealis-large the default model. */
const door = once(async () => desktopOn(await booted(), "--model", BOREALIS_LARGE));

// ---------- the routes ----------

check("the door's address is under /desktop", async () => {
  const d = await door();
  must(new URL(d.url).pathname === "/desktop", `the door the file names is ${d.url}`);
});

check("POST /desktop/v1/messages is answered", async () => {
  const d = await door();
  const t = await ask(d, { model: BOREALIS_LARGE, session: crypto.randomUUID(), stream: false, beta: false });
  must(t.status === 200, `it was answered ${answer(t)}`);
  must(t.reply === borealis.reply(), `the answer's text is "${t.reply}", not the provider's "${borealis.reply()}"`);
});

check("POST /desktop/v1/messages?beta=true is answered", async () => {
  const d = await door();
  const t = await ask(d, { model: BOREALIS_LARGE, session: crypto.randomUUID(), stream: false, beta: true });
  must(t.status === 200, `it was answered ${answer(t)}`);
  must(t.reply === borealis.reply(), `the answer's text is "${t.reply}", not the provider's "${borealis.reply()}"`);
});

check("a streamed turn through the door is answered as Anthropic's stream", async () => {
  const d = await door();
  const t = await ask(d, { model: BOREALIS_LARGE, session: crypto.randomUUID(), stream: true });
  must(t.status === 200, `it was answered ${answer(t)}`);
  const events = t.events.map((e) => e.event);
  must(events.includes("message_start") && events.includes("message_stop"), `the stream's events are ${show(events)}`);
  must(t.reply === borealis.reply(), `the stream's text is "${t.reply}", not the provider's "${borealis.reply()}"`);
});

check("POST /desktop/v1/messages/count_tokens is answered", async () => {
  const d = await door();
  const c = await count(d, { model: BOREALIS_LARGE, beta: false });
  must(c.status === 200, `it was answered ${c.status} ${c.body.slice(0, 200)}`);
  must(c.input_tokens > 0, `it counted ${c.input_tokens} input tokens`);
});

check("POST /desktop/v1/messages/count_tokens?beta=true is answered", async () => {
  const d = await door();
  const c = await count(d, { model: BOREALIS_LARGE, beta: true });
  must(c.status === 200, `it was answered ${c.status} ${c.body.slice(0, 200)}`);
  must(c.input_tokens > 0, `it counted ${c.input_tokens} input tokens`);
});

// ---------- a selector ----------

check("a request that names a selector runs that model on its head", async () => {
  await runs(await door(), astra, ASTRA_SMALL, "astra-small");
});

check("a selector of another head runs that model on that head", async () => {
  await runs(await door(), borealis, BOREALIS_MID, "borealis-mid");
});

check("every ID a head's own /v1/models lists is a selector the door runs on that head", async () => {
  const d = await door();
  const ids = (await listed(await launch(await booted(), "claude-astra"))).map((m) => m.id);
  same([...ids].sort(), [ASTRA_LARGE, ASTRA_SMALL], "what astra's own /v1/models lists");
  for (const id of ids) await runs(d, astra, id, id.replace("claude-astra--", ""));
});

// ---------- the head a session is on ----------

check("a session that has named no selector is on the head of the default model", async () => {
  await runs(await door(), borealis, "anthropic.claude-haiku-5-5", "borealis-small");
});

check("a session is on the head of the last selector it named", async () => {
  const d = await door();
  const session = crypto.randomUUID();
  await runs(d, astra, ASTRA_LARGE, "astra-large", session);
  await runs(d, astra, "anthropic.claude-opus-5-5", "astra-small", session);
  await runs(d, borealis, BOREALIS_LARGE, "borealis-large", session);
  await runs(d, borealis, "anthropic.claude-opus-5-5", "borealis-mid", session);
  await runs(d, astra, ASTRA_SMALL, "astra-small", session);
  await runs(d, astra, "anthropic.claude-opus-5-5", "astra-small", session);
});

// ---------- Anthropic's own IDs are a tier ----------

/** A case for one spelling of a tier: a session on borealis that asks for it runs `model`. */
function tier(name: string, asked: string, model: string): void {
  check(name, async () => runs(await door(), borealis, asked, model));
}

tier("the Mantle spelling of Opus runs the model the head names for its opus tier", "anthropic.claude-opus-5-5", "borealis-mid");
tier("the Mantle spelling of Haiku runs the model the head names for its haiku tier", "anthropic.claude-haiku-5-5", "borealis-small");
tier("an ID with the region prefix us. runs its tier's model", "us.anthropic.claude-opus-5-5", "borealis-mid");
tier("an ID with the region prefix global. runs its tier's model", "global.anthropic.claude-haiku-5-5", "borealis-small");
tier("an ID with a version suffix runs its tier's model", "anthropic.claude-opus-5-5-v1:0", "borealis-mid");
tier("an ID with a region prefix and a version suffix runs its tier's model", "us.anthropic.claude-haiku-5-5-v1:0", "borealis-small");
tier("the bare ID of Opus runs the opus tier's model", "claude-opus-5-5", "borealis-mid");
tier("the bare ID of Haiku runs the haiku tier's model", "claude-haiku-5-5", "borealis-small");
tier("the alias opus runs the opus tier's model", "opus", "borealis-mid");
tier("the alias haiku runs the haiku tier's model", "haiku", "borealis-small");
tier("a tier the head names no model for runs the head's pinned model", "anthropic.claude-sonnet-5-5", "borealis-large");
tier("the alias of a tier the head names no model for runs the head's pinned model", "sonnet", "borealis-large");

// ---------- any other model ----------

/** A turn that names `model`, and what every provider was sent because of it. */
async function turned(model: string): Promise<{ t: Turn; sent: number }> {
  const d = await door();
  const before = work(astra) + work(borealis) + work(corvus);
  const t = await ask(d, { model, session: crypto.randomUUID() });
  return { t, sent: work(astra) + work(borealis) + work(corvus) - before };
}

const unknown = once(() => turned("gpt-nobody-serves"));

check("a model the door does not serve is refused 400 invalid_request_error", async () => {
  refused((await unknown()).t, 400, "invalid_request_error");
});

check("the refusal of a model the door does not serve names splice desktop", async () => {
  const { t } = await unknown();
  must(t.status === 400, `it was answered ${answer(t)}, not 400`);
  must((t.error?.message ?? "").includes("splice desktop"), `the refusal says ${show(t.error?.message)}`);
});

check("a refused model sends nothing upstream", async () => {
  const { t, sent } = await unknown();
  must(t.status === 400, `it was answered ${answer(t)}, not 400`);
  must(sent === 0, `the providers were sent ${sent} requests`);
});

check("a selector of a head that is not in the door is refused with nothing sent upstream", async () => {
  const { t, sent } = await turned("claude-corvus--claude-opus-5-5");
  refused(t, 400, "invalid_request_error");
  must(sent === 0, `the providers were sent ${sent} requests`);
});

check("a selector of no head is refused with nothing sent upstream", async () => {
  const { t, sent } = await turned("claude-nobody--astra-large");
  refused(t, 400, "invalid_request_error");
  must(sent === 0, `the providers were sent ${sent} requests`);
});

check("a model its head does not list is refused with nothing sent upstream", async () => {
  const { t, sent } = await turned("claude-astra--no-such-model");
  refused(t, 400, "invalid_request_error");
  must(sent === 0, `the providers were sent ${sent} requests`);
});

// ---------- the head's own failures ----------

check("a failure of the head's provider is answered through the door as the head's own route answers it", async () => {
  const d = await door();
  const l = await launch(await booted(), "claude-astra");
  astra.behaviour.status = 400;
  try {
    const own = await askHead(l, { model: "astra-large", session: crypto.randomUUID() });
    must(own.status !== 200 && own.error !== null, `the head's own route answered the provider's refusal ${answer(own)}`);
    const before = astra.completions().length;
    const t = await ask(d, { model: ASTRA_LARGE, session: crypto.randomUUID() });
    must(astra.completions().length > before, `the provider was not asked, and the door answered ${answer(t)}`);
    same({ status: t.status, error: t.error }, { status: own.status, error: own.error }, "the door's answer to the provider's refusal");
  } finally {
    astra.behaviour.status = null;
  }
});

// ---------- a restart ----------

check("a restart does not move the door", async () => {
  const d = await door();
  const s = await booted();
  await s.restart();
  let after = "";
  await until("the file to name a door after the restart", () => {
    try {
      after = doorOf(s).url;
      return true;
    } catch {
      return false;
    }
  });
  must(after === d.url, `the file names ${after} after the restart, and ${d.url} before`);
  await runs(d, astra, ASTRA_SMALL, "astra-small");
});

await run(async () => {
  await live?.dispose();
  for (const up of [astra, borealis, corvus]) up.stop();
});
