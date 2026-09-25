// Key gate, the FALLBACK: `splice dashboard` opens the console with its key and a browser keeps it,
// so this shows only when neither happened (a new browser profile, cleared storage, a rotated key).
// The pasted key is kept in localStorage.
// The hint names the VERB, not a path: V4-177 made the state root install-dependent
// (~/.splice/state, or a pre-0.4 ~/.claude-codex/state adopted in place, or SPLICE_STATE_DIR),
// and this modal is pre-auth so it cannot ask the daemon which one it is. `splice dashboard`
// resolves the root itself and opens this console unlocked, so it is right on every install.
import { useState } from 'react';
import { unlock, useSession } from '@entities/session';
import { Fault, Input, Key } from '@shared/controls';
import { H, S, U } from './strings';
import './unlock-mgmt.css';

export function UnlockMgmt() {
  const locked = useSession((s) => s.locked);
  const refused = useSession((s) => s.refused);
  if (!locked) return null;
  return <UnlockForm refused={refused} />;
}

/** The modal, drawn from the gate's state so a test can render it (a static render only ever sees
 *  a store's initial state). It mounts with the lock, so a refused key comes back as an empty field
 *  under the refusal rather than the rejected key still sitting in it. */
export function UnlockForm({ refused }: { refused: boolean }) {
  const [key, setKey] = useState('');

  return (
    <div className="myx-modal-scrim" role="dialog" aria-modal="true" aria-label={S.title}>
      <div className="myx-modal">
        <h3 className="myx-modal-title">{S.title}</h3>
        <p className="myx-unlock-hint">
          <span>{S.openWith}</span>
          <code className="myx-unlock-command">{U.command}</code>
        </p>
        {refused ? <Fault message={H.refused} /> : null}
        <form
          className="myx-unlock-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (key.trim()) unlock(key);
          }}
        >
          <Input id="mgmt-key" label={S.key} value={key} onChange={setKey} secret w={44} />
          <Key type="submit">{S.unlock}</Key>
        </form>
      </div>
    </div>
  );
}
