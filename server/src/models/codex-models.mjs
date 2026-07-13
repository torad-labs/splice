// Codex model catalog + context windows + gateway-discovery wrap/unwrap.
//
// Context windows resolve by EXACT match, then an EXPLICIT ordered prefix list,
// then the default — the v29 fuzzy `id.includes(key)` pass and the catch-all
// regex are gone (P2: an unknown id silently inheriting a window hid catalog
// gaps; now unknown families fall to the default deliberately and visibly).
export const CODEX_MODEL_OPTIONS = [
  { value: 'gpt-5.6-sol', label: 'Codex gpt-5.6 Sol', description: 'Codex 5.6 Sol — primary high-reasoning', context_window: 272_000 },
  { value: 'gpt-5.6-luna', label: 'Codex gpt-5.6 Luna', description: 'Codex 5.6 Luna', context_window: 272_000 },
  { value: 'gpt-5.6-terra', label: 'Codex gpt-5.6 Terra', description: 'Codex 5.6 Terra', context_window: 272_000 },
  { value: 'gpt-5.6', label: 'Codex gpt-5.6', description: 'Codex 5.6 base id (if exposed)', context_window: 272_000 },
  { value: 'gpt-5.5', label: 'Codex gpt-5.5', description: 'Codex 5.5', context_window: 272_000 },
  { value: 'gpt-5.4', label: 'Codex gpt-5.4', description: 'Codex 5.4 balanced', context_window: 272_000 },
  { value: 'gpt-5.3-codex-spark', label: 'Codex gpt-5.3-codex-spark', description: 'Codex spark', context_window: 128_000 },
  { value: 'gpt-5.4-mini', label: 'Codex gpt-5.4-mini', description: 'Codex fast', context_window: 272_000 },
  { value: 'gpt-5.3-mini', label: 'Codex gpt-5.3-mini', description: 'Codex mini', context_window: 272_000 },
];

export const DEFAULT_CODEX_MODEL = CODEX_MODEL_OPTIONS[0].value; // gpt-5.6-sol
export const DEFAULT_CODEX_CONTEXT_WINDOW = 272_000;

export const CODEX_MODEL_CONTEXT_WINDOWS = {
  'gpt-5.6-sol': 272_000,
  'gpt-5.6-luna': 272_000,
  'gpt-5.6-terra': 272_000,
  'gpt-5.6': 272_000,
  'gpt-5.5': 272_000,
  'gpt-5.4': 272_000,
  'gpt-5.4-mini': 272_000,
  'gpt-5.3-mini': 272_000,
  'gpt-5.3-codex-spark': 128_000,
  // Legacy / optimistic 1M ids if backend grants them
  'gpt-5.4-1m': 1_000_000,
  'gpt-5.5-1m': 1_000_000,
};

// Ordered, most-specific first. startsWith only — never substring.
const CONTEXT_WINDOW_PREFIX_RULES = [
  ['gpt-5.3-codex-spark', 128_000],
  ['gpt-5.6', 272_000],
  ['gpt-5.5', 272_000],
  ['gpt-5.4', 272_000],
  ['gpt-5.3', 272_000],
];

// Gateway model discovery (Claude Code >=2.1.129) is the ONLY durable way to list
// N custom models in the /model picker. Claude Code drops any /v1/models id that
// doesn't match /^(claude|anthropic)/i, so codex ids are wrapped for discovery
// (gpt-5.6-luna -> claude-codex--gpt-5.6-luna) and unwrapped on the way in.
export const DISCOVERY_PREFIX = 'claude-codex--';

export function unwrapCodexModel(model) {
  const m = String(model || '');
  return m.startsWith(DISCOVERY_PREFIX) ? m.slice(DISCOVERY_PREFIX.length) : m;
}

/** Strip the discovery wrapper and the [1m] context hint before Codex sees the id. */
export function stripModelSuffixes(model) {
  return unwrapCodexModel(String(model || '')).replace(/\[1m\]$/i, '');
}

export function getContextWindowForModel(model, defaultWindow = DEFAULT_CODEX_CONTEXT_WINDOW) {
  const fallback = defaultWindow > 0 ? defaultWindow : DEFAULT_CODEX_CONTEXT_WINDOW;
  if (!model) return fallback;
  const id = stripModelSuffixes(model);
  if (CODEX_MODEL_CONTEXT_WINDOWS[id] != null) return CODEX_MODEL_CONTEXT_WINDOWS[id];
  for (const [prefix, win] of CONTEXT_WINDOW_PREFIX_RULES) {
    if (id.startsWith(prefix)) return win;
  }
  return fallback;
}

/** /v1/models payload — wrapped ids, pinned default excluded (it is already
 * shown via ANTHROPIC_CUSTOM_MODEL_OPTION; discovery would duplicate it). */
export function discoveryModels(pinnedModel) {
  return CODEX_MODEL_OPTIONS
    .filter((m) => m.value !== pinnedModel)
    .map((m) => ({ id: DISCOVERY_PREFIX + m.value, display_name: m.label, type: 'model', created: 0 }));
}

export function getCodexModelLabel(value) {
  return CODEX_MODEL_OPTIONS.find((option) => option.value === value)?.label ?? value;
}
