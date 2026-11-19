// door-launch-record: `claude` started on the door, on this machine or on another machine's door, starts without the
// warning that its launch could not be recorded, and splice keeps that launch's record: the command it was started as,
// and the door's address its Claude Code sends its turns to. A head's command is recorded as it always was.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { slottedHead } from "../desktop/lib/desktop.ts";
import { lane } from "../door/lib/claude.ts";
import { type Box, box, doorOpen, node } from "../door/lib/share.ts";
import { Splice, freePort } from "../server/lib/harness.ts";
import { Upstream } from "../server/lib/upstream.ts";
import { NOT_RECORDED, type Started, address, launchRecord, start } from "./lib/launch.ts";

setDefaultTimeout(300_000);

let astra: Upstream;
let s: Splice;
let b: Box;
let n: Splice;
const started: Started[] = [];

beforeAll(async () => {
  astra = new Upstream("astra", "astra-large").start();
  // no head is `claude`, so `claude` is on the door
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

describe("door-launch-record", () => {
  test("claude on this machine's door starts without the warning, and its launch is recorded at the door", async () => {
    const x = await start(s, "claude");
    started.push(x);
    expect(x.env.CLAUDE_CODE_USE_MANTLE).toBe("1");
    expect(address(x)).toBe(lane(s).door!);
    expect(x.said()).not.toContain(NOT_RECORDED);
    const r = launchRecord(s, x.pid);
    expect(r).not.toBeNull();
    expect([r?.head, r?.baseUrl, r?.kind]).toEqual(["claude", lane(s).door!, "session"]);
  });

  test("claude on another machine's door starts without the warning, and its launch is recorded at that door", async () => {
    const x = await start(n, "claude");
    started.push(x);
    expect(address(x)).toBe(`${b.base}/door`);
    expect(x.said()).not.toContain(NOT_RECORDED);
    const r = launchRecord(n, x.pid);
    expect(r).not.toBeNull();
    expect([r?.head, r?.baseUrl, r?.kind]).toEqual(["claude", `${b.base}/door`, "session"]);
    // the box that serves the door keeps no record of a launch it did not make
    expect(launchRecord(b.s, x.pid)).toBeNull();
  });

  test("a head's own command is recorded as before, at its head", async () => {
    const x = await start(s, "claude-astra");
    started.push(x);
    expect(x.said()).not.toContain(NOT_RECORDED);
    const r = launchRecord(s, x.pid);
    expect([r?.head, r?.baseUrl, r?.kind]).toEqual(["astra", address(x), "session"]);
  });
});
