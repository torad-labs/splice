// Config-dir isolation (the slimmed claudex-prepare — only the pure half):
// build ~/.claude-codex (or ~/.claude-mythos) as a CLAUDE_CONFIG_DIR that
// shares the operator's real profile via symlinks, with its own .claude.json
// state so codex model options never leak into plain `claude`.
import { existsSync, lstatSync, mkdirSync, readFileSync, rmSync, symlinkSync, unlinkSync, writeFileSync, copyFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CODEX_MODEL_OPTIONS, DEFAULT_CODEX_MODEL, availableModelIds } from '../src/models/codex-models.mjs';

// In-repo statusline script (rendered on every Claude Code tick). Absolute path so
// it resolves regardless of the launch cwd; regenerated into settings.json each start.
const STATUSLINE_SCRIPT = join(dirname(fileURLToPath(import.meta.url)), '..', 'statusline', 'claudex-statusline.mjs');

const SHARED_LINKS = ['settings.json', 'agents', 'commands', 'skills', 'hooks', 'plugins', 'CLAUDE.md'];

// Non-destructive UI/state flags ported from ~/.claude.json so thinking
// callouts, effort UI, verbose prefs, etc. match plain Claude. Never session
// history, credentials, or counters that would couple accounts.
const PORT_KEYS = [
  'verbose',
  'showSpinnerTree',
  'tipsHistory',
  'effortCalloutV2Dismissed',
  'unpinOpus47LaunchEffort',
  'unpinOpus48LaunchEffort',
  'unpinFable5LaunchEffort',
  'opusProMigrationComplete',
  'sonnet1m45MigrationComplete',
  'hasCompletedOnboarding',
  'lastOnboardingVersion',
  'autoUpdates',
  'theme',
];

function readJson(p) {
  try {
    if (existsSync(p)) return JSON.parse(readFileSync(p, 'utf8'));
  } catch { /* ignore */ }
  return {};
}

function linkShared(configDir, home) {
  const globalDir = join(home, '.claude');
  for (const item of SHARED_LINKS) {
    const src = join(globalDir, item);
    const dst = join(configDir, item);
    let srcExists = existsSync(src);
    try { srcExists = srcExists || Boolean(lstatSync(src)); } catch { /* keep */ }
    if (!srcExists) continue;
    try {
      // If a previous run left a real file where the symlink belongs, replace it.
      const st = (() => { try { return lstatSync(dst); } catch { return null; } })();
      if (st && !st.isSymbolicLink()) {
        if (st.isDirectory()) continue; // never delete a real directory the operator made
        rmSync(dst, { force: true });
      } else if (st) {
        unlinkSync(dst);
      }
      symlinkSync(src, dst);
    } catch { /* leave whatever is there */ }
  }
}

/** Write claudex's settings.json as a REAL merged file: the operator's global
 * settings + a claudex-only `availableModels` allowlist (+ `enforceAvailableModels`)
 * that REPLACES the /model picker with the codex catalog, hiding the built-in
 * Claude models (whose picker rows are hardcoded and can't be renamed away).
 * Re-merged every launch so global-settings changes still propagate; plain
 * `claude` (which reads ~/.claude/settings.json) is untouched. The symlink
 * linkShared() just made is broken FIRST — writing through it would clobber the
 * operator's real settings. */
function writeClaudexSettings(configDir, home) {
  const dstPath = join(configDir, 'settings.json');
  const global = readJson(join(home, '.claude', 'settings.json'));
  let existing = {};
  try {
    const st = lstatSync(dstPath);
    if (st.isSymbolicLink()) unlinkSync(dstPath);
    else existing = readJson(dstPath);
  } catch { /* no settings.json yet */ }
  const allow = availableModelIds();
  // Preserve a saved /model choice when it is still an allowed codex model.
  const model = existing.model && allow.includes(existing.model) ? existing.model : DEFAULT_CODEX_MODEL;
  writeFileSync(dstPath, JSON.stringify({
    ...global,
    availableModels: allow,
    enforceAvailableModels: true,
    model,
    // claudex bottom bar: model · actual tokens used/window · cache hit% · repo/branch.
    // process.execPath pins the launcher's own node so the tick never depends on PATH.
    statusLine: { type: 'command', command: `"${process.execPath}" "${STATUSLINE_SCRIPT}"`, padding: 0 },
  }, null, 2) + '\n');
}

/** claudex: isolated config dir + model picker cache + MCP/UI-state inherit. */
export function prepareClaudexConfig({ home = homedir(), configDir } = {}) {
  const dir = configDir ?? join(home, '.claude-codex');
  if (!dir.includes('.claude-codex')) {
    throw new Error(`prepare-config: refuse to write outside .claude-codex: ${dir}`);
  }
  mkdirSync(dir, { recursive: true });
  linkShared(dir, home);
  writeClaudexSettings(dir, home);

  const statePath = join(dir, '.claude.json');
  const globalData = readJson(join(home, '.claude.json'));
  const data = readJson(statePath);

  // Models for this CLAUDE_CONFIG_DIR only
  data.additionalModelOptionsCache = CODEX_MODEL_OPTIONS;

  // Inherit user-level MCPs from plain Claude
  const globalMcp = globalData.mcpServers;
  if (globalMcp && typeof globalMcp === 'object' && !Array.isArray(globalMcp)) {
    data.mcpServers = { ...globalMcp };
    for (const [name, cfg] of Object.entries(data.mcpServers)) {
      if (!cfg || typeof cfg !== 'object') delete data.mcpServers[name];
    }
  }

  for (const k of PORT_KEYS) {
    if (globalData[k] !== undefined && data[k] === undefined) data[k] = globalData[k];
  }
  if (globalData.verbose !== undefined) data.verbose = globalData.verbose;

  data.customApiKeyResponses = {
    approved: data.customApiKeyResponses?.approved ?? [],
    rejected: [],
  };
  data.hasCompletedOnboarding = true;

  writeFileSync(statePath, JSON.stringify(data, null, 2) + '\n');
  return { configDir: dir, models: CODEX_MODEL_OPTIONS.length, mcpServers: Object.keys(data.mcpServers || {}).length };
}

/** claudithos: isolated config dir; state seeded ONCE by copy (never shared live). */
export function prepareClaudithosConfig({ home = homedir(), configDir } = {}) {
  const dir = configDir ?? join(home, '.claude-mythos');
  if (!dir.includes('.claude-mythos')) {
    throw new Error(`prepare-config: refuse to write outside .claude-mythos: ${dir}`);
  }
  mkdirSync(dir, { recursive: true });
  linkShared(dir, home);
  const statePath = join(dir, '.claude.json');
  const globalState = join(home, '.claude.json');
  if (existsSync(globalState) && !existsSync(statePath)) {
    copyFileSync(globalState, statePath);
  }
  return { configDir: dir };
}
