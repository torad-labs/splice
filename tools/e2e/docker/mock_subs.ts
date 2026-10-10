#!/usr/bin/env bun
/** The two subscription vendors a real 0.3.2 user had signed in to, as one loopback upstream for the
 *  upgrade rehearsal (tools/e2e/docker/upgrade.sh): xAI's browser OAuth (Grok) and Moonshot's device
 *  flow (Kimi). Each sign-in ends in a credential file THE OLD RELEASE WRITES ITSELF, and each head's
 *  upstream is served here too, so a turn after the upgrade shows the new daemon READ that file —
 *  not merely that the file survived the install.
 *
 *  What it serves
 *    POST /grok/oauth2/token                     the authorization-code exchange, and later refreshes
 *    ANY  /grok/<rest>                           the Grok head's upstream: the bearer is checked
 *                                                against the token this mock issued, then the request
 *                                                is proxied verbatim to the vendored responses mock
 *                                                (MOCK_SUBS_CODEX_ORIGIN), whose wire this harness
 *                                                already trusts — nothing here re-implements a dialect
 *    POST /kimi/api/oauth/device_authorization   RFC 8628 device authorization (the kimi login's first call)
 *    POST /kimi/api/oauth/token                  the device-code poll (pending once, then tokens), and refreshes
 *    POST /kimi/v1/messages                      the Kimi head's upstream — x-api-key, NOT a bearer
 *                                                (KimiAuthProvider) — answered with an Anthropic stream
 *                                                and the unified rate-limit headers a quota file is
 *                                                written from, so the 0.3.2 turns leave a real reading
 *
 *  NO TOKEN VALUE IS EVER PRINTED OR LOGGED. Every request is one JSON line in MOCK_SUBS_LOG with the
 *  credential reported as a LABEL: `current` (the token this mock last issued for that vendor),
 *  `stale` (one it issued earlier), `absent`, or `other`. The assertions read the labels, so a receipt
 *  can never carry a key even though every token here is synthetic.
 *
 *  Usage: mock_subs.ts <port>  — prints {"port": N, "log": path} once listening, then serves.
 *  Env: MOCK_SUBS_LOG (required), MOCK_SUBS_CODEX_ORIGIN (required — the responses mock's origin).
 */
import { appendFileSync } from "node:fs";

const LOG = process.env["MOCK_SUBS_LOG"];
if (!LOG) throw new Error("mock_subs.ts: MOCK_SUBS_LOG is required");
const CODEX_ORIGIN = process.env["MOCK_SUBS_CODEX_ORIGIN"];
if (!CODEX_ORIGIN) throw new Error("mock_subs.ts: MOCK_SUBS_CODEX_ORIGIN is required");

/** The one authorization code the Grok callback is driven with (upgrade.sh curls the loopback callback
 *  the login listens on, which is what a browser redirect would do). */
const GROK_CODE = "grok-e2e-code";
const KIMI_DEVICE_CODE = "kimi-e2e-device-code";
const KIMI_USER_CODE = "E2E-KIMI";
/** Long enough that NOTHING refreshes during the run: a refresh rewrites the credential file, and the
 *  rehearsal asserts those files are byte-identical across the install. The refresh grant is still
 *  served (a 0.4.0 refresh must reach this mock, never the vendor), it is just not reached here. */
const EXPIRES_IN_S = 3600;
/** The reading the 0.3.2 turns leave in the Kimi head's quota file; asserted after the upgrade. The
 *  reset is always in the future, so the reading stays CURRENT for the whole run. */
const KIMI_5H_UTILIZATION = "0.42";
const KIMI_7D_UTILIZATION = "0.15";
const RESET_AHEAD_S = 3 * 3_600;

const KIMI_TEXT = "KIMI OK";

type Vendor = "grok" | "kimi";
type Issued = { access: string[]; refresh: string[] };
const issued: Record<Vendor, Issued> = {
  grok: { access: [], refresh: [] },
  kimi: { access: [], refresh: [] },
};
let kimiPolls = 0;

type Tokens = {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  token_type: string;
  scope: string;
};

function mint(vendor: Vendor): Tokens {
  const n = issued[vendor].access.length + 1;
  const access = `${vendor}-access-${n}`;
  const refresh = `${vendor}-refresh-${n}`;
  issued[vendor].access.push(access);
  issued[vendor].refresh.push(refresh);
  return {
    access_token: access,
    refresh_token: refresh,
    expires_in: EXPIRES_IN_S,
    token_type: "Bearer",
    scope: "offline_access",
  };
}

/** What a presented credential IS, never what it says. */
function label(list: readonly string[], value: string | null): string {
  if (value === null || value === "") return "absent";
  if (value === list[list.length - 1]) return "current";
  return list.includes(value) ? "stale" : "other";
}

function record(entry: Record<string, unknown>): void {
  appendFileSync(LOG as string, JSON.stringify({ at_ms: Date.now(), ...entry }) + "\n");
}

function oauthError(status: number, code: string): Response {
  return Response.json({ error: code }, { status });
}

function form(bodyText: string): URLSearchParams {
  return new URLSearchParams(bodyText);
}

function grokToken(path: string, bodyText: string): Response {
  const body = form(bodyText);
  const grant = body.get("grant_type") ?? "";
  if (grant === "authorization_code") {
    if (body.get("code") !== GROK_CODE) {
      record({ path, grant, status: 400, code: "unexpected" });
      return oauthError(400, "invalid_grant");
    }
    const tokens = mint("grok");
    record({ path, grant, status: 200, issued: issued.grok.access.length });
    return Response.json(tokens);
  }
  if (grant === "refresh_token") {
    const presented = label(issued.grok.refresh, body.get("refresh_token"));
    if (presented !== "current") {
      record({ path, grant, status: 400, refresh_token: presented });
      return oauthError(400, "invalid_grant");
    }
    const tokens = mint("grok");
    record({ path, grant, status: 200, refresh_token: presented, issued: issued.grok.access.length });
    return Response.json(tokens);
  }
  record({ path, grant, status: 400, reason: "unsupported grant" });
  return oauthError(400, "unsupported_grant_type");
}

function kimiDeviceAuthorization(path: string, origin: string): Response {
  kimiPolls = 0;
  record({ path, status: 200 });
  return Response.json({
    device_code: KIMI_DEVICE_CODE,
    user_code: KIMI_USER_CODE,
    verification_uri: `${origin}/kimi/device`,
    verification_uri_complete: `${origin}/kimi/device?user_code=${KIMI_USER_CODE}`,
    expires_in: 300,
    interval: 1,
  });
}

/** The device poll answers `authorization_pending` once — the state machine's normal path, so the old
 *  release's poller is exercised rather than skipped — and then issues the tokens. */
function kimiToken(path: string, bodyText: string, identity: string): Response {
  const body = form(bodyText);
  const grant = body.get("grant_type") ?? "";
  if (grant.includes("device_code")) {
    if (body.get("device_code") !== KIMI_DEVICE_CODE) {
      record({ path, grant, status: 400, device_code: "unexpected" });
      return oauthError(400, "invalid_grant");
    }
    kimiPolls += 1;
    if (kimiPolls === 1) {
      record({ path, grant, status: 400, poll: kimiPolls, error: "authorization_pending" });
      return oauthError(400, "authorization_pending");
    }
    const tokens = mint("kimi");
    record({ path, grant, status: 200, poll: kimiPolls, identity, issued: issued.kimi.access.length });
    return Response.json(tokens);
  }
  if (grant === "refresh_token") {
    const presented = label(issued.kimi.refresh, body.get("refresh_token"));
    if (presented !== "current") {
      record({ path, grant, status: 400, refresh_token: presented });
      return oauthError(400, "invalid_grant");
    }
    const tokens = mint("kimi");
    record({ path, grant, status: 200, refresh_token: presented, identity, issued: issued.kimi.access.length });
    return Response.json(tokens);
  }
  record({ path, grant, status: 400, reason: "unsupported grant" });
  return oauthError(400, "unsupported_grant_type");
}

/** The five X-Msh-* values Kimi binds a session to, as a label: present or absent, never the values. */
function kimiIdentity(req: Request): string {
  const names = ["X-Msh-Platform", "X-Msh-Version", "X-Msh-Device-Name", "X-Msh-Device-Model", "X-Msh-Os-Version"];
  const seen = names.filter((n) => (req.headers.get(n) ?? "") !== "");
  return seen.length === names.length ? "complete" : `partial(${seen.length}/${names.length})`;
}

function quotaHeaders(contentType: string): Record<string, string> {
  const resetAtS = Math.ceil(Date.now() / 1000) + RESET_AHEAD_S;
  return {
    "content-type": contentType,
    "request-id": "req_011CMockKimi",
    "anthropic-ratelimit-unified-status": "allowed",
    "anthropic-ratelimit-unified-representative-claim": "five_hour",
    "anthropic-ratelimit-unified-5h-utilization": KIMI_5H_UTILIZATION,
    "anthropic-ratelimit-unified-5h-reset": String(resetAtS),
    "anthropic-ratelimit-unified-7d-utilization": KIMI_7D_UTILIZATION,
    "anthropic-ratelimit-unified-7d-reset": String(resetAtS + 5 * 86_400),
  };
}

function kimiSse(model: string): string {
  const events: [string, unknown][] = [
    ["message_start", {
      type: "message_start",
      message: {
        id: "msg_mock_kimi", type: "message", role: "assistant", model, content: [],
        stop_reason: null, stop_sequence: null, usage: { input_tokens: 14, output_tokens: 1 },
      },
    }],
    ["content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }],
    ["content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: KIMI_TEXT } }],
    ["content_block_stop", { type: "content_block_stop", index: 0 }],
    ["message_delta", {
      type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 7 },
    }],
    ["message_stop", { type: "message_stop" }],
  ];
  return events.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join("");
}

function kimiMessage(model: string): unknown {
  return {
    id: "msg_mock_kimi", type: "message", role: "assistant", model,
    content: [{ type: "text", text: KIMI_TEXT }],
    stop_reason: "end_turn", stop_sequence: null, usage: { input_tokens: 14, output_tokens: 7 },
  };
}

async function kimiMessages(req: Request, path: string): Promise<Response> {
  const presented = label(issued.kimi.access, req.headers.get("x-api-key"));
  const identity = kimiIdentity(req);
  const bodyText = await req.text();
  const body = (() => {
    try {
      return JSON.parse(bodyText) as { stream?: boolean; model?: string };
    } catch {
      return {};
    }
  })();
  if (presented !== "current") {
    record({ path, status: 401, x_api_key: presented, identity });
    return Response.json(
      { type: "error", error: { type: "authentication_error", message: `kimi mock: x-api-key ${presented}` } },
      { status: 401 },
    );
  }
  const model = body.model ?? "kimi-for-coding";
  const streamed = body.stream === true;
  record({ path, status: 200, x_api_key: presented, identity, stream: streamed, model });
  return streamed
    ? new Response(kimiSse(model), { status: 200, headers: quotaHeaders("text/event-stream") })
    : new Response(JSON.stringify(kimiMessage(model)), { status: 200, headers: quotaHeaders("application/json") });
}

/** The Grok head's upstream. The bearer is this mock's business; the WIRE is the vendored responses
 *  mock's, so a checked request is proxied there and its bytes come back untouched. */
async function grokUpstream(req: Request, path: string): Promise<Response> {
  const presented = label(issued.grok.access, (req.headers.get("authorization") ?? "").replace(/^Bearer /, ""));
  const url = CODEX_ORIGIN as string + path.slice("/grok".length) + new URL(req.url).search;
  if (presented !== "current") {
    record({ path, method: req.method, status: 401, bearer: presented });
    return Response.json(
      { error: { type: "authentication_error", message: `grok mock: bearer ${presented}` } },
      { status: 401 },
    );
  }
  const headers = new Headers(req.headers);
  headers.delete("host");
  const body = req.method === "GET" || req.method === "HEAD" ? undefined : await req.arrayBuffer();
  const upstream = await fetch(url, { method: req.method, headers, body });
  record({ path, method: req.method, status: upstream.status, bearer: presented, proxied_to: url });
  return new Response(upstream.body, { status: upstream.status, headers: upstream.headers });
}

const server = Bun.serve({
  hostname: "127.0.0.1",
  port: Number(process.argv[2] ?? "0"),
  async fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (req.method === "POST" && path === "/grok/oauth2/token") return grokToken(path, await req.text());
    if (req.method === "POST" && path === "/kimi/api/oauth/device_authorization") {
      // The verification URI is built from the request's own origin, which is where a vendor builds
      // it from; reading the server's port here would make this handler's type circular.
      return kimiDeviceAuthorization(path, url.origin);
    }
    if (req.method === "POST" && path === "/kimi/api/oauth/token") {
      return kimiToken(path, await req.text(), kimiIdentity(req));
    }
    if (req.method === "POST" && path === "/kimi/v1/messages") return kimiMessages(req, path);
    if (req.method === "POST" && path === "/kimi/v1/messages/count_tokens") {
      record({ path, status: 200 });
      return Response.json({ input_tokens: 14 });
    }
    if (req.method === "GET" && path === "/kimi/v1/models") {
      record({ path, status: 200 });
      return Response.json({
        data: [{ type: "model", id: "kimi-for-coding", display_name: "Kimi (mock)" }],
        has_more: false,
      });
    }
    // Model discovery is answered HERE, not proxied: the responses mock lists the ChatGPT fixture's
    // one slug, and the Grok head's row is grok-4.6. Both field names a responses-dialect discovery
    // reads are in the body, so whichever one this release looks at finds the head's own row.
    if (req.method === "GET" && path === "/grok/models") {
      const presented = label(issued.grok.access, (req.headers.get("authorization") ?? "").replace(/^Bearer /, ""));
      record({ path, status: 200, bearer: presented });
      return Response.json({
        models: [{ slug: "grok-4.6", display_name: "Grok (mock)", context_window: 500_000 }],
        data: [{ id: "grok-4.6", object: "model", context_length: 500_000 }],
      });
    }
    if (path.startsWith("/grok/")) return grokUpstream(req, path);
    record({ path, method: req.method, status: 404 });
    return new Response("not found", { status: 404 });
  },
});

console.log(JSON.stringify({ port: server.port, log: LOG }));
