import { lib, runAll } from "./common.ts";
const names = ["DELETE of a running sign-in stops the login command before it signs in"];
await runAll(names, async (out) => {
  const { Splice, freePort, until } = await import(lib("server/lib/harness.ts"));
  const { Gateway, TeamCli, acme, withCli } = await import(lib("custom/lib/team.ts"));
  const cli = new TeamCli();
  const gw = new Gateway(cli).start();
  const s = await Splice.boot(acme({ url: gw.url, port: await freePort(), cli }), withCli(cli));
  try {
    // acme-ai login prints the sign-in address, then waits 8 s before it signs the person in
    cli.loginTakes(8);
    const started = await s.api("/api/auth/acme/login", { method: "POST", body: "{}" });
    const j = (await started.json()) as { id: string };
    await until("the sign-in prints its address", async () => !!((await (await s.api(`/api/auth/acme/login/${j.id}`)).json()) as { browser_url?: string }).browser_url, 20_000);
    const d = await s.api(`/api/auth/acme/login/${j.id}`, { method: "DELETE" });
    const cancelled = (await d.json().catch(() => ({}))) as { state?: string };
    // past the moment the login command would have finished, had it been left running
    await Bun.sleep(12_000);
    const auth = ((await (await s.api("/api/auth")).json()) as Record<string, { present?: boolean }>).acme ?? {};
    out(names[0]!, !cli.signedIn(),
      `DELETE answered ${d.status} state ${cancelled.state}, but the login command kept running after the cancel and completed the sign-in 8 s later (acme-ai wrote its signed-in marker; GET /api/auth acme present=${auth.present}); /bin/sh -c was killed and its child acme-ai login was left running`);
  } finally {
    await s.dispose();
    gw.stop();
    cli.dispose();
  }
});
