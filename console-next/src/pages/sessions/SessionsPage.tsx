import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors } from '@dnd-kit/core';
import type { DragEndEvent } from '@dnd-kit/core';
import { SortableContext, rectSortingStrategy, sortableKeyboardCoordinates } from '@dnd-kit/sortable';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { failureText } from '../../api/client';
import { useProjects } from '../../api/projects';
import { useHeads, useSessions, useStatus, useTeams } from '../../api/queries';
import { useBoardEdges, useSessionHistory, useTurnOf } from '../../api/sessions';
import { colourFromRegistry } from '../../lib/model';
import { moveKey, setOrder, sortByOrder, useOrder } from '../../lib/order';
import type { GroupBy } from '../../lib/sessions';
import { UNATTRIBUTED, groupSessions, handoffOf, matchesQuery, sessionKey, sessionsLede, stateOf, timingOf } from '../../lib/sessions';
import type { SessionRow } from '../../types/sessions';
import { UNKNOWN_HEAD } from '../../types/sessions';
import { Button, Empty, Fault, GroupHead, PageHead, SearchField, Segmented } from '../../ui';
import { P as PJ } from '../../lib/words-projects';
import { M } from '../../lib/words-teams';
import { TeamDialog } from '../teams/TeamDialog';
import { P } from './copy';
import { SessionCard } from './SessionCard';
import type { CardFacts } from './SessionCard';

const GROUPS = [['state', P.byState], ['repo', P.byRepo], ['head', P.byModel], ['team', P.byTeam]] as const;
const groupFrom = (value: string | null): GroupBy => GROUPS.find(([id]) => id === value)?.[0] ?? 'state';

const STATE_HEAD: Readonly<Record<string, { title: string; why: string }>> = {
  needs: { title: P.needs, why: P.needsWhy },
  working: { title: P.working, why: P.workingWhy },
  idle: { title: P.idle, why: P.idleWhy },
  gone: { title: P.gone, why: P.goneWhy },
};

export function SessionsPage() {
  const [params, setParams] = useSearchParams();
  const by = groupFrom(params.get('group'));
  const [query, setQuery] = useState('');
  const [composing, setComposing] = useState(false);
  const navigate = useNavigate();
  const teams = useTeams();
  const projects = useProjects();
  const sessions = useSessions();
  const heads = useHeads();
  const status = useStatus();
  const edges = useBoardEdges();
  const order = useOrder('sessions');
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const now = Date.now();
  const q = query.trim();
  const live = useMemo(() => (sessions.data?.sessions ?? []).filter((row) => matchesQuery(row, q)), [sessions.data, q]);
  const history = useSessionHistory(q, q.length >= 2);
  const turnOf = useTurnOf(
    (sessions.data?.sessions ?? []).filter((row) => row.head !== UNKNOWN_HEAD && (row.status === 'busy' || row.status === 'shell')).map((row) => row.head),
  );

  const headOf = (row: SessionRow) => (row.head === UNKNOWN_HEAD ? undefined : heads.data?.heads.find((candidate) => candidate.key === row.head));
  const colourOf = colourFromRegistry(status.data);
  const factsOf = (row: SessionRow): CardFacts => {
    const turn = turnOf(row);
    const state = stateOf(row);
    return {
    row,
    state,
    ...timingOf(row, state, turn, now),
    colour: row.head === UNKNOWN_HEAD ? 'none' : colourOf(row.head),
    head: row.head === UNKNOWN_HEAD ? null : (headOf(row)?.label ?? row.head),
    hand: handoffOf(sessions.data?.sessions ?? [], row.session_id === null ? [] : (edges.data?.sessions[row.session_id] ?? [])),
    };
  };

  const allKeys = live.map(sessionKey);
  const onDragEnd = (event: DragEndEvent): void => {
    const { active, over } = event;
    if (over === null || active.id === over.id) return;
    setOrder('sessions', moveKey(order, allKeys, String(active.id), String(over.id)));
  };

  if (sessions.isPending) return <PageHead title={P.title} lede={P.reading} />;
  if (sessions.isError) {
    return (
      <>
        <PageHead title={P.title} />
        <Fault message={failureText(sessions.error)} onRetry={() => void sessions.refetch()} />
      </>
    );
  }

  const groups = groupSessions(live, by);
  const liveKeys = new Set(allKeys);
  const pages = history.data?.pages ?? [];
  const found = pages.flatMap((page) => ('sessions' in page ? page.sessions : []));
  const earlier = found.filter((row) => !liveKeys.has(sessionKey(row)));
  const historyOff = pages.find((page): page is Extract<typeof page, { state: 'off' }> => 'state' in page);
  const setBy = (next: GroupBy): void => setParams(next === 'state' ? {} : { group: next }, { replace: true });

  return (
    <>
      <PageHead
        title={P.title}
        {...(sessionsLede(sessions.data.sessions) === '' ? {} : { lede: sessionsLede(sessions.data.sessions) })}
        tools={
          <>
            <Segmented label={P.groupBy} value={by} options={GROUPS} onChange={setBy} />
            <SearchField value={query} onChange={setQuery} label={P.search} hint={P.search} />
            {by === 'team' ? <Button onClick={() => setComposing(true)}>{M.newTeam}</Button> : null}
          </>
        }
      />
      {sessions.data.error === undefined ? null : <Fault message={sessions.data.error} />}
      {live.length === 0 && q === '' ? <Empty title={P.empty} why={sessions.data.note || P.emptyWhy} /> : null}
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
        {groups.map((group) => {
          const rows = sortByOrder(group.sessions, sessionKey, order);
          const text = by === 'state' ? STATE_HEAD[group.key] : undefined;
          const team = by === 'team' ? teams.data?.teams.find((candidate) => candidate.id === group.key) : undefined;
          const root = by === 'repo' ? group.sessions.find((row) => row.repo !== undefined)?.repo?.root : undefined;
          const title = text?.title ?? team?.name ?? (group.key === UNATTRIBUTED ? P.unattributed : group.key === UNKNOWN_HEAD ? P.unknownHead : group.key);
          return (
            <section key={group.key} aria-label={title}>
              <GroupHead title={title} count={rows.length} {...(text === undefined ? {} : { why: text.why })} {...(team !== undefined ? { action: <Link to={`/teams/${encodeURIComponent(team.id)}`}>{M.openTeam}</Link> } : root !== undefined && projects.data?.projects.some((project) => project.root === root) === true ? { action: <Link to={`/projects/${encodeURIComponent(root)}`}>{PJ.openProject}</Link> } : {})} />
              <SortableContext items={rows.map(sessionKey)} strategy={rectSortingStrategy}>
                <ul className="grid">
                  {rows.map((row) => (
                    <SessionCard key={sessionKey(row)} facts={factsOf(row)} />
                  ))}
                </ul>
              </SortableContext>
            </section>
          );
        })}
      </DndContext>
      {composing ? <TeamDialog team={null} onClose={() => setComposing(false)} onSaved={(saved) => { setComposing(false); void navigate(`/teams/${encodeURIComponent(saved.id)}`); }} /> : null}
      {q.length < 2 ? null : (
        <section aria-label={P.earlier}>
          <GroupHead title={P.earlier} count={earlier.length} why={P.earlierWhy} />
          {history.isPending ? <p className="hint">{P.searching}</p> : null}
          {history.isError ? <Fault message={failureText(history.error)} /> : null}
          {historyOff === undefined ? null : <p className="hint">{P.historyOff} {historyOff.reason}</p>}
          {history.isSuccess && earlier.length === 0 && historyOff === undefined ? <p className="hint">{P.historyNone}</p> : null}
          <ul className="grid">
            {earlier.map((row) => (
              <SessionCard key={sessionKey(row)} facts={factsOf(row)} sortable={false} />
            ))}
          </ul>
          {history.hasNextPage ? (
            <div className="more-row">
              <Button disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>{P.more}</Button>
            </div>
          ) : null}
        </section>
      )}
    </>
  );
}
