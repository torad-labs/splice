// What the muse-tool-cache cases share: a daemon built from this checkout with a Muse head on the stand-in for Meta
// (meta.ts), conversations sent through that head as Claude Code sends them (claude.ts), and what a case reads off
// the requests Meta was sent: what each read from the cache, the tool list it carried, and where it handed a tool to
// the model.

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Splice, freePort } from "../../checks/server/lib/harness.ts";
import { launch, type Launched } from "../../checks/speed/lib/session.ts";
import { must, show } from "./cases.ts";
import { Conversation, type Block, type Reply, type Tool } from "./claude.ts";
import { MUSE_ENV, Meta, handed, isTool, museHead, strings, type Sent, type WireTool } from "./meta.ts";

/** One request of a conversation: what Claude Code was answered, and the request Meta was sent for it (the last
 *  one, should the head have sent Meta more than one). */
export interface Turn {
  reply: Reply;
  sent: Sent;
}

/** A daemon with a Muse head on the stand-in, and a Claude Code session launched on that head. */
export class Stage {
  private constructor(
    readonly backend: Meta,
    private readonly s: Splice,
    private readonly muse: Launched,
    private readonly dir: string,
  ) {}

  static async open(): Promise<Stage> {
    const backend = Meta.start();
    const dir = mkdtempSync(join(tmpdir(), "muse-cases-"));
    let s: Splice | undefined;
    try {
      s = await Splice.boot(museHead({ command: "claude-muse", port: await freePort(), backend: backend.url, dir }), MUSE_ENV);
      return new Stage(backend, s, await launch(s, "claude-muse"), dir);
    } catch (e) {
      await s?.dispose();
      backend.stop();
      rmSync(dir, { recursive: true, force: true });
      throw e;
    }
  }

  /** A conversation whose Claude Code starts with `tools`. */
  talk(tools: Tool[], o: { session?: string; agent?: string } = {}): Talk {
    return new Talk(this.backend, new Conversation(this.muse, tools, o));
  }

  /** The daemon stops and starts again. Meta, and what it has cached, stay as they were. */
  restart(): Promise<void> {
    return this.s.restart();
  }

  async close(): Promise<void> {
    await this.s.dispose();
    this.backend.stop();
    rmSync(this.dir, { recursive: true, force: true });
  }
}

/** A conversation and the requests it made, one step at a time. */
export class Talk {
  constructor(
    private readonly backend: Meta,
    readonly c: Conversation,
  ) {}

  /** The person says `text`. */
  say(text: string): Promise<Turn> {
    this.c.say(text);
    return this.made(() => this.c.send());
  }

  /** A tool answers the call the model made in `to`, and `then` rides behind the result as text. Where the model
   *  made no call there is no result to send, and the person says `then` instead, so the conversation goes on and
   *  the requests after it can still be read. */
  answer(to: Turn, content: string | Block[], then?: string): Promise<Turn> {
    const made = to.reply.calls[0];
    if (made) this.c.result(made.id, content, then);
    else this.c.say(then ?? "Say OK.");
    return this.made(() => this.c.send());
  }

  /** ToolSearch answers the search the model made in `to` by loading `tools`. `then` rides behind the result. */
  load(to: Turn, tools: Tool[], then?: string): Promise<Turn> {
    this.c.loaded(callOf(to), tools, then);
    return this.made(() => this.c.send());
  }

  /** A server connects with `tools`, and then the person says `text`. */
  add(tools: Tool[], text: string): Promise<Turn> {
    this.c.added(tools).say(text);
    return this.made(() => this.c.send());
  }

  /** A server leaves with the tools named `names`, and then the person says `text`. */
  remove(names: string[], text: string): Promise<Turn> {
    this.c.removed(names).say(text);
    return this.made(() => this.c.send());
  }

  /** Claude Code compacts the conversation as it stands. */
  compact(): Promise<Turn> {
    return this.made(() => this.c.compact());
  }

  private async made(post: () => Promise<Reply>): Promise<Turn> {
    const before = this.backend.sent().length;
    const reply = await post();
    const sent = this.backend.sent().slice(before).at(-1);
    must(sent !== undefined, `Meta was sent nothing for a request of the conversation, which Claude Code was answered ${heard(reply)}`);
    return { reply, sent };
  }
}

/** The ID of the call the model made in `t`. */
function callOf(t: Turn): string {
  const made = t.reply.calls[0];
  must(made !== undefined, `the model called no tool where the conversation needs it to: Claude Code was answered ${heard(t.reply)}`);
  return made.id;
}

/** The requests of one conversation, by the names its cases know them under. A conversation that breaks on the way
 *  keeps the requests it made, and a case that needs a later one fails with what broke it. */
export class Scene<K extends string> {
  private readonly turns = new Map<K, Turn>();
  private failure: unknown;

  /** Plays `script`, which names each request as it makes it. */
  static async play<K extends string>(script: (scene: Scene<K>) => Promise<void>): Promise<Scene<K>> {
    const scene = new Scene<K>();
    try {
      await script(scene);
    } catch (e) {
      scene.failure = e;
    }
    return scene;
  }

  set(name: K, turn: Turn): Turn {
    this.turns.set(name, turn);
    return turn;
  }

  /** The request named `name`. */
  at(name: K): Turn {
    const turn = this.turns.get(name);
    if (turn) return turn;
    throw this.failure ?? new Error(`the conversation never made its "${name}" request`);
  }

  /** The requests named `names`, in that order. */
  all(...names: K[]): Turn[] {
    return names.map((n) => this.at(n));
  }
}

/** What Claude Code was answered, for a failure to show. */
export function heard(r: Reply): string {
  if (r.error) return `${r.status} ${r.error}`;
  const parts = [r.text ? `"${r.text}"` : "", ...r.calls.map((c) => `a call of ${c.name} with ${JSON.stringify(c.input)}`)].filter(Boolean);
  return `${r.status} ${parts.join(" and ") || "with nothing"}`;
}

/** The names a request's tool list declares, for a failure to show. */
function names(sent: Sent): string {
  return sent.listed.map((t) => t.name ?? t.type ?? "?").join(", ") || "no tools";
}

/** Fails unless Meta took the request of `t`. */
function taken(t: Turn, what: string): void {
  must(t.sent.status === 200, `Meta refused ${what}: ${t.sent.status} ${t.sent.refusal}`);
}

/** Fails unless the request of `after` read from Meta's cache everything the request of `before`, the one the
 *  conversation made right before it, had sent. */
export function readsAll(before: Turn, after: Turn, what: string): void {
  taken(after, what);
  must(
    after.sent.cached >= before.sent.input,
    `${what} read ${after.sent.cached} tokens from Meta's cache, not the ${before.sent.input} the conversation had already sent, and paid ${after.sent.fresh} in full`,
  );
}

/** Fails unless each request of `later` read from the cache everything the request before it had sent. `later`
 *  starts with the request the others follow. */
export function eachReadsAll(later: Turn[], what: string): void {
  must(later.length > 1, "the case names no request to look at");
  for (let at = 1; at < later.length; at++) readsAll(later[at - 1] as Turn, later[at] as Turn, `request ${at} ${what}`);
}

/** Fails unless each request of `later` carried the tool list of `first`, byte for byte. */
export function listsAs(first: Turn, later: Turn[], what: string): void {
  must(later.length > 0, "the case names no request to look at");
  for (const t of later) {
    taken(t, what);
    if (t.sent.tools === first.sent.tools) continue;
    const then = names(first.sent);
    const now = names(t.sent);
    throw new Error(then === now ? `${what} lists the tools of the conversation's first request (${then}) in other bytes` : `${what} lists ${now}, and the conversation's first request listed ${then}`);
  }
}

/** One place a request's input hands a tool to the model: a `tool_search_call` and, right after it, the
 *  `tool_search_output` that carries the tool. */
export interface Handover {
  /** Where the pair starts in the input. */
  at: number;
  /** The pair's two items, in the bytes they were sent in. */
  raw: string;
  /** The tool as the pair hands it over. */
  tool: WireTool;
}

/** Every place the input of `sent` hands over the tool Claude Code names `name`, in order. Fails when a
 *  `tool_search_output` carries the tool and is not the second half of a client-executed pair. */
export function handovers(sent: Sent, name: string): Handover[] {
  const out: Handover[] = [];
  sent.items.forEach((item, at) => {
    const tool = handed(item).find((t) => isTool(t, name));
    if (!tool) return;
    const before = at > 0 ? sent.items[at - 1] : undefined;
    const id = item.value.call_id;
    must(
      before !== undefined && before.value.type === "tool_search_call" && typeof id === "string" && id !== "" && before.value.call_id === id,
      `input[${at}] hands ${name} over in a tool_search_output with no tool_search_call of its call_id right before it`,
    );
    const executions = [before.value.execution, item.value.execution];
    must(
      executions.includes("client") && executions.every((e) => e === undefined || e === "client"),
      `the pair that hands ${name} over (input[${at - 1}] and input[${at}]) is not client-executed: its execution reads ${show(executions)}`,
    );
    out.push({ at: at - 1, raw: `${before.raw}\n${item.raw}`, tool });
  });
  return out;
}

/** What a request's input holds, for a failure to show. */
function kinds(sent: Sent): string {
  const count = new Map<string, number>();
  for (const item of sent.items) {
    const kind = typeof item.value.type === "string" ? item.value.type : typeof item.value.role === "string" ? `${item.value.role} message` : "an item of no type";
    count.set(kind, (count.get(kind) ?? 0) + 1);
  }
  return [...count.entries()].map(([kind, n]) => `${n} ${kind}`).join(", ") || "nothing";
}

/** The newest place the request of `t` hands `name` over in its history, as Meta's client-executed tool-search
 *  pair. Fails when there is none. */
export function rides(t: Turn, name: string, what: string): Handover {
  taken(t, what);
  const found = handovers(t.sent, name).at(-1);
  must(found !== undefined, `${what} hands ${name} over in no tool-search pair: its input holds ${kinds(t.sent)}, and its tool list ${names(t.sent)}`);
  return found;
}

/** Fails unless every request of `later` carries the pair that handed `name` over in `first`, in the bytes `first`
 *  carried it in. */
export function samePair(first: Turn, later: Turn[], name: string, what: string): void {
  must(later.length > 0, "the case names no request to look at");
  const then = rides(first, name, `the request that first carried ${name}`);
  later.forEach((t, at) => {
    const now = rides(t, name, `request ${at + 1} ${what}`);
    const kept = handovers(t.sent, name).some((h) => h.raw === then.raw);
    must(kept, `request ${at + 1} ${what} carries the pair of ${name} as ${show(now.raw)}, and it was first sent as ${show(then.raw)}`);
  });
}

/** Where the input of `sent` first holds an item `is` holds for, or -1. */
export function where(sent: Sent, is: (v: Record<string, unknown>) => boolean): number {
  return sent.items.findIndex((item) => is(item.value));
}

/** Whether an input item is the person saying `text`. */
export function person(text: string): (v: Record<string, unknown>) => boolean {
  return (v) => v.role === "user" && strings(v).some((s) => s.includes(text));
}

/** Whether an input item is the model's call of `tool` with arguments that hold `text`. */
export function modelCall(tool: string, text: string): (v: Record<string, unknown>) => boolean {
  return (v) => v.type === "function_call" && v.name === tool && typeof v.arguments === "string" && v.arguments.includes(text);
}

/** Where the input of `sent` tells the model, in the words of a message, of the tool named `name`: every message
 *  that names it. */
export function tells(sent: Sent, name: string): number[] {
  return sent.items.flatMap((item, at) => (typeof item.value.role === "string" && strings(item.value).some((s) => s.includes(name)) ? [at] : []));
}

/** Fails unless Claude Code was answered, in `t`, one call of the tool named `name` with `input`. */
export function called(t: Turn, name: string, input: Record<string, unknown>, what: string): void {
  taken(t, what);
  const got = t.reply.calls.map((c) => ({ name: c.name, input: c.input }));
  must(Bun.deepEquals(got, [{ name, input }], true), `${what} answered Claude Code ${heard(t.reply)}, not a call of ${name} with ${JSON.stringify(input)}`);
}
