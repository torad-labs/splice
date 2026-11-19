// door-session-relaunch: a running session started on the door is relaunched as a head's session is, on the command
// it was started with: a relaunch is never refused because the session "runs on no splice command". Here no terminal
// holds either session, so neither can be ended and started again; each is refused for that, by name, as a head's is.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { slottedHead } from "../desktop/lib/desktop.ts";
import { type Box, box, doorOpen, node } from "../door/lib/share.ts";
import { Splice, freePort, until } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { type Started, row, start } from "./lib/launch.ts";

setDefaultTimeout(300_000);

let astra: Upstream;
let s: Splice;
let b: Box;
let n: Splice;
const started: Started[] = [];

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }),
  );
  b = await box();
  await doorOpen(b);
  n = await node(b, `\n[remote.claude]\nurl = "${b.base}/door"\n`);
}, 600_000);

afterAll(async () => {
  for (const x of started) x.stop();
  await s?.dispose();
  await n?.dispose();
  await b?.s.dispose();
  b?.astra.stop();
  astra?.stop();
});

/** The answer to relaunching the session `x`, once `on` lists it as a door session. */
async function relaunch(on: Splice, x: Started): Promise<{ status: number; error: string; reason?: string }> {
  await until("the session is listed as a door session", async () => (await row(on, x.session))?.route === "door");
  const r = await on.api(`/api/sessions/${x.session}/relaunch`, { method: "POST" });
  const body = (await r.json()) as { error?: string; reason?: string };
  return { status: r.status, error: body.error ?? "", reason: body.reason };
}

describe("door-session-relaunch", () => {
  test("a session on this machine's door is relaunched on its command, not refused for having none", async () => {
    const x = await start(s, "claude");
    started.push(x);
    const a = await relaunch(s, x);
    expect(a.reason).not.toBe("head_unknown");
    expect(a.error).not.toContain("runs on no splice command");
    expect(a.error).toMatch(/\bclaude\b/);
  });

  test("a session on another machine's door is relaunched on its command here", async () => {
    const x = await start(n, "claude");
    started.push(x);
    const a = await relaunch(n, x);
    expect(a.reason).not.toBe("head_unknown");
    expect(a.error).not.toContain("runs on no splice command");
    expect(a.error).toMatch(/\bclaude\b/);
  });
});
