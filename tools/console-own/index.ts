// console-own: a Splice of the campaign's own, with made-up data and a probe session, for trying the console's writes
// without ever touching the Splice that runs on the machine. See README.md.
//
//   bun tools/console-own up [--json]     build this checkout, start its Splice in a home of its own, print where it is
//   bun tools/console-own down <home>     stop every process of that home and remove it
import { spawnSync } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { layout } from "../gate/src/lib/repo.ts";
import { homeAt, homeEnv, isOwnHome, makeHome, pidsOf, PREFIX, type Home } from "./home.ts";
import { startProbe, stopProbe } from "./probe.ts";
import { Daemon, freePort, providers, standIn, toml } from "./splice.ts";
import { send } from "./traffic.ts";

const say = (line: string) => console.error(`console-own: ${line}`);

/** The daemon jar, built from this checkout through the build gate like any build. Its output goes to stderr, so the
 *  last line on stdout stays the answer. */
function build(): string {
  const root = layout().repoRoot;
  say("building the daemon from this checkout (./gradlew :app:shadowJar)");
  const r = spawnSync("./gradlew", [":app:shadowJar", "--quiet"], { cwd: root, stdio: ["ignore", 2, 2] });
  if (r.status !== 0) throw new Error("the build failed");
  const jar = join(root, "app/build/libs/app-all.jar");
  if (!existsSync(jar)) throw new Error(`no jar at ${jar}`);
  return jar;
}

async function up(json: boolean): Promise<void> {
  const jar = build();
  const h = makeHome();
  try {
    const standin = await standIn(h);
    const list = await providers();
    const d = new Daemon(h, await freePort());
    say(`starting splice in ${h.root} on ${d.url}`);
    await d.start(jar, toml(h, d.port, standin, list));
    for (const [i, p] of list.entries()) {
      const r = await send(d, p.command, h.work, `0000000${i + 1}-feed-4bad-8eed-c0ffee00000${i + 1}`);
      say(`${p.command}: ${r.answered} of ${r.sent} requests answered`);
    }
    const team = await d.api("/api/teams", {
      method: "PUT",
      headers: { "idempotency-key": `${PREFIX}team` },
      body: JSON.stringify({ name: "harbor-crew", goal: "Chart the harbor", repo: h.work, slots: [{ id: "lead", role: "Lead", head: list[0]!.key, lead: true }] }),
    });
    if (!team.ok) throw new Error(`creating the team answered ${team.status}: ${await team.text()}`);
    const session = await startProbe(d, list[0]!.command);
    say(`probe session ${session} is live on ${list[0]!.command}`);
    const launch = consolePage(d);
    if (json) console.log(JSON.stringify({ home: h.root, console: d.url, key: d.key(), launch }));
    else console.log(`console-own: up in ${h.root}: console ${d.url}, sign in with ${launch}, stop with: bun tools/console-own down ${h.root}`);
  } catch (e) {
    await down(h.root).catch(() => {});
    throw e;
  }
}

/** The launch page `splice console --no-browser` writes for this home's console: it signs one browser in, once. */
function consolePage(d: Daemon): string {
  const r = d.cli(["console", "--no-browser"]);
  const page = /file:\/\/\S+/.exec(r.out)?.[0];
  if (r.code !== 0 || !page) throw new Error(`splice console --no-browser wrote no launch page:\n${r.out}`);
  return fileURLToPath(page);
}

/** Only a console-own home is ever removed: a folder directly under /tmp with the console-own prefix. */
function ownHome(path: string): Home {
  const root = resolve(path);
  if (!isOwnHome(root)) throw new Error(`${path} is not a console-own home`);
  return homeAt(root);
}

async function down(path: string): Promise<void> {
  const h = ownHome(path);
  stopProbe(h.tmux, homeEnv(h));
  for (const sig of ["SIGTERM", "SIGKILL"] as const) {
    for (const pid of pidsOf(h)) {
      try {
        process.kill(pid, sig);
      } catch {
        /* gone already */
      }
    }
    for (let i = 0; i < 40 && pidsOf(h).length; i++) await Bun.sleep(250);
  }
  if (pidsOf(h).length) throw new Error(`processes of ${h.root} would not stop: ${pidsOf(h).join(" ")}`);
  rmSync(h.root, { recursive: true, force: true });
}

async function main(argv: string[]): Promise<void> {
  const [verb, ...args] = argv;
  if (verb === "up") return up(args.includes("--json"));
  if (verb === "down" && args[0]) return down(args[0]);
  throw new Error("usage: bun tools/console-own up [--json] | down <home>");
}

main(process.argv.slice(2)).catch((e) => {
  console.error(`console-own: ${(e as Error).message}`);
  process.exit(1);
});
