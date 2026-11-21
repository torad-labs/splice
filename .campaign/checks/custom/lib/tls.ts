// A certificate authority of a check's own, as a company's TLS-inspecting proxy has: its root, and a server
// certificate for 127.0.0.1 that it signed. Made with openssl in a folder of its own.

import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export interface Authority {
  dir: string;
  /** The root's certificate, PEM: what a ca_file names. */
  ca: string;
  /** The server's certificate and key, signed by the root. */
  cert: string;
  key: string;
  dispose(): void;
}

function run(dir: string, args: string[]): void {
  const r = spawnSync("openssl", args, { cwd: dir, encoding: "utf8" });
  if (r.status !== 0) throw new Error(`openssl ${args.join(" ")} failed:\n${r.stderr}`);
}

export function authority(): Authority {
  const dir = mkdtempSync(join(tmpdir(), "acme-ca-"));
  run(dir, ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.pem", "-days", "2", "-subj", "/CN=Acme Corp Inspection Root"]);
  run(dir, ["req", "-newkey", "rsa:2048", "-nodes", "-keyout", "server.key", "-out", "server.csr", "-subj", "/CN=127.0.0.1"]);
  writeFileSync(join(dir, "san.ext"), "subjectAltName=IP:127.0.0.1,DNS:localhost\n");
  run(dir, ["x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key", "-CAcreateserial", "-out", "server.pem", "-days", "2", "-extfile", "san.ext"]);
  return {
    dir,
    ca: join(dir, "ca.pem"),
    cert: join(dir, "server.pem"),
    key: join(dir, "server.key"),
    dispose: () => rmSync(dir, { recursive: true, force: true }),
  };
}
