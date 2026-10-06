import { Link, useParams, useSearchParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useSessions, useStatus } from '../../api/queries';
import { usePerfTurns } from '../../api/turns';
import { ABSENT, fmtInt, fmtUsd } from '../../lib/format';
import { sessionLabel } from '../../lib/sessions';
import { colourFromRegistry } from '../../lib/model';
import { STAGE_ORDER, movedOf, outcomeOf, servedLocally, stagesOf, secondsText, turnCostWhy, turnLede } from '../../lib/turns-page';
import { P, T } from '../../lib/words-turns';
import { Empty, Fault, PageHead, State } from '../../ui';
import { sessionPath } from '../shared/SessionActions';
import { Failure, KeptTabs } from './KeptTabs';
import './turn.css';

/** One turn on its own page: where its time went, what it moved, and what was kept of it. */
export function TurnPage() {
  const { head = '', ts = '' } = useParams();
  const [params] = useSearchParams();
  const at = Number(ts);
  const turns = usePerfTurns({ head, n: 1, since: Number.isFinite(at) ? at : 0, until: Number.isFinite(at) ? at + 1 : 0 }, false);
  const heads = useHeads();
  const status = useStatus();
  const sessions = useSessions();
  const crumb = <div className="crumb"><Link to="/requests">{P.back}</Link></div>;

  if (turns.isError && turns.data === undefined) return <>{crumb}<Fault message={failureText(turns.error)} onRetry={() => void turns.refetch()} /></>;
  if (turns.isPending) return <>{crumb}<PageHead title={T.title} lede={T.reading} /></>;
  const slice = turns.data;
  const row = 'landed' in slice ? slice.landed.find((candidate) => candidate.ts === at) : undefined;
  if (row === undefined) return <>{crumb}<PageHead title={T.title} /><Empty title={P.gone} why={P.goneWhy} /></>;

  const headRow = heads.data?.heads.find((candidate) => candidate.key === head);
  const plan = headRow?.label ?? head;
  const colour = colourFromRegistry(status.data)(head);
  const session = (sessions.data?.sessions ?? []).find((candidate) => candidate.session_id !== null && (candidate.session_id === row.session_id || (row.session !== undefined && candidate.session_id.startsWith(row.session))));
  const title = session === undefined ? plan : sessionLabel(session);
  const outcome = outcomeOf(row.outcome, row.refused_runtime_port, row.cause);
  const stages = stagesOf(row);
  const painted = stages.filter(stage => stage.ms > 0);
  const moved = movedOf(row);
  const posted = (row.upstream_req_bytes ?? 0) > 0;
  const when = new Date(row.ts).toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

  return (
    <div className={`hue ${colour}`}>
      <div className="crumb"><Link to="/requests">{P.back}</Link><span>/</span><span>{when}</span></div>
      <header className="page-head hero">
        <div>
          <h1>{title}</h1>
          <p className="lede">{turnLede(row, stages)}</p>
          <Failure row={row} />
          <div className="facts">
            {servedLocally(row) ? <span className="tag">{T.servedLocallyTag}</span> : <State tone={outcome.tone}>{outcome.word}</State>}
            <span>{plan}</span>
            {row.model === null ? null : <span>{row.model}</span>}
            {row.account === undefined ? null : <span>{row.account}</span>}
            {row.compact === true ? <span className="tag">{T.compacted}</span> : null}
          </div>
          {session === undefined ? null : <div className="session-link"><Link className="btn go" to={sessionPath(session)}>{P.openSession}</Link></div>}
        </div>
      </header>

      <section className="turn-section wide" aria-labelledby="turn-stages">
        <h2 id="turn-stages">{P.stagesTitle}</h2>
        <p className="why">{stages.length === 0 ? P.stagesNone : P.stagesWhy}</p>
        {stages.length === 0 ? null : (
          <>
            {painted.length === 0 ? null : <div className="water" role="img" aria-label={P.stagesTitle}>
              {painted.map((stage) => <i key={stage.key} title={`${P.stages[stage.key][0]}: ${secondsText(stage.ms)}`} style={{ flex: stage.ms, ['--s' as string]: `${95 - STAGE_ORDER.indexOf(stage.key) * 15}%` }} />)}
            </div>}
            <div className="legend">
              {stages.map((stage) => (
                <div key={stage.key}>
                  <b><i style={{ ['--s' as string]: `${95 - STAGE_ORDER.indexOf(stage.key) * 15}%` }} />{P.stages[stage.key][0]}</b>
                  <div className="v">{secondsText(stage.ms)}</div>
                  <p>{P.stages[stage.key][1]}</p>
                </div>
              ))}
            </div>
          </>
        )}
      </section>

      <div className="frame-cols two">
      <section className="turn-section" aria-labelledby="turn-moved">
        <h2 id="turn-moved">{P.movedTitle}</h2>
        <div className="figs">
          {moved.read === null && !posted ? null : <div><div className="n">{moved.read === null ? P.notReported : fmtInt(moved.read)}</div><h3>{P.readIn}</h3><p>{moved.read === null ? P.tokenCountNotReported : moved.cached === null ? P.readInPlain : P.readInWhy(fmtInt(moved.cached))}</p></div>}
          {moved.written === null && !posted ? null : <div><div className="n">{moved.written === null ? P.notReported : fmtInt(moved.written)}</div><h3>{P.writtenOut}</h3><p>{moved.written === null ? P.tokenCountNotReported : (row.reasoning_tokens ?? 0) > 0 ? P.writtenOutThinking(fmtInt(row.reasoning_tokens ?? 0)) : P.writtenOutWhy}</p></div>}
          <div><div className="n">{moved.cost === null ? ABSENT : fmtUsd(moved.cost)}</div><h3>{P.cost}</h3><p>{turnCostWhy(row)}</p></div>
          {moved.retries === null ? null : <div><div className="n">{moved.retries}</div><h3>{P.retries}</h3><p>{P.retriesWhy}</p></div>}
        </div>
      </section>

      <KeptTabs row={row} plan={plan} tab={params.get('tab')} />
      </div>
    </div>
  );
}
