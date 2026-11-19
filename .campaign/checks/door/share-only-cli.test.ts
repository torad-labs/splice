// share-door-only, cli-door-only: `splice share status` on the box prints its door and nothing per command; on a node,
// `splice remote add` of one command's address on a box that holds no key of this node's is refused with the add for
// the box's door, the box alone (or `--as <name>`) links its door, `splice remote` says a door remote is ready by
// asking the door, and a box that can't be reached is said with nothing written.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { readFileSync } from "node:fs";
import { type Splice, freePort } from "../server/lib/harness.ts";
import { type Box, addLine, box, doorOpen, node, plain } from "./lib/share.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let b: Box;
let n: Splice;

beforeAll(async () => {
  b = await box();
  await doorOpen(b);
  n = await node(b);
}, 600_000);

afterAll(async () => {
  await n?.dispose();
  await b?.s.dispose();
  b?.astra.stop();
});

const config = () => readFileSync(n.config, "utf8");

describe("cli-door-only", () => {
  test("splice share status prints the door, its add line and the Mantle variables, and no command's own address", () => {
    const r = b.s.cli(["share", "status"]);
    const text = plain(r.out + r.err);
    expect([r.code, text]).toEqual([0, text]);
    expect(text).toMatch(new RegExp(`splice remote add http://\\S+:${b.port}(\\s|$)`, "m"));
    expect(text).toMatch(new RegExp(`ANTHROPIC_BEDROCK_MANTLE_BASE_URL=http://\\S+:${b.port}/door`));
    expect(text).toContain("CLAUDE_CODE_USE_MANTLE=1");
    expect(text).toContain("AWS_BEARER_TOKEN_BEDROCK=splice-lan");
    expect(text).toMatch(/\d+ models?/);
    expect(text).not.toMatch(new RegExp(`:${b.port}/(claude-astra|astra)\\b`));
    expect(text).not.toMatch(/deprecated/i);
    expect(text).not.toContain("?auth=");
    expect(text).not.toContain("?models=");
  });

  test("remote add of one command's address on a keyless box is refused with the add for its door, writing nothing", () => {
    for (const address of [`${b.base}/claude-astra`, `${b.base}/claude-astra?auth=work`, `${b.base}/astra`]) {
      const before = config();
      const r = n.cli(["remote", "add", address]);
      const text = plain(r.out + r.err);
      expect([address, r.code === 0]).toEqual([address, false]);
      expect(text).toContain(addLine(b));
      expect(text).toMatch(/door/i);
      expect(config()).toBe(before);
    }
  });

  test("remote add of the box alone, named with --as, links its door", () => {
    const r = n.cli(["remote", "add", b.base, "--as", "boxdoor"]);
    expect([r.code, plain(r.out + r.err)]).toEqual([0, plain(r.out + r.err)]);
    expect(config()).toContain("[remote.boxdoor]");
    expect(config()).toContain(`"${b.base}/door"`);
  });

  test("splice remote says the door remote is ready, with the models the door serves", () => {
    const r = n.cli(["remote"]);
    expect(r.code).toBe(0);
    const line = plain(r.out).split("\n").find((l) => l.trimStart().startsWith("boxdoor ")) ?? "";
    expect(line).toMatch(/ready on .+'s door, \d+ models?/);
  });

  test("remote add of a box that can't be reached says so and writes nothing", async () => {
    const closed = await freePort();
    const before = config();
    const r = n.cli(["remote", "add", `http://127.0.0.1:${closed}`, "--as", "gone"]);
    expect(r.code).not.toBe(0);
    expect(plain(r.out + r.err)).toMatch(/can't be reached/);
    expect(config()).toBe(before);
  });
});
