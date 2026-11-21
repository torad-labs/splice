// share-door-only, box-door-only: a machine that shares answers its door and its name, and nothing else. Any other
// first path segment (a command's launch, its turns, a turn on one of its logins, its statusline) is a 404 in the
// Anthropic error shape saying the machine no longer shares one command at an address of its own, that its door serves
// every model it has, and the add to run on the other machine, with the Host the request used. Admission still runs
// first. A node's `[remote.<command>]` left from the per-command days still boots; its launch prints the box's words,
// and `splice remote` lists it with the add that replaces it.

import { afterAll, beforeAll, describe, expect, setDefaultTimeout, test } from "bun:test";
import { doorTurn } from "../desktop/lib/desktop.ts";
import type { Splice } from "../server/lib/harness.ts";
import { type Box, addLine, box, doorOpen, node, plain } from "./lib/share.ts";

// No test here is a claim about speed: a busy machine must not fail one on bun's default five seconds.
setDefaultTimeout(120_000);

let b: Box;
let n: Splice;

beforeAll(async () => {
  b = await box();
  n = await node(b, `\n[remote.boxastra]\nurl = "${b.base}/claude-astra?auth=work"\n`);
}, 600_000);

afterAll(async () => {
  await n?.dispose();
  await b?.s.dispose();
  b?.astra.stop();
});

interface Refusal {
  status: number;
  type: string;
  kind: string;
  message: string;
}

async function refusal(r: Response): Promise<Refusal> {
  const text = await r.text();
  let j: { type?: unknown; error?: { type?: unknown; message?: unknown } } = {};
  try {
    j = JSON.parse(text) as typeof j;
  } catch {
    // not JSON: the status and the text are what is read
  }
  return { status: r.status, type: String(j.type ?? ""), kind: String(j.error?.type ?? ""), message: String(j.error?.message ?? text) };
}

/** The one 404 every per-command address now answers. */
function saysTheDoor(r: Refusal): void {
  expect(r.status).toBe(404);
  expect(r.type).toBe("error");
  expect(r.kind).toBe("not_found_error");
  expect(r.message).toMatch(/door/i);
  expect(r.message).toContain(addLine(b));
  expect(r.message).not.toMatch(/overloaded/i);
}

const TURN: RequestInit = {
  method: "POST",
  headers: { "content-type": "application/json", "anthropic-version": "2023-06-01", "x-claude-code-session-id": crypto.randomUUID() },
  body: JSON.stringify({ model: "astra-large", max_tokens: 16, messages: [{ role: "user", content: "Say OK." }] }),
};

describe("box-door-only", () => {
  test("GET / still names the machine, with no command of its own", async () => {
    const r = await fetch(`${b.base}/`);
    expect(r.status).toBe(200);
    const j = (await r.json()) as { machine?: unknown; commands?: unknown };
    expect(typeof j.machine).toBe("string");
    expect(String(j.machine).length).toBeGreaterThan(0);
    expect(j.commands).toEqual([]);
  });

  test("a command's own launch is a 404 naming the door and the add to run", async () => {
    saysTheDoor(await refusal(await fetch(`${b.base}/claude-astra/launch`)));
    saysTheDoor(await refusal(await fetch(`${b.base}/claude-astra/launch?auth=work&models=astra-large`)));
  });

  test("a turn, a turn on a login and a statusline at a command's own address are the same 404, and reach no head", async () => {
    const before = b.astra.completions().length;
    for (const path of ["/claude-astra/v1/messages", "/claude-astra/v1/messages?beta=true", "/claude-astra/@work/v1/messages", "/astra/v1/messages", "/claude-astra/statusline"]) {
      saysTheDoor(await refusal(await fetch(`${b.base}${path}`, TURN)));
    }
    expect(b.astra.completions().length).toBe(before);
  });

  test("a web page is refused before any route answers", async () => {
    const r = await fetch(`${b.base}/claude-astra/launch`, { headers: { origin: "http://page.example" } });
    expect(r.status).toBe(403);
  });

  test("the door answers as it did: its launch, and a turn on the box's head", async () => {
    const launch = await doorOpen(b);
    expect(typeof launch.machine).toBe("string");
    const rows = launch.rows ?? [];
    expect(rows.length).toBeGreaterThan(0);
    const before = b.astra.completions().length;
    const t = await doorTurn(`${b.base}/door`, { key: "splice-lan", model: rows[0]!.model, session: crypto.randomUUID() });
    expect(t.status).toBe(200);
    expect(b.astra.completions().length).toBe(before + 1);
  });

  test("a node's [remote.<command>] from the per-command days still boots, and its launch prints the box's words", async () => {
    const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd: n.home, launch: String(process.pid) });
    const r = await n.api("/launch/boxastra", { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
    const text = await r.text();
    expect(r.status).not.toBe(200);
    expect(text).toContain(addLine(b));
    expect(text).toMatch(/door/i);
  });

  test("splice remote on the node lists that entry with the add that replaces it", () => {
    const r = n.cli(["remote"]);
    expect(r.code).toBe(0);
    const line = plain(r.out).split("\n").find((l) => l.trimStart().startsWith("boxastra ")) ?? "";
    expect(line).toContain(addLine(b));
  });
});
