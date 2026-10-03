import { useSearchParams } from 'react-router';
import { isPendingRoute } from '../../api/auth';
import { failureText } from '../../api/client';
import { useModels } from '../../api/models';
import { useAccounts, useHeads, useStatus } from '../../api/queries';
import { fmtTokens } from '../../lib/format';
import { MODEL_SORTS, modelRows, priceText, searchRows, sortRows, sortedBy, tableSearchOf, tableViewOf } from '../../lib/model-table';
import type { LongContext, ModelSort, ModelTableRow, ModelTableView, ServedBy } from '../../lib/model-table';
import { Chevron, Fault, SearchField } from '../../ui';
import { MT } from './copy';
import './models.css';

const NUMERIC: ReadonlySet<ModelSort> = new Set(['window', 'input', 'output']);

function accountWords(served: ServedBy): string {
  switch (served.kind) {
    case 'key': return MT.key;
    case 'local': return MT.local;
    case 'unreported': return MT.unreported;
    case 'signedOut': return MT.signedOut;
    case 'login': {
      const name = served.name ?? (served.plan === null ? MT.signedIn : MT.onPlan(served.plan));
      return served.others === 0 ? name : MT.others(name, served.others);
    }
  }
}

function Price({ usd, long, side }: { usd: number | null; long: LongContext | null; side: 'input' | 'output' }) {
  if (usd === null) return <span className="none">{MT.noPrice}</span>;
  return (
    <>
      {priceText(usd)}
      {long === null ? null : <small>{MT.above(priceText(long[side]), fmtTokens(long.over))}</small>}
    </>
  );
}

function Row({ row }: { row: ModelTableRow }) {
  return (
    <tr>
      <th scope="row">
        <b>{row.label}</b>
        {row.pinned ? <span className="chip">{MT.pinned}</span> : null}
        {row.resolved ? null : <span className="chip warn">{MT.unresolved}</span>}
        {row.label === row.id ? null : <code>{row.id}</code>}
      </th>
      <td>{row.command}</td>
      <td>{accountWords(row.servedBy)}</td>
      <td className="num">{row.window === null ? <span className="none">{MT.noWindow}</span> : fmtTokens(row.window)}</td>
      <td className="num"><Price usd={row.input} long={row.longContext} side="input" /></td>
      <td className="num"><Price usd={row.output} long={row.longContext} side="output" /></td>
    </tr>
  );
}

function Header({ sort, view, go }: { sort: ModelSort; view: ModelTableView; go: (next: ModelTableView) => void }) {
  const active = view.sort === sort;
  const label = MT.columns[sort];
  return (
    <th scope="col" className={NUMERIC.has(sort) ? 'num' : undefined} aria-sort={active ? (view.direction === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" aria-label={MT.sortBy(label)} onClick={() => go(sortedBy(view, sort))}>
        {label}
        <Chevron className={active ? `sort-mark ${view.direction}` : 'sort-mark idle'} aria-hidden="true" />
      </button>
    </th>
  );
}

/** Every model every command serves, in one table the operator searches and sorts; the view lives in the address. */
export function ModelTable() {
  const [params, setParams] = useSearchParams();
  const view = tableViewOf(params);
  const go = (next: ModelTableView): void => setParams(tableSearchOf(next), { replace: true });
  const models = useModels();
  const heads = useHeads();
  const accounts = useAccounts();
  const status = useStatus();

  const head = (
    <div className="model-table-head">
      <h2 id="every-model">{MT.title}</h2>
      <SearchField value={view.query} onChange={(query) => go({ ...view, query })} label={MT.search} hint={MT.searchHint} />
    </div>
  );
  if (models.isError) return <section className="section" aria-labelledby="every-model">{head}<Fault message={failureText(models.error)} onRetry={() => void models.refetch()} /></section>;
  if (models.data === undefined) return <section className="section" aria-labelledby="every-model">{head}<p className="why">{MT.reading}</p></section>;
  if (isPendingRoute(models.data)) return <section className="section" aria-labelledby="every-model">{head}<p className="why">{MT.unserved}</p></section>;

  const families = new Map(status.data?.registry.map((entry) => [entry.key, entry.family] as const) ?? []);
  const all = modelRows(models.data.heads, heads.data?.heads ?? [], accounts.data?.accounts ?? [], families);
  const rows = sortRows(searchRows(all, view.query), view.sort, view.direction);
  const said = view.query.trim() === ''
    ? MT.why(MT.models(all.length), MT.commands(models.data.heads.length))
    : MT.shown(rows.length.toLocaleString(), all.length.toLocaleString());

  return (
    <section className="section" aria-labelledby="every-model">
      {head}
      <p className="why">{said}</p>
      {rows.length === 0 && view.query.trim() !== '' ? <p className="why">{MT.none(view.query.trim())}</p> : (
        <div className="model-scroll" role="region" aria-label={MT.table} tabIndex={0}>
          <table className="model-table">
            <thead>
              <tr>{MODEL_SORTS.map((sort) => <Header key={sort} sort={sort} view={view} go={go} />)}</tr>
            </thead>
            <tbody>
              {rows.map((row) => <Row key={row.key} row={row} />)}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
