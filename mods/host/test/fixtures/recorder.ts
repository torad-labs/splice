import type { Register } from "../../../contract/index.ts";

export const register: Register = on => {
  on("mod.start", async $ => {
    const previous = Number(await $.store.get("run/count") ?? 0);
    await $.store.set("run/count", previous + 1);
    await $.store.set("run/to-delete", true);
    await $.store.delete("run/to-delete");
    $.log(`run ${previous + 1}; keys: ${(await $.store.list("run/")).join(",")}`);
    try { await $.store.set("large", "é".repeat(40_000)); }
    catch (error) { $.log((error as Error).message); }
    try { $.sessions.invalidate(); }
    catch (error) { $.log((error as Error).message); }
  });
  on("mod.stop", async ($, event) => {
    await $.store.set("last-stop", event.mod);
    $.log(`clean stop for ${event.mod}`);
  });
  on("launch.prepare", async (_, event) => event);
  on("sessions.list", async () => []);
  on("command.run", async () => ({ text: "unserved" }));
  on("ui.render", async () => ({ html: "", title: "" }));
  on("http.request", async () => ({ status: 204 }));
};
