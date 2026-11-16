// The cases of muse-tool-change: a tool a server adds to a Muse conversation, or takes away from it, leaves the
// conversation's cache standing. Claude Code tells the change in a system message (`tool_addition`, `tool_removal`).
// Each conversation here is sent once, through a daemon built from this checkout with a Muse head on the stand-in for
// Meta (../muse-lib), and each case reads one thing off the requests Meta was sent and the answers Claude Code got:
// what a request read from Meta's cache, the tool list it carried, where it handed an added tool to the model, the
// words that told the model a tool went, and the call that came back.

import { check, must, once, run, show } from "../muse-lib/cases.ts";
import { call, pasted, tool } from "../muse-lib/claude.ts";
import { Scene, Stage, called, eachReadsAll, handovers, listsAs, person, readsAll, rides, samePair, tells, where, type Turn } from "../muse-lib/muse.ts";

const stage = once(() => Stage.open());

/** The tools Claude Code starts every conversation with. */
const BASE = [tool("Read"), tool("Bash"), tool("ToolSearch", ["query"])];
const DOCS = tool("mcp__docs__create_doc", ["title"]);
const EDIT = tool("mcp__docs__update_doc", ["id", "text"]);
const DESK = tool("mcp__desk__run_process", ["command"]);
/** A name longer than the 64 characters Meta takes for a tool. */
const LONG = tool("mcp__claude_ai_Shared_Team_Calendar__find_the_free_hours_of_everyone_for_one_week", ["person"]);

/** A long file the person pasted, and the model's first answer: the history a change must not pay for again. */
function opening(): string {
  return `${pasted(8000)}\nSay OK.`;
}

/** Fails unless the request of `t` hands `name` over after everything the request of `before` had sent, and before
 *  the person's words `next`, which came after the change. */
function handedOverBetween(before: Turn, t: Turn, name: string, next: string, what: string): void {
  const pair = rides(t, name, what);
  const said = where(t.sent, person(next));
  must(said >= 0, `${what} does not carry what the person said after the change`);
  must(pair.at >= before.sent.items.length, `${what} hands ${name} over at input[${pair.at}], inside what the conversation had already sent (${before.sent.items.length} items)`);
  must(pair.at < said, `${what} hands ${name} over at input[${pair.at}], after what the person said next (input[${said}])`);
}

/** Fails unless the request of `t` tells the model, in the words of a message, that `name` went. */
function toldOf(t: Turn, name: string, what: string): number[] {
  must(t.sent.status === 200, `Meta refused ${what}: ${t.sent.status} ${t.sent.refusal}`);
  const told = tells(t.sent, name);
  must(told.length > 0, `${what} names ${name} in no message of its input: the model is not told which tool went`);
  return told;
}

/** Fails unless the words of `t` that name `name` sit after everything the request of `before` had sent, and no
 *  later than the person's words `next`, which came after the removal. */
function toldBetween(before: Turn, t: Turn, name: string, next: string, what: string): void {
  const told = toldOf(t, name, what);
  const said = where(t.sent, person(next));
  must(said >= 0, `${what} does not carry what the person said after the removal`);
  const placed = told.filter((at) => at >= before.sent.items.length && at <= said);
  must(placed.length > 0, `${what} names ${name} at input[${told.join(", ")}], not between what the conversation had already sent (${before.sent.items.length} items) and what the person said next (input[${said}])`);
}

// ---------- a server connects with two tools ----------

const NOTES = { title: "Notes" };
const MORE = { id: "1", text: "more" };

const added = once(() =>
  Scene.play<"first" | "second" | "add" | "edit" | "end" | "later">(async (sc) => {
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    const made = sc.set("add", await t.add([DOCS, EDIT], call(DOCS.name, NOTES)));
    const edited = sc.set("edit", await t.answer(made, "created", call(EDIT.name, MORE)));
    sc.set("end", await t.answer(edited, "updated"));
    sc.set("later", await t.say("Say OK once more."));
  }),
);

check("the request that carries a tool_addition reads everything the conversation had already sent from the cache", async () => {
  const sc = await added();
  readsAll(sc.at("second"), sc.at("add"), "the request that carried the addition");
});

check("every request after a tool_addition reads everything the conversation had already sent from the cache", async () => {
  const sc = await added();
  eachReadsAll(sc.all("add", "edit", "end", "later"), "after the addition");
});

check("the request that carries a tool_addition sends the tool list of the conversation's first request unchanged", async () => {
  const sc = await added();
  listsAs(sc.at("first"), [sc.at("add")], "the request that carried the addition");
});

check("every request after a tool_addition sends the tool list of the conversation's first request unchanged", async () => {
  const sc = await added();
  listsAs(sc.at("first"), sc.all("edit", "end", "later"), "a request after the addition");
});

check("each added tool rides in the history as Meta's client-executed tool-search pair", async () => {
  const sc = await added();
  for (const t of [DOCS, EDIT]) rides(sc.at("add"), t.name, "the request that carried the addition");
});

check("an added tool's pair sits in the history at the place the tool was added", async () => {
  const sc = await added();
  for (const t of [DOCS, EDIT]) {
    handedOverBetween(sc.at("second"), sc.at("add"), t.name, call(DOCS.name, NOTES), "the request that carried the addition");
    handedOverBetween(sc.at("second"), sc.at("later"), t.name, call(DOCS.name, NOTES), "the last request");
  }
});

check("a call of an added tool reaches Claude Code under the tool's own name with its arguments", async () => {
  const sc = await added();
  called(sc.at("add"), DOCS.name, NOTES, "the request that carried the addition");
});

check("an added tool is still the model's to call on a later request", async () => {
  const sc = await added();
  called(sc.at("edit"), EDIT.name, MORE, "the request after the addition");
});

// ---------- a server connects with a tool whose name is past Meta's 64 characters ----------

const ANA = { person: "ana" };
const BO = { person: "bo" };

const long = once(() =>
  Scene.play<"first" | "second" | "add" | "end" | "again">(async (sc) => {
    must(LONG.name.length > 64, `the long name is ${LONG.name.length} characters`);
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    const made = sc.set("add", await t.add([LONG], call(LONG.name, ANA)));
    sc.set("end", await t.answer(made, "found"));
    sc.set("again", await t.say(call(LONG.name, BO)));
  }),
);

check("the request that adds a tool whose name is past 64 characters reads everything the conversation had already sent from the cache", async () => {
  const sc = await long();
  readsAll(sc.at("second"), sc.at("add"), "the request that carried the addition");
});

check("every request after the addition of a tool whose name is past 64 characters reads everything the conversation had already sent from the cache", async () => {
  const sc = await long();
  eachReadsAll(sc.all("add", "end", "again"), "after the addition");
});

check("an added tool whose name is past 64 characters rides in the history at the place it was added, as a client-executed tool-search pair", async () => {
  const sc = await long();
  handedOverBetween(sc.at("second"), sc.at("add"), LONG.name, call(LONG.name, ANA), "the request that carried the addition");
});

check("a call of an added tool whose name is past 64 characters reaches Claude Code under the tool's own name with its arguments", async () => {
  const sc = await long();
  called(sc.at("add"), LONG.name, ANA, "the request that carried the addition");
});

check("an added tool whose name is past 64 characters is still called under its own name on a later request", async () => {
  const sc = await long();
  called(sc.at("again"), LONG.name, BO, "a later request");
});

// ---------- a server leaves with a tool the conversation started with ----------

const AFTER_REMOVAL = "Say OK, now that the server has left.";
const LS = { x: "ls" };

const started = once(() =>
  Scene.play<"first" | "second" | "remove" | "bash" | "end">(async (sc) => {
    const t = (await stage()).talk([...BASE, DESK]);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    sc.set("remove", await t.remove([DESK.name], AFTER_REMOVAL));
    const made = sc.set("bash", await t.say(call("Bash", LS)));
    sc.set("end", await t.answer(made, "a.txt"));
  }),
);

check("the request that carries a tool_removal reads everything the conversation had already sent from the cache", async () => {
  const sc = await started();
  readsAll(sc.at("second"), sc.at("remove"), "the request that carried the removal");
});

check("every request after a tool_removal reads everything the conversation had already sent from the cache", async () => {
  const sc = await started();
  eachReadsAll(sc.all("remove", "bash", "end"), "after the removal");
});

check("taking away a tool the conversation started with leaves the tool list of the conversation's first request unchanged", async () => {
  const sc = await started();
  listsAs(sc.at("first"), sc.all("remove", "bash", "end"), "a request from the removal on");
});

check("the request that carries a tool_removal tells the model in words which tool went", async () => {
  const sc = await started();
  toldOf(sc.at("remove"), DESK.name, "the request that carried the removal");
});

check("the words that say which tool went sit at the place the removal happened", async () => {
  const sc = await started();
  toldBetween(sc.at("second"), sc.at("remove"), DESK.name, AFTER_REMOVAL, "the request that carried the removal");
  toldBetween(sc.at("second"), sc.at("end"), DESK.name, AFTER_REMOVAL, "the last request");
});

check("after a tool_removal the tools that stayed are still the model's to call", async () => {
  const sc = await started();
  called(sc.at("bash"), "Bash", LS, "the request after the removal");
});

// ---------- a server leaves with a tool it had added ----------

const AFTER_SECOND_REMOVAL = "Say OK, now that one of the added tools is gone.";

const addedThenRemoved = once(() =>
  Scene.play<"first" | "second" | "add" | "between" | "remove" | "edit" | "end">(async (sc) => {
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    sc.set("add", await t.add([DOCS, EDIT], "Say OK, now that a server has connected."));
    sc.set("between", await t.say("Say OK between the two changes."));
    sc.set("remove", await t.remove([DOCS.name], AFTER_SECOND_REMOVAL));
    const made = sc.set("edit", await t.say(call(EDIT.name, MORE)));
    sc.set("end", await t.answer(made, "updated"));
  }),
);

check("the request that takes away an added tool reads everything the conversation had already sent from the cache", async () => {
  const sc = await addedThenRemoved();
  readsAll(sc.at("between"), sc.at("remove"), "the request that carried the removal");
});

check("every request after an added tool is taken away reads everything the conversation had already sent from the cache", async () => {
  const sc = await addedThenRemoved();
  eachReadsAll(sc.all("remove", "edit", "end"), "after the removal");
});

check("taking away an added tool leaves the tool list of the conversation's first request unchanged", async () => {
  const sc = await addedThenRemoved();
  listsAs(sc.at("first"), sc.all("remove", "edit", "end"), "a request from the removal on");
});

check("taking away an added tool leaves the pair that handed it over in the history as it was", async () => {
  const sc = await addedThenRemoved();
  samePair(sc.at("add"), sc.all("between", "remove", "edit", "end"), DOCS.name, "after the addition");
});

check("the request that takes away an added tool tells the model in words, at the place it happened, which tool went", async () => {
  const sc = await addedThenRemoved();
  toldBetween(sc.at("between"), sc.at("remove"), DOCS.name, AFTER_SECOND_REMOVAL, "the request that carried the removal");
});

check("after an added tool is taken away the added tool that stayed is still the model's to call", async () => {
  const sc = await addedThenRemoved();
  called(sc.at("edit"), EDIT.name, MORE, "the request after the removal");
});

// ---------- a tool comes back with a new schema ----------

const FILED = { title: "Notes", folder: "team" };

const cameBack = once(() =>
  Scene.play<"first" | "second" | "add" | "remove" | "back" | "end">(async (sc) => {
    const t = (await stage()).talk(BASE);
    sc.set("first", await t.say(opening()));
    sc.set("second", await t.say("Say OK again."));
    sc.set("add", await t.add([DOCS], "Say OK, now that a server has connected."));
    sc.set("remove", await t.remove([DOCS.name], "Say OK, now that the server has left."));
    const made = sc.set("back", await t.add([tool(DOCS.name, ["title", "folder"])], call(DOCS.name, FILED)));
    sc.set("end", await t.answer(made, "created"));
  }),
);

check("the request that brings a tool back with a new schema reads everything the conversation had already sent from the cache", async () => {
  const sc = await cameBack();
  readsAll(sc.at("remove"), sc.at("back"), "the request that brought the tool back");
});

check("the request after a tool came back with a new schema reads everything the conversation had already sent from the cache", async () => {
  const sc = await cameBack();
  readsAll(sc.at("back"), sc.at("end"), "the request after the tool came back");
});

check("a tool coming back with a new schema leaves the tool list of the conversation's first request unchanged", async () => {
  const sc = await cameBack();
  listsAs(sc.at("first"), sc.all("back", "end"), "a request from the tool's return on");
});

check("a tool that comes back with a new schema is handed over again at the place it came back", async () => {
  const sc = await cameBack();
  const back = sc.at("back");
  handedOverBetween(sc.at("remove"), back, DOCS.name, call(DOCS.name, FILED), "the request that brought the tool back");
  const all = handovers(back.sent, DOCS.name);
  must(all.length >= 2, `the request that brought the tool back hands ${DOCS.name} over ${all.length} time, not where it was added and again where it came back`);
  const newest = Object.keys(rides(back, DOCS.name, "the request that brought the tool back").tool.parameters?.properties ?? {}).sort();
  must(Bun.deepEquals(newest, ["folder", "title"]), `where it came back, ${DOCS.name} is handed over taking ${show(newest)}, not the new schema's folder and title`);
});

check("a tool that comes back with a new schema is called with the new schema", async () => {
  const sc = await cameBack();
  called(sc.at("back"), DOCS.name, FILED, "the request that brought the tool back");
});

await run(async () => {
  await (await stage()).close();
});
