// Red-green proof for docker/topology_checks.ts, the port of inside.sh's three inline python3 checks.
// example-heads runs against the SHIPPED example topology with a fake daemon and fake wrappers: the green
// fixture satisfies the contract, each red fixture breaks exactly one clause of it.
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const SCRIPT = join(import.meta.dir, "..", "docker", "topology_checks.ts");
const EXAMPLE = join(import.meta.dir, "..", "..", "..", "app", "src", "main", "resources", "splice.example.toml");

async function run(args: string[], stdin = "", env: Record<string, string> = {}) {
  const proc = Bun.spawn(["bun", SCRIPT, ...args], { stdin: new TextEncoder().encode(stdin), stdout: "pipe", stderr: "pipe", env: { ...process.env, ...env } });
  const [out, code] = await Promise.all([new Response(proc.stdout).text(), proc.exited]);
  return { out, code };
}

describe("count-tokens", () => {
  test("an integer input_tokens passes", async () => expect((await run(["count-tokens"], '{"input_tokens": 12}')).code).toBe(0));
  test("a string input_tokens fails", async () => expect((await run(["count-tokens"], '{"input_tokens": "12"}')).code).toBe(1));
  test("a missing input_tokens fails", async () => expect((await run(["count-tokens"], "{}")).code).toBe(1));
});

describe("launch-recipe", () => {
  const good = { env: { ANTHROPIC_BASE_URL: "http://127.0.0.1:3101", API_TIMEOUT_MS: "3600000", CLAUDE_CODE_RETRY_WATCHDOG: "1" }, argv: ["claude"] };
  const recipe = (patch: object) => JSON.stringify({ ...good, ...patch, env: { ...good.env, ...((patch as any).env ?? {}) } });
  test("the contract passes", async () => expect((await run(["launch-recipe", "3101"], recipe({}))).code).toBe(0));
  test("the wrong base URL fails", async () => expect((await run(["launch-recipe", "3102"], recipe({}))).code).toBe(1));
  test("a timeout at the daemon's 900s wall fails", async () =>
    expect((await run(["launch-recipe", "3101"], recipe({ env: { API_TIMEOUT_MS: "900000" } }))).code).toBe(1));
  test("a missing timeout fails", async () =>
    expect((await run(["launch-recipe", "3101"], recipe({ env: { API_TIMEOUT_MS: "" } }))).code).toBe(1));
  test("persistent retry not planted fails", async () =>
    expect((await run(["launch-recipe", "3101"], recipe({ env: { CLAUDE_CODE_RETRY_WATCHDOG: "0" } }))).code).toBe(1));
  for (const bad of ["Infinity", "900000.5", "1e6", "0x100000", "-5", " 3600000", "3600000 ", "1_000_000"]) {
    test(`API_TIMEOUT_MS ${JSON.stringify(bad)} is not a plain decimal integer and fails`, async () =>
      expect((await run(["launch-recipe", "3101"], recipe({ env: { API_TIMEOUT_MS: bad } }))).code).toBe(1));
  }
  test("a plain decimal integer past the wall passes", async () =>
    expect((await run(["launch-recipe", "3101"], recipe({ env: { API_TIMEOUT_MS: "900001" } }))).code).toBe(0));
  test("an empty argv fails", async () => expect((await run(["launch-recipe", "3101"], recipe({ argv: [] }))).code).toBe(1));
});

describe("example-heads", () => {
  type Row = Record<string, any>;
  const toml = Bun.TOML.parse(require("node:fs").readFileSync(EXAMPLE, "utf8")) as Row;
  const heads = Object.entries<Row>(toml["heads"]);
  const LOGIN_OUT: Record<string, string> = {
    "chatgpt-oauth": "open this URL to sign in https://auth.openai.com",
    "grok-oauth": "open this URL to sign in https://x",
    "kimi-oauth": "login error: could not start device login",
    "muse-oauth": "login error: could not start device login",
    "api-key": "pipe it instead: splice key set",
    client: "no browser login for that kind; signs in with Claude Code's own /login",
  };
  let root: string;
  let server: ReturnType<typeof Bun.serve>;
  let mutate: (head: string, env: Row) => void = () => {};

  const kindOf = (h: Row) => String(toml["providers"][h["provider"]]["auth"]["kind"]);
  const commandOf = (key: string, h: Row) => h["claude"]?.["command"] ?? key;

  function fixture(skipWrapper?: string) {
    root = mkdtempSync(join(tmpdir(), "topo-"));
    const home = join(root, "home");
    mkdirSync(join(home, ".claude", "sessions"), { recursive: true });
    mkdirSync(join(home, ".local", "bin"), { recursive: true });
    for (const [key, h] of heads) {
      const command = commandOf(key, h);
      if (command !== skipWrapper) {
        const wrapper = join(home, ".local", "bin", command);
        writeFileSync(wrapper, `#!/bin/sh\necho '${LOGIN_OUT[kindOf(h)]!.replaceAll("'", "'\\''")}'\n`);
        chmodSync(wrapper, 0o755);
      }
      const config = join(root, "config", key);
      mkdirSync(config, { recursive: true });
      symlinkSync(join(home, ".claude", "sessions"), join(config, "sessions"));
      writeFileSync(join(config, "settings.json"), "{}");
      writeFileSync(join(config, ".claude.json"), "{}");
    }
    writeFileSync(join(root, "heads.json"), JSON.stringify({ heads: heads.map(([key]) => ({ key, running: true, healthy: true })) }));
    return home;
  }

  beforeAll(() => {
    server = Bun.serve({
      port: 0,
      fetch(req) {
        const head = new URL(req.url).pathname.split("/").pop()!;
        const h = toml["heads"][head] as Row;
        const prov = toml["providers"][h["provider"]] as Row;
        const windows = new Map<string, unknown>((prov["models"] ?? []).map((m: Row) => [m["id"], m["context_window"]]));
        const want = h["context_window"] || windows.get(h["pinned_model"] ?? "") || prov["context_window"];
        const env: Row = { ANTHROPIC_MODEL: h["pinned_model"], CLAUDE_CONFIG_DIR: join(root, "config", head) };
        if (kindOf(h).toLowerCase() !== "client") env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"] = String(want);
        mutate(head, env);
        return Response.json({ env });
      },
    });
  });
  afterAll(() => {
    server.stop(true);
    rmSync(root, { recursive: true, force: true });
  });

  const go = (home: string, headsFile = join(root, "heads.json")) =>
    run(["example-heads", EXAMPLE, headsFile, String(server.port), home], "", { MGMT_KEY: "k" });

  test("the shipped example, launched and logged in to its own contract, passes", async () => {
    mutate = () => {};
    const { out, code } = await go(fixture());
    expect({ code, out: code ? out : "" }).toEqual({ code: 0, out: "" });
  });

  test("a head whose window is off the example's fails", async () => {
    const victim = heads.find(([, h]) => kindOf(h).toLowerCase() !== "client")![0];
    mutate = (head, env) => { if (head === victim) env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"] = "1"; };
    const { out, code } = await go(fixture());
    expect(code).toBe(1);
    expect(out).toContain("MISMATCH");
  });

  test("a head whose sessions directory is not linked to the shared registry fails", async () => {
    mutate = (head, env) => { if (head === heads[0]![0]) env["CLAUDE_CONFIG_DIR"] = join(root, "nowhere"); };
    expect((await go(fixture())).code).toBe(1);
  });

  test("a head without its wrapper on PATH fails", async () => {
    mutate = () => {};
    const [key, h] = heads[0]!;
    expect((await go(fixture(commandOf(key, h)))).code).toBe(1);
  });

  test("a topology that lists a different set of heads fails", async () => {
    mutate = () => {};
    const home = fixture();
    writeFileSync(join(root, "short.json"), JSON.stringify({ heads: [{ key: heads[0]![0] }] }));
    expect((await go(home, join(root, "short.json"))).code).toBe(1);
  });

  test("a login verb that never reaches its provider flow fails", async () => {
    mutate = () => {};
    const home = fixture();
    const [key, h] = heads[0]!;
    writeFileSync(join(home, ".local", "bin", commandOf(key, h)), "#!/bin/sh\necho nothing useful\n");
    const { out, code } = await go(home);
    expect(code).toBe(1);
    expect(out).toContain("MISSING");
  });
});
