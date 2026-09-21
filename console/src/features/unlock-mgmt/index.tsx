// Key gate: the management bearer key is pasted once and kept in localStorage.
// The hint below names the VERB, not a path: V4-177 made the state root install-dependent
// (~/.splice/state, or a pre-0.4 ~/.claude-codex/state adopted in place, or SPLICE_STATE_DIR),
// and this modal is pre-auth so it cannot ask the daemon which one it is. `splice dashboard`
// resolves the root itself and prints the key, so it is right on every install.
import { useState } from 'react';
import { unlock, useSession } from '@entities/session';
import { Btn, Field, Well } from '@shared/ui';

export function UnlockMgmt() {
  const locked = useSession((s) => s.locked);
  const [key, setKey] = useState('');
  if (!locked) return null;

  return (
    <div className="myx-modal-scrim" role="dialog" aria-modal="true" aria-label="management key required">
      <div className="myx-modal">
        <h3 className="myx-modal-title">management key required</h3>
        <Well>splice dashboard</Well>
        <form
          className="myx-unlock-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (key.trim()) unlock(key);
          }}
        >
          <Field label="key" htmlFor="mgmt-key">
            <input
              id="mgmt-key"
              type="password"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              autoComplete="off"
              spellCheck={false}
            />
          </Field>
          <Btn kind="primary" type="submit">unlock</Btn>
        </form>
      </div>
    </div>
  );
}
