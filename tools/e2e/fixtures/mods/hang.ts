import type { Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  on("turn.start", async ($) => {
    $.log("fixture entered the hanging handler");
    await new Promise<void>(() => {});
  });
  on("stream.event", async () => {});
};
