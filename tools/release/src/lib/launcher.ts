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
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
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
  healthReads: number;
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
  readonly captures: { java: string; javaArgv: string; unit: string; pwned: string; bootLog: string };
  readonly stateDir: string;
  readonly daemonState: string;
  launch(env: Record<string, string | undefined>, argv?: readonly string[], command?: string): Promise<Run>;
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
    // Fail-closed boot: the new jar refuses this splice.toml, so the old daemon is NOT stopped and the launch ends
    // with the list. Order matters: it runs straight after the replace arm and puts the daemon back to "old".
    name: "a stale daemon is left running when the new jar refuses the config",
    run: async (ctx) => {
      writeFileSync(ctx.daemonState, "old\n");
      ctx.daemon.forget();
      const run = await ctx.launch({ ...ctx.harness, LAUNCHER_CONFIG_REFUSES: "1" });
      if (ctx.daemon.lastShutdown) return "the stale daemon was shut down although the new jar refuses splice.toml";
      if (read(ctx.daemonState) !== "old") return "the stale daemon was replaced although the new jar refuses splice.toml";
      if (!run.stderr.includes("heads.one.provider")) return `the launch must print the findings, got: ${run.stderr}`;
      const said = run.stderr.includes("was not stopped") ? null : `the launch must say the daemon was not stopped, got: ${run.stderr}`;
      // Hand the arms after this one the state the replace arm left them: a current daemon.
      writeFileSync(ctx.daemonState, "new\n");
      ctx.daemon.forget();
      return said;
    },
  },
  {
    // Only a clean answer permits the stop: a check that cannot give a verdict (no such verb, a crash) keeps the
    // serving daemon exactly as a refusal does, and the launch says why.
    name: "a stale daemon is left running when the new jar's config check cannot answer",
    run: async (ctx) => {
      writeFileSync(ctx.daemonState, "old\n");
      ctx.daemon.forget();
      const run = await ctx.launch({ ...ctx.harness, LAUNCHER_CONFIG_UNCHECKABLE: "1" });
      if (ctx.daemon.lastShutdown) return "the stale daemon was shut down although the config check could not answer";
      if (read(ctx.daemonState) !== "old") return "the stale daemon was replaced although the config check could not answer";
      const said = run.stderr.includes("could not check splice.toml") && run.stderr.includes("was not stopped")
        ? null
        : `the launch must say the check could not run and the daemon was not stopped, got: ${run.stderr}`;
      writeFileSync(ctx.daemonState, "new\n");
      ctx.daemon.forget();
      return said;
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
  {
    // V4-258: the daemon printed every daemon.log line to its stderr, which the raw spawn sends into
    // daemon-boot.log, so the boot log grew as a second, unbounded copy of daemon.log. The shim tells
    // the daemon its stderr is the boot log with the flag DaemonLaunch's argv passes too
    // (BOOT_LOG_FLAG), and the daemon then keeps daemon.log's lines out of it.
    name: "V4-258 the raw spawn tells the daemon its stderr is the boot log",
    run: async (ctx) => {
      ctx.cold();
      rmSync(ctx.captures.javaArgv, { force: true });
      await ctx.launch({ LAUNCHER_UNIT_PRESENT: "0" });
      const runs = read(ctx.captures.javaArgv).split("\n").filter(Boolean).map((line) => JSON.parse(line) as string[]);
      const daemon = runs.find((javaArgv) => javaArgv.includes("daemon"));
      if (daemon === undefined) return `the raw spawn never ran the daemon: ${JSON.stringify(runs)}`;
      return daemon.slice(daemon.indexOf("daemon")).join(" ") === "daemon --stderr-is-boot-log"
        ? null
        : `the daemon must be told its stderr is the boot log, argv: ${JSON.stringify(daemon)}`;
    },
  },
  {
    name: "V4-457 daemon OOM diagnostics use one fixed state path even with custom JVM options",
    run: async (ctx) => {
      const stateDir = join(ctx.dir, "state with spaces ' and $ characters");
      mkdirSync(stateDir, { recursive: true });
      writeFileSync(join(stateDir, "mgmt-key"), "test-key\n");
      const paths: readonly (readonly [string, readonly string[]])[] = [
        ["test", []],
        ["splice", ["daemon", "--stderr-is-boot-log", "literal argument with spaces"]],
      ];
      for (const [head, argv] of paths) {
        ctx.cold();
        const run = await ctx.launch({
          ...ctx.harness,
          SPLICE_HEAD: head,
          SPLICE_STATE_DIR: stateDir,
          SPLICE_JVM_OPTS: "-Xmx96m -XX:-ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=wrong.hprof",
          LAUNCHER_UNIT_PRESENT: "0",
        }, argv);
        if (run.code !== 0) return `${head}: the isolated daemon must launch: ${run.output}`;
        const runs = read(ctx.captures.javaArgv).split("\n").filter(Boolean).map((line) => JSON.parse(line) as string[]);
        const daemon = runs.find((args) => args.includes("daemon"));
        if (daemon === undefined) return `${head}: java never ran the daemon`;
        const flags = daemon.slice(0, daemon.indexOf("-jar"));
        const exitFlags = flags.filter((flag) => /^-XX:[+-]ExitOnOutOfMemoryError$/.test(flag));
        const dumpFlags = flags.filter((flag) => /^-XX:[+-]HeapDumpOnOutOfMemoryError$/.test(flag));
        const dumpPaths = flags.filter((flag) => flag.startsWith("-XX:HeapDumpPath="));
        const expectedPath = `-XX:HeapDumpPath=${join(stateDir, "splice-oom.hprof")}`;
        if (exitFlags.at(-1) !== "-XX:+ExitOnOutOfMemoryError") return `${head}: OOM must terminate with custom JVM options`;
        if (dumpFlags.at(-1) !== "-XX:+HeapDumpOnOutOfMemoryError") return `${head}: OOM must dump with custom JVM options`;
        if (dumpPaths.at(-1) !== expectedPath) return `${head}: the fixed dump path must be one argv item under the state`;
        if (head === "splice" && JSON.stringify(daemon.slice(daemon.indexOf("daemon"))) !== JSON.stringify(argv)) {
          return "the admin daemon argv must remain unchanged";
        }
      }
      return null;
    },
  },
  {
    // V4-218: the jar resolves ~ from HOME first (splice.core.config.UserHome), and every JVM the shim
    // starts is given that same home as -Duser.home, so the JDK and any library that reads user.home agree
    // with it instead of naming the passwd entry's home. All three paths: a CLI verb, `<head> login`, and
    // the daemon's raw spawn. Next to the console arm at the end, and for the same reason: the java mock
    // marks the daemon "new".
    name: "V4-218 every java the shim starts runs under the launch's HOME",
    run: async (ctx) => {
      const home = `-Duser.home=${ctx.env.HOME}`;
      const javaRuns = () => read(ctx.captures.javaArgv).split("\n").filter(Boolean).map((line) => JSON.parse(line) as string[]);
      ctx.cold();
      await ctx.launch({ LAUNCHER_UNIT_PRESENT: "0" });
      const daemon = javaRuns().find((javaArgv) => javaArgv.includes("daemon"));
      if (daemon === undefined) return `the raw spawn never ran the daemon: ${JSON.stringify(javaRuns())}`;
      if (!daemon.includes(home)) return `the daemon's java ran without ${home}: ${JSON.stringify(daemon)}`;
      const paths: readonly (readonly [string, readonly string[]])[] = [["splice", ["status"]], ["test", ["login"]]];
      for (const [head, argv] of paths) {
        rmSync(ctx.captures.javaArgv, { force: true });
        await ctx.launch({ SPLICE_HEAD: head }, argv);
        const runs = javaRuns();
        const label = `${head} ${argv.join(" ")}`;
        if (runs.length === 0) return `${label}: java never ran`;
        if (!runs.every((javaArgv) => javaArgv.includes(home))) {
          return `${label}: java ran without ${home}: ${JSON.stringify(runs)}`;
        }
      }
      return null;
    },
  },
  {
    // V4-218: another HOME is another profile. Even if the service answers the same version, it
    // owns a different config and key. The shim must refuse the service's port before probing it;
    // a different configured port must raw-spawn, not start the service under the old HOME.
    name: "V4-218 another HOME never borrows the unit's daemon",
    run: async (ctx) => {
      const otherHome = join(ctx.dir, "other-home");
      const config = join(otherHome, ".config", "splice", "splice.toml");
      mkdirSync(join(otherHome, ".local", "share", "splice"), { recursive: true });
      mkdirSync(join(otherHome, ".splice", "state"), { recursive: true });
      mkdirSync(join(otherHome, ".config", "splice"), { recursive: true });
      writeFileSync(join(otherHome, ".local", "share", "splice", "splice.jar"), "");
      writeFileSync(join(otherHome, ".splice", "state", "mgmt-key"), "test-key\n");
      writeFileSync(config, `[daemon]\ncontrol_port = ${ctx.daemon.toml}\n`);
      ctx.cold();
      const readsBefore = ctx.daemon.healthReads;
      const conflict = await ctx.launch({ HOME: otherHome });
      if (ctx.daemon.healthReads !== readsBefore) return "another HOME contacted the unit's daemon before refusing";
      if (conflict.code !== 1 || !conflict.stderr.includes("SPLICE_CONTROL_PORT")) {
        return `a second HOME on the unit's port must refuse before health: ${conflict.output}`;
      }
      if (existsSync(ctx.captures.unit) || existsSync(ctx.captures.java)) {
        return "the conflicting HOME must neither start the unit nor spawn java";
      }
      ctx.cold();
      const aliasReads = ctx.daemon.healthReads;
      const aliased = await ctx.launch({
        HOME: otherHome,
        SPLICE_CONTROL_PORT: String(ctx.daemon.toml).padStart(6, "0"),
      });
      if (ctx.daemon.healthReads !== aliasReads || aliased.code !== 1) {
        return "a zero-padded spelling of the unit's port must also refuse before health";
      }
      writeFileSync(config, `[daemon]\ncontrol_port = ${ctx.daemon.state}\n`);
      ctx.cold();
      const isolated = await ctx.launch({ HOME: otherHome });
      if (isolated.code !== 0) return `a second HOME on its own port must launch: ${isolated.output}`;
      if (existsSync(ctx.captures.unit)) return "a second HOME must never start the first HOME's unit";
      if (!existsSync(ctx.captures.java)) return "a second HOME on its own port must raw-spawn";
      return ctx.daemon.lastLaunch?.url === `http://127.0.0.1:${ctx.daemon.state}/launch/test`
        ? null
        : "the second HOME did not reach its own port";
    },
  },
  {
    name: "wrapped Claude under another HOME runs unwrapped without contacting splice",
    run: async (ctx) => {
      const otherHome = join(ctx.dir, "wrapped-other-home");
      const state = join(ctx.dir, "home", ".splice", "state", "claude-head-wrap.json");
      const binary = join(ctx.dir, "real-claude");
      const capture = join(ctx.dir, "real-claude-capture");
      const wrapped = join(ctx.dir, "claude");
      symlinkSync(ctx.shim, wrapped);
      mkdirSync(join(otherHome, ".config", "splice"), { recursive: true });
      writeFileSync(join(otherHome, ".config", "splice", "splice.toml"), `[daemon]\ncontrol_port = ${ctx.daemon.toml}\n`);
      writeStub(binary,
        'import { writeFileSync } from "node:fs";\n' +
        `writeFileSync(${JSON.stringify(capture)}, JSON.stringify({ argv: process.argv.slice(2), home: process.env.HOME, marker: process.env.SYNTHETIC_KEEP, config: process.env.CLAUDE_CONFIG_DIR }));\n` +
        "process.exit(23);\n",
      );
      const argv = ["plugin", "validate", "--strict", "synthetic plugin dir", "", "line one\nline two"];
      const launches = [[undefined, argv], [String(ctx.daemon.state), argv], [undefined, ["login", "--label", "synthetic"]]] as const;
      for (const [port, words] of launches) {
        writeFileSync(state, JSON.stringify({ real_binary_path: binary, shim_path: ctx.shim }));
        const unitState = port === undefined ? undefined : join(ctx.dir, "unit selected state");
        if (unitState !== undefined) {
          mkdirSync(unitState, { recursive: true });
          writeFileSync(join(unitState, "claude-head-wrap.json"), readFileSync(state));
          writeFileSync(state, JSON.stringify({ real_binary_path: join(ctx.dir, "wrong-default") }));
        }
        ctx.cold();
        const reads = ctx.daemon.healthReads;
        const run = await ctx.launch({
          HOME: otherHome, SPLICE_HEAD: undefined, SPLICE_CONTROL_PORT: port,
          LAUNCHER_UNIT_STATE_DIR: unitState,
          SYNTHETIC_KEEP: "unchanged", CLAUDE_CONFIG_DIR: join(otherHome, "synthetic-config"),
        }, words, wrapped);
        if (run.code !== 23) return `wrapped Claude must propagate the real binary's exit: ${run.output}`;
        const expected = { argv: words, home: otherHome, marker: "unchanged", config: join(otherHome, "synthetic-config") };
        if (read(capture) !== JSON.stringify(expected)) return "unwrapped argv or caller environment changed";
        if (ctx.daemon.healthReads !== reads || ctx.daemon.lastLaunch || ctx.daemon.lastShutdown ||
            existsSync(ctx.captures.unit) || existsSync(ctx.captures.javaArgv)) {
          return "foreign-HOME wrapped Claude contacted or started splice";
        }
        if (run.stderr.trim().split("\n").length !== 1 || !run.stderr.includes("unwrapped")) {
          return `unwrapped Claude must print only its one explanatory line: ${run.stderr}`;
        }
      }
      ctx.cold();
      const reads = ctx.daemon.healthReads;
      const named = await ctx.launch({ HOME: otherHome, SPLICE_HEAD: "synthetic-named" });
      if (named.code !== 1 || ctx.daemon.healthReads !== reads) return "a foreign-HOME named head must still refuse";
      rmSync(capture, { force: true });
      const same = await ctx.launch({ SPLICE_HEAD: undefined }, [], wrapped);
      if (same.code !== 0 || ctx.daemon.lastLaunch?.url !== `http://127.0.0.1:${ctx.daemon.toml}/launch/claude` ||
          existsSync(capture)) return "same-HOME wrapped Claude must still route through splice";
      return null;
    },
  },
  {
    name: "wrapped Claude keeps its owner when the caller's user bus is unreachable",
    run: async (ctx) => {
      const share = join(ctx.dir, "bus-owner-share");
      const state = join(ctx.dir, "bus-owner-state");
      const otherHome = join(ctx.dir, "bus-other-home");
      const installed = join(share, "splice-launch");
      const wrapped = join(ctx.dir, "bus-wrapped", "claude");
      const binary = join(ctx.dir, "bus-real-claude");
      const capture = join(ctx.dir, "bus-real-capture");
      for (const dir of [share, state, otherHome, join(ctx.dir, "bus-wrapped")]) mkdirSync(dir, { recursive: true });
      copyFileSync(ctx.shim, installed);
      chmodSync(installed, 0o755);
      symlinkSync(installed, wrapped);
      const customConfig = join(ctx.dir, "owner-custom.toml");
      writeFileSync(customConfig, `[daemon]\ncontrol_port = ${ctx.daemon.state}\n`);
      writeFileSync(join(share, "splice-launch-owner.json"), JSON.stringify({
        home: ctx.env.HOME, state_dir: state, selectors: { SPLICE_CONFIG: customConfig },
      }));
      writeFileSync(join(state, "claude-head-wrap.json"), JSON.stringify({ real_binary_path: binary, shim_path: installed }));
      writeStub(binary,
        'import { writeFileSync } from "node:fs";\n' +
        `writeFileSync(${JSON.stringify(capture)}, JSON.stringify({ argv: process.argv.slice(2), home: process.env.HOME, runtime: process.env.XDG_RUNTIME_DIR, bus: process.env.DBUS_SESSION_BUS_ADDRESS }));\n` +
        "process.exit(23);\n",
      );
      const environments = [
        { XDG_RUNTIME_DIR: join(otherHome, "isolated-runtime"), DBUS_SESSION_BUS_ADDRESS: undefined },
        { XDG_RUNTIME_DIR: undefined, DBUS_SESSION_BUS_ADDRESS: "unix:path=/synthetic-dead-bus" },
        { XDG_RUNTIME_DIR: join(otherHome, "isolated-runtime"), DBUS_SESSION_BUS_ADDRESS: "unix:path=/synthetic-dead-bus" },
      ];
      const argv = ["--print", "--output-format", "json", "synthetic peer request", "", "line one\nline two"];
      const problems: string[] = [];
      for (const [index, environment] of environments.entries()) {
        ctx.cold();
        rmSync(capture, { force: true });
        const reads = ctx.daemon.healthReads;
        const run = await ctx.launch({
          ...environment, HOME: otherHome, SPLICE_HEAD: undefined, SPLICE_CONTROL_PORT: String(ctx.daemon.toml),
        }, argv, wrapped);
        const expected = { argv, home: otherHome, runtime: environment.XDG_RUNTIME_DIR, bus: environment.DBUS_SESSION_BUS_ADDRESS };
        if (run.code !== 23 || read(capture) !== JSON.stringify(expected)) problems.push(`bus case ${index}: unwrapped launch failed: ${run.output}`);
        if (ctx.daemon.healthReads !== reads || ctx.daemon.lastLaunch || existsSync(ctx.captures.javaArgv)) {
          problems.push(`bus case ${index}: foreign HOME contacted or started splice`);
        }
        ctx.cold();
        const namedReads = ctx.daemon.healthReads;
        const named = await ctx.launch({
          ...environment, HOME: otherHome, SPLICE_HEAD: "synthetic-named", SPLICE_CONTROL_PORT: String(ctx.daemon.toml),
        }, ["--version"], installed);
        if (named.code !== 1 || !named.stderr.includes("no daemon was contacted") || ctx.daemon.healthReads !== namedReads) {
          problems.push(`bus case ${index}: a foreign locator must not authorize an unowned named head: ${named.output}`);
        }
      }
      const ownHome = join(ctx.dir, "bus-owned-other-profile");
      const ownState = join(ownHome, ".splice", "state");
      mkdirSync(ownState, { recursive: true });
      mkdirSync(join(ownHome, ".local", "share", "splice"), { recursive: true });
      writeFileSync(join(ownHome, ".local", "share", "splice", "splice.jar"), "");
      writeFileSync(join(ownState, "mgmt-key"), "test-key\n");
      writeFileSync(join(ownState, "config.json"), JSON.stringify({ controlPort: ctx.daemon.state }));
      ctx.cold();
      const conflictReads = ctx.daemon.healthReads;
      const conflict = await ctx.launch({
        HOME: ownHome, SPLICE_HEAD: "synthetic-named", XDG_RUNTIME_DIR: join(ownHome, "isolated-runtime"),
      }, ["--version"], installed);
      if (conflict.code !== 1 || ctx.daemon.healthReads !== conflictReads) {
        problems.push(`the owner's selected topology port must refuse a named-head collision: ${conflict.output}`);
      }
      ctx.cold();
      rmSync(capture, { force: true });
      const same = await ctx.launch({
        HOME: `${ctx.env.HOME}/.`, SPLICE_HEAD: "claude", XDG_RUNTIME_DIR: join(otherHome, "isolated-runtime"),
      }, ["--version"], installed);
      if (same.code !== 0 || ctx.daemon.lastLaunch?.url !== `http://127.0.0.1:${ctx.daemon.toml}/launch/claude` || existsSync(capture)) {
        problems.push(`an equivalent owner HOME spelling must keep splice routing: ${same.output}`);
      }
      writeFileSync(join(ownState, "config.json"), JSON.stringify({ controlPort: ctx.daemon.toml }));
      ctx.cold();
      const own = await ctx.launch({
        HOME: ownHome, SPLICE_HEAD: "synthetic-named", XDG_RUNTIME_DIR: join(ownHome, "isolated-runtime"),
      }, ["--version"], installed);
      if (own.code !== 0 || ctx.daemon.lastLaunch?.url !== `http://127.0.0.1:${ctx.daemon.toml}/launch/synthetic-named`) {
        problems.push(`a named head with its own state must launch despite a foreign locator: ${own.output}`);
      }
      return problems.length === 0 ? null : problems.join("\n");
    },
  },
  {
    name: "an unreachable bus and absent owner refuse plain and named heads before daemon contact",
    run: async (ctx) => {
      const otherHome = join(ctx.dir, "bus-unowned-home");
      mkdirSync(join(otherHome, ".config", "splice"), { recursive: true });
      writeFileSync(join(otherHome, ".config", "splice", "splice.toml"), `[daemon]\ncontrol_port = ${ctx.daemon.toml}\n`);
      const environments = [
        { XDG_RUNTIME_DIR: join(otherHome, "isolated-runtime"), DBUS_SESSION_BUS_ADDRESS: undefined },
        { XDG_RUNTIME_DIR: undefined, DBUS_SESSION_BUS_ADDRESS: "unix:path=/synthetic-dead-bus" },
      ];
      const problems: string[] = [];
      for (const environment of environments) {
        for (const head of ["claude", "synthetic-named"]) {
          ctx.cold();
          const reads = ctx.daemon.healthReads;
          const run = await ctx.launch({ ...environment, HOME: otherHome, SPLICE_HEAD: head }, ["--version"]);
          if (run.code !== 1 || !run.stderr.includes("no daemon was contacted")) problems.push(`${head}: missing owner must refuse: ${run.output}`);
          if (ctx.daemon.healthReads !== reads || ctx.daemon.lastLaunch || existsSync(ctx.captures.javaArgv)) {
            problems.push(`${head}: an unowned HOME contacted or started splice`);
          }
        }
      }
      return problems.length === 0 ? null : problems.join("\n");
    },
  },
  {
    name: "no user manager or owner locator still launches this HOME's existing splice state",
    run: async (ctx) => {
      // Includes the first plain-claude launch after upgrade, before reconcile backfills its locator.
      for (const head of ["test", "claude"]) {
        ctx.cold();
        const run = await ctx.launch({
          SPLICE_HEAD: head, LAUNCHER_UNIT_PRESENT: "0",
          XDG_RUNTIME_DIR: join(ctx.dir, "isolated-runtime"), DBUS_SESSION_BUS_ADDRESS: "unix:path=/synthetic-dead-bus",
        }, ["--version"]);
        if (run.code !== 0 || ctx.daemon.lastLaunch?.url !== `http://127.0.0.1:${ctx.daemon.toml}/launch/${head}`) {
          return `own-HOME ${head} must keep working without a locator or bus: ${run.output}`;
        }
      }
      return null;
    },
  },
  {
    name: "wrapped Claude refuses missing or recursive recorded binaries before daemon contact",
    run: async (ctx) => {
      const otherHome = join(ctx.dir, "wrapped-other-home");
      const state = join(ctx.dir, "home", ".splice", "state", "claude-head-wrap.json");
      const alias = join(ctx.dir, "recursive-claude");
      symlinkSync(ctx.shim, alias);
      const cases: readonly (readonly [string | null, string])[] = [
        [null, "wrap state"],
        ["not json", "wrap state"],
        [JSON.stringify({}), "real binary"],
        [JSON.stringify({ real_binary_path: join(ctx.dir, "gone") }), "real binary"],
        [JSON.stringify({ real_binary_path: ctx.dir }), "real binary"],
        [JSON.stringify({ real_binary_path: alias }), "launcher"],
      ];
      for (const [body, reason] of cases) {
        if (body === null) rmSync(state, { force: true });
        else writeFileSync(state, body);
        ctx.cold();
        const reads = ctx.daemon.healthReads;
        const run = await ctx.launch({ HOME: otherHome, SPLICE_HEAD: "claude" });
        if (run.code !== 1 || !run.stderr.includes(reason)) return `invalid wrap state must name ${reason}: ${run.output}`;
        if (ctx.daemon.healthReads !== reads || ctx.daemon.lastLaunch || ctx.daemon.lastShutdown ||
            existsSync(ctx.captures.unit) || existsSync(ctx.captures.javaArgv)) {
          return "a missing or recursive binary contacted or started splice";
        }
      }
      return null;
    },
  },
  {
    // Blank and whitespace-only HOME are not directories. The shim and JVM must agree on the
    // passwd fallback, including when an explicit config and jar point into a test sandbox.
    name: "V4-218 an unset or blank HOME uses the JVM's fallback home",
    run: async (ctx) => {
      // The shipped shim runs Node. Bun userInfo() can echo the sandbox HOME instead of the passwd home.
      const fallback = execFileSync("node", ["-e", 'process.stdout.write(require("node:os").userInfo().homedir)'], {
        env: ctx.env, encoding: "utf8",
      });
      const expected = `-Duser.home=${fallback}`;
      for (const home of [undefined, "", "  "]) {
        rmSync(ctx.captures.javaArgv, { force: true });
        await ctx.launch({ ...ctx.harness, SPLICE_HEAD: "splice", HOME: home }, ["status"]);
        const runs = read(ctx.captures.javaArgv).split("\n").filter(Boolean).map((line) => JSON.parse(line) as string[]);
        if (runs.length !== 1 || !runs[0]?.includes(expected)) {
          return `HOME=${JSON.stringify(home)} must use ${expected}, got ${JSON.stringify(runs)}`;
        }
      }
      return null;
    },
  },
  {
    // v0.4.0 review: the CLI asks System.console() whether a person is at a terminal, and on JDK 22-24
    // it answers yes into a pipe unless java runs with -Djdk.console=java.base, and a verb that prints to
    // a terminal only then prints into an agent's transcript. Both paths that run the CLI must carry it.
    // LAST on purpose: the java mock marks the daemon "new", which no arm after this one may inherit.
    name: "the CLI's java runs with the terminal-only console",
    run: async (ctx) => {
      const paths: readonly (readonly [string, readonly string[]])[] = [["splice", ["status"]], ["test", ["login"]]];
      for (const [head, argv] of paths) {
        rmSync(ctx.captures.javaArgv, { force: true });
        await ctx.launch({ SPLICE_HEAD: head }, argv);
        const runs = read(ctx.captures.javaArgv).split("\n").filter(Boolean).map((line) => JSON.parse(line) as string[]);
        const label = `${head} ${argv.join(" ")}`;
        if (runs.length === 0) return `${label}: java never ran`;
        if (!runs.every((javaArgv) => javaArgv[0] === "-Djdk.console=java.base")) {
          return `${label}: java ran without -Djdk.console=java.base: ${JSON.stringify(runs)}`;
        }
      }
      return null;
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
    javaArgv: join(dir, "java-argv"),
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
      if (SELECTORS.some((selector) => selector === key) || /^(SPLICE_|CLAUDEX_|CONTROL_|LAUNCHER_)/.test(key)) continue;
      base[key] = value;
    }
    base.HOME = join(dir, "home");
    base.PATH = `${sandbox.bin}:${Bun.env.PATH ?? ""}`;
    base.SPLICE_HEAD = "test";
    base.LAUNCHER_DAEMON_STATE = daemonState;
    base.LAUNCHER_JAVA_CAPTURE = captures.java;
    base.LAUNCHER_JAVA_ARGV = captures.javaArgv;
    base.LAUNCHER_START_CAPTURE = captures.unit;
    base.LAUNCHER_UNIT_PRESENT = "1";
    base.LAUNCHER_UNIT_BOOTS = "1";
    base.LAUNCHER_UNIT_HOME = base.HOME;

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
      async launch(overrides, argv = [], command = shim) {
        const env: Record<string, string> = { ...base };
        for (const [key, value] of Object.entries(overrides)) {
          if (value === undefined) delete env[key];
          else env[key] = value;
        }
        const proc = Bun.spawn([command, ...argv], { cwd: dir, env, stdout: "pipe", stderr: "pipe" });
        const [stdout, stderr] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text()]);
        await proc.exited;
        return { code: exitStatusOf(proc), stderr, output: `${stdout}${stderr}` };
      },
      cold() {
        writeFileSync(daemonState, "down\n");
        rmSync(captures.java, { force: true });
        rmSync(captures.javaArgv, { force: true });
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
      daemon.healthReads += 1;
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
    healthReads: 0,
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
      // The short ownership writer is not a daemon boot or an administrative CLI invocation.
      'if (process.argv.includes("record-launch")) process.exit(0);\n' +
      // fail-closed boot: the new jar's findings pass, asked before the stale daemon is stopped.
      'if (process.argv.includes("check-config")) {\n' +
      '  if (process.env.LAUNCHER_CONFIG_REFUSES === "1") { process.stdout.write("splice: splice.toml has 1 finding: heads.one.provider (line 9)\\n"); process.exit(3); }\n' +
      // A jar that cannot give a verdict (an older build's unknown verb exits 2, as does a crash).
      '  if (process.env.LAUNCHER_CONFIG_UNCHECKABLE === "1") { process.stderr.write("usage: splice <command>\\n"); process.exit(2); }\n' +
      "  process.exit(0);\n" +
      "}\n" +
      'if (process.env.LAUNCHER_JAVA_BOOT_FAILS === "1") {\n' +
      // JW-01: a boot-dead daemon — the stack trace goes to stderr, which the shim must be
      // redirecting into daemon-boot.log (pre-fix it went to /dev/null).
      '  process.stderr.write("Exception in thread main: kaboom-at-boot\\n");\n' +
      "  process.exit(1);\n" +
      "}\n" +
      'writeFileSync(process.env.LAUNCHER_DAEMON_STATE, "new\\n");\n' +
      'if (process.env.LAUNCHER_JAVA_ARGV) appendFileSync(process.env.LAUNCHER_JAVA_ARGV, JSON.stringify(process.argv.slice(2)) + "\\n");\n' +
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
      'if (process.env.XDG_RUNTIME_DIR?.endsWith("isolated-runtime") || process.env.DBUS_SESSION_BUS_ADDRESS?.includes("synthetic-dead-bus")) process.exit(1);\n' +
      'if (argv[1] === "cat") process.exit(process.env.LAUNCHER_UNIT_PRESENT === "1" ? 0 : 1);\n' +
      'if (argv[1] === "show-environment") {\n' +
      '  process.stdout.write(`HOME=${process.env.LAUNCHER_UNIT_HOME}\\n`);\n' +
      '  if (process.env.LAUNCHER_UNIT_STATE_DIR) process.stdout.write(`SPLICE_STATE_DIR=${process.env.LAUNCHER_UNIT_STATE_DIR}\\n`);\n' +
      '  process.exit(0);\n' +
      '}\n' +
      'if (argv[1] === "start") {\n' +
      '  appendFileSync(process.env.LAUNCHER_START_CAPTURE, `${argv[2] ?? ""}\\n`);\n' +
      '  if (process.env.LAUNCHER_UNIT_BOOTS === "1") { writeFileSync(process.env.LAUNCHER_DAEMON_STATE, "new\\n"); process.exit(0); }\n' +
      "  process.exit(1);\n" +
      "}\n" +
      'process.stderr.write(`unexpected systemctl verb: ${argv.join(" ")}\\n`);\n' +
      "process.exit(2);\n",
  );
}
