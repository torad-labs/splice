import type { Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  const slow = async () => { await Bun.sleep(25); };
  on("turn.start", slow);
  on("request.send", slow);
  on("response.receive", slow);
  on("tool.call", async (_, event, next) => { await slow(); return next(event); });
  on("turn.complete", slow);
  on("stream.event", async ($, event) => {
    $.log(`crash fixture handling ${event.session} stream seq=${event.seq}`);
    // While this handler is in flight, later stream events from the already-open turn queue behind it.
    await Bun.sleep(4_500);
  });
};
