import { useState } from 'react';
import { failureText } from '../../api/client';
import { usePlayground } from '../../api/playground';
import { useHeads } from '../../api/queries';
import { Y } from '../../lib/words-playground';
import { Button, Select } from '../../ui';
import { Row } from './Row';

/** One prompt through one plan. The exchange lives in this mutation and nowhere else: the next send replaces it and nothing stores it. */
export function Playground() {
  const heads = useHeads();
  const run = usePlayground();
  const [head, setHead] = useState('');
  const [prompt, setPrompt] = useState('');
  const options = [{ id: '', label: Y.pick }, ...(heads.data?.heads ?? []).map((one) => ({ id: one.key, label: one.label }))];
  const ready = head !== '' && prompt.trim() !== '' && !run.isPending;
  const send = (): void => {
    if (ready) run.mutate({ head, prompt });
  };
  return (
    <>
      <Row title={Y.title} why={Y.why} control={<Select label={Y.plan} value={head} options={options} onChange={setHead} />} />
      <form className="try" onSubmit={(event) => { event.preventDefault(); send(); }}>
        <input className="input wide" aria-label={Y.prompt} placeholder={Y.placeholder} value={prompt} spellCheck={false} onChange={(event) => setPrompt(event.currentTarget.value)} />
        <Button kind="go" type="submit" disabled={!ready}>{run.isPending ? Y.sending : Y.send}</Button>
        <Button disabled={run.isIdle} onClick={() => { run.reset(); }}>{Y.clear}</Button>
      </form>
      {run.data === undefined ? null : (
        <div className="exchange">
          <section aria-label={Y.sent}><h4>{Y.sent}</h4><pre>{JSON.stringify(run.data.request, null, 2)}</pre></section>
          <section aria-label={Y.answer(run.data.response.status)}><h4>{Y.answer(run.data.response.status)}</h4><pre>{JSON.stringify(run.data.response.body, null, 2)}</pre></section>
        </div>
      )}
      {run.isError ? <p className="hint alert row-note" role="alert">{Y.failed} {failureText(run.error)}</p> : null}
    </>
  );
}
