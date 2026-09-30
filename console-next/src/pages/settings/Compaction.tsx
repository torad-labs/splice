import { failureText } from '../../api/client';
import { useHeads } from '../../api/queries';
import { useCompactStats, useCompactionInstructions } from '../../api/turns';
import { clockTime, fmtInt } from '../../lib/format';
import { charsText, compactLede, failedOf, medianOf, outcomeCounts, outcomeRows, outcomeText, plansText, recentOf, ruleText, shareText, stateOf } from '../../lib/compaction';
import { secondsText } from '../../lib/turns-page';
import { W } from '../../lib/words-compaction';
import { State } from '../../ui';
import type { StateTone } from '../../ui';
import { Row } from './Row';

const TONE: Record<ReturnType<typeof stateOf>, StateTone> = { ok: 'work', warn: 'quota', fail: 'stuck' };

/** How compactions ended, the newest few, and the instructions they ran under. Read only: the rules are lines in splice.toml. */
export function Compaction() {
  const stats = useCompactStats();
  const rules = useCompactionInstructions();
  const heads = useHeads();
  const labelOf = (key: string): string => heads.data?.heads.find((head) => head.key === key)?.label ?? key;
  const data = stats.data?.stats;
  const { counts, total, week } = data === undefined ? { counts: {}, total: 0, week: false } : outcomeCounts(data);
  const failed = failedOf(counts);
  const recent = data === undefined ? [] : recentOf(data.tail, 8);
  const median = data === undefined ? null : medianOf(data.tail, 'ms');
  return (
    <section className="kept-block" aria-labelledby="settings-compaction">
      <h3 id="settings-compaction" className="kept-title">{W.title}</h3>
      <p className="kept-why">{W.why}</p>
      {stats.isError ? <p className="tip bad" role="alert">{failureText(stats.error)}</p> : null}
      {data === undefined ? null : (
        <>
          <Row
            title={W.outcomes}
            why={compactLede(total, failed, week)}
            control={
              <span className="census">
                {outcomeRows(counts).map(({ outcome, count }) => (
                  <span key={outcome}><State tone={TONE[stateOf(outcome)]}>{outcomeText(outcome)}</State> {fmtInt(count)} · {shareText(count, total)}</span>
                ))}
                {median === null ? null : <span>{W.took} {secondsText(median)}</span>}
              </span>
            }
          />
          {recent.length === 0 ? null : (
            <Row
              title={W.recent}
              why=""
              control={
                <ul className="recent">
                  {recent.map((row) => (
                    <li key={`${row.head}-${row.ts}`}>
                      <span>{clockTime(row.ts)}</span>
                      <span>{row.head}</span>
                      <State tone={TONE[stateOf(row.outcome ?? '')]}>{outcomeText(row.outcome ?? row.error ?? '')}</State>
                      <span>{row.ms === undefined ? '' : secondsText(row.ms)}</span>
                    </li>
                  ))}
                </ul>
              }
            />
          )}
        </>
      )}
      {rules.isError ? <p className="tip bad" role="alert">{failureText(rules.error)}</p> : null}
      {rules.data === undefined ? null : (
        <Row
          title={W.rules}
          why={rules.data.rules.length === 0 ? W.rulesNone : W.rulesWhy}
          control={
            <ul className="recent rules">
              {rules.data.rules.map((rule) => {
                const text = ruleText(rule);
                const plans = plansText(rule.heads, labelOf);
                return (
                  <li key={`${rule.scope}-${rule.source}`}>
                    <b>{text.scope}</b>
                    {text.names === null ? null : <span>{text.names}</span>}
                    <span>{charsText(rule.chars)}</span>
                    {plans === null ? null : <span>{plans}</span>}
                  </li>
                );
              })}
            </ul>
          }
        />
      )}
      {rules.data === undefined || rules.data.unread.length === 0 ? null : <p className="tip bad">{rules.data.unread.map((row) => W.unread(row.head, row.reason)).join(' ')}</p>}
    </section>
  );
}
