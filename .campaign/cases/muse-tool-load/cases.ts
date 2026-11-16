// The cases of muse-tool-load: a tool Claude Code's ToolSearch loads in the middle of a Muse conversation costs the
// session only what it adds. Each conversation here is sent once, through a daemon built from this checkout with a
// Muse head on the stand-in for Meta (../muse-lib), and each case reads one thing off the requests Meta was sent and
// the answers Claude Code got: what a request read from Meta's cache, the tool list it carried, where it handed the
// loaded tool to the model and in which bytes, and the call that came back.

import { check, must, once, run } from "../muse-lib/cases.ts";
import { NEVER_LOADED, call, pasted, tool } from "../muse-lib/claude.ts";
import { Scene, Stage, called, eachReadsAll, handovers, listsAs, modelCall, person, readsAll, rides, samePair, where } from "../muse-lib/muse.ts";

const stage = once(() => Stage.open());

/** The tools Claude Code starts every conversation with. */
const BASE = [tool("Read"), tool("Bash"), tool("ToolSearch", ["query"])];
const SEND = tool("SendMessage", ["to", "message"]);
const STOP = tool("TaskStop", ["task_id"]);
const MONITOR = tool("Monitor", ["command"]);
const EXA = tool("mcp__exa__web_search_exa", ["query"]);

/** The words that make the model search for `names`. */
function search(...names: string[]): string {
  return call("ToolSearch", { query: `select:${names.join(",")}` });
}

/** A long file the person pasted, and the model's first answer: the history a load must not pay for again. */
function opening(): string {
  return `${pasted(8000)}\nSay OK.`;
}

const HI = { to: "ops", message: "hi" };

// ---------- one tool, loaded by one search ----------

// The load's result is the last block of its request, so Claude Code's cache mark sits on it there, and has moved on
// in every request after.
const one = once(() =>
  Scene.play<"first" | "second" | "search" | "load" | "call" | "result" | "later">(async (sc) => {
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    const searched = sc.set("search", await t.say(search(SEND.name)));
    sc.set("load", await t.load(searched, [SEND]));
    const made = sc.set("call", await t.say(call(SEND.name, HI)));
    sc.set("result", await t.answer(made, "sent"));
    sc.set("later", await t.say("Say OK once more."));
  }),
);

check("the request that carries a loaded tool reads everything the conversation had already sent from the cache", async () => {
  const sc = await one();
  readsAll(sc.at("search"), sc.at("load"), "the request that carried the load");
});

check("every request after the load reads everything the conversation had already sent from the cache", async () => {
  const sc = await one();
  eachReadsAll(sc.all("load", "call", "result", "later"), "after the load");
});

check("the request that carries a loaded tool sends the tool list of the conversation's first request, byte for byte", async () => {
  const sc = await one();
  listsAs(sc.at("first"), [sc.at("load")], "the request that carried the load");
});

check("every request after the load sends the tool list of the conversation's first request, byte for byte", async () => {
  const sc = await one();
  listsAs(sc.at("first"), sc.all("call", "result", "later"), "a request after the load");
});

check("a loaded tool rides in the history as Meta's client-executed tool-search pair", async () => {
  const sc = await one();
  rides(sc.at("load"), SEND.name, "the request that carried the load");
});

check("a loaded tool's pair sits in the history at the place the tool was loaded", async () => {
  const sc = await one();
  for (const name of ["load", "later"] as const) {
    const t = sc.at(name);
    const pair = rides(t, SEND.name, `the "${name}" request`);
    const searched = where(t.sent, modelCall("ToolSearch", SEND.name));
    must(searched >= 0, `the "${name}" request does not carry the model's ToolSearch call`);
    must(pair.at > searched, `the "${name}" request hands ${SEND.name} over at input[${pair.at}], before the search that loaded it (input[${searched}])`);
  }
  const later = sc.at("later");
  const next = where(later.sent, person(call(SEND.name, HI)));
  must(next >= 0, "the last request does not carry what the person said after the load");
  const pair = rides(later, SEND.name, "the last request");
  must(pair.at < next, `the last request hands ${SEND.name} over at input[${pair.at}], after what the person said next (input[${next}])`);
});

check("a loaded tool's pair is the same bytes on every later request, wherever Claude Code's cache mark sits", async () => {
  const sc = await one();
  samePair(sc.at("load"), sc.all("call", "result", "later"), SEND.name, "after the load");
});

check("a call of a loaded tool reaches Claude Code under the tool's own name with its arguments", async () => {
  const sc = await one();
  called(sc.at("call"), SEND.name, HI, "the request after the load");
});

check("a tool declared defer_loading that no result references stays out of every request", async () => {
  const sc = await one();
  sc.all("first", "second", "search", "load", "call", "result", "later").forEach((t, at) => {
    must(!t.sent.raw.includes(NEVER_LOADED), `request ${at + 1} of the conversation carries ${NEVER_LOADED}, which no result references`);
  });
});

// ---------- a reminder rides behind the load's result ----------

// Claude Code's cache mark sits on the reminder, never on the load's result, and the reminder asks for the tool at
// once: the request that carries the load is the one the model calls it on.
const reminded = once(() =>
  Scene.play<"first" | "second" | "search" | "load" | "result">(async (sc) => {
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    const searched = sc.set("search", await t.say(search(SEND.name)));
    const made = sc.set("load", await t.load(searched, [SEND], call(SEND.name, HI)));
    sc.set("result", await t.answer(made, "sent"));
  }),
);

check("the model calls a loaded tool on the very request that carries it", async () => {
  const sc = await reminded();
  called(sc.at("load"), SEND.name, HI, "the request that carried the load");
});

check("a load whose result has a reminder behind it reads everything the conversation had already sent from the cache", async () => {
  const sc = await reminded();
  eachReadsAll(sc.all("search", "load", "result"), "from the load on");
});

check("a loaded tool's pair is the same bytes on the next request when the cache mark sat behind the load's result", async () => {
  const sc = await reminded();
  samePair(sc.at("load"), [sc.at("result")], SEND.name, "after the load");
});

// ---------- several tools, loaded by one search ----------

const MONITORED = { command: "tail" };
const SEARCHED = { query: "muse" };
const STOPPED = { task_id: "7" };

const three = once(() =>
  Scene.play<"first" | "second" | "search" | "load" | "exa" | "stop" | "end">(async (sc) => {
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    const searched = sc.set("search", await t.say(search(STOP.name, MONITOR.name, EXA.name)));
    const monitor = sc.set("load", await t.load(searched, [STOP, MONITOR, EXA], call(MONITOR.name, MONITORED)));
    const exa = sc.set("exa", await t.answer(monitor, "watching", call(EXA.name, SEARCHED)));
    const stop = sc.set("stop", await t.answer(exa, "found", call(STOP.name, STOPPED)));
    sc.set("end", await t.answer(stop, "stopped"));
  }),
);

check("the request that carries three tools loaded by one search reads everything the conversation had already sent from the cache", async () => {
  const sc = await three();
  readsAll(sc.at("search"), sc.at("load"), "the request that carried the load of three tools");
});

check("every request after a search that loaded three tools reads everything the conversation had already sent from the cache", async () => {
  const sc = await three();
  eachReadsAll(sc.all("load", "exa", "stop", "end"), "after the load of three tools");
});

check("three tools loaded by one search leave the tool list of the conversation's first request as it was", async () => {
  const sc = await three();
  listsAs(sc.at("first"), sc.all("load", "exa", "stop", "end"), "a request from the load of three tools on");
});

check("each of three tools loaded by one search rides in the history as a client-executed tool-search pair", async () => {
  const sc = await three();
  const t = sc.at("load");
  const searched = where(t.sent, modelCall("ToolSearch", STOP.name));
  must(searched >= 0, "the request that carried the load does not carry the model's ToolSearch call");
  for (const loaded of [STOP, MONITOR, EXA]) {
    const pair = rides(t, loaded.name, "the request that carried the load of three tools");
    must(pair.at > searched, `${loaded.name} is handed over at input[${pair.at}], before the search that loaded it (input[${searched}])`);
  }
});

check("the pairs of three tools loaded by one search are the same bytes on every later request", async () => {
  const sc = await three();
  for (const loaded of [STOP, MONITOR, EXA]) samePair(sc.at("load"), sc.all("exa", "stop", "end"), loaded.name, "after the load of three tools");
});

check("each of three tools loaded by one search is the model's to call under its own name with its arguments", async () => {
  const sc = await three();
  called(sc.at("load"), MONITOR.name, MONITORED, "the request that carried the load of three tools");
  called(sc.at("exa"), EXA.name, SEARCHED, "the first request after the load of three tools");
  called(sc.at("stop"), STOP.name, STOPPED, "the second request after the load of three tools");
});

// ---------- a second load, later in the conversation ----------

const STILL = { to: "ops", message: "still here" };

const twice = once(() =>
  Scene.play<"first" | "second" | "search1" | "load1" | "between" | "search2" | "load2" | "stop" | "end">(async (sc) => {
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    const first = sc.set("search1", await t.say(search(SEND.name)));
    sc.set("load1", await t.load(first, [SEND]));
    sc.set("between", await t.say("Say OK between the two loads."));
    const second = sc.set("search2", await t.say(search(STOP.name)));
    const send = sc.set("load2", await t.load(second, [STOP], call(SEND.name, STILL)));
    const stop = sc.set("stop", await t.answer(send, "sent", call(STOP.name, STOPPED)));
    sc.set("end", await t.answer(stop, "stopped"));
  }),
);

check("the request that carries a second load reads everything the conversation had already sent from the cache", async () => {
  const sc = await twice();
  readsAll(sc.at("search2"), sc.at("load2"), "the request that carried the second load");
});

check("every request after a second load reads everything the conversation had already sent from the cache", async () => {
  const sc = await twice();
  eachReadsAll(sc.all("load2", "stop", "end"), "after the second load");
});

check("a second load leaves the tool list of the conversation's first request as it was", async () => {
  const sc = await twice();
  listsAs(sc.at("first"), sc.all("load2", "stop", "end"), "a request from the second load on");
});

check("the pair of the tool loaded first is the same bytes after a second load", async () => {
  const sc = await twice();
  samePair(sc.at("load1"), sc.all("between", "search2", "load2", "stop", "end"), SEND.name, "after the first load");
});

check("the tool a second load brings rides in the history at the place it was loaded, after the first", async () => {
  const sc = await twice();
  const t = sc.at("end");
  const firstPair = rides(t, SEND.name, "the last request");
  const secondPair = rides(t, STOP.name, "the last request");
  const searched = where(t.sent, modelCall("ToolSearch", STOP.name));
  const next = where(t.sent, person(call(STOP.name, STOPPED)));
  must(searched >= 0 && next >= 0, "the last request does not carry the second search or what came after its result");
  must(secondPair.at > firstPair.at, `${STOP.name} is handed over at input[${secondPair.at}], before ${SEND.name}, which was loaded first (input[${firstPair.at}])`);
  must(secondPair.at > searched, `${STOP.name} is handed over at input[${secondPair.at}], before the search that loaded it (input[${searched}])`);
  must(secondPair.at < next, `${STOP.name} is handed over at input[${secondPair.at}], after what came after its load (input[${next}])`);
});

check("after a second load the tool loaded first is still the model's to call under its own name", async () => {
  const sc = await twice();
  called(sc.at("load2"), SEND.name, STILL, "the request that carried the second load");
});

check("the tool a second load brings is the model's to call under its own name with its arguments", async () => {
  const sc = await twice();
  called(sc.at("stop"), STOP.name, STOPPED, "the request after the second load");
});

// ---------- a subagent's conversation ----------

const DONE = { to: "lead", message: "done" };

// The subagent belongs to a session whose main conversation is already under way, and has a conversation of its own.
const subagent = once(() =>
  Scene.play<"first" | "second" | "search" | "load" | "result" | "later">(async (sc) => {
    const main = (await stage()).talk(BASE);
    await main.say(opening());
    await main.say("Say OK again.");
    const t = (await stage()).talk(BASE, { session: main.c.session, agent: "agent-a41f07c2d9e85b36" });
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    const searched = sc.set("search", await t.say(search(SEND.name)));
    const made = sc.set("load", await t.load(searched, [SEND], call(SEND.name, DONE)));
    sc.set("result", await t.answer(made, "sent"));
    sc.set("later", await t.say("Say OK once more."));
  }),
);

check("in a subagent's conversation the request that carries a loaded tool reads everything the conversation had already sent from the cache", async () => {
  const sc = await subagent();
  readsAll(sc.at("search"), sc.at("load"), "the subagent's request that carried the load");
});

check("in a subagent's conversation every request after the load reads everything the conversation had already sent from the cache", async () => {
  const sc = await subagent();
  eachReadsAll(sc.all("load", "result", "later"), "of the subagent after the load");
});

check("in a subagent's conversation a load leaves the tool list of the conversation's first request as it was", async () => {
  const sc = await subagent();
  listsAs(sc.at("first"), sc.all("load", "result", "later"), "a request of the subagent from the load on");
});

check("in a subagent's conversation a loaded tool rides in the history as a client-executed tool-search pair", async () => {
  const sc = await subagent();
  const t = sc.at("load");
  const pair = rides(t, SEND.name, "the subagent's request that carried the load");
  const searched = where(t.sent, modelCall("ToolSearch", SEND.name));
  must(searched >= 0 && pair.at > searched, `the subagent's request hands ${SEND.name} over at input[${pair.at}], not after the search that loaded it (input[${searched}])`);
});

check("in a subagent's conversation a loaded tool's pair is the same bytes on every later request", async () => {
  const sc = await subagent();
  samePair(sc.at("load"), sc.all("result", "later"), SEND.name, "of the subagent after the load");
});

check("in a subagent's conversation a call of a loaded tool reaches Claude Code under the tool's own name with its arguments", async () => {
  const sc = await subagent();
  called(sc.at("load"), SEND.name, DONE, "the subagent's request that carried the load");
});

// ---------- the request that compacts the conversation ----------

const compacted = once(() =>
  Scene.play<"first" | "second" | "search" | "load" | "later" | "compact">(async (sc) => {
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    const searched = sc.set("search", await t.say(search(SEND.name)));
    sc.set("load", await t.load(searched, [SEND]));
    sc.set("later", await t.say("Say OK once more."));
    sc.set("compact", await t.compact());
  }),
);

check("the request that compacts a conversation with a loaded tool reads everything the conversation had already sent from the cache", async () => {
  const sc = await compacted();
  readsAll(sc.at("later"), sc.at("compact"), "the request that compacts the conversation");
});

check("the request that compacts a conversation with a loaded tool sends the tool list of the conversation's first request, byte for byte", async () => {
  const sc = await compacted();
  listsAs(sc.at("first"), [sc.at("compact")], "the request that compacts the conversation");
});

check("the request that compacts a conversation carries the loaded tool's pair in the bytes it was first sent in", async () => {
  const sc = await compacted();
  samePair(sc.at("load"), [sc.at("compact")], SEND.name, "(the one that compacts the conversation)");
});

// ---------- a tool still declared defer_loading, with no result that references it ----------

const NOTE = tool("mcp__notes__append_note", ["text"]);

// What Claude Code starts after a compaction: a new conversation that still declares a tool it once loaded, with no
// search result in it.
const unreferenced = once(() =>
  Scene.play<"first" | "second">(async (sc) => {
    const t = (await stage()).talk([...BASE, { ...NOTE, defer_loading: true }]);
    sc.set("first", await t.say(`${pasted(2000)}\nThis is the summary of an earlier conversation. Say OK.`));
    sc.set("second", await t.say("Say OK again."));
  }),
);

check("a new conversation that still declares a tool defer_loading, with no result that references it, keeps the tool out of its requests", async () => {
  const sc = await unreferenced();
  sc.all("first", "second").forEach((t, at) => {
    must(t.sent.status === 200, `Meta refused request ${at + 1} of the new conversation: ${t.sent.status} ${t.sent.refusal}`);
    must(!t.sent.listed.some((x) => x.name === NOTE.name), `request ${at + 1} of the new conversation lists ${NOTE.name}, which no result references`);
    must(handovers(t.sent, NOTE.name).length === 0, `request ${at + 1} of the new conversation hands ${NOTE.name} over, and no result references it`);
    must(!t.sent.raw.includes(NOTE.name), `request ${at + 1} of the new conversation carries ${NOTE.name}, which no result references`);
  });
});

// ---------- after a daemon restart ----------

const BACK = { to: "ops", message: "back" };

// Two conversations are under way when the daemon restarts: one has its loaded tool behind it, and the other has
// searched and not yet been answered, so the request that carries its load is the first the new daemon sees of it.
const restarted = once(() =>
  Scene.play<"first" | "load" | "later" | "call" | "result" | "first2" | "search2" | "load2" | "result2">(async (sc) => {
    const st = await stage();
    const a = st.talk(BASE);
    sc.set("first", await a.say(opening()));
    await a.say("Say OK again.");
    const searched = await a.say(search(SEND.name));
    sc.set("load", await a.load(searched, [SEND]));
    sc.set("later", await a.say("Say OK once more."));
    const b = st.talk(BASE);
    sc.set("first2", await b.say(opening()));
    await b.say("Say OK again.");
    const searched2 = sc.set("search2", await b.say(search(SEND.name)));
    await st.restart();
    const made = sc.set("call", await a.say(call(SEND.name, BACK)));
    sc.set("result", await a.answer(made, "sent"));
    const made2 = sc.set("load2", await b.load(searched2, [SEND], call(SEND.name, BACK)));
    sc.set("result2", await b.answer(made2, "sent"));
  }),
);

check("after a daemon restart the next request reads everything the conversation had already sent from the cache", async () => {
  const sc = await restarted();
  eachReadsAll(sc.all("later", "call", "result"), "after the restart");
});

check("after a daemon restart a conversation still sends the tool list of its first request, byte for byte", async () => {
  const sc = await restarted();
  listsAs(sc.at("first"), sc.all("call", "result"), "a request after the restart");
});

check("after a daemon restart a loaded tool's pair is the same bytes as before it", async () => {
  const sc = await restarted();
  samePair(sc.at("load"), sc.all("later", "call", "result"), SEND.name, "after the load, the restart between them,");
});

check("after a daemon restart a loaded tool is still the model's to call under its own name", async () => {
  const sc = await restarted();
  called(sc.at("call"), SEND.name, BACK, "the first request after the restart");
});

check("a load whose request is the first after a daemon restart reads everything the conversation had already sent from the cache", async () => {
  const sc = await restarted();
  eachReadsAll(sc.all("search2", "load2", "result2"), "from the load after the restart on");
});

check("a load whose request is the first after a daemon restart sends the tool list of the conversation's first request, byte for byte", async () => {
  const sc = await restarted();
  listsAs(sc.at("first2"), sc.all("load2", "result2"), "a request from the load after the restart on");
});

check("a tool loaded on the first request after a daemon restart rides in the history as a client-executed tool-search pair", async () => {
  const sc = await restarted();
  const t = sc.at("load2");
  const pair = rides(t, SEND.name, "the request that carried the load after the restart");
  const searched = where(t.sent, modelCall("ToolSearch", SEND.name));
  must(searched >= 0 && pair.at > searched, `${SEND.name} is handed over at input[${pair.at}], not after the search that loaded it (input[${searched}])`);
  samePair(t, [sc.at("result2")], SEND.name, "after the load after the restart");
});

check("a tool loaded on the first request after a daemon restart is the model's to call under its own name", async () => {
  const sc = await restarted();
  called(sc.at("load2"), SEND.name, BACK, "the request that carried the load after the restart");
});

await run(async () => {
  await (await stage()).close();
});
