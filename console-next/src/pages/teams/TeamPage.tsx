import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useSessions, useStatus, useTeams } from '../../api/queries';
import { useArchiveTeam, useTeamActivity, useTeamChat, useTeamEconomics } from '../../api/teams';
import { ABSENT, clockTime, fmtUsd } from '../../lib/format';
import { colourFromRegistry } from '../../lib/model';
import { stateOf, stateTone, stateWord } from '../../lib/sessions';
import { repoLabel } from '../../lib/projects';
import { atRetentionEdge, dayOf, dayWords, peerName, seatsOf, sessionIn, teamLede } from '../../lib/teams-page';
import { M } from '../../lib/words-teams';
import type { TeamSlotTally } from '../../types/teams';
import { Button, Empty, Fault, Markdown, PageHead, State } from '../../ui';
import { sessionPath } from '../shared/SessionActions';
import { TeamDialog } from './TeamDialog';
import './teams.css';

const tallyText = (tally: TeamSlotTally | undefined): string => {
  if (tally === undefined || tally.turns === 0) return M.noTurns;
  const missing = tally.unreported_usage_turns ?? 0;
  const cost = tally.cost_usd === null ? ABSENT : missing > 0 || tally.unpriced_turns > 0
    ? M.costLowerBound(fmtUsd(tally.cost_usd)) : M.cost(fmtUsd(tally.cost_usd));
  const parts = [M.turns(tally.turns), cost];
  if (tally.checks === 'pass') parts.push(M.lastFinished);
  if (tally.checks === 'fail') parts.push(M.lastFailed);
  if (missing > 0) parts.push(M.unreportedUsage(missing));
  return parts.join(' · ');
};

/** What the seat's session is called under the seat's own name: the name the session carries, unless that is the seat's role said
 *  again, in which case the link says what it opens. */
const seatSession = (role: string, row: { name: string | null; session_id: string | null }): string => {
  const name = row.name ?? row.session_id ?? M.openSession;
  return name.trim().toLowerCase() === role.trim().toLowerCase() ? M.openSession : name;
};

/** One team: the seats and who sits in them, what they sent each other and what they did on a day. */
export function TeamPage() {
  const { id = '' } = useParams();
  const teams = useTeams();
  const sessions = useSessions();
  const heads = useHeads();
  const status = useStatus();
  const [back, setBack] = useState(0);
  const [editing, setEditing] = useState(false);
  const archive = useArchiveTeam();
  const team = teams.data?.teams.find((candidate) => candidate.id === id) ?? null;
  const now = Date.now();
  const day = dayOf(now, back);
  const chat = useTeamChat(team === null ? null : team.id, day, back === 0);
  const activity = useTeamActivity(team === null ? null : team.id, day, back === 0);
  const economics = useTeamEconomics(team === null ? null : team.id);
  const rows = sessions.data?.sessions ?? [];
  const crumb = <div className="crumb"><Link to="/teams">{M.back}</Link></div>;

  if (teams.isPending) return <>{crumb}<PageHead title={M.back} lede={M.reading} /></>;
  if (teams.isError) return <>{crumb}<Fault message={failureText(teams.error)} onRetry={() => void teams.refetch()} /></>;
  if (team === null) return <>{crumb}<PageHead title={M.back} /><Empty title={M.gone} why={M.goneWhy} /></>;

  const colourOf = colourFromRegistry(status.data);
  const labelOf = (key: string): string => heads.data?.heads.find((head) => head.key === key)?.label ?? key;
  const seats = seatsOf(team);
  const working = new Set(seats.filter((slot) => {
    const row = sessionIn(slot, rows);
    return row !== null && stateOf(row) === 'working';
  }).map(slot => slot.session)).size;
  const tallies = new Map((economics.data?.slots ?? []).map((tally) => [tally.slot, tally] as const));
  const nameOf = (session: string): string => {
    const slot = team.slots.find((candidate) => candidate.id === session);
    return peerName(slot?.role ?? null, session);
  };

  return (
    <>
      <div className="crumb"><Link to="/teams">{M.back}</Link><span>/</span><span>{team.name}</span></div>
      <PageHead
        title={team.name}
        lede={teamLede(team, working)}
        tools={
          <>
            <Button onClick={() => setEditing(true)}>{M.edit}</Button>
            <Button kind="quiet" disabled={archive.isPending} onClick={() => archive.mutate({ team, archived: !team.archived })}>{team.archived ? M.restore : M.archive}</Button>
          </>
        }
      />
      {team.archived || team.features.length > 0 || team.repo !== '' ? (
        <div className="chips">
          {team.archived ? <span className="tag">{M.archived}</span> : null}
          {team.repo === '' ? null : <span className="tag">{repoLabel(team.repo)}</span>}
          {team.features.map((feature) => <span key={feature} className="tag">{feature}</span>)}
        </div>
      ) : null}
      {archive.isError ? <Fault message={failureText(archive.error)} /> : null}

      <section className="team-section wide" aria-labelledby="team-seats">
        <h2 id="team-seats">{M.seatsTitle}</h2>
        <p className="why">{M.seatsWhy}</p>
        <ul className="seatrows">
          {seats.map((slot) => {
            const row = sessionIn(slot, rows);
            const state = row === null ? null : stateOf(row);
            return (
              <li key={slot.id} className={`seatrow hue ${colourOf(slot.head)}`} aria-label={slot.role}>
                <div>
                  <h3><i />{slot.role}{slot.lead ? <span className="tag">{M.lead}</span> : null}</h3>
                  <div className="cmd">{labelOf(slot.head)}</div>
                </div>
                <div>
                  {row === null ? <div className="who none">{M.nobody}</div> : <div className="who"><Link to={sessionPath(row)}>{seatSession(slot.role, row)}</Link></div>}
                  <p className="note">{slot.instructions === null || slot.instructions.trim() === '' ? M.noInstructions : slot.instructions}</p>
                </div>
                <div>
                  {state === null ? <State tone="idle">{M.openSeat}</State> : <State tone={stateTone(state)}>{stateWord(state)}</State>}
                  <div className="stat">{tallyText(tallies.get(slot.id))}</div>
                </div>
              </li>
            );
          })}
        </ul>
        {economics.data === undefined || economics.data.unattributed_turns === 0 ? null : <p className="team-note">{M.economicsUnattributed(economics.data.unattributed_turns)}</p>}
      </section>

      <div className="frame-cols two">
      <section className="team-section" aria-labelledby="team-talk">
        <h2 id="team-talk">{M.talkTitle} {dayWords(now, back).toLowerCase()}</h2>
        <p className="why">{M.talkWhy}</p>
        <div className="day" role="group" aria-label={M.dayLabel}>
          <Button small kind="quiet" disabled={atRetentionEdge(chat.data, day)} onClick={() => setBack(back + 1)}>{M.earlier}</Button>
          <span>{dayWords(now, back)}</span>
          {back === 0 ? null : <Button small kind="quiet" onClick={() => setBack(back - 1)}>{M.later}</Button>}
        </div>
        {chat.isError ? <p className="team-note" role="alert">{failureText(chat.error)}</p> : null}
        {chat.data === undefined ? null : chat.data.reason !== undefined && chat.data.messages.length === 0 ? <p className="team-note">{chat.data.reason}</p> : chat.data.messages.length === 0 ? <p className="team-note">{M.talkNone}</p> : (
          <ul className="talk">
            {[...chat.data.messages].sort((a, b) => b.at - a.at).map((message) => (
              <li key={`${message.at}-${message.from}-${message.to}`}>
                <small>{nameOf(message.from_slot ?? message.from)} {M.to} {nameOf(message.to_slot ?? message.to)} · {clockTime(message.at)}</small>
                {message.text === null ? <p className="missing">{message.missing_reason ?? M.textMissing}</p> : <Markdown>{message.text}</Markdown>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="team-section" aria-labelledby="team-done">
        <h2 id="team-done">{M.doneTitle} {dayWords(now, back).toLowerCase()}</h2>
        <p className="why">{M.doneWhy}</p>
        {activity.isError ? <p className="team-note" role="alert">{failureText(activity.error)}</p> : null}
        {activity.data === undefined ? null : activity.data.reason !== undefined && activity.data.entries.length === 0 ? <p className="team-note">{activity.data.reason}</p> : activity.data.entries.length === 0 ? <p className="team-note">{M.doneNone}</p> : (
          <ul className="talk">
            {[...activity.data.entries].sort((a, b) => b.at - a.at).map((entry) => (
              <li key={`${entry.at}-${entry.session}`}>
                <p><small>{entry.slot === null ? entry.head : nameOf(entry.slot)} · {clockTime(entry.at)}</small>{entry.label}{entry.detail === null ? '' : ` ${entry.detail}`}</p>
              </li>
            ))}
          </ul>
        )}
      </section>
      </div>

      {editing ? <TeamDialog team={team} onClose={() => setEditing(false)} onSaved={() => setEditing(false)} /> : null}
    </>
  );
}
