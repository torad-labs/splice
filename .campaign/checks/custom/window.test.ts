// cp-window: a `claude` on the door holds one window for the session (CLAUDE_CODE_MAX_CONTEXT_TOKENS, the lane's), and
// the model picked has its own. The door reports a turn's counts as the same share of the session's window that they
// are of the picked model's own, so auto-compaction follows the pick (splice CLAUDE.md section 22: splice writes the
// counts, so it scales them). A provider may name where it reports the window it really serves, `served_window = {
// url, field }`, read once at start: that window is the model's then, since it is the real ceiling. A served window
// that cannot be read leaves the declared one in force, and splice doctor says so.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { reported } from "../desktop/lib/desktop.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { ask, session, toDoor } from "../door/lib/claude.ts";
import { Gateway, MODEL, TeamCli } from "./lib/team.ts";

setDefaultTimeout(120_000);

const DECLARED = 65_536;
const SERVED = 32_768;

let cli: TeamCli;
let served: Gateway;
let declared: Gateway;
let broken: Gateway;
let s: Splice;

function provider(key: string, gw: Gateway, port: number, extra = ""): string {
  return `
[providers.${key}]
dialect = "openai-chat"
base_url = "${gw.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
# a team gateway on another machine: no local server probe
local = false
# a hosted api-key provider reports usage only when asked (QuirksOverlay, V4-163); the counts are what is scaled
quirks = { stream_usage = true }
${extra}

[[providers.${key}.models]]
id = "${MODEL}"
context_window = ${DECLARED}

[heads.${key}]
provider = "${key}"
port = ${port}
discovery_prefix = "claude-${key}--"
pinned_model = "${MODEL}"
`;
}

/** The share of the session's window one door turn on `key`'s model reports. */
async function share(key: string): Promise<number> {
  const x = await session(s);
  const window = Number(x.sets.CLAUDE_CODE_MAX_CONTEXT_TOKENS);
  expect(window).toBeGreaterThan(0);
  const t = await ask(x, { model: `claude-${key}--${MODEL}` });
  expect(t.status).toBe(200);
  return reported(t) / window;
}

beforeAll(async () => {
  cli = new TeamCli();
  // each reports a prompt that fills half of the window its model really has
  served = new Gateway(cli, { usage: { prompt: SERVED / 2, cached: 0, completion: 40 } }).start();
  declared = new Gateway(cli, { usage: { prompt: DECLARED / 2, cached: 0, completion: 40 } }).start();
  broken = new Gateway(cli, { usage: { prompt: DECLARED / 2, cached: 0, completion: 40 } }).start();
  for (const g of [served, declared, broken]) g.open = true;
  served.servedWindow = SERVED;
  s = await Splice.boot(
    provider("served", served, await freePort(), `served_window = { url = "${served.propsUrl}", field = "default_generation_settings.n_ctx" }`) +
      provider("declared", declared, await freePort()) +
      provider("broken", broken, await freePort(), `served_window = { url = "${broken.url}/no-such-route", field = "default_generation_settings.n_ctx" }`),
  );
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  for (const g of [served, declared, broken]) g?.stop();
  cli?.dispose();
});

describe("cp-window", () => {
  test("a picked model's declared window is the one the session's counts are scaled to", async () => {
    expect(await share("declared")).toBeCloseTo(0.5, 1);
  });

  test("a served window read at start is the model's window", async () => {
    expect(await share("served")).toBeCloseTo(0.5, 1);
  });

  test("a served window that cannot be read leaves the declared one, and splice doctor says so", async () => {
    expect(await share("broken")).toBeCloseTo(0.5, 1);
    const r = s.cli(["doctor"]);
    const text = `${r.out}\n${r.err}`;
    expect(text).toContain("broken");
    expect(text).toContain("served_window");
  });
});
