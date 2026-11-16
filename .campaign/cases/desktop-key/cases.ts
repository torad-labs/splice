// desktop-key: the door has a key of its own, the desktop key. It opens the door and nothing else, no other key of
// splice's opens the door, it is never sent to a provider, `splice rotate-key` leaves it as it is, and `splice
// desktop rotate-key` replaces it, in the settings file too.
//
// One head in the door, astra. The cases run in the order they are written: the key as `on` wrote it, then `splice
// rotate-key`, then `splice desktop rotate-key` with Desktop on, then with Desktop off.

import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { Splice, type Turn, freePort, turn } from "../../checks/server/lib/harness.ts";
import { Upstream } from "../../checks/server/lib/upstream.ts";
import { check, must, once, run, same, show } from "../desktop-lib/cases.ts";
import { type Door, answer, ask, count, desktop, desktopOn, doorOf, fileEnv, headUrl, holders, keyHead, launch, refused, settings, turnKey } from "../desktop-lib/door.ts";

const MODEL = "claude-astra--astra-large";

const astra = new Upstream("astra", "astra-large");
let live: Splice | undefined;

const booted = once(async () => {
  astra.start();
  live = await Splice.boot(keyHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, models: [{ id: "astra-large" }, { id: "astra-small" }] }));
  return live;
});

/** Desktop on, and the door with the key `on` wrote. */
const open = once(async (): Promise<{ s: Splice; d: Door }> => {
  const s = await booted();
  return { s, d: desktopOn(s) };
});

/** One turn that presents `key` (none when null), and how many requests the provider was sent because of it. */
async function presenting(d: Door, key: string | null): Promise<{ t: Turn; sent: number }> {
  const before = astra.completions().length;
  const t = await ask(d, { model: MODEL, session: crypto.randomUUID(), key });
  return { t, sent: astra.completions().length - before };
}

/** Three cases for one key that must not open the door: the 401, what it names, and nothing sent upstream. */
function refusedKey(what: string, made: () => Promise<{ t: Turn; sent: number }>): void {
  check(`a request with ${what} is answered 401 authentication_error`, async () => {
    refused((await made()).t, 401, "authentication_error");
  });
  check(`the refusal of a request with ${what} names splice desktop`, async () => {
    const { t } = await made();
    must(t.status === 401, `it was answered ${answer(t)}, not 401`);
    must((t.error?.message ?? "").includes("splice desktop"), `the refusal says ${show(t.error?.message)}`);
  });
  check(`a request with ${what} sends nothing upstream`, async () => {
    const { t, sent } = await made();
    must(t.status === 401, `it was answered ${answer(t)}, not 401`);
    must(sent === 0, `the provider was sent ${sent} requests`);
  });
}

// ---------- the key on wrote ----------

check("the desktop key opens the door", async () => {
  const { d } = await open();
  const { t } = await presenting(d, d.key);
  must(t.status === 200, `it was answered ${answer(t)}`);
  must(t.reply === astra.reply(), `the answer's text is "${t.reply}", not the provider's`);
});

refusedKey(
  "no key",
  once(async () => presenting((await open()).d, null)),
);

refusedKey(
  "a wrong key",
  once(async () => presenting((await open()).d, "not-the-desktop-key")),
);

check("a count_tokens request with no key is answered 401", async () => {
  const { d } = await open();
  const c = await count(d, { model: MODEL, key: null });
  must(c.status === 401, `it was answered ${c.status} ${c.body.slice(0, 200)}`);
});

check("the turn key does not open the door", async () => {
  const { s, d } = await open();
  const key = turnKey(await launch(s, "claude-astra"));
  const { t, sent } = await presenting(d, key);
  refused(t, 401, "authentication_error");
  must(sent === 0, `the provider was sent ${sent} requests`);
});

check("the management key does not open the door", async () => {
  const { s, d } = await open();
  const { t, sent } = await presenting(d, s.mgmtKey());
  refused(t, 401, "authentication_error");
  must(sent === 0, `the provider was sent ${sent} requests`);
});

check("a head's own routes refuse the desktop key", async () => {
  const { s, d } = await open();
  const base = headUrl(await launch(s, "claude-astra"));
  const before = astra.completions().length;
  for (const bearer of [false, true]) {
    const t = await turn(`${base}/v1/messages`, { key: d.key, bearer, model: "astra-large" });
    must(t.status === 401 || t.status === 403, `the head's own Messages route, given the desktop key as ${bearer ? "a Bearer" : "x-api-key"}, answered ${answer(t)}`);
  }
  must(astra.completions().length === before, "the provider was sent a turn that presented the desktop key to the head");
});

check("the control API refuses the desktop key", async () => {
  const { s, d } = await open();
  const r = await fetch(`http://127.0.0.1:${s.controlPort}/api/perf/turns`, { headers: { authorization: `Bearer ${d.key}` } });
  must(r.status === 401 || r.status === 403, `/api/perf/turns, given the desktop key, answered ${r.status}`);
});

check("the desktop key is not forwarded to the provider", async () => {
  const { d } = await open();
  const before = astra.seen.length;
  const { t } = await presenting(d, d.key);
  must(t.status === 200, `the turn was answered ${answer(t)}`);
  const sent = astra.seen.slice(before);
  must(
    sent.some((r) => r.path.endsWith("/chat/completions")),
    "the provider was not sent the turn",
  );
  must(!JSON.stringify(sent).includes(d.key), "what the provider was sent holds the desktop key");
});

check("the desktop key is kept in splice's state folder", async () => {
  const { s, d } = await open();
  must(holders(s.state, d.key).length > 0, "no file under splice's state folder holds the desktop key");
});

check("splice's own copy of the desktop key is for its owner only", async () => {
  const { s, d } = await open();
  const held = holders(s.state, d.key);
  must(held.length > 0, "no file under splice's state folder holds the desktop key");
  const open_ = held.filter((f) => (statSync(join(s.state, f)).mode & 0o077) !== 0);
  must(open_.length === 0, `others than its owner can reach ${open_.map((f) => `${f} (${(statSync(join(s.state, f)).mode & 0o777).toString(8)})`).join(", ")}`);
});

// ---------- splice rotate-key ----------

check("splice rotate-key leaves the desktop key as it is", async () => {
  const { s, d } = await open();
  const r = s.cli(["rotate-key"]);
  must(r.code === 0, `splice rotate-key exited ${r.code}: ${r.err.slice(0, 200)}`);
  must(doorOf(s).key === d.key, "the settings file holds another desktop key after splice rotate-key");
  const { t } = await presenting(d, d.key);
  must(t.status === 200, `a turn that presents the desktop key was answered ${answer(t)} after splice rotate-key`);
});

// ---------- splice desktop rotate-key, with Desktop on ----------

/** `splice desktop rotate-key` with Desktop on: the key before it, and the door as the file gives it after. */
const rotated = once(async (): Promise<{ s: Splice; old: Door; next: Door }> => {
  const { s, d } = await open();
  must(fileEnv(s).CLAUDE_CODE_USE_MANTLE === "1", "Desktop is not on");
  desktop(s, "rotate-key");
  return { s, old: d, next: doorOf(s) };
});

check("splice desktop rotate-key writes a new desktop key into the settings file when Desktop is on", async () => {
  const { old, next } = await rotated();
  must(next.key !== "" && next.key !== old.key, "the settings file holds the key it held before");
  must(next.url === old.url, `the door moved from ${old.url} to ${next.url}`);
});

check("the new desktop key opens the door", async () => {
  const { next } = await rotated();
  const { t } = await presenting(next, next.key);
  must(t.status === 200, `it was answered ${answer(t)}`);
});

refusedKey(
  "a replaced key",
  once(async () => {
    const { old, next } = await rotated();
    must(next.key !== old.key, "splice desktop rotate-key did not replace the key");
    return presenting(next, old.key);
  }),
);

// ---------- splice desktop rotate-key, with Desktop off ----------

/** Desktop off, then `splice desktop rotate-key`, then on again: what the file held in between, and the keys. */
const rotatedOff = once(async () => {
  const { s, next } = await rotated();
  desktop(s, "off");
  const offBefore = settings(s);
  desktop(s, "rotate-key");
  const offAfter = settings(s);
  const again = desktopOn(s);
  return { s, earlier: next, offBefore, offAfter, again };
});

check("with Desktop off, splice desktop rotate-key writes nothing into the settings file", async () => {
  const { offBefore, offAfter } = await rotatedOff();
  same(offAfter, offBefore, "what the file holds after splice desktop rotate-key with Desktop off");
  must(!("AWS_BEARER_TOKEN_BEDROCK" in ((offAfter.env ?? {}) as Record<string, unknown>)), "the file holds a desktop key while Desktop is off");
});

check("a key replaced while Desktop was off is refused once Desktop is on again", async () => {
  const { earlier, again } = await rotatedOff();
  must(again.key !== earlier.key, "on wrote the key that splice desktop rotate-key replaced");
  const good = await presenting(again, again.key);
  must(good.t.status === 200, `a turn that presents the key on wrote was answered ${answer(good.t)}`);
  refused((await presenting(again, earlier.key)).t, 401, "authentication_error");
});

// ---------- the daemon's log ----------

check("the daemon's log holds no desktop key", async () => {
  const { s, d } = await open();
  const keys = new Set([d.key, fileEnv(s).AWS_BEARER_TOKEN_BEDROCK ?? d.key]);
  try {
    keys.add((await rotated()).next.key);
  } catch {}
  const log = readFileSync(s.log, "utf8");
  const logged = [...keys].filter((k) => k !== "" && log.includes(k));
  must(logged.length === 0, `the daemon's log holds ${logged.length} of the ${keys.size} desktop keys the door has had`);
});

await run(async () => {
  await live?.dispose();
  astra.stop();
});
