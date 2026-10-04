import { useSearchParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useStatus, useUsage } from '../../api/queries';
import { useEconomics } from '../../api/usage';
import { usePerfTurns } from '../../api/turns';
import { isPendingRoute } from '../../api/auth';
import { ABSENT, fmtInt, fmtShare } from '../../lib/format';
import { fullWindowUsage } from '../../lib/usage-breakdown';
import { colourFromRegistry } from '../../lib/model';
import { cacheLine, costLine, orderPlans, planUsage, splitIdle, tokensText, totalsOf, usageLede, windowChoices } from '../../lib/usage-page';
import type { PlanUsage } from '../../lib/usage-page';
import { U, spanWords } from '../../lib/words-usage';
import { Empty, Fault, PageHead, Segmented } from '../../ui';
import { Alerts } from './Alerts';
import { Budgets } from './Budgets';
import { UsageBreakdown } from './UsageBreakdown';
import { UsagePricing } from './UsagePricing';
import { B } from './copy';
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
    ? [plan.full ? U.refused : plan.reading ?? (plan.turns === 0 ? U.idle : U.noLimit)]
    : [plan.reset === null ? null : U.resets(plan.reset), plan.pace, plan.reading].filter((part): part is string => part !== null);
  return (
    <li className={`uplan hue ${plan.colour}`}>
      <b><i />{plan.label}</b>
      <div className="use">
        {plan.pct === null ? null : (
          <div className={`track${plan.full ? ' full' : ''}`} role="img" aria-label={U.ofLimit(Math.round(plan.pct), plan.limitWindow)}><i style={{ width: `${Math.min(100, plan.pct)}%` }} /></div>
        )}
        <span>{plan.pct === null ? null : <b>{U.ofLimit(Math.round(plan.pct), plan.limitWindow)}</b>}{plan.pct === null || words.length === 0 ? '' : ' · '}{words.join(' · ')}</span>
      </div>
      <div className="tok">{plan.turns === null ? <span>{B.unknown}</span> : plan.turns === 0 ? <span>{U.idle}</span> : <><strong>{plan.partial && plan.inTokens !== null ? B.atLeast(tokensText(plan.inTokens)) : tokensText(plan.inTokens)}</strong>{U.readIn}</>}</div>
      {plan.spark.length === 0 ? <span className="hint">{B.unknown}</span> : <Spark values={plan.spark} />}
      <div className="cost">{plan.costPartial && plan.cost !== null ? B.atLeast(costLine(plan.cost)) : costLine(plan.cost)}<small>{cacheLine(plan.cache)}</small></div>
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
  const choices = windowChoices(economics.data?.retention_hours ?? 24);
  const asked = params.get('window') ?? '24';
  const hours = Number((choices.find(([id]) => id === asked) ?? choices[0])?.[0] ?? '24');
  const requests = usePerfTurns({ last: hours * 3_600_000, n: 1, filter: { local: false }, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone }, 5_000);
  const recorded = requests.data === undefined || isPendingRoute(requests.data) ? null : requests.data;

  if (economics.isPending) return <PageHead title={U.title} lede={U.reading} />;
  if (economics.isError) return <><PageHead title={U.title} /><Fault message={failureText(economics.error)} onRetry={() => void economics.refetch()} /></>;

  const now = Date.now();
  const headRows = heads.data?.heads ?? [];
  const colourOf = colourFromRegistry(status.data);
  const label = (key: string): string => headRows.find((head) => head.key === key)?.label ?? key;
  const sourceHeads = [...new Set([...headRows.map(head => head.key), ...economics.data.heads.map(head => head.key)])];
  const plans = orderPlans(sourceHeads.map(key => {
    const head = economics.data.heads.find(head => head.key === key) ?? { key, label: label(key), ceiling_tokens: null, buckets: [] };
    const plan = planUsage(head, label(key), colourOf(key), usage.data ?? null, hours, now, headRows.find(status => status.key === key));
    const values = recorded?.usageBy?.[key]?.totals;
    const unread = recorded === null || recorded.unread.some(row => row.head === key);
    return { ...plan, turns: unread ? null : values?.requests ?? null, inTokens: values?.input_tokens ?? null, cost: values?.cost_usd ?? null, cache: unread ? null : values?.cache_share ?? null, partial: unread || (values?.missing_input_requests ?? 0) > 0, costPartial: unread || (values?.unpriced_requests ?? 0) > 0, models: recorded?.usageBy?.[key]?.models.flatMap(model => model.key === null ? [] : [model.key]) ?? [], subscription: usage.data?.heads.find(row => row.key === key)?.usage?.quota?.plan, spark: head.buckets.length === 0 ? [] : plan.spark };
  }));
  const { active, idle } = splitIdle(plans);
  const totals = totalsOf(economics.data.heads, hours, now);
  const values = fullWindowUsage(recorded);
  const partial = recorded !== null && recorded.unread.length > 0;
  const measured = (value: number | null | undefined, missing: number): string => value == null ? B.unknown : partial || missing > 0 ? B.atLeast(tokensText(value)) : tokensText(value);
  const cost = values?.cost_usd ?? null;

  return (
    <div className="usage-page">
      <PageHead
        title={U.title}
        lede={usageLede(totals, plans, hours, recorded === null || recorded.unread.length > 0 ? null : recorded.matched, cost, partial || (values?.unpriced_requests ?? 0) > 0)}
        tools={choices.length < 2 ? undefined : <Segmented label={U.window} value={String(hours)} options={choices} onChange={(next) => setParams(next === '24' ? {} : { window: next }, { replace: true })} />}
      />
      {plans.length === 0 ? <Empty title={U.plansNone} /> : (
        <>
          <section className="section" aria-label={U.title}>
            <div className="totals">
              <div><div className="n">{recorded?.matched == null ? B.unknown : recorded.unread.length > 0 ? B.atLeast(fmtInt(recorded.matched)) : fmtInt(recorded.matched)}</div><h3>{U.totalsTurns}</h3><p>{U.totalsTurnsWhy(plans.length, spanWords(hours))}</p></div>
              <div><div className="n">{measured(values?.input_tokens, values?.missing_input_requests ?? 0)}</div><h3>{U.totalsIn}</h3><p>{values?.cache_share == null || partial ? U.totalsInPlain : U.totalsInWhy(fmtShare(values.cache_share))}</p></div>
              <div><div className="n">{measured(values?.output_tokens, values?.missing_output_requests ?? 0)}</div><h3>{U.totalsOut}</h3><p>{U.totalsOutWhy}</p></div>
              <div><div className="n">{cost === null ? ABSENT : partial || (values?.unpriced_requests ?? 0) > 0 ? B.atLeast(costLine(cost)) : costLine(cost)}</div><h3>{U.totalsCost}</h3><p>{cost === null ? U.totalsCostNone : `${U.totalsCostWhy}${(values?.unpriced_requests ?? 0) > 0 ? ` ${U.totalsCostUnpriced(values?.unpriced_requests ?? 0)}` : ''}`}</p></div>
            </div>
          </section>
          <section className="section" aria-labelledby="usage-plans">
            <h2 id="usage-plans">{U.plansTitle}</h2>
            <p className="why">{U.plansWhy}</p>
            {active.length === 0 ? null : <ul className="uplans">{active.map((plan) => <PlanRow key={plan.key} plan={plan} />)}</ul>}
            {idle.length === 0 ? null : <p className="idle-plans">{U.idlePlans(idle.map((plan) => plan.label).join(", "), spanWords(hours))}</p>}
          </section>
          <UsageBreakdown key={hours} labelOf={label} read={requests} />
          <UsagePricing heads={headRows} recorded={recorded} />
          <Budgets plans={plans} />
          <Alerts />
        </>
      )}
    </div>
  );
}
