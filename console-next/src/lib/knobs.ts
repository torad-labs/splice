// What each knob is, as data: its group on the rack, the unit its number is measured in, whether the
// daemon forces or scopes it, and the closed set of values it reads. Each entry is written from the
// knob's own entry in Knob.kt and the code that reads it. The words live apart from this, in
// lib/words-knobs.ts: the group labels, the names and the one-line help. tests/knobs.test.ts ties all
// three tables to Knob.kt in both directions, so a knob added to the daemon without an entry fails by
// name. Ported from the old console's knob-form/knobs.ts, without the rack itself.
import { noun } from './format';
import type { GROUP_LABELS } from './words-knobs';

export type KnobGroup = keyof typeof GROUP_LABELS;

/** How a knob's number is measured. `ms`, `bytes` and `chars` also get a readable form beside
 *  the raw value; the rest print as they are, with the unit after them. */
export type KnobUnit = 'ms' | 'bytes' | 'chars' | 'days' | 'percent' | 'tokens' | 'count' | 'port';

export interface KnobMeta {
  group: KnobGroup;
  unit?: KnobUnit;
  /** The daemon forces the value whatever is saved (ConfigCoercion), so the row prints it and
   *  offers no control that would look like it does something. */
  locked?: true;
  /** Set only in a head's [heads.KEY.overrides] (Knob.headOnly): the daemon refuses it in PATCH,
   *  so the global view prints it and a head's view, which writes that table, edits it. */
  headOnly?: true;
  /** The values the daemon reads, when the set is closed: the row is a picker, not a text box.
   *  `''` is "not set", which the daemon fills with its default. Each list is the daemon's own
   *  vocabulary, cited beside it. */
  choices?: readonly string[];
}

export const KNOB_META: Record<string, KnobMeta> = {
  // load limits
  maxInflight: {
    group: 'limits',
    unit: 'count',
  },
  maxQueued: {
    group: 'limits',
    unit: 'count',
  },
  maxRequestBytes: {
    group: 'limits',
    unit: 'bytes',
  },
  requestReadTimeoutMs: {
    group: 'limits',
    unit: 'ms',
  },
  materializationPermits: {
    group: 'limits',
    unit: 'count',
  },

  // retries and timeouts
  upstreamRetries: {
    group: 'retries',
    unit: 'count',
  },
  retryBackoffBaseMs: {
    group: 'retries',
    unit: 'ms',
  },
  retryBackoffCapMs: {
    group: 'retries',
    unit: 'ms',
  },
  retryBackoffJitterPct: {
    group: 'retries',
    unit: 'percent',
  },
  upstreamTimeoutMs: {
    group: 'retries',
    unit: 'ms',
  },
  firstByteTimeoutMs: {
    group: 'retries',
    unit: 'ms',
  },
  streamIdleMs: {
    group: 'retries',
    unit: 'ms',
  },
  stallReanchorMs: {
    group: 'retries',
    unit: 'ms',
  },

  // reasoning
  effort: {
    group: 'reasoning',
    // EffortVocabulary RUNGS (DefaultEffortVocabulary.kt); unset leaves each model its own default.
    choices: ['', 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
  },
  summary: {
    group: 'reasoning',
    // SUMMARY_CANONICAL (ResponsesEffort.kt).
    choices: ['auto', 'concise', 'detailed', 'none'],
  },
  showReasoning: {
    group: 'reasoning',
    // ConfigCoercion.normalizeShowReasoning folds every spelling into these three.
    choices: ['text', 'thinking', 'off'],
  },
  replayReasoning: {
    group: 'reasoning',
  },
  mirrorReasoning: {
    group: 'reasoning',
    locked: true,
  },
  progressLine: {
    group: 'reasoning',
  },
  foldReasoningModels: {
    group: 'reasoning',
  },
  foldMaxContinue: {
    group: 'reasoning',
    unit: 'count',
  },
  foldMarkerText: {
    group: 'reasoning',
  },
  foldMaxTier: {
    group: 'reasoning',
    unit: 'count',
  },

  // models and tools
  contextWindowOverride: {
    group: 'models',
    unit: 'tokens',
  },
  toolSurface: {
    group: 'models',
    // ConfigCoercion.offOrAuto: anything but off reads as auto.
    choices: ['auto', 'off'],
  },

  // usage and budgets
  quotaPoll: {
    group: 'usage',
    // ConfigCoercion.offOrAuto.
    choices: ['auto', 'off'],
  },
  quotaPollIntervalMs: {
    group: 'usage',
    unit: 'ms',
  },
  usageWarnPct: {
    group: 'usage',
    unit: 'percent',
  },
  usageWarnTokens5h: {
    group: 'usage',
    unit: 'tokens',
  },
  budgetDefaultAction: {
    group: 'usage',
    // BudgetStore.WARN / BudgetStore.BLOCK, the only two it stores.
    choices: ['warn', 'block'],
  },

  // shared mcp servers
  mcpMaxServers: {
    group: 'mcp',
    unit: 'count',
  },
  mcpIdleTimeoutMs: {
    group: 'mcp',
    unit: 'ms',
  },
  mcpRequestTimeoutMs: {
    group: 'mcp',
    unit: 'ms',
  },
  mcpInitializeTimeoutMs: {
    group: 'mcp',
    unit: 'ms',
  },
  mcpSlice: {
    group: 'mcp',
  },

  // recording and history
  transcriptView: {
    group: 'records',
  },
  trace: {
    group: 'records',
    headOnly: true,
  },
  traceRetentionDays: {
    group: 'records',
    unit: 'days',
  },
  traceMaxBodyChars: {
    group: 'records',
    unit: 'chars',
  },
  wireTap: {
    group: 'records',
    headOnly: true,
    unit: 'count',
  },
  activityRetentionDays: {
    group: 'records',
    unit: 'days',
  },
  activityStoreHeads: {
    group: 'records',
  },
  messageEdges: {
    group: 'records',
  },
  perfArchiveRetentionDays: {
    group: 'records',
    unit: 'days',
  },
  debug: {
    group: 'records',
  },

  // chatgpt and grok logins
  port: {
    group: 'logins',
    unit: 'port',
  },
  pinnedModel: {
    group: 'logins',
  },
  chatgptApiBase: {
    group: 'logins',
  },
  codexAuthPath: {
    group: 'logins',
  },
  grokPort: {
    group: 'logins',
    unit: 'port',
  },
  grokModel: {
    group: 'logins',
  },
  xaiApiBase: {
    group: 'logins',
  },
  grokAuthPath: {
    group: 'logins',
  },
  authCacheMs: {
    group: 'logins',
    unit: 'ms',
  },

  // daemon
  controlPort: {
    group: 'daemon',
    unit: 'port',
  },
  supervisorUnit: {
    group: 'daemon',
  },
  statuslineGitRoots: {
    group: 'daemon',
  },
};

const MS_PER_S = 1000;
const S_PER_MIN = 60;
const MIN_PER_H = 60;
const KIB = 1024;
const MILLION = 1_000_000;

/** A count with its noun: `1 byte`, `0 bytes`. */
export function plural(n: number, word: string): string {
  return `${n} ${noun(n, word, `${word}s`)}`;
}

/** A figure at one decimal, and none when the decimal is zero: 1.5 stays, 2.04 is `2`. Rounding
 *  first is what keeps a figure just under a unit boundary from printing as `60.0` or `1024.0`. */
function tenths(value: number): string {
  const shown = Math.round(value * 10) / 10;
  return Number.isInteger(shown) ? String(shown) : shown.toFixed(1);
}

/** A millisecond count as a person says it: 90000 is "1 min 30 s", 1800000 is "30 min". */
export function readableMs(ms: number): string {
  if (ms < MS_PER_S) return `${ms} ms`;
  // Under a minute keeps a tenth; a value that rounds up to the minute moves on to it.
  if (Math.round(ms / 100) / 10 < S_PER_MIN) return `${tenths(ms / MS_PER_S)} s`;
  const totalS = Math.round(ms / MS_PER_S);
  const totalMin = Math.floor(totalS / S_PER_MIN);
  const s = totalS - totalMin * S_PER_MIN;
  if (totalMin < MIN_PER_H) return s === 0 ? `${totalMin} min` : `${totalMin} min ${s} s`;
  const h = Math.floor(totalMin / MIN_PER_H);
  const min = totalMin - h * MIN_PER_H;
  return min === 0 ? `${h} h` : `${h} h ${min} min`;
}

/** A byte count in binary units: 8388608 is "8 MiB". */
export function readableBytes(bytes: number): string {
  if (bytes < KIB) return plural(bytes, 'byte');
  const units = ['KiB', 'MiB', 'GiB'];
  let value = bytes / KIB;
  let at = 0;
  while (Math.round(value * 10) / 10 >= KIB && at < units.length - 1) {
    value /= KIB;
    at += 1;
  }
  return `${tenths(value)} ${units[at]}`;
}

/** The unit printed after the raw value in the field, and the readable form beside it when the
 *  raw number is hard to read. Null parts are not printed. */
export function unitText(unit: KnobUnit | undefined, value: number | null): { suffix: string | null; readable: string | null } {
  if (unit === undefined || value === null) return { suffix: null, readable: null };
  switch (unit) {
    case 'ms':
      return { suffix: 'ms', readable: value >= MS_PER_S ? readableMs(value) : null };
    case 'bytes':
      return { suffix: 'bytes', readable: value >= KIB ? readableBytes(value) : null };
    case 'chars':
      return { suffix: 'characters', readable: value >= MILLION ? `${(value / MILLION).toFixed(1)} million` : null };
    case 'days':
      return { suffix: 'days', readable: null };
    case 'percent':
      return { suffix: '%', readable: null };
    case 'tokens':
      return { suffix: 'tokens', readable: null };
    case 'count':
    case 'port':
      return { suffix: null, readable: null };
  }
}
