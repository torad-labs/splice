// Grok (xAI) model catalog + context windows + gateway-discovery wrap/unwrap.
// Mirrors codex-models.mjs; the grok head is the codex head adapted for xAI.
//
// xAI exposes one production coding model, grok-4.5 (500k context), plus two
// aliases. reasoning.effort tops out at `high` (no xhigh/max) and cannot be
// disabled — the translate-request clamp enforces that.
// Windows verified against the live models.dev registry (what xAI/opencode
// resolve at runtime, 2026-07-15). grok-build is 256k, NOT 500k — a wrong window
// misfires autocompact, worse here than for codex given the 500k/1M spread.
export const GROK_MODEL_OPTIONS = [
  { value: 'grok-4.5', label: 'Grok 4.5', description: 'xAI Grok 4.5 — 500k context, high reasoning (newest)', context_window: 500_000 },
  { value: 'grok-4.3', label: 'Grok 4.3', description: 'xAI Grok 4.3 — 1M context', context_window: 1_000_000 },
  { value: 'grok-build-latest', label: 'Grok Build', description: 'xAI coding model — 256k, fixed reasoning', context_window: 256_000 },
];

export const DEFAULT_GROK_MODEL = 'grok-4.5';
export const DEFAULT_GROK_CONTEXT_WINDOW = 500_000;

export const GROK_MODEL_CONTEXT_WINDOWS = {
  'grok-4.5': 500_000,
  'grok-4.3': 1_000_000,
  'grok-build-latest': 256_000,
  'grok-build-0.1': 256_000,
};

// Ordered, most-specific first; startsWith only (no substring fuzz — the v29 bug).
const CONTEXT_WINDOW_PREFIX_RULES = [
  ['grok-4.5', 500_000],
  ['grok-4.3', 1_000_000],
  ['grok-build', 256_000],
  ['grok-4', 500_000],
];

// Claude Code drops any /v1/models id that doesn't match /^(claude|anthropic)/i,
// so grok ids are wrapped for discovery (grok-4.5 -> claude-grok--grok-4.5) and
// unwrapped before xAI sees them. Distinct from claude-codex-- so the two heads
// never cross-match.
export const DISCOVERY_PREFIX = 'claude-grok--';

export function wrapGrokModel(value) {
  return DISCOVERY_PREFIX + String(value || '');
}

export function unwrapGrokModel(model) {
  const m = String(model || '');
  return m.startsWith(DISCOVERY_PREFIX) ? m.slice(DISCOVERY_PREFIX.length) : m;
}

/** Strip the discovery wrapper and the [1m] context hint before xAI sees the id. */
export function stripModelSuffixes(model) {
  return unwrapGrokModel(String(model || '')).replace(/\[1m\]$/i, '');
}

export function getGrokContextWindow(model, defaultWindow = DEFAULT_GROK_CONTEXT_WINDOW) {
  const fallback = defaultWindow > 0 ? defaultWindow : DEFAULT_GROK_CONTEXT_WINDOW;
  if (!model) return fallback;
  const id = stripModelSuffixes(model);
  if (GROK_MODEL_CONTEXT_WINDOWS[id] != null) return GROK_MODEL_CONTEXT_WINDOWS[id];
  for (const [prefix, win] of CONTEXT_WINDOW_PREFIX_RULES) {
    if (id.startsWith(prefix)) return win;
  }
  return fallback;
}

/** /v1/models payload — every model wrapped, each with a display_name label. */
export function discoveryModels() {
  return GROK_MODEL_OPTIONS.map((m) => ({ id: DISCOVERY_PREFIX + m.value, display_name: m.label, type: 'model', created: 0 }));
}

/** UNWRAPPED ids for the settings.json availableModels allowlist (hides the
 * built-in Claude rows). Unwrapped is load-bearing: a claude-* wrapped active
 * model makes Claude Code ignore the context window and compact absurdly early —
 * even worse at grok's 500k than at codex's 272k. */
export function availableModelIds() {
  return GROK_MODEL_OPTIONS.map((m) => m.value);
}

export function getGrokModelLabel(value) {
  return GROK_MODEL_OPTIONS.find((option) => option.value === value)?.label ?? value;
}
