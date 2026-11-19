// What the custom-provider checks share (issue #680): a team's own OpenAI-compatible gateway in front of a
// self-hosted model, the team's CLI that signs a person in and prints a short-lived personal key, the team's
// provider definition in splice.toml's grammar, and requests sent the way Claude Code sends them on the door.
//
// The grammar the definition uses is the one the custom-provider items decide once (cp-schema):
//   [providers.<key>]  auth = { kind = "command", credential_command = "...", login_command = "..." },
//                      picker = true|false, ca_file = "...", error_messages = "whole"|"filtered",
//                      served_window = { url = "...", field = "dotted.path" }, send_version = true
//   [[providers.<key>.models]]  label, description, context_window, max_output_tokens
//   [heads.<key>]      picker = true|false, model_slots = { opus, sonnet, haiku, fable }
//   [door.commands.<name>]  head = "<head>", models = "head"|"door"

import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { type Splice, type Turn } from "../../server/lib/harness.ts";
import { Upstream } from "../../server/lib/upstream.ts";

export const MODEL = "glm-5.3-flash";
export const LABEL = "GLM-5.3 Flash";
export const DESCRIPTION = "Acme's own GLM, served in-house";
export const SELECTOR = `claude-acme--${MODEL}`;

export interface Seen {
  path: string;
  headers: Record<string, string>;
  body: Record<string, unknown> | null;
}

/** A canned answer the gateway gives the next completion, in place of the model's. */
export interface Canned {
  status: number;
  message: string;
  headers?: Record<string, string>;
}

/** The team's gateway: OpenAI-compatible, in front of a model server. It admits a completion only with a key the
 *  team's CLI issued and that was not revoked, and answers 401 in OpenAI's shape otherwise. It records every request
 *  it was sent, can answer the next completions with canned refusals (a model that is loading, a client that is out of
 *  date), and reports the window it serves at /props, as llama-server does. */
export class Gateway {
  readonly model: Upstream;
  readonly seen: Seen[] = [];
  readonly revoked = new Set<string>();
  readonly next: Canned[] = [];
  /** The window /props reports, as llama-server's `default_generation_settings.n_ctx`; null answers /props 404, as a
   *  gateway that reports none does (splice probes a local server's /props at boot and refuses a declared window past it). */
  servedWindow: number | null = null;
  unauthorized = "Your Acme AI key has expired or was revoked.";
  /** Refuse every key, as a gateway does whose sign-in service is down. */
  refuseAll = false;
  /** Admit any key, as a gateway on a static key does. */
  open = false;
  private server: ReturnType<typeof Bun.serve> | null = null;
  private scheme = "http";

  constructor(
    readonly cli: TeamCli,
    o: { usage?: { prompt: number; cached: number; completion: number }; say?: string } = {},
  ) {
    this.model = new Upstream("acme", MODEL, { ...(o.usage ? { usage: o.usage } : {}), ...(o.say ? { say: o.say } : {}) });
  }

  start(tls?: { cert: string; key: string }): this {
    this.model.start();
    this.scheme = tls ? "https" : "http";
    this.server = Bun.serve({
      port: 0,
      hostname: "127.0.0.1",
      idleTimeout: 0,
      ...(tls ? { tls: { cert: readFileSync(tls.cert, "utf8"), key: readFileSync(tls.key, "utf8") } } : {}),
      fetch: (req) => this.answer(req),
    });
    return this;
  }

  stop(): void {
    this.server?.stop(true);
    this.server = null;
    this.model.stop();
  }

  get port(): number {
    if (!this.server) throw new Error("the gateway is not running");
    return this.server.port as number;
  }

  /** The base URL the provider names. */
  get url(): string {
    return `${this.scheme}://127.0.0.1:${this.port}/v1`;
  }

  /** Where it reports the window it serves. */
  get propsUrl(): string {
    return `${this.scheme}://127.0.0.1:${this.port}/props`;
  }

  completions(): Seen[] {
    return this.seen.filter((s) => s.path.endsWith("/chat/completions"));
  }

  /** The completions whose last user message carries `marker`. */
  turnsWith(marker: string): Seen[] {
    return this.completions().filter((s) => JSON.stringify(s.body?.messages ?? []).includes(marker));
  }

  /** The key a request presented, from its Authorization header. */
  static bearer(s: Seen): string {
    return (s.headers.authorization ?? "").replace(/^Bearer\s+/i, "");
  }

  private admits(req: Request): boolean {
    const key = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
    return this.open || !this.refuseAll && this.cli.issued().includes(key) && !this.revoked.has(key);
  }

  private async answer(req: Request): Promise<Response> {
    const path = new URL(req.url).pathname;
    const text = req.method === "POST" ? await req.text() : "";
    let body: Record<string, unknown> | null = null;
    try {
      body = text ? (JSON.parse(text) as Record<string, unknown>) : null;
    } catch {}
    this.seen.push({ path, headers: Object.fromEntries(req.headers), body });
    if (path === "/props") {
      return this.servedWindow === null
        ? Response.json({ error: { message: "no route /props" } }, { status: 404 })
        : Response.json({ default_generation_settings: { n_ctx: this.servedWindow } });
    }
    if (!this.admits(req)) return Response.json({ error: { message: this.unauthorized, type: "invalid_api_key", code: "invalid_api_key" } }, { status: 401 });
    if (path === "/v1/models") return Response.json({ object: "list", data: [{ id: MODEL, object: "model", owned_by: "acme" }] });
    if (path !== "/v1/chat/completions") return Response.json({ error: { message: `no route ${path}` } }, { status: 404 });
    const canned = this.next.shift();
    if (canned) {
      return Response.json(
        { error: { message: canned.message, type: canned.status >= 500 ? "server_error" : "invalid_request_error" } },
        { status: canned.status, headers: canned.headers ?? {} },
      );
    }
    const res = await fetch(`${this.model.url}/chat/completions`, { method: "POST", headers: { "content-type": "application/json" }, body: text });
    return new Response(res.body, { status: res.status, headers: { "content-type": res.headers.get("content-type") ?? "application/json" } });
  }
}

/** The team's own CLI, `acme-ai`, as a script in a folder of its own: `acme-ai login` signs the person in (printing
 *  the address of the company's sign-in page, as a device sign-in does), and `acme-ai token --json` prints a fresh
 *  personal key with its expiry, `{"token": "...", "expires_at": "<RFC 3339>"}`, or fails, saying so on stderr, when
 *  the person is not signed in. Every key it prints is new and starts with a prefix of this run. */
export class TeamCli {
  readonly dir: string;
  readonly bin: string;
  readonly prefix = `acme-key-${crypto.randomUUID().slice(0, 8)}`;

  constructor() {
    this.dir = mkdtempSync(join(tmpdir(), "acme-cli-"));
    this.bin = join(this.dir, "bin");
    mkdirSync(this.bin, { recursive: true });
    const script = `#!/bin/sh
D='${this.dir}'
case "$1" in
  token)
    env > "$D/token-env.txt"
    if [ -f "$D/fail" ]; then cat "$D/fail" >&2; exit 1; fi
    if [ ! -f "$D/signed-in" ]; then echo "acme-ai: not signed in; run acme-ai login" >&2; exit 1; fi
    n=$(( $(cat "$D/n" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$D/n"
    t="${this.prefix}-$n"
    echo "$t" >> "$D/issued"
    if [ -f "$D/expires" ]; then printf '{"token":"%s","expires_at":"%s"}\\n' "$t" "$(cat "$D/expires")"
    else printf '{"token":"%s"}\\n' "$t"; fi
    ;;
  login)
    env > "$D/login-env.txt"
    echo "To sign in to Acme AI, open https://sso.acme.example/device?user_code=WXYZ-1234"
    sleep "$(cat "$D/login-delay" 2>/dev/null || echo 0)"
    if [ -f "$D/login-fails" ]; then echo "acme-ai: the sign-in was not completed" >&2; exit 3; fi
    touch "$D/signed-in"
    echo "Signed in to Acme AI."
    ;;
  *) echo "acme-ai: unknown command $1" >&2; exit 2 ;;
esac
`;
    writeFileSync(join(this.bin, "acme-ai"), script);
    chmodSync(join(this.bin, "acme-ai"), 0o755);
    this.expiresIn(3600);
  }

  /** Remove its folder. */
  dispose(): void {
    rmSync(this.dir, { recursive: true, force: true });
  }

  get credentialCommand(): string {
    return `${join(this.bin, "acme-ai")} token --json`;
  }

  get loginCommand(): string {
    return `${join(this.bin, "acme-ai")} login`;
  }

  signIn(): void {
    writeFileSync(join(this.dir, "signed-in"), "");
  }

  signOut(): void {
    rmSync(join(this.dir, "signed-in"), { force: true });
  }

  signedIn(): boolean {
    return existsSync(join(this.dir, "signed-in"));
  }

  /** The keys it prints from now expire in `seconds`; null prints keys with no expiry. */
  expiresIn(seconds: number | null): void {
    const f = join(this.dir, "expires");
    if (seconds === null) rmSync(f, { force: true });
    else writeFileSync(f, new Date(Date.now() + seconds * 1000).toISOString().replace(/\.\d{3}Z$/, "Z"));
  }

  /** `acme-ai token` fails with `message` on stderr from now; null lets it work again. */
  failWith(message: string | null): void {
    const f = join(this.dir, "fail");
    if (message === null) rmSync(f, { force: true });
    else writeFileSync(f, `${message}\n`);
  }

  /** `acme-ai login` fails, exiting 3, from now (true), or works again (false). */
  loginFails(fails: boolean): void {
    const f = join(this.dir, "login-fails");
    if (fails) writeFileSync(f, "");
    else rmSync(f, { force: true });
  }

  /** `acme-ai login` takes this long before it signs the person in. */
  loginTakes(seconds: number): void {
    writeFileSync(join(this.dir, "login-delay"), String(seconds));
  }

  /** Every key it has printed, oldest first. */
  issued(): string[] {
    const f = join(this.dir, "issued");
    return existsSync(f) ? readFileSync(f, "utf8").split("\n").filter(Boolean) : [];
  }

  /** The environment the last run of `token` or `login` was started with. */
  envOf(verb: "token" | "login"): string {
    const f = join(this.dir, `${verb}-env.txt`);
    return existsSync(f) ? readFileSync(f, "utf8") : "";
  }
}

export interface Definition {
  url: string;
  port: number;
  cli: TeamCli;
  /** More lines of the provider's table. */
  provider?: string;
  /** More lines of the model's table. */
  model?: string;
  /** More lines of the head's table. */
  head?: string;
  /** Tables after the head's (door commands). */
  after?: string;
  window?: number;
}

/** The team's provider, its one model and its head, in splice.toml's grammar. */
export function acme(o: Definition): string {
  return `
[providers.acme]
dialect = "openai-chat"
base_url = "${o.url}"
auth = { kind = "command", credential_command = "${o.cli.credentialCommand}", login_command = "${o.cli.loginCommand}" }
# the team's gateway is on another machine; the checks run it on loopback, which alone would make it a local server
local = false
${o.provider ?? ""}

[[providers.acme.models]]
id = "${MODEL}"
label = "${LABEL}"
description = "${DESCRIPTION}"
context_window = ${o.window ?? 65536}
${o.model ?? ""}

[heads.acme]
provider = "acme"
port = ${o.port}
discovery_prefix = "claude-acme--"
pinned_model = "${MODEL}"
${o.head ?? ""}
${o.after ?? ""}
`;
}

/** A Messages request through the door with the launch's address and key, with a body and headers of the check's
 *  own, as the session's Claude Code sends it in Mantle mode. Read to its end. */
export async function doorRaw(
  x: { url: string; key: string },
  body: Record<string, unknown>,
  o: { session?: string; headers?: Record<string, string> } = {},
): Promise<{ status: number; headers: Headers; body: string }> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "anthropic-version": "2023-06-01",
    "user-agent": "claude-cli/2.1.326 (external, cli)",
    "x-app": "cli",
    authorization: `Bearer ${x.key}`,
    "x-claude-code-session-id": o.session ?? crypto.randomUUID(),
    ...o.headers,
  };
  const r = await fetch(`${x.url}/v1/messages?beta=true`, { method: "POST", headers, body: JSON.stringify(body) });
  return { status: r.status, headers: r.headers, body: await r.text() };
}

/** The error envelope of an answer, streamed or not: its type and message. */
export function errorOf(t: Turn | { body: string }): { type: string; message: string } | null {
  if ("error" in t && t.error) return t.error;
  for (const chunk of t.body.split(/\n/)) {
    const line = chunk.replace(/^data:\s*/, "").trim();
    if (!line.startsWith("{")) continue;
    try {
      const j = JSON.parse(line) as { type?: string; error?: { type?: string; message?: string } };
      if (j.type === "error" && j.error) return { type: String(j.error.type), message: String(j.error.message) };
    } catch {}
  }
  return null;
}

/** The PATH a daemon runs with, the team's CLI first, so a definition may name it bare as `acme-ai`. */
export function withCli(cli: TeamCli): Record<string, string> {
  return { PATH: `${cli.bin}:${process.env.PATH ?? ""}` };
}

/** Every file of the daemon's home holding `secret`, by path. */
export function holding(s: Splice, secret: string): string[] {
  return s.files().filter((f) => f.bytes.includes(Buffer.from(secret))).map((f) => f.path);
}

/** The recipe a launch of `command` with the person's own `args` answers the `splice-launch` shim, as the shim sends
 *  it: each argument one `arg` field, in order. */
export async function recipeWith(s: Splice, command: string, args: string[]): Promise<{ argv: string[]; env: Record<string, string>; unset: string[] }> {
  const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd: s.home, launch: String(process.pid) });
  for (const a of args) form.append("arg", a);
  const r = await s.api(`/launch/${command}`, { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
  const text = await r.text();
  if (r.status !== 200) throw new Error(`launching ${command} answered ${r.status}: ${text}`);
  return JSON.parse(text) as { argv: string[]; env: Record<string, string>; unset: string[] };
}

/** Every `--settings` value in `argv`, read: inline JSON, or the file it names. */
export function settingsOf(argv: string[]): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = [];
  argv.forEach((a, i) => {
    const v = a === "--settings" ? argv[i + 1] : a.startsWith("--settings=") ? a.slice("--settings=".length) : undefined;
    if (v === undefined) return;
    out.push(JSON.parse(v.trimStart().startsWith("{") ? v : readFileSync(v, "utf8")) as Record<string, unknown>);
  });
  return out;
}
