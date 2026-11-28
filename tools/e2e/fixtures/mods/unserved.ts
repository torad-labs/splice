import type { Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  on("ui.render", async () => { throw new Error("unserved ui.render ran"); });
  on("launch.prepare", async () => { throw new Error("unserved launch.prepare ran"); });
  on("session.start", async ($, event) => {
    $.log(`observed session.start: ${event.session}`);
  });
};
