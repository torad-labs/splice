// A self-served chat model gets each earlier turn's reasoning back as that assistant turn's `reasoning_content`, which
// its chat template renders inside the turn's think block (Marcos, 2026-10-23). The conformance suite's chat head is
// self-served by splice's own rule (openai-chat on a loopback address), so its reasoning cell grades that field: the
// reasoning arrives whole on the assistant turn, never as visible text and never on another role.
import { expect, test } from "bun:test";
import { gradeCheck, type Check, type Json } from "../src/commands/conformance.ts";

const THINK = "CONF-THINK weighing the two causes";
const ANSWER = "CONF-A1 it was the expired certificate.";
const check: Check = { kind: "reasoning", role: "assistant", text: THINK };

const chat = (...messages: Json[]): Json => ({ model: "conf-chat", messages });

test("the assistant turn's reasoning_content carrying the whole reasoning passes", () => {
  const body = chat({ role: "user", content: "CONF-U1" }, { role: "assistant", content: ANSWER, reasoning_content: THINK }, { role: "user", content: "CONF-U2" });
  expect(gradeCheck("openai-chat", body, check)).toBeNull();
});

test("an assistant turn sent without its reasoning fails, naming the field", () => {
  const body = chat({ role: "user", content: "CONF-U1" }, { role: "assistant", content: ANSWER }, { role: "user", content: "CONF-U2" });
  expect(gradeCheck("openai-chat", body, check)).toContain("reasoning_content");
});

test("reasoning that arrived as the turn's visible text fails, saying where it went", () => {
  const body = chat({ role: "user", content: "CONF-U1" }, { role: "assistant", content: `${THINK}\n${ANSWER}` }, { role: "user", content: "CONF-U2" });
  expect(gradeCheck("openai-chat", body, check)).toContain("visible text");
});

test("reasoning carried on another role, or cut short, fails", () => {
  const onUser = chat({ role: "user", content: "CONF-U1", reasoning_content: THINK }, { role: "assistant", content: ANSWER });
  const cut = chat({ role: "user", content: "CONF-U1" }, { role: "assistant", content: ANSWER, reasoning_content: THINK.slice(0, 20) });
  expect(typeof gradeCheck("openai-chat", onUser, check)).toBe("string");
  expect(typeof gradeCheck("openai-chat", cut, check)).toBe("string");
});

test("the reasoning on two assistant turns fails, since one earlier turn carried it", () => {
  const twice = chat({ role: "assistant", content: ANSWER, reasoning_content: THINK }, { role: "assistant", content: "CONF-A2", reasoning_content: THINK });
  expect(typeof gradeCheck("openai-chat", twice, check)).toBe("string");
});
