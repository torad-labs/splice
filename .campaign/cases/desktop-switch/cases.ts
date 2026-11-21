// desktop-switch: `splice desktop on` points the Code tab of Claude Desktop at splice by writing Claude Code's own
// switch into ~/.claude/settings.json, keeping everything else the person has there; `off` puts the file back; and
// `splice desktop` says what is so.
//
// Two heads in the door, astra first in splice.toml (pinned astra-large), then borealis (pinned borealis-large).
// The cases run in the order they are written: first on a machine with no settings file, then on one whose person
// has settings of their own, then the files and the commands `on` refuses.

import { join } from "node:path";
import { Splice, freePort } from "../../checks/server/lib/harness.ts";
import { Upstream } from "../../checks/server/lib/upstream.ts";
import { check, must, once, run, same, show } from "../desktop-lib/cases.ts";
import { WRITTEN, answer, ask, clientHead, desk, desktop, fileEnv, holders, keyHead, printed, settings, settingsText, writeSettings } from "../desktop-lib/door.ts";

const ASTRA_LARGE = "claude-astra--astra-large";
const ASTRA_SMALL = "claude-astra--astra-small";
const BOREALIS_SMALL = "claude-borealis--borealis-small";

/** A label nothing else on the machine holds, in a setting `on` replaces. */
const MARK = "My Opus 7c1f9e";

/** What a person has in the file before splice touches it, with values of their own under keys `on` writes. */
const MINE = {
  env: { EDITOR: "vim", CLAUDE_CODE_MAX_CONTEXT_TOKENS: "200000", ANTHROPIC_MODEL: "opus" },
  permissions: { allow: ["Bash(ls:*)"] },
  statusLine: { type: "command", command: "echo mine" },
  model: "opus",
  modelPicker: { options: [{ model: "opus", label: MARK }] },
};

const astra = new Upstream("astra", "astra-large");
const borealis = new Upstream("borealis", "borealis-large");
const corvus = new Upstream("corvus", "claude-opus-5-5");
let live: Splice | undefined;
let lone: Splice | undefined;

const booted = once(async () => {
  for (const up of [astra, borealis]) up.start();
  live = await Splice.boot(
    keyHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, models: [{ id: "astra-large" }, { id: "astra-small" }] }) +
      keyHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, models: [{ id: "borealis-large" }, { id: "borealis-small" }] }),
  );
  return live;
});

// ---------- on a machine with no settings file ----------

/** `splice desktop on`, run where there is no settings file and no model was chosen before. */
const fresh = once(async () => {
  const s = await booted();
  must(settingsText(s) === null, "there is a settings file before splice desktop on was run");
  const ran = desktop(s, "on");
  return { s, said: printed(ran), env: fileEnv(s), held: settings(s), made: settingsText(s) !== null };
});

/** What `splice desktop --json` says after it. */
const told = once(async () => desk((await fresh()).s));

check("on makes the settings file when there is none", async () => {
  must((await fresh()).made, "splice desktop on ended well and there is no ~/.claude/settings.json");
});

check("on writes CLAUDE_CODE_USE_MANTLE=1 into the file's env", async () => {
  const { env } = await fresh();
  must(env.CLAUDE_CODE_USE_MANTLE === "1", `CLAUDE_CODE_USE_MANTLE is ${show(env.CLAUDE_CODE_USE_MANTLE)}`);
});

check("on writes the door's address as ANTHROPIC_BEDROCK_MANTLE_BASE_URL", async () => {
  const { env } = await fresh();
  const url = env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL ?? "";
  must(/^https?:\/\/[^/]+\/desktop$/.test(url), `ANTHROPIC_BEDROCK_MANTLE_BASE_URL is ${show(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL)}`);
  const t = await ask({ url, key: env.AWS_BEARER_TOKEN_BEDROCK ?? "" }, { model: ASTRA_LARGE, session: crypto.randomUUID() });
  must(t.status === 200, `a turn sent to it was answered ${answer(t)}`);
});

check("on writes the desktop key as AWS_BEARER_TOKEN_BEDROCK", async () => {
  const { env } = await fresh();
  const key = env.AWS_BEARER_TOKEN_BEDROCK ?? "";
  must(key !== "", `AWS_BEARER_TOKEN_BEDROCK is ${show(env.AWS_BEARER_TOKEN_BEDROCK)}`);
  const url = env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL ?? "";
  const with_ = await ask({ url, key }, { model: ASTRA_LARGE, session: crypto.randomUUID() });
  must(with_.status === 200, `a turn that presents it was answered ${answer(with_)}`);
  const without = await ask({ url, key: `${key}x` }, { model: ASTRA_LARGE, session: crypto.randomUUID() });
  must(without.status === 401, `a turn that presents another key was answered ${answer(without)}`);
});

check("on declares a window as CLAUDE_CODE_MAX_CONTEXT_TOKENS", async () => {
  const { env } = await fresh();
  must(/^[1-9]\d*$/.test(env.CLAUDE_CODE_MAX_CONTEXT_TOKENS ?? ""), `CLAUDE_CODE_MAX_CONTEXT_TOKENS is ${show(env.CLAUDE_CODE_MAX_CONTEXT_TOKENS)}`);
});

check("on writes CLAUDE_CODE_AUTO_MODE_SERVER=0", async () => {
  const { env } = await fresh();
  must(env.CLAUDE_CODE_AUTO_MODE_SERVER === "0", `CLAUDE_CODE_AUTO_MODE_SERVER is ${show(env.CLAUDE_CODE_AUTO_MODE_SERVER)}`);
});

check("on writes the default model's selector as ANTHROPIC_MODEL", async () => {
  const { env } = await fresh();
  const d = await told();
  must(Boolean(env.ANTHROPIC_MODEL) && env.ANTHROPIC_MODEL === d.default_model, `ANTHROPIC_MODEL is ${show(env.ANTHROPIC_MODEL)}, and splice desktop says the default model is ${show(d.default_model)}`);
});

check("on writes modelPicker with replaceBuiltInOptions true", async () => {
  const { held } = await fresh();
  const picker = held.modelPicker as { replaceBuiltInOptions?: unknown } | undefined;
  must(picker?.replaceBuiltInOptions === true, `modelPicker is ${show(held.modelPicker)}`);
});

check("with no --model and none chosen before, the default model is the pinned model of the first head in the door", async () => {
  const { env } = await fresh();
  must(env.ANTHROPIC_MODEL === ASTRA_LARGE, `ANTHROPIC_MODEL is ${show(env.ANTHROPIC_MODEL)}, not ${ASTRA_LARGE}`);
});

check("on says which model is the default", async () => {
  const { env, said } = await fresh();
  must(Boolean(env.ANTHROPIC_MODEL) && said.includes(env.ANTHROPIC_MODEL ?? ""), `on wrote ${show(env.ANTHROPIC_MODEL)} as the default model and said: ${said.slice(0, 300)}`);
});

check("on writes no model into a file that has none", async () => {
  const { held } = await fresh();
  must(!("model" in held), `the file's model is ${show(held.model)}`);
});

check("splice desktop --json gives on, door, default_model, models, left_out and last_turn", async () => {
  const d = (await told()) as unknown as Record<string, unknown>;
  const missing = ["on", "door", "default_model", "models", "left_out", "last_turn"].filter((k) => !(k in d));
  must(missing.length === 0, `it gives no ${missing.join(", ")}: ${show(d)}`);
});

check("splice desktop --json says Desktop is on after on", async () => {
  must((await told()).on === true, `on is ${show((await told()).on)}`);
});

check("splice desktop --json gives the door the file names", async () => {
  const { env } = await fresh();
  must((await told()).door === env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL, `door is ${show((await told()).door)}, and the file names ${show(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL)}`);
});

check("splice desktop says the door", async () => {
  const { s, env } = await fresh();
  const plain = desktop(s).out;
  must(Boolean(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL) && plain.includes(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL ?? ""), `it does not name ${show(env.ANTHROPIC_BEDROCK_MANTLE_BASE_URL)}: ${plain.replace(/\s+/g, " ").slice(0, 300)}`);
});

check("splice desktop says the lineup", async () => {
  const { s } = await fresh();
  const plain = desktop(s).out;
  const missing = ["astra-large", "astra-small", "borealis-large", "borealis-small"].filter((m) => !plain.includes(m));
  must(missing.length === 0, `it does not name ${missing.join(", ")}: ${plain.replace(/\s+/g, " ").slice(0, 300)}`);
});

check("off takes out what on put in", async () => {
  const { s } = await fresh();
  desktop(s, "off");
  const env = fileEnv(s);
  const left = [...WRITTEN.filter((name) => name in env), ...("modelPicker" in settings(s) ? ["modelPicker"] : [])];
  must(left.length === 0, `the file still holds ${left.join(", ")}`);
});

check("a file that was not there before on holds nothing after off", async () => {
  const { s } = await fresh();
  must(!desk(s).on, "Desktop is still on, so off has not run");
  same(settings(s), {}, "what the file holds after off");
});

check("splice desktop --json says Desktop is not on after off", async () => {
  const { s } = await fresh();
  must(desk(s).on === false, `on is ${show(desk(s).on)}`);
});

// ---------- on a machine whose person has settings of their own ----------

/** `splice desktop on`, run over the person's own settings. */
const over = once(async () => {
  const { s } = await fresh();
  must(!desk(s).on, "Desktop is still on, so the person's own file cannot be laid down");
  writeSettings(s, MINE);
  desktop(s, "on");
  return { s, env: fileEnv(s), held: settings(s) };
});

check("on keeps everything else in the file", async () => {
  const { env, held } = await over();
  same({ EDITOR: env.EDITOR, permissions: held.permissions, statusLine: held.statusLine }, { EDITOR: "vim", permissions: MINE.permissions, statusLine: MINE.statusLine }, "what the person had beside what on writes");
});

check("on leaves the person's own model as it is", async () => {
  const { held } = await over();
  must(held.model === "opus", `the file's model is ${show(held.model)}, and the person's pick was "opus"`);
});

check("what on replaced is kept in splice's state folder", async () => {
  const { s, env } = await over();
  must(env.CLAUDE_CODE_USE_MANTLE === "1", "on did not write its switch over the person's file");
  must(holders(s.state, MARK).length > 0, `no file under splice's state folder holds the picker on replaced (its label is "${MARK}")`);
});

check("nothing on replaced is kept beside the settings file", async () => {
  const { s, env } = await over();
  must(env.CLAUDE_CODE_USE_MANTLE === "1", "on did not write its switch over the person's file");
  const beside = holders(join(s.home, ".claude"), MARK).filter((f) => f !== "settings.json");
  must(beside.length === 0, `the picker on replaced is kept in ~/.claude/${beside.join(", ~/.claude/")}`);
});

check("off puts the file back as it was before on", async () => {
  const { s } = await over();
  desktop(s, "off");
  same(settings(s), MINE, "what the file holds after off");
});

check("on with --model names the default model", async () => {
  const { s } = await over();
  desktop(s, "on", "--model", BOREALIS_SMALL);
  must(fileEnv(s).ANTHROPIC_MODEL === BOREALIS_SMALL, `ANTHROPIC_MODEL is ${show(fileEnv(s).ANTHROPIC_MODEL)}, not ${BOREALIS_SMALL}`);
  must(desk(s).default_model === BOREALIS_SMALL, `splice desktop says the default model is ${show(desk(s).default_model)}, not ${BOREALIS_SMALL}`);
});

check("on run with no --model keeps the model chosen the last time", async () => {
  const { s } = await over();
  must(fileEnv(s).ANTHROPIC_MODEL === BOREALIS_SMALL, `--model did not choose ${BOREALIS_SMALL} the last time: ANTHROPIC_MODEL is ${show(fileEnv(s).ANTHROPIC_MODEL)}`);
  desktop(s, "off");
  desktop(s, "on");
  must(fileEnv(s).ANTHROPIC_MODEL === BOREALIS_SMALL, `ANTHROPIC_MODEL is ${show(fileEnv(s).ANTHROPIC_MODEL)}, not ${BOREALIS_SMALL}`);
});

check("on run again with another --model changes the default model", async () => {
  const { s } = await over();
  must(desk(s).on, "Desktop is not on, so on is not being run again");
  desktop(s, "on", "--model", ASTRA_SMALL);
  must(fileEnv(s).ANTHROPIC_MODEL === ASTRA_SMALL, `ANTHROPIC_MODEL is ${show(fileEnv(s).ANTHROPIC_MODEL)}, not ${ASTRA_SMALL}`);
});

check("off after on was run more than once puts the file back as it was before the first", async () => {
  const { s } = await over();
  must(fileEnv(s).CLAUDE_CODE_USE_MANTLE === "1", "Desktop is not on, so there is nothing for off to put back");
  desktop(s, "off");
  same(settings(s), MINE, "what the file holds after off");
});

// ---------- what on refuses ----------

interface Refusal {
  code: number;
  said: string;
  before: string | null;
  after: string | null;
}

/** `splice desktop on <args>` over `file`, with Desktop off: how it ended, what it said, and the file before and
 *  after. */
async function refusal(s: Splice, file: Record<string, unknown>, args: string[]): Promise<Refusal> {
  must(!desk(s).on, "Desktop is on, so on cannot be shown to refuse");
  writeSettings(s, file);
  const before = settingsText(s);
  const ran = s.cli(["desktop", "on", ...args]);
  return { code: ran.code, said: printed(ran), before, after: settingsText(s) };
}

/** Three cases for one thing `on` refuses: it refuses, it changes nothing, and it names what it found. */
function refuses(what: string, made: () => Promise<Refusal>, names: string[]): void {
  check(`on refuses ${what}`, async () => {
    const r = await made();
    must(r.code !== 0, `it exited 0 and said: ${r.said.slice(0, 300)}`);
  });
  check(`on changes nothing when it refuses ${what}`, async () => {
    const r = await made();
    must(r.code !== 0, `it exited 0 and said: ${r.said.slice(0, 300)}`);
    must(r.after === r.before, `the file was ${show(r.before)} and is ${show(r.after)}`);
  });
  check(`on names what it found when it refuses ${what}`, async () => {
    const r = await made();
    must(r.code !== 0, `it exited 0 and said: ${r.said.slice(0, 300)}`);
    must(
      names.some((n) => r.said.includes(n)),
      `it names none of ${names.join(", ")}: ${r.said.slice(0, 300)}`,
    );
  });
}

refuses(
  "a --model that is not in the door",
  once(async () => refusal((await over()).s, MINE, ["--model", "claude-nobody--nothing"])),
  ["claude-nobody--nothing"],
);

for (const provider of ["CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY"]) {
  refuses(
    `a file that sets ${provider}`,
    once(async () => refusal((await over()).s, { ...MINE, env: { ...MINE.env, [provider]: "1" } }, [])),
    [provider],
  );
}

const ELSEWHERE = "https://bedrock-mantle.us-east-1.api.aws";

refuses(
  "a file that sets CLAUDE_CODE_USE_MANTLE with an address that is not the door",
  once(async () => refusal((await over()).s, { ...MINE, env: { ...MINE.env, CLAUDE_CODE_USE_MANTLE: "1", ANTHROPIC_BEDROCK_MANTLE_BASE_URL: ELSEWHERE } }, [])),
  ["CLAUDE_CODE_USE_MANTLE", ELSEWHERE],
);

/** `on` where the one head forwards the caller's own Claude login, so no head is in the door. */
const empty = once(async () => {
  corvus.start();
  lone = await Splice.boot(clientHead({ key: "corvus", command: "claude-corvus", port: await freePort(), url: corvus.url }));
  return refusal(lone, MINE, []);
});

check("on refuses when no head is in the door", async () => {
  const r = await empty();
  must(r.code !== 0, `it exited 0 and said: ${r.said.slice(0, 300)}`);
});

check("on changes nothing when no head is in the door", async () => {
  const r = await empty();
  must(r.code !== 0, `it exited 0 and said: ${r.said.slice(0, 300)}`);
  must(r.after === r.before, `the file was ${show(r.before)} and is ${show(r.after)}`);
});

await run(async () => {
  await live?.dispose();
  await lone?.dispose();
  for (const up of [astra, borealis, corvus]) up.stop();
});
