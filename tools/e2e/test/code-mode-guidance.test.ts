// The packaged code-mode mock checks that a Responses lite turn keeps the caller's instructions and the code-mode
// guidance together in one developer item right after additional_tools. Since a493ed3e5 (Marcos, 2026-10-28) the head's
// standing prompt rides as a further developer item after those leading ones, so the check accepts a head prompt there
// and still refuses guidance or base instructions that moved into, or were copied to, another item.
import { expect, test } from "bun:test";
import { assertGuidance, CALLER_SYSTEM, EXEC_TOOL, GUIDANCE } from "../src/commands/code-mode.ts";
import { loadsBytes } from "../src/compat/python-json.ts";

const tools = { type: "additional_tools", role: "developer", tools: [{ type: "custom", name: EXEC_TOOL }] };
const base = { role: "developer", content: `${CALLER_SYSTEM}\n\n${GUIDANCE}` };
const headPrompt = { role: "developer", content: "# Working in Claude Code through splice\n\nThe head's standing prompt." };
const user = { role: "user", content: [{ type: "input_text", text: "LOOKUP_EDIT" }] };
const body = (...input: unknown[]) => loadsBytes(new TextEncoder().encode(JSON.stringify({ model: "gpt-6-astra", input })));

test("a lite turn with the head's standing prompt after its base instructions keeps the caller and guidance together", () => {
  expect(() => assertGuidance(body(tools, base, headPrompt, user), CALLER_SYSTEM, true)).not.toThrow();
});

test("a lite turn with no head prompt passes as before", () => {
  expect(() => assertGuidance(body(tools, base, user), CALLER_SYSTEM, true)).not.toThrow();
});

test("guidance copied into the head's item is a duplicate", () => {
  const copied = { role: "developer", content: `${headPrompt.content}\n\n${GUIDANCE}` };
  expect(() => assertGuidance(body(tools, base, copied, user), CALLER_SYSTEM, true)).toThrow("developer instructions moved or duplicated");
});

test("base instructions behind the head's prompt have moved", () => {
  expect(() => assertGuidance(body(tools, headPrompt, base, user), CALLER_SYSTEM, true)).toThrow("developer instructions moved or duplicated");
});

test("with code mode off, caller instructions copied to a later item are a duplicate", () => {
  const caller = { role: "developer", content: CALLER_SYSTEM };
  expect(() => assertGuidance(body(tools, caller, caller, user), CALLER_SYSTEM, false)).toThrow("developer instructions moved or duplicated");
});
