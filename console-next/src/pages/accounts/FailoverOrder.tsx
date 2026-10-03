import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors } from '@dnd-kit/core';
import type { DragEndEvent } from '@dnd-kit/core';
import { SortableContext, arrayMove, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useAccountOrder, useSaveAccountOrder } from '../../api/account-order';
import { failureText } from '../../api/client';
import type { AccountRow } from '../../types/accounts';
import { Button, Fault } from '../../ui';
import { A } from './copy';

function OrderedAccount({ id, name, index, length, disabled, move }: {
  id: string; name: string; index: number; length: number; disabled: boolean; move: (from: number, to: number) => void;
}) {
  const drag = useSortable({ id, disabled });
  return (
    <li ref={drag.setNodeRef} style={{ transform: CSS.Transform.toString(drag.transform), transition: drag.transition }} aria-busy={disabled || undefined}>
      <button type="button" className="btn sm drag-handle" ref={drag.setActivatorNodeRef} aria-label={A.drag(name)} disabled={disabled} {...drag.attributes} {...drag.listeners}>⠿</button>
      <span className="order-number">{index + 1}</span><b className="order-name">{name}</b>
      <span className="order-controls">
        <Button small disabled={disabled || index === 0} aria-label={A.moveUp(name)} onClick={() => move(index, index - 1)}>↑</Button>
        <Button small disabled={disabled || index === length - 1} aria-label={A.moveDown(name)} onClick={() => move(index, index + 1)}>↓</Button>
      </span>
    </li>
  );
}

export function FailoverOrder({ head, accounts }: { head: string; accounts: readonly AccountRow[] }) {
  const single = accounts.length > 0 && accounts.every(row => row.single_login && row.label === null && row.login_place == null);
  const read = useAccountOrder(head, !single);
  const save = useSaveAccountOrder(head);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }), useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }));
  const data = read.data;
  if (single) return <p className="hint">{A.orderSingle}</p>;
  if (data === undefined) return read.isError ? <Fault message={failureText(read.error)} onRetry={() => void read.refetch()} /> : <p className="hint">{A.orderReading}</p>;
  if ('unavailable' in data) return <p className="hint">{A.orderUnavailable} {data.unavailable}</p>;
  const order = data.effective_order;
  if (order.length < 2) return null;
  const move = (from: number, to: number): void => {
    if (save.isPending || from < 0 || from >= order.length || to < 0 || to >= order.length || from === to) return;
    save.mutate(arrayMove(order, from, to));
  };
  const dragEnd = ({ active, over }: DragEndEvent): void => {
    if (over === null) return;
    move(order.indexOf(String(active.id)), order.indexOf(String(over.id)));
  };
  const nameOf = (id: string): string => {
    const row = accounts.find(account => (account.login_place?.id ?? account.label) === id);
    return row?.account?.email == null ? id : `${id} · ${row.account.email}`;
  };
  return (
    <section className="account-order" aria-label={`${head} ${A.failover}`}>
      <h3>{head} · {A.failover}</h3>
      <p className="why">{A.orderWhy}</p>
      <p className="hint">{accounts.some(row => row.pinned === true) ? A.pinRule : A.nextBecause}</p>
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={dragEnd}>
        <SortableContext items={order} strategy={verticalListSortingStrategy}>
          <ol>{order.map((id, index) => <OrderedAccount key={id} id={id} name={nameOf(id)} index={index} length={order.length} disabled={save.isPending} move={move} />)}</ol>
        </SortableContext>
      </DndContext>
      {save.isPending ? <p className="hint" role="status">{A.orderSaving}</p> : save.isSuccess ? <p className="hint" role="status">{A.orderSaved}</p> : null}
      {save.isError ? <p className="hint alert" role="alert">{failureText(save.error)}</p> : null}
    </section>
  );
}
