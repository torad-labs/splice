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

// THE ANSWER BELONGS TO THE VALUE IT WAS GIVEN FOR. A detail column reuses one Copy as the operator
// opens another head or check, so a state held alone said `copied` beside a command that was never
// copied, and a paste put the previous head's command in the terminal (code review, 2026-09-24). The
// state records the value it answered, and any other value reads as not yet pressed.
type CopyState = { outcome: 'copied' | 'refused'; value: string } | null;

export function copyLabel(state: CopyState, value: string, label: string): string {
  if (state === null || state.value !== value) return label;
  return state.outcome === 'copied' ? S.copied : S.copyByHand;
}

export function Copy({ value, label = S.copy }: { value: string; label?: string }) {
  const [state, setState] = useState<CopyState>(null);
  const press = () => {
    const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
    if (clipboard === undefined) {
      setState({ outcome: 'refused', value });
      return;
    }
    clipboard.writeText(value).then(
      () => setState({ outcome: 'copied', value }),
      () => setState({ outcome: 'refused', value }),
    );
  };
  return <Key onClick={press}>{copyLabel(state, value, label)}</Key>;
}
