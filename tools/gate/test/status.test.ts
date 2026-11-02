// exitStatusOf answers as a shell would for a finished child, whichever shape Bun hands it.
import { describe, expect, test } from "bun:test";
import { exitStatusOf } from "../src/lib/status.ts";

describe("exitStatusOf", () => {
  test("a plain exit code passes through", () => {
    expect(exitStatusOf({ exitCode: 3, signalCode: null })).toBe(3);
  });
  test("a child killed by a named signal reports 128 + its number, as bash does", () => {
    expect(exitStatusOf({ exitCode: null, signalCode: "SIGTERM" })).toBe(143);
    expect(exitStatusOf({ exitCode: null, signalCode: "SIGINT" })).toBe(130);
  });
  test("a child killed by a numbered signal (Bun.spawnSync since @types/bun 1.4.4) reports the same status", () => {
    expect(exitStatusOf({ exitCode: null, signalCode: 15 })).toBe(143);
    expect(exitStatusOf({ exitCode: null, signalCode: 2 })).toBe(130);
  });
  test("neither a code nor a signal reads as 1", () => {
    expect(exitStatusOf({ exitCode: null })).toBe(1);
  });
});
