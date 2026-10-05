import { useState } from 'react';
import { isPendingRoute } from '../../api/auth';
import { failureText } from '../../api/client';
import { useBudgets, usePutBudgets } from '../../api/usage';
import { fmtUsd } from '../../lib/format';
import { U } from '../../lib/words-usage';
import { Button } from '../../ui';
import { A } from './copy';

export function AccountBudget({ head, label = head }: { head: string; label?: string }) {
  const read = useBudgets();
  const put = usePutBudgets();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState('');
  const data = read.data;
  if (read.isError) return <p className="hint alert" role="alert">{failureText(read.error)}</p>;
  if (data === undefined) return <p className="hint">{A.budgetUnread}</p>;
  if (isPendingRoute(data)) return <p className="hint">{A.budgetMissing}</p>;
  if (data.unreadable != null) return <p className="hint alert" role="alert">{data.unreadable}</p>;
  const budget = data.budgets.find(row => row.head === head);
  const cap = budget?.daily_usd ?? null;
  const value = text.trim() === '' ? null : Number(text);
  const valid = value === null || (Number.isFinite(value) && value > 0);
  const write = (): void => {
    if (!valid) return;
    const others = data.budgets.filter(row => row.head !== head).map(row => ({ head: row.head, daily_usd: row.daily_usd, action: row.action }));
    put.mutate([...others, { head, daily_usd: value, action: budget?.action ?? 'warn' }], {
      onSuccess: answer => { if (!isPendingRoute(answer)) setEditing(false); },
    });
  };
  return (
    <div className="account-budget">
      <b>{A.headBudget(label)}</b>
      <span>{cap === null ? A.noCapWhy : fmtUsd(cap)}</span>
      {cap === null ? null : budget?.used_usd == null ? <span className="hint">{budget?.spend_pending === true ? U.budgetReading : A.spendUnknown}</span> : <>
        <span>{A.spent}: {fmtUsd(budget.used_usd)}</span>
        {budget.remaining_usd == null ? null : <span>{A.left}: {fmtUsd(budget.remaining_usd)}</span>}
      </>}
      {editing ? <form className="acts-row" onSubmit={event => { event.preventDefault(); write(); }}>
        <label className="sr" htmlFor={`budget-${head}`}>{A.headBudget(label)}</label>
        <input id={`budget-${head}`} className="input" type="number" min="0.01" step="0.01" value={text} placeholder={A.noCap} onChange={event => setText(event.target.value)} autoFocus />
        <Button small type="submit" disabled={put.isPending || !valid}>{put.isPending ? A.saving : A.save}</Button>
        <Button small disabled={put.isPending} onClick={() => setEditing(false)}>{A.cancel}</Button>
        {!valid ? <span role="alert">{A.invalidCap}</span> : null}
      </form> : <Button small onClick={() => { setText(cap === null ? '' : String(cap)); put.reset(); setEditing(true); }}>{cap === null ? A.setCap : A.editCap}</Button>}
      <span className="hint">{A.budgetScope}</span>
      {put.isError ? <span className="hint alert" role="alert">{failureText(put.error)}</span> : null}
      {put.data !== undefined && isPendingRoute(put.data) ? <span className="hint" role="status">{A.budgetMissing}</span> : null}
    </div>
  );
}
