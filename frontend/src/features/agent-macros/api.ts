import { useQuery } from '@tanstack/react-query'

import { api, ApiError } from '@/lib/api'
import type { AgentActivity, AgentComputer, AgentMacroJob } from '@/lib/types'

/*
 * Makra Mercalys na wolnym komputerze sklepu (agent sklepu), także z innego miasta: serwer składa makro i wybiera
 * komputer, agent odlicza z „Anuluj”, a przeglądarka tylko pyta o stan zlecenia.
 */

const path = (storeId: string) => `/stores/${storeId}/agent-macros`

const POLL_MS = 1500
/** Tyle pytań o stan z rzędu bez odpowiedzi (wdrożenie, chwilowy brak internetu), zanim zlecenie zgłosi błąd. */
const MAX_FAILURES = 40

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

export const isActive = (job: AgentMacroJob) => job.status === 'OFFERED' || job.status === 'RUNNING'

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
      query.state.data?.update || query.state.data?.jobs.some((job) => isActive(job)) ? 5_000 : 60_000,
  })
}

/** Zleca makro i czeka na jego koniec; `onUpdate` dostaje każdy stan (komputer, odliczanie, w toku, wynik). */
export async function runMacro(
  storeId: string,
  { macro, ...body }: MacroRequest,
  onUpdate: (job: AgentMacroJob) => void,
): Promise<AgentMacroJob> {
  let job = await api<AgentMacroJob>(`${path(storeId)}/${macro}`, { method: 'POST', json: body })
  onUpdate(job)
  let failures = 0
  while (isActive(job)) {
    await new Promise((resolve) => setTimeout(resolve, POLL_MS))
    try {
      job = await api<AgentMacroJob>(`${path(storeId)}/${job.id}`)
      failures = 0
      onUpdate(job)
    } catch (error) {
      const permanent = error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 429
      failures += 1
      if (permanent || failures >= MAX_FAILURES) throw error
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
