// NEW: V4-444 — probe progress and retained observation time shared by quota surfaces.
export const Q = {
  checking: 'Checking provider limits…',
  failed: (reason: string) => `Could not refresh provider limits. ${reason}`,
  observed: (at: string | null) => at === null ? 'Observation time not reported' : `Observed ${at}`,
  window: (name: string, pct: number, reset: string | null, current: boolean) => `${name} · ${current ? '' : 'Last reading '}${Math.round(pct)}% · ${reset === null ? 'Reset not reported' : `resets ${reset}`}${current ? '' : ' · Not current'}`,
} as const;
