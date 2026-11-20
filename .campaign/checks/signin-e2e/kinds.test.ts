// signin-e2e kinds: each kind splice signs in, driven end to end on its own by `bun tools/e2e signin --kind <kind>`:
// the page opens, the login completes, the gateway serves a turn on it, and a failure says so. Run one kind with
// `bun test ./.campaign/checks/signin-e2e/kinds.test.ts -t '<kind> signs in'`; it needs Java, Bun and Playwright's
// Chromium, so it runs on a Mac that has Java (`rig remote run --needs system=Darwin/arm64 --needs java -- ...`).

import { describe, setDefaultTimeout, test } from "bun:test";
import { judgeRun, registryKinds } from "./lib/report.ts";

// The first run of a tree builds the jar; no test here is a claim about speed.
setDefaultTimeout(45 * 60_000);

describe("signin-e2e kinds", () => {
  for (const kind of registryKinds()) {
    test(`${kind} signs in end to end`, () => judgeRun(kind));
  }
});
