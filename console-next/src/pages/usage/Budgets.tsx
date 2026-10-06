import * as Dialog from '@radix-ui/react-dialog';
import { useState } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute } from '../../api/auth';
import { useModels } from '../../api/models';
import { CommandPricing } from './UsagePricing';
import { useBudgets, usePutBudgets } from '../../api/usage';
import { fmtUsd } from '../../lib/format';
import { budgetFor, hasBudgetPrices, nextBudgetResetAtEpochSeconds } from '../../lib/budget';
import { localZonedInstantText } from '../../lib/heads';
import { budgetWarning } from '../../lib/usage-breakdown';
import { B } from './copy';
import type { PlanUsage } from '../../lib/usage-page';
import { U } from '../../lib/words-usage';
import type { Budget, BudgetAction } from '../../types/budget';
import { Button, Close, Select } from '../../ui';

const ACTIONS = [
  { id: 'warn', label: U.budgetWarn, hint: U.budgetWarnHint },
  { id: 'block', label: U.budgetBlock, hint: U.budgetBlockHint },
] as const;

/** One plan's budget, set or changed. `first` is the plan it opens on: a budgeted plan to change, or null to pick one that has none. */
function BudgetDialog({ plans, budgets, first, onClose }: { plans: readonly PlanUsage[]; budgets: readonly Budget[]; first: string | null; onClose: () => void }) {
  const put = usePutBudgets();
  const models = useModels();
  const reset = localZonedInstantText(nextBudgetResetAtEpochSeconds(Date.now()));
  const [discarding, setDiscarding] = useState(false);
  const [openMenu, setOpenMenu] = useState<'command' | 'action' | null>(null);
  const catalogs = models.data === undefined || isPendingRoute(models.data) ? [] : models.data.heads;
  const hasPrices = (command: string): boolean => hasBudgetPrices(catalogs.find(head => head.head === command));
  const free = plans.filter((plan) => budgetFor({ budgets: [...budgets] }, plan.key)?.daily_usd == null).sort((a, b) => Number(hasPrices(b.key)) - Number(hasPrices(a.key)));
  const [key, setKey] = useState(first ?? '');
  const current = budgetFor({ budgets: [...budgets] }, key);
  const [amount, setAmount] = useState(current?.daily_usd == null ? '' : String(current.daily_usd));
  const usd = amount.trim() === '' ? null : Number(amount);
  const [action, setAction] = useState<BudgetAction>(current?.action ?? 'warn');
  const plan = plans.find((candidate) => candidate.key === key);
  const catalog = models.data === undefined || isPendingRoute(models.data) ? undefined : models.data.heads.find(head => head.head === key);
  const priced = hasBudgetPrices(catalog);
  const initial = budgetFor({ budgets: [...budgets] }, first ?? '');
  const dirty = key !== (first ?? '') || amount !== (initial?.daily_usd == null ? '' : String(initial.daily_usd)) || action !== (initial?.action ?? 'warn');
  const close = (): void => { if (openMenu !== null) setOpenMenu(null); else if (dirty) setDiscarding(true); else onClose(); };
  const others = budgets.filter((row) => row.head !== key);
  const write = (next: readonly Budget[]): void => put.mutate(next, { onSuccess: onClose });
  return (
    <Dialog.Root open onOpenChange={(open) => (open ? undefined : close())}>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog budget-dialog" onInteractOutside={event => {
            event.preventDefault();
            const target = event.detail.originalEvent.target;
            if (!(target instanceof Element) || target.closest('.budget-command-menu') === null) setOpenMenu(null);
          }}>
          <div className="dialog-head">
            <Dialog.Title>{plan === undefined ? U.budgetAdd : U.budgetDialog(plan.label)}</Dialog.Title>
            <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={U.budgetCancel}><Close /></button></Dialog.Close>
          </div>
          <Dialog.Description className="hint">{key === '' ? U.budgetChoose : models.isPending ? B.pricesReading : models.isError || catalog === undefined ? B.priceCatalogMissing : priced ? U.budgetDialogWhy : B.budgetUnpriced} {U.budgetResets(reset)}</Dialog.Description>
          <div className="budget-form">
            {first !== null ? null : (
              <div className="field"><span className="eyebrow">{U.budgetPlan}</span><Select value={key} placeholder={B.chooseCommand} options={free.map((candidate) => ({ id: candidate.key, label: candidate.label }))} onChange={setKey} label={U.budgetPlan} menuClassName="budget-command-menu" menuState={{ open: openMenu === 'command', onOpenChange: open => setOpenMenu(open ? 'command' : null) }} />{free.length <= 7 ? null : <small className="hint">{B.commandScroll}</small>}</div>
            )}
            <label className="field"><span className="eyebrow">{U.budgetDollars}</span><input className="input" type="number" min="0.01" step="0.01" value={amount} onChange={event => setAmount(event.currentTarget.value)} aria-label={U.budgetDollars} /></label>
            <div className="field"><span className="eyebrow">{U.budgetAction}</span><Select value={action} options={ACTIONS} onChange={setAction} label={U.budgetAction} menuClassName="budget-command-menu" menuState={{ open: openMenu === 'action', onOpenChange: open => setOpenMenu(open ? 'action' : null) }} /></div>
            {key === '' ? null : models.isPending ? <p className="hint">{B.pricesReading}</p> : models.isError ? <p className="hint alert">{failureText(models.error)}</p> : <>
              {priced ? <p className="hint" role="status">{B.budgetPriced}</p> : null}
              {priced ? null : <CommandPricing command={key} label={plan?.label ?? key} catalog={catalog} unpriced={0} usedModels={plan?.models?.length ? plan.models : catalog?.models.map(model => model.id) ?? []} subscription={plan?.subscription} path={undefined} />}
            </>}
            <div className="acts-row">
              <Button kind="go" disabled={put.isPending || !priced || usd === null || !Number.isFinite(usd) || usd <= 0 || key === ''} onClick={() => { if (usd !== null) write([...others, { head: key, daily_usd: usd, action }]); }}>{U.budgetSave}</Button>
              {first === null ? null : <Button kind="danger" disabled={put.isPending} onClick={() => write(others)}>{U.budgetRemove}</Button>}
            </div>
            {discarding ? <div role="alert"><p>{B.discardWhy}</p><div className="acts-row"><Button kind="danger" onClick={onClose}>{B.discard}</Button><Button onClick={() => setDiscarding(false)}>{B.keepEditing}</Button></div></div> : null}
            {put.isError ? <p className="hint alert" role="alert">{U.budgetFailed} {failureText(put.error)}</p> : null}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** Measured spending in the daemon's actual daily budget window, never a rolling-hour estimate. */
export function BudgetBalance({ budget }: { budget: Budget | null }) {
  if (budget?.daily_usd == null) return null;
  const spent = budget.used_usd ?? null;
  const warning = budgetWarning(budget.daily_usd, spent);
  return <div>
    <span>{spent === null ? `${fmtUsd(budget.daily_usd)} a day. ${budget.spend_pending === true ? U.budgetReading : B.spentUnknown}` : U.budgetSet(fmtUsd(budget.daily_usd), fmtUsd(spent), budget.action)}{spent === null || budget.remaining_usd == null ? '' : ` ${U.budgetLeft(fmtUsd(budget.remaining_usd))}`}</span>
    {warning === null ? null : <p className="budget-early-warning" role="status">{warning.kind === 'reached' ? B.reached : B.near(warning.remaining)}</p>}
  </div>;
}

/** Each plan's own daily dollars ceiling, and what to do when it is reached. Only plans that have one are listed. */
export function Budgets({ plans }: { plans: readonly PlanUsage[] }) {
  const read = useBudgets();
  const data = read.data;
  const reset = localZonedInstantText(nextBudgetResetAtEpochSeconds(Date.now()));
  const [editing, setEditing] = useState<string | null | undefined>(undefined);
  const set = data === undefined || isPendingRoute(data) ? [] : plans.filter((plan) => budgetFor(data, plan.key)?.daily_usd != null);
  const room = data === undefined || isPendingRoute(data) ? false : plans.some((plan) => budgetFor(data, plan.key)?.daily_usd == null);
  return (
    <section className="section" aria-labelledby="usage-budgets">
      <h2 id="usage-budgets">{U.budgetsTitle}</h2>
      <p className="why">{U.budgetsWhy} {U.budgetResets(reset)}</p>
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
                  <BudgetBalance budget={budget} />
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
