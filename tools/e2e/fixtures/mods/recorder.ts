import { Database } from "bun:sqlite";
import { join } from "node:path";
import { existsSync, readFileSync } from "node:fs";
import type { EventName, Handle, Payload, Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  // This fixture has a second SQLite reader (the evidence script); its snapshots must not block the host's writes.
  const inspector = new Database(join(process.env.SPLICE_MOD_DATA!, "store.sqlite"));
  try { inspector.exec("PRAGMA journal_mode = WAL"); } finally { inspector.close(); }
  const marker = join(process.env.SPLICE_MOD_DATA!, "previous-recorder");
  const previous = existsSync(marker) ? Number(readFileSync(marker, "utf8")) : 0;
  const state = previous && (() => {
    try { return readFileSync(`/proc/${previous}/stat`, "utf8").split(") ").at(-1)?.[0] === "Z" ? "gone" : "alive"; }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      return "gone";
    }
  })();
  let index: number | undefined;
  const note = <E extends EventName>(name: E) => async ($: Handle, event: Payload<E>) => {
    index ??= (await $.store.list("event/")).length;
    const value = JSON.stringify({ name, payload: event });
    await $.store.set(`event/${String(index + 1).padStart(8, "0")}`, value);
    index++;
    $.log(value);
  };
  on("mod.start", async ($, event) => {
    if (previous) $.log(`startup pid=${process.pid}: previous recorder ${previous} was ${state} at registration`);
    await note("mod.start")($, event);
  });
  on("mod.stop", note("mod.stop"));
  on("session.start", note("session.start"));
  on("session.end", note("session.end"));
  on("turn.start", note("turn.start"));
  on("request.send", note("request.send"));
  on("response.receive", note("response.receive"));
  on("stream.event", note("stream.event"));
  on("tool.call", async ($, event, next) => {
    await note("tool.call")($, event);
    return next(event);
  });
  on("turn.complete", async ($, event) => {
    await note("turn.complete")($, event);
    if (event.labels["audit"]) {
      const snapshot = JSON.stringify(await $.sessions.list());
      await $.store.set("audit/sessions", snapshot);
      $.log(`sessions.list: ${snapshot}`);
    }
  });
};
