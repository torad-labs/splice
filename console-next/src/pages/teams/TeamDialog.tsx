import * as Dialog from '@radix-ui/react-dialog';
import { useRef, useState } from 'react';
import { failureText } from '../../api/client';
import { useHeads, useSessions } from '../../api/queries';
import { useSaveTeam } from '../../api/teams';
import { addSeat, blankDraft, draftOf, editSeat, keyFor, removeSeat, setLead, validateDraft, writeOf } from '../../lib/teams-page';
import type { TeamDraft } from '../../lib/teams-page';
import { sessionLabel } from '../../lib/sessions';
import { M } from '../../lib/words-teams';
import type { TeamRow } from '../../types/teams';
import { Button, Close, Select } from '../../ui';

const C = M.compose;
const mint = (): string => crypto.randomUUID();

/** Compose a new team (`team` null) or edit one: its facts, and every seat's role, plan, session and standing instructions. */
export function TeamDialog({ team, onClose, onSaved }: { team: TeamRow | null; onClose: () => void; onSaved: (saved: TeamRow) => void }) {
  const heads = useHeads();
  const sessions = useSessions();
  const save = useSaveTeam();
  const [draft, setDraft] = useState<TeamDraft>(() => (team === null ? blankDraft(mint()) : draftOf(team)));
  const key = useRef<{ body: string; key: string } | null>(null);
  const [tried, setTried] = useState(false);
  const problems = validateDraft(draft);
  const plans = (heads.data?.heads ?? []).map((head) => ({ id: head.key, label: head.label }));
  const rows = sessions.data?.sessions ?? [];
  const taken = new Set(draft.seats.flatMap((seat) => (seat.session === null ? [] : [seat.session])));
  const repos = [...new Set(rows.flatMap((row) => (row.repo === undefined ? [] : [row.repo.root])))];
  const set = (change: Partial<TeamDraft>): void => setDraft({ ...draft, ...change });

  const submit = (): void => {
    setTried(true);
    if (problems.length > 0) return;
    const body = writeOf(draft);
    key.current = keyFor(key.current, JSON.stringify(body), mint);
    save.mutate({ team, body, key: key.current.key }, { onSuccess: onSaved });
  };

  return (
    <Dialog.Root open onOpenChange={(open) => (open ? undefined : onClose())}>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog wide">
          <div className="dialog-head">
            <Dialog.Title>{team === null ? C.newTitle : C.editTitle(team.name)}</Dialog.Title>
            <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={C.cancel}><Close /></button></Dialog.Close>
          </div>
          <Dialog.Description className="hint">{C.why}</Dialog.Description>
          <form className="compose" onSubmit={(event) => { event.preventDefault(); submit(); }}>
            <label className="field"><span className="eyebrow">{C.name}</span><input className="input" value={draft.name} onChange={(event) => set({ name: event.target.value })} autoFocus /></label>
            <label className="field"><span className="eyebrow">{C.goal}</span><textarea className="input" value={draft.goal} onChange={(event) => set({ goal: event.target.value })} /></label>
            <label className="field"><span className="eyebrow">{C.features}</span><textarea className="input" value={draft.features} onChange={(event) => set({ features: event.target.value })} /><span className="hint">{C.featuresHint}</span></label>
            <label className="field">
              <span className="eyebrow">{C.repo}</span>
              <input className="input" list="team-repos" spellCheck={false} value={draft.repo} onChange={(event) => set({ repo: event.target.value })} />
              <datalist id="team-repos">{repos.map((repo) => <option key={repo} value={repo} />)}</datalist>
              <span className="hint">{C.repoHint}</span>
            </label>
            <h3 className="eyebrow">{C.seats}</h3>
            {draft.seats.map((seat, index) => (
              <div key={seat.id} className="seatform" role="group" aria-label={C.seat(index + 1)}>
                <div className="line">
                  <label className="field"><span className="eyebrow">{C.role}</span><input className="input" value={seat.role} onChange={(event) => setDraft(editSeat(draft, index, { role: event.target.value }))} /></label>
                  <div className="field"><span className="eyebrow">{C.plan}</span>
                    <Select value={seat.head} options={seat.head === '' ? [{ id: '', label: C.choosePlan }, ...plans] : plans} onChange={(head) => setDraft(editSeat(draft, index, { head }))} label={`${C.seat(index + 1)} ${C.plan}`} />
                  </div>
                </div>
                <div className="field"><span className="eyebrow">{C.session}</span>
                  <Select
                    value={seat.session ?? ''}
                    options={[{ id: '', label: C.sessionNone }, ...rows.flatMap((row) => (row.session_id === null || (taken.has(row.session_id) && row.session_id !== seat.session) ? [] : [{ id: row.session_id, label: sessionLabel(row) }]))]}
                    onChange={(session) => setDraft(editSeat(draft, index, { session: session === '' ? null : session }))}
                    label={`${C.seat(index + 1)} ${C.session}`}
                  />
                </div>
                <label className="field"><span className="eyebrow">{C.instructions}</span><textarea className="input" value={seat.instructions} onChange={(event) => setDraft(editSeat(draft, index, { instructions: event.target.value }))} /><span className="hint">{C.instructionsHint}</span></label>
                <div className="acts-row">
                  {seat.lead ? <span className="tag">{C.leadSeat}</span> : <Button small onClick={() => setDraft(setLead(draft, index))}>{C.makeLead}</Button>}
                  <Button small kind="danger" onClick={() => setDraft(removeSeat(draft, index))}>{C.removeSeat}</Button>
                </div>
              </div>
            ))}
            <div><Button small onClick={() => setDraft(addSeat(draft, mint()))}>{C.addSeat}</Button></div>
            {tried && problems.length > 0 ? <ul className="problems" role="alert">{problems.map((problem) => <li key={problem}>{problem}</li>)}</ul> : null}
            {save.isError ? <p className="hint alert" role="alert">{C.failed} {failureText(save.error)}</p> : null}
            <div className="acts-row"><Button kind="go" type="submit" disabled={save.isPending}>{save.isPending ? C.saving : C.save}</Button></div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
