// Sample data for the design capture, under the fixture rule (CONTRACTS.md section 4): DEV only,
// behind `?fixture=<name>` in the hash query, never in the shipped dist, labelled `sample data`.
//
// It carries PROVIDERS (the optional field the by-provider view needs) so the capture shows that
// view working; a live payload that omits the field groups every head under the honest
// `provider not reported` bay instead.
import type { ModelsPayload } from '@entities/model';

export const FIXTURE_NAME = 'models';

export function fixtureName(search: string, dev: boolean): string | null {
  if (!dev) return null;
  const asked = new URLSearchParams(search).get('fixture');
  return asked === FIXTURE_NAME ? asked : null;
}

export const fixtureCatalog: ModelsPayload = {
  heads: [
    {
      key: 'claudex', label: 'claudex', provider: 'chatgpt-oauth',
      discovery_prefix: 'claude', pinned_model: 'gpt-5.6-sol',
      context_window: 400_000, default_context_window: 200_000,
      models: [
        { id: 'gpt-5.6-sol', label: 'sol', description: 'frontier reasoning, the pinned row', slot: 'opus', context_window: 400_000, context_window_source: 'head declaration', rates: { input: 1.25, cache_read: 0.125, cache_write: 1.5, output: 10 }, pinned: true },
        { id: 'gpt-5.6-luna', label: 'luna', description: 'fast builder for the cheap tier', slot: 'sonnet', context_window: 272_000, context_window_source: 'provider model entry', rates: { input: 0.4, cache_read: 0.04, cache_write: 0.5, output: 3.2 }, pinned: false },
        { id: 'gpt-5.5', label: 'terra', description: 'long-context reviewer', slot: 'haiku', context_window: 200_000, context_window_source: 'prefix rule gpt-5.5', rates: null, pinned: false },
        { id: 'gpt-5.6-mini', label: 'mini', description: 'window-only id the picker does not list', slot: null, context_window: 128_000, context_window_source: 'extra window', rates: null, pinned: false },
      ],
      extra_windows: [{ id: 'gpt-5.6-mini', context_window: 128_000 }],
      window_rules: [{ prefix: 'gpt-5.5', context_window: 200_000 }],
    },
    {
      key: 'claude-deepseek', label: 'claude-deepseek', provider: 'api-key',
      discovery_prefix: 'claude', pinned_model: 'deepseek-flash',
      context_window: null, default_context_window: 128_000,
      models: [
        { id: 'deepseek-flash', label: 'flash', description: 'cheap builder on the local runtime', slot: 'sonnet', context_window: 128_000, context_window_source: 'provider default', rates: { input: 0.14, cache_read: 0.014, output: 0.28 }, pinned: true },
      ],
      extra_windows: [],
      window_rules: [],
    },
    {
      key: 'claude-kimi', label: 'claude-kimi', provider: 'kimi-oauth',
      discovery_prefix: 'claude', pinned_model: 'kimi-k2.5',
      context_window: 262_144, default_context_window: 262_144,
      models: [
        { id: 'kimi-k2.5', label: 'k2.5', description: 'long-context builder', slot: 'opus', context_window: 262_144, context_window_source: 'head declaration', rates: { input: 0.6, cache_read: 0.06, cache_write: 0.75, output: 2.5 }, pinned: true },
      ],
      extra_windows: [],
      window_rules: [],
    },
  ],
};
