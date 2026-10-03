import { useState } from 'react';
import { Link } from 'react-router';
import { failureText } from '../../api/client';
import { useBoardEdges } from '../../api/sessions';
import { repoLabel } from '../../lib/projects';
import { projectHandoffs, projectUsage } from '../../lib/project-teams';
import type { ModelsPayload } from '../../types/models';
import type { TurnsState } from '../../types/perf';
import { ProjectUsage } from './ProjectUsage';
import type { ProjectTeam as ProjectTeamRow } from '../../lib/project-teams';
import { cardLine, sessionKey, sessionLabel, sessionStatus, sinceOf, stateOf } from '../../lib/sessions';
import type { SessionRow } from '../../types/sessions';
import { Fault, State, Window } from '../../ui';
import { sessionPath } from '../shared/SessionActions';
import { HandoffMessage } from './HandoffMessage';
import { T } from './copy';

export function ProjectSeat({ row, model, reading = false }: { row: SessionRow; model?: string; reading?: boolean }) {
  const status = sessionStatus(row);
  const line = cardLine(row, status.state, sinceOf(row, Date.now()), null);
  return (
    <li className="project-seat">
      <div className="project-seat-name"><Link to={sessionPath(row)}>{sessionLabel(row)}</Link><small>{model === undefined ? reading ? T.modelReading : T.modelUnknown : T.lastModel(model)}</small></div>
      <p className={line.agent ? 'project-work' : 'project-work muted'}>{row.last == null ? T.notReported : line.line}</p>
      <div className="project-seat-state"><State tone={status.tone}>{status.word}</State>{status.old ? <small>{T.staleWhy}</small> : null}</div>
    </li>
  );
}

export interface ProjectReading {
  data: TurnsState | undefined;
  models: ModelsPayload | undefined;
  pending: boolean;
  error: string | null;
  since: number;
  retry: () => void;
}

export function ProjectTeam({ group, reading }: { group: ProjectTeamRow; reading?: ProjectReading }) {
  const edges = useBoardEdges();
  const messages = projectHandoffs(group.sessions, edges.data?.sessions ?? {});
  const stale = group.sessions.filter(row => row.availability === 'stale').length;
  const working = group.sessions.filter(row => row.availability !== 'gone' && stateOf(row) === 'working').length;
  const [shown, setShown] = useState(5);
  const visible = messages.slice(0, shown);
  const data = reading?.data;
  const usage = data === undefined ? null : projectUsage(group.sessions, data.landed);
  const heads = new Set(group.sessions.map(row => row.head));
  const partial = data !== undefined && (data.unread.some(row => heads.has(row.head)) || data.truncated.some(row => heads.has(row.head)) || (data.completeFrom !== undefined && data.completeFrom > (reading?.since ?? 0)) || data.landed.some(row => heads.has(row.head) && row.session_id === undefined && row.local_step !== 1));
  const modelOf = (row: SessionRow): string | undefined => {
    const last = row.session_id === null ? undefined : usage?.models[row.session_id];
    if (last === undefined) return undefined;
    return reading?.models?.heads.find(head => head.head === last.head)?.models.find(model => model.id === last.model)?.label ?? last.model;
  };
  return (
    <Window className="project-team">
      <header className="project-team-head">
        <div><h2>{repoLabel(group.root, group.remote)}</h2><p>{T.seats(group.sessions.length)} · {T.working(working)}{stale === 0 ? '' : ` · ${T.old(stale)}`}</p></div>
        <Link className="btn quiet sm" to={`/projects/${encodeURIComponent(group.root)}`}>{T.project}</Link>
      </header>
      {group.sessions.every(row => row.repo === undefined || row.repo.reason) ? <p className="hint">{T.folderGrouping}</p> : null}
      <ProjectUsage value={usage} reading={reading?.pending ?? false} partial={partial} error={reading?.error ?? null} retry={reading?.retry ?? (() => undefined)} />
      <div className="project-team-members">
        <h3>{T.members}<span>{T.work}</span></h3>
        <ul>{group.sessions.map(row => {
          const model = modelOf(row);
          return <ProjectSeat key={sessionKey(row)} row={row} reading={reading?.pending ?? false} {...(model === undefined ? {} : { model })} />;
        })}</ul>
      </div>
      <section className="project-team-talk" aria-label={T.handoffs}>
        <h3>{T.handoffs}</h3><p className="hint">{T.handoffsWhy}</p>
        {edges.isError ? <Fault message={failureText(edges.error)} onRetry={() => void edges.refetch()} />
          : edges.isPending ? <p className="hint">{T.readingHandoffs}</p>
          : edges.data.reason ? <p className="hint">{edges.data.reason}</p>
          : edges.data.state === 'off' || edges.data.state === 'deleted' ? <p className="hint">{T.missingMessage}</p>
          : messages.length === 0 ? <p className="hint">{T.noHandoffs}</p> : (
            <>
              <ul className="project-handoffs">{visible.map(edge => <HandoffMessage key={JSON.stringify([edge.from, edge.to, edge.at])} edge={edge} rows={group.sessions} />)}</ul>
              {messages.length <= visible.length ? null : <button type="button" className="btn quiet sm project-handoffs-more" onClick={() => setShown(shown + 10)}>{T.allHandoffs}</button>}
            </>
          )}
      </section>
    </Window>
  );
}
