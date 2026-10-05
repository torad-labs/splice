import { useState } from 'react';
import { Link } from 'react-router';
import { isPendingRoute } from '../../api/auth';
import { failureText } from '../../api/client';
import { usePerfTurns } from '../../api/turns';
import { fmtTokens, fmtUsd } from '../../lib/format';
import { cutLines, fullUsageBreakdown, fullWindowUsage, priceGapLines } from '../../lib/usage-breakdown';
import type { UsageBreakdown as Breakdown, UsageDimension } from '../../lib/usage-breakdown';
import type { TurnsState } from '../../types/perf';
import { Button, Empty, Fault, Segmented } from '../../ui';
import { B } from './copy';
import './usage-breakdown.css';

const dimensions = [['model', B.model], ['account', B.account], ['day', B.day]] as const;

export function requestsFor(item: Breakdown, by: UsageDimension, since: number, until: number): string {
  let from = since;
  let to = until;
  if (by === 'day' && item.key !== null) {
    const [year = 0, month = 0, day = 0] = item.key.split('-').map(Number);
    from = Math.max(since, new Date(year, month - 1, day).getTime());
    to = Math.min(until, new Date(year, month - 1, day + 1).getTime());
  }
  const query = new URLSearchParams({ since: String(from), until: String(to) });
  if (by === 'account' && item.head !== null) query.set('head', item.head);
  if (item.key === null) query.set('unattributed', by);
  else query.set(by, item.key);
  return '/requests?' + query.toString();
}

const titleOf = (item: Breakdown, by: UsageDimension): string =>
  item.key ?? (by === 'account' ? B.unreportedAccount : B.unreportedModel);
const amount = (item: Breakdown): string => item.cost === null ? B.unknown : item.unpriced === 0 ? fmtUsd(item.cost) : B.atLeast(fmtUsd(item.cost));
const tokens = (value: number | null, missing: number): string => value === null ? B.unknown : missing === 0 ? fmtTokens(value) : B.atLeast(fmtTokens(value));

export function UsageValues({ items, by, since, until, labelOf }: { items: readonly Breakdown[]; by: UsageDimension; since: number; until: number; labelOf: (head: string) => string }) {
  const [table, setTable] = useState(false);
  const maximum = Math.max(...items.map(item => item.cost ?? 0), 0) || 1;
  if (items.length === 0) return <Empty title={B.none} />;
  const entries = items.map(item => {
    const title = titleOf(item, by);
    const name = by === 'account' && item.head !== null ? `${title} · ${labelOf(item.head)}` : title;
    const href = requestsFor(item, by, since, until);
    return {
      id: item.id,
      name: <div className="usage-name"><Link to={href}>{name}</Link><small>{B.requestCount(item.turns)}</small></div>,
      cost: <div className="usage-spend">
        <Link to={href} className="usage-cost-link" aria-label={`${B.inspect(name)}: ${amount(item)}`} title={`${name}: ${amount(item)}`}>
          <strong>{amount(item)}</strong>
          {table || item.cost === null ? null : <span className="usage-spend-track" aria-hidden="true"><i style={{ width: `${item.cost / maximum * 100}%` }} /></span>}
        </Link>
        {priceGapLines(item.gaps).map(line => <small key={line}>{line}</small>)}
      </div>,
      input: <div className="usage-token" role="group" aria-label={B.input}><span className="usage-token-label">{B.input}</span><strong>{tokens(item.input, item.missingInput)}</strong>{item.missingInput === 0 ? null : <small>{B.inputMissing(item.missingInput)}</small>}{cutLines(item.cut).map(line => <small key={line}>{line}</small>)}</div>,
      output: <div className="usage-token" role="group" aria-label={B.output}><span className="usage-token-label">{B.output}</span><strong>{tokens(item.output, item.missingOutput)}</strong>{item.missingOutput === 0 ? null : <small>{B.outputMissing(item.missingOutput)}</small>}</div>,
    };
  });
  const dimension = dimensions.find(([key]) => key === by)?.[1];
  return <>
    <Button small kind="quiet" onClick={() => setTable(!table)}>{table ? B.plot : B.table}</Button>
    {!table && items.some(item => (item.cost ?? 0) > 0) ? <p className="hint">{B.barWhy}</p> : null}
    <div className="usage-values" data-table={table}>
      {table ? <table>
        <caption className="sr">{B.title} · {dimension}</caption>
        <thead><tr><th scope="col">{dimension}</th><th scope="col">{B.amount}</th><th scope="col">{B.input}</th><th scope="col">{B.output}</th></tr></thead>
        <tbody>{entries.map(entry => <tr key={entry.id}><th scope="row">{entry.name}</th><td>{entry.cost}</td><td>{entry.input}</td><td>{entry.output}</td></tr>)}</tbody>
      </table> : <>
        <div className="usage-values-head" aria-hidden="true"><span>{dimension}</span><span>{B.amount}</span><span>{B.input}</span><span>{B.output}</span></div>
        <ul>{entries.map(entry => <li key={entry.id}>{entry.name}{entry.cost}{entry.input}{entry.output}</li>)}</ul>
      </>}
    </div>
  </>;
}

function Coverage({ data, labelOf }: { data: TurnsState; labelOf: (head: string) => string }) {
  if (data.unread.length === 0 && (data.pendingHeads?.length ?? 0) === 0) return null;
  return <div className="usage-coverage" role="status"><b>{B.partial}</b>{data.unread.map(row => <p key={row.head}>{labelOf(row.head)}: {row.reason}</p>)}</div>;
}

export function UsageBreakdown({ labelOf, read }: {
  labelOf: (head: string) => string; read: ReturnType<typeof usePerfTurns>;
}) {
  const [by, setBy] = useState<UsageDimension>('model');
  const data = read.data === undefined || isPendingRoute(read.data) ? null : read.data;
  const window = data?.window;
  const items = data === null ? null : fullUsageBreakdown(data, by);
  return (
    <section className="section usage-breakdown" aria-labelledby="usage-breakdown">
      <div className="usage-breakdown-head"><div><h2 id="usage-breakdown">{B.title}</h2><p className="why">{B.why}</p></div><Segmented label={B.group} options={dimensions} value={by} onChange={setBy} /></div>
      {by === 'account' ? <p className="hint">{B.accountWhy}</p> : by === 'day' ? <p className="hint">{B.dayWhy}</p> : null}
      {read.isError ? <Fault message={failureText(read.error)} onRetry={() => void read.refetch()} />
        : read.isPending ? <p className="hint">{B.reading}</p>
        : data === null || window === undefined || window.until === null ? <p className="hint">{B.unavailable}</p> : <>
          <Coverage data={data} labelOf={labelOf} />
          {(data.pendingHeads?.length ?? 0) > 0 ? <p className="hint">{B.reading}</p> : null}
          {items === null ? <p className="hint">{B.unavailable}</p> : items.length === 0 && fullWindowUsage(data) === null ? null : <UsageValues items={items} by={by} since={window.since} until={window.until} labelOf={labelOf} />}
        </>}
    </section>
  );
}
