// Red-green proof for the campaign-ledger guard (.claude/hooks/modules/pretooluse/08_manifest_single_channel.ts),
// which no ladder row ran (v0.4.0 tooling review): a guard that stops refusing raw ledger edits
// leaves no signal until two seats' hand edits collide in the ledger.
//
// Every arm drives the REAL entry — `.claude/hooks/orchestrator/pretooluse.ts`, the command
// .claude/settings.json routes Write|Edit|MultiEdit to — as a subprocess with a synthetic hook event.
// It runs against a HERMETIC COPY of the tracked hook tree: the runner turns itself off when
// `.claude/state/enforcement-disabled` exists, which is gitignored, so a checkout carrying that flag
// would make every allow arm pass and every block arm fail for a reason no arm names. A block is exit 2
// with the reason on stderr (runner.ts emitBlock); an allow is exit 0 with nothing said.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { layout } from "../src/lib/repo.ts";

const { repoRoot } = layout();
const BUN = process.execPath;
const ENTRY = ".claude/hooks/orchestrator/pretooluse.ts";
const LEDGER = ".dev/campaigns/v0.4.0.toml";
const ASSET = ".dev/campaigns/v0.4.0/walls/wall_registry.toml";
const TRACKED_HOOKS = [".claude/hooks/orchestrator", ".claude/hooks/lib", ".claude/hooks/modules/pretooluse"];

let root = "";
beforeAll(() => {
  root = mkdtempSync(join(tmpdir(), "splice-ledger-guard-"));
  for (const dir of TRACKED_HOOKS) cpSync(join(repoRoot, dir), join(root, dir), { recursive: true });
  writeFileSync(join(root, "sgconfig.yml"), ""); // findProjectRoot's marker, beside .claude/
  for (const file of [LEDGER, ASSET]) {
    mkdirSync(join(root, file, ".."), { recursive: true });
    writeFileSync(join(root, file), "[campaign]\n");
  }
});
afterAll(() => rmSync(root, { recursive: true, force: true }));

function runGuard(event: Record<string, unknown>): { exit: number | null; stderr: string } {
  const env: Record<string, string | undefined> = { ...process.env, CLAUDE_PROJECT_DIR: root };
  delete env.OC_WORKSPACES_HOOKS; // the runner's launch-time kill switch
  const proc = Bun.spawnSync([BUN, join(root, ENTRY)], {
    stdin: Buffer.from(JSON.stringify({ cwd: root, ...event })),
    env,
    stdout: "pipe",
    stderr: "pipe",
    timeout: 30000,
  });
  return { exit: proc.exitCode, stderr: proc.stderr.toString() };
}

const edit = (rel: string) => ({ tool_name: "Edit", tool_input: { file_path: join(root, rel), old_string: "a", new_string: "b" } });
const write = (rel: string) => ({ tool_name: "Write", tool_input: { file_path: join(root, rel), content: "[campaign]\n" } });

describe("the campaign-ledger guard, through the PreToolUse entry", () => {
  test("a raw Edit of a ledger is refused by name", () => {
    const { exit, stderr } = runGuard(edit(LEDGER));
    expect(exit, stderr).toBe(2);
    expect(stderr).toContain("§manifest-single-channel");
    expect(stderr).toContain(`target: ${LEDGER}`);
  });

  test("a Write over an existing ledger, and a relative path to it, are refused too", () => {
    expect(runGuard(write(LEDGER)).exit).toBe(2);
    expect(runGuard({ tool_name: "MultiEdit", tool_input: { file_path: LEDGER, edits: [] } }).exit).toBe(2);
  });

  test("a campaign's own asset below the ledger directory stays writable", () => {
    const { exit, stderr } = runGuard(edit(ASSET));
    expect(exit, stderr).toBe(0);
    expect(stderr).toBe("");
  });

  test("a Write that creates a new ledger is its birth, and is allowed", () => {
    const { exit, stderr } = runGuard(write(".dev/campaigns/v0.5.0.toml"));
    expect(exit, stderr).toBe(0);
    expect(stderr).toBe("");
  });

  test("the entry these arms drive is the one .claude/settings.json routes writes to", () => {
    const settings = JSON.parse(readFileSync(join(repoRoot, ".claude", "settings.json"), "utf8")) as {
      hooks: { PreToolUse: { matcher: string; hooks: { command: string }[] }[] };
    };
    const routed = settings.hooks.PreToolUse.filter((group) => /\bEdit\b/.test(group.matcher) && /\bWrite\b/.test(group.matcher))
      .flatMap((group) => group.hooks.map((hook) => hook.command));
    expect(routed).toContain(`bun $CLAUDE_PROJECT_DIR/${ENTRY}`);
  });
});
