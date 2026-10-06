import { useState } from 'react';
import { failureText } from '../../api/client';
import { useDeleteKey, useKeyStore, usePutKey } from '../../api/auth';
import { useAuth } from '../../api/queries';
import { answerLines, headKeys, sourceWord } from '../../lib/keys';
import type { HeadKeyFacts } from '../../lib/keys';
import { KW } from '../../lib/words-keys';
import type { KeyState } from '../../types/login';
import { Button, Confirm, Fault, Prompt } from '../../ui';

/** One key an api-key head reads: where it reads it from now, a way to store or replace it, and (while splice's store holds it) a way to remove it.
 *  The value goes one way: the dialog's field is forgotten when it closes and no answer carries it. */
function KeyRow({ head, facts, keyFile }: { head: string; facts: HeadKeyFacts; keyFile: string | undefined }) {
  const put = usePutKey();
  const remove = useDeleteKey();
  const [answer, setAnswer] = useState<string[] | null>(null);
  const said = (state: KeyState): void => setAnswer(answerLines(state, head));
  return (
    <div className="head-key">
      <dl className="key-source">
        <dt>{KW.readsFrom}</dt>
        <dd>{sourceWord(facts.source)}</dd>
      </dl>
      {keyFile === undefined ? null : <p className="hint key-file">{KW.configuredFile}: <code>{keyFile}</code></p>}
      <div className="acts-row">
        <Prompt
          trigger={<Button small>{facts.stored ? KW.replace : KW.store}</Button>}
          title={KW.ask(facts.name)}
          why={KW.why}
          field={KW.field}
          submit={KW.save}
          cancel={KW.cancel}
          secret
          onSubmit={async (text) => said(await put.mutateAsync({ name: facts.name, value: text.trim() }))}
        />
        {facts.stored ? (
          <Confirm
            trigger={<Button small kind="danger">{KW.remove}</Button>}
            title={KW.removeAsk}
            why={KW.removeWhy}
            act={KW.removeAct(facts.name)}
            cancel={KW.cancel}
            onConfirm={async () => said(await remove.mutateAsync(facts.name))}
          />
        ) : null}
      </div>
      {answer === null ? null : <p className="hint" role="status">{answer.length === 0 ? KW.noReader : answer.join(' ')}</p>}
    </div>
  );
}

/** The key control of an api-key head: one row per variable the daemon says the head reads. */
export function HeadKey({ head }: { head: string }) {
  const store = useKeyStore();
  const auth = useAuth();
  if (store.isError) return <Fault message={failureText(store.error)} onRetry={() => void store.refetch()} />;
  const keys = headKeys(store.data, head);
  return (
    <>
      <h2 className="sub-head">{KW.title}</h2>
      {keys.length === 0 ? <p className="hint">{KW.none}</p> : keys.map((facts) => <KeyRow key={facts.name} head={head} facts={facts} keyFile={auth.data?.[head]?.key_file} />)}
    </>
  );
}
