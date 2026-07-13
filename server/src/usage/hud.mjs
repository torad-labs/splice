// Usage payload + HUD state persistence (out-of-repo statusline reads these files).
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { appendFile as appendFileAsync, writeFile as writeFileAsync } from 'node:fs/promises';
import { getContextWindowForModel } from '../models/codex-models.mjs';
import { getConfig, stateDir, statePaths } from '../config.mjs';

export function buildUsagePayload(model, usage = {}) {
  const cfg = getConfig();
  const contextWindow = getContextWindowForModel(model, cfg.contextWindowOverride ?? undefined);
  // Accept Anthropic names and OpenAI Responses aliases
  const inputTokens = usage.input_tokens ?? usage.prompt_tokens ?? 0;
  const outputTokens = usage.output_tokens ?? usage.completion_tokens ?? 0;
  const cacheCreationInputTokens = usage.cache_creation_input_tokens ?? 0;
  const cacheReadInputTokens = usage.cache_read_input_tokens
    ?? usage.input_tokens_details?.cached_tokens
    ?? 0;
  const totalInputTokens = inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
  const payload = {
    input_tokens: inputTokens,
    output_tokens: outputTokens,
    cache_creation_input_tokens: cacheCreationInputTokens,
    cache_read_input_tokens: cacheReadInputTokens,
  };
  if (contextWindow) {
    // Claude Code autocompact / HUD read these non-standard fields from custom gateways
    payload.context_window = contextWindow;
    payload.context_window_size = contextWindow;
    payload.used_percentage = contextWindow > 0 ? (totalInputTokens / contextWindow) * 100 : 0;
  }
  return payload;
}

const FIVE_HOURS_MS = 5 * 60 * 60 * 1000;

export function appendCodexUsage(outputTokens) {
  if (!outputTokens) return;
  // Fire-and-forget — never block the stream path on disk I/O
  queueMicrotask(async () => {
    try {
      mkdirSync(stateDir(), { recursive: true });
      const path = statePaths.usage();
      let entries = [];
      if (existsSync(path)) {
        try { entries = JSON.parse(readFileSync(path, 'utf8')); } catch { entries = []; }
      }
      if (!Array.isArray(entries)) entries = [];
      const cutoff = Date.now() - FIVE_HOURS_MS;
      entries = entries.filter((e) => e.timestamp > cutoff);
      entries.push({ timestamp: Date.now(), output_tokens: outputTokens });
      await writeFileAsync(path, JSON.stringify(entries));
    } catch { /* ignore */ }
  });
}

// Capture OpenAI rate-limit headers and persist for the HUD statusline.
// Headers: x-ratelimit-limit-tokens, x-ratelimit-remaining-tokens, x-ratelimit-reset-tokens
export function persistCodexRateLimit(headers) {
  try {
    const limitTokens = parseInt(headers.get('x-ratelimit-limit-tokens') ?? '', 10);
    const remainingTokens = parseInt(headers.get('x-ratelimit-remaining-tokens') ?? '', 10);
    const resetTokens = headers.get('x-ratelimit-reset-tokens') ?? ''; // e.g. "6m0s", "1ms"
    if (!Number.isFinite(limitTokens)) return;
    mkdirSync(stateDir(), { recursive: true });
    const payload = JSON.stringify({
      limit_tokens: limitTokens,
      remaining_tokens: Number.isFinite(remainingTokens) ? remainingTokens : null,
      reset_tokens: resetTokens || null,
      updated_at: Date.now(),
    }) + '\n';
    writeFileAsync(statePaths.ratelimit(), payload).catch(() => {});
  } catch { /* ignore */ }
}

/** Clamp REPORTED output_tokens to the client's max_tokens (v26). The ChatGPT
 * backend rejects token-limit params so generation is uncapped, and reasoning
 * tokens count in output_tokens — an undetected max-effort compaction reports
 * far more output than the delivered message contains, tripping Claude Code's
 * "response exceeded N output token maximum" guard. The delivered content is
 * bounded by max_tokens anyway; reasoning tokens are not part of the message. */
export function makeOutputClamp(clientMaxTokens, compactMode) {
  const max = Number.isFinite(clientMaxTokens) && clientMaxTokens > 0 ? clientMaxTokens : null;
  return (n) => {
    if (max && n > max) {
      process.stderr.write(
        `[codex-proxy] output_tokens ${n} > client max_tokens ${max} compact=${compactMode} — clamping reported usage\n`,
      );
      return max;
    }
    return n;
  };
}

/** Read persisted usage + ratelimit state for /mgmt/usage. */
export function readUsageState() {
  let entries = [];
  let ratelimit = null;
  try {
    if (existsSync(statePaths.usage())) entries = JSON.parse(readFileSync(statePaths.usage(), 'utf8'));
  } catch { entries = []; }
  if (!Array.isArray(entries)) entries = [];
  try {
    if (existsSync(statePaths.ratelimit())) ratelimit = JSON.parse(readFileSync(statePaths.ratelimit(), 'utf8'));
  } catch { ratelimit = null; }
  const cutoff = Date.now() - FIVE_HOURS_MS;
  const window = entries.filter((e) => e && e.timestamp > cutoff);
  return {
    window_hours: 5,
    entries: window.length,
    output_tokens_5h: window.reduce((s, e) => s + (e.output_tokens || 0), 0),
    ratelimit,
  };
}
