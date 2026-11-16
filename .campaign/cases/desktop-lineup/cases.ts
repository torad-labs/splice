// desktop-lineup: the Code tab's picker lists every model of every head in the door, one row a model, and follows
// splice while Desktop is on.
//
// Four heads, in this order in splice.toml:
//   borealis  called with an API key splice holds: in the door
//   corvus    forwards the caller's own Claude login: not in the door
//   astra     called with an API key splice holds: in the door
//   nova      signed in with a ChatGPT login whose file is not there: left out until it is signed in
// A model's label is not its ID, so a row's label can be told from its selector.

import { rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Splice, freePort, until } from "../../checks/server/lib/harness.ts";
import { Upstream } from "../../checks/server/lib/upstream.ts";
import { CodexBackend, FAST, gptEnv } from "../../checks/speed/lib/codex.ts";
import { check, must, once, run, same, show } from "../desktop-lib/cases.ts";
import { type Door, ask, chatgptHead, clientHead, desk, desktop, desktopOn, doorOf, keyHead, launch, listed, refused, rows, runs, signIn } from "../desktop-lib/door.ts";

const BOREALIS = ["claude-borealis--borealis-large", "claude-borealis--borealis-small"];
const ASTRA = ["claude-astra--astra-large", "claude-astra--astra-small"];
const NOVA = "claude-nova--gpt-6-nova";
const CYGNUS = ["claude-cygnus--cygnus-large", "claude-cygnus--cygnus-small"];

const borealis = new Upstream("borealis", "borealis-large");
const corvus = new Upstream("corvus", "claude-opus-5-5");
const astra = new Upstream("astra", "astra-large");
const cygnus = new Upstream("cygnus", "cygnus-large");
const logins = join(tmpdir(), `splice-desktop-lineup-${process.pid}`);
const login = join(logins, "nova-auth.json");
let backend: CodexBackend | undefined;
let live: Splice | undefined;
/** Each head's part of splice.toml. */
const toml = { borealis: "", corvus: "", astra: "", nova: "", cygnus: "" };

const booted = once(async () => {
  for (const up of [borealis, corvus, astra, cygnus]) up.start();
  backend = CodexBackend.start([{ slug: "gpt-6-nova", tiers: [FAST] }]);
  toml.borealis = keyHead({
    key: "borealis",
    command: "claude-borealis",
    port: await freePort(),
    url: borealis.url,
    models: [
      { id: "borealis-large", label: "Borealis Large" },
      { id: "borealis-small", label: "Borealis Small" },
    ],
  });
  toml.corvus = clientHead({ key: "corvus", command: "claude-corvus", port: await freePort(), url: corvus.url });
  toml.astra = keyHead({
    key: "astra",
    command: "claude-astra",
    port: await freePort(),
    url: astra.url,
    models: [
      { id: "astra-large", label: "Astra Large" },
      { id: "astra-small", label: "Astra Small" },
    ],
  });
  toml.nova = chatgptHead({ key: "nova", command: "claude-nova", port: await freePort(), backend: backend.url, model: "gpt-6-nova", login });
  toml.cygnus = keyHead({
    key: "cygnus",
    command: "claude-cygnus",
    port: await freePort(),
    url: cygnus.url,
    models: [
      { id: "cygnus-large", label: "Cygnus Large" },
      { id: "cygnus-small", label: "Cygnus Small" },
    ],
  });
  live = await Splice.boot(toml.borealis + toml.corvus + toml.astra + toml.nova, gptEnv(backend.url));
  return live;
});

/** Desktop on, and the door the file held from then. */
const open = once(async (): Promise<{ s: Splice; d: Door }> => {
  const s = await booted();
  return { s, d: desktopOn(s) };
});

/** The picker's selectors, in the file's order. */
function picked(s: Splice): string[] {
  return rows(s).map((r) => r.model);
}

/** The heads of a list of selectors, by their prefixes, in the order they first come. */
function headsOf(selectors: string[]): string[] {
  const out: string[] = [];
  for (const m of selectors) {
    const head = m.split("--")[0] ?? m;
    if (out.at(-1) !== head) out.push(head);
  }
  return out;
}

/** Waits for the picker in the file to be as `ok` wants it, with no command run, and answers its selectors. */
async function follows(s: Splice, what: string, ok: (selectors: string[]) => boolean): Promise<string[]> {
  let selectors: string[] = [];
  await until(
    what,
    () => {
      try {
        selectors = picked(s);
        return ok(selectors);
      } catch {
        return false;
      }
    },
    30_000,
  );
  return selectors;
}

// ---------- the rows ----------

check("the picker has one row for each model of each head in the door", async () => {
  const { s } = await open();
  same(picked(s).sort(), [...ASTRA, ...BOREALIS].sort(), "the picker's selectors");
});

check("the picker's rows are in splice.toml's order of heads", async () => {
  const { s } = await open();
  same(headsOf(picked(s)), ["claude-borealis", "claude-astra"], "the heads of the picker's rows, in their order");
});

check("a row's model is the ID its head's own /v1/models lists", async () => {
  const { s } = await open();
  const ids = [...(await listed(await launch(s, "claude-borealis"))), ...(await listed(await launch(s, "claude-astra")))].map((m) => m.id);
  must(ids.length === 4, `the two heads' own /v1/models list ${show(ids)}`);
  same(picked(s).sort(), [...ids].sort(), "the picker's selectors");
});

check("a row's label is the model's own label", async () => {
  const { s } = await open();
  same(
    Object.fromEntries(rows(s).map((r) => [r.model, r.label])),
    {
      "claude-borealis--borealis-large": "Borealis Large",
      "claude-borealis--borealis-small": "Borealis Small",
      "claude-astra--astra-large": "Astra Large",
      "claude-astra--astra-small": "Astra Small",
    },
    "each row's label",
  );
});

check("a row's description names splice and its head's command", async () => {
  const { s } = await open();
  same(
    Object.fromEntries(rows(s).map((r) => [r.model, r.description])),
    {
      "claude-borealis--borealis-large": "splice · claude-borealis",
      "claude-borealis--borealis-small": "splice · claude-borealis",
      "claude-astra--astra-large": "splice · claude-astra",
      "claude-astra--astra-small": "splice · claude-astra",
    },
    "each row's description",
  );
});

check("splice desktop --json lists the same models as the picker", async () => {
  const { s } = await open();
  const models = desk(s).models.map((m) => m.model);
  must(models.length > 0, "splice desktop --json lists no models");
  same(models, picked(s), "the models splice desktop --json lists, in their order");
});

check("each model splice desktop --json lists has its model, label, head and command", async () => {
  const { s } = await open();
  const byModel = (a: { model: string }, b: { model: string }) => (a.model < b.model ? -1 : 1);
  same(
    desk(s)
      .models.map(({ model, label, head, command }) => ({ model, label, head, command }))
      .sort(byModel),
    [
      { model: "claude-astra--astra-large", label: "Astra Large", head: "astra", command: "claude-astra" },
      { model: "claude-astra--astra-small", label: "Astra Small", head: "astra", command: "claude-astra" },
      { model: "claude-borealis--borealis-large", label: "Borealis Large", head: "borealis", command: "claude-borealis" },
      { model: "claude-borealis--borealis-small", label: "Borealis Small", head: "borealis", command: "claude-borealis" },
    ],
    "the models splice desktop --json lists",
  );
});

// ---------- the heads left out ----------

check("a head that forwards the caller's own Claude login has no row", async () => {
  const { s } = await open();
  must(rows(s).length > 0, "the picker has no rows");
  const its = rows(s).filter((r) => r.model.includes("corvus") || (r.description ?? "").includes("corvus"));
  must(its.length === 0, `the picker has ${show(its)}`);
});

check("left_out names the head that forwards the caller's own Claude login, with that reason", async () => {
  const { s } = await open();
  const out = desk(s).left_out;
  const it = out.find((h) => h.head === "corvus");
  must(it !== undefined, `left_out is ${show(out)}`);
  must(/claude|login/i.test(String(it.reason ?? "")), `its reason is ${show(it.reason)}`);
});

check("splice desktop names the head that forwards the caller's own Claude login", async () => {
  const { s } = await open();
  const plain = desktop(s).out;
  must(plain.includes("corvus"), `it does not name corvus: ${plain.replace(/\s+/g, " ").slice(0, 400)}`);
});

check("a head whose login is missing has no row", async () => {
  const { s } = await open();
  must(rows(s).length > 0, "the picker has no rows");
  const its = rows(s).filter((r) => r.model.includes("nova") || (r.description ?? "").includes("nova"));
  must(its.length === 0, `the picker has ${show(its)}`);
});

check("left_out names the head whose login is missing", async () => {
  const { s } = await open();
  const out = desk(s).left_out;
  must(
    out.some((h) => h.head === "nova"),
    `left_out is ${show(out)}`,
  );
});

check("splice desktop names the head whose login is missing with the command that signs it in", async () => {
  const { s } = await open();
  const plain = desktop(s).out;
  must(plain.includes("claude-nova login") || plain.includes("splice login nova"), `it names neither claude-nova login nor splice login nova: ${plain.replace(/\s+/g, " ").slice(0, 400)}`);
});

// ---------- the lineup follows splice ----------

/** nova is signed in, and the daemon is started again: no `splice` command is run. */
const signedIn = once(async () => {
  const { s } = await open();
  signIn(login, "nova");
  await s.restart();
  return follows(s, "the picker to list the head that was signed in", (m) => m.includes(NOVA));
});

check("a head signed in again is in the picker with no command run", async () => {
  await signedIn();
});

check("a head signed in again is no longer left out", async () => {
  const { s } = await open();
  await signedIn();
  const out = desk(s).left_out;
  must(!out.some((h) => h.head === "nova"), `left_out is ${show(out)}`);
});

check("the door and its key stay as the file held them when the lineup changes", async () => {
  const { s, d } = await open();
  await signedIn();
  same({ url: doorOf(s).url, key: doorOf(s).key }, { url: d.url, key: d.key }, "the door and the key in the file");
});

/** A head is added at the end of splice.toml, and the daemon is started again. */
const added = once(async () => {
  const { s } = await open();
  s.writeConfig(toml.borealis + toml.corvus + toml.astra + toml.nova + toml.cygnus);
  await s.restart();
  return follows(s, "the picker to list the head that was added", (m) => CYGNUS.every((c) => m.includes(c)));
});

check("a head added to splice is in the picker with no command run", async () => {
  await added();
});

check("the picker keeps splice.toml's order of heads when a head is added", async () => {
  const selectors = await added();
  const heads = headsOf(selectors);
  same(
    heads.filter((h) => h !== "claude-nova"),
    ["claude-borealis", "claude-astra", "claude-cygnus"],
    "the heads of the picker's rows, in their order",
  );
});

check("a model of a head that was added runs through the door the file held before", async () => {
  const { d } = await open();
  await added();
  await runs(d, cygnus, "claude-cygnus--cygnus-small", "cygnus-small");
});

/** A head is taken out of splice.toml, and the daemon is started again. */
const removed = once(async () => {
  const { s } = await open();
  must(picked(s).includes("claude-astra--astra-large"), "the picker does not list the head that is to be taken out");
  s.writeConfig(toml.borealis + toml.corvus + toml.nova + toml.cygnus);
  await s.restart();
  return follows(s, "the picker to drop the head that was taken out", (m) => m.length > 0 && !m.some((x) => x.startsWith("claude-astra--")));
});

check("a head removed from splice leaves the picker", async () => {
  await removed();
});

check("a removed head's selectors are refused", async () => {
  const { d } = await open();
  await removed();
  const before = astra.completions().length;
  refused(await ask(d, { model: "claude-astra--astra-large", session: crypto.randomUUID() }), 400, "invalid_request_error");
  must(astra.completions().length === before, "the removed head's provider was sent the turn");
});

check("a head whose login is removed leaves the picker", async () => {
  const { s } = await open();
  await signedIn();
  must(picked(s).includes(NOVA), "the picker does not list the head whose login is to be removed");
  rmSync(login);
  await s.restart();
  await follows(s, "the picker to drop the head whose login was removed", (m) => m.length > 0 && !m.includes(NOVA));
});

await run(async () => {
  await live?.dispose();
  for (const up of [borealis, corvus, astra, cygnus]) up.stop();
  backend?.stop();
  rmSync(logins, { recursive: true, force: true });
});
