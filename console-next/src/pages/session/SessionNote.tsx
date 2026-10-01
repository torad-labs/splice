import { useState } from 'react';
import { failureText } from '../../api/client';
import { useSendNote } from '../../api/sessions';
import type { SessionRow } from '../../types/sessions';
import { noteRefusal } from '../../lib/note';
import { Composer, NoteClosed, NoteRefused } from './Composer';
import type { NotePhase } from './Composer';

/** The note box of one session: its draft, the send, and what came of it. A session that is not running, or has no id to address, or runs a
 *  Claude Code version the daemon does not send notes to, takes no note and says so before anyone types. Keyed by the session, so a draft never follows the operator to another one. */
export function SessionNote({ row, versions }: { row: SessionRow; versions: readonly string[] | undefined }) {
  const [draft, setDraft] = useState('');
  const send = useSendNote(row.session_id ?? '');
  if (row.availability !== 'live' || row.session_id === null) return <NoteClosed />;
  const refusal = noteRefusal(row.version, versions);
  if (refusal !== null) return <NoteRefused text={refusal} />;
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
