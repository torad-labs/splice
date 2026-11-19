// cp-tls: a provider reached through a company's TLS-inspecting proxy works. splice trusts the roots Java ships with,
// the operating system's own trust store (on Linux the bundle SSL_CERT_FILE names, else the distribution's bundle),
// and a provider's own `ca_file`. A turn whose provider presents a certificate none of them trusts fails with a message
// that names the provider and ca_file, so the person knows the fix, instead of a bare PKIX error.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { ask, session, toDoor } from "../door/lib/claude.ts";
import { MODEL, errorOf } from "./lib/team.ts";
import { type Authority, authority } from "./lib/tls.ts";

setDefaultTimeout(180_000);

let ca: Authority;
let model: Upstream;
let front: ReturnType<typeof Bun.serve>;

/** The team's provider behind the inspecting proxy's certificate, on an API key. */
async function provider(extra = ""): Promise<string> {
  return `
[providers.acme]
dialect = "openai-chat"
base_url = "https://127.0.0.1:${front.port}/v1"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
local = false
${extra}

[[providers.acme.models]]
id = "${MODEL}"
context_window = 65536

[heads.acme]
provider = "acme"
port = ${await freePort()}
discovery_prefix = "claude-acme--"
pinned_model = "${MODEL}"
`;
}

/** Boot on `toml` with `env`, send one door turn on the team's model, and stop. */
async function oneTurn(toml: string, env: Record<string, string> = {}) {
  const s = await Splice.boot(toml, env);
  try {
    toDoor(s);
    const t = await ask(await session(s), { model: `claude-acme--${MODEL}` });
    return { status: t.status, reply: t.reply, error: errorOf(t) };
  } finally {
    await s.dispose();
  }
}

beforeAll(() => {
  ca = authority();
  model = new Upstream("acme", MODEL).start();
  // the proxy: TLS with a certificate the company's own root signed, then on to the model server
  front = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    tls: { cert: readFileSync(ca.cert, "utf8"), key: readFileSync(ca.key, "utf8") },
    fetch: async (req) => {
      const u = new URL(req.url);
      const res = await fetch(`${model.url}${u.pathname.replace(/^\/v1/, "")}`, { method: req.method, headers: { "content-type": "application/json" }, body: req.method === "POST" ? await req.text() : undefined });
      return new Response(res.body, { status: res.status, headers: { "content-type": res.headers.get("content-type") ?? "application/json" } });
    },
  });
});

afterAll(() => {
  front?.stop(true);
  model?.stop();
  ca?.dispose();
});

describe("cp-tls", () => {
  test("a certificate nothing trusts fails the turn with a message naming the provider and ca_file", async () => {
    const t = await oneTurn(await provider());
    expect(t.status === 200 && t.error === null).toBe(false);
    expect(t.error).not.toBeNull();
    expect(t.error!.message).toContain("acme");
    expect(t.error!.message).toContain("ca_file");
  });

  test("the provider's ca_file makes it trusted", async () => {
    const t = await oneTurn(await provider(`ca_file = "${ca.ca}"`));
    expect(t.status, JSON.stringify(t.error)).toBe(200);
    expect(t.error).toBeNull();
    expect(t.reply).toBe(model.reply());
  });

  test("a root in the operating system's trust store is trusted with no ca_file", async () => {
    const t = await oneTurn(await provider(), { SSL_CERT_FILE: ca.ca });
    expect(t.status, JSON.stringify(t.error)).toBe(200);
    expect(t.error).toBeNull();
    expect(t.reply).toBe(model.reply());
  });
});
