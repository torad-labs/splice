import type { Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  on("mod.start", async ($) => { $.log("fixture ready to be killed by the real-run harness"); });
};
