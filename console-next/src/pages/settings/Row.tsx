import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { failureText } from '../../api/client';
import { useKnobSave } from '../../api/config';
import { outcomeOf } from '../../lib/settings';
import type { SaveOutcome } from '../../lib/settings';
import { useRestartPending } from '../../lib/restart-pending';
import { useShowKeys } from '../../lib/show-keys';
import type { ConfigValue } from '../../types/core';
import { Check, Clock, KeyIcon } from '../../ui';
import { DaemonRestart } from '../shared/DaemonRestart';
import { T } from './copy';

/** A setting's key, on demand: the icon on a row reveals just that row's key; Advanced › Show setting keys reveals every one. */
function KeyChip({ name }: { name: string }) {
  const all = useShowKeys();
  const [own, setOwn] = useState(false);
  const shown = all || own;
  return (
    <button type="button" className={`key${shown ? ' on' : ' hid'}`} aria-label={T.showKey} aria-pressed={shown} onClick={() => setOwn(!own)}>
      <KeyIcon />
      {shown ? <code>{name}</code> : null}
    </button>
  );
}

/** One setting: what it is called, one sentence, its typed control on the right, and under both what its last save said. */
export function Row({ title, why, settingKey, control, note }: { title: string; why: string; settingKey?: string; control: ReactNode; note?: ReactNode }) {
  return (
    <div className="row">
      <div>
        <h3>{title}</h3>
        <p>{why}</p>
        {settingKey === undefined ? null : <KeyChip name={settingKey} />}
        {note}
      </div>
      <div className="ctl">{control}</div>
    </div>
  );
}

export type Saved = SaveOutcome | { kind: 'failed'; message: string } | { kind: 'saving' } | null;

/** A saved value's state under its row: saved, waiting for a restart (with the one act that ends the wait), refused, or only live. */
export function SaveNote({ keys, saved }: { keys: readonly string[]; saved: Saved }) {
  const pending = useRestartPending();
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    if (saved?.kind !== 'saved') return undefined;
    setSeen(true);
    const timer = window.setTimeout(() => setSeen(false), 2_500);
    return () => window.clearTimeout(timer);
  }, [saved]);
  const waiting = keys.some((key) => pending.includes(key));
  return (
    <>
      {saved?.kind === 'saved' && seen ? <span className="saved" role="status"><Check />{T.saved}</span> : null}
      {saved?.kind === 'saving' ? <span className="tip">{T.saving}</span> : null}
      {saved?.kind === 'rejected' ? <span className="tip bad" role="alert">{T.rejected} {saved.reason}</span> : null}
      {saved?.kind === 'failed' ? <span className="tip bad" role="alert">{T.failed} {saved.message}</span> : null}
      {saved?.kind === 'live-only' ? <span className="tip bad" role="alert">{T.liveOnly} {saved.why}</span> : null}
      {waiting ? (
        <span className="tip-restart">
          <Clock />
          {T.waits} ·{' '}
          <DaemonRestart label={T.restartNow} small />
        </span>
      ) : null}
    </>
  );
}

/** Save one knob and remember what the daemon answered. The value shown is the daemon's, read again after every save. */
export function useSetting(key: string): { save: (value: ConfigValue) => void; saved: Saved } {
  const write = useKnobSave();
  const [saved, setSaved] = useState<Saved>(null);
  return {
    saved: write.isPending ? { kind: 'saving' } : saved,
    save: (value) =>
      write.mutate({ [key]: value }, { onSuccess: (result) => setSaved(outcomeOf(key, result)), onError: (err) => setSaved({ kind: 'failed', message: failureText(err) }) }),
  };
}
