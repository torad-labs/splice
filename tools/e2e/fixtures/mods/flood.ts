import type { Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  on("turn.start", async ($) => {
    for (let i = 0; i < 2_000; i++) $.log(`fixture log flood ${i}`);
  });
};
