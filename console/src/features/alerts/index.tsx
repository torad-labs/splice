// The alerts panel: the one webhook splice posts to when a head passes a warn budget, mounted by the
// usage page beside the budgets it reports on.
//
// One webhook, not a list, and no chat channels: FEATURES.md section 5 rules Slack and PagerDuty
// out and keeps exactly this much in. The panel offers a TEST SEND because a webhook the operator
// cannot try is a webhook they will discover is wrong during an outage.
//
// NO DESKTOP SWITCH. The settings carry `desktop`, and the daemon delivers nothing to a desktop
// (AlertDelivery.kt's header: "not anywhere yet"), so a switch here would be a control that does
// nothing. The field still round-trips: a save keeps whatever the settings hold.
import { useEffect, useState } from 'react';
import { canTest, fetchAlerts, putAlerts, sendTestAlert, useAlerts } from '@entities/alert';
import { Input, Key } from '@shared/controls';
import { Empty, Section, Tip } from '@shared/ui';
import { H, S } from './strings';
import './alerts.css';

export function AlertsPanel() {
  const alerts = useAlerts((state) => state);
  const [url, setUrl] = useState<string | null>(null);
  const [note, setNote] = useState<{ text: string; failed: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { void fetchAlerts(); }, []);

  if (alerts.data !== null && 'pending' in alerts.data) {
    return (
      <Section title={S.title}>
        <Empty text={S.unavailable} source={H.unavailable} />
      </Section>
    );
  }

  const settings = alerts.data;
  const typed = url ?? (settings?.webhook_url ?? '');

  const run = (work: Promise<unknown>, done: string) => {
    setBusy(true);
    setNote(null);
    work.then(
      () => setNote({ text: done, failed: false }),
      (err: unknown) => setNote({ text: err instanceof Error ? err.message : String(err), failed: true }),
    ).finally(() => setBusy(false));
  };

  const test = (
    <Key busy={busy} disabled={!canTest(settings)} onClick={() => run(sendTestAlert(), S.sent)}>
      {S.test}
    </Key>
  );

  return (
    <Section title={S.title} info={{ text: H.about, label: S.about }} className="myx-alert">
      <div className="myx-alert-row">
        <Input label={S.webhook} value={typed} onChange={setUrl} w={40} placeholder="https://" disabled={settings === null} />
        <Key
          busy={busy}
          disabled={settings === null}
          onClick={() => {
            if (settings === null) return;
            const trimmed = typed.trim();
            run(putAlerts({ ...settings, webhook_url: trimmed === '' ? null : trimmed }), S.saved);
          }}
        >
          {S.save}
        </Key>
        {canTest(settings) ? test : <Tip text={H.saveFirst}>{test}</Tip>}
      </div>
      {note === null ? null : <p className={note.failed ? 'myx-alert-note myx-alert-failed' : 'myx-alert-note'} role="status">{note.text}</p>}
    </Section>
  );
}
