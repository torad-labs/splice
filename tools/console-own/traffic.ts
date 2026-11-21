// Requests through a console-own Splice, sent the way Claude Code sends them: the command is launched as the
// splice-launch shim launches it (POST /launch/<command>), and its turns go to the head with the key the session's
// apiKeyHelper hands out, named by a session id, so Activity and Providers have rows to show. One of them asks the
// stand-in for a refusal, so there is a failed request among them.
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { Daemon } from "./splice.ts";

export interface Launch {
  env: Record<string, string>;
  unset: string[];
  argv: string[];
}

/** The recipe the daemon answers a launch of [command] in [cwd] with. */
export async function launch(d: Daemon, command: string, cwd: string): Promise<Launch> {
  const form = new URLSearchParams({ dangerouslySkipPermissions: "false", cwd, launch: String(process.pid) });
  const r = await d.api(`/launch/${command}`, { method: "POST", body: form.toString(), headers: { "content-type": "application/x-www-form-urlencoded" } });
  const text = await r.text();
  if (r.status !== 200) throw new Error(`launching ${command} answered ${r.status}: ${text}`);
  return JSON.parse(text) as Launch;
}

/** The key a launched session's apiKeyHelper hands Claude Code. */
function sessionKey(d: Daemon, l: Launch, cwd: string): string {
  const dir = l.env.CLAUDE_CONFIG_DIR;
  if (!dir) throw new Error("the launch named no CLAUDE_CONFIG_DIR");
  const helper = String((JSON.parse(readFileSync(join(dir, "settings.json"), "utf8")) as { apiKeyHelper?: string }).apiKeyHelper ?? "");
  if (!helper) throw new Error("the launch's settings name no apiKeyHelper");
  const env = { ...d.env(), ...l.env };
  for (const u of l.unset) delete env[u];
  const r = spawnSync("bash", ["-c", helper], { cwd, env, encoding: "utf8" });
  const key = (r.stdout ?? "").trim();
  if (!key) throw new Error(`the apiKeyHelper gave no key: ${r.stderr}`);
  return key;
}

const ASKS = [
  "List the files in the harbor folder.", "Read the map module.", "Explain the tide table.", "Add a test for the dock parser.",
  "Run the tests.", "Fix the failing assertion.", "Rename the berth helper.", "Summarise the change.",
  "Check the lint output.", "Update the changelog.", "Write the commit message.",
];

/** A dozen turns through [command], the last of which the stand-in refuses with a 429. Answers how many were sent
 *  and how many the head answered. */
export async function send(d: Daemon, command: string, cwd: string, session: string): Promise<{ sent: number; answered: number }> {
  const l = await launch(d, command, cwd);
  const key = sessionKey(d, l, cwd);
  const base = l.env.ANTHROPIC_BASE_URL;
  if (!base) throw new Error(`the launch of ${command} named no ANTHROPIC_BASE_URL`);
  let answered = 0;
  const asks = [...ASKS, "SCENARIO:ratelimit Check the queue."];
  for (const ask of asks) {
    const r = await fetch(`${base}/v1/messages`, {
      method: "POST",
      headers: { "content-type": "application/json", "anthropic-version": "2023-06-01", "x-api-key": key, "x-claude-code-session-id": session },
      body: JSON.stringify({ model: l.env.ANTHROPIC_MODEL, max_tokens: 512, stream: true, messages: [{ role: "user", content: ask }] }),
    });
    // a refusal can come inside a stream that opened 200: its error event is what says so
    const body = await r.text();
    if (r.ok && !/"type":\s*"error"/.test(body)) answered++;
  }
  return { sent: asks.length, answered };
}
