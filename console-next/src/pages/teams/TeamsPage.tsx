import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { failureText } from '../../api/client';
import { useSessions, useTeams } from '../../api/queries';
import { isPendingRoute } from '../../api/auth';
import { useModels } from '../../api/models';
import { usePerfTurns } from '../../api/turns';
import { projectTeams } from '../../lib/project-teams';
import { sessionKey } from '../../lib/sessions';
import { M } from '../../lib/words-teams';
import { Button, Empty, Fault, PageHead } from '../../ui';
import { ProjectSeat, ProjectTeam } from './ProjectTeam';
import { TeamDialog } from './TeamDialog';
import { T } from './copy';
import './teams.css';
import './team-board.css';

export function TeamsPage() {
  const sessions = useSessions();
  const teams = useTeams();
  const [composing, setComposing] = useState(false);
  const navigate = useNavigate();
  const registryFailure = sessions.isError ? failureText(sessions.error) : sessions.data?.error;
  const ready = sessions.isSuccess && registryFailure === undefined;
  const { groups, unresolved } = projectTeams(ready ? sessions.data.sessions : []);
  const usage = usePerfTurns({ last: 86_400_000, n: 1, filter: { local: false } }, 30_000, ready);
  const models = useModels();
  const reading = {
    data: usage.data === undefined || isPendingRoute(usage.data) ? undefined : usage.data,
    models: models.data === undefined || isPendingRoute(models.data) ? undefined : models.data,
    pending: usage.isPending,
    error: usage.isError ? failureText(usage.error) : null,
    retry: () => void usage.refetch(),
  };
  return (
    <div className="teams-page">
      <PageHead title={T.title} lede={T.lede} tools={<Button kind="quiet" onClick={() => setComposing(true)}>{M.newTeam}</Button>} />
      {registryFailure !== undefined ? <Fault message={registryFailure} onRetry={() => void sessions.refetch()} />
        : !ready ? <p className="hint">{T.reading}</p>
        : groups.length === 0 && unresolved.length === 0 ? <Empty title={T.empty} why={T.emptyWhy} /> : (
          <>
            <div className="project-teams">{groups.map(group => <ProjectTeam key={group.root} group={group} reading={reading} />)}</div>
            {unresolved.length === 0 ? null : <section className="project-team-unresolved"><h2>{T.unresolved}</h2><p className="hint">{T.unresolvedWhy}</p><ul>{unresolved.map(row => <ProjectSeat key={sessionKey(row)} row={row} />)}</ul></section>}
          </>
        )}
      <section className="saved-teams">
        <h2>{T.saved}</h2><p className="hint">{T.savedWhy}</p>
        {teams.isError ? <Fault message={failureText(teams.error)} onRetry={() => void teams.refetch()} />
          : teams.isPending ? <p className="hint">{T.savedReading}</p>
          : teams.data.teams.length === 0 ? <p className="hint">{T.savedNone}</p> : (
            <ul>{teams.data.teams.map(team => <li key={team.id}><div><Link to={`/teams/${encodeURIComponent(team.id)}`}>{team.name}</Link>{team.archived ? <span className="tag">{M.archived}</span> : null}</div><p>{team.goal || T.savedGoal}</p></li>)}</ul>
          )}
      </section>
      {composing ? <TeamDialog team={null} onClose={() => setComposing(false)} onSaved={team => { setComposing(false); void navigate(`/teams/${encodeURIComponent(team.id)}`); }} /> : null}
    </div>
  );
}
