// cp-errors: what a team's gateway says reaches the person. A 503 that carries Retry-After (a model that is loading)
// is answered to Claude Code at once with that status class and that Retry-After, as an overloaded_error, so Claude
// Code waits and retries as it does with Anthropic, and splice does not hold or retry the turn itself. A provider that
// sets `error_messages = "whole"` has its own error messages shown as it wrote them, whole, on a 503 and on a 4xx (an
// outdated client told to update), instead of cut to their first readable sentence. A provider that does not set it
// keeps splice's filter.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort, type Turn } from "../server/lib/harness.ts";
import { ask, session, toDoor } from "../door/lib/claude.ts";
import { Gateway, MODEL, TeamCli, errorOf } from "./lib/team.ts";

setDefaultTimeout(120_000);

const LOADING = "GLM-5.3-Flash is loading on the Acme cluster. About 40 seconds are left; please wait and try again.";
const OUTDATED =
  "Your acme-ai CLI 1.2.0 is out of date. Run `acme-ai update` to get 1.4.0, then start claude again. Details: https://wiki.acme.example/ai/update.";

let cli: TeamCli;
let whole: Gateway;
let filtered: Gateway;
let s: Splice;

function provider(key: string, url: string, port: number, extra = ""): string {
  return `
[providers.${key}]
dialect = "openai-chat"
base_url = "${url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
local = false
${extra}

[[providers.${key}.models]]
id = "${MODEL}"
context_window = 65536

[heads.${key}]
provider = "${key}"
port = ${port}
discovery_prefix = "claude-${key}--"
pinned_model = "${MODEL}"
`;
}

async function turn(key: string, marker: string): Promise<Turn> {
  return ask(await session(s), { model: `claude-${key}--${MODEL}`, content: marker });
}

beforeAll(async () => {
  cli = new TeamCli();
  whole = new Gateway(cli).start();
  whole.open = true;
  filtered = new Gateway(cli).start();
  filtered.open = true;
  s = await Splice.boot(
    provider("acme", whole.url, await freePort(), 'error_messages = "whole"') + provider("plain", filtered.url, await freePort()),
  );
  toDoor(s);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  whole?.stop();
  filtered?.stop();
  cli?.dispose();
});

describe("cp-errors", () => {
  test("a 503 with Retry-After reaches Claude Code at once as an overload with that Retry-After, tried once", async () => {
    for (const [key, gw] of [["acme", whole], ["plain", filtered]] as const) {
      gw.next.push({ status: 503, message: LOADING, headers: { "retry-after": "7" } });
      const marker = `cp-errors-503-${key}-${crypto.randomUUID()}`;
      const t = await turn(key, marker);
      expect([503, 529]).toContain(t.status);
      expect(t.headers.get("retry-after")).toBe("7");
      expect(errorOf(t)?.type).toBe("overloaded_error");
      expect(gw.turnsWith(marker).length).toBe(1);
    }
  });

  test("with error_messages = whole, the 503's own message is shown whole", async () => {
    whole.next.push({ status: 503, message: LOADING, headers: { "retry-after": "7" } });
    const t = await turn("acme", `cp-errors-503-words-${crypto.randomUUID()}`);
    expect(errorOf(t)?.message ?? "").toContain(LOADING);
  });

  test("with error_messages = whole, a 4xx the gateway wrote is shown whole, and not retried", async () => {
    whole.next.push({ status: 426, message: OUTDATED });
    const marker = `cp-errors-426-${crypto.randomUUID()}`;
    const t = await turn("acme", marker);
    expect(t.status).toBeGreaterThanOrEqual(400);
    expect(t.status).toBeLessThan(500);
    expect(errorOf(t)?.message ?? "").toContain(OUTDATED);
    expect(whole.turnsWith(marker).length).toBe(1);
  });

  test("a provider that does not ask keeps splice's filter on its messages", async () => {
    filtered.next.push({ status: 426, message: OUTDATED });
    const t = await turn("plain", `cp-errors-426-plain-${crypto.randomUUID()}`);
    expect(t.status).toBeGreaterThanOrEqual(400);
    expect(errorOf(t)?.message ?? "").not.toContain("https://wiki.acme.example");
  });
});
