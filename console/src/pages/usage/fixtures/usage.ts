// Sample data for the design capture, under the fixture rule (CONTRACTS.md section 4): it loads
// only when `import.meta.env.DEV` is true and the address carries `?fixture=<name>` in the hash
// query, the shipped dist carries none of it, and the page prints a grey `sample data` holder edge
// while it is shown.
//
// The series is GENERATED rather than pasted so every window is real: a 7d view of a sample that
// held only 24 buckets would draw six days of idle gaps and teach the reviewer the wrong shape.
// The pattern is a working day — quiet at night, a morning ramp, a long afternoon plateau — which is
// what makes the stacked bars legible as a day rather than as noise.
import type { EconomicsBucket, EconomicsPayload } from '@shared/api';
import type { ModelsPayload } from '@entities/model';

/** A fixed clock: the fixture is a still, so a capture and a test see the same window. */
export const FIXTURE_NOW = 1_787_400_000_000;

const HOUR_MS = 3_600_000;
const HOURS = 168;


function bucketAt(hour: number, index: number, scale: number, ceilingShare: number): EconomicsBucket {
  const at = new Date(hour).getUTCHours();
  // Quiet between midnight and 06:00 UTC, ramping to a plateau through the working hours.
  const shape = at < 6 ? 0.06 : at < 9 ? 0.35 + (at - 6) * 0.15 : at < 20 ? 1 : 0.4;
  const jitter = 0.85 + ((index * 37) % 30) / 100;
  const inTokens = Math.round(4_200_000 * shape * jitter * scale);
  const cached = Math.round(inTokens * 0.82);
  const write = Math.round(inTokens * 0.05);
  const turns = Math.round(18 * shape * jitter * scale);
  return {
    hour,
    turns,
    in_tokens: inTokens,
    cached_tokens: cached,
    cache_write_tokens: write,
    out_tokens: Math.round(inTokens * ceilingShare),
    req_bytes: Math.round(inTokens * 3.4),
    upstream_req_bytes: Math.round(inTokens * 2.7),
    tools_eager: turns * 9,
    tools_deferred: turns * 31,
    deferral_turns: turns,
    rate_limited: shape > 0.9 && index % 11 === 0 ? 3 : 0,
  };
}

function bucketsFor(scale: number, outputShare: number): EconomicsBucket[] {
  const end = Math.floor(FIXTURE_NOW / HOUR_MS) * HOUR_MS;
  return Array.from({ length: HOURS }, (_, index) =>
    bucketAt(end - (HOURS - 1 - index) * HOUR_MS, index, scale, outputShare),
  );
}

export const fixtureEconomics: EconomicsPayload = {
  retention_hours: 720,
  generated_at: FIXTURE_NOW,
  heads: [
    { key: 'claudex', label: 'claudex', ceiling_tokens: 420_000_000, buckets: bucketsFor(1, 0.011) },
    { key: 'claude-splice', label: 'claude-splice', ceiling_tokens: 180_000_000, buckets: bucketsFor(0.55, 0.014) },
    { key: 'claude-deepseek', label: 'claude-deepseek', ceiling_tokens: null, buckets: bucketsFor(0.3, 0.02) },
  ],
};

export const fixtureModels: ModelsPayload = {
  heads: [
    {
      head: 'claudex',
      provider: 'chatgpt-oauth',
      pinned_model: 'gpt-5.6-sol',
      models: [
        {
          id: 'gpt-5.6-sol', label: 'sol', description: 'frontier reasoning',
          slot: 'opus', context_window: 400_000, context_window_source: 'head declaration',
          rates: { input: 1.25, cache_read: 0.125, cache_write: 1.5, output: 10 }, pinned: true, resolved: true,
        },
        {
          id: 'gpt-5.6-luna', label: 'luna', description: 'fast builder',
          slot: 'sonnet', context_window: 272_000, context_window_source: 'provider model entry',
          rates: { input: 0.4, cache_read: 0.04, cache_write: 0.5, output: 3.2 }, pinned: false, resolved: true,
        },
        {
          id: 'gpt-5.5', label: 'terra', description: 'long-context reviewer',
          slot: 'haiku', context_window: 200_000, context_window_source: 'prefix rule 5.5*',
          rates: null, pinned: false, resolved: true,
        },
      ],
    },
    {
      head: 'claude-deepseek',
      provider: 'api-key',
      pinned_model: 'deepseek-flash',
      models: [
        {
          id: 'deepseek-flash', label: 'flash', description: 'cheap builder',
          slot: 'sonnet', context_window: 128_000, context_window_source: 'provider default',
          rates: { input: 0.14, cache_read: 0.014, output: 0.28 }, pinned: true, resolved: true,
        },
      ],
    },
  ],
};
