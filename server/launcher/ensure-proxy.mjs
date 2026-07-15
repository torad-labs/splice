#!/usr/bin/env node
// Launcher entry (shared by both heads): health check, version handshake,
// kill-stale, restart, and the exec-argv handoff to the thin bash shim.
//
// Process-lifecycle primitives (health / spawn / kill / handshake) now live in
// heads.mjs — the ONE source shared with the control server so start/stop/kill
// logic is never forked. This entry keeps the launcher-only orchestration: the
// version handshake, claude-bin resolution, and
// the NUL-separated exec-argv handoff.
//
// Invocation (from bin/claudex):
//   node ensure-proxy.mjs <claudex> [claude args…]
// stdout: NUL-separated `env` argv (unsets, KEY=VALs, claude bin, args) for
// the shim's `exec env`. All diagnostics go to stderr.
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { CODEX_PROXY_VERSION, CONTROL_SERVER_VERSION } from '../src/versions.mjs';
import { getConfig, statePaths } from '../src/config.mjs';
import { proxyLogName } from '../src/mgmt/api.mjs';
import {
  assembleClaudexEnv,
  buildClaudeArgs,
} from './assemble-env.mjs';
import { prepareClaudexConfig } from './prepare-config.mjs';
import { decideAction, filterSelf, killStale, proxyHealth, sleep, spawnProxy, stalePattern } from './heads.mjs';

// Re-export the pure lifecycle helpers so existing importers (launcher tests) keep resolving them here.
export { decideAction, filterSelf, proxyHealth, stalePattern };

const SRC_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');

function die(msg) {
  process.stderr.write(`launcher: ${msg}\n`);
  process.exit(1);
}

export async function ensureProxy({ name, scriptName, port, wantVersion, proxyEnv, logName }) {
  let health = await proxyHealth(port);
  const action = decideAction({ health, wantVersion });

  if (action === 'ok') return;

  if (action === 'restart') {
    process.stderr.write(`launcher: restarting ${name} (have v${health?.version ?? '?'}, want v${wantVersion})\n`);
  }
  await killStale({ scriptName, port });

  const logPath = spawnProxy({ scriptName, port, proxyEnv, logName });
  process.stderr.write(`launcher: starting ${name} on 127.0.0.1:${port} (log: ${logPath})\n`);

  for (let i = 0; i < 40; i++) {
    await sleep(100);
    health = await proxyHealth(port);
    if (health) break;
  }
  if (!health) die(`${name} failed to start — see ${logPath}`);
  if (String(health.version) !== String(wantVersion)) {
    // LOUD, never a loop: something else owns the port with the wrong version.
    die(`${name} on :${port} reports v${health.version}, want v${wantVersion} — a stale instance survived; see ${logPath}`);
  }
  if (wantMode && health.mode !== wantMode && !(await patchMode(port, wantMode))) {
    die(`${name} arm is ${health.mode}, want ${wantMode}, and the mgmt hot-switch failed — see ${logPath}`);
  }
}

export function resolveClaudeBin(env = process.env) {
  if (env.CLAUDE_CODEX_CLAUDE_BIN) return env.CLAUDE_CODEX_CLAUDE_BIN;
  const versionsDir = join(homedir(), '.local', 'share', 'claude', 'versions');
  try {
    const entries = readdirSync(versionsDir).sort((a, b) =>
      a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }));
    for (let i = entries.length - 1; i >= 0; i--) {
      const candidate = join(versionsDir, entries[i]);
      try {
        execFileSync('test', ['-x', candidate]);
        return candidate;
      } catch { /* not executable */ }
    }
  } catch { /* no versions dir */ }
  try {
    return execFileSync('command', ['-v', 'claude'], { encoding: 'utf8', shell: true }).trim() || null;
  } catch {
    return null;
  }
}

function emitExecArgv({ unsets, envMap, bin, args }) {
  const tokens = [];
  for (const name of unsets) tokens.push('-u', name);
  for (const [k, v] of Object.entries(envMap)) tokens.push(`${k}=${v}`);
  tokens.push(bin, ...args);
  process.stdout.write(tokens.join('\0') + '\0');
}

function openBrowser(url) {
  const cmd = process.platform === 'darwin' ? 'open' : process.platform === 'win32' ? 'cmd' : 'xdg-open';
  const args = process.platform === 'win32' ? ['/c', 'start', '', url] : [url];
  try { spawn(cmd, args, { stdio: 'ignore', detached: true }).unref(); } catch { /* best effort */ }
}

/**
 * Ensure the control server (spliced) is up. wait=false (a head launch) just
 * fires the spawn and returns — the dashboard comes up async and never blocks the
 * head; wait+fatal (`claudex dashboard`) confirms /health before opening a tab.
 * Concurrent launches racing to spawn are safe: listenLoopback exits 0 on
 * EADDRINUSE, so the first to bind wins and the rest no-op.
 */
async function ensureControlServer({ wait = false, fatal = false } = {}) {
  const port = getConfig().controlPort;
  const health = await proxyHealth(port);
  if (health && String(health.version) === String(CONTROL_SERVER_VERSION)) return { port, already: true };
  if (health) await killStale({ scriptName: 'control-server.mjs', port });
  const logPath = spawnProxy({ scriptName: 'control-server.mjs', port, proxyEnv: {}, logName: proxyLogName('control-server', port) });
  if (!wait) return { port, logPath, spawned: true };
  for (let i = 0; i < 40; i++) {
    await sleep(100);
    if (await proxyHealth(port)) return { port, logPath };
  }
  const msg = `control-server failed to start on :${port} — see ${logPath}`;
  if (fatal) die(msg);
  process.stderr.write(`launcher: ${msg}\n`);
  return { port, logPath, failed: true };
}

async function handleDashboardCmd() {
  await ensureControlServer({ wait: true, fatal: true });
  const url = `http://127.0.0.1:${getConfig().controlPort}/`;
  process.stderr.write(`\nsplice dashboard → ${url}\n  unlock key: ${statePaths.mgmtKey()}\n\n`);
  openBrowser(url);
  emitExecArgv({ unsets: [], envMap: {}, bin: 'echo', args: [`splice dashboard: ${url}`] });
}

async function main() {
  const head = process.argv[2];
  const userArgs = process.argv.slice(3);

  // `claudex dashboard` — head-agnostic: open spliced.
  if (userArgs[0] === 'dashboard') { await handleDashboardCmd(); return; }

  if (head === 'claudex') {
    if (userArgs[0] === 'login') {
      // `claudex login` → interactive Sign in with ChatGPT. Emit an exec argv so
      // the shim runs the login CLI with a real tty (opens the browser, binds
      // the :1455 OAuth callback, writes ~/.codex/auth.json).
      emitExecArgv({
        unsets: [],
        envMap: {},
        bin: process.execPath,
        args: [join(SRC_DIR, 'auth', 'codex-login.mjs'), ...userArgs.slice(1)],
      });
      return;
    }
    const authPath = process.env.CODEX_AUTH_PATH || join(homedir(), '.codex', 'auth.json');
    if (!existsSync(authPath)) die(`no Codex auth at ${authPath} — run: claudex login (Sign in with ChatGPT)`);
    try {
      const auth = JSON.parse(readFileSync(authPath, 'utf8'));
      if (!auth.tokens?.access_token) throw new Error('no access_token');
    } catch {
      die('Codex auth incomplete — run: claudex login (Sign in with ChatGPT)');
    }

    const profile = assembleClaudexEnv({});
    prepareClaudexConfig({});
    await ensureProxy({
      name: 'codex-proxy',
      scriptName: 'codex-proxy.mjs',
      port: profile.port,
      wantVersion: CODEX_PROXY_VERSION,
      proxyEnv: profile.proxyEnv,
      logName: proxyLogName('codex-proxy', profile.port),
    });
    await ensureControlServer(); // best-effort: the dashboard rides alongside every head launch
    const bin = resolveClaudeBin();
    if (!bin) die('Claude Code not found; set CLAUDE_CODEX_CLAUDE_BIN');
    process.stderr.write(
      `claudex: proxy 127.0.0.1:${profile.port} v${CODEX_PROXY_VERSION} model=${profile.model} ctx=${profile.contextWindow} autocompact_window=${profile.childEnv.CLAUDE_CODE_AUTO_COMPACT_WINDOW} pct=${profile.childEnv.CLAUDE_AUTOCOMPACT_PCT_OVERRIDE}\n`,
    );
    emitExecArgv({
      unsets: profile.childUnset,
      envMap: profile.childEnv,
      bin,
      args: buildClaudeArgs(userArgs, { defaultModel: profile.model, safeEnvVar: 'CLAUDEX_SAFE' }),
    });
    return;
  }

  die(`unknown head '${head}' — usage: ensure-proxy.mjs <claudex> [claude args…]`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => die(String(err?.stack || err)));
}
