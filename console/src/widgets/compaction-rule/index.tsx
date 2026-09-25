// Compaction instruction rules: each one's scope, core's source label, its live length and, where the
// reader knows them, the heads it applies to. Never its text: no route carries it, and the source
// names the file an operator opens to read it.
//
// Two pages print rules — the compaction page (every configured rule, merged across heads) and the
// project detail (the rules a compaction in that repo resolves to) — and both read the same wire
// shape, so the table and the words for a length live here once rather than drifting in two copies.
import { DataTable, Strip, StripField } from '@shared/ui';
import type { Column } from '@shared/ui';
import { LEGACY, S } from './strings';

/** The wire shape both routes write for a rule (CompactionInstructionsRoute, ProjectsRoutes). */
export interface CompactionRuleView {
  scope: string;
  /** Core's composed label; printed, never parsed. */
  source: string;
  chars: number | null;
}

/** A rule's length as printed: the live character count, `Client default` for an empty text (the
 *  client's own instructions stand), and `Unavailable` when the rule's file cannot be read (its
 *  source label says so too). Zero is never printed as a length: it is a decision, not a size. */
export function charsText(chars: number | null): string {
  if (chars === null) return S.unavailable;
  return chars === 0 ? S.optOut : String(chars);
}

/** Rules as a table, in the order given, which is the daemon's precedence. `heads` is a column only
 *  when the reader knows which heads listed each rule. */
export function CompactionRules({ rules, headsOf }: {
  rules: readonly CompactionRuleView[];
  headsOf?: ((rule: CompactionRuleView) => readonly string[]) | undefined;
}) {
  const columns: Column<CompactionRuleView>[] = [
    // Every cell wraps rather than clips: a detail panel is narrow, and a rule's source is the file
    // an operator opens, so it is read whole.
    { key: 'scope', label: S.scope, width: '26%', wrap: true, cell: (rule) => rule.scope },
    { key: 'source', label: S.source, mono: true, wrap: true, cell: (rule) => rule.source },
    { key: 'chars', label: S.chars, width: '26%', align: 'end', wrap: true, cell: (rule) => charsText(rule.chars) },
    ...(headsOf === undefined ? [] : [{ key: 'heads', label: S.heads, width: '22%', cell: (rule: CompactionRuleView) => headsOf(rule).join(', ') }]),
  ];
  return <DataTable columns={columns} rows={rules} rowKey={(rule) => `${rule.scope}:${rule.source}`} label={S.rules} />;
}

/** One rule as a strip, for the compaction page until its rebuild lands. */
export function CompactionRuleStrip({ rule, heads }: { rule: CompactionRuleView; heads?: readonly string[] | undefined }) {
  return (
    <Strip edge="grey" edgeLabel={LEGACY.rule} ariaLabel={`${LEGACY.instruction} ${rule.source}`}>
      <StripField w={14} label={S.scope} value={rule.scope} mono={false} />
      <StripField w={44} label={S.source} value={rule.source} mono={false} />
      <StripField w={12} label={S.chars} value={charsText(rule.chars)} />
      {heads === undefined ? null : <StripField w={36} label={S.heads} value={heads.join(' ')} mono={false} />}
    </Strip>
  );
}
