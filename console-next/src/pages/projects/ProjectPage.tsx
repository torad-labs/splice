import { Link, useParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useSessions, useTeams } from '../../api/queries';
import { useProject, useProjectFiles } from '../../api/projects';
import { clockTime } from '../../lib/format';
import { charsText, ruleText } from '../../lib/compaction';
import { liveNames, projectLede, repoLabel, sessionsIn } from '../../lib/projects';
import { sessionLabel } from '../../lib/sessions';
import { P } from '../../lib/words-projects';
import { Empty, PageHead } from '../../ui';
import { sessionPath } from '../shared/SessionActions';
import { Standing } from './Standing';
import './projects.css';

/** One repo: what runs in it, who works in it, what governs it, and the files and standing texts that stand for it. */
export function ProjectPage() {
  const { id = '' } = useParams();
  const row = useProject(id);
  const files = useProjectFiles(id);
  const sessions = useSessions();
  const teams = useTeams();
  const heads = useHeads();
  const crumb = <div className="crumb"><Link to="/sessions?group=repo">{P.back}</Link></div>;

  if (row.isPending) return <>{crumb}<PageHead title={repoLabel(id)} lede={P.reading} /></>;
  if (row.isError) return <>{crumb}<PageHead title={repoLabel(id)} /><Empty title={P.gone} why={`${P.goneWhy} ${failureText(row.error)}`} /></>;
  const project = row.data;
  const rows = sessions.data?.sessions ?? [];
  const here = sessionsIn(rows, project.root);
  const teamsHere = (teams.data?.teams ?? []).filter((team) => team.repo === project.root && !team.archived);
  const labelOf = (key: string): string => heads.data?.heads.find((head) => head.key === key)?.label ?? key;

  return (
    <>
      <div className="crumb"><Link to="/sessions?group=repo">{P.back}</Link><span>/</span><span>{repoLabel(project.root, project.remote)}</span></div>
      <PageHead title={repoLabel(project.root, project.remote)} lede={projectLede(project)} />
      <p className="hint">{project.root}{project.last_activity === null ? ` · ${P.never}` : ` · ${P.activity} ${clockTime(project.last_activity)}`}</p>

      <section className="proj-section" aria-labelledby="proj-sessions">
        <h2 id="proj-sessions">{P.sessionsTitle}</h2>
        {here.length === 0 ? <p className="why">{P.sessionsNone}</p> : (
          <ul className="proj-list">
            {here.map((session) => <li key={session.session_id ?? session.pid}><Link to={sessionPath(session)}>{sessionLabel(session)}</Link><small>{labelOf(session.head)}</small></li>)}
          </ul>
        )}
      </section>

      {teamsHere.length === 0 ? null : (
        <section className="proj-section" aria-labelledby="proj-teams">
          <h2 id="proj-teams">{P.teamsTitle}</h2>
          <ul className="proj-list">
            {teamsHere.map((team) => <li key={team.id}><Link to={`/teams/${encodeURIComponent(team.id)}`}>{team.name}</Link><small>{team.goal}</small></li>)}
          </ul>
        </section>
      )}

      <section className="proj-section" aria-labelledby="proj-compaction">
        <h2 id="proj-compaction">{P.compaction}</h2>
        <p className="why">{project.compaction === null ? P.compactionUnwired : project.compaction.length === 0 ? P.compactionNone : P.compactionWhy}</p>
        {project.compaction === null || project.compaction.length === 0 ? null : (
          <ul className="proj-list">
            {project.compaction.map((rule) => {
              const text = ruleText(rule);
              return <li key={`${rule.scope}-${rule.source}`}><b>{text.scope}</b>{text.names === null ? null : <small>{text.names}</small>}<small>{charsText(rule.chars)}</small></li>;
            })}
          </ul>
        )}
      </section>

      <section className="proj-section" aria-labelledby="proj-standing">
        <h2 id="proj-standing">{P.standingTitle}</h2>
        <p className="why">{P.standingWhy}</p>
        <Standing key={project.root} root={project.root} live={liveNames(rows, project.root)} />
      </section>

      <section className="proj-section" aria-labelledby="proj-files">
        <h2 id="proj-files">{P.filesTitle}</h2>
        <p className="why">{P.filesWhy}</p>
        {files.isError ? <p className="hint alert" role="alert">{failureText(files.error)}</p> : null}
        {files.data === undefined ? null : (
          <>
            {files.data.files.length === 0 ? <p className="hint">{P.filesNone} {P.filesLooked}: {files.data.looked_in.join(', ')}</p> : (
              <ul className="proj-files">
                {files.data.files.map((file) => (
                  <li key={file.path}>
                    <details>
                      <summary>{file.head === null ? file.kind : `${file.kind} · ${labelOf(file.head)}`}<small>{file.path}</small></summary>
                      <pre>{file.text}</pre>
                    </details>
                  </li>
                ))}
              </ul>
            )}
            {files.data.auto_memory_enabled === false ? <p className="hint">{P.memoryOff}</p> : null}
          </>
        )}
      </section>

      {project.statusline_roots.length === 0 ? null : (
        <section className="proj-section" aria-labelledby="proj-status">
          <h2 id="proj-status">{P.statuslineTitle}</h2>
          <p className="why">{P.statuslineWhy}</p>
          <ul className="proj-list">
            {project.statusline_roots.map((entry) => (
              <li key={entry.head}><b>{labelOf(entry.head)}</b><small>{entry.root === null ? P.noBranch : `${entry.root} · ${entry.entry === null ? '' : P.trusted[entry.entry]}`}</small></li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}
