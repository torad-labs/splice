import { useState } from 'react';
import { storeKey } from '../api/client';
import { Button, Window } from '../ui';
import { C } from './copy';

/** The page opened without a key the daemon accepts. `splice dashboard` hands the key over in the
 *  address, so this is the fallback: paste the key from the state root. */
export function Unlock({ rejected }: { rejected: boolean }) {
  const [key, setKey] = useState('');
  return (
    <main className="unlock">
      <Window className="unlock-card" as="section">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (key.trim() !== '') storeKey(key);
          }}
        >
          <h1 className="mark">splice</h1>
          <p className="lede">{rejected ? C.unlockRejected : C.unlockAsk}</p>
          <label className="field">
            <span className="eyebrow">{C.keyLabel}</span>
            <input
              className="input mono"
              type="password"
              autoComplete="off"
              autoFocus
              spellCheck={false}
              value={key}
              onChange={(event) => setKey(event.target.value)}
            />
          </label>
          <p className="hint">{C.unlockHint}</p>
          <Button kind="go" type="submit" disabled={key.trim() === ''}>
            {C.unlock}
          </Button>
        </form>
      </Window>
    </main>
  );
}
