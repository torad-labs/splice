import { afterEach, expect, test } from "bun:test";
import { mkdtemp, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { HostSessionRow, ModManifest } from "../../contract/index.ts";

type Frame = Record<string, unknown>;
const host = join(import.meta.dir, "../host.ts");
const projects = new Set<string>();
const children = new Set<RunningMod>();

class RunningMod {
  readonly #child;
  readonly #reader;
  readonly #decoder = new TextDecoder();
  #buffer = "";

  constructor(root: string, data: string) {
    this.#child = Bun.spawn([process.execPath, "--config=/dev/null", "--no-env-file", "--no-install", host], {
      env: { SPLICE_MOD_NAME: "recorder", SPLICE_MOD_ROOT: root, SPLICE_MOD_DATA: data },
      stdin: "pipe",
      stdout: "pipe",
      stderr: "inherit",
    });
    this.#reader = this.#child.stdout.getReader();
    children.add(this);
  }

  async raw(line: string) {
    this.#child.stdin.write(line + "\n");
    await this.#child.stdin.flush();
  }

  async send(frame: Frame) { await this.raw(JSON.stringify(frame)); }

  async event(name: string, payload: object = {}) {
    await this.send({ type: "event", name, session_id: "a-session", payload });
  }

  async frame(type: string | readonly string[], match: (frame: Frame) => boolean = () => true): Promise<Frame> {
    const types = typeof type === "string" ? [type] : type;
    for (;;) {
      const frame = await this.#next();
      if (!frame) throw new Error(`The mod exited without sending ${type} (exit ${await this.#child.exited}).`);
      if (types.includes(String(frame.type)) && match(frame)) return frame;
    }
  }

  async line(text: string): Promise<Frame> { return this.frame("log", frame => frame.message === text); }

  async stop() {
    await this.send({ type: "stop", reason: "disabled" });
    this.#child.stdin.end();
  }

  async exited(): Promise<number> {
    const code = await this.#child.exited;
    children.delete(this);
    return code;
  }

  async kill() {
    this.#child.kill("SIGKILL");
    await this.exited();
  }

  async #next(): Promise<Frame | null> {
    let newline = this.#buffer.indexOf("\n");
    while (newline === -1) {
      const read = await this.#reader.read();
      if (read.done) return null;
      this.#buffer += this.#decoder.decode(read.value, { stream: true });
      newline = this.#buffer.indexOf("\n");
    }
    const line = this.#buffer.slice(0, newline);
    this.#buffer = this.#buffer.slice(newline + 1);
    return JSON.parse(line) as Frame;
  }
}

async function fixture(name: string, extras: Partial<ModManifest> = {}) {
  const root = await mkdtemp(join(tmpdir(), "splice-mod-test-"));
  projects.add(root);
  await mkdir(join(root, ".splice-mod"));
  const manifest: ModManifest = {
    name: "recorder", version: "1.0.0", description: "Test observation.", surfaces: [], capabilities: [], ...extras,
  };
  await Bun.write(join(root, ".splice-mod/mod.json"), JSON.stringify(manifest));
  await Bun.write(join(root, "hooks/hooks.json"), '{"modules":["./register.ts"]}');
  await Bun.write(join(root, "hooks/register.ts"), Bun.file(join(import.meta.dir, "fixtures", `${name}.ts`)));
  return { root, data: join(root, "data") };
}

afterEach(async () => {
  await Promise.all(Array.from(children, child => child.kill()));
  for (const path of projects) await rm(path, { recursive: true, force: true });
  projects.clear();
});

test("load notices name the mod, describe observation, and name the refused features", async () => {
  const { root, data } = await fixture("recorder", {
    pages: [{ name: "board", title: "Board" }],
    commands: [{ name: "open", description: "Open the board." }],
    tools: ["read"],
    views: [{ uri: "ui://recorder/board", name: "Board" }],
  });
  const child = new RunningMod(root, data);
  const ready = await child.frame("ready");
  expect(ready.notice).toBe("Mod recorder: handlers observe only in this version of Splice.");
  expect(ready.events).toEqual(["mod.start", "mod.stop"]);
  const refused = (ready.refused as string[]).join("\n");
  const names = [
    "launch.prepare", "sessions.list", "ui.render", "command.run", "http.request",
    "$.command", "$.tool", "$.ui", "$.sessions.invalidate", "$.ui.respond",
  ];
  for (const name of names) expect(refused).toContain(`Mod recorder: ${name} is not served yet`);
  expect(ready.declared_not_served).toEqual([
    "Mod recorder: pages: board is declared but not served yet.",
    "Mod recorder: commands: open is declared but not served yet.",
    "Mod recorder: tools: read is declared but not served yet.",
    "Mod recorder: views: ui://recorder/board is declared but not served yet.",
  ]);
  await child.line("run 1; keys: run/count");
  await child.line("The mod store accepts JSON values of at most 64 KiB.");
  await child.line("Mod recorder: $.sessions.invalidate is not served yet.");
  await child.stop();
  await child.line("clean stop for recorder");
  expect(await child.exited()).toBe(0);

  const restarted = new RunningMod(root, data);
  await restarted.line("run 2; keys: run/count");
  await restarted.stop();
  expect(await restarted.exited()).toBe(0);
});

test("a timed-out event is abandoned so the next event can be observed", async () => {
  const { root, data } = await fixture("hangs");
  const child = new RunningMod(root, data);
  await child.frame("ready");
  await child.event("turn.start");
  await child.line("waiting forever");
  const timeout = await child.frame("timeout");
  expect(timeout.event).toBe("turn.start");
  expect(timeout.message).toBe("handler exceeded 5 s; abandoned the event");
  await child.frame("handled");
  await child.event("turn.complete");
  await child.line("next event arrived");
  await child.frame("handled");
  await child.event("turn.start");
  await child.line("waiting forever");
  await child.stop();
  expect((await child.frame(["timeout", "log"])).message).toBe("stop handler ran");
  expect(await child.exited()).toBe(0);
}, 10_000);

test("handlers receive frozen events, their catch sees the error, and changed events have no effect", async () => {
  const { root, data } = await fixture("throws");
  const child = new RunningMod(root, data);
  await child.frame("ready");
  await child.event("session.start", { cwd: "/original", labels: { role: "worker" } });
  expect((await child.frame("error")).event).toBe("session.start");
  await child.line("caught TypeError; frozen labels: true");
  await child.line(
    "A handler for session.start passed a changed event to next() or returned a value; " +
    "in this version of Splice that has no effect on the turn.",
  );
  await child.line("original folder: /original");
  await child.frame("handled");
  await child.event("tool.call", { server: "different", tool: "read" });
  expect((await child.frame(["handled", "log"])).type).toBe("handled");
  await child.event("tool.call", { server: "board", tool: "read" });
  await child.line("matching tool observed");
  await child.stop();
  expect(await child.exited()).toBe(0);
});

test("a bad register fails to load and names the problem", async () => {
  const { root, data } = await fixture("loadfail");
  const child = new RunningMod(root, data);
  expect((await child.frame("load-failed")).why).toBe("fixture cannot register");
  expect(await child.exited()).not.toBe(0);
});

test("oversized and malformed JSON lines are dropped without losing the next event", async () => {
  const { root, data } = await fixture("hangs");
  const child = new RunningMod(root, data);
  await child.frame("ready");
  await child.raw("x".repeat(256 * 1024 + 1));
  await child.raw("{");
  expect((await child.frame("dropped")).count).toBe(1);
  expect((await child.frame("dropped")).count).toBe(1);
  await child.event("turn.complete");
  await child.line("next event arrived");
  await child.stop();
  expect(await child.exited()).toBe(0);
});

test("a mod receives session-only rows through its handle, without blocking input", async () => {
  const { root, data } = await fixture("recorder");
  await Bun.write(join(root, "hooks/register.ts"), `
    export function register(on) {
      on("mod.start", async $ => $.log(JSON.stringify(await $.sessions.list())));
    }
  `);
  const child = new RunningMod(root, data);
  await child.frame("ready");
  const call = await child.frame("call");
  expect(call.method).toBe("sessions.list");
  const rows: readonly HostSessionRow[] = [{ id: "session", title: "One session", kind: "session", status: "waiting" }];
  await child.send({ type: "reply", id: call.id, rows });
  await child.line(JSON.stringify(rows));
  await child.stop();
  expect(await child.exited()).toBe(0);
});
