// Goal (2026-11-10): a first install downloads the 0.4.0 jar, about 473 MB, on an ordinary connection. install.sh's
// downloads had no stall limit and no resume, so a dead connection hung the installer forever and a dropped one started
// over. Now a try ends after SPLICE_DOWNLOAD_STALL_S seconds with no data, a broken try resumes where it stopped, a
// server that cannot resume starts the file over, and three tries in a row that get no further end the install with a
// sentence that says where it stopped and how to retry. The checksum check after each download is as strict as before.
//
// The real curl against a loopback node:http server that cuts or stalls the jar on purpose; the rest of the machine is
// faked the way install-provenance.test.ts fakes it.
import { afterAll, describe, expect, test } from "bun:test";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createHash } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AddressInfo } from "node:net";
import { layout } from "../../gate/src/lib/repo.ts";

const { repoRoot } = layout();
const workspaces: string[] = [];
afterAll(() => {
  for (const dir of workspaces) rmSync(dir, { recursive: true, force: true });
});

// Release mode runs from a copy outside the checkout, the way `curl … | bash` runs it.
const scriptDir = mkdtempSync(join(tmpdir(), "install-resume-script-"));
workspaces.push(scriptDir);
const INSTALLER = join(scriptDir, "install.sh");
writeFileSync(INSTALLER, readFileSync(join(repoRoot, "install.sh")));

const TOOLS = [
  "bash", "env", "sh", "uname", "curl", "sha256sum", "awk", "grep", "sed", "mktemp", "cp", "mv", "rm",
  "ln", "readlink", "install", "chmod", "mkdir", "dirname", "cat", "head", "tr", "timeout", "printf",
];
const TOOL_FARM = mkdtempSync(join(tmpdir(), "install-resume-tools-"));
workspaces.push(TOOL_FARM);
for (const tool of TOOLS) {
  const real = Bun.which(tool);
  if (!real) throw new Error(`the installer test needs ${tool} on PATH`);
  symlinkSync(real, join(TOOL_FARM, tool));
}

// Big enough that a cut lands mid-file; the fake java never reads it.
const JAR = "synthetic splice jar bytes\n".repeat(40_000);
const SHIM = '#!/usr/bin/env node\n// synthetic splice-launch\nconst SPLICE_SHIM_VERSION = "shim-test";\nconst SPLICE_GATEWAY_VERSION = "9.9.9";\n';
const sha = (text: string) => createHash("sha256").update(text).digest("hex");
const SUMS = `${sha(JAR)}  splice.jar\n${sha(SHIM)}  splice-launch\n`;

function exe(path: string, body: string) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

function sandbox() {
  const dir = mkdtempSync(join(tmpdir(), "install-resume-"));
  workspaces.push(dir);
  const bin = join(dir, "path");
  mkdirSync(bin);
  exe(join(bin, "java"), `#!/usr/bin/env bash
case "$*" in
  -version) echo 'openjdk version "21.0.4" 2024-07-16' >&2 ;;
  *" shim-version") echo shim-test ;;
  *" version") echo "splice 9.9.9" ;;
  *" install --all") mkdir -p "$SPLICE_BIN_DIR" && ln -sfn "$SPLICE_JAR" "$SPLICE_BIN_DIR/splice" ;;
  *) : ;;
esac
`);
  exe(join(bin, "node"), "#!/usr/bin/env bash\n[ \"$1\" = -v ] && echo v24.0.0\nexit 0\n");
  exe(join(bin, "claude"), "#!/usr/bin/env bash\nexit 0\n");
  return { dir, bin, share: join(dir, "share"), binDir: join(dir, "bin") };
}

/** How the server answers the jar: [n] is which request for it this is (1 for the first). */
type JarAnswer = (req: IncomingMessage, res: ServerResponse, n: number) => void;

/** The rest of the jar from the request's Range, as a 206. */
function rest(req: IncomingMessage, res: ServerResponse) {
  const from = Number((req.headers.range ?? "bytes=0-").replace("bytes=", "").replace("-", ""));
  res.writeHead(206, { "Content-Range": `bytes ${from}-${JAR.length - 1}/${JAR.length}`, "Content-Length": JAR.length - from });
  res.end(JAR.slice(from));
}

/** The first [count] bytes of a 200, then the connection is cut under it. */
function cut(res: ServerResponse, count: number) {
  res.writeHead(200, { "Content-Length": JAR.length });
  res.write(JAR.slice(0, count), () => res.socket?.destroy());
}

async function install(jar: JarAnswer) {
  const box = sandbox();
  const ranges: (string | undefined)[] = [];
  let jarRequests = 0;
  const server = createServer((req, res) => {
    const name = (req.url ?? "/").slice(1);
    if (name === "splice.jar") {
      ranges.push(req.headers.range);
      jar(req, res, ++jarRequests);
    } else if (name === "splice-launch") {
      res.end(SHIM);
    } else if (name === "sha256sums.txt") {
      res.end(SUMS);
    } else {
      res.writeHead(404).end("not found");
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  try {
    const proc = Bun.spawn(["bash", INSTALLER], {
      cwd: box.dir,
      stdin: "ignore",
      stdout: "pipe",
      stderr: "pipe",
      env: {
        PATH: `${box.bin}:${TOOL_FARM}`,
        HOME: box.dir,
        SPLICE_SHARE_DIR: box.share,
        SPLICE_BIN_DIR: box.binDir,
        SPLICE_RELEASE_BASE_URL: `http://127.0.0.1:${port}`,
        SPLICE_DOWNLOAD_STALL_S: "1",
      },
    });
    const [out, err, code] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text(), proc.exited]);
    const leftovers = existsSync(box.share) ? readdirSync(box.share).filter((name) => name.startsWith(".")) : [];
    return { code, out: out + err, jar: join(box.share, "splice.jar"), ranges, leftovers };
  } finally {
    server.closeAllConnections();
    server.close();
  }
}

describe("install.sh downloads survive a slow or broken connection", () => {
  test("a cut connection resumes where it stopped and the install completes", async () => {
    const r = await install((req, res, n) => (n === 1 ? cut(res, 300_000) : rest(req, res)));
    expect(r.code, r.out).toBe(0);
    expect(readFileSync(r.jar, "utf8")).toBe(JAR);
    expect(r.out).toContain("resuming");
    expect(r.ranges[0]).toBeUndefined();
    const from = Number((r.ranges[1] ?? "").replace("bytes=", "").replace("-", ""));
    expect(from > 0 && from <= 300_000, `the second request resumed: ${r.ranges[1]}`).toBe(true);
    expect(r.out).toContain("splice.jar: OK");
  });

  test("a server that cannot resume sends the whole jar again and the install completes", async () => {
    const r = await install((_req, res, n) => {
      if (n === 1) return cut(res, 300_000);
      res.writeHead(200, { "Content-Length": JAR.length });
      res.end(JAR);
    });
    expect(r.code, r.out).toBe(0);
    expect(readFileSync(r.jar, "utf8")).toBe(JAR);
    expect(r.out).toContain("cannot resume");
  });

  test("a server that cannot resume and drops at the same place every time ends the install", async () => {
    const r = await install((_req, res) => cut(res, 300_000));
    expect(r.code).not.toBe(0);
    expect(r.out).toMatch(/downloading \S+splice\.jar failed at 0\.[23] MB \(curl exit \d+\), 3 tries in a row got no further; check the connection, then run the installer again/);
    expect(existsSync(r.jar)).toBe(false);
    expect(r.leftovers.filter((name) => name.startsWith(".splice.jar"))).toEqual([]);
  }, 60_000); // each try and each start-over waits two seconds before the next

  test("a download that stops moving ends the install with where it stalled and how to retry", async () => {
    const r = await install((_req, res, n) => {
      // The first answer sends a little and goes quiet; every resume answers and sends nothing.
      if (n === 1) {
        res.writeHead(200, { "Content-Length": JAR.length });
        res.write(JAR.slice(0, 200_000));
      } else {
        res.writeHead(206, { "Content-Range": `bytes 200000-${JAR.length - 1}/${JAR.length}`, "Content-Length": JAR.length - 200_000 });
        res.flushHeaders();
      }
    });
    expect(r.code).not.toBe(0);
    expect(r.out).toMatch(/downloading \S+splice\.jar stalled at 0\.2 MB: no data for 1s, 3 tries in a row; check the connection, then run the installer again/);
    expect(r.ranges.length).toBe(4);
    expect(existsSync(r.jar)).toBe(false);
    expect(r.leftovers.filter((name) => name.startsWith(".splice.jar"))).toEqual([]);
  }, 30_000); // four tries, each waiting out a one-second stall
});
