// Compaction instruction rules: each one's scope, core's source label, its live length and, where the
// reader knows them, the heads it applies to. Never its text: no route carries it, and the source
// names the file an operator opens to read it.
//
// Two pages print rules — the compaction page (every configured rule, merged across heads) and the
// project detail (the rules a compaction in that repo resolves to) — and both read the same wire
// shape, so the table and its length cell live here once rather than drifting in two copies.
import { HeadMark } from '@entities/control-status';
import { fmtInt, ratio } from '@shared/lib';
import { Badge, DataTable, Meter, weightedColumns } from '@shared/ui';
import type { Column } from '@shared/ui';
import { S } from './strings';
import './compaction-rule.css';

/** The wire shape both routes write for a rule (CompactionInstructionsRoute, ProjectsRoutes). */
export interface CompactionRuleView {
  scope: string;
  /** Core's composed label; printed, never parsed. */
  source: string;
  chars: number | null;
}

/** A rule's length, shown before it is said: a bar against the longest rule in view with its count,
 *  `Client default` for an empty text (the client's own instructions stand), and `Unavailable` when
 *  the rule's file cannot be read (its source label says so too). Zero is never drawn as a length:
 *  it is a decision, not a size. */
export function RuleLength({ rule, longest }: { rule: CompactionRuleView; longest: number }) {
  if (rule.chars === null) return <Badge tone="warn" quiet>{S.unavailable}</Badge>;
  if (rule.chars === 0) return <Badge tone="neutral" quiet>{S.optOut}</Badge>;
  return <Meter value={ratio(rule.chars, longest)} tone="neutral" label={`${S.chars} ${rule.source}`} figure={fmtInt(rule.chars)} />;
}

/** Each column's share, normalised over the columns shown: the heads column is there only when the
 *  reader knows which heads listed each rule. */
const WEIGHTS: Record<string, number> = { scope: 14, source: 38, chars: 24, heads: 24 };

/** Rules as a table, in the order given, which is the daemon's precedence. */
export function CompactionRules<R extends CompactionRuleView>({ rules, headsOf, label = S.rules }: {
  rules: readonly R[];
  headsOf?: ((rule: R) => readonly string[]) | undefined;
  /** The table's accessible name: the title of the section it sits in. */
  label?: string;
}) {
  const longest = Math.max(0, ...rules.map((rule) => rule.chars ?? 0));
  const columns: Column<R>[] = [
    // Scope and source wrap rather than clip: a detail panel is narrow, and a rule's source is the
    // file an operator opens, so it is read whole.
    { key: 'scope', label: S.scope, wrap: true, cell: (rule) => rule.scope },
    { key: 'source', label: S.source, mono: true, wrap: true, primary: true, cell: (rule) => rule.source },
    { key: 'chars', label: S.chars, cell: (rule) => <RuleLength rule={rule} longest={longest} /> },
    ...(headsOf === undefined ? [] : [{
      key: 'heads',
      label: S.heads,
      cell: (rule: R) => <span className="myx-cr-heads">{headsOf(rule).map((head) => <HeadMark key={head} head={head} />)}</span>,
    }]),
  ];
  return <DataTable columns={weightedColumns(columns, WEIGHTS)} rows={rules} rowKey={(rule) => `${rule.scope}:${rule.source}`} label={label} />;
}
