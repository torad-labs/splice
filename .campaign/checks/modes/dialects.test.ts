// modes-dialects: a door mode works the same on a head whose provider speaks another dialect. On a head over OpenAI's
// Chat Completions and on a Codex head over the Responses API, the provider is offered the template's tool in its
// own shape with the mode's description, and the mode's prompt; when the model calls the tool, the mode answers it
// inside splice, the provider gets the handler's text as the call's result in its own shape, and the person's client
// never sees the call and gets the model's answer after it as one response.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { assemble } from "../harness/lib/agent.ts";
import { KEYS, first } from "../harness/lib/capability.ts";
import { Splice, freePort, mockHead } from "../server/lib/harness.ts";
import { gptEnv, gptHead } from "../speed/lib/codex.ts";
import { type Launched, launch } from "../speed/lib/session.ts";
import { TOOL, ToolsUpstream, inJson, mark, modesTable, offeredTool, requireTemplate, resultOf, templatePrompt, templateTool } from "./lib/modes.ts";

setDefaultTimeout(120_000);

let up: ToolsUpstream;
let s: Splice;
let logins: string;
const on = {} as Record<"chat" | "gpt", Launched>;

beforeAll(async () => {
  requireTemplate();
  up = new ToolsUpstream().start();
  logins = mkdtempSync(join(tmpdir(), "modes-dialects-"));
  s = await Splice.boot(
    mockHead({ key: "chat", command: "claude-chat", port: await freePort(), url: up.chatUrl, model: "chat-large" }) +
      gptHead({ key: "gpt", command: "claude-gpt", port: await freePort(), backend: up.url, model: "gpt-6-astra", dir: logins }) +
      modesTable("template", "on = true"),
    { ...KEYS, ...gptEnv(up.url) },
  );
  on.chat = await launch(s, "claude-chat");
  on.gpt = await launch(s, "claude-gpt");
}, 300_000);

afterAll(async () => {
  await s?.dispose();
  up?.stop();
  if (logins) rmSync(logins, { recursive: true, force: true });
});

describe("modes-dialects", () => {
  for (const key of ["chat", "gpt"] as const) {
    test(`${key}: the tool and the prompt are offered in the dialect's shape, and the call is answered inside splice`, async () => {
      const m = mark();
      const { sent } = await first(on[key], `say back ${m}`);
      expect(sent.status, sent.body.slice(0, 2000)).toBe(200);
      expect(sent.body).not.toContain(TOOL);
      const got = assemble(sent.body);
      expect(got.blocks.some((b) => b.type === "tool_use")).toBe(false);
      expect(got.count("message_start")).toBe(1);
      expect(got.count("message_stop")).toBe(1);
      expect(got.blocks.filter((b) => b.type === "text").map((b) => b.text ?? "").join("")).toContain(`the mode said: ${m}`);

      const asked = up.with(m);
      expect(asked.length, "the provider was asked twice: the turn, then the turn with the call's result").toBe(2);
      const offered = offeredTool(asked[0]!.body, TOOL);
      expect(offered, "the template's tool is offered").not.toBeNull();
      expect(offered!.description).toBe(templateTool().description);
      expect(JSON.stringify(offered!.parameters)).toContain('"text"');
      expect(JSON.stringify(asked[0]!.body)).toContain(inJson(templatePrompt()));
      expect(resultOf(asked[0]!.body)).toBeNull();
      expect(resultOf(asked[1]!.body)).toBe(m);
    });
  }
});
