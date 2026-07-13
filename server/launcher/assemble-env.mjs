// Launch-time environment assembly: section-aware TOML read (replacing the
// shim's sed, which grabbed the first `key = value` match ANYWHERE — including
// inside [profile] sections it should never read), models_cache lookup,
// context-window resolution, and the proxy/child env maps.
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

// ── TOML (minimal, section-aware) ────────────────────────────────────────────

/**
 * Read a string value for `key` from a TOML file, honoring sections: only keys
 * in the requested section match ('' = the root table before any [section]).
 * Handles quoted and bare values and trailing comments; arrays/tables are out
 * of scope (none of the codex knobs use them).
 */
export function readTomlKey(tomlText, key, section = '') {
  let current = '';
  for (const rawLine of String(tomlText ?? '').split('\n')) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const sec = /^\[\[?([^\]]+)\]?\]$/.exec(line);
    if (sec) {
      current = sec[1].trim();
      continue;
    }
    if (current !== section) continue;
    const kv = /^([A-Za-z0-9_.-]+)\s*=\s*(.+)$/.exec(line);
    if (!kv || kv[1] !== key) continue;
    let value = kv[2].trim();
    const quoted = /^"((?:[^"\\]|\\.)*)"/.exec(value) || /^'([^']*)'/.exec(value);
    if (quoted) return quoted[1];
    value = value.split('#')[0].trim(); // strip trailing comment on bare values
    return value;
  }
  return '';
}

export function readCodexToml(key, section = '', tomlPath = join(homedir(), '.codex', 'config.toml')) {
  try {
    if (!existsSync(tomlPath)) return '';
    return readTomlKey(readFileSync(tomlPath, 'utf8'), key, section);
  } catch {
    return '';
  }
}

// ── models_cache.json ────────────────────────────────────────────────────────

export function contextWindowFromModelsCache(model, cachePath = join(homedir(), '.codex', 'models_cache.json')) {
  try {
    if (!existsSync(cachePath)) return null;
    const d = JSON.parse(readFileSync(cachePath, 'utf8'));
    const models = d.models || [];
    const m = models.find((x) => (x.slug || x.id || '') === model)
      || models.find((x) => String(x.slug || x.id || '').includes(model));
    const win = m && (m.context_window || m.contextWindow);
    return Number.isFinite(win) && win > 0 ? win : null;
  } catch {
    return null;
  }
}

// ── context window resolution ────────────────────────────────────────────────

/**
 * Resolve the context window reported to Claude Code. Order: explicit env →
 * models_cache → config.toml model_context_window → 272000; capped at
 * CLAUDEX_CONTEXT_CEILING (default 272000).
 *
 * The REAL window is reported deliberately (v29 CHANGELOG): a lower window
 * fires autocompact more often, big tool-result reads refill within 3 turns,
 * Claude Code's thrash guard disables autocompact, the session grows unbounded
 * → 502. Real 272k fires at ~231k with ~4 turns of headroom.
 */
export function resolveContextWindow({ model, env = process.env, tomlPath, cachePath } = {}) {
  let win = parseInt(env.CLAUDEX_CONTEXT_WINDOW || env.CODEX_MODEL_CONTEXT_WINDOW || '', 10);
  if (!Number.isFinite(win) || win <= 0) win = contextWindowFromModelsCache(model, cachePath) ?? NaN;
  if (!Number.isFinite(win) || win <= 0) {
    win = parseInt(readCodexToml('model_context_window', '', tomlPath) || '', 10);
  }
  if (!Number.isFinite(win) || win <= 0) win = 272_000;
  const ceiling = parseInt(env.CLAUDEX_CONTEXT_CEILING || '', 10);
  const cap = Number.isFinite(ceiling) && ceiling > 0 ? ceiling : 272_000;
  return Math.min(win, cap);
}

export function resolveReasoning({ env = process.env, tomlPath } = {}) {
  const effort = env.CLAUDEX_REASONING_EFFORT || env.CODEX_REASONING_EFFORT
    || readCodexToml('model_reasoning_effort', '', tomlPath) || 'high';
  const summary = env.CLAUDEX_REASONING_SUMMARY || env.CODEX_REASONING_SUMMARY
    || readCodexToml('model_reasoning_summary', '', tomlPath) || 'detailed';
  return { effort, summary };
}

// ── env maps ─────────────────────────────────────────────────────────────────

export const AUTO_COMPACT_WINDOW_FLOOR = 100_000;

/**
 * The claudex launch profile: everything the proxy process and the Claude Code
 * child need. The child map carries the autocompact un-gate:
 * CLAUDE_CODE_AUTO_COMPACT_WINDOW is the ONLY env that makes Claude Code run
 * autocompact for a model absent from its hardcoded context tables (binary
 * trace, v2.1.207) — CLAUDE_CODE_MAX_CONTEXT_TOKENS does not, and is kept only
 * for unwrapped gpt-* names.
 */
export function assembleClaudexEnv({ env = process.env, tomlPath, cachePath } = {}) {
  const model = env.CLAUDEX_MODEL || 'gpt-5.6-sol';
  const port = env.CODEX_PROXY_PORT || '3099';
  const { effort, summary } = resolveReasoning({ env, tomlPath });
  const showReasoning = env.CLAUDEX_SHOW_REASONING || 'text';
  const contextWindow = resolveContextWindow({ model, env, tomlPath, cachePath });
  const shared = {
    CLAUDEX_REASONING_EFFORT: effort,
    CLAUDEX_REASONING_SUMMARY: summary,
    CLAUDEX_SHOW_REASONING: showReasoning,
    CLAUDEX_UPSTREAM_RETRIES: env.CLAUDEX_UPSTREAM_RETRIES || '2',
    CLAUDEX_MAX_INFLIGHT: env.CLAUDEX_MAX_INFLIGHT || '0',
    CLAUDEX_DEBUG: env.CLAUDEX_DEBUG || '0',
    CODEX_MODEL_CONTEXT_WINDOW: String(contextWindow),
  };
  return {
    model,
    port: parseInt(port, 10),
    contextWindow,
    proxyEnv: {
      ...shared,
      CODEX_PROXY_PORT: port,
      CLAUDEX_PINNED_MODEL: model,
    },
    childUnset: [
      'ANTHROPIC_BASE_URL', 'ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN',
      'CLAUDE_CODE_OAUTH_TOKEN', 'CLAUDE_CODE_OAUTH_REFRESH_TOKEN',
    ],
    childEnv: {
      ...shared,
      ANTHROPIC_BASE_URL: `http://127.0.0.1:${port}`,
      ANTHROPIC_AUTH_TOKEN: 'codex-local',
      ANTHROPIC_MODEL: model,
      ANTHROPIC_DEFAULT_SONNET_MODEL: model,
      ANTHROPIC_DEFAULT_OPUS_MODEL: model,
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'gpt-5.4-mini',
      ANTHROPIC_CUSTOM_MODEL_OPTION: model,
      ANTHROPIC_CUSTOM_MODEL_OPTION_NAME: `Codex ${model}`,
      ANTHROPIC_CUSTOM_MODEL_OPTION_DESCRIPTION: 'ChatGPT Codex via local proxy',
      CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY: '1',
      // Still honored for unwrapped gpt-* names; ignored for claude-* wrapped ids.
      CLAUDE_CODE_MAX_CONTEXT_TOKENS: String(contextWindow),
      // THE autocompact un-gate (fix #1) — floored so a tiny/typo'd window can
      // never wedge autocompact into firing constantly.
      CLAUDE_CODE_AUTO_COMPACT_WINDOW: String(Math.max(AUTO_COMPACT_WINDOW_FLOOR, contextWindow)),
      // Fires at ~231k of the real 272k — min'd against window−13k by Claude
      // Code itself, so 85% stays meaningful and above the thrash guard.
      CLAUDE_AUTOCOMPACT_PCT_OVERRIDE: env.CLAUDE_AUTOCOMPACT_PCT_OVERRIDE || env.CLAUDEX_AUTOCOMPACT_PCT || '85',
      MAX_THINKING_TOKENS: env.MAX_THINKING_TOKENS || '128000',
      CLAUDE_CONFIG_DIR: env.CLAUDE_CONFIG_DIR_CODEX || join(homedir(), '.claude-codex'),
      NO_PROXY: '127.0.0.1,localhost',
      CLAUDEX: '1',
    },
  };
}

export function assembleClaudithosEnv({ env = process.env } = {}) {
  const port = env.CLAUDITHOS_PORT || '3098';
  const mode = ['native', 'amnesia', 'mirror'].includes(String(env.CLAUDITHOS_MODE || '').toLowerCase())
    ? String(env.CLAUDITHOS_MODE).toLowerCase()
    : 'mirror';
  return {
    mode,
    port: parseInt(port, 10),
    proxyEnv: {
      CLAUDITHOS_PORT: port,
      CLAUDITHOS_MODE: mode,
    },
    childUnset: [
      'ANTHROPIC_BASE_URL', 'ANTHROPIC_API_KEY', 'ANTHROPIC_AUTH_TOKEN',
      'CLAUDE_CODE_OAUTH_TOKEN', 'CLAUDE_CODE_OAUTH_REFRESH_TOKEN',
    ],
    childEnv: {
      ANTHROPIC_BASE_URL: `http://127.0.0.1:${port}`,
      ANTHROPIC_AUTH_TOKEN: 'mythos-local',
      CLAUDE_CONFIG_DIR: env.CLAUDE_CONFIG_DIR_MYTHOS || join(homedir(), '.claude-mythos'),
      NO_PROXY: '127.0.0.1,localhost',
      CLAUDITHOS: '1',
      CLAUDITHOS_MODE: mode,
    },
  };
}

// ── claude arg policy (shared by both heads) ─────────────────────────────────

export function buildClaudeArgs(userArgs, { defaultModel = null, safeEnvVar = 'CLAUDEX_SAFE', env = process.env } = {}) {
  const args = [...userArgs];
  if (env[safeEnvVar] !== '1' && !args.includes('--dangerously-skip-permissions')) {
    args.unshift('--dangerously-skip-permissions');
  }
  if (defaultModel) {
    const hasModel = args.some((a) => a === '--model' || a === '-m' || a.startsWith('--model=') || a.startsWith('-m='));
    if (!hasModel) args.unshift('--model', defaultModel);
  }
  return args;
}
