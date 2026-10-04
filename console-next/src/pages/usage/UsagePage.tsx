import { useSearchParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useStatus, useUsage } from '../../api/queries';
import { useEconomics } from '../../api/usage';
import { useUsageTurns } from '../../api/turns';
import { isPendingRoute } from '../../api/auth';
import { ABSENT, fmtInt, fmtShare } from '../../lib/format';
import { cutLines, reportedWindowUsage, reportedRequestCount } from '../../lib/usage-breakdown';
import { colourFromRegistry } from '../../lib/model';
import { cacheLine, costLine, orderPlans, planUsage, splitIdle, tokensText, totalsOf, usageLede, windowChoices } from '../../lib/usage-page';
import type { PlanUsage } from '../../lib/usage-page';
import { U, spanWords } from '../../lib/words-usage';
import { Q } from '../../lib/words-quota';
import { localZonedInstantText } from '../../lib/heads';
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
        {plan.observations?.map(window => <small key={window.window}>{Q.window(window.window === '5h' ? '5 hours' : 'Week', window.pct, window.resetsAt === null ? null : localZonedInstantText(window.resetsAt), !window.stale)} · {Q.observed(window.observedAt === null ? null : localZonedInstantText(window.observedAt))}</small>)}
      </div>
      <div className="tok">{plan.requestState === 'loading' ? <span role="status">{U.readingRequests}</span> : plan.turns === null ? <span>{U.requestsUnavailable}</span> : plan.turns === 0 ? <span>{U.idle}</span> : <><strong>{plan.partial && plan.inTokens !== null ? B.atLeast(tokensText(plan.inTokens)) : tokensText(plan.inTokens)}</strong>{U.readIn}</>}{plan.requestReason === undefined ? null : <small>{plan.requestReason}</small>}</div>
      {plan.spark.length === 0 ? <span className="hint">{plan.hourlyReason ?? B.unknown}</span> : <Spark values={plan.spark} />}
      <div className="cost">{plan.requestState === 'loading' ? U.readingMetric : plan.costPartial && plan.cost !== null ? B.atLeast(costLine(plan.cost)) : costLine(plan.cost)}<small>{plan.requestState === 'loading' ? U.readingMetric : cacheLine(plan.cache)}</small></div>
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
  const requests = useUsageTurns(hours, Intl.DateTimeFormat().resolvedOptions().timeZone, economics.isSuccess || economics.isError);
  const recorded = requests.data === undefined || isPendingRoute(requests.data) ? null : requests.data;
  const loading = recorded === null && !requests.isError && requests.data === undefined;

  const economicHeads = economics.isError ? [] : economics.data?.heads ?? [];
  const now = Date.now();
  const headRows = heads.data?.heads ?? [];
  const colourOf = colourFromRegistry(status.data);
  const label = (key: string): string => headRows.find((head) => head.key === key)?.label ?? key;
  const sourceHeads = [...new Set([...headRows.map(head => head.key), ...economicHeads.map(head => head.key), ...Object.keys(recorded?.matchedBy ?? {}), ...Object.keys(recorded?.usageBy ?? {}), ...recorded?.pendingHeads ?? [], ...recorded?.unread.map(row => row.head) ?? []])];
  const plans = orderPlans(sourceHeads.map(key => {
    const head = economicHeads.find(head => head.key === key) ?? { key, label: label(key), ceiling_tokens: null, buckets: [] };
    const plan = planUsage(head, label(key), colourOf(key), usage.data ?? null, hours, now, headRows.find(status => status.key === key));
    const values = recorded?.usageBy?.[key]?.totals;
    const reason = recorded?.unread.find(row => row.head === key)?.reason;
    const pending = loading || recorded?.pendingHeads?.includes(key) === true;
    const turns = values?.requests ?? recorded?.matchedBy[key] ?? null;
    const requestState = pending ? 'loading' as const : turns === null ? 'unavailable' as const : 'ready' as const;
    return { ...plan, requestState, ...(reason === undefined ? {} : { requestReason: reason }), turns, inTokens: values?.input_tokens ?? null, cost: values?.cost_usd ?? null, cache: values?.cache_share ?? null, partial: reason !== undefined || (values?.missing_input_requests ?? 0) > 0, costPartial: reason !== undefined || (values?.unpriced_requests ?? 0) > 0, models: recorded?.usageBy?.[key]?.models.flatMap(model => model.key === null ? [] : [model.key]) ?? [], subscription: usage.data?.heads.find(row => row.key === key)?.usage?.quota?.plan, spark: head.buckets.length === 0 ? [] : plan.spark };
  }));
  const { active, idle } = splitIdle(plans);
  const totals = totalsOf(economicHeads, hours, now);
  const values = reportedWindowUsage(recorded);
  const count = reportedRequestCount(recorded);
  const usedCommands = plans.filter(plan => plan.turns !== null && plan.turns > 0).length;
  const awaiting = loading || (recorded?.pendingHeads?.length ?? 0) > 0;
  const partial = recorded !== null && (recorded.unread.length > 0 || (recorded.pendingHeads?.length ?? 0) > 0 || recorded.matched === null || Object.keys(recorded.matchedBy).some(key => recorded.usageBy?.[key] === undefined));
  const measured = (value: number | null | undefined, missing: number): string => value == null ? awaiting ? U.readingMetric : B.unknown : partial || missing > 0 ? B.atLeast(tokensText(value)) : tokensText(value);
  const cost = values?.cost_usd ?? null;
  const lede = count === null && awaiting ? U.reading : requests.isError ? U.requestsUnavailable : usageLede(totals, plans, hours, count, cost, partial || (values?.unpriced_requests ?? 0) > 0, partial);
  const pendingNames = recorded?.pendingHeads?.map(label) ?? [];

  return (
    <div className="usage-page">
      <PageHead
        title={U.title}
        lede={pendingNames.length === 0 ? lede : `${lede} ${U.commandsReading(pendingNames.join(', '))}`}
        tools={choices.length < 2 ? undefined : <Segmented label={U.window} value={String(hours)} options={choices} onChange={(next) => setParams(next === '24' ? {} : { window: next }, { replace: true })} />}
      />
      {economics.isError ? <section className="section" aria-label={B.hourlyHistory}><Fault message={B.hourlyUnavailable} onRetry={() => void economics.refetch()} /></section>
        : economics.isPending ? <p className="hint" role="status">{B.hourlyReading}</p> : null}
      {plans.length === 0 ? loading || heads.isPending ? <p className="hint" role="status">{U.readingRequests}</p> : heads.isError ? <Fault message={failureText(heads.error)} onRetry={() => void heads.refetch()} /> : <Empty title={U.plansNone} /> : (
        <>
          <section className="section" aria-label={U.title}>
            <div className="totals">
              <div><div className="n">{count === null ? awaiting ? U.readingMetric : B.unknown : partial ? B.atLeast(fmtInt(count)) : fmtInt(count)}</div><h3>{U.totalsTurns}</h3><p>{count === null && awaiting ? U.totalsTurnsReading : U.totalsTurnsWhy(usedCommands, spanWords(hours), partial)}</p></div>
              <div><div className="n">{measured(values?.input_tokens, values?.missing_input_requests ?? 0)}</div><h3>{U.totalsIn}</h3><p>{values?.cache_share == null || partial ? U.totalsInPlain : U.totalsInWhy(fmtShare(values.cache_share))}{cutLines(values?.cut_source_rounds ?? 0).map(line => ` ${line}`).join('')}</p></div>
              <div><div className="n">{measured(values?.output_tokens, values?.missing_output_requests ?? 0)}</div><h3>{U.totalsOut}</h3><p>{U.totalsOutWhy}</p></div>
              <div><div className="n">{cost === null ? awaiting ? U.readingMetric : ABSENT : partial || (values?.unpriced_requests ?? 0) > 0 ? B.atLeast(costLine(cost)) : costLine(cost)}</div><h3>{U.totalsCost}</h3><p>{cost === null ? awaiting ? U.readingRequests : U.totalsCostNone : `${U.totalsCostWhy}${(values?.unpriced_requests ?? 0) > 0 ? ` ${U.totalsCostUnpriced(values?.unpriced_requests ?? 0)}` : ''}`}</p></div>
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
