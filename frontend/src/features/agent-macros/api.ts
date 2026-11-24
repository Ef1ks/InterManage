import { useQuery } from '@tanstack/react-query'

import { api, ApiError } from '@/lib/api'
import type { AgentComputer, AgentMacroJob } from '@/lib/types'

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
  | { macro: 'base-receive' | 'base-close'; deliveryId: string; agentId?: string }

export const isActive = (job: AgentMacroJob) => job.status === 'OFFERED' || job.status === 'RUNNING'

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

export const cancelMacro = (storeId: string, id: string) =>
  api<AgentMacroJob>(`${path(storeId)}/${id}/cancel`, { method: 'POST' })
