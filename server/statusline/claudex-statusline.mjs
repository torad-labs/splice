#!/usr/bin/env node
// claudex statusline: renders the bottom bar for the codex/grok heads. Claude Code
// pipes a JSON session blob to stdin on every tick; we print one ANSI line to stdout.
//
// Everything shown comes straight from stdin — no state files, no network, no API
// tokens. The token counts and cache-read numbers are the ones our proxy injects
// into the usage payload (buildUsagePayload), so `used/window` reflects the real
// Codex/Grok context window (272k/500k/1M), not Claude's 200k, and the cache % is
// the same prompt-cache hit rate the proxy logs per turn.
//
// Boringly defensive by contract: any missing field degrades to a shorter line and
// a parse/throw prints a minimal fallback rather than crashing the bar.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { computeUsageWarn } from '../src/usage/warn.mjs';

const C = {
  reset: '\x1b[0m', bold: '\x1b[1m', dim: '\x1b[2m',
  cyan: '\x1b[36m', green: '\x1b[32m', yellow: '\x1b[33m', red: '\x1b[31m', mag: '\x1b[35m',
};

/** 8500 -> 8.5k, 128000 -> 128k, 1000000 -> 1M. */
function fmt(n) {
  n = Number(n) || 0;
  if (n >= 1e6) return (n / 1e6).toFixed(n >= 1e7 ? 0 : 1).replace(/\.0$/, '') + 'M';
  if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'k';
  return String(Math.round(n));
}

/** Actual tokens live in the context window this turn. current_usage.* is correct on
 * every Claude Code version (0 at session start, never cumulative); total_input_tokens
 * is the fallback for pre-2.1.132. */
function usedTokens(cw) {
  const cu = cw.current_usage;
  if (cu) {
    return (cu.input_tokens || 0) + (cu.cache_read_input_tokens || 0) + (cu.cache_creation_input_tokens || 0);
  }
  return cw.total_input_tokens || 0;
}

/** Cache-read share of this turn's input — the prompt-cache hit rate. null before the
 * first API call (no current_usage yet) so the segment can hide. */
function cacheHitPct(cw) {
  const cu = cw.current_usage;
  if (!cu) return null;
  const total = (cu.input_tokens || 0) + (cu.cache_read_input_tokens || 0) + (cu.cache_creation_input_tokens || 0);
  if (total <= 0) return null;
  return Math.round(((cu.cache_read_input_tokens || 0) / total) * 100);
}

function gitBranch(cwd) {
  if (!cwd) return '';
  try {
    return execFileSync('git', ['-C', cwd, 'branch', '--show-current'], {
      encoding: 'utf8', timeout: 200, stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
  } catch { return ''; }
}

const STATE_DIR = process.env.CLAUDEX_STATE_DIR || join(homedir(), '.claude-codex', 'state');

function readStateJson(name) {
  try { return JSON.parse(readFileSync(join(STATE_DIR, name), 'utf8')); } catch { return null; }
}

/** Subtle soft-warn hint — empty until usage crosses the configured threshold, so
 * the bar shows no limit at rest (only a ⚠ when you're near the cap). Reads the
 * state the proxy persists + computeUsageWarn (shared with the dashboard). */
function usageWarnGlyph() {
  const cfg = readStateJson('config.json') || {};
  const ratelimit = readStateJson('codex-ratelimit.json');
  const usage = readStateJson('codex-usage.json');
  let outputTokens5h = 0;
  if (Array.isArray(usage)) {
    const cutoff = Date.now() - 5 * 60 * 60 * 1000;
    outputTokens5h = usage.filter((e) => e && e.timestamp > cutoff).reduce((s, e) => s + (e.output_tokens || 0), 0);
  }
  const w = computeUsageWarn({
    outputTokens5h,
    ratelimit,
    warnPct: Number(cfg.usageWarnPct) || 80,
    warnTokens5h: Number(cfg.usageWarnTokens5h) || 0,
  });
  if (w.level === 'ok') return '';
  const color = w.level === 'critical' ? C.red : C.yellow;
  return `${color}⚠ ${w.pct}%${C.reset}`;
}

function render(d) {
  const parts = [];

  // Model — accent dot + display name.
  const model = d.model?.display_name || d.model?.id || 'model';
  parts.push(`${C.bold}${C.cyan}●${C.reset} ${C.bold}${model}${C.reset}`);

  // Actual context tokens used / window, colored by how close compaction is.
  const cw = d.context_window || {};
  const size = cw.context_window_size || 0;
  const used = usedTokens(cw);
  const pct = typeof cw.used_percentage === 'number'
    ? Math.round(cw.used_percentage)
    : (size > 0 ? Math.round((used / size) * 100) : 0);
  const pctColor = pct >= 85 ? C.red : pct >= 60 ? C.yellow : C.green;
  const window = size > 0 ? `${fmt(used)}/${fmt(size)}` : fmt(used);
  parts.push(`${window} ${C.dim}·${C.reset} ${pctColor}${pct}%${C.reset}`);

  // Cache hit — the claudex bit. Hidden until there's a turn to measure.
  const hit = cacheHitPct(cw);
  if (hit != null) {
    const cColor = hit >= 70 ? C.green : hit >= 40 ? C.yellow : C.dim;
    parts.push(`${cColor}⚡ ${hit}%${C.reset}`);
  }

  // Soft-warn hint (subscription headroom) — only present when near/over the cap.
  const warn = usageWarnGlyph();
  if (warn) parts.push(warn);

  // Location — repo basename + branch, dim.
  const cwd = d.workspace?.current_dir || d.cwd || '';
  const base = cwd ? cwd.split('/').filter(Boolean).pop() : '';
  const branch = gitBranch(cwd);
  const loc = [base, branch ? `⎇ ${branch}` : ''].filter(Boolean).join('  ');
  if (loc) parts.push(`${C.dim}${loc}${C.reset}`);

  return parts.join(`${C.dim}   ${C.reset}`);
}

let raw = '';
try {
  process.stdin.setEncoding('utf8');
  for await (const chunk of process.stdin) raw += chunk;
} catch { /* no stdin */ }

try {
  const data = raw.trim() ? JSON.parse(raw) : {};
  process.stdout.write(render(data));
} catch (err) {
  // Never crash the bar — a plain marker beats a blank/error line.
  process.stdout.write(`${C.dim}claudex${C.reset}`);
  process.stderr.write(`[claudex-statusline] ${err?.message || err}\n`);
}
