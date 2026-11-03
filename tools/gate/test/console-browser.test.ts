// The consoleTests row (tools/gate/config/ladder.json) launches Chromium, and the gate runs every row wherever a workflow
// runs it whole: ci.yml's ladder on a push to main, and release.yml's repository gates on a v* tag or a push to prod. A
// runner has no Playwright browser and bun install downloads none, so every job that runs the gate installs one first.
// PR 456's review, round 1: release.yml did not, and the v0.4.0 tag would have stopped at its gate before the jar.
import { describe, expect, test } from "bun:test";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { layout } from "../src/lib/repo.ts";

type Step = { name?: string; run?: string; uses?: string };
type Workflow = { jobs?: Record<string, { steps?: Step[] }> };

const { repoRoot } = layout();
const workflows = join(repoRoot, ".github/workflows");
// the whole gate, not npm run gate:rules or gate:audit, which run one leg and launch no browser (round 2's suggestion)
const runsGate = (step: Step) => /(?:^|\s)(?:npm run gate|bun tools\/gate run)(?=\s|$)/m.test(step.run ?? "");
const installsChromium = (step: Step) => /\bplaywright install\b.*\bchromium\b/.test(step.run ?? "");

const jobs = readdirSync(workflows)
  .filter((file) => /\.ya?ml$/.test(file))
  .flatMap((file) => {
    const parsed = Bun.YAML.parse(readFileSync(join(workflows, file), "utf8")) as Workflow;
    return Object.entries(parsed.jobs ?? {}).map(([name, job]) => ({ where: `${file} ${name}`, steps: job.steps ?? [] }));
  });
const gating = jobs.filter((job) => job.steps.some(runsGate));

describe("every workflow job that runs the gate has Chromium for the console's tests", () => {
  test("the gate runs in ci.yml's ladder and in release.yml's build", () => {
    const where = gating.map((job) => job.where);
    expect(where).toContain("ci.yml ladder");
    expect(where).toContain("release.yml build");
  });

  test("each installs Playwright's Chromium before its first gate step", () => {
    for (const job of gating) {
      const gate = job.steps.findIndex(runsGate);
      const browser = job.steps.findIndex(installsChromium);
      expect(browser, `${job.where} installs Chromium`).toBeGreaterThanOrEqual(0);
      expect(browser, `${job.where} installs it before the gate`).toBeLessThan(gate);
    }
  });
});
