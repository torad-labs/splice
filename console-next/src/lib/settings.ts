// What Settings shows and writes, as data and pure rules: the sections, the closed choices of the curated knobs, the day
// counts a retention select offers, the colon list of git folders, what a save answered, and the topology edits the Tools
// switches make.
import type { ConfigValue, PatchResult } from '../types/core';
import type { DoctorCheck } from '../types/doctor';
import type { McpServer } from '../types/mcp';
import type { KnobDisposition } from '../types/config';
import { wantsAttention } from './doctor';
import { KNOB_META } from './knobs';
import type { KnobGroup, KnobMeta } from './knobs';
import { GROUP_LABELS, KNOB_LABELS } from './words-knobs';
import { R, toolReasonText } from './words-settings';

export const SECTIONS = ['general', 'conversation', 'tools', 'storage', 'health', 'advanced'] as const;
export type Section = (typeof SECTIONS)[number];

/** The section an address names; anything else is the first. */
export const sectionOf = (param: string | undefined): Section => SECTIONS.find((section) => section === param) ?? 'general';

// ── the curated knobs ─────────────────────────────────────────────────────────────────────────────

/** `effort`: unset leaves each model its own default. The rungs above and below these four are in Advanced. */
export const EFFORT_CHOICES = [['', 'Model default'], ['low', 'Low'], ['medium', 'Medium'], ['high', 'High']] as const;
export type Effort = (typeof EFFORT_CHOICES)[number][0];
export const effortChoice = (value: ConfigValue | undefined): Effort | null => EFFORT_CHOICES.find(([id]) => id === (value ?? ''))?.[0] ?? null;

/** `showReasoning`: ConfigCoercion folds every spelling into these three. */
export const REASONING_CHOICES = [
  { id: 'text', ...R.text },
  { id: 'thinking', ...R.thinking },
  { id: 'off', ...R.off },
] as const;

export const ACTIVITY_DAYS = [2, 7, 14, 30, 60, 90, 180, 365] as const;
export const TRACE_DAYS = [1, 3, 7, 14, 30, 60, 90] as const;

/** The days a select offers: the list, and the value the daemon holds now even when it is not on it. */
export function daysOptions(list: readonly number[], current: number | null): number[] {
  return current === null || list.includes(current) ? [...list] : [...list, current].sort((a, b) => a - b);
}

export const numberOf = (value: ConfigValue | undefined): number | null => (typeof value === 'number' ? value : null);
export const textOf = (value: ConfigValue | undefined): string => (value === null || value === undefined ? '' : String(value));

/** `statuslineGitRoots` is a colon-separated list of folders. */
export const gitRootsOf = (value: ConfigValue | undefined): string[] => textOf(value).split(':').map((part) => part.trim()).filter((part) => part !== '');
export const gitRootsValue = (roots: readonly string[]): string => roots.join(':');

/** A folder as the daemon reads it: no colon (it would split), no blank. Null when it cannot be one. */
export function folderOf(raw: string): string | null {
  const path = raw.trim();
  return path === '' || path.includes(':') ? null : path;
}

// ── what a save answered ──────────────────────────────────────────────────────────────────────────

export type SaveOutcome =
  /** In force now. */
  | { kind: 'saved' }
  /** Saved; the running daemon reads it at its next start. */
  | { kind: 'waits' }
  /** The daemon named the key and did not save it: its own reason. */
  | { kind: 'rejected'; reason: string }
  /** In force now, but it did not reach the file, so a restart forgets it. */
  | { kind: 'live-only'; why: string };

/** PATCH /api/config answers 200 with `rejected` naming a key it did not save, and `persisted: null` when the value is live
 *  but not on disk. Neither is success. */
export function outcomeOf(key: string, result: PatchResult): SaveOutcome {
  const reason = result.rejected[key];
  if (reason !== undefined) return { kind: 'rejected', reason };
  if (result.persisted === null) return { kind: 'live-only', why: result.not_persisted };
  return result.restart_required.includes(key) ? { kind: 'waits' } : { kind: 'saved' };
}

// ── the Tools switches: edits of the topology's [daemon] table ────────────────────────────────────

type Topology = Record<string, unknown>;
const tableOf = (value: unknown): Topology => (typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Topology) : {});

export const excludedOf = (topology: Topology): string[] => {
  const list = tableOf(topology['daemon'])['mcp_hosting_exclude'];
  return Array.isArray(list) ? list.filter((name): name is string => typeof name === 'string') : [];
};

const withDaemon = (topology: Topology, change: Topology): Topology => ({ ...topology, daemon: { ...tableOf(topology['daemon']), ...change } });

/** `daemon.mcp_hosting`: one process per tool server, shared by every session. */
export const withMcpHosting = (topology: Topology, on: boolean): Topology => withDaemon(topology, { mcp_hosting: on });

/** A saved command override, separate from the daemon's effective boot-time layer. */
export function headOverrideOf(topology: Topology, head: string, key: string): string | null {
  const value = tableOf(tableOf(tableOf(topology['heads'])[head])['overrides'])[key];
  return typeof value === 'string' ? value : null;
}

/** The command's saved thinking default. */
export const headEffort = (topology: Topology, head: string): string | null => headOverrideOf(topology, head, 'effort');

/** Add or remove one server in `daemon.mcp_hosting_exclude`. An empty list is dropped, so an untouched file stays as it was. */
export function withServerExcluded(topology: Topology, name: string, excluded: boolean): Topology {
  const rest = excludedOf(topology).filter((entry) => entry !== name);
  const next = excluded ? [...rest, name].sort() : rest;
  const daemon = { ...tableOf(topology['daemon']) };
  if (next.length === 0) delete daemon['mcp_hosting_exclude'];
  else daemon['mcp_hosting_exclude'] = next;
  return { ...topology, daemon };
}

/** The daemon's sentence as prose: its markdown backticks dropped and a capital to begin. */
export function proseOf(sentence: string): string {
  const plain = sentence.replaceAll('`', '').replaceAll('Claude head', 'Claude command');
  return `${plain.charAt(0).toUpperCase()}${plain.slice(1)}`;
}

/** A server's word and whether its switch is on: on = shared with sessions. */
export type ToolState = { word: 'Running' | 'Not started' | 'Not shared'; tone: 'work' | 'idle'; shared: boolean; why: string | null };
export function toolState(server: McpServer, excluded: boolean): ToolState {
  // the operator's own exclusion makes the planner call the server ineligible: the switch says why, the planner's echo adds nothing
  if (excluded) return { word: 'Not shared', tone: 'idle', shared: false, why: null };
  if (!server.eligible) return { word: 'Not shared', tone: 'idle', shared: false, why: toolReasonText(server.reason) };
  return server.hosted ? { word: 'Running', tone: 'work', shared: true, why: null } : { word: 'Not started', tone: 'idle', shared: true, why: null };
}

// ── Health ────────────────────────────────────────────────────────────────────────────────────────

export type Health = { word: 'All good' | 'Mostly good' | 'Needs you'; tone: 'work' | 'wait' | 'stuck'; wanting: number };

/** One word for the whole report: a failing check needs you, a warning is mostly good, nothing wants attention is all good. */
export function healthOf(checks: readonly DoctorCheck[]): Health {
  const wanting = checks.filter((check) => wantsAttention(check.status));
  if (checks.some((check) => check.status === 'fail')) return { word: 'Needs you', tone: 'stuck', wanting: wanting.length };
  return wanting.length > 0 ? { word: 'Mostly good', tone: 'wait', wanting: wanting.length } : { word: 'All good', tone: 'work', wanting: 0 };
}

// ── Advanced: every knob the curated sections do not show ─────────────────────────────────────────

/** The knobs the sections above draw as their own control. */
export const CURATED_KNOBS: ReadonlySet<string> = new Set([
  'usageWarnPct', 'debug', 'effort', 'maxInflight', 'showReasoning', 'activityRetentionDays', 'traceRetentionDays', 'statuslineGitRoots',
]);

export interface KnobGroupView {
  group: KnobGroup;
  knobs: KnobDisposition[];
}

/** The rest of the knobs, in the groups GROUP_LABELS orders, each group by the name the operator reads. A knob the table does not
 *  place (the daemon added one) goes to the last group rather than nowhere: no knob leaves the console. */
export function otherKnobs(dispositions: readonly KnobDisposition[]): KnobGroupView[] {
  const order = Object.keys(GROUP_LABELS) as KnobGroup[];
  const last = order[order.length - 1] ?? 'daemon';
  const nameOf = (key: string): string => (KNOB_LABELS as Record<string, string>)[key] ?? key;
  return order
    .map((group) => ({
      group,
      knobs: dispositions
        .filter((knob) => !CURATED_KNOBS.has(knob.key) && (KNOB_META[knob.key]?.group ?? last) === group)
        .sort((a, b) => nameOf(a.key).localeCompare(nameOf(b.key))),
    }))
    .filter((view) => view.knobs.length > 0);
}

/** What kind of control a knob gets, from what the daemon says it is. */
export type KnobControl =
  | { kind: 'locked' }
  /** Set only in a plan's own overrides: the global view prints it. */
  | { kind: 'head-only' }
  | { kind: 'switch' }
  | { kind: 'choice'; choices: readonly string[] }
  | { kind: 'number' }
  | { kind: 'text' };

export function controlOf(value: ConfigValue, meta: KnobMeta | undefined, head: string | null): KnobControl {
  if (meta?.locked === true) return { kind: 'locked' };
  if (meta?.headOnly === true && head === null) return { kind: 'head-only' };
  if (meta?.choices !== undefined) return { kind: 'choice', choices: meta.choices };
  if (typeof value === 'boolean') return { kind: 'switch' };
  if (typeof value === 'number') return { kind: 'number' };
  return { kind: 'text' };
}

/** A plan's own override of one knob, written into `[heads.<key>.overrides]`; `null` removes it, and a head left with no
 *  overrides loses the table. The table holds strings (the daemon reads them back as the knob's type). */
export function withHeadOverride(topology: Topology, head: string, key: string, value: string | number | boolean | null): Topology {
  const heads = tableOf(topology['heads']);
  const entry = tableOf(heads[head]);
  const kept = Object.entries(tableOf(entry['overrides'])).filter(([name]) => name !== key);
  const overrides = Object.fromEntries(value === null ? kept : [...kept, [key, String(value)]]);
  const rest = Object.fromEntries(Object.entries(entry).filter(([name]) => name !== 'overrides'));
  return { ...topology, heads: { ...heads, [head]: Object.keys(overrides).length === 0 ? rest : { ...rest, overrides } } };
}
