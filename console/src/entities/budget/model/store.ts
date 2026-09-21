import { createResource } from '@shared/lib';
import type { BudgetsSlice } from './types';

/** The budgets. A union with PendingRoute because GET /api/budgets does not exist yet (V4-133). */
export const budgetsStore = createResource<BudgetsSlice>();
