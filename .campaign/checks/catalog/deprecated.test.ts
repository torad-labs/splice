// gw-deprecated: whoever still starts a head's own command, or a `claude` in a head's own Claude folder, reads one line
// before Claude Code starts: custom heads are deprecated and will be removed in a coming release, and what to type
// instead (`claude`, then `/model <the head's selector>`, with `splice claude door` first where `claude` is not on the
// Splice gateway). The head still works in this release. `claude` in the person's own folder says nothing of it.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { rmSync } from "node:fs";
import { join } from "node:path";
import { lane, toDoor } from "../door/lib/claude.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { gatewayTurn, launchOf, mustGateway } from "./lib/gateway.ts";
import { claudeHeadToml } from "./lib/heads.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

const ASTRA = "claude-astra--gpt-6-astra";
const BOREALIS = "claude-borealis--borealis-large";
const DEPRECATED = /custom heads are deprecated and will be removed in a coming release/i;
const MOVE = "splice claude door";

const logins = `/tmp/claude-1000/catalog-deprecated-${process.pid}`;
let backend: CodexBackend;
let borealis: Upstream;
let anthropic: Provider;
let s: Splice;

beforeAll(async () => {
  backend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  borealis = new Upstream("borealis", "borealis-large").start();
  anthropic = new Provider("anthropic").start();
  s = await Splice.create(
    claudeHeadToml({ port: await freePort(), url: anthropic.url }) +
      gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: backend.url, model: "gpt-6-astra", dir: logins }) +
      mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, model: "borealis-large" }),
    gptEnv(backend.url),
  );
  await s.start();
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  backend?.stop();
  borealis?.stop();
  anthropic?.stop();
  rmSync(logins, { recursive: true, force: true });
});

/** The one line, as the shim prints it: a notice of more than one line is not one line. */
function oneLine(warning: string): string {
  const lines = warning.split("\n").filter((l) => DEPRECATED.test(l));
  expect(lines.length).toBe(1);
  return lines[0]!;
}

describe("gw-deprecated", () => {
  test("with claude on the gateway, a head's launch says heads go in a coming release and what to type in claude", async () => {
    toDoor(s);
    for (const [command, selector] of [["claude-astra", ASTRA], ["claude-borealis", BOREALIS]] as const) {
      const line = oneLine((await launchOf(s, command)).warning);
      expect([command, line.includes("`claude`"), line.includes(`/model ${selector}`), line.includes(MOVE)]).toEqual([command, true, true, false]);
    }
  });

  test("with claude on its head, the line says splice claude door comes first", async () => {
    expect(s.cli(["claude", "head"]).code).toBe(0);
    expect(lane(s).lane).toBe("head");
    const line = oneLine((await launchOf(s, "claude-astra")).warning);
    expect(line).toContain(MOVE);
    expect(line).toContain(`/model ${ASTRA}`);
    toDoor(s);
  });

  test("claude started in a head's own Claude folder says the same line, naming that head's model", async () => {
    await launchOf(s, "claude-astra");
    const x = await launchOf(s, "claude", { inheritedConfigDir: join(s.home, ".claude-astra") });
    const line = oneLine(x.warning);
    expect(line).toContain(`/model ${ASTRA}`);
  });

  test("claude in the person's own folder says nothing of it", async () => {
    expect((await launchOf(s, "claude")).warning).not.toMatch(DEPRECATED);
  });

  test("the head still works in this release", async () => {
    const x = await launchOf(s, "claude-astra");
    const marker = `gw-deprecated-${crypto.randomUUID()}`;
    expect((await gatewayTurn(mustGateway(x), ASTRA, { content: marker })).status).toBe(200);
    expect(backend.turnsWith(marker).length).toBe(1);
  });
});
