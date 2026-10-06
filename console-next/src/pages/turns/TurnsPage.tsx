import { Link, useSearchParams } from 'react-router';
import { Fragment } from 'react';
import type { ReactNode } from 'react';
import { failureText } from '../../api/client';
import { useHeads, useLiveTurns, useSessions, useStatus, useStopTurn } from '../../api/queries';
import { isPendingRoute } from '../../api/auth';
import { SUMMARY_POLL_MS, TURNS_POLL_MS, usePerfSummary, usePerfTurns } from '../../api/turns';
import { ABSENT, fmtInt, fmtTokens } from '../../lib/format';
import { sessionLabel } from '../../lib/sessions';
import { colourFromRegistry } from '../../lib/model';
import { askOf, narrowed, requestsHref, searchOf, SELECTORS, viewOf } from '../../lib/requests-view';
import type { RequestsView, Selector } from '../../lib/requests-view';
import { barMax, cacheText, liveTurnFor, linesOf, newestFirst, pageLede, planRows, runningOf, secondsText, localStepsOf, tookText } from '../../lib/turns-page';
import type { TurnLine, PlanRow, RunningLine } from '../../lib/turns-page';
import { T } from '../../lib/words-turns';
import type { PerfWindowLabel } from '../../types/perf';
import { Button, Close, Empty, Fault, PageHead, Segmented, State, Window, WindowBar } from '../../ui';
import { S } from '../shared/copy';
import './turns.css';

/** The most finished requests the list draws, newest first across every command; the count says how many matched. */
const LIST_CAP = 200;

export function turnPath(line: Pick<TurnLine, 'head' | 'ts'>): string {
  return `/requests/${encodeURIComponent(line.head)}/${line.ts}`;
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
    <Window as="li" colour={turn.colour} aria-label={`${turn.title}, ${turn.plan}`}>
      <WindowBar title={turn.title}>
        <State tone="work">{T.working}</State>
      </WindowBar>
      <p className="say"><b>{turn.plan}</b>{turn.model === null ? '' : ` · ${turn.model}`}{turn.quiet === null ? ` · ${say}` : ''} · {T.runningFor(turn.age)}</p>
      {turn.quiet === null ? null : <p className="say">{T.quietFor(turn.quiet)}</p>}
      {act}
    </Window>
  );
}

function PlanLine({ row, max, failedHref }: { row: PlanRow; max: number; failedHref: string }) {
  const slow = row.firstP95 ?? row.firstP50;
  return (
    <li className={`plan hue ${row.colour}`}>
      <b><i />{row.label}</b>
      <span className="n">{fmtInt(row.turns)}</span>
      {row.failed > 0 ? <Link className="n bad" to={failedHref} aria-label={T.failedOf(row.failed, row.label)}>{fmtInt(row.failed)}</Link> : <span className="n">{fmtInt(0)}</span>}
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

/** Each part of a row that names a command, a model or an account opens the requests narrowed to it. */
export type NarrowTo = (selector: Selector, value: string) => string;

export function TurnRowView({ line, narrow }: { line: TurnLine; narrow: NarrowTo }) {
  const clock = new Date(line.ts).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const parts: [Selector, string, string][] = [['head', line.head, line.plan]];
  if (line.model !== null) parts.push(['model', line.model, line.model]);
  if (line.account !== null) parts.push(['account', line.account, line.account]);
  return (
    <li className={`turn hue ${line.colour}${line.outcome.failed ? ' failed' : ''}`}>
      <span className="t">{clock}</span>
      <div>
        <h3><Link to={turnPath(line)}>{line.title}</Link></h3>
        <div className="sub">
          <span>
            {parts.map(([selector, value, text], i) => (
              <Fragment key={selector}>{i === 0 ? null : ' · '}<Link to={narrow(selector, value)}>{text}</Link></Fragment>
            ))}
          </span>
          {line.tags.map((tag) => <span key={tag} className="tag">{tag}</span>)}
          {line.session === null ? null : <Link className="same" to={narrow('session', line.session)}>{T.sameSession}</Link>}
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

/** What the list is narrowed to, each part one click from the wider list. */
function Narrowing({ view, labelOf }: { view: RequestsView; labelOf: (head: string) => string }) {
  const chips: { key: string; label: string; value: string; wider: RequestsView }[] = SELECTORS.flatMap((selector) => {
    const value = view[selector];
    return value === null ? [] : [{ key: selector, label: T.selector[selector], value: selector === 'head' ? labelOf(value) : value, wider: { ...view, [selector]: null } }];
  });
  if (view.unattributed !== null) chips.push({ key: 'unattributed', label: T.unattributed[view.unattributed], value: '', wider: { ...view, unattributed: null } });
  if (chips.length === 0) return null;
  return (
    <ul className="narrowing" aria-label={T.narrowedLabel}>
      {chips.map((chip) => (
        <li key={chip.key}>
          <Link to={requestsHref(chip.wider)} aria-label={T.widen(`${chip.label} ${chip.value}`.trim())}>
            <span>{chip.label}</span>
            {chip.value === '' ? null : <b>{chip.value}</b>}
            <Close />
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** How the commands are answering, and every request that finished, narrowed by the address: each opens its own page. */
export function TurnsPage() {
  const [params, setParams] = useSearchParams();
  const view = viewOf(params);
  const go = (next: RequestsView): void => setParams(searchOf(next), { replace: true });
  const window = view.range.kind === 'last' ? view.range.window : null;
  const closed = view.range.kind === 'span' && view.range.until !== null && view.range.until <= Date.now();
  const readable = view.unread === null;
  const summary = usePerfSummary(window ?? '1h', readable && window !== null);
  const turns = usePerfTurns(
    { ...askOf(view), live: window === '1h' || window === '24h' },
    closed ? false : window === '7d' ? SUMMARY_POLL_MS : TURNS_POLL_MS,
    readable,
  );
  const heads = useHeads();
  const status = useStatus();
  const sessions = useSessions();

  const headRows = heads.data?.heads ?? [];
  const planLabel = (key: string): string => headRows.find((head) => head.key === key)?.label ?? key;
  const colourOf = colourFromRegistry(status.data);
  const sessionRows = sessions.data?.sessions ?? [];
  const titleOf = (sessionId: string | undefined, short: string | undefined): string | null => {
    const row = sessionRows.find((candidate) => (sessionId !== undefined && candidate.session_id === sessionId) || (short !== undefined && candidate.session_id?.startsWith(short) === true));
    return row === undefined ? null : sessionLabel(row);
  };

  const pick = (next: PerfWindowLabel): void => go({ ...view, range: { kind: 'last', window: next } });
  // A span has no window pressed; choosing one returns to a rolling window.
  const tools = <Segmented<PerfWindowLabel | 'span'> label={T.window} value={window ?? 'span'} options={T.windows} onChange={(next) => { if (next !== 'span') pick(next); }} />;
  if (view.unread !== null) return <><PageHead title={T.title} tools={tools} /><Fault message={T.linkUnread(view.unread.param, view.unread.value)} /></>;

  const plans = summary.data === undefined ? null : planRows(summary.data.heads, colourOf);
  const slice = turns.data === undefined || isPendingRoute(turns.data) ? undefined : turns.data;
  const matched = turns.isPending ? undefined : (slice?.matched ?? null);
  const lede = window !== null && summary.isError && plans === null ? undefined : pageLede(view, plans, matched, summary.data?.time_before_first_byte_ms);
  const narrow = (selector: Selector, value: string): string => requestsHref({ ...view, [selector]: value });
  const lines = slice === undefined ? [] : newestFirst(linesOf(slice.landed, planLabel, colourOf, titleOf)).slice(0, LIST_CAP);
  const running = slice === undefined ? [] : runningOf(slice.inflight, planLabel, colourOf, (prefix) => titleOf(undefined, prefix));
  const quiet = running.filter((turn) => turn.longQuiet);
  const longestQuiet = quiet[0];
  const local = localStepsOf(summary.data?.heads ?? []);

  return (
    <>
      <PageHead title={T.title} {...(lede === undefined ? {} : { lede })} tools={tools} />
      {window === null ? null : (
        <section className="section" aria-labelledby="turns-running">
          <h2 id="turns-running">{T.runningTitle}</h2>
          <p className="why">{turns.isPending ? T.reading : running.length === 0 ? T.runningNone : `${T.runningWhy}${longestQuiet === undefined || longestQuiet.quiet === null ? '' : ` ${T.runningQuiet(quiet.length, longestQuiet.quiet)}`}`}</p>
          {running.length === 0 ? null : <ul className="running-list">{running.map((turn) => <RunningCard key={turn.key} turn={turn} act={<StopRunning turn={turn} />} />)}</ul>}
        </section>
      )}
      {window === null ? null : (
        <section className="section" aria-labelledby="turns-plans">
          <h2 id="turns-plans">{T.plansTitle}</h2>
          {summary.isError && plans === null ? <Fault message={failureText(summary.error)} onRetry={() => void summary.refetch()} /> : <p className="why">{plans === null ? T.reading : plans.length === 0 ? T.plansNone : T.plansWhy}</p>}
          {plans === null || plans.length === 0 ? null : (
            <ul className="plans">
              <li className="plan cols" aria-hidden="true"><span>{T.colPlan}</span><span>{T.colTurns}</span><span>{T.colFailed}</span><span>{T.colFirstWord}</span><span /></li>
              {plans.map((row) => <PlanLine key={row.key} row={row} max={barMax(plans)} failedHref={requestsHref({ ...view, status: 'failed', head: row.key })} />)}
            </ul>
          )}
        </section>
      )}
      <section className="section" aria-labelledby="turns-finished">
        <h2 id="turns-finished">{T.finishedTitle}</h2>
        <p className="why">{narrowed(view) && typeof matched === 'number' ? T.matching(matched) : T.finishedWhy}</p>
        <div className="filters">
          <Segmented label={T.filterLabel} value={view.status} options={T.filters} onChange={(next) => go({ ...view, status: next })} />
          <Narrowing view={view} labelOf={planLabel} />
        </div>
        {turns.isPending ? <p className="why" role="status">{T.reading}</p>
          : turns.isError && slice === undefined ? <Fault message={failureText(turns.error)} onRetry={() => void turns.refetch()} />
            : lines.length === 0 ? <Empty title={T.none} why={T.noneWhy} />
              : <ul className="list">{lines.map((line) => <TurnRowView key={line.key} line={line} narrow={narrow} />)}</ul>}
        {typeof matched === 'number' && matched > lines.length && lines.length > 0 ? <p className="plan-foot">{T.shownOf(lines.length, matched)}</p> : null}
        {window !== null && local > 0 && !narrowed(view) ? <p className="plan-foot">{T.localLeftOut(local)}</p> : null}
      </section>
      {slice === undefined || slice.unread.length === 0 ? null : (
        <section className="unread" aria-label={T.unreadTitle}>
          <h3>{T.unreadTitle}</h3>
          <ul>
            {slice.unread.map((row) => <li key={`u-${row.head}`}>{T.unread(planLabel(row.head), row.reason)}</li>)}
          </ul>
        </section>
      )}
    </>
  );
}
