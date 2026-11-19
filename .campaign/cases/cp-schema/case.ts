import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { lib, runAll } from "./common.ts";
const names = ["ca_file of plain text is a boot finding", "ca_file of a broken PEM block is a boot finding"];
await runAll(names, async (out) => {
  const { Splice } = await import(lib("server/lib/harness.ts"));
  const { TeamCli, acme, withCli } = await import(lib("custom/lib/team.ts"));
  const cli = new TeamCli();
  const cases: [string, string][] = [
    [names[0]!, "this is not a certificate\n"],
    [names[1]!, "-----BEGIN CERTIFICATE-----\nnot base64 at all\n-----END CERTIFICATE-----\n"],
  ];
  for (const [name, content] of cases) {
    const s = await Splice.create("", withCli(cli));
    const pem = join(s.home, "bad.pem");
    writeFileSync(pem, content);
    s.writeConfig(acme({ url: "https://ai.acme.example/v1", port: 47001, cli, provider: `ca_file = "${pem}"` }));
    let up = true;
    try { await s.start(); } catch { up = false; }
    const log = s.logTail(20000);
    await s.dispose();
    const finding = /splice\.toml has \d+ problem/.test(log) && /providers\.acme\.ca_file/.test(log);
    const crashed = /UNCAUGHT/.test(log);
    out(name, !up && finding && !crashed,
      up ? "the daemon booted with it" : crashed ? "boot crashed (UNCAUGHT on main, from CertificateFactory.generateCertificates in ProviderCaFile) instead of listing providers.acme.ca_file as a finding" : "boot refused it without naming providers.acme.ca_file");
  }
  cli.dispose();
});
