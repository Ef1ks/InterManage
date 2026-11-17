import { useMutation, useQuery } from '@tanstack/react-query'

import { api, printFile } from '@/lib/api'
import type { TimeRequest, TimeRequestKind, TimeRequestStatus } from '@/lib/types'

const path = (storeId: string, suffix = '') => `/stores/${storeId}/staff/time/requests${suffix}`

/** Polskie nazwy rodzajów wniosków (krótko, do listy). */
export const REQUEST_KINDS: Record<TimeRequestKind, string> = {
  OVERTIME: 'Nadgodziny z własnej inicjatywy',
  WORK_OFF: 'Odpracowanie wyjścia prywatnego',
  PRIVATE_EXIT: 'Wyjście prywatne do odpracowania',
  LATE_OR_EARLY: 'Spóźnienie lub wcześniejsze wyjście',
  FORCE_MAJEURE: 'Siła wyższa (50%)',
  CHILD_CARE: 'Opieka nad dzieckiem',
  OTHER_PAID: 'Inna płatna nieobecność',
  SICK_LEAVE: 'Zgłoszenie L4',
}

export const REQUEST_STATUSES: Record<TimeRequestStatus, { label: string; badge: string }> = {
  PENDING: {
    label: 'Czeka na kierownika',
    badge: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
  MANAGER_APPROVED: {
    label: 'Czeka na kadry',
    badge: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  },
  APPROVED: {
    label: 'Zatwierdzony',
    badge: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  },
  REJECTED: { label: 'Odrzucony', badge: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100' },
  CANCELLED: {
    label: 'Wycofany',
    badge: 'bg-zinc-200 text-zinc-900 dark:bg-zinc-700 dark:text-zinc-100',
  },
}

/** Otwarte wnioski (do decyzji) albo wszystkie z dni okresu. */
export function useTimeRequests(storeId: string, range: { from: string; to: string } | null, enabled: boolean) {
  return useQuery({
    queryKey: ['staff-time', storeId, 'requests', range?.from ?? 'open', range?.to ?? 'open'],
    queryFn: () => api<TimeRequest[]>(path(storeId, range ? `?${new URLSearchParams(range)}` : '')),
    enabled,
  })
}

/** Ile wniosków czeka na tę osobę (jako kierownika i jako kadry); odświeża się co 2 minuty. */
export function useTimeRequestCount(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['staff-time', storeId, 'requests', 'count'],
    queryFn: () => api<{ manager: number; hr: number; total: number }>(path(storeId, '/count')),
    enabled,
    refetchInterval: 120_000,
  })
}

export function useTimeRequestActions(storeId: string) {
  const approve = useMutation({
    mutationFn: ({ id, note }: { id: string; note?: string | null }) =>
      api<TimeRequest>(path(storeId, `/${id}/approve`), { method: 'POST', json: { note: note ?? null } }),
  })
  const reject = useMutation({
    mutationFn: ({ id, note }: { id: string; note?: string | null }) =>
      api<TimeRequest>(path(storeId, `/${id}/reject`), { method: 'POST', json: { note: note ?? null } }),
  })
  const undo = useMutation({
    mutationFn: (id: string) => api<TimeRequest>(path(storeId, `/${id}/undo`), { method: 'POST' }),
  })
  return { approve, reject, undo }
}

/** Wydruk wniosków, każdy na osobnej kartce. */
export const printTimeRequests = (storeId: string, ids: string[]) =>
  printFile(path(storeId, `/pdf?${new URLSearchParams(ids.map((id) => ['ids', id]))}`))
