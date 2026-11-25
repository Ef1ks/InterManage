import { useQuery } from '@tanstack/react-query'

import { api, ApiError } from '@/lib/api'
import type { AgentActivity, AgentComputer, AgentMacroJob } from '@/lib/types'

/*
 * Makra Mercalys na wolnym komputerze sklepu (agent sklepu), także z innego miasta: serwer składa makro i wybiera
 * komputer, agent odlicza z „Anuluj”, a przeglądarka tylko pyta o stan zlecenia.
 */

const path = (storeId: string) => `/stores/${storeId}/agent-macros`

const POLL_MS = 1500
/** W kolejce zlecenie czeka zwykle minuty, więc pytamy rzadziej. */
const QUEUED_POLL_MS = 5000
/** Przy braku odpowiedzi serwera (wdrożenie, restart, brak internetu) pytamy dalej, coraz rzadziej. */
const OFFLINE_POLL_MS = 10_000

/** Komputery sklepu z agentem i powód, dla którego komputer nie może teraz wykonać makra. */
export function useAgentComputers(storeId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['stores', storeId, 'agent-macros', 'computers'],
    queryFn: () => api<AgentComputer[]>(`${path(storeId)}/computers`),
    enabled,
    refetchInterval: 10_000,
  })
}

export type MacroRequest =
  | { macro: 'itm8-collect'; deliveryId: string; agentId?: string }
  | { macro: 'itm8-apply'; deliveryId: string; codes: string[]; agentId?: string }
  | { macro: 'mea-codes'; codes: string[]; agentId?: string }
  | { macro: 'cad-priorities'; items: { itm8: string; topItm8: string }[]; agentId?: string }
  | { macro: 'base-receive' | 'base-close'; deliveryId: string; agentId?: string }

/** Jeszcze się nie skończyło: czeka w kolejce, odlicza albo działa na komputerze. */
export const isActive = (job: AgentMacroJob) =>
  job.status === 'QUEUED' || job.status === 'OFFERED' || job.status === 'RUNNING'

/** Na komputerze: odliczanie albo wykonanie (w sklepie najwyżej jedno naraz). */
export const onComputer = (job: AgentMacroJob) => job.status === 'OFFERED' || job.status === 'RUNNING'

/** Tyle po końcu makra jego wynik pokazuje jeszcze okno, z którego je zlecono, także po odświeżeniu strony. */
const FRESH_MS = 30 * 60_000

/** `fresh`: trwa albo skończyło się niedawno. */
export type RecentMacro = AgentMacroJob & { fresh: boolean }

/** Makra i aktualizacja danych sklepu z serwera, więc odświeżenie strony ich nie gubi. */
export function useAgentActivity(storeId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['stores', storeId, 'agent-macros', 'recent'],
    queryFn: async () => {
      const activity = await api<AgentActivity>(`${path(storeId ?? '')}/recent`)
      const since = Date.now() - FRESH_MS
      return {
        update: activity.update,
        jobs: activity.jobs.map((job): RecentMacro => ({
          ...job,
          fresh: isActive(job) || (job.finishedAt !== null && new Date(job.finishedAt).getTime() > since),
        })),
      }
    },
    enabled: enabled && !!storeId,
    // Zmiany przychodzą na żywo; odpytywanie łapie zgubioną wiadomość i aktualizację danych.
    refetchInterval: (query) =>
      query.state.data?.update || query.state.data?.jobs.some((job) => onComputer(job))
        ? 5_000
        : query.state.data?.jobs.some((job) => isActive(job))
          ? 15_000
          : 60_000,
  })
}

/**
 * Zleca makro i czeka na jego koniec; `onUpdate` dostaje każdy stan (kolejka, komputer, odliczanie, w toku, wynik).
 * Zlecenie leży w bazie, więc brak odpowiedzi serwera (restart, wdrożenie) nie kończy czekania: pytamy dalej, aż
 * serwer wróci; przerywa tylko odmowa serwera (np. makro usunięte).
 */
export async function runMacro(
  storeId: string,
  { macro, ...body }: MacroRequest,
  onUpdate: (job: AgentMacroJob) => void,
): Promise<AgentMacroJob> {
  let job = await api<AgentMacroJob>(`${path(storeId)}/${macro}`, { method: 'POST', json: body })
  onUpdate(job)
  let offline = false
  while (isActive(job)) {
    const delay = offline ? OFFLINE_POLL_MS : job.status === 'QUEUED' ? QUEUED_POLL_MS : POLL_MS
    await new Promise((resolve) => setTimeout(resolve, delay))
    try {
      job = await api<AgentMacroJob>(`${path(storeId)}/${job.id}`)
      offline = false
      onUpdate(job)
    } catch (error) {
      const permanent = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 429
      if (permanent) throw error
      offline = true
    }
  }
  return job
}

/** Ponowienie makra nowych artykułów albo zmian cen, które nie doszło do końca. */
export const retryMacro = (storeId: string, id: string) =>
  api<AgentMacroJob>(`${path(storeId)}/${id}/retry`, { method: 'POST' })

/** Pliki nieudanego makra wczytał człowiek: nowe artykuły trafiają do bazy jak po udanym makrze. */
export const markLoadedByHand = (storeId: string, id: string) =>
  api<void>(`${path(storeId)}/${id}/loaded`, { method: 'POST' })

/** Czy zapisany przed chwilą plik artykułów wczyta komputer sklepu. */
export const productFileExpected = (storeId: string, fileName: string) =>
  api<{ expected: boolean }>(`${path(storeId)}/product-file?fileName=${encodeURIComponent(fileName)}`)

export const cancelMacro = (storeId: string, id: string) =>
  api<AgentMacroJob>(`${path(storeId)}/${id}/cancel`, { method: 'POST' })

/** Anulowane przed startem wraca do kolejki na swoje miejsce. */
export const restoreMacro = (storeId: string, id: string) =>
  api<AgentMacroJob>(`${path(storeId)}/${id}/restore`, { method: 'POST' })
