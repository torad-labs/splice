#!/usr/bin/env node
// Launcher entry (shared by both heads): health check, version handshake,
// kill-stale, restart, and the exec-argv handoff to the thin bash shim.
//
// Kill-stale is a pgrep/lsof loop with SIGTERM→SIGKILL escalation that
// excludes our own PID/PPID — never `pkill -f` (the v29 trap: a self-matching
// pkill mid-restart left the stale proxy holding the port, the fresh spawn hit
// EADDRINUSE and silently exit(0)'d, and the shim looped on the version
// handshake forever). A surviving wrong-version proxy is now a LOUD failure
// pointing at the log, never a loop.
//
// Invocation (from bin/claudex | bin/claudithos):
//   node ensure-proxy.mjs <claudex|claudithos> [claude args…]
// stdout: NUL-separated `env` argv (unsets, KEY=VALs, claude bin, args) for
// the shim's `exec env`. All diagnostics go to stderr.
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, mkdirSync, openSync, readdirSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { CODEX_PROXY_VERSION, CLAUDITHOS_PROXY_VERSION } from '../src/versions.mjs';
import {
  assembleClaudexEnv,
  assembleClaudithosEnv,
  buildClaudeArgs,
} from './assemble-env.mjs';
import { prepareClaudexConfig, prepareClaudithosConfig } from './prepare-config.mjs';

const SRC_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');

function die(msg) {
  process.stderr.write(`launcher: ${msg}\n`);
  process.exit(1);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function proxyHealth(port) {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(1000) });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

/** Pure handshake decision — unit-tested. */
export function decideAction({ health, wantVersion, wantMode = null }) {
  if (!health || health.ok !== true) return 'start';
  if (String(health.version) !== String(wantVersion)) return 'restart';
  if (wantMode && health.mode !== wantMode) return 'patch-mode';
  return 'ok';
}

/** Exclude our own process tree from a kill candidate list — unit-tested. */
export function filterSelf(pids, self = { pid: process.pid, ppid: process.ppid }) {
  return pids.filter((pid) => Number.isFinite(pid) && pid > 1 && pid !== self.pid && pid !== self.ppid);
}

function pgrep(pattern) {
  try {
    const out = execFileSync('pgrep', ['-f', pattern], { encoding: 'utf8' });
    return out.split('\n').filter(Boolean).map((s) => parseInt(s, 10));
  } catch {
    return []; // no matches
  }
}

function pidsOnPort(port) {
  try {
    const out = execFileSync('lsof', ['-ti', `tcp:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' });
    return out.split('\n').filter(Boolean).map((s) => parseInt(s, 10));
  } catch {
    return [];
  }
}

export async function killStale({ namePattern, port }) {
  const candidates = filterSelf([...new Set([...pgrep(namePattern), ...pidsOnPort(port)])]);
  if (!candidates.length) return;
  process.stderr.write(`launcher: killing stale proxy pid(s) ${candidates.join(', ')}\n`);
  for (const pid of candidates) {
    try { process.kill(pid, 'SIGTERM'); } catch { /* already gone */ }
  }
  for (let i = 0; i < 20; i++) {
    await sleep(100);
    if (!filterSelf([...new Set([...pgrep(namePattern), ...pidsOnPort(port)])]).length) return;
  }
  for (const pid of filterSelf(pidsOnPort(port))) {
    try { process.kill(pid, 'SIGKILL'); } catch { /* already gone */ }
  }
  await sleep(200);
}

function logsDir() {
  return join(homedir(), '.claude-codex', 'logs');
}

function spawnProxy({ scriptName, proxyEnv, logName }) {
  mkdirSync(logsDir(), { recursive: true });
  const logPath = join(logsDir(), logName);
  const fd = openSync(logPath, 'a');
  const env = { ...process.env, ...proxyEnv };
  // The proxy process must never inherit the caller's Anthropic routing.
  delete env.ANTHROPIC_BASE_URL;
  delete env.ANTHROPIC_API_KEY;
  delete env.ANTHROPIC_AUTH_TOKEN;
  const child = spawn(process.execPath, [join(SRC_DIR, scriptName)], {
    detached: true,
    stdio: ['ignore', fd, fd],
    env,
  });
  child.unref();
  return logPath;
}

async function patchMode(port, mode) {
  try {
    const keyPath = join(homedir(), '.claude-codex', 'state', 'mgmt-key');
    if (!existsSync(keyPath)) return false;
    const key = readFileSync(keyPath, 'utf8').trim();
    const res = await fetch(`http://127.0.0.1:${port}/mgmt/config`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
      body: JSON.stringify({ claudithosMode: mode }),
      signal: AbortSignal.timeout(2000),
    });
    if (!res.ok) return false;
    const health = await proxyHealth(port);
    return health?.mode === mode;
  } catch {
    return false;
  }
}

export async function ensureProxy({ name, scriptName, port, wantVersion, wantMode = null, proxyEnv, logName }) {
  let health = await proxyHealth(port);
  let action = decideAction({ health, wantVersion, wantMode });

  if (action === 'patch-mode') {
    // Hot arm switch through our own management plane — no restart needed.
    if (await patchMode(port, wantMode)) {
      process.stderr.write(`launcher: ${name} arm hot-switched to ${wantMode} via /mgmt/config\n`);
      return;
    }
    action = 'restart';
  }

  if (action === 'ok') return;

  if (action === 'restart') {
    process.stderr.write(`launcher: restarting ${name} (have v${health?.version ?? '?'}, want v${wantVersion})\n`);
  }
  await killStale({ namePattern: scriptName, port });

  const logPath = spawnProxy({ scriptName, proxyEnv, logName });
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

async function main() {
  const head = process.argv[2];
  const userArgs = process.argv.slice(3);

  if (head === 'claudex') {
    const authPath = process.env.CODEX_AUTH_PATH || join(homedir(), '.codex', 'auth.json');
    if (!existsSync(authPath)) die(`no Codex auth at ${authPath} — run: codex login (Sign in with ChatGPT)`);
    try {
      const auth = JSON.parse(readFileSync(authPath, 'utf8'));
      if (!auth.tokens?.access_token) throw new Error('no access_token');
    } catch {
      die('Codex auth incomplete — run: codex login (Sign in with ChatGPT)');
    }

    const profile = assembleClaudexEnv({});
    prepareClaudexConfig({});
    await ensureProxy({
      name: 'codex-proxy',
      scriptName: 'codex-proxy.mjs',
      port: profile.port,
      wantVersion: CODEX_PROXY_VERSION,
      proxyEnv: profile.proxyEnv,
      logName: 'codex-proxy.log',
    });
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

  if (head === 'claudithos') {
    const credPath = process.env.CLAUDE_CREDENTIALS_PATH || join(homedir(), '.claude', '.credentials.json');
    if (!existsSync(credPath)) die(`no Claude OAuth at ${credPath} — log in with plain \`claude\` first`);

    const profile = assembleClaudithosEnv({});
    prepareClaudithosConfig({});
    await ensureProxy({
      name: 'claudithos',
      scriptName: 'claudithos-proxy.mjs',
      port: profile.port,
      wantVersion: CLAUDITHOS_PROXY_VERSION,
      wantMode: profile.mode,
      proxyEnv: profile.proxyEnv,
      logName: 'claudithos-proxy.log',
    });
    const bin = resolveClaudeBin();
    if (!bin) die('Claude Code not found; set CLAUDE_CODEX_CLAUDE_BIN');
    process.stderr.write(`claudithos: arm=${profile.mode} proxy=127.0.0.1:${profile.port} v${CLAUDITHOS_PROXY_VERSION} (experiment tool — disposable tasks only)\n`);
    emitExecArgv({
      unsets: profile.childUnset,
      envMap: profile.childEnv,
      bin,
      args: buildClaudeArgs(userArgs, { safeEnvVar: 'CLAUDITHOS_SAFE' }),
    });
    return;
  }

  die(`unknown head '${head}' — usage: ensure-proxy.mjs <claudex|claudithos> [claude args…]`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => die(String(err?.stack || err)));
}
