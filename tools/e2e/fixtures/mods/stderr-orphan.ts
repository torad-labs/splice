import { symlinkSync } from "node:fs";
import { join } from "node:path";
import type { Register } from "../../../../mods/contract/index.ts";

export const register: Register = (on) => {
  on("turn.start", async ($, event) => {
    if (event.session !== "handover-orphan") return;
    const sleep = join(process.env.SPLICE_MOD_DATA!, "fixture-sleep");
    symlinkSync("/bin/sleep", sleep);
    // The shell exits, but the orphan keeps the mod's stderr pipe open. Without group cleanup a JVM reader
    // waiting for EOF can hold retirement for the whole sleep.
    const shell = Bun.spawn(["/bin/sh", "-c", '"$1" 300 </dev/null >&2 & printf "%s\\n" "$!"', "fixture", sleep], {
      stdin: "ignore", stdout: "pipe", stderr: "inherit",
    });
    const [out, code] = await Promise.all([new Response(shell.stdout).text(), shell.exited]);
    const pid = Number(out.trim());
    if (code !== 0 || !Number.isInteger(pid) || pid <= 1) throw new Error("could not start the orphan sleep");
    $.log(`background sleep pid=${pid}`);
  });
};
