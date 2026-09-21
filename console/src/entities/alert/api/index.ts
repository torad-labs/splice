// The alert entity's HTTP segment. All three calls are pending V4-133.
import { pendingOf, request } from '@shared/api';
import { alertsStore } from '../model/store';
import type { AlertSettings } from '../model/types';

/** The v0.4.0 item that will serve the alert routes. */
export const PENDING_ALERTS = 'V4-133';

export async function fetchAlerts(): Promise<void> {
  alertsStore.startLoading();
  try {
    alertsStore.setData(await request<AlertSettings>('/api/alerts'));
  } catch (err) {
    const pending = pendingOf(err, PENDING_ALERTS);
    if (pending !== null) {
      alertsStore.setData(pending);
      return;
    }
    alertsStore.setError(err instanceof Error ? err.message : String(err));
  }
}

/** Write the settings; the daemon answers with what it now holds, and the store takes that. */
export async function putAlerts(settings: AlertSettings): Promise<AlertSettings | null> {
  try {
    const applied = await request<AlertSettings>('/api/alerts', {
      method: 'PUT',
      body: JSON.stringify(settings),
    });
    alertsStore.setData(applied);
    return applied;
  } catch (err) {
    const pending = pendingOf(err, PENDING_ALERTS);
    if (pending !== null) {
      alertsStore.setData(pending);
      return null;
    }
    alertsStore.setError(err instanceof Error ? err.message : String(err));
    return null;
  }
}

/**
 * Fire one test alert. Section 6 names the purpose ("test send") beside the GET/PUT pair, and a
 * test send is neither, so the console calls POST /api/alerts/test: a path the daemon row may
 * spell differently, which is why the pending state names the item rather than the path.
 *
 * No poller in this slice: settings change when the operator changes them, and a timer would only
 * race their own edit.
 */
export async function sendTestAlert(): Promise<boolean> {
  try {
    await request<unknown>('/api/alerts/test', { method: 'POST' });
    return true;
  } catch (err) {
    const pending = pendingOf(err, PENDING_ALERTS);
    if (pending !== null) {
      alertsStore.setData(pending);
      return false;
    }
    alertsStore.setError(err instanceof Error ? err.message : String(err));
    return false;
  }
}
