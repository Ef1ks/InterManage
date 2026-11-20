import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { BadgeTone } from '@/components/ui'

import { api } from './api'
import type { StoreTask, TaskCounts, TaskStatus } from './types'

const KEY = ['tasks'] as const
const path = (storeId: string, suffix = '') => `/stores/${storeId}/tasks${suffix}`

export const TASK_STATUSES: Record<TaskStatus, { label: string; tone: BadgeTone }> = {
  OPEN: { label: 'Do zrobienia', tone: 'info' },
  DONE: { label: 'Czeka na przyjęcie', tone: 'warn' },
  ACCEPTED: { label: 'Przyjęte', tone: 'ok' },
}

/** Moje zadania w sklepie; odświeżają się co 2 minuty i przy powrocie do aplikacji. */
export function useMyTasks(storeId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, storeId, 'mine'],
    queryFn: () => api<StoreTask[]>(path(storeId!, '/mine')),
    enabled: storeId !== undefined,
    staleTime: 30_000,
    refetchInterval: 120_000,
  })
}

/** Wszystkie zadania sklepu: widok zlecającego (właściciel). */
export function useAllTasks(storeId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, storeId, 'all'],
    queryFn: () => api<StoreTask[]>(path(storeId!)),
    enabled: enabled && storeId !== undefined,
    staleTime: 30_000,
    refetchInterval: 120_000,
  })
}

export function useTaskCounts(storeId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, storeId, 'counts'],
    queryFn: () => api<TaskCounts>(path(storeId!, '/counts')),
    enabled: storeId !== undefined,
    staleTime: 30_000,
    refetchInterval: 120_000,
  })
}

/** Odhaczanie pozycji, wykonanie, cofnięcie, przyjęcie i odesłanie; po każdej zmianie listy pobierają się od nowa. */
export function useTaskActions(storeId: string) {
  const queryClient = useQueryClient()
  const onSuccess = () => queryClient.invalidateQueries({ queryKey: KEY })
  const post = (suffix: string, json?: unknown) => api<StoreTask>(path(storeId, suffix), { method: 'POST', json })
  return {
    checkItem: useMutation({
      mutationFn: ({ id, itemId, checked }: { id: string; itemId: string; checked: boolean }) =>
        post(`/${id}/items/${itemId}`, { checked, note: null }),
      onSuccess,
    }),
    done: useMutation({
      mutationFn: ({ id, comment }: { id: string; comment: string | null }) => post(`/${id}/done`, { comment }),
      onSuccess,
    }),
    undone: useMutation({ mutationFn: (id: string) => post(`/${id}/undone`), onSuccess }),
    accept: useMutation({ mutationFn: (id: string) => post(`/${id}/accept`), onSuccess }),
    sendBack: useMutation({
      mutationFn: ({ id, comment }: { id: string; comment: string | null }) => post(`/${id}/return`, { comment }),
      onSuccess,
    }),
  }
}

/** Termin minął, a zadanie nie jest wykonane. */
export function isOverdue(task: StoreTask, today: string) {
  return task.status === 'OPEN' && task.dueDate !== null && task.dueDate < today
}
