// A mock prints one JSON line with its port; the scenario scripts read it back. A line that is cut off must stop the
// run at the step that reads it, naming the mock and the field, not leave an empty port for a config to fail on later.
import { expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";

const lib = join(layout().repoRoot, "tools/e2e/docker/lib.sh");

function readPorts(chatLine: string) {
  const dir = mkdtempSync(join(tmpdir(), "mock-ports-"));
  writeFileSync(join(dir, "mock_codex.out"), `{"port":4001,"auth_path":"/tmp/auth.json"}\n`);
  writeFileSync(join(dir, "mock_chat.out"), chatLine);
  const run = spawnSync("bash", ["-c", `source "${lib}"; read_mock_ports; echo "REACHED chat=$CHAT_MOCK_PORT"`], {
    env: { PATH: process.env.PATH ?? "", HOME: dir, OUT: dir, ARTIFACTS: dir, REPO: layout().repoRoot },
    encoding: "utf8",
  });
  return { out: run.stdout + run.stderr, status: run.status };
}

test("every port reads: the run goes on with them", () => {
  const { out, status } = readPorts(`{"port":4002}\n`);
  expect(status).toBe(0);
  expect(out).toContain("REACHED chat=4002");
});

test("a mock whose first line is cut off stops the run at the port step, naming the mock", () => {
  const { out, status } = readPorts(`{"po`);
  expect(status).not.toBe(0);
  expect(out).toContain("chat mock: cannot read 'port' from mock_chat.out");
  expect(out).not.toContain("REACHED");
});
