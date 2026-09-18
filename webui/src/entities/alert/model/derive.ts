// The alert entity's pure half: what the current settings amount to, in one printed line.
import type { AlertSettings } from './types';

/** Desktop notifications as printed. */
export function desktopText(settings: AlertSettings | null): string {
  if (settings === null) return 'desktop unknown';
  return settings.desktop ? 'desktop on' : 'desktop off';
}

/**
 * The webhook as printed. A null URL is "no webhook", never an empty link: the page must not render
 * an anchor that goes nowhere, and "configured" with a blank value would.
 */
export function webhookText(settings: AlertSettings | null): string {
  if (settings === null) return 'webhook unknown';
  return settings.webhook_url === null ? 'no webhook' : settings.webhook_url;
}

/** Whether a test send is possible at all: it needs somewhere to send to. */
export function canTest(settings: AlertSettings | null): boolean {
  if (settings === null) return false;
  return settings.desktop || settings.webhook_url !== null;
}
