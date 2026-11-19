// cp-schema: the keys a team's provider definition is written with are splice.toml's own. A file that uses every one
// of them boots, and a file with a mistake in any of them refuses boot, every finding listed at once, as boot does for
// every other key (repo CLAUDE.md, "Boot fails closed").

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { Splice, freePort } from "../server/lib/harness.ts";
import { MODEL, TeamCli, acme, withCli } from "./lib/team.ts";
import { type Authority, authority } from "./lib/tls.ts";

setDefaultTimeout(300_000);

let cli: TeamCli;
let ca: Authority;

beforeAll(() => {
  cli = new TeamCli();
  ca = authority();
});

afterAll(() => {
  cli?.dispose();
  ca?.dispose();
});

/** Start a daemon on `toml`; the log it wrote and whether it came up. */
async function boot(toml: string): Promise<{ up: boolean; log: string; s: Splice }> {
  const s = await Splice.create(toml, withCli(cli));
  try {
    await s.start();
    return { up: true, log: s.logTail(100_000), s };
  } catch {
    return { up: false, log: s.logTail(100_000), s };
  }
}

describe("cp-schema", () => {
  test("a splice.toml that uses every key of a team's definition boots", async () => {
    const { up, log, s } = await boot(
      acme({
        url: "https://ai.acme.example/v1",
        port: await freePort(),
        cli,
        provider: `picker = true
ca_file = "${ca.ca}"
error_messages = "whole"
send_version = true
served_window = { url = "https://ai.acme.example/props", field = "default_generation_settings.n_ctx" }`,
        model: "max_output_tokens = 16384",
        head: `picker = true
model_slots = { opus = "${MODEL}", sonnet = "${MODEL}", haiku = "${MODEL}", fable = "${MODEL}" }`,
        after: `
[door.commands.claude-acme]
head = "acme"
models = "head"
`,
      }),
    );
    try {
      expect(up, log.slice(-3000)).toBe(true);
      const r = s.cli(["claude", "--json"]);
      expect(r.code, `${r.out}\n${r.err}`).toBe(0);
    } finally {
      await s.dispose();
    }
  });

  test("a mistake in any of them refuses boot, and every finding is listed at once", async () => {
    const { up, log, s } = await boot(
      acme({
        url: "https://ai.acme.example/v1",
        port: await freePort(),
        cli,
        provider: `ca_file = "/nonexistent/acme-root.pem"
error_messages = "loud"
served_window = { url = "props", field = "" }`,
        model: "max_output_tokens = 0",
        after: `
[providers.bare]
dialect = "openai-chat"
base_url = "https://bare.example/v1"
auth = { kind = "command" }

[[providers.bare.models]]
id = "bare-model"
context_window = 8192

[providers.keyed]
dialect = "openai-chat"
base_url = "https://keyed.example/v1"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY", credential_command = "acme-ai token --json" }

[[providers.keyed.models]]
id = "keyed-model"
context_window = 8192

[door.commands.claude-nohead]
head = "nope"

[door.commands.claude]
head = "acme"

[door.commands.claude-somemodels]
head = "acme"
models = "some"
`,
      }),
    );
    await s.dispose();
    expect(up).toBe(false);
    const named = [
      /providers\.acme\.ca_file/,
      /providers\.acme\.error_messages/,
      /providers\.acme\.served_window/,
      /max_output_tokens/,
      /providers\.bare\.auth/,
      /providers\.keyed\.auth/,
      /door\.commands\.claude-nohead/,
      /door\.commands\.claude(?![-\w])/,
      /door\.commands\.claude-somemodels/,
    ];
    const missing = named.filter((r) => !r.test(log)).map(String);
    expect(missing, log.slice(-6000)).toEqual([]);
  });
});
