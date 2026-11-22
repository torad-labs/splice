// Heads the head-catalog checks boot splice with, beside the ones the server harness and the speed checks write.

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { Splice } from "../../server/lib/harness.ts";

/** The head keyed `claude` on Anthropic's Messages API, forwarding the caller's own login as the `claude` head does,
 *  with Claude's models declared and its tiers named. */
export function claudeHeadToml(o: { port: number; url: string }): string {
  return `
[providers.anthropic]
dialect = "anthropic-passthrough"
base_url = "${o.url}"
auth = { kind = "client" }
extra_headers = { anthropic-version = "2023-06-01" }
model_slots = { opus = "claude-opus-5-5", sonnet = "claude-opus-5-5", haiku = "claude-haiku-5-5" }

[[providers.anthropic.models]]
id = "claude-opus-5-5"
label = "Claude Opus 5.5"
context_window = 200000

[[providers.anthropic.models]]
id = "claude-haiku-5-5"
label = "Claude Haiku 5.5"
context_window = 200000

[heads.claude]
provider = "anthropic"
port = ${o.port}
discovery_prefix = "claude--"
pinned_model = "claude-opus-5-5"
[heads.claude.claude]
command = "claude"
`;
}

/** A Claude account added to head `head`, as a sign-in on splice's accounts page lands it: one owner-only folder in
 *  Claude Code's own file format. Written before the daemon starts. */
export function addClaudeAccount(s: Splice, head: string, label: string, token: string): void {
  const dir = join(s.state, "claude-accounts", head, label);
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const far = Date.now() + 365 * 24 * 3600 * 1000;
  writeFileSync(
    join(dir, ".credentials.json"),
    JSON.stringify({ claudeAiOauth: { accessToken: token, refreshToken: `${token}-refresh`, expiresAt: far, scopes: ["user:inference", "user:profile"] } }),
    { mode: 0o600 },
  );
  writeFileSync(join(dir, ".claude.json"), JSON.stringify({ oauthAccount: { accountUuid: crypto.randomUUID(), emailAddress: `${label}@example.com` } }), { mode: 0o600 });
  writeFileSync(join(dir, ".splice-account.json"), JSON.stringify({ added_at: Date.now() }), { mode: 0o600 });
}

/** A head on an API key whose discovery prefix is `prefix`, which a person may write by hand in splice.toml. */
export function prefixedHead(o: { key: string; command: string; prefix: string; port: number; url: string; model: string; window?: number }): string {
  return `
[door.selector_aliases]
"${o.prefix}" = "${o.key}"

[providers.${o.key}]
dialect = "openai-chat"
base_url = "${o.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }

[[providers.${o.key}.models]]
id = "${o.model}"
label = "${o.model}"
context_window = ${o.window ?? 131072}

[heads.${o.key}]
provider = "${o.key}"
port = ${o.port}
discovery_prefix = "${o.prefix}"
pinned_model = "${o.model}"
[heads.${o.key}.claude]
command = "${o.command}"
`;
}
