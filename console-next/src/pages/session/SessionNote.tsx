import { useState } from 'react';
import { failureText } from '../../api/client';
import { useSendNote } from '../../api/sessions';
import type { SessionRow } from '../../types/sessions';
import { Composer, NoteClosed } from './Composer';
import type { NotePhase } from './Composer';

/** The note box of one session: its draft, the send, and what came of it. A session that is not running, or has no id to address, takes
 *  no note and says so. Keyed by the session, so a draft never follows the operator to another one. */
export function SessionNote({ row }: { row: SessionRow }) {
  const [draft, setDraft] = useState('');
  const send = useSendNote(row.session_id ?? '');
  if (row.availability !== 'live' || row.session_id === null) return <NoteClosed />;
  const phase: NotePhase = send.isPending ? 'sending' : send.isSuccess ? 'sent' : send.isError ? 'failed' : 'idle';
  return (
    <Composer
      draft={draft}
      phase={phase}
      failure={send.isError ? failureText(send.error) : null}
      onDraft={(next) => {
        setDraft(next);
        if (!send.isPending) send.reset();
      }}
      onSend={() => send.mutate(draft, { onSuccess: () => setDraft('') })}
    />
  );
}
