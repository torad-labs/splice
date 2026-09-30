import { useEffect, useState } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute } from '../../api/auth';
import { useAlerts, usePutAlerts, useTestAlert } from '../../api/usage';
import { canTest } from '../../lib/alerts';
import { U } from '../../lib/words-usage';
import type { AlertSettings } from '../../types/alerts';
import { Button } from '../../ui';

/** Only the webhook is here: the daemon delivers nothing to a desktop and this console shows no notification (AlertDelivery.kt's header), so a desktop switch would promise what nothing does. */
export function Settings({ settings }: { settings: AlertSettings }) {
  const put = usePutAlerts();
  const test = useTestAlert();
  const [url, setUrl] = useState(settings.webhook_url ?? '');
  useEffect(() => setUrl(settings.webhook_url ?? ''), [settings.webhook_url]);
  const saved = (settings.webhook_url ?? '') === url.trim();
  return (
    <ul className="usrows">
      <li className="usrow">
        <b>{U.alertWebhook}</b>
        <div>
          <input className="input" aria-label={U.alertWebhook} spellCheck={false} placeholder={U.alertWebhookNone} value={url} onChange={(event) => setUrl(event.target.value)} />
          <span className="hint">{U.alertWebhookWhy}</span>
        </div>
        <Button small disabled={saved || put.isPending} onClick={() => put.mutate({ ...settings, webhook_url: url.trim() === '' ? null : url.trim() })}>{U.alertWebhookSave}</Button>
      </li>
      <li className="usrow">
        <b>{U.alertTest}</b>
        <div>
          <span>{canTest(settings) ? U.alertTestWhy : U.alertTestNeeds}</span>
          {test.isSuccess && !isPendingRoute(test.data) ? <span className="hint" role="status">{U.alertTestSent}</span> : null}
          {test.isError ? <span className="hint alert" role="alert">{U.alertFailed} {failureText(test.error)}</span> : null}
        </div>
        <Button small disabled={!canTest(settings) || test.isPending} onClick={() => test.mutate()}>{U.alertTest}</Button>
      </li>
      {put.isError ? <li className="usrow"><span className="hint alert" role="alert">{U.alertFailed} {failureText(put.error)}</span></li> : null}
    </ul>
  );
}

/** Where splice speaks up when a plan nears a limit or a budget. */
export function Alerts() {
  const read = useAlerts();
  const data = read.data;
  return (
    <section className="section" aria-labelledby="usage-alerts">
      <h2 id="usage-alerts">{U.alertsTitle}</h2>
      <p className="why">{U.alertsWhy}</p>
      {read.isError ? <p className="why alert" role="alert">{failureText(read.error)}</p> : null}
      {data === undefined ? null : isPendingRoute(data) ? <p className="why">{U.alertsPending}</p> : <Settings settings={data} />}
    </section>
  );
}
