import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import type { BadgeTone } from '@/components/ui'

import { api } from './api'
import type { MyTimeAnomalies, TimeRequest, TimeRequestKind, TimeRequestStatus } from './types'

export const REQUEST_QUERY_KEY = ['me', 'time'] as const

/** Różnice w karcie do wyjaśnienia; odświeżają się przy powrocie do aplikacji i co 5 minut. */
export function useMyAnomalies(enabled = true) {
  return useQuery({
    queryKey: [...REQUEST_QUERY_KEY, 'anomalies'],
    queryFn: () => api<MyTimeAnomalies>('/me/time-anomalies'),
    enabled,
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
  })
}

export function useMyRequests() {
  return useQuery({
    queryKey: [...REQUEST_QUERY_KEY, 'requests'],
    queryFn: () => api<TimeRequest[]>('/me/time-requests'),
    staleTime: 60_000,
  })
}

export type NewRequest = {
  kind: TimeRequestKind
  start?: string
  end?: string
  from?: string
  to?: string
  reason?: string | null
}

export function useSubmitRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (json: NewRequest) => api<TimeRequest>('/me/time-requests', { method: 'POST', json }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: REQUEST_QUERY_KEY }),
  })
}

export function useCancelRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api<TimeRequest>(`/me/time-requests/${id}/cancel`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: REQUEST_QUERY_KEY }),
  })
}

/** Nazwa, opis i czy trzeba podać przyczynę: to samo co na wydruku wniosku, prostszymi słowami. */
export const REQUEST_KINDS: Record<TimeRequestKind, { label: string; description: string; reason: boolean }> = {
  OVERTIME: {
    label: 'Nadgodziny z mojej inicjatywy',
    description: 'Pracowałem/am poza grafikiem i chcę, żeby kierownik przyjął to jako nadgodziny.',
    reason: false,
  },
  WORK_OFF: {
    label: 'Odpracowanie wyjścia prywatnego',
    description: 'Odrabiam wcześniejsze wyjście na sprawy osobiste. Liczy się do pracy, ale to nie nadgodziny.',
    reason: false,
  },
  LATE_OR_EARLY: {
    label: 'Ważna przyczyna',
    description: 'Spóźnienie albo wcześniejsze wyjście z ważnego powodu. Usprawiedliwione, bez wynagrodzenia.',
    reason: true,
  },
  PRIVATE_EXIT: {
    label: 'Wyjście prywatne do odpracowania',
    description: 'Wyszedłem/am na sprawy osobiste i odpracuję ten czas. Do tego czasu bez wynagrodzenia.',
    reason: false,
  },
  FORCE_MAJEURE: {
    label: 'Siła wyższa',
    description: 'Pilna sprawa rodzinna: choroba albo wypadek. Do 16 godzin w roku, płatne 50%.',
    reason: true,
  },
  CHILD_CARE: {
    label: 'Opieka nad dzieckiem',
    description: 'Opieka nad dzieckiem do 14 lat. Do 16 godzin albo 2 dni w roku, płatne.',
    reason: false,
  },
  OTHER_PAID: {
    label: 'Inne płatne zwolnienie',
    description: 'Badania lekarskie, krwiodawstwo i podobne. Dokument pokaż kierownikowi.',
    reason: true,
  },
  SICK_LEAVE: {
    label: 'Zwolnienie lekarskie (L4)',
    description: 'Zgłoszenie choroby. Samo zwolnienie (e-ZLA) pracodawca dostaje z ZUS.',
    reason: false,
  },
}

export const REQUEST_STATUSES: Record<TimeRequestStatus, { label: string; tone: BadgeTone }> = {
  PENDING: { label: 'Czeka na kierownika', tone: 'warn' },
  MANAGER_APPROVED: { label: 'Czeka na kadry', tone: 'info' },
  APPROVED: { label: 'Zatwierdzony', tone: 'ok' },
  REJECTED: { label: 'Odrzucony', tone: 'danger' },
  CANCELLED: { label: 'Wycofany', tone: 'neutral' },
}

type Anomaly = MyTimeAnomalies['items'][number]

/** Co się stało, po ludzku: spóźnienie, wcześniejsze wyjście, dłuższe zostanie… */
export function describeAnomaly(item: Anomaly) {
  if (item.part === 'MISSING_EXIT') return 'Brak odbicia wyjścia'
  if (item.part === 'SHORTFALL') {
    if (item.plannedStart && item.start === item.plannedStart) return 'Spóźnienie'
    if (item.plannedEnd && item.end === item.plannedEnd) return 'Wcześniejsze wyjście'
    return 'Przerwa w pracy w czasie zmiany'
  }
  if (item.plannedStart && item.end && item.end <= item.plannedStart) return 'Wcześniejsze przyjście'
  if (item.plannedEnd && item.start >= item.plannedEnd) return 'Dłuższe zostanie'
  return 'Praca poza grafikiem'
}

/** 2026-11-16T06:05:00 → 6:05 */
export const clock = (dateTime: string) => dateTime.slice(11, 16).replace(/^0/, '')

/** 75 → 1:15 */
export const hm = (minutes: number) => `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`
