// desktop-terminal: with Desktop on, the terminal is as it was. The file `splice desktop on` writes is the one the
// terminal's `claude` reads, and a shell inside a Code tab session carries its env, so a launch clears Claude Code's
// provider switches, the `claude` command's own launch cancels the file's switch in the settings it hands Claude
// Code, and splice writes one file in the `claude` command's folder.
//
// The first daemon has two heads: astra, with a config folder of its own, and the `claude` head, which runs over the
// `claude` command's own folder as the wrapped `claude` does and carries its settings on the command line. The
// second has astra alone, for what splice writes in the `claude` command's folder.

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ROOT, Splice, freePort } from "../../checks/server/lib/harness.ts";
import { Upstream } from "../../checks/server/lib/upstream.ts";
import { check, must, once, run, same, show } from "../desktop-lib/cases.ts";
import {
  type Door,
  type Recipe,
  WRITTEN,
  answer,
  ask,
  askHead,
  clientHead,
  desktop,
  desktopOn,
  fileEnv,
  filesUnder,
  headRow,
  headUrl,
  keyHead,
  launch,
  overlay,
  recipe,
  rows,
  rowsOf,
  settings,
  settingsText,
  writeSettings,
} from "../desktop-lib/door.ts";

/** Claude Code's provider switches and what goes with them: any of these reaching a session sends it past its head. */
const SWITCHES = [
  "CLAUDE_CODE_USE_BEDROCK",
  "CLAUDE_CODE_USE_MANTLE",
  "CLAUDE_CODE_USE_VERTEX",
  "CLAUDE_CODE_USE_FOUNDRY",
  "ANTHROPIC_BEDROCK_BASE_URL",
  "ANTHROPIC_BEDROCK_MANTLE_BASE_URL",
  "AWS_BEARER_TOKEN_BEDROCK",
];

const astra = new Upstream("astra", "astra-large");
const anthropic = new Upstream("anthropic", "claude-opus-5-5");
const second = new Upstream("astra", "astra-large");
const extra = new Upstream("borealis", "borealis-large");
let live: Splice | undefined;
let alone: Splice | undefined;

const booted = once(async () => {
  for (const up of [astra, anthropic]) up.start();
  live = await Splice.boot(
    keyHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, models: [{ id: "astra-large" }, { id: "astra-small" }] }) +
      clientHead({ key: "claude", command: "claude", port: await freePort(), url: anthropic.url }),
  );
  return live;
});

// ---------- a launch's recipe ----------

/** The recipe of a head's launch, before Desktop is turned on: a machine may be set up for Bedrock without it. */
const plain = once(async () => recipe(await booted(), "claude-astra"));

for (const name of SWITCHES) {
  check(`a launch's recipe unsets ${name}`, async () => {
    const r = await plain();
    must(r.unset.includes(name), `the recipe unsets ${show(r.unset)}`);
    must(!(name in r.env), `the recipe sets ${name} to ${show(r.env[name])}`);
  });
}

/** The `claude` command's own launch before Desktop is turned on: the values that are the launch's own. */
const claudeBefore = once(async () => recipe(await booted(), "claude"));

check("the recipe of the claude command's own launch unsets them too", async () => {
  const r = await claudeBefore();
  const kept = SWITCHES.filter((name) => !r.unset.includes(name) || name in r.env);
  must(kept.length === 0, `it does not unset ${kept.join(", ")}`);
});

// ---------- a head started from inside a Code tab session ----------

const open = once(async (): Promise<{ s: Splice; d: Door }> => {
  const s = await booted();
  return { s, d: desktopOn(s) };
});

/** astra launched from a shell that carries the file's env, as a shell inside a Code tab session does. */
const inside = once(async () => {
  const { s, d } = await open();
  const carried = fileEnv(s);
  must(carried.CLAUDE_CODE_USE_MANTLE === "1", `the file's env carries no switch: ${show(carried)}`);
  return { s, d, l: await launch(s, "claude-astra", carried) };
});

check("a head started from inside a Code tab session is left none of the file's switch", async () => {
  const { l, d } = await inside();
  const left = SWITCHES.filter((name) => name in l.env);
  must(left.length === 0, `its environment still holds ${left.join(", ")}`);
  must(!headUrl(l).startsWith(d.url), `its ANTHROPIC_BASE_URL is the door, ${headUrl(l)}`);
});

check("a head started from inside a Code tab session runs its turn on its head", async () => {
  const { s, l } = await inside();
  const session = crypto.randomUUID();
  const before = astra.completions().length;
  const t = await askHead(l, { model: l.env.ANTHROPIC_MODEL ?? "astra-large", session });
  must(t.status === 200 && t.reply === astra.reply(), `its turn was answered ${answer(t)}`);
  must(astra.completions().length === before + 1, `its provider was sent ${astra.completions().length - before} requests, not one`);
  const row = await headRow(s, "astra", session);
  must(row.door === undefined || row.door === null, `its row says it came through the door: ${show(row.door)}`);
});

check("a head with a config folder of its own carries nothing of the door in its settings", async () => {
  const { s, d, l } = await inside();
  must(l.dir !== null && l.dir !== join(s.home, ".claude"), `its config folder is ${show(l.dir)}, the claude command's own`);
  const text = readFileSync(join(l.dir, "settings.json"), "utf8");
  const found = [d.key, d.url, "CLAUDE_CODE_USE_MANTLE", "ANTHROPIC_BEDROCK_MANTLE_BASE_URL", "AWS_BEARER_TOKEN_BEDROCK"].filter((x) => text.includes(x));
  must(found.length === 0, `its settings hold ${found.map((x) => (x === d.key ? "the desktop key" : x)).join(", ")}`);
});

// ---------- the claude command's own launch ----------

/** The `claude` command's launch with Desktop on, and the settings it hands Claude Code over the file. */
const wrapped = once(async (): Promise<{ s: Splice; r: Recipe; over: Record<string, unknown>; own: Record<string, unknown> }> => {
  const before = await claudeBefore();
  const { s } = await open();
  const r = await recipe(s, "claude");
  const over = overlay(r);
  must(over !== null, `the launch hands Claude Code no settings of its own: ${show(r.argv)}`);
  // the launch's own values: what it set before Desktop was on, in its environment or in its own settings, and
  // what its environment sets now
  const own = { ...((overlay(before)?.env ?? {}) as Record<string, unknown>), ...before.env, ...r.env };
  return { s, r, over, own };
});

check("the wrapped claude's overlay sets every variable on wrote to the launch's own value or to the empty string", async () => {
  const { over, own } = await wrapped();
  const env = (over.env ?? {}) as Record<string, unknown>;
  const wrong = WRITTEN.filter((name) => !(name in env) || !(env[name] === "" || (name in own && env[name] === own[name])));
  must(
    wrong.length === 0,
    `${wrong.map((name) => `${name} is ${name in env ? (name === "AWS_BEARER_TOKEN_BEDROCK" ? "a key" : show(env[name])) : "not set"} and the launch's own is ${show(own[name])}`).join("; ")}`,
  );
});

check("the wrapped claude's overlay carries a picker with none of the door's rows", async () => {
  const { s, over } = await wrapped();
  const lineup = rows(s).map((row) => row.model);
  must(lineup.length > 0, "the file's picker has no rows, so the overlay has nothing to cancel");
  must("modelPicker" in over, `the overlay carries no modelPicker: its keys are ${show(Object.keys(over))}`);
  const theirs = rowsOf(over.modelPicker).filter((row) => lineup.includes(row.model));
  must(theirs.length === 0, `the overlay's picker has the door's ${show(theirs.map((row) => row.model))}`);
});

check("where the file's model holds a selector the wrapped claude's launch says so in its warning", async () => {
  const { s } = await wrapped();
  const selector = rows(s)[0]?.model;
  must(selector !== undefined, "the file's picker has no rows to pick from");
  const held = settings(s);
  must(!("model" in held), `the file already holds a model, ${show(held.model)}`);
  const without = (await recipe(s, "claude")).warning ?? "";
  // the person picks a row in the Code tab, and Claude Code writes the pick into the file
  writeSettings(s, { ...held, model: selector });
  try {
    const warned = (await recipe(s, "claude")).warning ?? "";
    must(warned !== "" && warned !== without, `with ${selector} as the file's model the launch's warning is ${show(warned)}, and without it ${show(without)}`);
  } finally {
    writeSettings(s, held);
  }
});

// ---------- the claude command's folder ----------

const tomlOfSecond = { astra: "", borealis: "" };

const lone = once(async () => {
  for (const up of [second, extra]) up.start();
  tomlOfSecond.astra = keyHead({ key: "astra", command: "claude-astra", port: await freePort(), url: second.url, models: [{ id: "astra-large" }, { id: "astra-small" }] });
  tomlOfSecond.borealis = keyHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: extra.url, models: [{ id: "borealis-large" }] });
  alone = await Splice.boot(tomlOfSecond.astra);
  return alone;
});

check("splice writes one file in the claude command's folder", async () => {
  const s = await lone();
  const d = desktopOn(s);
  const t = await ask(d, { model: d.model, session: crypto.randomUUID() });
  must(t.status === 200, `a turn through the door was answered ${answer(t)}`);
  await launch(s, "claude-astra");
  desktop(s, "rotate-key");
  same(filesUnder(join(s.home, ".claude")), ["settings.json"], "the files in the claude command's folder");
});

check("with Desktop off splice leaves the settings file alone when its heads change", async () => {
  const s = await lone();
  must(fileEnv(s).CLAUDE_CODE_USE_MANTLE === "1", "Desktop is not on, so off has nothing to turn off");
  desktop(s, "off");
  const before = settingsText(s);
  must(!(before ?? "").includes("CLAUDE_CODE_USE_MANTLE"), "off left the switch in the file");
  s.writeConfig(tomlOfSecond.astra + tomlOfSecond.borealis);
  await s.restart();
  await Bun.sleep(5000);
  must(settingsText(s) === before, `the file was ${show(before)} after off and is ${show(settingsText(s))} after a head was added`);
});

// ---------- the README ----------

check("the README says that splice desktop writes the claude command's settings file", () => {
  const blocks = readFileSync(join(ROOT, "README.md"), "utf8")
    .split(/\n\s*\n/)
    .flatMap((block) => (block.trimStart().startsWith("|") ? block.split("\n") : [block]));
  must(
    blocks.some((b) => b.includes("splice desktop") && b.includes("settings.json")),
    "no paragraph or table row of README.md names both splice desktop and settings.json",
  );
});

await run(async () => {
  await live?.dispose();
  await alone?.dispose();
  for (const up of [astra, anthropic, second, extra]) up.stop();
});
