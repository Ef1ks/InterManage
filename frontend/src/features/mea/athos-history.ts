import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'

import { api, ApiError } from '@/lib/api'
import { ensureFolderAccess, folderAccess, folderSavingSupported, listFolderFiles } from '@/lib/saved-folders'
import { agentWatches } from '@/lib/store-agent'
import type { MeaAthosStatus } from '@/lib/types'

/*
 * Statusy wysyłki MEA z ATHOS: ATHOS zapisuje każdą wysyłkę zamówień MEA w dzienniku Histo.Log na serwerze sklepu
 * (\\<serwer SR>\appres\athos2000). Folder wybiera się raz w przeglądarce (tylko odczyt), a strona MEA czyta dziennik
 * od razu po wejściu i co 10 minut; plik idzie na serwer tylko wtedy, gdy zmienił się od ostatniego wysłania
 * (data zmiany i rozmiar w localStorage). Serwer dopisuje nowe wysyłki i z nich liczy zakładki „Do wysłania” /
 * „Wysłane”. Działa w Chrome i Edge; po ponownym otwarciu przeglądarki zgodę na odczyt daje kliknięcie
 * „Sprawdź statusy ATHOS”.
 */

const CHECK_INTERVAL_MS = 10 * 60_000
const FILE_NAME = /^histo\.log$/i

/** Folder dziennika ATHOS, osobno dla każdego sklepu (każdy sklep ma swój serwer SR). */
export const athosFolderKey = (storeId: string) => `mea-athos:${storeId}`
/** Ścieżka sklepu dla tego folderu (wspólna dla komputerów sklepu, `lib/store-locations`). */
export const athosFolderSpec = (storeId: string) => ({
  key: athosFolderKey(storeId),
  label: 'Dziennik ATHOS',
  suggestedPath: '\\\\SR…\\appres\\athos2000',
})

/**
 * Folder, z którego ATHOS bierze zamówienia MEA (\\<serwer SR>\appres\comrnis, jak `MeaExportPathZip` w eMEA).
 * Aplikacja zapisuje w nim plik R…, więc folder wybiera się z prawem zapisu.
 */
export const comrnisFolderSpec = (storeId: string) => ({
  key: `mea-comrnis:${storeId}`,
  label: 'Zamówienie MEA do ATHOS',
  suggestedPath: '\\\\SR…\\appres\\comrnis',
})

const markerKey = (storeId: string) => `intermanage.meaAthosFile.${storeId}`

function readMarker(storeId: string) {
  try {
    return localStorage.getItem(markerKey(storeId))
  } catch {
    return null
  }
}

function writeMarker(storeId: string, value: string) {
  try {
    localStorage.setItem(markerKey(storeId), value)
  } catch {
    // Bez localStorage plik pójdzie przy każdym sprawdzeniu; serwer i tak pomija znane wysyłki.
  }
}

export type AthosWatch = {
  /** `missing`: folder nie wybrany; `prompt`: czeka na zgodę (kliknięcie „Sprawdź statusy ATHOS”). */
  access: PermissionState | 'missing' | 'unknown'
  checking: boolean
  checkedAt: number | null
  error: string | null
}

const statusKey = (storeId: string) => ['stores', storeId, 'mea', 'athos'] as const

export function useAthosStatus(storeId: string) {
  return useQuery({
    queryKey: statusKey(storeId),
    queryFn: () => api<MeaAthosStatus>(`/stores/${storeId}/mea/athos`),
  })
}

/**
 * Czyta dziennik ATHOS po wejściu na stronę i co 10 minut (bez pytania o zgodę), a `check(true)` po kliknięciu: wtedy
 * prosi o zgodę i wysyła plik nawet bez zmian.
 */
export function useAthosHistorySync(storeId: string) {
  const queryClient = useQueryClient()
  const [watch, setWatch] = useState<AthosWatch>({ access: 'unknown', checking: false, checkedAt: null, error: null })
  const running = useRef(false)

  const check = useCallback(
    async (interactive = false) => {
      if (!folderSavingSupported || running.current) return
      // Dziennik czyta agent sklepu (sklep pracuje przez agenta, agent online); kliknięcie dalej czyta go tutaj.
      if (!interactive && agentWatches(storeId, 'ATHOS')) return
      running.current = true
      const key = athosFolderKey(storeId)
      try {
        // Okno zgody działa tylko zaraz po kliknięciu, więc przy kliknięciu pytamy od razu, przed czymkolwiek innym.
        // Stan ustawiamy dopiero po pierwszym await (sprawdzenie startuje też z efektu).
        const access: AthosWatch['access'] = interactive
          ? (await ensureFolderAccess(key, 'read'))
            ? 'granted'
            : await folderAccess(key, 'read')
          : await folderAccess(key, 'read')
        setWatch((current) => ({ ...current, access, checking: access === 'granted' }))
        if (access !== 'granted') return
        const [handle] = await listFolderFiles(key, (name) => FILE_NAME.test(name))
        if (!handle) {
          throw new ApiError(0, 'W wybranym folderze nie ma pliku Histo.Log. Wybierz folder appres\\athos2000.')
        }
        const file = await handle.getFile()
        const marker = `${file.lastModified}|${file.size}`
        if (interactive || readMarker(storeId) !== marker) {
          const formData = new FormData()
          formData.append('file', file, handle.name)
          formData.append('modified', String(file.lastModified))
          const status = await api<MeaAthosStatus>(`/stores/${storeId}/mea/athos`, { method: 'POST', formData })
          writeMarker(storeId, marker)
          queryClient.setQueryData(statusKey(storeId), status)
          // Zakładki listy i wysyłki w dokumentach liczą się z nowych wysyłek.
          void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'mea'] })
        }
        setWatch((current) => ({ ...current, error: null, checkedAt: Date.now() }))
      } catch (error) {
        setWatch((current) => ({
          ...current,
          error: error instanceof ApiError ? error.message : 'Nie udało się odczytać dziennika ATHOS.',
        }))
      } finally {
        running.current = false
        setWatch((current) => ({ ...current, checking: false }))
      }
    },
    [storeId, queryClient],
  )

  useEffect(() => {
    // Pierwsze sprawdzenie zaraz po wejściu, kolejne co 10 minut (oba z timera, poza renderem).
    const first = window.setTimeout(() => void check(), 0)
    const timer = window.setInterval(() => void check(), CHECK_INTERVAL_MS)
    return () => {
      window.clearTimeout(first)
      window.clearInterval(timer)
    }
  }, [check])

  return { watch, check }
}
