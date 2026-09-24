// The world's copy key: puts a value on the clipboard and says whether it got there.
//
// Three pages carried their own copy (doctor's fix, the login ticket's code and link, and now the
// fleet's key command), each claiming to "admit when the clipboard is unavailable" while printing
// nothing when it was: a console served over plain http has no navigator.clipboard, and the key
// simply did not answer. This one answers every press: `copied`, or `copy by hand` beside the value
// the caller already prints, so the operator knows to select it instead.
import { useState } from 'react';
import { Key } from './key';
import { S } from './strings';

type CopyState = 'idle' | 'copied' | 'refused';

export function Copy({ value, label = S.copy }: { value: string; label?: string }) {
  const [state, setState] = useState<CopyState>('idle');
  const press = () => {
    const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
    if (clipboard === undefined) {
      setState('refused');
      return;
    }
    clipboard.writeText(value).then(() => setState('copied'), () => setState('refused'));
  };
  return (
    <Key onClick={press}>
      {state === 'copied' ? S.copied : state === 'refused' ? S.copyByHand : label}
    </Key>
  );
}
