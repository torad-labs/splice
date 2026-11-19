// A team's published definitions, served over http on loopback from a child process.
//
// The checks run splice through Splice.cli, which is spawnSync: it blocks this process's event loop until
// splice exits, so a Bun.serve in this process could never answer splice's fetch (splice waits its
// 15 seconds and says the URL did not answer). A server in its own process answers while the CLI runs.

import { type ChildProcess, spawn } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const SERVER = `
import { existsSync, readFileSync } from "node:fs";
import { basename, join } from "node:path";
const root = process.argv[2];
const server = Bun.serve({
  port: 0,
  hostname: "127.0.0.1",
  fetch(req) {
    const f = join(root, basename(new URL(req.url).pathname));
    return existsSync(f) ? new Response(readFileSync(f)) : new Response("no", { status: 404 });
  },
});
console.log(server.port);
`;

export class Site {
  private readonly home = mkdtempSync(join(tmpdir(), "cp-site-"));
  private readonly files = join(this.home, "files");
  private child: ChildProcess | null = null;
  port = 0;

  async start(): Promise<this> {
    mkdirSync(this.files);
    const script = join(this.home, "serve.ts");
    writeFileSync(script, SERVER);
    const child = spawn(process.execPath, [script, this.files], { stdio: ["ignore", "pipe", "inherit"] });
    this.child = child;
    this.port = await new Promise<number>((resolve, reject) => {
      let out = "";
      child.stdout!.on("data", (b: Buffer) => {
        out += b.toString();
        const nl = out.indexOf("\n");
        if (nl >= 0) resolve(Number(out.slice(0, nl).trim()));
      });
      child.on("exit", (code) => reject(new Error(`the definition server exited ${code} before it listened`)));
    });
    return this;
  }

  /** Publish `text` as `/<name>`; the server reads it on each request, so later files are served too. */
  publish(name: string, text: string): string {
    writeFileSync(join(this.files, name), text);
    return this.url(name);
  }

  url(name: string): string {
    return `http://127.0.0.1:${this.port}/${name}`;
  }

  stop(): void {
    this.child?.kill();
    this.child = null;
    rmSync(this.home, { recursive: true, force: true });
  }
}
