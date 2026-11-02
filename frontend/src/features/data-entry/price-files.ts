import { useEffect, useSyncExternalStore } from 'react'
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'

import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { api, ApiError } from '@/lib/api'
import { folderAccess, folderSavingSupported, listFolderFiles } from '@/lib/saved-folders'
import type { PriceFileOverview, PriceFileUploadResult } from '@/lib/types'

/*
 * Ceny z kasy: system sklepu zapisuje przy zmianach cen pliki TRACEART_RRRRMMDD_HHMMSS_….Traite0 w folderze dnia
 * (starsze przenosi do podfolderu z poprzedniego dnia). Przeglądarka czyta wybrany raz folder co 10 minut, pyta
 * serwer, których plików jeszcze nie ma, i wysyła je; serwer nadpisuje ceny w kartotece, gdy plik jest nowszy niż
 * raport. Folder jest osobny dla każdego sklepu, bo w nazwie pliku nie ma numeru sklepu. Działa w Chrome i Edge,
 * póki aplikacja jest otwarta; bez zgody „przy każdej wizycie” przeglądarka pyta o nią raz po ponownym otwarciu.
 */

const CHECK_INTERVAL_MS = 10 * 60_000
/** Tyle plików idzie w jednym żądaniu (pliki mają po kilkaset linii, najwyżej ok. 150 kB). */
const UPLOAD_CHUNK = 20
const FILE_NAME = /^TRACEART_\d{8}_\d{6}/i

export const priceFolderKey = (storeId: string) => `price-files:${storeId}`

export type PriceFileWatch = {
  storeId: string | null
  /** `missing`: folder nie wybrany; `prompt`: przeglądarka czeka na zgodę (kliknięcie). */
  access: PermissionState | 'missing' | 'unknown'
  checking: boolean
  checkedAt: number | null
  error: string | null
}

let state: PriceFileWatch = { storeId: null, access: 'unknown', checking: false, checkedAt: null, error: null }
const listeners = new Set<() => void>()
let running: Promise<void> | null = null

function update(patch: Partial<PriceFileWatch>) {
  state = { ...state, ...patch }
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Stan śledzenia folderu bieżącego sklepu. */
export function usePriceFileWatch(): PriceFileWatch {
  return useSyncExternalStore(subscribe, () => state)
}

const overviewKey = (storeId: string) => ['stores', storeId, 'price-files'] as const

export function usePriceFileOverview(storeId: string) {
  return useQuery({
    queryKey: overviewKey(storeId),
    queryFn: () => api<PriceFileOverview>(`/stores/${storeId}/imports/price-files`),
  })
}

/**
 * Sprawdza folder i wysyła nowe pliki. Bez zgody na odczyt tylko zapisuje stan (zgodę daje kliknięcie w
 * {@link PriceFileStatus}); równoległe wywołania czekają na trwające sprawdzenie.
 */
export function checkPriceFiles(storeId: string, queryClient: QueryClient): Promise<void> {
  if (running && state.storeId === storeId) return running
  running = run(storeId, queryClient).finally(() => {
    running = null
  })
  return running
}

async function run(storeId: string, queryClient: QueryClient) {
  const key = priceFolderKey(storeId)
  update({ storeId, checking: true })
  try {
    const access = await folderAccess(key, 'read')
    update({ access })
    if (access !== 'granted') return
    const files = await listFolderFiles(key, (name) => FILE_NAME.test(name))
    if (files.length > 0) {
      const { fileNames } = await api<{ fileNames: string[] }>(`/stores/${storeId}/imports/price-files/pending`, {
        method: 'POST',
        json: { fileNames: files.map((file) => file.name) },
      })
      const byName = new Map(files.map((file) => [file.name, file]))
      const pending = fileNames.flatMap((name) => byName.get(name) ?? [])
      for (let start = 0; start < pending.length; start += UPLOAD_CHUNK) {
        const formData = new FormData()
        for (const file of pending.slice(start, start + UPLOAD_CHUNK)) formData.append('files', file, file.name)
        await api<PriceFileUploadResult>(`/stores/${storeId}/imports/price-files`, { method: 'POST', formData })
      }
      // Bez unieważniania wszystkiego: nowe ceny dostaje każdy kolejny artykuł wczytany z kartoteki.
      if (pending.length > 0) await queryClient.invalidateQueries({ queryKey: overviewKey(storeId) })
    }
    update({ checkedAt: Date.now(), error: null })
  } catch (error) {
    update({ error: error instanceof ApiError ? error.message : 'Nie udało się sprawdzić plików cen' })
  } finally {
    update({ checking: false })
  }
}

/**
 * Śledzenie plików cen w tle dla bieżącego sklepu (w układzie aplikacji, więc działa na każdej stronie): od razu po
 * wejściu, potem co 10 minut i po powrocie do karty, gdy minęło 10 minut.
 */
export function usePriceFileWatcher() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const queryClient = useQueryClient()
  const allowed = hasPermission(me, 'DATA_ENTRY') || hasPermission(me, 'IMPORTS', 'EDIT')
  const storeId = allowed && folderSavingSupported ? (store?.id ?? null) : null

  useEffect(() => {
    if (!storeId) return
    update({ storeId, access: 'unknown', checkedAt: null, error: null })
    const check = () => void checkPriceFiles(storeId, queryClient)
    check()
    const timer = window.setInterval(check, CHECK_INTERVAL_MS)
    const onVisible = () => {
      const due = state.checkedAt === null || Date.now() - state.checkedAt >= CHECK_INTERVAL_MS
      if (document.visibilityState === 'visible' && due) check()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [storeId, queryClient])
}
