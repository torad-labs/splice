import { expect, test } from "bun:test";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { execFileSync } from "node:child_process";
import { join } from "node:path";
import { layout } from "../../gate/src/lib/repo.ts";
import { shimMarkers, shimPath } from "../../release/src/lib/shim.ts";

// The real shim and packaged CLI, a synthetic control plane and a local Node client.
// The client reads its declaration AFTER exec, proving both record-before-exec and PID continuity.
test("the declaration names the live process after the real launcher exec", async () => {
  const { repoRoot, buildRoot } = layout();
  const jar = join(buildRoot, "app/build/libs/app-all.jar");
  expect(existsSync(jar), "build :app:shadowJar before this packaged launch proof").toBe(true);
  const shim = shimPath(repoRoot);
  const markers = shimMarkers(shim);
  if (markers instanceof Error) throw markers;
  const dir = mkdtempSync(join(tmpdir(), "splice-owner-"));
  const state = join(dir, "state");
  const config = join(dir, "splice.toml");
  const client = join(dir, "client.cjs");
  const realJava = execFileSync("which", ["java"], {encoding: "utf8"}).trim();
  const birthProbe = join(dir, "Birth.java");
  writeFileSync(birthProbe, `class Birth {
    public static void main(String[] args) {
      System.out.print(ProcessHandle.of(Long.parseLong(args[0])).orElseThrow()
        .info().startInstant().orElseThrow().toString());
    }
  }`);
  mkdirSync(state);
  writeFileSync(join(state, "mgmt-key"), "fixture-management-key");
  writeFileSync(client, `
    const fs = require("node:fs");
    const path = require("node:path");
    const file = path.join(process.env.SPLICE_STATE_DIR, "launch-owners", process.pid + ".json");
    const owner = JSON.parse(fs.readFileSync(file, "utf8"));
    const actualBirth = require("node:child_process").execFileSync(
      ${JSON.stringify(realJava)}, [${JSON.stringify(birthProbe)}, String(process.pid)], {encoding: "utf8"});
    if (owner.startedAt !== actualBirth) throw new Error("exec changed the declared process birth");
    process.stdout.write(JSON.stringify({pid: process.pid, owner, actualBirth, mode: fs.statSync(file).mode & 0o777}));
  `);
  const control = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    fetch(request) {
      const url = new URL(request.url);
      if (url.pathname === "/health") {
        return Response.json({ok: true, version: markers.gateway, wantShimVersion: markers.shim});
      }
      if (url.pathname === "/launch/fixture" || url.pathname === "/launch/claude") {
        return Response.json({
          argv: ["node", client], unset: [],
          env: {SPLICE: "1", ANTHROPIC_BASE_URL: "http://127.0.0.1:3101"},
        });
      }
      return new Response("unexpected fixture route", {status: 404});
    },
  });
  writeFileSync(config, `
[daemon]
control_port = ${control.port}
[providers.fixture]
dialect = "openai-chat"
base_url = "http://127.0.0.1:1"
auth = { kind = "api-key", env = "FIXTURE_KEY" }
[heads.fixture]
provider = "fixture"
port = 3101
discovery_prefix = "claude-fixture--"
pinned_model = "fixture-model"
  `);
  chmodSync(config, 0o600);
  try {
    const env = {
      PATH: process.env.PATH!, HOME: dir, SPLICE_HEAD: "fixture", SPLICE_JAR: jar,
      SPLICE_STATE_DIR: state, SPLICE_CONFIG: config, FIXTURE_KEY: "synthetic-key",
    };
    const child = Bun.spawn(["node", shim], {env, stdout: "pipe", stderr: "pipe"});
    const [out, err, code] = await Promise.all([
      new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited,
    ]);
    expect(code, err).toBe(0);
    expect(err).not.toContain("ownership could not be recorded");
    const result = JSON.parse(out);
    expect(result.pid).toBe(child.pid);
    expect(result.owner.pid).toBe(child.pid);
    expect(result.owner.startedAt).toBe(result.actualBirth);
    expect(result.owner.head).toBe("fixture");
    expect(result.owner.baseUrl).toBe("http://127.0.0.1:3101");
    expect(result.owner.kind).toBe("session");
    expect(result.mode).toBe(0o600);
    expect(Number.isNaN(Date.parse(result.owner.startedAt))).toBe(false);
    const wrapped = Bun.spawn(["node", shim], {
      env: {...env, SPLICE_HEAD: "claude"}, stdout: "pipe", stderr: "pipe",
    });
    const [wrappedOut, wrappedErr, wrappedCode] = await Promise.all([
      new Response(wrapped.stdout).text(), new Response(wrapped.stderr).text(), wrapped.exited,
    ]);
    expect(wrappedCode, wrappedErr).toBe(0);
    expect(JSON.parse(wrappedOut).owner.head).toBe("fixture");
    // Real ownership writer, but the final login is a local Node stand-in, never a provider request.
    const bin = join(dir, "bin");
    mkdirSync(bin);
    const fakeJava = join(bin, "java");
    writeFileSync(fakeJava, `#!/usr/bin/env node
      const args = process.argv.slice(2);
      if (args.includes("record-launch")) {
        process.execve(${JSON.stringify(realJava)}, ["java", ...args], process.env);
      }
      require(${JSON.stringify(client)});
    `);
    chmodSync(fakeJava, 0o700);
    const login = Bun.spawn(["node", shim, "login", "--label", "work", "fixture"], {
      env: {...env, PATH: bin + ":" + env.PATH, SPLICE_HEAD: "splice"},
      stdout: "pipe", stderr: "pipe",
    });
    const [loginOut, loginErr, loginCode] = await Promise.all([
      new Response(login.stdout).text(), new Response(login.stderr).text(), login.exited,
    ]);
    expect(loginCode, loginErr).toBe(0);
    const loginRecord = JSON.parse(loginOut).owner;
    expect(loginRecord.pid).toBe(login.pid);
    expect(loginRecord.head).toBe("fixture");
    expect(loginRecord.kind).toBe("login");
    // This proof must fail when the launcher omits its declaration, not merely observe an exec.
    const preload = join(dir, "omit-owner.cjs");
    writeFileSync(preload, `
      const cp = require("node:child_process");
      const original = cp.execFileSync;
      cp.execFileSync = function(command, args, options) {
        if (args.includes("record-launch")) throw new Error("synthetic omitted declaration");
        return original(command, args, options);
      };
    `);
    const mutant = Bun.spawn(["node", shim], {
      env: {...env, NODE_OPTIONS: "--require " + preload}, stdout: "pipe", stderr: "pipe",
    });
    const [mutantErr, mutantCode] = await Promise.all([
      new Response(mutant.stderr).text(), mutant.exited,
    ]);
    expect(mutantCode).not.toBe(0);
    expect(mutantErr).toContain("ownership could not be recorded");
    // A vanished parent cannot be declared by an unrelated child JVM.
    const refused = Bun.spawn(["java", "-jar", jar, "record-launch", String(child.pid),
      "fixture", "", "login", "hook"], {env, stdout: "pipe", stderr: "pipe"});
    expect(await refused.exited).toBe(1);
    expect(JSON.stringify(result.owner)).not.toContain("synthetic-key");
    expect(existsSync(join(state, "launch-owners", child.pid + ".json"))).toBe(false);
  } finally {
    control.stop(true);
    rmSync(dir, {recursive: true, force: true});
  }
}, 30_000);
