import type { Register } from "../../../contract/index.ts";

export const register: Register = on => {
  on("turn.start", async $ => {
    $.log("waiting forever");
    await new Promise<void>(() => {});
  });
  on("turn.complete", async $ => { $.log("next event arrived"); });
  on("mod.stop", async $ => { $.log("stop handler ran"); });
};
