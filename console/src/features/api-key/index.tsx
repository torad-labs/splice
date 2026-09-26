// Store or remove an api-key head's key (V4-220 item 1: `splice key set|unset` over the console,
// PUT and DELETE /api/keys/{ENV}).
//
// THE VALUE GOES ONE WAY. It lives in the box until the store answers, then the box is emptied:
// nothing else holds it, and the answer carries none of it. The box is the world's secret Input, so
// it masks what is typed and no browser offers to keep it.
//
// THE ANSWER IS WHAT EACH HEAD READS NOW. The daemon re-reads after the write and names, per head,
// the link of its read chain that supplies the key; the daemon's environment and a key file are
// read before the store, so a stored key one of them shadows is said as such, never as applied.
// Remove is a Confirm, offered only while the store holds the key: there is nothing else to remove.
import { useState } from 'react';
import type { ReactNode } from 'react';
import { removeKey, storeKey } from '@entities/auth';
import type { KeyReader, KeyState } from '@entities/auth';
import { Confirm, Fault, Input, Key } from '@shared/controls';
import { H, S, SOURCE } from './strings';
import './api-key.css';

/** A read-chain link in the page's words, or the daemon's own word for a link this console does
 *  not know yet. */
export function sourceWord(source: string): string {
  return SOURCE[source] ?? source;
}

/** One head's line after a write: where it reads the key from now. */
export function readerLine(reader: KeyReader, name: string): string {
  switch (reader.source) {
    case 'store': return H.store(reader.head);
    case 'environment': return H.environment(reader.head, name);
    case 'file': return H.file(reader.head);
    case 'missing': return H.missing(reader.head);
    default: return H.other(reader.head, reader.source);
  }
}

type Answer = { kind: 'applied'; state: KeyState } | { kind: 'refused'; message: string };

export function ApiKeyForm({ name, stored, aside = null }: {
  /** The environment variable the key is stored under. */
  name: string;
  /** Whether splice's store holds this key now (GET /api/keys). */
  stored: boolean;
  /** What the host says beside the store key: the page's help on which read wins. */
  aside?: ReactNode;
}) {
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [answer, setAnswer] = useState<Answer | null>(null);

  const send = (write: Promise<KeyState>, clear: boolean) => {
    setBusy(true);
    setAnswer(null);
    write.then(
      (state) => {
        if (clear) setValue('');
        setAnswer({ kind: 'applied', state });
      },
      (err: unknown) => setAnswer({ kind: 'refused', message: err instanceof Error ? err.message : String(err) }),
    ).finally(() => setBusy(false));
  };

  return (
    <div className="myx-akey">
      <div className="myx-akey-row">
        <Input label={S.newKey} value={value} onChange={setValue} secret w={24} disabled={busy} />
        <Key disabled={busy || value.trim() === ''} onClick={() => send(storeKey(name, value), true)}>{S.store}</Key>
        {aside}
      </div>
      {stored ? (
        <div className="myx-akey-row">
          <Confirm label={S.remove} confirmLabel={S.removeArmed(name)} busy={busy} onConfirm={() => send(removeKey(name), false)} />
        </div>
      ) : null}
      {answer === null ? null : answer.kind === 'refused' ? <Fault message={answer.message} /> : (
        <ul className="myx-akey-read" role="status">
          {answer.state.heads.length === 0 ? <li>{H.noReader}</li>
            : answer.state.heads.map((reader) => <li key={reader.head}>{readerLine(reader, answer.state.name)}</li>)}
        </ul>
      )}
    </div>
  );
}
