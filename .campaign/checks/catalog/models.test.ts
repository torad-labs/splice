// gw-models: the Splice gateway answers Claude Code's model discovery itself, at the address a session is pointed at:
// `GET /v1/models?limit=1000` with the session's credential, at once, no redirect, every model it routes once, in the
// order `splice claude --json` lists them, each id one Claude Code keeps (it contains `claude` or `anthropic`), and each
// id one the gateway runs when a turn names it. A Claude model is listed by its own Anthropic id, so Claude Code folds
// it into the built-in row that names it; every other model by its selector. A sign-in or a sign-out reaches the next
// listing with no restart.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { rmSync } from "node:fs";
import { join } from "node:path";
import { lane, toDoor } from "../door/lib/claude.ts";
import { Splice, freePort, mockHead, until } from "../server/lib/harness.ts";
import { Provider } from "../harness/lib/capability.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { CodexBackend, gptEnv, gptHead } from "../speed/lib/codex.ts";
import { addClaudeAccount, claudeHeadToml, prefixedHead } from "./lib/heads.ts";
import { type ModelRow, gatewayTurn, kept, launchOf, modelsOf } from "./lib/gateway.ts";

// No test here is a claim about speed but the one that says so: a busy machine must not fail one on bun's five seconds.
setDefaultTimeout(120_000);

const ASTRA = "claude-astra--gpt-6-astra";
const TERRA = "claude-terra--gpt-6.1-terra";
const BOREALIS = "claude-borealis--borealis-large";
// A head whose discovery prefix names no Claude: Claude Code would drop its selector from a discovery answer.
const ZETA = "zeta--zeta-large";

const logins = `/tmp/claude-1000/catalog-models-${process.pid}`;
let astraBackend: CodexBackend;
let terraBackend: CodexBackend;
let borealis: Upstream;
let zeta: Upstream;
let anthropic: Provider;
let s: Splice;
let terraPort = 0;
let gw: { base: string; key: string };

const terra = () => gptHead({ key: "terra", command: "claude-terra", port: terraPort, backend: terraBackend.url, model: "gpt-6.1-terra", dir: logins });

/** The id the gateway lists for a model `splice claude --json` lists: a Claude model of the claude head by its own
 *  Anthropic id, a selector that names no Claude with `claude-` before it, any other by its selector. */
function idOf(m: { model: string; head: string }): string {
  if (m.head === "claude") return m.model.replace(/^claude--/, "");
  return /claude|anthropic/i.test(m.model) ? m.model : `claude-${m.model}`;
}

const ids = (rows: ModelRow[]) => rows.map((r) => r.id);

beforeAll(async () => {
  astraBackend = CodexBackend.start([{ slug: "gpt-6-astra", tiers: [] }]);
  terraBackend = CodexBackend.start([{ slug: "gpt-6.1-terra", tiers: [] }]);
  borealis = new Upstream("borealis", "borealis-large").start();
  zeta = new Upstream("zeta", "zeta-large").start();
  anthropic = new Provider("anthropic").start();
  terraPort = await freePort();
  const toml =
    claudeHeadToml({ port: await freePort(), url: anthropic.url }) +
    gptHead({ key: "astra", command: "claude-astra", port: await freePort(), backend: astraBackend.url, model: "gpt-6-astra", dir: logins }) +
    mockHead({ key: "borealis", command: "claude-borealis", port: await freePort(), url: borealis.url, model: "borealis-large" }) +
    prefixedHead({ key: "zeta", command: "zeta", prefix: "zeta--", port: await freePort(), url: zeta.url, model: "zeta-large" }) +
    terra();
  s = await Splice.create(toml, gptEnv(astraBackend.url));
  addClaudeAccount(s, "claude", "work", "sk-ant-oat01-added-work-account");
  await s.start();
  toDoor(s);
  // the terminal's key and the gateway's address, as a launch of `claude` hands them to Claude Code (Mantle's names
  // before gateway mode, gateway mode's after)
  const x = await launchOf(s, "claude");
  gw = {
    base: x.sets.ANTHROPIC_BEDROCK_MANTLE_BASE_URL ?? x.sets.ANTHROPIC_BASE_URL ?? "",
    key: x.sets.AWS_BEARER_TOKEN_BEDROCK ?? x.sets.ANTHROPIC_AUTH_TOKEN ?? "",
  };
  expect(gw.base).toBe(lane(s).door!);
  await until("every head is in the gateway", () => (lane(s).models ?? []).some((m) => m.model === TERRA) && (lane(s).models ?? []).some((m) => m.head === "claude"), 60_000);
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  for (const u of [borealis, zeta]) u?.stop();
  anthropic?.stop();
  astraBackend?.stop();
  terraBackend?.stop();
  rmSync(logins, { recursive: true, force: true });
});

describe("gw-models", () => {
  test("the gateway answers discovery with the session's credential at once, in Anthropic's list shape, with no redirect", async () => {
    const r = await modelsOf(gw.base, gw.key);
    expect(r.redirected).toBe(false);
    expect(r.status).toBe(200);
    // Claude Code waits 3 s for this answer and then falls back to an older list
    expect(r.ms).toBeLessThan(3_000);
    const body = JSON.parse(r.body) as { data: Record<string, unknown>[]; has_more: boolean; first_id: string | null; last_id: string | null };
    expect(body.has_more).toBe(false);
    expect(body.first_id).toBe(String(body.data[0]?.id));
    expect(body.last_id).toBe(String(body.data[body.data.length - 1]?.id));
    for (const row of body.data) {
      expect([row.id, row.type]).toEqual([row.id, "model"]);
      expect(typeof row.display_name).toBe("string");
      expect(Number.isNaN(Date.parse(String(row.created_at)))).toBe(false);
    }
  });

  test("every model the gateway routes is listed once, in splice claude's order, each id one Claude Code keeps", async () => {
    const rows = (await modelsOf(gw.base, gw.key)).rows;
    const models = lane(s).models ?? [];
    expect(ids(rows)).toEqual(models.map(idOf));
    expect(new Set(ids(rows)).size).toBe(rows.length);
    expect(ids(kept(rows))).toEqual(ids(rows));
    // labelled as splice claude labels them, each with the command whose head serves it
    rows.forEach((row, i) => {
      expect(row.display_name).toBe(models[i]!.label);
      expect(row.description ?? "").toContain(`splice · ${models[i]!.command}`);
    });
  });

  test("a Claude model is listed by its own Anthropic id, never under a selector beside it", async () => {
    const listed = ids((await modelsOf(gw.base, gw.key)).rows);
    expect(listed).toContain("claude-opus-5-5");
    expect(listed).not.toContain("claude--claude-opus-5-5");
    expect(listed).toContain(ASTRA);
    expect(listed).toContain(BOREALIS);
  });

  test("each listed id runs when a turn names it, and a selector of today's form still does", async () => {
    const listed = ids((await modelsOf(gw.base, gw.key)).rows);
    expect(listed).toContain(`claude-${ZETA}`);
    const before = zeta.completions().length;
    expect((await gatewayTurn(gw, `claude-${ZETA}`)).status).toBe(200);
    expect((await gatewayTurn(gw, ZETA)).status).toBe(200);
    expect(zeta.completions().length).toBe(before + 2);
    const b = borealis.completions().length;
    expect((await gatewayTurn(gw, BOREALIS)).status).toBe(200);
    expect(borealis.completions().length).toBe(b + 1);
  });

  test("a key the gateway does not hold is refused, and lists nothing", async () => {
    const r = await modelsOf(gw.base, "not-a-key-splice-holds");
    expect(r.status).toBeGreaterThanOrEqual(400);
    expect(r.rows).toEqual([]);
  });

  test("a sign-out and a sign-in reach the next listing, with no restart", async () => {
    rmSync(join(logins, "terra-auth.json"));
    await until("the signed-out head leaves the list", async () => !ids((await modelsOf(gw.base, gw.key)).rows).includes(TERRA), 30_000);
    terra();
    await until("the signed-in head is listed again", async () => ids((await modelsOf(gw.base, gw.key)).rows).includes(TERRA), 30_000);
  }, 90_000);
});
