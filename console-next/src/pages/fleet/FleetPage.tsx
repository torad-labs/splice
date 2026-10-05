import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors } from '@dnd-kit/core';
import type { DragEndEvent } from '@dnd-kit/core';
import { SortableContext, rectSortingStrategy, sortableKeyboardCoordinates } from '@dnd-kit/sortable';
import { failureText } from '../../api/client';
import { useKeyStore } from '../../api/auth';
import { useAccounts, useAuth, useHealth, useHeads, useSessions, useStatus, useUsage } from '../../api/queries';
import { poolOf } from '../../lib/accounts';
import { fleetCard, fleetLede } from '../../lib/fleet';
import { moveKey, setOrder, sortByOrder, useOrder } from '../../lib/order';
import { Empty, Fault, GroupHead, PageHead, Plus } from '../../ui';
import { ModelTable } from '../models/ModelTable';
import { AddPlan } from './AddPlan';
import { F } from './copy';
import { FleetCardView } from './FleetCard';
import { FleetFix } from './FleetFix';

export function FleetPage() {
  const heads = useHeads();
  const status = useStatus();
  const usage = useUsage();
  const auth = useAuth();
  const accounts = useAccounts();
  const sessions = useSessions();
  const health = useHealth();
  const keyStore = useKeyStore();
  const order = useOrder('fleet');
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const now = Date.now();

  if (heads.isPending) return <PageHead title={F.title} lede={F.reading} />;
  if (heads.isError) {
    return (
      <>
        <PageHead title={F.title} />
        <Fault message={failureText(heads.error)} onRetry={() => void heads.refetch()} />
      </>
    );
  }

  const rows = accounts.data?.accounts ?? [];
  const live = new Map<string, number>();
  for (const row of sessions.data?.sessions ?? []) {
    if (row.availability !== 'gone') live.set(row.head, (live.get(row.head) ?? 0) + 1);
  }
  const families = new Map(status.data?.registry.map((row) => [row.key, row.family] as const) ?? []);
  const cards = sortByOrder(heads.data.heads, (head) => head.key, order).map((head) => ({
    head,
    facts: fleetCard(head, { usage: usage.data ?? null, auth: auth.data ?? null, accounts: rows, sessions: live, topologyStale: health.data?.topologyStale === true, family: families.get(head.key) ?? null, keys: keyStore.data ?? null, now }),
  }));
  const keys = cards.map((card) => card.head.key);
  const move = (key: string, over: string): void => setOrder('fleet', moveKey(order, keys, key, over));
  const onDragEnd = (event: DragEndEvent): void => {
    const { active, over } = event;
    if (over === null || active.id === over.id) return;
    move(String(active.id), String(over.id));
  };

  return (
    <>
      <PageHead
        title={F.title}
        {...(cards.length === 0 ? {} : { lede: fleetLede(cards.map((card) => card.facts)) })}
        tools={
          <AddPlan>
            <button type="button" className="btn go">
              <Plus />
              {F.add}
            </button>
          </AddPlan>
        }
      />
      <section className="commands" aria-label={F.commands}>
        <GroupHead title={F.commands} count={cards.length} why={F.commandsWhy} />
        {cards.length === 0 ? <Empty title={F.empty} why={F.emptyWhy} /> : null}
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={keys} strategy={rectSortingStrategy}>
            <ul className="grid fit">
              {cards.map(({ head, facts }, index) => {
                const earlier = cards[index - 1];
                const later = cards[index + 1];
                return <FleetCardView
                  key={head.key}
                  facts={facts}
                  ordering={{
                    earlier: earlier === undefined ? null : () => move(head.key, earlier.head.key),
                    later: later === undefined ? null : () => move(head.key, later.head.key),
                  }}
                  fix={facts.fix === null ? null : <FleetFix fix={facts.fix} head={head} pool={poolOf(rows, head.key)} now={now} keyCommand={facts.keyCommand ?? null} />}
                />;
              })}
              <li>
                <AddPlan>
                  <button type="button" className="add-card">
                    <b>{F.bringAnother}</b>
                    <span>{F.bringWhy}</span>
                  </button>
                </AddPlan>
              </li>
            </ul>
          </SortableContext>
        </DndContext>
      </section>
      <ModelTable />
    </>
  );
}
