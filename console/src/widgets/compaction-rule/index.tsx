// One compaction instruction rule as a strip: its scope, core's source label, its live length and,
// where the reader knows them, the heads it applies to. Never its text: no route carries it, and the
// source names the file an operator opens to read it.
//
// Two pages print rules — the compaction page (every configured rule, merged across heads) and the
// project detail (the rules a compaction in that repo resolves to) — and both read the same wire
// shape, so the strip and the words for a length live here once rather than drifting in two copies.
import { Strip, StripField } from '@shared/ui';
import { S } from './strings';

/** The wire shape both routes write for a rule (CompactionInstructionsRoute, ProjectsRoutes). */
export interface CompactionRuleView {
  scope: string;
  /** Core's composed label; printed, never parsed. */
  source: string;
  chars: number | null;
}

/** A rule's length as printed: the live character count, `client default` for an empty text (the
 *  client's own instructions stand), and `unavailable` when the rule's file cannot be read (its source label
 *  says so too). Zero is never printed as a length: it is a decision, not a size. */
export function charsText(chars: number | null): string {
  if (chars === null) return S.unavailable;
  return chars === 0 ? S.optOut : String(chars);
}

/** One rule. `heads` is printed only when the reader knows which heads listed it. */
export function CompactionRuleStrip({ rule, heads }: { rule: CompactionRuleView; heads?: readonly string[] | undefined }) {
  return (
    <Strip edge="grey" edgeLabel={S.rule} ariaLabel={`${S.instruction} ${rule.source}`}>
      <StripField w={14} label={S.scope} value={rule.scope} mono={false} />
      <StripField w={44} label={S.source} value={rule.source} mono={false} />
      <StripField w={12} label={S.chars} value={charsText(rule.chars)} />
      {heads === undefined ? null : <StripField w={36} label={S.heads} value={heads.join(' ')} mono={false} />}
    </Strip>
  );
}
