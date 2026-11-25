// An mcp mode met by a real client, on a Splice of its own (tools/console-own): never the Splice a person runs.
//
// It builds this checkout's daemon, starts it in a home of its own with the template app turned on, and asks it for a
// launch of a head's command, as the launcher shim does. The launch's arguments must carry the file of splice's own
// MCP servers. From that file's entry alone (the address and the key a session would be given), two things that are
// not splice's code take over:
//
//   - the MCP project's own client (@modelcontextprotocol/sdk) initializes, lists the tools, calls show_note, lists the
//     resources and reads the page;
//   - the MCP Apps project's own host bridge (@modelcontextprotocol/ext-apps, AppBridge) draws that page in a sandboxed
//     frame in Chrome, answers its ui/initialize, and hands it the call's arguments and result. The page is then read
//     back from the frame and a picture of it is kept.
//
// Neither package is a dependency of this repository: the check installs the two, pinned, into a temp folder of its
// own, so it needs the network. It needs a Mac or a Linux machine with Java 21, bun and Chrome
// (SCREEN_REVIEW_BROWSER), and it is run by hand:
//
//   bun .campaign/checks/modes/mcp-real-client.ts --out <folder>      (SPLICE_JAR=<jar> skips the build)
//
// Everything it shows is made up.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { chromium } from "@playwright/test";
import { makeHome } from "../../../tools/console-own/home.ts";
import { Daemon, freePort, providers, standIn, toml } from "../../../tools/console-own/splice.ts";
import { launch } from "../../../tools/console-own/traffic.ts";
import { layout } from "../../../tools/gate/src/lib/repo.ts";

const ROOT = layout().repoRoot;
const out = join(ROOT, process.argv[process.argv.indexOf("--out") + 1] || "build/modes-mcp-real-client");
const SDK = "@modelcontextprotocol/sdk@1.32.2";
const APPS = "@modelcontextprotocol/ext-apps@2.0.4";
const NOTE = { title: "Tide", text: "High at 04:10, low at 10:25." };
const said: string[] = [];
const say = (line: string) => {
  said.push(line);
  console.log(line);
};
function must(what: string, ok: boolean, got: unknown): void {
  say(`${ok ? "ok  " : "FAIL"} ${what}${ok ? "" : `: ${typeof got === "string" ? got : JSON.stringify(got)}`}`);
  if (!ok) process.exitCode = 1;
}

/** The daemon's jar: the one named, or this checkout built as any build is. */
function jar(): string {
  if (process.env.SPLICE_JAR) return process.env.SPLICE_JAR;
  const built = spawnSync("./gradlew", [":app:shadowJar", "--quiet"], { cwd: ROOT, stdio: ["ignore", 2, 2] });
  if (built.status !== 0) throw new Error("the build failed");
  return join(ROOT, "app/build/libs/app-all.jar");
}

/** A temp folder with the two packages in it, and the host page's script built for a browser. */
function packages(): string {
  const dir = mkdtempSync(join(tmpdir(), "modes-mcp-client-"));
  writeFileSync(join(dir, "package.json"), '{"name":"modes-mcp-client","private":true}\n');
  const added = spawnSync("bun", ["add", SDK, APPS], { cwd: dir, encoding: "utf8" });
  if (added.status !== 0) throw new Error(`could not install ${SDK} and ${APPS}: ${added.stderr}`);
  // The host: a sandboxed frame given the page as its document, and the MCP Apps bridge on the other end of it.
  writeFileSync(
    join(dir, "host.ts"),
    `import { AppBridge, PostMessageTransport } from "@modelcontextprotocol/ext-apps/app-bridge";
(window as any).draw = async (html: string, args: Record<string, unknown>, result: any) => {
  const frame = document.createElement("iframe");
  frame.setAttribute("sandbox", "allow-scripts");
  frame.style.cssText = "width:640px;height:220px;border:1px solid #999";
  document.body.append(frame);
  const bridge = new AppBridge(null, { name: "acceptance-host", version: "1" }, {});
  const ready = new Promise<void>((resolve) => { bridge.oninitialized = () => resolve(); });
  // the bridge listens before the page loads, so the page's first message is heard
  await bridge.connect(new PostMessageTransport(frame.contentWindow!, frame.contentWindow!));
  frame.srcdoc = html;
  await ready;
  await bridge.sendToolInput({ arguments: args });
  await bridge.sendToolResult(result);
  return { app: bridge.getAppVersion() ?? null };
};
`,
  );
  const bundled = spawnSync("bun", ["build", "host.ts", "--target", "browser", "--outfile", "host.js"], { cwd: dir, encoding: "utf8" });
  if (bundled.status !== 0) throw new Error(`could not build the host page: ${bundled.stderr}${bundled.stdout}`);
  return dir;
}

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
const pk = packages();
const h = makeHome();
let d: Daemon | undefined;
try {
  const standin = await standIn(h);
  const list = await providers();
  d = new Daemon(h, await freePort());
  await d.start(jar(), `${toml(h, d.port, standin, list)}\n[modes.templateapp]\non = true\n`);
  const command = list[0]!.command;

  // 1. the launch names splice's own servers, in a file of its state, as the one word --mcp-config=<file>
  const l = await launch(d, command, h.work);
  const files = l.argv.flatMap((arg) => (arg.startsWith("--mcp-config=") ? [arg.slice("--mcp-config=".length)] : []));
  const own = files.find((file) => file.startsWith(h.state) && /own-mcp-/.test(file));
  must(`a launch of ${command} carries --mcp-config=<file> with the file of splice's own servers`, own !== undefined, l.argv);
  if (!own) throw new Error("no file to read an entry from");
  const servers = (JSON.parse(readFileSync(own, "utf8")) as { mcpServers: Record<string, { type: string; url: string; headers: Record<string, string> }> }).mcpServers;
  const entry = servers.templateapp;
  must("the file names templateapp as an http server at the daemon's own address", entry?.type === "http" && entry.url === `${d.url}/mcp/templateapp`, servers);
  if (!entry) throw new Error("no entry for templateapp");

  // 2. the MCP project's own client, given that entry and nothing else
  const { Client } = await import(join(pk, "node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js"));
  const { StreamableHTTPClientTransport } = await import(join(pk, "node_modules/@modelcontextprotocol/sdk/dist/esm/client/streamableHttp.js"));
  const client = new Client({ name: "acceptance-client", version: "1" }, { capabilities: { extensions: { "io.modelcontextprotocol/ui": { mimeTypes: ["text/html;profile=mcp-app"] } } } });
  await client.connect(new StreamableHTTPClientTransport(new URL(entry.url), { requestInit: { headers: entry.headers } }));
  must("the client initializes, and the server names itself templateapp", client.getServerVersion()?.name === "templateapp", client.getServerVersion());
  must("the server's instructions are the mode's prompt.md", String(client.getInstructions() ?? "").includes("call show_note"), client.getInstructions());
  const tools = (await client.listTools()).tools as { name: string; _meta?: { ui?: { resourceUri?: string } } }[];
  const uri = tools[0]?._meta?.ui?.resourceUri ?? "";
  must("it lists one tool, show_note, which names its page", tools.length === 1 && tools[0]!.name === "show_note" && uri === "ui://templateapp/note", tools);
  const called = (await client.callTool({ name: "show_note", arguments: NOTE })) as { content: { type: string; text: string }[]; isError?: boolean };
  must("a call of show_note answers with the note", called.isError !== true && called.content[0]?.text === `${NOTE.title}\n\n${NOTE.text}`, called);
  const refused = (await client.callTool({ name: "show_note", arguments: { text: 7 } })) as { content: { text: string }[]; isError?: boolean };
  must("a call with no string text is an error result that says so", refused.isError === true && refused.content[0]!.text.includes("text"), refused);
  const resources = (await client.listResources()).resources as { uri: string; mimeType?: string }[];
  must("it lists the page as an MCP App resource", resources.length === 1 && resources[0]!.uri === uri && resources[0]!.mimeType === "text/html;profile=mcp-app", resources);
  const read = (await client.readResource({ uri })).contents as { uri: string; mimeType?: string; text?: string }[];
  const html = read[0]?.text ?? "";
  must("the page reads as an html document of that type", read[0]?.mimeType === "text/html;profile=mcp-app" && html.trimStart().startsWith("<!doctype html>"), read[0]?.mimeType);
  await client.close();

  // 3. the MCP Apps project's own host bridge draws the page
  const browser = await chromium.launch({ executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined, args: ["--no-sandbox"] });
  try {
    const page = await browser.newPage({ viewport: { width: 720, height: 300 } });
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.setContent("<!doctype html><html><body style='margin:16px;font:14px system-ui'></body></html>");
    await page.addScriptTag({ path: join(pk, "host.js") });
    const drawn = await page.evaluate(([doc, args, result]) => (window as unknown as { draw: (h: string, a: unknown, r: unknown) => Promise<{ app: { name?: string } | null }> }).draw(doc as string, args, result), [html, NOTE, called] as const);
    must("the page says who it is to the host bridge (ui/initialize)", drawn.app?.name === "templateapp", drawn);
    const frame = page.frames().find((f) => f !== page.mainFrame());
    if (!frame) throw new Error("the host made no frame");
    await frame.locator("#text:not(.quiet)").waitFor({ timeout: 5_000 });
    const shown = { title: await frame.locator("#title").textContent(), text: await frame.locator("#text").textContent() };
    must("the page shows the note it was handed: its title and its text", shown.title === NOTE.title && shown.text === NOTE.text, shown);
    must("nothing on the page threw", errors.length === 0, errors);
    await page.screenshot({ path: join(out, "note-drawn.png") });
  } finally {
    await browser.close();
  }
} finally {
  writeFileSync(join(out, "report.txt"), `${said.join("\n")}\n`);
  // the home's own processes and the home, as `console-own down` leaves them
  spawnSync("bun", [join(ROOT, "tools/console-own/index.ts"), "down", h.root], { stdio: "ignore" });
  rmSync(pk, { recursive: true, force: true });
  if (existsSync(h.root)) rmSync(h.root, { recursive: true, force: true });
}
