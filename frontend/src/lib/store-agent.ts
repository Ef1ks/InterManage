import { useSyncExternalStore } from 'react'

import type { AgentStatus } from '@/lib/types'

/*
 * Stan agenta sklepu (program na komputerze w sklepie, katalog agent/ w repozytorium) dla bieżącego sklepu. Sklep sam
 * wybiera w Ustawieniach sklepu, czy pracuje z folderami przez agenta, czy jak dotąd przez przeglądarkę. Gdy wybrał
 * agenta i agent jest online (`active`), zapis plików z `folders` idzie przez kolejkę sklepu do agenta
 * (`lib/agent-save`), a pilnowanie folderów z `watches` (pliki cen z kasy, zmiany cen, dziennik ATHOS) robi agent,
 * więc przeglądarka go nie powtarza. Gdy agenta nie ma albo jest offline, wszystko działa jak dotąd, w przeglądarce.
 *
 * Stan ustawia `useAgentStatus` (features/store-agent) z `AppLayout`; zapis do folderu go tylko czyta.
 */

let current: { storeId: string; status: AgentStatus } | null = null
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

const REMEMBERED_KEY = 'intermanage.agentStatus.'

/**
 * Ostatnio znany stan agenta sklepu: kropka w nagłówku jest od razu po odświeżeniu strony, zanim odpowie serwer.
 * Tylko do pokazania; o zapisie i pilnowaniu folderów decyduje bieżący stan.
 */
export function rememberedAgentStatus(storeId: string): AgentStatus | null {
  try {
    const saved = localStorage.getItem(REMEMBERED_KEY + storeId)
    return saved ? (JSON.parse(saved) as AgentStatus) : null
  } catch {
    return null
  }
}

function remember(storeId: string, status: AgentStatus) {
  try {
    localStorage.setItem(REMEMBERED_KEY + storeId, JSON.stringify(status))
  } catch {
    // Bez localStorage kropka pojawi się po odpowiedzi serwera.
  }
}

export function setAgentStatus(storeId: string | null, status: AgentStatus | null) {
  const next = storeId && status ? { storeId, status } : null
  if (JSON.stringify(next) === JSON.stringify(current)) return
  current = next
  if (next) remember(next.storeId, next.status)
  listeners.forEach((listener) => listener())
}

/** Czy agent przejmuje zapis do tego folderu (zwraca sklep) — inaczej `null` i zapis idzie jak dotąd. */
export function agentStoreFor(folderKey: string): string | null {
  if (!current?.status.active) return null
  // Także zamówienia ATHOS (`mea-comrnis:…`): `saveToFolder` czeka na potwierdzenie zapisu od agenta, a dopiero potem
  // okno MEA potwierdza wysyłkę na serwerze.
  return current.status.folders.includes(folderKey) ? current.storeId : null
}

/** Czy agent pilnuje folderu tego rodzaju (`TRACEART`, `CTRLPRIX`, `ATHOS`) w sklepie: przeglądarka wtedy nie. */
export function agentWatches(storeId: string | null | undefined, kind: string): boolean {
  return !!storeId && current?.storeId === storeId && current.status.active && current.status.watches.includes(kind)
}

export function useAgentWatches(storeId: string | null | undefined, kind: string): boolean {
  return useSyncExternalStore(subscribe, () => agentWatches(storeId, kind))
}

export function useAgentState(): { storeId: string; status: AgentStatus } | null {
  return useSyncExternalStore(subscribe, () => current)
}
