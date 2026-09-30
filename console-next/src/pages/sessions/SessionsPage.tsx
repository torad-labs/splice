import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors } from '@dnd-kit/core';
import type { DragEndEvent } from '@dnd-kit/core';
import { SortableContext, rectSortingStrategy, sortableKeyboardCoordinates } from '@dnd-kit/sortable';
import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useSessions } from '../../api/queries';
import { useBoardEdges, useSessionHistory } from '../../api/sessions';
import { colourOfHead } from '../../lib/model';
import type { ModelColour } from '../../lib/model';
import { moveKey, setOrder, sortByOrder, useOrder } from '../../lib/order';
import type { GroupBy } from '../../lib/sessions';
import { UNATTRIBUTED, groupSessions, handoffOf, matchesQuery, sessionKey, sessionsLede, stateOf } from '../../lib/sessions';
import type { SessionRow } from '../../types/sessions';
import { UNKNOWN_HEAD } from '../../types/sessions';
import { Button, Empty, Fault, GroupHead, PageHead, SearchField, Segmented } from '../../ui';
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
  const sessions = useSessions();
  const heads = useHeads();
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

  const headOf = (row: SessionRow) => (row.head === UNKNOWN_HEAD ? undefined : heads.data?.heads.find((candidate) => candidate.key === row.head));
  const colourOf = (row: SessionRow): ModelColour => {
    const head = headOf(row);
    return head === undefined ? 'none' : colourOfHead(head.authKind);
  };
  const factsOf = (row: SessionRow): CardFacts => ({
    row,
    state: stateOf(row, now),
    colour: colourOf(row),
    head: row.head === UNKNOWN_HEAD ? null : (headOf(row)?.label ?? row.head),
    hand: handoffOf(sessions.data?.sessions ?? [], row.session_id === null ? [] : (edges.data?.sessions[row.session_id] ?? [])),
    now,
  });

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

  const groups = groupSessions(live, by, now);
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
        {...(sessionsLede(sessions.data.sessions, now) === '' ? {} : { lede: sessionsLede(sessions.data.sessions, now) })}
        tools={
          <>
            <Segmented label={P.groupBy} value={by} options={GROUPS} onChange={setBy} />
            <SearchField value={query} onChange={setQuery} label={P.search} hint={P.search} />
          </>
        }
      />
      {sessions.data.error === undefined ? null : <Fault message={sessions.data.error} />}
      {live.length === 0 && q === '' ? <Empty title={P.empty} why={sessions.data.note || P.emptyWhy} /> : null}
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
        {groups.map((group) => {
          const rows = sortByOrder(group.sessions, sessionKey, order);
          const text = by === 'state' ? STATE_HEAD[group.key] : undefined;
          const title = text?.title ?? (group.key === UNATTRIBUTED ? P.unattributed : group.key === UNKNOWN_HEAD ? P.unknownHead : group.key);
          return (
            <section key={group.key} aria-label={title}>
              <GroupHead title={title} count={rows.length} {...(text === undefined ? {} : { why: text.why })} />
              <SortableContext items={rows.map(sessionKey)} strategy={rectSortingStrategy}>
                <ul className={by === 'state' && group.key === 'needs' ? 'grid first' : 'grid'}>
                  {rows.map((row) => (
                    <SessionCard key={sessionKey(row)} facts={factsOf(row)} />
                  ))}
                </ul>
              </SortableContext>
            </section>
          );
        })}
      </DndContext>
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
