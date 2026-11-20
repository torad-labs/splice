// gnh-moves: a value that only a head block holds and that would make the Splice gateway serve that head's provider
// differently (an older selector prefix, a context window, the tiers' models, the system prompt mode, an override) is a
// boot finding that names it, and the fix session offers to move it: the prefix becomes a `[gateway] aliases` entry,
// every other value moves to the provider's own table, the head keeps behaving as before, and nothing is moved without
// the person's answer. A head whose values the gateway already serves the same way is no finding.

import { afterAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Splice, freePort } from "../server/lib/harness.ts";
import { onTerminal, tomlOf } from "./lib/providers.ts";

setDefaultTimeout(300_000);

const BIG = "Quartz-9-Large";
const SMALL = "Quartz-9-Mini";

const made: Splice[] = [];
afterAll(async () => {
  for (const s of made) await s.dispose();
});

async function quartz(head: string): Promise<string> {
  return `
[providers.quartz]
dialect = "openai-chat"
base_url = "http://127.0.0.1:${await freePort()}/v1"
auth = { kind = "api-key", env = "QUARTZ_KEY" }

[[providers.quartz.models]]
id = "${BIG}"
label = "${BIG}"
context_window = 262144

[[providers.quartz.models]]
id = "${SMALL}"
label = "${SMALL}"
context_window = 131072

[heads.qz]
provider = "quartz"
port = ${await freePort()}
${head}
[heads.qz.claude]
command = "claude-qz"
`;
}

const HELD = `discovery_prefix = "qz--"
pinned_model = "${BIG}"
context_window = 200000
model_slots = { opus = "${BIG}", sonnet = "${BIG}", haiku = "${SMALL}", fable = "${BIG}" }
system_prompt_mode = "replace"
[heads.qz.overrides]
upstreamTimeoutMs = "600000"`;

describe("gnh-moves", () => {
  test("each value only the head holds is a finding that names it", async () => {
    const s = await Splice.create(await quartz(HELD));
    made.push(s);
    const r = s.cli(["check-config"]);
    const text = `${r.out}\n${r.err}`;
    expect(r.code).toBe(3);
    for (const path of [
      "heads.qz.discovery_prefix",
      "heads.qz.context_window",
      "heads.qz.model_slots",
      "heads.qz.system_prompt_mode",
      "heads.qz.overrides.upstreamTimeoutMs",
    ]) {
      expect(text).toContain(path);
    }
    // a pin is where the head's own command starts, not something a gateway turn needs
    expect(text).not.toContain("heads.qz.pinned_model");
  });

  test("a head whose values the gateway serves the same way is no finding", async () => {
    const s = await Splice.create(await quartz(`discovery_prefix = "claude-quartz--"\npinned_model = "${BIG}"`));
    made.push(s);
    const r = s.cli(["check-config"]);
    expect(r.code, `${r.out}\n${r.err}`).toBe(0);
  });

  test("the fix session moves each value where the gateway reads it, backs the file up, and splice starts", async () => {
    const s = await Splice.create(await quartz(HELD));
    made.push(s);
    // one answer per finding: move it
    const r = onTerminal(s, ["status"], Array.from({ length: 12 }, () => "m"));
    expect(s.cli(["check-config"]).code, r.out).toBe(0);
    const toml = tomlOf(s);
    expect(toml.gateway?.aliases?.["qz--"]).toBe("quartz");
    const quartzTable = toml.providers.quartz;
    expect(quartzTable.context_window).toBe(200000);
    expect(quartzTable.model_slots).toEqual({ opus: BIG, sonnet: BIG, haiku: SMALL, fable: BIG });
    expect(quartzTable.system_prompt_mode).toBe("replace");
    expect(quartzTable.overrides?.upstreamTimeoutMs).toBe("600000");
    const head = toml.heads.qz;
    // the head keeps its own prefix and pin for its own command; what moved is no longer written twice
    expect(head.discovery_prefix).toBe("qz--");
    expect(head.pinned_model).toBe(BIG);
    for (const key of ["context_window", "model_slots", "system_prompt_mode"]) expect(head[key]).toBeUndefined();
    expect(head.overrides?.upstreamTimeoutMs).toBeUndefined();
    const backups = join(s.state, "config-backups");
    expect(existsSync(backups) && readdirSync(backups).length > 0).toBe(true);
  });

  test("quitting the session moves nothing", async () => {
    const s = await Splice.create(await quartz(HELD));
    made.push(s);
    const before = readFileSync(s.config, "utf8");
    onTerminal(s, ["status"], ["q"]);
    expect(readFileSync(s.config, "utf8")).toBe(before);
  });
});
