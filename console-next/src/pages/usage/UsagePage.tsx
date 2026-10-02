import { useSearchParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useStatus, useUsage } from '../../api/queries';
import { useEconomics } from '../../api/usage';
import { ABSENT, fmtInt, fmtShare } from '../../lib/format';
import { costOf, hitRate } from '../../lib/economics';
import { colourFromRegistry } from '../../lib/model';
import { cacheLine, costLine, orderPlans, planUsage, splitIdle, tokensText, totalsOf, usageLede, windowChoices } from '../../lib/usage-page';
import type { PlanUsage } from '../../lib/usage-page';
import { U, spanWords } from '../../lib/words-usage';
import { Empty, Fault, PageHead, Segmented } from '../../ui';
import { Alerts } from './Alerts';
import { Budgets } from './Budgets';
import './usage.css';

function Spark({ values }: { values: readonly number[] }) {
  const top = Math.max(...values, 1);
  return (
    <svg viewBox="0 0 144 32" width="144" height="32" role="img" aria-label={U.sparkLabel}>
      {values.map((value, index) => {
        const height = value === 0 ? 1 : Math.max(2, (value / top) * 30);
        return <rect key={index} x={index * 6 + 1} y={32 - height} width={4} height={height} rx={2} />;
      })}
    </svg>
  );
}

function PlanRow({ plan }: { plan: PlanUsage }) {
  const words = plan.pct === null
    ? [plan.full ? U.refused : plan.turns === 0 ? U.idle : U.noLimit]
    : [plan.reset === null ? null : U.resets(plan.reset), plan.pace].filter((part): part is string => part !== null);
  return (
    <li className={`uplan hue ${plan.colour}`}>
      <b><i />{plan.label}</b>
      <div className="use">
        {plan.pct === null ? null : (
          <div className={`track${plan.full ? ' full' : ''}`} role="img" aria-label={U.ofLimit(Math.round(plan.pct))}><i style={{ width: `${Math.min(100, plan.pct)}%` }} /></div>
        )}
        <span>{plan.pct === null ? null : <b>{U.ofLimit(Math.round(plan.pct))}</b>}{plan.pct === null || words.length === 0 ? '' : ' · '}{words.join(' · ')}</span>
      </div>
      <div className="tok"><strong>{tokensText(plan.inTokens)}</strong>{U.readIn}</div>
      <Spark values={plan.spark} />
      <div className="cost">{costLine(plan.cost)}<small>{cacheLine(plan.cache)}</small></div>
    </li>
  );
}

/** What the plans have used, against what each may, and the two settings that act on it. */
export function UsagePage() {
  const [params, setParams] = useSearchParams();
  const economics = useEconomics();
  const heads = useHeads();
  const status = useStatus();
  const usage = useUsage();

  if (economics.isPending) return <PageHead title={U.title} lede={U.reading} />;
  if (economics.isError) return <><PageHead title={U.title} /><Fault message={failureText(economics.error)} onRetry={() => void economics.refetch()} /></>;

  const now = Date.now();
  const choices = windowChoices(economics.data.retention_hours);
  const asked = params.get('window') ?? '24';
  const hours = Number((choices.find(([id]) => id === asked) ?? choices[0])?.[0] ?? '24');
  const headRows = heads.data?.heads ?? [];
  const colourOf = colourFromRegistry(status.data);
  const label = (key: string): string => headRows.find((head) => head.key === key)?.label ?? key;
  const plans = orderPlans(economics.data.heads.map((head) => planUsage(head, label(head.key), colourOf(head.key), usage.data ?? null, hours, now, headRows.find((status) => status.key === head.key))));
  const { active, idle } = splitIdle(plans);
  const totals = totalsOf(economics.data.heads, hours, now);
  const cost = costOf(totals);
  const cached = hitRate(totals);

  return (
    <>
      <PageHead
        title={U.title}
        lede={usageLede(totals, plans, hours)}
        tools={choices.length < 2 ? undefined : <Segmented label={U.window} value={String(hours)} options={choices} onChange={(next) => setParams(next === '24' ? {} : { window: next }, { replace: true })} />}
      />
      {plans.length === 0 ? <Empty title={U.plansNone} /> : (
        <>
          <section className="section" aria-label={U.title}>
            <div className="totals">
              <div><div className="n">{fmtInt(totals.turns)}</div><h3>{U.totalsTurns}</h3><p>{U.totalsTurnsWhy(plans.length, spanWords(hours))}</p></div>
              <div><div className="n">{tokensText(totals.inTokens)}</div><h3>{U.totalsIn}</h3><p>{cached === null ? U.totalsInPlain : U.totalsInWhy(fmtShare(cached))}</p></div>
              <div><div className="n">{tokensText(totals.outTokens)}</div><h3>{U.totalsOut}</h3><p>{U.totalsOutWhy}</p></div>
              <div><div className="n">{cost === null ? ABSENT : costLine(cost)}</div><h3>{U.totalsCost}</h3><p>{cost === null ? U.totalsCostNone : `${U.totalsCostWhy}${totals.unpricedTurns > 0 ? ` ${U.totalsCostUnpriced(totals.unpricedTurns)}` : ''}`}</p></div>
            </div>
          </section>
          <section className="section" aria-labelledby="usage-plans">
            <h2 id="usage-plans">{U.plansTitle}</h2>
            <p className="why">{U.plansWhy}</p>
            {active.length === 0 ? null : <ul className="uplans">{active.map((plan) => <PlanRow key={plan.key} plan={plan} />)}</ul>}
            {idle.length === 0 ? null : <p className="idle-plans">{U.idlePlans(idle.map((plan) => plan.label).join(", "), spanWords(hours))}</p>}
          </section>
          <Budgets plans={plans} />
          <Alerts />
        </>
      )}
    </>
  );
}
