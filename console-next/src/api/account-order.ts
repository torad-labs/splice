import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pendingOf } from './auth';
import { failureText, MgmtError, request } from './client';
import { keys } from './queries';
import { awaitRefetch } from './refetch';

export interface AccountOrder {
  head: string;
  order: string[];
  effective_order: string[];
  single_account?: boolean;
}
export type AccountOrderState = AccountOrder | { unavailable: string };
export const orderPath = (head: string) => `/api/auth/${encodeURIComponent(head)}/order`;
const orderKey = (head: string) => ['account-order', head] as const;

export async function readAccountOrder(head: string): Promise<AccountOrderState> {
  try {
    return await request<AccountOrder>(orderPath(head));
  } catch (error) {
    if (pendingOf(error, 'account order') !== null || (error instanceof MgmtError && error.status === 409)) {
      return { unavailable: failureText(error) };
    }
    throw error;
  }
}

export const saveAccountOrder = (head: string, order: readonly string[]) =>
  request<AccountOrder>(orderPath(head), { method: 'PUT', body: JSON.stringify({ order }) });

export const useAccountOrder = (head: string, enabled = true) => useQuery({
  queryKey: orderKey(head), queryFn: () => readAccountOrder(head), refetchInterval: 10_000, enabled,
});

export function useSaveAccountOrder(head: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (order: readonly string[]) => saveAccountOrder(head, order),
    onSuccess: async (answer) => {
      client.setQueryData<AccountOrderState>(orderKey(head), answer);
      await awaitRefetch(client, [keys.accounts, keys.auth, keys.heads]);
    },
  });
}
