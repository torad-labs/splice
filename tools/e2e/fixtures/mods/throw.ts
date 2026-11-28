import type { Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  on("turn.start", async () => { throw new Error("fixture handler threw"); });
};
