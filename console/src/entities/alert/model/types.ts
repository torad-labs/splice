// Alerts, typed from FEATURES.md section 5 ("alerts as desktop notifications and an optional
// webhook") and section 6 (GET/PUT /api/alerts: "desktop notification and webhook settings, test
// send").
//
// PENDING V4-133. Two deliberate omissions, both because section 5 draws the line:
// - No chat-channel targets: Slack and PagerDuty are OUT, one webhook is in.
// - No delivery history: a "last fired" store is not in the route's purpose, and inventing one
//   would be a new daemon feature rather than a contract.
import type { PendingRoute } from '@shared/api';

export interface AlertSettings {
  /** Desktop notifications on or off. */
  desktop: boolean;
  /** The webhook endpoint, or null when none is configured. Absent is a state, not an empty URL:
   *  a page must not print a link that goes nowhere. */
  webhook_url: string | null;
}

export type AlertsSlice = AlertSettings | PendingRoute;
