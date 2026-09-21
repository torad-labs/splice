// THE LAUNCH SHIM'S REHEARSAL — checks/release/launcher-test.sh, arm for arm, against a real
// loopback daemon, a mocked JVM and a mocked supervisor unit.
//
// WHY NOT A FAKE `curl` (the bash harness's mechanism, launcher-test.sh:27-75): the shim is a Node
// script as of PR 6 and speaks node:http, so a curl stub on PATH is never called — under that
// harness every /health read comes back "" and every arm after the first is dead while the script
// still reports OK. The daemon is therefore a REAL HTTP server here, bound to loopback on a port
// the kernel picks, and the arms that were "the URL curl was asked for" are now "the request the
// daemon received". The state machine is unchanged: the daemon-state FILE is still the mock
// daemon's memory, because the mocked `java` and `systemctl` are separate processes that flip it.
//
// What stays a PATH mock is what the shim genuinely spawns: `java` (coldStart's `spawn("java", …)`)
// and `systemctl` (unitExists/startThroughUnit's execFileSync, frozen text — PR #171).
//
// The mock daemon's "current" version is DERIVED from the shim under test, never hardcoded — a
// snapshot literal goes stale on the first version bump and flips every "up" daemon to "stale",
// sending the launcher down the replace path against the mock (found by the v0.2.0 bump: the
// hardcoded 0.1.1 made this test fail on exactly the commit that mattered). src/lib/shim.ts is the
// one reader of the markers, so this file and `release accept` cannot disagree about them.
//
// The arms run IN ORDER in ONE sandbox, as the script's did — several depend on what the previous
// one left in the daemon-state file.
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { shimMarkers } from "./shim.ts";
import { makeSandbox, writeStub } from "./sandbox.ts";
import { exitStatusOf } from "../../../gate/src/lib/status.ts";

/** The selector env vars that mean "a harness's own daemon": the unit is never touched for these.
 *  The same nine the shim's `unitDefaults()` reads. */
export const SELECTORS = [
  "SPLICE_CONFIG", "XDG_CONFIG_HOME", "SPLICE_JAR", "SPLICE_SHARE_DIR", "SPLICE_STATE_DIR",
  "CLAUDEX_STATE_DIR", "SPLICE_CONTROL_PORT", "CONTROL_PROXY_PORT", "CONTROL_PORT",
] as const;

interface Exchange {
  readonly url: string;
  readonly body: string;
  readonly authorization: string;
}

interface Daemon {
  /** the port the TOML names */
  readonly toml: number;
  /** the port state/config.json names — the one that must WIN */
  readonly state: number;
  /** flipped per arm, the way the curl stub read its env */
  topologyStale: boolean;
  injectEnvKey: boolean;
  pwnedFile: string;
  lastLaunch?: Exchange;
  lastShutdown?: Exchange;
  /** Drop what the last arm recorded. A method, not an assignment: an arm that cleared a field by
   *  hand would have the compiler narrow it to `undefined` and the assertion after it to `never`. */
  forget(): void;
  stop(): void;
}

interface Ctx {
  readonly dir: string;
  readonly shim: string;
  readonly daemon: Daemon;
  readonly env: Record<string, string>;
  /** the harness's own daemon: the selectors that aim the shim at this sandbox */
  readonly harness: Record<string, string>;
  readonly captures: { java: string; unit: string; pwned: string; bootLog: string };
  readonly stateDir: string;
  readonly daemonState: string;
  launch(env: Record<string, string | undefined>, argv?: readonly string[]): Promise<Run>;
  cold(): void;
}

interface Run {
  readonly code: number;
  readonly stderr: string;
  /** stdout and stderr together, as `2>&1` produced */
  readonly output: string;
}

/** One arm of the rehearsal: a name, and a problem string when it fails. ASYNC, and every arm
 *  awaits its launch: the mock daemon serves from THIS process, so a blocking `spawnSync` would
 *  hold the event loop while the shim waits for a /health that can never be answered — measured,
 *  before this was async: 112s of the shim's own timeouts and "<no request>" on the first arm. */
interface Arm {
  readonly name: string;
  readonly run: (ctx: Ctx) => Promise<string | null>;
}

const read = (path: string): string => (existsSync(path) ? readFileSync(path, "utf8").trim() : "");

const ARMS: readonly Arm[] = [
  {
    name: "the TOML control port",
    run: async (ctx) => {
      await ctx.launch(ctx.harness, ["", "line one\nline two"]);
      const launch = ctx.daemon.lastLaunch;
      if (launch?.url !== `http://127.0.0.1:${ctx.daemon.toml}/launch/test`) {
        return `expected the TOML control port, got ${launch?.url ?? "<no request>"}`;
      }
      const body = JSON.parse(launch.body) as { args?: unknown };
      if (JSON.stringify(body.args) !== JSON.stringify(["", "line one\nline two"])) {
        return `the argv must reach the daemon verbatim, got ${JSON.stringify(body.args)}`;
      }
      if (launch.authorization !== "Bearer test-key") {
        return `the launch must carry the mgmt-key, got ${JSON.stringify(launch.authorization)}`;
      }
      return null;
    },
  },
  {
    // State config is above TOML in ConfigService's precedence and the shim must resolve the same
    // port or it will probe/launch the daemon at one address and call another.
    name: "state config outranks the TOML port",
    run: async (ctx) => {
      writeFileSync(join(ctx.stateDir, "config.json"), `{"controlPort":${ctx.daemon.state}}\n`);
      await ctx.launch(ctx.harness);
      const url = ctx.daemon.lastLaunch?.url;
      return url === `http://127.0.0.1:${ctx.daemon.state}/launch/test`
        ? null
        : `state config must outrank the TOML port, got ${url ?? "<no request>"}`;
    },
  },
  {
    name: "a stale daemon is shut down and replaced",
    run: async (ctx) => {
      writeFileSync(ctx.daemonState, "old\n");
      ctx.daemon.forget();
      await ctx.launch(ctx.harness);
      const shutdown = ctx.daemon.lastShutdown;
      if (shutdown?.url !== `http://127.0.0.1:${ctx.daemon.state}/api/daemon/shutdown`) {
        return `a stale daemon must be shut down at the resolved port, got ${shutdown?.url ?? "<no request>"}`;
      }
      if (shutdown.authorization !== "Bearer test-key") {
        return `the shutdown must carry the mgmt-key, got ${JSON.stringify(shutdown.authorization)}`;
      }
      return read(ctx.daemonState) === "new" ? null : "the stale daemon was not replaced";
    },
  },
  {
    // Regression: a recipe env key that is not a bare identifier must never reach the launched
    // process. The bash shim's risk was `eval "$CMD"` executing `X$(touch …)`; the Node shim
    // rejects the key by name, so BOTH are asserted — the file the substitution would have created
    // is absent, and the shim said why.
    name: "a malformed recipe env key is dropped, never executed",
    run: async (ctx) => {
      writeFileSync(ctx.daemonState, "new\n");
      rmSync(ctx.captures.pwned, { force: true });
      ctx.daemon.injectEnvKey = true;
      const run = await ctx.launch(ctx.harness);
      ctx.daemon.injectEnvKey = false;
      if (existsSync(ctx.captures.pwned)) return "a recipe env key was executed as a command substitution";
      if (!run.stderr.includes("ignoring malformed env key from daemon")) {
        return `the dropped key must be named on stderr, got: ${run.stderr}`;
      }
      return run.code === 0 ? null : `the rest of the recipe must still launch, exit was ${run.code}`;
    },
  },
  {
    // JW-01: a boot-dead daemon must leave a tailable trace, and the launcher must SHOW it on the
    // handshake failure instead of just "got <none>". The java stub writes its stack trace to
    // stderr, which the shim's redirect must capture in daemon-boot.log.
    name: "JW-01 the boot log is written and shown",
    run: async (ctx) => {
      writeFileSync(ctx.daemonState, "down\n");
      rmSync(ctx.captures.bootLog, { force: true });
      const run = await ctx.launch({ ...ctx.harness, LAUNCHER_JAVA_BOOT_FAILS: "1" });
      if (run.code === 0) return "a daemon that dies at boot must fail the launch";
      if (!run.output.includes("daemon-boot.log")) return `launcher must name the boot log, got: ${run.output}`;
      if (!run.output.includes("kaboom-at-boot")) return `launcher must print the boot-log tail, got: ${run.output}`;
      return readFileSync(ctx.captures.bootLog, "utf8").includes("kaboom-at-boot")
        ? null
        : "the boot log must hold the daemon's stderr";
    },
  },
  {
    // JW-04: a daemon reporting topologyStale=true must produce the non-fatal restart warning while
    // the launch still proceeds (warning shape mirrors the shim-staleness one).
    name: "JW-04 a stale topology warns and still launches",
    run: async (ctx) => {
      writeFileSync(ctx.daemonState, "up\n");
      ctx.daemon.topologyStale = true;
      const run = await ctx.launch(ctx.harness);
      ctx.daemon.topologyStale = false;
      if (!run.stderr.includes("running topology is stale")) {
        return `expected the stale-topology warning, got: ${run.stderr}`;
      }
      if (!run.stderr.includes("splice restart")) return `the warning must name the fix, got: ${run.stderr}`;
      return run.code === 0 ? null : `the launch must still proceed, exit was ${run.code}`;
    },
  },
  {
    // V4-189 / UF-01: with no selector override and splice.service on the box, a cold start STARTS
    // THE UNIT and waits for it; java is never spawned beside it (the raw nohup spawn is how three
    // stray daemons squatted :3096 on 2026-09-21).
    name: "UF-01 a cold start goes through the unit",
    run: async (ctx) => {
      ctx.cold();
      await ctx.launch({});
      if (read(ctx.captures.unit) !== "splice.service") {
        return `a cold start must start the unit, got ${read(ctx.captures.unit) || "<nothing>"}`;
      }
      if (existsSync(ctx.captures.java)) return "java must never be spawned beside the unit";
      const url = ctx.daemon.lastLaunch?.url;
      return url === `http://127.0.0.1:${ctx.daemon.toml}/launch/test`
        ? null
        : `the launch must reach the daemon the unit started, got ${url ?? "<no request>"}`;
    },
  },
  {
    // UF-02: the unit name is the operator's SPLICE_SUPERVISOR_UNIT, never a hardcoded splice.service.
    name: "UF-02 the unit is SPLICE_SUPERVISOR_UNIT",
    run: async (ctx) => {
      ctx.cold();
      await ctx.launch({ SPLICE_SUPERVISOR_UNIT: "splice-canary.service" });
      if (read(ctx.captures.unit) !== "splice-canary.service") {
        return `the unit must be SPLICE_SUPERVISOR_UNIT, got ${read(ctx.captures.unit) || "<nothing>"}`;
      }
      return existsSync(ctx.captures.java) ? "java must never be spawned beside the unit" : null;
    },
  },
  // UF-03: any selector override means a harness's own daemon — the unit is never touched, the raw
  // spawn runs. One arm per selector the guard names.
  ...SELECTORS.map((selector) => ({
    name: `UF-03 ${selector} raw-spawns instead of the unit`,
    run: async (ctx: Ctx) => {
      ctx.cold();
      await ctx.launch({ [selector]: selectorValue(selector, ctx) });
      if (existsSync(ctx.captures.unit)) return `${selector} set must never start the unit`;
      return read(ctx.captures.java) === "spawned" ? null : `${selector} set must raw-spawn`;
    },
  })),
  {
    // UF-04: a unit that never answers is reported on a wall-clock deadline and the shim exits 1 —
    // it never falls through to a raw spawn beside the unit it just started.
    name: "UF-04 a dead unit is a deadline, never a second daemon",
    run: async (ctx) => {
      ctx.cold();
      const run = await ctx.launch({ LAUNCHER_UNIT_BOOTS: "0", SPLICE_UNIT_WAIT_SECONDS: "1" });
      if (run.code !== 1) return `expected exit 1, got ${run.code}`;
      if (!run.output.includes("splice.service did not answer /health within 1s")) {
        return `expected the deadline message, got: ${run.output}`;
      }
      if (read(ctx.captures.unit) !== "splice.service") return "the unit must still have been started";
      return existsSync(ctx.captures.java) ? "a dead unit must never fall through to a raw spawn" : null;
    },
  },
  {
    // UF-05: no unit on the box (systemctl cat fails) — the raw spawn is still the cold start.
    name: "UF-05 no unit on the box means the raw spawn",
    run: async (ctx) => {
      ctx.cold();
      await ctx.launch({ LAUNCHER_UNIT_PRESENT: "0" });
      if (existsSync(ctx.captures.unit)) return "no unit on the box means no unit start";
      return read(ctx.captures.java) === "spawned" ? null : "without a unit the cold start is the raw spawn";
    },
  },
];

/** Every arm's name, in order — the rehearsal's inventory, for a count that cannot drift from it. */
export const ARM_NAMES: readonly string[] = ARMS.map((arm) => arm.name);

/**
 * Run every arm, in order, against `shim`. Returns the first problem, or null — the script exited
 * at the first failing arm and a later arm's premise is the earlier one's outcome.
 */
export async function launcherRehearsal(shim: string): Promise<string | null> {
  const markers = shimMarkers(shim);
  if (markers instanceof Error) return markers.message;

  const sandbox = makeSandbox("release-launcher-");
  const dir = sandbox.dir;
  const stateDir = join(dir, "state");
  mkdirSync(stateDir, { recursive: true });
  const daemonState = join(dir, "daemon-state");
  const captures = {
    java: join(dir, "java-spawns"),
    unit: join(dir, "unit-starts"),
    pwned: join(dir, "pwned"),
    // the shim's LOGS_DIR is <state dir>/../logs
    bootLog: join(dir, "logs", "daemon-boot.log"),
  };
  const daemon = startDaemon(daemonState, markers.gateway, markers.shim, captures.pwned);
  try {
    writeFileSync(join(dir, "share", "splice.jar"), "");
    writeFileSync(join(stateDir, "mgmt-key"), "test-key\n");
    writeFileSync(join(dir, "splice.toml"), `[daemon]\ncontrol_port = ${daemon.toml} # custom\n`);
    writeFileSync(daemonState, "up\n");
    writeMocks(sandbox.bin);

    // The operator's shape: the sandbox HOME carries the same config, jar and mgmt-key at their
    // DEFAULT paths, so the arms with no selector resolve everything from $HOME (V4-189).
    mkdirSync(join(dir, "home", ".config", "splice"), { recursive: true });
    mkdirSync(join(dir, "home", ".local", "share", "splice"), { recursive: true });
    mkdirSync(join(dir, "home", ".splice", "state"), { recursive: true });
    writeFileSync(join(dir, "home", ".config", "splice", "splice.toml"), readFileSync(join(dir, "splice.toml")));
    writeFileSync(join(dir, "home", ".local", "share", "splice", "splice.jar"), "");
    writeFileSync(join(dir, "home", ".splice", "state", "mgmt-key"), "test-key\n");

    // A SCRUBBED environment: this rehearsal runs on a machine with a real splice install, and an
    // ambient SPLICE_* would aim the shim at the operator's own daemon (or, through unitDefaults,
    // decide the unit arms). Only what the harness sets survives.
    const base: Record<string, string> = {};
    for (const [key, value] of Object.entries(Bun.env)) {
      if (typeof value !== "string") continue;
      if (/^(SPLICE_|CLAUDEX_|CONTROL_|LAUNCHER_)/.test(key)) continue;
      base[key] = value;
    }
    base.HOME = join(dir, "home");
    base.PATH = `${sandbox.bin}:${Bun.env.PATH ?? ""}`;
    base.SPLICE_HEAD = "test";
    base.LAUNCHER_DAEMON_STATE = daemonState;
    base.LAUNCHER_JAVA_CAPTURE = captures.java;
    base.LAUNCHER_START_CAPTURE = captures.unit;
    base.LAUNCHER_UNIT_PRESENT = "1";
    base.LAUNCHER_UNIT_BOOTS = "1";

    const ctx: Ctx = {
      dir,
      shim,
      daemon,
      env: base,
      harness: {
        SPLICE_CONFIG: join(dir, "splice.toml"),
        SPLICE_SHARE_DIR: join(dir, "share"),
        CLAUDEX_STATE_DIR: stateDir,
      },
      captures,
      stateDir,
      daemonState,
      async launch(overrides, argv = []) {
        const env: Record<string, string> = { ...base };
        for (const [key, value] of Object.entries(overrides)) {
          if (value === undefined) delete env[key];
          else env[key] = value;
        }
        const proc = Bun.spawn([shim, ...argv], { cwd: dir, env, stdout: "pipe", stderr: "pipe" });
        const [stdout, stderr] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text()]);
        await proc.exited;
        return { code: exitStatusOf(proc), stderr, output: `${stdout}${stderr}` };
      },
      cold() {
        writeFileSync(daemonState, "down\n");
        rmSync(captures.java, { force: true });
        rmSync(captures.unit, { force: true });
        daemon.forget();
      },
    };

    for (const arm of ARMS) {
      // A throw inside an arm (an unparseable recipe body, a capture that is not there) is that
      // arm's RED, not a stack trace out of the rehearsal: `release verify` reports a verdict.
      let problem: string | null;
      try {
        problem = await arm.run(ctx);
      } catch (failure) {
        problem = `threw: ${failure instanceof Error ? failure.message : String(failure)}`;
      }
      if (problem) return `launcher test: ${arm.name}: ${problem}`;
    }
    return null;
  } finally {
    daemon.stop();
    rmSync(sandbox.dir, { recursive: true, force: true });
  }
}

function selectorValue(selector: string, ctx: Ctx): string {
  switch (selector) {
    case "SPLICE_CONFIG":
      return join(ctx.dir, "home", ".config", "splice", "splice.toml");
    case "XDG_CONFIG_HOME":
      return join(ctx.dir, "home", ".config");
    case "SPLICE_JAR":
      return join(ctx.dir, "home", ".local", "share", "splice", "splice.jar");
    case "SPLICE_SHARE_DIR":
      return join(ctx.dir, "home", ".local", "share", "splice");
    case "SPLICE_STATE_DIR":
    case "CLAUDEX_STATE_DIR":
      return join(ctx.dir, "home", ".splice", "state");
    default:
      return String(ctx.daemon.toml);
  }
}

/**
 * The mock daemon: ONE handler on TWO loopback ports, so "which port did the shim resolve" is
 * answered by which listener received the request rather than by a URL a stub wrote down. The
 * ports are the kernel's (port 0) — a fixed 4567 would bind whatever this machine already runs
 * there. `down` answers an EMPTY body, which is what the shim reads as "no daemon" (request()
 * returns the body on any status, and health() maps null and "" alike to "").
 */
function startDaemon(statePath: string, gateway: string, shimVersion: string, pwnedFile: string): Daemon {
  const handler = async (request: Request): Promise<Response> => {
    const url = new URL(request.url);
    const authorization = request.headers.get("authorization") ?? "";
    const state = existsSync(statePath) ? readFileSync(statePath, "utf8").trim() : "down";
    if (url.pathname === "/health") {
      if (state === "up" || state === "new") {
        return new Response(
          `{"ok":true,"version":"${gateway}","wantShimVersion":"${shimVersion}","topologyStale":${daemon.topologyStale}}\n`,
        );
      }
      if (state === "old") return new Response('{"ok":true,"version":"0.0.9","wantShimVersion":"shim-1"}\n');
      return new Response(""); // down
    }
    if (url.pathname === "/api/daemon/shutdown") {
      daemon.lastShutdown = { url: request.url, body: await request.text(), authorization };
      writeFileSync(statePath, "down\n");
      return new Response('{"ok":true}\n');
    }
    if (url.pathname.startsWith("/launch/")) {
      daemon.lastLaunch = { url: request.url, body: await request.text(), authorization };
      const env = daemon.injectEnvKey ? `{"X$(touch ${daemon.pwnedFile})":"v"}` : "{}";
      return new Response(`{"env":${env},"unset":[],"argv":["true"]}\n`);
    }
    return new Response(`unexpected daemon URL: ${request.url}\n`, { status: 404 });
  };
  const servers = [
    Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: handler }),
    Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: handler }),
  ];
  const ports = servers.map((server) => server.port);
  if (ports.some((port) => typeof port !== "number")) throw new Error("launcher test: the mock daemon took no TCP port");
  const daemon: Daemon = {
    toml: ports[0] as number,
    state: ports[1] as number,
    topologyStale: false,
    injectEnvKey: false,
    pwnedFile,
    forget() {
      daemon.lastLaunch = undefined;
      daemon.lastShutdown = undefined;
    },
    stop() {
      for (const server of servers) server.stop(true);
    },
  };
  return daemon;
}

/** The two processes the shim genuinely spawns: the JVM and the supervisor. */
function writeMocks(bin: string): void {
  writeStub(
    join(bin, "java"),
    'import { appendFileSync, writeFileSync } from "node:fs";\n' +
      'if (process.env.LAUNCHER_JAVA_BOOT_FAILS === "1") {\n' +
      // JW-01: a boot-dead daemon — the stack trace goes to stderr, which the shim must be
      // redirecting into daemon-boot.log (pre-fix it went to /dev/null).
      '  process.stderr.write("Exception in thread main: kaboom-at-boot\\n");\n' +
      "  process.exit(1);\n" +
      "}\n" +
      'writeFileSync(process.env.LAUNCHER_DAEMON_STATE, "new\\n");\n' +
      'if (process.env.LAUNCHER_JAVA_CAPTURE) appendFileSync(process.env.LAUNCHER_JAVA_CAPTURE, "spawned\\n");\n',
  );
  // V4-189: the supervisor unit, mocked. `cat <unit>` answers "the unit exists" only when
  // LAUNCHER_UNIT_PRESENT=1; `start <unit>` records the unit name and, when LAUNCHER_UNIT_BOOTS=1,
  // brings the mock daemon up the way the real unit would.
  writeStub(
    join(bin, "systemctl"),
    'import { appendFileSync, writeFileSync } from "node:fs";\n' +
      "const argv = process.argv.slice(2);\n" +
      'if (argv[0] !== "--user") { process.stderr.write(`unexpected systemctl args: ${argv.join(" ")}\\n`); process.exit(2); }\n' +
      'if (argv[1] === "cat") process.exit(process.env.LAUNCHER_UNIT_PRESENT === "1" ? 0 : 1);\n' +
      'if (argv[1] === "start") {\n' +
      '  appendFileSync(process.env.LAUNCHER_START_CAPTURE, `${argv[2] ?? ""}\\n`);\n' +
      '  if (process.env.LAUNCHER_UNIT_BOOTS === "1") { writeFileSync(process.env.LAUNCHER_DAEMON_STATE, "new\\n"); process.exit(0); }\n' +
      "  process.exit(1);\n" +
      "}\n" +
      'process.stderr.write(`unexpected systemctl verb: ${argv.join(" ")}\\n`);\n' +
      "process.exit(2);\n",
  );
}
