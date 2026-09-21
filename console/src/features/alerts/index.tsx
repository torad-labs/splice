// The alerts panel: desktop notifications and one webhook, mounted by the usage page.
//
// One webhook, not a list, and no chat channels: FEATURES.md section 5 rules Slack and PagerDuty
// out and keeps exactly this much in. The panel offers a TEST SEND because a webhook the operator
// cannot try is a webhook they will discover is wrong during an outage.
import { useEffect, useState } from 'react';
import { PENDING_ALERTS, canTest, desktopText, fetchAlerts, putAlerts, sendTestAlert, useAlerts, webhookText } from '@entities/alert';
import { Empty, FieldBox } from '@shared/ui';
import { S } from './strings';
import './alerts.css';

export function AlertsPanel() {
  const alerts = useAlerts((state) => state);
  const [url, setUrl] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { void fetchAlerts(); }, []);

  if (alerts.data !== null && 'pending' in alerts.data) {
    return <Empty text="alerts not built" source={`row ${PENDING_ALERTS}`} />;
  }

  const settings = alerts.data !== null && !('pending' in alerts.data) ? alerts.data : null;
  const typed = url ?? (settings?.webhook_url ?? '');

  const run = (work: Promise<unknown>, done: string) => {
    setBusy(true);
    setNote(null);
    work.then(
      () => setNote(done),
      (err: unknown) => setNote(err instanceof Error ? err.message : String(err)),
    ).finally(() => setBusy(false));
  };

  return (
    <section className="myx-alert">
      <h2 className="myx-alert-title">{S.title}</h2>
      <div className="myx-alert-row">
        <button
          type="button"
          className="myx-alert-btn"
          disabled={busy || settings === null}
          onClick={() => {
            if (settings === null) return;
            run(putAlerts({ ...settings, desktop: !settings.desktop }), 'saved');
          }}
        >
          {`${S.desktop} ${settings?.desktop === true ? S.on : S.off}`}
        </button>
        <span className="myx-alert-note">{desktopText(settings)}</span>
      </div>

      <div className="myx-alert-row">
        <FieldBox
          label={S.webhook}
          value={typed}
          provenance="state file"
          hot
          onChange={setUrl}
        />
        <button
          type="button"
          className="myx-alert-btn"
          disabled={busy || settings === null}
          onClick={() => {
            if (settings === null) return;
            const trimmed = typed.trim();
            run(putAlerts({ ...settings, webhook_url: trimmed === '' ? null : trimmed }), 'saved');
          }}
        >
          {S.save}
        </button>
      </div>

      <div className="myx-alert-row">
        {/* A test send needs somewhere to send to: with desktop off and no webhook it is disabled
            rather than firing into nothing and reporting success. */}
        <button
          type="button"
          className="myx-alert-btn"
          disabled={busy || !canTest(settings)}
          onClick={() => run(sendTestAlert(), 'sent')}
        >
          {S.test}
        </button>
        <span className="myx-alert-note">{webhookText(settings)}</span>
      </div>

      {note === null ? null : <p className="myx-alert-note" role="status">{note}</p>}
    </section>
  );
}
