// NEW: V4-444 — probe progress and retained observation time shared by quota surfaces.
export const Q = {
  checking: 'Checking provider limits…',
  failed: (reason: string) => `Could not refresh provider limits. ${reason}`,
  observed: (at: string | null) => at === null ? 'Observation time not reported' : `Observed ${at}`,
} as const;
