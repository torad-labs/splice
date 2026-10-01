import { Link, useSearchParams } from 'react-router';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { failureText } from '../../api/client';
import { useHeads, useLiveTurns, useSessions, useStopTurn } from '../../api/queries';
import { isPendingRoute } from '../../api/auth';
import { usePerfSummary, usePerfTurns } from '../../api/turns';
import { ABSENT, fmtInt, fmtTokens } from '../../lib/format';
import { sessionLabel } from '../../lib/sessions';
import {
  WINDOW_MS, barMax, cacheText, colourFromHeads, filterLines, liveTurnFor, lineOf, newestFirst, planRows, runningOf, secondsText, localStepsOf, servedLocally, tookText, turnsLede,
} from '../../lib/turns-page';
import type { TurnFilter, TurnLine, PlanRow, RunningLine } from '../../lib/turns-page';
import { T } from '../../lib/words-turns';
import { PERF_WINDOWS } from '../../types/perf';
import type { PerfWindowLabel } from '../../types/perf';
import { Button, Empty, Fault, PageHead, SearchField, Segmented, State, Window, WindowBar } from '../../ui';
import { S } from '../shared/copy';
import './turns.css';

const windowOf = (raw: string | null): PerfWindowLabel => PERF_WINDOWS.find((label) => label === raw) ?? '1h';

export function turnPath(line: Pick<TurnLine, 'head' | 'ts'>): string {
  return `/turns/${encodeURIComponent(line.head)}/${line.ts}`;
}

/** Stops the turn a running card stands for. A card whose turn the daemon no longer lists has nothing to stop, so it offers nothing. */
function StopRunning({ turn }: { turn: RunningLine }) {
  const live = useLiveTurns(turn.head);
  const stop = useStopTurn();
  const target = liveTurnFor(turn, live.data?.turns ?? []);
  if (target === null) return null;
  return (
    <div className="acts">
      <Button small disabled={stop.isPending} onClick={() => stop.mutate({ head: turn.head, id: target.id })}>{stop.isPending ? S.stopping : S.stopTurn}</Button>
      {stop.isError ? <span className="hint" role="alert">{S.stopFailed} {failureText(stop.error)}</span> : null}
    </div>
  );
}

export function RunningCard({ turn, act }: { turn: RunningLine; act?: ReactNode }) {
  const say = turn.phase === 'streaming' ? T.streaming : T.connecting;
  return (
    <Window as="li" colour={turn.colour} attention={turn.stuck} aria-label={`${turn.title}, ${turn.plan}`}>
      <WindowBar title={turn.title}>
        <State tone={turn.stuck ? 'stuck' : 'work'}>{turn.stuck ? 'Stuck' : 'Working'}</State>
      </WindowBar>
      <p className="say"><b>{turn.plan}</b>{turn.model === null ? '' : ` · ${turn.model}`}{turn.quiet === null ? ` · ${say}` : ''} · {T.runningFor(turn.age)}</p>
      {turn.quiet === null ? null : <p className="say">{T.quietFor(turn.quiet)}</p>}
      {act}
    </Window>
  );
}

function PlanLine({ row, max }: { row: PlanRow; max: number }) {
  const slow = row.firstP95 ?? row.firstP50;
  return (
    <li className={`plan hue ${row.colour}`}>
      <b><i />{row.label}</b>
      <span className="n">{fmtInt(row.turns)}</span>
      <span className={`n${row.failed > 0 ? ' bad' : ''}`}>{fmtInt(row.failed)}</span>
      {row.firstP50 === null || slow === null ? (
        <span className="plan-first">{ABSENT}</span>
      ) : (
        <div className="plan-first">
          <div className="bar2" role="img" aria-label={`${T.typical} ${secondsText(row.firstP50)}, ${T.slowest} ${secondsText(slow)}`}>
            <i style={{ width: `${(slow / max) * 100}%` }} />
            <u style={{ width: `${(row.firstP50 / max) * 100}%` }} />
          </div>
          <span><b>{secondsText(row.firstP50)}</b> {T.typical} · {secondsText(slow)} {T.slowest}</span>
        </div>
      )}
      <small>{row.cache === null ? ABSENT : T.cached(cacheText(row.cache))}</small>
    </li>
  );
}

export function TurnRowView({ line }: { line: TurnLine }) {
  const clock = new Date(line.ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  return (
    <li className={`turn hue ${line.colour}${line.outcome.failed ? ' failed' : ''}`}>
      <span className="t">{clock}</span>
      <div>
        <h3><Link to={turnPath(line)}>{line.title}</Link></h3>
        <div className="sub">
          <span>{line.plan}{line.model === null ? null : ` · ${line.model}`}</span>
          {line.tag === null ? null : <span className="tag">{line.tag}</span>}
        </div>
      </div>
      <State tone={line.outcome.tone}>{line.outcome.word}</State>
      <span className="took">{tookText(line.tookMs)}</span>
      <span className="tok">
        {line.inTokens === null ? ABSENT : `${fmtTokens(line.inTokens)} ${T.in}`}
        <small>{line.outTokens === null ? `– ${T.out}` : `${fmtTokens(line.outTokens)} ${T.out}`}</small>
      </span>
      <span className="cost">{line.cost === ABSENT ? T.notPriced : line.cost}</span>
    </li>
  );
}

/** How the plans are answering, and every turn that finished: each opens its own page. */
export function TurnsPage() {
  const [params, setParams] = useSearchParams();
  const window = windowOf(params.get('window'));
  const [filter, setFilter] = useState<TurnFilter>('all');
  const [query, setQuery] = useState('');
  const summary = usePerfSummary(window);
  const turns = usePerfTurns();
  const heads = useHeads();
  const sessions = useSessions();

  const headRows = heads.data?.heads ?? [];
  const planLabel = (key: string): string => headRows.find((head) => head.key === key)?.label ?? key;
  const colourOf = colourFromHeads(headRows);
  const sessionRows = sessions.data?.sessions ?? [];
  const titleOf = (sessionId: string | undefined, short: string | undefined): string | null => {
    const row = sessionRows.find((candidate) => (sessionId !== undefined && candidate.session_id === sessionId) || (short !== undefined && candidate.session_id?.startsWith(short) === true));
    return row === undefined ? null : sessionLabel(row);
  };

  const plans = planRows(summary.data?.heads ?? [], colourOf);
  const pick = (next: PerfWindowLabel): void => setParams(next === '1h' ? {} : { window: next }, { replace: true });
  const tools = <Segmented label={T.window} value={window} options={T.windows} onChange={pick} />;

  if (turns.isPending && summary.isPending) return <PageHead title={T.title} lede={T.reading} />;
  if (turns.isError && turns.data === undefined) return <><PageHead title={T.title} tools={tools} /><Fault message={failureText(turns.error)} onRetry={() => void turns.refetch()} /></>;
  const slice = turns.data;
  if (slice === undefined || isPendingRoute(slice)) return <><PageHead title={T.title} tools={tools} /><Empty title={T.none} why={T.noneWhy} /></>;

  const since = Date.now() - WINDOW_MS[window];
  const inWindow = slice.landed.filter((row) => row.ts >= since);
  const local = localStepsOf(summary.data?.heads ?? []);
  const all = newestFirst(inWindow.filter((row) => !servedLocally(row)).map((row) => lineOf(row, planLabel, colourOf, titleOf)));
  const lines = filterLines(all, filter, query);
  const running = runningOf(slice.inflight, planLabel, colourOf, (prefix) => titleOf(undefined, prefix));
  const quiet = running.filter((turn) => turn.stuck);
  const held = plans.reduce((n, row) => n + row.turns, 0);
  const stuck = quiet[0];

  return (
    <>
      <PageHead title={T.title} lede={turnsLede(plans, window)} tools={tools} />
      <section className="section" aria-labelledby="turns-running">
        <h2 id="turns-running">{T.runningTitle}</h2>
        <p className="why">{running.length === 0 ? T.runningNone : `${T.runningWhy}${stuck === undefined || stuck.quiet === null ? '' : ` ${T.runningQuiet(quiet.length, stuck.quiet)}`}`}</p>
        {running.length === 0 ? null : <ul className="running-list">{running.map((turn) => <RunningCard key={turn.key} turn={turn} act={<StopRunning turn={turn} />} />)}</ul>}
      </section>
      <section className="section" aria-labelledby="turns-plans">
        <h2 id="turns-plans">{T.plansTitle}</h2>
        <p className="why">{plans.length === 0 ? T.plansNone : T.plansWhy}</p>
        {plans.length === 0 ? null : (
          <ul className="plans">
            <li className="plan cols" aria-hidden="true"><span>{T.colPlan}</span><span>{T.colTurns}</span><span>{T.colFailed}</span><span>{T.colFirstWord}</span><span /></li>
            {plans.map((row) => <PlanLine key={row.key} row={row} max={barMax(plans)} />)}
          </ul>
        )}
      </section>
      <section className="section" aria-labelledby="turns-finished">
        <h2 id="turns-finished">{T.finishedTitle}</h2>
        <p className="why">{T.finishedWhy}</p>
        <div className="filters">
          <Segmented label={T.filterLabel} value={filter} options={T.filters} onChange={setFilter} />
          <SearchField value={query} onChange={setQuery} label={T.find} hint={T.find} />
        </div>
        {lines.length === 0 ? <Empty title={T.none} why={T.noneWhy} /> : <ul className="list">{lines.map((line) => <TurnRowView key={line.key} line={line} />)}</ul>}
        {local > 0 && filter === 'all' && query === '' ? <p className="plan-foot">{T.localLeftOut(local)}</p> : null}
        {held > all.length && filter === 'all' && query === '' ? <p className="plan-foot">{T.shownOf(all.length, held)}</p> : null}
      </section>
      {slice.unread.length === 0 && slice.truncated.length === 0 ? null : (
        <section className="unread" aria-label={T.unreadTitle}>
          <h3>{T.unreadTitle}</h3>
          <ul>
            {slice.unread.map((row) => <li key={`u-${row.head}`}>{T.unread(planLabel(row.head), row.reason)}</li>)}
            {slice.truncated.map((row) => <li key={`t-${row.head}`}>{T.clamped(planLabel(row.head))}</li>)}
          </ul>
        </section>
      )}
    </>
  );
}
