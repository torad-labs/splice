/**
 * The environment a harness daemon starts with: the caller's, WITHOUT the variable families that
 * point a daemon at a state root, a credential or an account (CLAUDEX_, CODEX_, SPLICE_, CHATGPT_),
 * and then the harness's own keys, which always win.
 *
 * V4-294: three builders (code-mode's mock and compare daemons, heads' reasoning-cache daemon)
 * spread process.env and set only CLAUDEX_STATE_DIR, which StatePaths reads AFTER SPLICE_STATE_DIR
 * (StatePaths.kt:46,50). A shell that exported SPLICE_STATE_DIR booted the "isolated" daemon on the
 * operator's real state (mgmt-key, config.json, logs) beside the live one. oracle.ts dropped the
 * family first; this is that filter, once, for every harness daemon.
 */
const DAEMON_FAMILY = /^(CLAUDEX_|CODEX_|SPLICE_|CHATGPT_)/;

export function daemonEnv(
  own: Record<string, string>,
  caller: Record<string, string | undefined> = process.env,
): Record<string, string> {
  const kept = Object.entries(caller)
    .filter((kv): kv is [string, string] => kv[1] !== undefined && !DAEMON_FAMILY.test(kv[0]));
  return { ...Object.fromEntries(kept), ...own };
}
