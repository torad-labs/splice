// share-door-only: a box that shares its door on `[share]`, and a node beside it, each a real daemon in a home of its
// own. The box serves one api-key head (`claude-astra`) on a stand-in upstream; the node has a head of its own and
// nothing shared. (docs/specs/door-network.md; the per-command share of docs/specs/lan-share.md is what goes.)

import { slottedHead } from "../../desktop/lib/desktop.ts";
import { Splice, freePort, until } from "../../server/lib/harness.ts";
import { Upstream } from "../../server/lib/upstream.ts";

export interface Box {
  s: Splice;
  /** The share port. */
  port: number;
  /** The box as another machine names it: `http://127.0.0.1:<share port>`. */
  base: string;
  astra: Upstream;
}

export interface DoorLaunch {
  machine?: string;
  window?: number;
  rows?: { model: string; label?: string; command?: string }[];
}

/** A box with `[share]` on, its listener answering. */
export async function box(): Promise<Box> {
  const astra = new Upstream("astra", "astra-large").start();
  const port = await freePort();
  const s = await Splice.boot(
    slottedHead({ key: "astra", command: "claude-astra", port: await freePort(), url: astra.url, big: "astra-large", small: "astra-small" }) +
      `\n[share]\nenabled = true\nport = ${port}\n`,
  );
  const base = `http://127.0.0.1:${port}`;
  await until("the box's share listener answers", async () => (await fetch(`${base}/`).catch(() => null))?.status === 200, 60_000);
  return { s, port, base, astra };
}

/** What the box's door launches another machine's `claude` with, once the door is open. */
export async function doorOpen(b: Box): Promise<DoorLaunch> {
  let launch: DoorLaunch = {};
  await until(
    "the box's door opens",
    async () => {
      const r = await fetch(`${b.base}/door/launch`).catch(() => null);
      if (r?.status !== 200) return false;
      launch = (await r.json()) as DoorLaunch;
      return true;
    },
    120_000,
  );
  return launch;
}

/** A node: a daemon with one head of its own, and `more` lines of splice.toml (a `[remote.*]`, say). */
export async function node(b: Box, more = ""): Promise<Splice> {
  return Splice.boot(
    slottedHead({ key: "local", command: "claude-local", port: await freePort(), url: b.astra.url, big: "local-large", small: "local-small" }) + more,
  );
}

/** The add a person runs on another machine to put it on the box's door. */
export function addLine(b: Box): string {
  return `splice remote add ${b.base}`;
}

/** `text` without terminal colours. */
export function plain(text: string): string {
  return text.replace(/\u001b\[[0-9;]*m/g, "");
}
