// Stand-in provider OAuth + inference HTTP server for `bun tools/e2e signin` (signin-e2e).
// Every token, account, email and reply is synthetic. Tracks which credentials were minted, removed,
// and actually sent by Splice on inference requests so Step 3 proves the gateway used the newly
// minted credential and no other.
import { createHash, randomBytes } from "node:crypto";
import type { SignInFlow } from "./signin-registry.ts";

export interface ProviderProfile {
  kind: string;
  flow: SignInFlow;
  head: string;
  providerKey: string;
  dialect: "openai-responses" | "anthropic-passthrough";
  modelId: string;
  providerPage: string;
  providerOrigin: string;
  callbackUrl?: string;
}

export const PROFILES: Record<string, ProviderProfile> = {
  "chatgpt-oauth": {
    kind: "chatgpt-oauth",
    flow: "loopback",
    head: "codex",
    providerKey: "openai-codex",
    dialect: "openai-responses",
    modelId: "gpt-5.3-codex",
    providerPage: "https://auth.openai.com/oauth/authorize",
    providerOrigin: "https://auth.openai.com",
    callbackUrl: "http://localhost:1455/auth/callback",
  },
  "grok-oauth": {
    kind: "grok-oauth",
    flow: "loopback",
    head: "grok",
    providerKey: "xai",
    dialect: "openai-responses",
    modelId: "grok-4",
    providerPage: "https://auth.x.ai/oauth2/authorize",
    providerOrigin: "https://auth.x.ai",
    callbackUrl: "http://127.0.0.1:56121/callback",
  },
  "kimi-oauth": {
    kind: "kimi-oauth",
    flow: "device",
    head: "kimi",
    providerKey: "kimi",
    dialect: "anthropic-passthrough",
    modelId: "kimi-k2.5",
    providerPage: "https://auth.kimi.com/device",
    providerOrigin: "https://auth.kimi.com",
  },
  "muse-oauth": {
    kind: "muse-oauth",
    flow: "device",
    head: "muse",
    providerKey: "muse",
    dialect: "openai-responses",
    modelId: "muse-code",
    providerPage: "https://auth.meta.com/device",
    providerOrigin: "https://auth.meta.com",
  },
  client: {
    kind: "client",
    flow: "paste",
    head: "claude",
    providerKey: "claude",
    dialect: "anthropic-passthrough",
    modelId: "claude-sonnet-4-6",
    providerPage: "https://claude.com/cai/oauth/authorize",
    providerOrigin: "https://claude.com",
  },
};

export type StandInMode = "approve" | "refuse" | "expire" | "unnamed";

export interface StandInServer {
  port: number;
  baseUrl: string;
  setMode(mode: StandInMode, refusal?: string): void;
  setExpectedReply(text: string): void;
  mintNext(labelSeed: string): { accessToken: string; refreshToken: string; accountId: string; apiKey: string };
  approveDevice(userCode: string): void;
  lastDeviceCode(): { userCode: string; deviceCode: string } | null;
  seenCredentials(): string[];
  clearSeenCredentials(): void;
  envOverrides(): Record<string, string>;
  stop(): void;
}

function makeFakeJwt(accountId: string, email: string, plan = "plus"): string {
  const header = Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(
    JSON.stringify({
      sub: `user-${accountId}`,
      email,
      "https://api.openai.com/auth": {
        chatgpt_account_id: accountId,
        chatgpt_plan_type: plan,
      },
      exp: Math.floor(Date.now() / 1000) + 3600,
    }),
  ).toString("base64url");
  return `${header}.${payload}.sig`;
}

export function startStandIn(profile: ProviderProfile): StandInServer {
  let mode: StandInMode = "approve";
  let refusalText = "synthetic provider refused authorization";
  let expectedReply = `standin-reply-${profile.kind}`;
  let seq = 0;
  let deviceSeq = 0;
  let current = mintCredentials("first");
  const deviceByUserCode = new Map<string, string>();
  const approvedDeviceCodes = new Set<string>();
  let deviceActed = false;
  let latestDevice: { userCode: string; deviceCode: string } | null = null;
  const seenCreds: string[] = [];

  function mintCredentials(seed: string) {
    seq += 1;
    const tag = `${profile.kind}-${seed}-${seq}-${randomBytes(4).toString("hex")}`;
    const accountId = `acct-${seq}-${createHash("sha256").update(tag).digest("hex").slice(0, 8)}`;
    const accessToken =
      profile.kind === "chatgpt-oauth"
        ? makeFakeJwt(accountId, `${seed}@synthetic.example`)
        : `tok-${tag}`;
    const refreshToken = `ref-${tag}`;
    const apiKey = `muse-key-${tag}`;
    return { accessToken, refreshToken, accountId, apiKey };
  }

  function extractCredential(req: Request): string {
    const auth = req.headers.get("authorization") ?? "";
    if (auth.toLowerCase().startsWith("bearer ")) return auth.slice(7).trim();
    const apiKey = req.headers.get("x-api-key") ?? "";
    if (apiKey) return apiKey.trim();
    return auth.trim();
  }

  function anthropicReply(stream: boolean): Response {
    if (!stream) {
      return Response.json({
        id: "msg_standin_1",
        type: "message",
        role: "assistant",
        model: profile.modelId,
        content: [{ type: "text", text: expectedReply }],
        stop_reason: "end_turn",
        stop_sequence: null,
        usage: { input_tokens: 12, output_tokens: 8 },
      });
    }
    const sse = [
      `event: message_start\ndata: ${JSON.stringify({ type: "message_start", message: { id: "msg_standin_1", type: "message", role: "assistant", model: profile.modelId, content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 12, output_tokens: 0 } } })}\n\n`,
      `event: content_block_start\ndata: ${JSON.stringify({ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } })}\n\n`,
      `event: content_block_delta\ndata: ${JSON.stringify({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: expectedReply } })}\n\n`,
      `event: content_block_stop\ndata: ${JSON.stringify({ type: "content_block_stop", index: 0 })}\n\n`,
      `event: message_delta\ndata: ${JSON.stringify({ type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 8 } })}\n\n`,
      `event: message_stop\ndata: ${JSON.stringify({ type: "message_stop" })}\n\n`,
    ].join("");
    return new Response(sse, { headers: { "content-type": "text/event-stream" } });
  }

  function responsesReply(): Response {
    const sse = [
      `event: response.created\ndata: ${JSON.stringify({ type: "response.created", response: { id: "resp_standin_1", model: profile.modelId, status: "in_progress" } })}\n\n`,
      `event: response.output_item.added\ndata: ${JSON.stringify({ type: "response.output_item.added", output_index: 0, item: { id: "item_1", type: "message", role: "assistant", status: "in_progress", content: [] } })}\n\n`,
      `event: response.output_text.delta\ndata: ${JSON.stringify({ type: "response.output_text.delta", output_index: 0, content_index: 0, delta: expectedReply })}\n\n`,
      `event: response.output_item.done\ndata: ${JSON.stringify({ type: "response.output_item.done", output_index: 0, item: { id: "item_1", type: "message", role: "assistant", status: "completed", content: [{ type: "output_text", text: expectedReply }] } })}\n\n`,
      `event: response.completed\ndata: ${JSON.stringify({ type: "response.completed", response: { id: "resp_standin_1", model: profile.modelId, status: "completed", usage: { input_tokens: 12, output_tokens: 8, total_tokens: 20 } } })}\n\n`,
    ].join("");
    return new Response(sse, { headers: { "content-type": "text/event-stream" } });
  }

  const server = Bun.serve({
    port: 0,
    hostname: "127.0.0.1",
    async fetch(req) {
      const url = new URL(req.url);
      const path = url.pathname;

      // Device authorization endpoints (Kimi / Muse)
      if (path.includes("device_authorization") || path.includes("/device/authorization")) {
        if (mode === "unnamed") {
          return Response.json({ error: "server_error", error_description: "stand-in device endpoint refused start" }, { status: 503 });
        }
        deviceSeq += 1;
        const userCode = `CODE-${String(deviceSeq).padStart(4, "0")}`;
        const deviceCode = `dev-${randomBytes(6).toString("hex")}`;
        latestDevice = { userCode, deviceCode };
        deviceByUserCode.set(userCode, deviceCode);
        return Response.json({
          device_code: deviceCode,
          user_code: userCode,
          verification_uri: profile.providerPage,
          verification_uri_complete: `${profile.providerPage}?user_code=${encodeURIComponent(userCode)}`,
          expires_in: 300,
          interval: 1,
        });
      }

      // OAuth token endpoints (Codex, Grok, Kimi, Muse)
      if (path.includes("/oauth/token") || path.includes("/oauth2/token") || path.includes("/device/token")) {
        const body = await req.text();
        const params = new URLSearchParams(body);
        if (profile.flow === "device") {
          const code = params.get("device_code") ?? "";
          if (mode === "refuse" || mode === "expire") {
            if (!deviceActed) {
              return Response.json({ error: "authorization_pending" }, { status: 400 });
            }
          } else if (!latestDevice || code !== latestDevice.deviceCode || !approvedDeviceCodes.has(code)) {
            return Response.json({ error: "authorization_pending" }, { status: 400 });
          }
        }
        if (mode === "refuse") {
          return Response.json({ error: "access_denied", error_description: refusalText }, { status: 400 });
        }
        if (mode === "expire") {
          return Response.json({ error: "expired_token", error_description: "The authorization code expired." }, { status: 400 });
        }
        const idToken =
          profile.kind === "chatgpt-oauth"
            ? makeFakeJwt(current.accountId, `${current.accountId}@synthetic.example`)
            : undefined;
        return Response.json({
          access_token: current.accessToken,
          refresh_token: current.refreshToken,
          ...(idToken ? { id_token: idToken } : {}),
          token_type: "Bearer",
          expires_in: 3600,
          scope: "openid profile email offline_access",
        });
      }

      // Muse key mint endpoint
      if (path.includes("/muse-code/key")) {
        return Response.json({
          is_subs_active: true,
          require_payment: false,
          api_key: current.apiKey,
        });
      }

      // Claude OAuth profile verification endpoint
      if (path.endsWith("/api/oauth/profile")) {
        return Response.json({
          account: {
            uuid: current.accountId,
            email: `${current.accountId}@synthetic.example`,
          },
        });
      }

      // Inference endpoints (Responses or Anthropic Messages)
      if (path.endsWith("/responses") || path.endsWith("/v1/messages") || path.endsWith("/messages")) {
        const cred = extractCredential(req);
        if (cred) seenCreds.push(cred);
        let stream = true;
        try {
          const parsed = (await req.json()) as { stream?: boolean };
          stream = parsed.stream !== false;
        } catch {
          stream = true;
        }
        return profile.dialect === "openai-responses" ? responsesReply() : anthropicReply(stream);
      }

      return new Response("not found", { status: 404 });
    },
  });

  const port = server.port ?? 0;
  const baseUrl = `http://127.0.0.1:${port}`;

  return {
    port,
    baseUrl,
    setMode(nextMode: StandInMode, refusal?: string) {
      mode = nextMode;
      deviceActed = false;
      if (refusal) refusalText = refusal;
    },
    setExpectedReply(text: string) {
      expectedReply = text;
    },
    mintNext(labelSeed: string) {
      current = mintCredentials(labelSeed);
      return current;
    },
    approveDevice(userCode: string) {
      deviceActed = true;
      const deviceCode = deviceByUserCode.get(userCode);
      if (deviceCode) approvedDeviceCodes.add(deviceCode);
    },
    lastDeviceCode() {
      return latestDevice;
    },
    seenCredentials() {
      return [...seenCreds];
    },
    clearSeenCredentials() {
      seenCreds.length = 0;
    },
    envOverrides(): Record<string, string> {
      switch (profile.kind) {
        case "chatgpt-oauth":
          return { CODEX_OAUTH_TOKEN_URL: `${baseUrl}/oauth/token` };
        case "grok-oauth":
          return { GROK_OAUTH_TOKEN_URL: `${baseUrl}/oauth2/token` };
        case "kimi-oauth":
          return { KIMI_OAUTH_HOST: baseUrl };
        case "muse-oauth":
          return {
            MUSE_OAUTH_DEVICE_AUTHORIZATION_URL: `${baseUrl}/oidc/device/authorization/`,
            MUSE_OAUTH_TOKEN_URL: `${baseUrl}/oidc/device/token/`,
            MUSE_OAUTH_KEY_URL: `${baseUrl}/muse-code/key`,
          };
        case "client":
          return {
            CLAUDE_OAUTH_PROFILE_URL: `${baseUrl}/api/oauth/profile`,
          };
        default:
          return {};
      }
    },
    stop() {
      server.stop(true);
    },
  };
}
