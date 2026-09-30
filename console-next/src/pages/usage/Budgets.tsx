import * as Dialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute } from '../../api/auth';
import { useBudgets, usePutBudgets } from '../../api/usage';
import { fmtUsd } from '../../lib/format';
import { budgetFor } from '../../lib/budget';
import type { PlanUsage } from '../../lib/usage-page';
import { U } from '../../lib/words-usage';
import type { Budget, BudgetAction } from '../../types/budget';
import { Button, Close, NumberInput, Select } from '../../ui';

const ACTIONS = [
  { id: 'warn', label: U.budgetWarn, hint: U.budgetWarnHint },
  { id: 'block', label: U.budgetBlock, hint: U.budgetBlockHint },
] as const;

/** One plan's budget, set or changed. `first` is the plan it opens on: a budgeted plan to change, or null to pick one that has none. */
function BudgetDialog({ plans, budgets, first, onClose }: { plans: readonly PlanUsage[]; budgets: readonly Budget[]; first: string | null; onClose: () => void }) {
  const put = usePutBudgets();
  const free = plans.filter((plan) => budgetFor({ budgets: [...budgets] }, plan.key)?.daily_usd == null);
  const [key, setKey] = useState(first ?? free[0]?.key ?? '');
  const current = budgetFor({ budgets: [...budgets] }, key);
  const [usd, setUsd] = useState(current?.daily_usd ?? 10);
  const [action, setAction] = useState<BudgetAction>(current?.action ?? 'warn');
  const plan = plans.find((candidate) => candidate.key === key);
  const others = budgets.filter((row) => row.head !== key);
  const write = (next: readonly Budget[]): void => put.mutate(next, { onSuccess: onClose });
  return (
    <Dialog.Root open onOpenChange={(open) => (open ? undefined : onClose())}>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog">
          <div className="dialog-head">
            <Dialog.Title>{U.budgetDialog(plan?.label ?? '')}</Dialog.Title>
            <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={U.budgetCancel}><Close /></button></Dialog.Close>
          </div>
          <Dialog.Description className="hint">{U.budgetDialogWhy}</Dialog.Description>
          <div className="budget-form">
            {first !== null ? null : (
              <div className="field"><span className="eyebrow">{U.budgetPlan}</span><Select value={key} options={free.map((candidate) => ({ id: candidate.key, label: candidate.label }))} onChange={setKey} label={U.budgetPlan} /></div>
            )}
            <label className="field"><span className="eyebrow">{U.budgetDollars}</span><NumberInput value={usd} onCommit={setUsd} label={U.budgetDollars} /></label>
            <div className="field"><span className="eyebrow">{U.budgetAction}</span><Select value={action} options={ACTIONS} onChange={setAction} label={U.budgetAction} /></div>
            <div className="acts-row">
              <Button kind="go" disabled={put.isPending || usd <= 0 || key === ''} onClick={() => write([...others, { head: key, daily_usd: usd, action }])}>{U.budgetSave}</Button>
              {first === null ? null : <Button kind="danger" disabled={put.isPending} onClick={() => write(others)}>{U.budgetRemove}</Button>}
            </div>
            {put.isError ? <p className="hint alert" role="alert">{U.budgetFailed} {failureText(put.error)}</p> : null}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** Each plan's own daily dollars ceiling, and what to do when it is reached. Only plans that have one are listed. */
export function Budgets({ plans }: { plans: readonly PlanUsage[] }) {
  const read = useBudgets();
  const data = read.data;
  const [editing, setEditing] = useState<string | null | undefined>(undefined);
  const set = data === undefined || isPendingRoute(data) ? [] : plans.filter((plan) => budgetFor(data, plan.key)?.daily_usd != null);
  const room = data === undefined || isPendingRoute(data) ? false : plans.some((plan) => budgetFor(data, plan.key)?.daily_usd == null);
  return (
    <section className="section" aria-labelledby="usage-budgets">
      <h2 id="usage-budgets">{U.budgetsTitle}</h2>
      <p className="why">{U.budgetsWhy}</p>
      {read.isError ? <p className="why alert" role="alert">{failureText(read.error)}</p> : null}
      {data === undefined ? null : isPendingRoute(data) ? <p className="why">{U.budgetsPending}</p> : (
        <>
          {data.unreadable == null ? null : <p className="why alert" role="alert">{U.budgetsUnreadable(data.unreadable)}</p>}
          <ul className="usrows">
            {set.length === 0 ? <li className="usrow"><span>{U.budgetNone}</span><span /><span /></li> : null}
            {set.map((plan) => {
              const budget = budgetFor(data, plan.key);
              return (
                <li key={plan.key} className="usrow">
                  <b>{plan.label}</b>
                  <span>{budget === null || budget.daily_usd === null ? '' : U.budgetSet(fmtUsd(budget.daily_usd), plan.spentToday === null ? '$0' : fmtUsd(plan.spentToday), budget.action)}</span>
                  <Button small onClick={() => setEditing(plan.key)}>{U.budgetChange}</Button>
                </li>
              );
            })}
            {room ? <li className="usrow"><span /><span /><Button small onClick={() => setEditing(null)}>{U.budgetAdd}</Button></li> : null}
          </ul>
          {editing === undefined ? null : <BudgetDialog key={editing ?? 'new'} plans={plans} budgets={data.budgets} first={editing} onClose={() => setEditing(undefined)} />}
        </>
      )}
    </section>
  );
}
