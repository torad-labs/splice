// The alert entity's pure half: what the current settings allow.
import type { AlertSettings } from './types';

/** Whether a test send can go anywhere: the daemon posts it to the SAVED webhook and answers 409
 *  without one (AlertRoutes.test). Desktop is no destination, since the daemon delivers nothing
 *  to a desktop (AlertDelivery.kt's header). */
export function canTest(settings: AlertSettings | null): boolean {
  return settings !== null && settings.webhook_url !== null;
}
