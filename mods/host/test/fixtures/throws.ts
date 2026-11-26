import type { Register } from "../../../contract/index.ts";

export const register: Register = on => {
  on("session.start", async (_, event) => {
    (event.labels as Record<string, string>).role = "changed";
  }).catch(async ($, event, next) => {
    $.log(`caught ${event.error.name}; frozen labels: ${Object.isFrozen(event.labels)}`);
    await next({ ...event, cwd: "/wrong" });
  });
  on("session.start", async ($, event) => { $.log(`original folder: ${event.cwd}`); });
  on("tool.call", { server: "board", tool: "read" }, async $ => {
    $.log("matching tool observed");
    return { content: [] };
  });
};
