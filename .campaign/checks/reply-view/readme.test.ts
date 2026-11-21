// rv-readme: the README beside the module tells a host how to embed the reader: how to load it and mount it, how to feed
// it (the shapes it reads, from the transcript route and the live feed), the connector for splice's own API, every
// custom property its stylesheet reads, and the Content-Security-Policy it keeps. It names no person or machine.
import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { READER } from "./lib/rv.ts";

test("the README says how to embed the reader, every custom property its stylesheet reads, and what it reads", () => {
  const readme = readFileSync(join(READER, "README.md"), "utf8");
  const css = readFileSync(join(READER, "reply-view.css"), "utf8");
  const props = [...new Set([...css.matchAll(/var\(\s*(--rv-[a-z0-9-]+)/g)].map((m) => m[1]!))];
  expect(props.length).toBeGreaterThanOrEqual(8);
  expect(props.filter((p) => !readme.includes(p))).toEqual([]);
  for (const word of [
    "mount(", "addHistory", "addLive", "destroy", "replyview:change", "connect(", "onLocked", "earlier(",
    "/reply-view/reply-view.js", "/reply-view/reply-view.css", "/reply-view/connect.js", "/reply-view/index.html",
    "/api/sessions/{id}/transcript", "/api/sessions/{id}/live", "Content-Security-Policy", "THIRD_PARTY_NOTICES.md",
  ]) {
    expect(readme, `README does not name ${word}`).toContain(word);
  }
  for (const field of ["index", "role", "tool_use_id", "message", "thinking", "compact", "hello", "turn", "text", "tool", "end"]) {
    expect(readme, `README does not name the field or event ${field}`).toMatch(new RegExp(`\`${field}\``));
  }
  expect(readme).not.toMatch(/\/home\/[a-z]|\/Users\/[A-Za-z]|Marcos|Telegram/);
});
