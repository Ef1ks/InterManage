import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from '@/lib/api'
import { setAgentStatus } from '@/lib/store-agent'
import type { AgentStatus, StoreAgents } from '@/lib/types'

export const agentsKey = (storeId: string) => ['stores', storeId, 'agents'] as const
const statusKey = (storeId: string) => [...agentsKey(storeId), 'status'] as const

/**
 * Stan agenta bieżącego sklepu (w `AppLayout`): przy wiadomości `agent` odświeża się od razu, a na wszelki wypadek
 * co minutę. Ustawia stan w `lib/store-agent`, z którego korzysta zapis do folderu i pilnowanie folderów.
 */
export function useAgentStatus(storeId: string | undefined) {
  const query = useQuery({
    queryKey: statusKey(storeId ?? ''),
    queryFn: () => api<AgentStatus>(`/stores/${storeId}/agents/status`),
    enabled: !!storeId,
    refetchInterval: 60_000,
    staleTime: 30_000,
  })
  useEffect(() => {
    setAgentStatus(storeId ?? null, query.data ?? null)
  }, [storeId, query.data])
  return query
}

export function useStoreAgents(storeId: string, enabled = true) {
  return useQuery({
    queryKey: [...agentsKey(storeId), 'list'],
    queryFn: () => api<StoreAgents>(`/stores/${storeId}/agents`),
    enabled,
    refetchInterval: 15_000,
  })
}

function useAgentMutation<T>(storeId: string, request: (value: T) => Promise<StoreAgents>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: request,
    onSuccess: (data) => {
      queryClient.setQueryData([...agentsKey(storeId), 'list'], data)
      void queryClient.invalidateQueries({ queryKey: statusKey(storeId) })
    },
    meta: { invalidates: false },
  })
}

export function useAddAgent(storeId: string) {
  return useAgentMutation(storeId, (name: string) =>
    api<StoreAgents>(`/stores/${storeId}/agents`, { method: 'POST', json: { name } }),
  )
}

export function useRemoveAgent(storeId: string) {
  return useAgentMutation(storeId, (id: string) =>
    api<StoreAgents>(`/stores/${storeId}/agents/${id}`, { method: 'DELETE' }),
  )
}

export function useRestoreAgent(storeId: string) {
  return useAgentMutation(storeId, (id: string) =>
    api<StoreAgents>(`/stores/${storeId}/agents/${id}/restore`, { method: 'POST' }),
  )
}

export function useAgentPreferred(storeId: string) {
  return useAgentMutation(storeId, ({ id, preferred }: { id: string; preferred: boolean }) =>
    api<StoreAgents>(`/stores/${storeId}/agents/${id}/preferred`, { method: 'PUT', json: { preferred } }),
  )
}

export function useAgentEnabled(storeId: string) {
  return useAgentMutation(storeId, (enabled: boolean) =>
    api<StoreAgents>(`/stores/${storeId}/agents/settings`, { method: 'PUT', json: { enabled } }),
  )
}
