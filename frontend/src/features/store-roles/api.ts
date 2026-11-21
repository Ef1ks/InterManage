import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'

import { meQueryKey } from '@/features/auth/api'
import { api } from '@/lib/api'
import type { StoreRoleRequest, StoreRoles } from '@/lib/types'

export const storeRolesKey = (storeId: string) => ['store-roles', storeId] as const

/** Hierarchia sklepu: rangi od najwyższej, ranga pytającego i propozycje od sklepów zaprzyjaźnionych. */
export function useStoreRoles(storeId: string | undefined) {
  return useQuery({
    queryKey: storeRolesKey(storeId ?? ''),
    queryFn: () => api<StoreRoles>(`/stores/${storeId}/roles`),
    enabled: !!storeId,
  })
}

/** Rangi kilku sklepów naraz (formularz konta: ranga w każdym wybranym sklepie). */
export function useStoresRoles(storeIds: string[]) {
  return useQueries({
    queries: storeIds.map((storeId) => ({
      queryKey: storeRolesKey(storeId),
      queryFn: () => api<StoreRoles>(`/stores/${storeId}/roles`),
    })),
  })
}

export type StoreRoleAction =
  | { type: 'create'; request: StoreRoleRequest }
  | { type: 'update'; id: string; request: StoreRoleRequest }
  | { type: 'delete'; id: string }
  | { type: 'restore'; id: string }
  | { type: 'order'; roleIds: string[] }
  | { type: 'offer'; storeIds: string[] }
  | { type: 'accept' | 'reject' | 'cancel' | 'undo'; offerId: string }

/** Każda zmiana zwraca całą hierarchię, a uprawnienia konta (`/auth/me`) mogą się od niej zmienić. */
export function useStoreRoleChange(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (action: StoreRoleAction) => {
      const base = `/stores/${storeId}/roles`
      switch (action.type) {
        case 'create':
          return api<StoreRoles>(base, { method: 'POST', json: action.request })
        case 'update':
          return api<StoreRoles>(`${base}/${action.id}`, { method: 'PUT', json: action.request })
        case 'delete':
          return api<StoreRoles>(`${base}/${action.id}`, { method: 'DELETE' })
        case 'restore':
          return api<StoreRoles>(`${base}/${action.id}/restore`, { method: 'POST' })
        case 'order':
          return api<StoreRoles>(`${base}/order`, { method: 'PUT', json: { roleIds: action.roleIds } })
        case 'offer':
          return api<StoreRoles>(`${base}/offers`, { method: 'POST', json: { storeIds: action.storeIds } })
        case 'cancel':
          return api<StoreRoles>(`${base}/offers/${action.offerId}`, { method: 'DELETE' })
        default:
          return api<StoreRoles>(`${base}/offers/${action.offerId}/${action.type}`, { method: 'POST' })
      }
    },
    onSuccess: (roles) => {
      queryClient.setQueryData(storeRolesKey(storeId), roles)
      queryClient.invalidateQueries({ queryKey: meQueryKey })
      queryClient.invalidateQueries({ queryKey: ['settings', 'users'] })
    },
    meta: { invalidates: false },
  })
}
