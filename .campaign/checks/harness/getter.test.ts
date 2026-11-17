// getter-runs: the Context Getter, switched on for a head, gathers for a session's first main interactive turn. It
// starts a `claude -p` in the session's folder on the head's fast model (its small tier, or `worker.model`), thinking
// at that model's own level (the person's setting for it, else Claude Code's own default, never the head's effort or a
// launcher's), with Claude Code's tool-use summaries on, and the first user message in its input. Its final result
// goes into the first user message of that request and of every later one, byte for byte. Its own requests name the
// session they work for and are never gathered for. It fails open, and it is not called, with the reason recorded,
// when the session's folder cannot be found or the head has no login for the fast model.
// (docs/specs/harness.md, decisions 2, 5, 6, 9 and 10.)

import { afterAll, afterEach, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { KEYS, blocks, opening, rows, second, send } from "./lib/capability.ts";
import { Backend, Fake, type Run, contextGetter, folder, labelledHead, naming, personSettings, recorded, register, sendWith, textOf } from "./lib/gatherer.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA = { large: { id: "astra-large", label: "Astra Large" }, small: { id: "astra-small", label: "Astra Small" } };
const BOREALIS = {
  large: { id: "borealis-large", label: "Borealis Large" },
  small: { id: "borealis-small", label: "Borealis Small" },
  more: [{ id: "borealis-mini", label: "Borealis Mini" }],
};

let astraUp: Backend;
let borealisUp: Backend;
let cirrusUp: Backend;
let fake: Fake;
let s: Splice;
let astra: Launched;
let borealis: Launched;
let cirrus: Launched;
let n = 0;

beforeAll(async () => {
  astraUp = new Backend("astra").start();
  borealisUp = new Backend("borealis").start();
  cirrusUp = new Backend("cirrus").start();
  fake = new Fake();
  s = await Splice.create(
    labelledHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astraUp.url, ...ASTRA }) +
      // a head that thinks at xhigh: its sessions start at --effort xhigh, and its gatherer must not
      labelledHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealisUp.url, ...BOREALIS, extra: `overrides = { effort = "xhigh" }` }) +
      // a head with no login at all: its key is in no environment
      labelledHead({
        key: "cirrus",
        command: "claude-cirrus",
        port: await freePort(),
        url: cirrusUp.url,
        large: { id: "cirrus-large", label: "Cirrus Large" },
        small: { id: "cirrus-small", label: "Cirrus Small" },
        keyEnv: "CIRRUS_UPSTREAM_KEY",
      }) +
      // mode any: a gatherer's own print-mode request meets the rule, so only splice's own condition keeps it out
      contextGetter(["astra", "cirrus"], `when = { mode = "any" }`) +
      `
[capabilities.scout]
heads = ["borealis"]
call = "rule"
when = { turn = "first", thread = "main", mode = "interactive" }
place = "first-user-message"
may = "add"
message = "Scouting the folder"
color = "cyan"
timeout_s = 60
on_failure = "open"
worker = { kind = "builtin", name = "context-getter", model = "borealis-mini" }
`,
    { ...KEYS, PATH: fake.path },
  );
  // The person's own Claude Code settings: an effort for the astra head's fast model, under either name a session
  // may know it by, and none for any borealis model.
  personSettings(s, { modelSettings: { "astra-small": { effortLevel: "low" }, "claude-astra--astra-small": { effortLevel: "low" } } });
  await s.start();
  astra = await launch(s, "claude-astra");
  borealis = await launch(s, "claude-borealis");
  cirrus = await launch(s, "claude-cirrus");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  for (const each of [astraUp, borealisUp, cirrusUp]) each?.stop();
  fake?.dispose();
});

afterEach(() => fake.reset());

/** A first turn of a new session on `l`, whose folder Claude Code has registered (unless `registered` is false). */
async function opened(l: Launched, typed: string, registered = true) {
  const session = crypto.randomUUID();
  const cwd = folder(s, `p${++n}`);
  if (registered) register(l.dir, session, cwd, typed);
  const mine = opening(typed);
  const sent = await send(l, session, [{ role: "user", content: mine }]);
  return { session, cwd, mine, sent };
}

/** The one gatherer run for `typed`. */
function only(typed: string): Run {
  const runs = fake.of(typed);
  expect(runs.length).toBe(1);
  return runs[0]!;
}

describe("getter-runs", () => {
  test("a first main interactive turn starts one gatherer, in the session's folder, on the head's fast model, with the first message in its input", async () => {
    const typed = "Add a --json flag to the status command.";
    const { cwd, sent } = await opened(astra, typed);
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(astraUp.reply());

    const run = only(typed);
    expect(run.print).toBe(true);
    expect(run.cwd).toBe(cwd);
    expect(run.input).toContain(typed);
    // Claude Code's own tool-use summaries are on
    expect(run.summaries).toBe(true);
    // it reached the head's provider, on the head's small tier
    expect(run.request?.status).toBe(200);
    const asked = astraUp.of(run.session);
    expect(asked.length).toBeGreaterThanOrEqual(1);
    expect(asked[0]!.body.model).toBe("astra-small");
    // it thinks at the level the person's settings name for that model
    expect(run.effort).toBe("low");
  });

  test("its result goes into the first user message, after the client's blocks, on that request and every later one, byte for byte", async () => {
    const typed = "Rename the --tail flag of the logs command to --last.";
    const { session, mine } = await opened(astra, typed);
    const run = only(typed);
    const first = astraUp.of(session);
    expect(first.length).toBe(1);
    const content = blocks(first[0]!.body.messages[0]);
    expect(content.length).toBe(mine.length + 1);
    expect(content.slice(0, mine.length).map((b) => b.text)).toEqual(mine.map((b) => b.text));
    const added = content.at(-1)!;
    expect(added.type).toBe("text");
    expect(added.text ?? "").toContain(`What the gatherer found (run ${run.run})`);

    expect((await send(astra, session, second(mine))).status).toBe(200);
    const later = astraUp.of(session);
    expect(later.length).toBe(2);
    const again = blocks(later[1]!.body.messages[0]).at(-1)!;
    expect(JSON.stringify({ type: again.type, text: again.text })).toBe(JSON.stringify({ type: added.type, text: added.text }));
    // the later turn gathered nothing more
    expect(fake.of(typed).length).toBe(1);

    const [row] = await rows(s, "astra", session, 1);
    expect(String(row!.held_on ?? "")).toContain("context-getter");
    expect(Number(row!.capability_text_chars)).toBeGreaterThan(0);
  });

  test("worker.model names the fast model, and the gatherer thinks at that model's own level, never the head's effort or a launcher's", async () => {
    const typed = "Why does the daemon's log rotate at midnight?";
    const { cwd, sent } = await opened(borealis, typed);
    expect(sent.status).toBe(200);
    const run = only(typed);
    expect(run.cwd).toBe(cwd);
    expect(borealisUp.of(run.session)[0]?.body.model).toBe("borealis-mini");
    // the person's settings name no effort for it, so it runs at Claude Code's own default: no --effort, no
    // effort word in its environment, whatever the head's effort is
    expect(run.effort).toBeNull();
    expect(run.argv.join(" ")).not.toMatch(/--effort/);
  });

  test("the gatherer's requests name the session they work for, and are never gathered for", async () => {
    const typed = "Explain the retry ladder.";
    const { session } = await opened(astra, typed);
    // one gatherer: its own request, which meets the rule here, started no other
    const run = only(typed);
    const named = naming(run, session).filter(([name]) => /splice/i.test(name));
    expect(named.length).toBe(1);

    // the provider got the gatherer's request as the gatherer sent it, with nothing added
    const own = astraUp.of(run.session);
    expect(own.length).toBe(1);
    const content = blocks(own[0]!.body.messages[0]);
    expect(content.length).toBe(1);
    expect(textOf(content)).toStartWith(`[gatherer ${run.run}]`);

    // its record names the session it worked for, so the two can be joined
    const [row] = await rows(s, "astra", run.session, 1);
    expect(Object.entries(row!).some(([k, v]) => k !== "session_id" && v === session)).toBe(true);

    // an interactive first turn carrying that header, of a session whose folder is known, is not gathered for either
    const [header] = named[0]!;
    const other = crypto.randomUUID();
    const cwd = folder(s, `p${++n}`);
    const otherTyped = "List the heads splice runs.";
    register(astra.dir, other, cwd, otherTyped);
    const mine = opening(otherTyped);
    const sent = await sendWith(astra, other, [{ role: "user", content: mine }], { [header]: session });
    expect(sent.status).toBe(200);
    expect(fake.of(otherTyped).length).toBe(0);
    expect(blocks(astraUp.of(other)[0]!.body.messages[0]).map((b) => b.text)).toEqual(mine.map((b) => b.text));
  });

  test("it fails open: a gatherer that exits non-zero or ends with no result leaves the turn as it is today", async () => {
    for (const mode of ["fail", "empty"] as const) {
      const typed = `Add a --since flag to the logs command (${mode}).`;
      fake.when(typed, { mode });
      const { session, mine, sent } = await opened(astra, typed);
      expect(sent.status, mode).toBe(200);
      expect(sent.error, mode).toBeNull();
      expect(sent.body, mode).toContain(astraUp.reply());
      expect(fake.of(typed).length, mode).toBe(1);
      expect(blocks(astraUp.of(session)[0]!.body.messages[0]).map((b) => b.text), mode).toEqual(mine.map((b) => b.text));

      // nothing is added later, and the session is not gathered for again
      expect((await send(astra, session, second(mine))).status, mode).toBe(200);
      expect(blocks(astraUp.of(session).at(-1)!.body.messages[0]).map((b) => b.text), mode).toEqual(mine.map((b) => b.text));
      expect(fake.of(typed).length, mode).toBe(1);
    }
  });

  test("a session whose folder cannot be found is not gathered for, and the reason is recorded", async () => {
    const from = s.logTail(Number.MAX_SAFE_INTEGER).length;
    const typed = "Add a --json flag to the doctor command.";
    const { session, mine, sent } = await opened(astra, typed, false);
    expect(sent.status).toBe(200);
    expect(sent.body).toContain(astraUp.reply());
    expect(fake.of(typed).length).toBe(0);
    expect(blocks(astraUp.of(session)[0]!.body.messages[0]).map((b) => b.text)).toEqual(mine.map((b) => b.text));
    let line: string | null = null;
    await until("a record of why it was not called", async () => (line = await recorded(s, { key: "astra", session, reason: /folder/i, from })) !== null);
    expect(line).not.toBeNull();
  });

  test("a head with no login for its fast model is not gathered for, and the reason is recorded", async () => {
    const from = s.logTail(Number.MAX_SAFE_INTEGER).length;
    const typed = "Add a --json flag to the version command.";
    const { session } = await opened(cirrus, typed);
    // the turn itself is whatever it is today on a head with no login; only the gatherer is in question here
    await Bun.sleep(2000);
    expect(fake.of(typed).length).toBe(0);
    let line: string | null = null;
    await until("a record of why it was not called", async () => (line = await recorded(s, { key: "cirrus", session, reason: /login|sign|key/i, from })) !== null);
    expect(line).not.toBeNull();
  });
});
