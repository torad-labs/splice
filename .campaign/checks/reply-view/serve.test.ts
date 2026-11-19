// rv-page (serving): a Splice built from this checkout serves the reader at stable paths on its control plane, open
// like the console's other files (a page carries no data), byte for byte the files in this checkout, so any client of
// its API loads the version that matches the feed it reads. /reply-view/ opens the reader's page. Nothing else in the
// folder is served. Runs on Linux (the harness reads /proc).
import { afterAll, beforeAll, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { freePort, mockHead, Splice } from "../server/lib/harness.ts";
import { READER } from "./lib/rv.ts";

let s: Splice;
let base = "";
beforeAll(async () => {
  s = await Splice.boot(mockHead({ key: "acme", command: "claude-acme", port: await freePort(), url: "http://127.0.0.1:9", model: "acme-large-2" }));
  base = `http://127.0.0.1:${s.controlPort}`;
}, 1_800_000);
afterAll(async () => {
  await s?.dispose();
}, 60_000);

test("the module, its stylesheet, the connector and the page are served without a key, as they are in this checkout", async () => {
  for (const [name, type] of [
    ["reply-view.js", "javascript"],
    ["reply-view.css", "text/css"],
    ["connect.js", "javascript"],
    ["index.html", "text/html"],
  ] as const) {
    const res = await fetch(`${base}/reply-view/${name}`);
    expect(res.status, name).toBe(200);
    expect(res.headers.get("content-type") ?? "", name).toContain(type);
    expect(Buffer.from(await res.arrayBuffer()).equals(readFileSync(join(READER, name))), `${name} differs from this checkout`).toBe(true);
  }
});

test("/reply-view/ opens the reader's page, and nothing but the reader's files is served from its folder", async () => {
  const res = await fetch(`${base}/reply-view/?session=aaaa1111`, { redirect: "manual" });
  if ([301, 302, 303, 307, 308].includes(res.status)) {
    expect(res.headers.get("location")).toBe("/reply-view/index.html?session=aaaa1111");
  } else {
    expect(res.status).toBe(200);
    expect(await res.text()).toBe(readFileSync(join(READER, "index.html"), "utf8"));
  }
  expect((await fetch(`${base}/reply-view/README.md`)).status).toBe(404);
  expect((await fetch(`${base}/reply-view/nested/reply-view.js`)).status).toBe(404);
});
