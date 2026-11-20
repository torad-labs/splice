import { lib, runAll } from "./common.ts";
const names = [
  "a 529 with Retry-After reaches the session as an overload sent upstream once",
  "a 529 with Retry-After 9 reaches the session as an overload sent upstream once",
];
await runAll(names, async (out) => {
  const { Splice, freePort } = await import(lib("server/lib/harness.ts"));
  const { session, toDoor } = await import(lib("door/lib/claude.ts"));
  const { Gateway, MODEL, TeamCli, doorRaw, errorOf } = await import(lib("custom/lib/team.ts"));
  const cli = new TeamCli();
  const gw = new Gateway(cli).start();
  gw.open = true;
  const s = await Splice.boot(`
[providers.acme]
dialect = "openai-chat"
base_url = "${gw.url}"
auth = { kind = "api-key", env = "MOCK_UPSTREAM_KEY" }
local = false
error_messages = "whole"

[[providers.acme.models]]
id = "${MODEL}"
context_window = 65536

[heads.acme]
provider = "acme"
port = ${await freePort()}
discovery_prefix = "claude-acme--"
pinned_model = "${MODEL}"
`);
  try {
    toDoor(s);
    const x = await session(s);
    const cases: [string, number, Record<string, string>, string][] = [
      [names[0]!, 529, { "retry-after": "4" }, "4"],
      [names[1]!, 529, { "retry-after": "9" }, "9"],
    ];
    for (const [name, status, headers, want] of cases) {
      gw.next.length = 0;
      gw.next.push({ status, message: "GLM-5.3-Flash is loading on the Acme cluster.", headers });
      const marker = `case-${crypto.randomUUID()}`;
      const r = await doorRaw(x, { model: `claude-acme--${MODEL}`, max_tokens: 256, stream: true, messages: [{ role: "user", content: marker }] });
      const e = errorOf(r);
      const sent = gw.turnsWith(marker).length;
      const ok = r.status === 529 && r.headers.get("retry-after") === want && e?.type === "overloaded_error" && sent === 1;
      out(name, ok, `provider answered ${status} with ${JSON.stringify(headers)}; session got status ${r.status}, retry-after ${r.headers.get("retry-after")}, error type ${e?.type}, sent upstream ${sent} time(s); wanted 529, retry-after ${want}, overloaded_error, once`);
    }
  } finally {
    await s.dispose();
    gw.stop();
    cli.dispose();
  }
});
