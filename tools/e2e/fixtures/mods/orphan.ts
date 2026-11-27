import { writeFileSync } from "node:fs";
import { join } from "node:path";
import type { Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  on("turn.start", async (_, event) => {
    if (event.session !== "daemon-kill") return;
    writeFileSync(join(process.env.SPLICE_MOD_DATA!, "blocked"), String(process.pid));
    // Block the event loop without burning CPU. This host cannot react to EOF when its daemon dies.
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0);
  });
};
