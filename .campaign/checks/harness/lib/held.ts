// What the held-turn checks read: a session's status line as Claude Code draws it, with its colours kept, and the
// parts of a held turn's segment.

import { spawnSync } from "node:child_process";
import type { Launched } from "../../speed/lib/session.ts";

/** The session's status line with its terminal colours: the output of its own command, run with a tick on its input. */
export function drawn(l: Launched, session: string): string {
  const command = String((l.settings.statusLine as { command?: string } | undefined)?.command ?? "");
  if (!command) throw new Error("the session's settings name no status line command");
  const tick = JSON.stringify({ session_id: session, model: { id: l.sets.ANTHROPIC_MODEL, display_name: l.sets.ANTHROPIC_MODEL }, workspace: { current_dir: l.cwd }, cwd: l.cwd });
  return spawnSync("bash", ["-c", command], { input: tick, env: l.env, cwd: l.cwd, encoding: "utf8" }).stdout ?? "";
}

/** `text` with its terminal colours taken out. */
export function plain(text: string): string {
  // biome-ignore lint/suspicious/noControlCharactersInRegex: the escape that starts a terminal colour
  return text.replace(/\u001b\[[0-9;]*m/g, "");
}

/** The session's status line as a person reads it. */
export const line = (l: Launched, session: string): string => plain(drawn(l, session));

/** How long ago the held turn's message `message` came, in seconds, as the line says it; null when the line does not
 *  say that message with an age in seconds. */
export function age(text: string, message: string): number | null {
  const at = text.indexOf(`${message} · `);
  if (at < 0) return null;
  const m = /^(\d+)s ago/.exec(text.slice(at + message.length + 3));
  return m ? Number(m[1]) : null;
}

/** The colour `message` is drawn in: the last colour escape before it in the line as drawn. */
export function colour(text: string, message: string): string | null {
  const at = text.indexOf(message);
  if (at < 0) return null;
  // biome-ignore lint/suspicious/noControlCharactersInRegex: the escape that starts a terminal colour
  const before = [...text.slice(0, at).matchAll(/\u001b\[[0-9;]*m/g)];
  return before.at(-1)?.[0] ?? null;
}
