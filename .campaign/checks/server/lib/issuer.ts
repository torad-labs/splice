// An OpenID Connect issuer for the sign-in checks, shaped like Google's: discovery, a JWKS, an authorization
// endpoint that signs in whoever the check names and redirects straight back, and a token endpoint that checks the
// PKCE verifier and answers an RS256 ID token. Knobs make the token wrong in each way a server must refuse.

import { createHash, randomBytes } from "node:crypto";

/** A public key as the JWKS publishes it. */
type Jwk = Record<string, unknown> & { kid: string };

export interface Identity {
  email: string;
  email_verified?: boolean;
  /** Replace the `aud` claim. */
  aud?: string;
  /** Replace the `iss` claim. */
  iss?: string;
  /** Seconds from now to `exp`; negative for a token that has expired. */
  expiresIn?: number;
  /** Sign with a key the JWKS doesn't hold. */
  foreignKey?: boolean;
  /** Replace the `nonce` claim. */
  nonce?: string;
}

interface Pending {
  who: Identity;
  challenge: string;
  redirect: string;
  nonce: string;
  clientId: string;
}

const b64url = (b: Buffer | Uint8Array | string) => Buffer.from(b as Uint8Array).toString("base64url");

export function pkce(): { verifier: string; challenge: string } {
  const verifier = b64url(randomBytes(32));
  return { verifier, challenge: b64url(createHash("sha256").update(verifier).digest()) };
}

export class Issuer {
  readonly clientId = "splice-check-client";
  readonly clientSecret = "splice-check-secret";
  /** Who signs in at the authorization endpoint when a check doesn't say. */
  next: Identity = { email: "alice@example.com" };
  /** The form of every token request the issuer was sent. */
  readonly tokenRequests: Record<string, string>[] = [];
  private server: ReturnType<typeof Bun.serve> | null = null;
  private readonly codes = new Map<string, Pending>();
  private keys!: { own: CryptoKeyPair; foreign: CryptoKeyPair; jwk: Jwk };

  get url(): string {
    return `http://127.0.0.1:${this.server!.port}`;
  }

  async start(): Promise<this> {
    const gen = () =>
      crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, [
        "sign",
        "verify",
      ]) as Promise<CryptoKeyPair>;
    const own = await gen();
    const foreign = await gen();
    const jwk = { ...(await crypto.subtle.exportKey("jwk", own.publicKey)), kid: "check-key-1", alg: "RS256", use: "sig" } as Jwk;
    this.keys = { own, foreign, jwk };
    this.server = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: (req) => this.answer(req) });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
  }

  /** The browser's part of a sign-in: open the authorization URL as `who`, and return where it redirects. */
  async authorize(url: string, who?: Identity): Promise<string> {
    if (who) this.next = who;
    const r = await fetch(url, { redirect: "manual" });
    const to = r.headers.get("location");
    if (r.status !== 302 || !to) throw new Error(`the authorization endpoint answered ${r.status}: ${await r.text()}`);
    return to;
  }

  private async answer(req: Request): Promise<Response> {
    const u = new URL(req.url);
    if (u.pathname === "/.well-known/openid-configuration") {
      return Response.json({
        issuer: this.url,
        authorization_endpoint: `${this.url}/authorize`,
        token_endpoint: `${this.url}/token`,
        jwks_uri: `${this.url}/jwks`,
        response_types_supported: ["code"],
        subject_types_supported: ["public"],
        id_token_signing_alg_values_supported: ["RS256"],
        code_challenge_methods_supported: ["S256"],
        scopes_supported: ["openid", "email", "profile"],
      });
    }
    if (u.pathname === "/jwks") return Response.json({ keys: [this.keys.jwk] });
    if (u.pathname === "/authorize") {
      const q = u.searchParams;
      if (q.get("client_id") !== this.clientId) return new Response("unknown client", { status: 400 });
      if (q.get("response_type") !== "code" || q.get("code_challenge_method") !== "S256" || !q.get("code_challenge")) {
        return new Response("PKCE S256 and response_type=code are required", { status: 400 });
      }
      const redirect = q.get("redirect_uri") ?? "";
      const code = b64url(randomBytes(16));
      this.codes.set(code, { who: this.next, challenge: q.get("code_challenge")!, redirect, nonce: q.get("nonce") ?? "", clientId: this.clientId });
      const to = new URL(redirect);
      to.searchParams.set("code", code);
      if (q.get("state") !== null) to.searchParams.set("state", q.get("state")!);
      return new Response(null, { status: 302, headers: { location: to.toString() } });
    }
    if (u.pathname === "/token" && req.method === "POST") {
      const form = Object.fromEntries(new URLSearchParams(await req.text()));
      const basic = req.headers.get("authorization");
      if (basic?.startsWith("Basic ")) {
        const [id, secret] = Buffer.from(basic.slice(6), "base64").toString().split(":");
        form.client_id ??= decodeURIComponent(id ?? "");
        form.client_secret ??= decodeURIComponent(secret ?? "");
      }
      this.tokenRequests.push(form);
      const p = this.codes.get(form.code ?? "");
      const bad = (why: string) => Response.json({ error: "invalid_grant", error_description: why }, { status: 400 });
      if (!p) return bad("unknown code");
      this.codes.delete(form.code!);
      if (form.grant_type !== "authorization_code") return bad("grant_type");
      if (form.client_id !== this.clientId || form.client_secret !== this.clientSecret) return Response.json({ error: "invalid_client" }, { status: 401 });
      if (form.redirect_uri !== p.redirect) return bad("redirect_uri differs");
      const challenge = b64url(createHash("sha256").update(form.code_verifier ?? "").digest());
      if (challenge !== p.challenge) return bad("code_verifier does not match");
      return Response.json({ access_token: b64url(randomBytes(24)), token_type: "Bearer", expires_in: 3600, id_token: await this.idToken(p), scope: "openid email profile" });
    }
    return new Response("not found", { status: 404 });
  }

  private async idToken(p: Pending): Promise<string> {
    const w = p.who;
    const now = Math.floor(Date.now() / 1000);
    const claims = {
      iss: w.iss ?? this.url,
      aud: w.aud ?? p.clientId,
      sub: `sub-${w.email}`,
      email: w.email,
      email_verified: w.email_verified ?? true,
      hd: w.email.split("@")[1],
      nonce: w.nonce ?? p.nonce,
      iat: now,
      exp: now + (w.expiresIn ?? 3600),
    };
    const head = b64url(JSON.stringify({ alg: "RS256", kid: "check-key-1", typ: "JWT" }));
    const body = b64url(JSON.stringify(claims));
    const key = w.foreignKey ? this.keys.foreign.privateKey : this.keys.own.privateKey;
    const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${body}`));
    return `${head}.${body}.${b64url(new Uint8Array(sig))}`;
  }
}
