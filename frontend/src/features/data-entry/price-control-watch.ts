import { useEffect, useSyncExternalStore } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'

import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { PROTO_FOLDER_KEY } from '@/lib/proto-files'
import { folderAccess, folderSavingSupported, listFolderFiles } from '@/lib/saved-folders'
import type { PriceControlFiles } from '@/lib/types'

import {
  applyPriceControlFiles,
  fetchPriceControlFiles,
  sendPriceControlSeen,
  usePriceControlPendingCount,
} from './api'

/*
 * Pilnowanie wgrania zmian cen: program sklepu po wczytaniu pliku z folderu plików PROTO zmienia jego końcówkę
 * z .traite0 na .Traite1, a każde kolejne przetworzenie podnosi liczbę (.Traite2…). Przeglądarka z wybranym folderem
 * sprawdza go od razu, co 5 minut i po powrocie do karty, póki są niewgrane zmiany cen, i zgłasza serwerowi, które
 * pliki program już wczytał. Plik z .traite0 czeka na program, a pliku, którego w folderze nie ma, trzeba zapisać
 * ponownie (albo oznaczyć ręcznie, gdy wgrano go z innego komputera). Podfolder BACKUP też przeglądamy, gdyby program
 * przenosił tam wczytane pliki.
 */

const CHECK_INTERVAL_MS = 5 * 60_000
const FILE_NAME = /^(CTRLPRIX_.+)\.traite(\d+)$/i
const BACKUP_FOLDER = 'BACKUP'

/** Stan pliku w folderze: `waiting` = jest z .traite0 (czeka na program), `missing` = nie ma go w folderze. */
export type FolderStatus = 'waiting' | 'missing'

export type PriceControlWatch = {
  storeId: string | null
  /** `missing`: folder nie wybrany; `prompt`: przeglądarka czeka na zgodę (kliknięcie). */
  access: PermissionState | 'missing' | 'unknown'
  checking: boolean
  checkedAt: number | null
  error: string | null
  /** Stan niewgranych plików w folderze z ostatniego sprawdzenia, po id. */
  statuses: Record<string, FolderStatus>
}

let state: PriceControlWatch = {
  storeId: null,
  access: 'unknown',
  checking: false,
  checkedAt: null,
  error: null,
  statuses: {},
}
const listeners = new Set<() => void>()
let running: Promise<void> | null = null

function update(patch: Partial<PriceControlWatch>) {
  state = { ...state, ...patch }
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function usePriceControlWatch(): PriceControlWatch {
  return useSyncExternalStore(subscribe, () => state)
}

/** Nazwa bez końcówki, małymi literami: po niej plik z historii łączy się z plikiem w folderze. */
const baseName = (fileName: string) => fileName.replace(/\.traite\d+$/i, '').toLowerCase()

/**
 * Sprawdza folder plików PROTO i zgłasza wczytane pliki. Bez zgody na odczyt tylko zapisuje stan (zgodę daje kliknięcie
 * w oknie zmian cen); równoległe wywołania czekają na trwające sprawdzenie.
 */
export function checkPriceControlFiles(storeId: string, queryClient: QueryClient): Promise<void> {
  if (running && state.storeId === storeId) return running
  running = run(storeId, queryClient).finally(() => {
    running = null
  })
  return running
}

async function run(storeId: string, queryClient: QueryClient) {
  if (state.storeId !== storeId) update({ storeId, statuses: {}, checkedAt: null, error: null })
  update({ checking: true })
  try {
    let files: PriceControlFiles = await fetchPriceControlFiles(storeId)
    applyPriceControlFiles(queryClient, storeId, files)
    if (files.pending.length === 0) {
      update({ statuses: {}, error: null })
      return
    }
    const access = await folderAccess(PROTO_FOLDER_KEY, 'read')
    update({ access })
    if (access !== 'granted') return
    // Najwyższa liczba przetworzeń każdej nazwy; 0 = plik jest, ale program go jeszcze nie wczytał.
    const processed = new Map<string, number>()
    for (const handle of await listFolderFiles(PROTO_FOLDER_KEY, (name) => FILE_NAME.test(name), BACKUP_FOLDER)) {
      const match = FILE_NAME.exec(handle.name)
      if (!match) continue
      const key = match[1].toLowerCase()
      processed.set(key, Math.max(processed.get(key) ?? 0, Number(match[2])))
    }
    const seen = [...files.pending, ...files.loaded].flatMap((file) => {
      const count = processed.get(baseName(file.fileName)) ?? 0
      return count > 0 && (file.loadedAt === null || (file.processedCount !== null && count > file.processedCount))
        ? [{ id: file.id, processedCount: count }]
        : []
    })
    if (seen.length > 0) {
      files = await sendPriceControlSeen(storeId, seen)
      applyPriceControlFiles(queryClient, storeId, files)
    }
    const statuses: Record<string, FolderStatus> = {}
    for (const file of files.pending) {
      statuses[file.id] = processed.has(baseName(file.fileName)) ? 'waiting' : 'missing'
    }
    update({ statuses, checkedAt: Date.now(), error: null })
  } catch (error) {
    update({ error: error instanceof ApiError ? error.message : 'Nie udało się sprawdzić folderu protokołów' })
  } finally {
    update({ checking: false })
  }
}

/**
 * Pilnowanie zmian cen w tle dla bieżącego sklepu (w układzie aplikacji): póki licznik niewgranych jest większy od
 * zera, sprawdza folder od razu, co 5 minut i po powrocie do karty. Licznik serwera odświeża się sam co 5 minut.
 */
export function usePriceControlWatcher() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const queryClient = useQueryClient()
  const storeId = hasPermission(me, 'DATA_ENTRY') && store ? store.id : null
  const { data: pending = 0 } = usePriceControlPendingCount(storeId ?? '', storeId !== null)
  const active = storeId !== null && folderSavingSupported && pending > 0

  useEffect(() => {
    if (!active || !storeId) return
    void checkPriceControlFiles(storeId, queryClient)
    const timer = window.setInterval(() => void checkPriceControlFiles(storeId, queryClient), CHECK_INTERVAL_MS)
    const onVisible = () => {
      if (document.visibilityState === 'visible' && Date.now() - (state.checkedAt ?? 0) >= CHECK_INTERVAL_MS) {
        void checkPriceControlFiles(storeId, queryClient)
      }
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [active, storeId, queryClient])
}
