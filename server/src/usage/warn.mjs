// Pure soft-warn evaluation — ZERO imports so the statusline can pull it without
// dragging in the config/model graph. One logic source, shared by the statusline
// hint and the control-server usage API (never forked).
//
// Soft-warn NEVER blocks — it only classifies headroom. Rate-limit remaining is
// the real subscription signal; the 5h output-token count is the fallback when
// the backend sends no x-ratelimit-* headers (warnTokens5h = 0 disables it).
export function computeUsageWarn({ outputTokens5h = 0, ratelimit = null, warnPct = 80, warnTokens5h = 0 } = {}) {
  const pctThreshold = warnPct > 0 ? warnPct : 80;
  const limit = Number(ratelimit?.limit_tokens);
  const remaining = Number(ratelimit?.remaining_tokens);

  if (Number.isFinite(limit) && limit > 0 && Number.isFinite(remaining)) {
    const usedPct = Math.min(100, Math.max(0, (1 - remaining / limit) * 100));
    let level = 'ok';
    if (remaining <= 0 || usedPct >= 98) level = 'critical';
    else if (usedPct >= pctThreshold) level = 'warn';
    return { level, pct: Math.round(usedPct), source: 'ratelimit', reset: ratelimit?.reset_tokens ?? null };
  }

  if (warnTokens5h > 0) {
    const usedPct = Math.min(100, Math.round((outputTokens5h / warnTokens5h) * 100));
    let level = 'ok';
    if (outputTokens5h >= warnTokens5h) level = 'critical';
    else if (usedPct >= pctThreshold) level = 'warn';
    return { level, pct: usedPct, source: 'tokens5h', reset: null };
  }

  return { level: 'ok', pct: 0, source: 'none', reset: null };
}
