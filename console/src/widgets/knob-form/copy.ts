// What each knob does, in the operator's words, and how its number reads.
//
// The daemon sends a knob's value and where it came from; it does not say what the knob is for,
// and a camelCase key with a raw millisecond count answers none of the questions the operator
// opens Settings with. This table is that answer. Each summary is written from the knob's own
// entry in Knob.kt and the code that reads it, not from its name. The labels live in strings.ts
// (the label wall reads them there); this file holds the sentences, the group and the unit.
// tests/knob-copy.test.ts ties both tables to Knob.kt in both directions, so a knob added to the
// daemon without a sentence here fails by name.
import type { GROUP_LABELS } from './strings';

export type KnobGroup = keyof typeof GROUP_LABELS;

/** How a knob's number is measured. `ms`, `bytes` and `chars` also get a readable form beside
 *  the raw value; the rest print as they are, with the unit after them. */
export type KnobUnit = 'ms' | 'bytes' | 'chars' | 'days' | 'percent' | 'tokens' | 'count' | 'port';

export interface KnobCopy {
  group: KnobGroup;
  summary: string;
  unit?: KnobUnit;
  /** The daemon forces the value whatever is saved (ConfigCoercion), so the row prints it and
   *  offers no control that would look like it does something. */
  locked?: true;
  /** Set only in a head's [heads.KEY.overrides] (Knob.headOnly): the daemon refuses it in PATCH,
   *  so the global view prints it and a head's view, which writes that table, edits it. */
  headOnly?: true;
}

export const KNOB_COPY: Record<string, KnobCopy> = {
  // load limits
  maxInflight: {
    group: 'limits',
    unit: 'count',
    summary: 'How many turns one head sends upstream at the same time. More wait in the queue. 0 means no limit.',
  },
  maxQueued: {
    group: 'limits',
    unit: 'count',
    summary: 'How many turns can wait for a free slot on one head. Past this, new turns are refused.',
  },
  maxRequestBytes: {
    group: 'limits',
    unit: 'bytes',
    summary: 'The largest request splice accepts from Claude Code. A larger one is refused.',
  },
  requestReadTimeoutMs: {
    group: 'limits',
    unit: 'ms',
    summary: 'How long splice waits for Claude Code to finish sending a request.',
  },
  materializationPermits: {
    group: 'limits',
    unit: 'count',
    summary: "How many requests the whole daemon converts into a provider's format at once, across every head.",
  },

  // retries and timeouts
  upstreamRetries: {
    group: 'retries',
    unit: 'count',
    summary: 'How many times a failing call to the provider is tried, counting the first try, before the turn fails.',
  },
  retryBackoffBaseMs: {
    group: 'retries',
    unit: 'ms',
    summary: 'The wait before the first retry of an unexpected error. Each later retry waits longer.',
  },
  retryBackoffCapMs: {
    group: 'retries',
    unit: 'ms',
    summary: 'The longest any single retry waits.',
  },
  retryBackoffJitterPct: {
    group: 'retries',
    unit: 'percent',
    summary: 'How much each retry wait varies at random, so heads that fail together do not retry together.',
  },
  upstreamTimeoutMs: {
    group: 'retries',
    unit: 'ms',
    summary: 'The longest one turn can run before splice ends it.',
  },
  firstByteTimeoutMs: {
    group: 'retries',
    unit: 'ms',
    summary: 'How long a provider has to start answering before splice checks whether the connection is still alive.',
  },
  streamIdleMs: {
    group: 'retries',
    unit: 'ms',
    summary: 'How long an answer can go quiet before splice checks the connection. A live one is kept waiting; a dead one is retried.',
  },
  stallReanchorMs: {
    group: 'retries',
    unit: 'ms',
    summary: 'For heads that can resume a cut answer: how long output can stall before splice resumes the turn itself.',
  },

  // reasoning
  effort: {
    group: 'reasoning',
    summary: "How hard reasoning models think when Claude Code does not say. Leave empty to use each model's own default.",
  },
  summary: {
    group: 'reasoning',
    summary: 'How much of its reasoning an OpenAI-style model sends back: auto, concise or detailed.',
  },
  showReasoning: {
    group: 'reasoning',
    summary: 'How reasoning shows in Claude Code: text, thinking (as thinking blocks) or off.',
  },
  replayReasoning: {
    group: 'reasoning',
    summary: "Send a model's earlier encrypted reasoning back to it each turn. Off by default: it measurably shortens new reasoning.",
  },
  mirrorReasoning: {
    group: 'reasoning',
    locked: true,
    summary: 'Always off. splice never writes a reasoning summary of its own into the conversation.',
  },
  progressLine: {
    group: 'reasoning',
    summary: 'While a turn is silent, show a line saying how long splice has been waiting for the model.',
  },
  foldReasoningModels: {
    group: 'reasoning',
    summary: 'Models that cut their own reasoning short. splice asks these to keep thinking until they finish. Comma-separated.',
  },
  foldMaxContinue: {
    group: 'reasoning',
    unit: 'count',
    summary: 'The most times splice asks a model from the fold list to keep thinking in one turn.',
  },
  foldMarkerText: {
    group: 'reasoning',
    summary: 'The text splice sends to ask a model to keep thinking.',
  },
  foldMaxTier: {
    group: 'reasoning',
    unit: 'count',
    summary: 'Reasoning that stops at exactly 518 × n − 2 tokens, for n up to this number, counts as cut short.',
  },

  // models and tools
  contextWindowOverride: {
    group: 'models',
    unit: 'tokens',
    summary: "Report this context window for every model instead of the provider's figure. Leave empty to use the provider's.",
  },
  toolSurface: {
    group: 'models',
    summary: "off sends every tool to every model up front. auto lets each provider's settings decide whether tools load on demand.",
  },

  // usage and budgets
  quotaPoll: {
    group: 'usage',
    summary: 'Subscription heads ask their provider how much of the plan is left. off stops that; the bars then update only from turns.',
  },
  quotaPollIntervalMs: {
    group: 'usage',
    unit: 'ms',
    summary: 'How often subscription heads ask for plan usage. splice keeps a minimum so the provider is not flooded.',
  },
  usageWarnPct: {
    group: 'usage',
    unit: 'percent',
    summary: 'Warn when a plan window passes this share of its limit.',
  },
  usageWarnTokens5h: {
    group: 'usage',
    unit: 'tokens',
    summary: 'Warn when a head uses more than this many tokens in five hours. 0 turns the warning off.',
  },
  budgetDefaultAction: {
    group: 'usage',
    summary: 'What a new budget does when it is reached, if you do not choose: warn, or block new turns.',
  },

  // shared mcp servers
  mcpMaxServers: {
    group: 'mcp',
    unit: 'count',
    summary: 'The most MCP servers splice runs for every session to share.',
  },
  mcpIdleTimeoutMs: {
    group: 'mcp',
    unit: 'ms',
    summary: 'Stop a shared MCP server after it has been idle this long.',
  },
  mcpRequestTimeoutMs: {
    group: 'mcp',
    unit: 'ms',
    summary: 'The longest one call to a shared MCP server can take.',
  },
  mcpInitializeTimeoutMs: {
    group: 'mcp',
    unit: 'ms',
    summary: 'How long a shared MCP server has to start before splice gives up on it.',
  },
  mcpSlice: {
    group: 'mcp',
    summary: 'The systemd slice shared MCP servers run in. A memory limit on the slice caps all of them.',
  },

  // recording and history
  trace: {
    group: 'records',
    headOnly: true,
    summary: 'Record every request, provider call and answer for a head, secrets removed. It keeps whole conversations: turn it on per head, only while investigating.',
  },
  traceRetentionDays: {
    group: 'records',
    unit: 'days',
    summary: 'Days of trace files kept. Older days are deleted.',
  },
  traceMaxBodyChars: {
    group: 'records',
    unit: 'chars',
    summary: 'A body longer than this is cut in the trace and marked as cut.',
  },
  wireTap: {
    group: 'records',
    headOnly: true,
    unit: 'count',
    summary: 'Keep this many recent requests to the provider in memory, for splice wire. 0 keeps none. Set it per head.',
  },
  activityRetentionDays: {
    group: 'records',
    unit: 'days',
    summary: 'Days of session activity and message history kept for the console.',
  },
  activityStoreHeads: {
    group: 'records',
    summary: 'Which heads store activity labels: * for all, empty for none, or head keys separated by commas.',
  },
  perfArchiveRetentionDays: {
    group: 'records',
    unit: 'days',
    summary: 'Days of past turn statistics kept. 0 keeps only the current file.',
  },
  debug: {
    group: 'records',
    summary: 'Write detailed debug lines to the daemon log.',
  },

  // chatgpt and grok logins
  port: {
    group: 'logins',
    unit: 'port',
    summary: 'The port every ChatGPT-login head listens on. It replaces the port splice.toml gives that head.',
  },
  pinnedModel: {
    group: 'logins',
    summary: 'The model every ChatGPT-login head uses. It replaces the pinned model splice.toml gives that head.',
  },
  chatgptApiBase: {
    group: 'logins',
    summary: 'The ChatGPT backend address that ChatGPT-login providers call.',
  },
  codexAuthPath: {
    group: 'logins',
    summary: 'Where the ChatGPT login is stored. splice login writes it here.',
  },
  grokPort: {
    group: 'logins',
    unit: 'port',
    summary: 'The port every Grok-login head listens on. It replaces the port splice.toml gives that head.',
  },
  grokModel: {
    group: 'logins',
    summary: 'The model every Grok-login head uses. It replaces the pinned model splice.toml gives that head.',
  },
  xaiApiBase: {
    group: 'logins',
    summary: 'The xAI API address that Grok-login providers call.',
  },
  grokAuthPath: {
    group: 'logins',
    summary: 'Where the Grok login is stored. splice login writes it here.',
  },
  authCacheMs: {
    group: 'logins',
    unit: 'ms',
    summary: 'How long a head reuses a login before reading it from disk again.',
  },

  // daemon
  controlPort: {
    group: 'daemon',
    unit: 'port',
    summary: 'The port this console and the control API listen on.',
  },
  supervisorUnit: {
    group: 'daemon',
    summary: 'The systemd user unit that runs splice. Restart daemon restarts this unit.',
  },
  statuslineGitRoots: {
    group: 'daemon',
    summary: 'More folders, separated by colons, where the status line may show a git branch. Home and /tmp are always allowed.',
  },
};

const MS_PER_S = 1000;
const S_PER_MIN = 60;
const MIN_PER_H = 60;
const KIB = 1024;
const MILLION = 1_000_000;

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`;
}

/** A millisecond count as a person says it: 90000 is "1 min 30 s", 1800000 is "30 min". */
export function readableMs(ms: number): string {
  if (ms < MS_PER_S) return `${ms} ms`;
  const totalS = ms / MS_PER_S;
  if (totalS < S_PER_MIN) return `${Number.isInteger(totalS) ? totalS : totalS.toFixed(1)} s`;
  const totalMin = Math.floor(totalS / S_PER_MIN);
  const s = Math.round(totalS - totalMin * S_PER_MIN);
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
  while (value >= KIB && at < units.length - 1) {
    value /= KIB;
    at += 1;
  }
  return `${Number.isInteger(value) ? value : value.toFixed(1)} ${units[at]}`;
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
