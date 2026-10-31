// Goal (Marcos, Oct 30: "remove everything related to my machine"): the historical formatter and audio oracles run on
// any maintainer's machine. They read the Codex clone CODEX_REFERENCE_REPO names, as the current oracles do, and with
// none named they stop and say what to set and which revision the clone must hold. No folder of one machine is assumed.
import { afterEach, expect, test } from "bun:test";
import { historicalCodexSource } from "./current-source";

const saved = process.env.CODEX_REFERENCE_REPO;
afterEach(() => {
  if (saved === undefined) delete process.env.CODEX_REFERENCE_REPO;
  else process.env.CODEX_REFERENCE_REPO = saved;
});
const revision = "17a9df60e420b58e3edc55efb1bb052e39492bbc";

test("the historical oracles read the Codex clone CODEX_REFERENCE_REPO names", () => {
  process.env.CODEX_REFERENCE_REPO = "/srv/clones/codex";
  expect(historicalCodexSource(revision)).toBe("/srv/clones/codex");
});

test("with no clone named, the historical oracles stop and say what to set", () => {
  delete process.env.CODEX_REFERENCE_REPO;
  expect(() => historicalCodexSource(revision)).toThrow(`set CODEX_REFERENCE_REPO to a Codex clone that holds ${revision}`);
});
