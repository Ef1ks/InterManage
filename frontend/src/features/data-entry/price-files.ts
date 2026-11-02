import { useEffect, useSyncExternalStore } from 'react'
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'

import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { useCurrentStore } from '@/features/stores/current-store'
import { api, ApiError } from '@/lib/api'
import { folderAccess, folderSavingSupported, listFolderFiles, requestStoredFolderAccess } from '@/lib/saved-folders'
import type { PriceFileOverview, PriceFileUploadResult } from '@/lib/types'

/*
 * Ceny z kasy: system sklepu zapisuje przy zmianach cen pliki TRACEART_RRRRMMDD_HHMMSS_….Traite0 w folderze
 * (starsze przenosi do podfolderu BACKUP, w którym jest cała historia). Przeglądarka czyta wybrany raz folder razem
 * z BACKUP od razu po wejściu i co 10 minut, pyta serwer, których plików jeszcze nie ma, i wysyła je od najstarszego,
 * więc po dniu bez aplikacji historia uzupełnia się sama (daty są w nazwach plików). Serwer nadpisuje ceny
 * w kartotece, gdy plik jest nowszy niż raport. Folder jest osobny dla każdego sklepu, bo w nazwie pliku nie ma
 * numeru sklepu. Działa w Chrome i Edge, póki aplikacja jest otwarta; bez zgody „przy każdej wizycie” przeglądarka
 * pyta o nią po ponownym otwarciu: przy logowaniu albo pierwszym kliknięciu w aplikacji.
 */

const CHECK_INTERVAL_MS = 10 * 60_000
/** Tyle plików idzie w jednym żądaniu (pliki mają po kilkaset linii, najwyżej ok. 150 kB). */
const UPLOAD_CHUNK = 20
const FILE_NAME = /^TRACEART_\d{8}_\d{6}/i
/** Podfolder z plikami z poprzednich dni. */
const HISTORY_FOLDER = 'BACKUP'
/** Tyle nazw przyjmuje naraz `POST …/pending`. */
const PENDING_CHUNK = 5000

export const priceFolderKey = (storeId: string) => `price-files:${storeId}`

export type PriceFileWatch = {
  storeId: string | null
  /** `missing`: folder nie wybrany; `prompt`: przeglądarka czeka na zgodę (kliknięcie). */
  access: PermissionState | 'missing' | 'unknown'
  checking: boolean
  checkedAt: number | null
  error: string | null
  /** Wysyłanie zaległych plików (np. historii z BACKUP), gdy jest ich więcej niż jedna paczka. */
  progress: { done: number; total: number } | null
}

let state: PriceFileWatch = {
  storeId: null,
  access: 'unknown',
  checking: false,
  checkedAt: null,
  error: null,
  progress: null,
}
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
  lastQueryClient = queryClient
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
    let access = await folderAccess(key, 'read')
    // Zgoda poproszona przy logowaniu albo pierwszym kliknięciu może jeszcze czekać na odpowiedź w oknie przeglądarki.
    if (access === 'prompt' && accessRequest) {
      await accessRequest
      access = await folderAccess(key, 'read')
    }
    update({ access })
    if (access !== 'granted') return
    const files = await listFolderFiles(key, (name) => FILE_NAME.test(name), HISTORY_FOLDER)
    const pending = await pendingFiles(storeId, files)
    update({ progress: pending.length > UPLOAD_CHUNK ? { done: 0, total: pending.length } : null })
    for (let start = 0; start < pending.length; start += UPLOAD_CHUNK) {
      const formData = new FormData()
      for (const handle of pending.slice(start, start + UPLOAD_CHUNK)) {
        try {
          formData.append('files', await handle.getFile(), handle.name)
        } catch {
          // Plik przeniesiony do BACKUP między listą a odczytem: pójdzie przy kolejnym sprawdzeniu.
        }
      }
      if (formData.has('files')) {
        await api<PriceFileUploadResult>(`/stores/${storeId}/imports/price-files`, { method: 'POST', formData })
      }
      if (state.progress)
        update({ progress: { done: Math.min(start + UPLOAD_CHUNK, pending.length), total: pending.length } })
    }
    // Bez unieważniania wszystkiego: nowe ceny dostaje każdy kolejny artykuł wczytany z kartoteki.
    if (pending.length > 0) await queryClient.invalidateQueries({ queryKey: overviewKey(storeId) })
    update({ checkedAt: Date.now(), error: null })
  } catch (error) {
    update({ error: error instanceof ApiError ? error.message : 'Nie udało się sprawdzić plików cen' })
  } finally {
    update({ checking: false, progress: null })
  }
}

/** Pliki z folderu, których serwer jeszcze nie ma, od najstarszego (nazwa zaczyna się od daty i godziny). */
async function pendingFiles(storeId: string, files: FileSystemFileHandle[]): Promise<FileSystemFileHandle[]> {
  const byName = new Map(files.map((file) => [file.name, file]))
  const names = [...byName.keys()]
  const pending: FileSystemFileHandle[] = []
  for (let start = 0; start < names.length; start += PENDING_CHUNK) {
    const { fileNames } = await api<{ fileNames: string[] }>(`/stores/${storeId}/imports/price-files/pending`, {
      method: 'POST',
      json: { fileNames: names.slice(start, start + PENDING_CHUNK) },
    })
    pending.push(...fileNames.flatMap((name) => byName.get(name) ?? []))
  }
  const time = (name: string) => name.slice(9, 24)
  return pending.sort((a, b) => time(a.name).localeCompare(time(b.name)) || a.name.localeCompare(b.name))
}

let accessRequest: Promise<boolean> | null = null

/**
 * Prosi o zgodę na odczyt zapamiętanych folderów plików cen. Wołać od razu po kliknięciu (logowanie, pierwsze
 * kliknięcie w aplikacji), bo przeglądarka pokazuje okno zgody tylko po geście użytkownika. Sprawdzenie, które
 * w tym czasie trafi na brak zgody, czeka na odpowiedź.
 */
export function requestPriceFolderAccess() {
  if (!folderSavingSupported) return
  const request = requestStoredFolderAccess('price-files:', 'read')
  accessRequest = request
  void request.then((granted) => {
    if (accessRequest === request) accessRequest = null
    if (granted && lastQueryClient && state.storeId && state.access !== 'granted') {
      void checkPriceFiles(state.storeId, lastQueryClient)
    }
  })
}

let lastQueryClient: QueryClient | null = null

/**
 * Śledzenie plików cen w tle dla bieżącego sklepu (w układzie aplikacji, więc działa na każdej stronie): od razu po
 * wejściu, potem co 10 minut i po powrocie do karty, gdy minęło 10 minut. Bez zgody na odczyt (przeglądarka
 * otwarta ponownie, zalogowany z „Nie wylogowuj mnie”) pierwsze kliknięcie w aplikacji prosi o nią.
 */
export function usePriceFileWatcher() {
  const me = useCurrentUser()
  const store = useCurrentStore()
  const queryClient = useQueryClient()
  const allowed = hasPermission(me, 'DATA_ENTRY') || hasPermission(me, 'IMPORTS', 'EDIT')
  const storeId = allowed && folderSavingSupported ? (store?.id ?? null) : null

  useEffect(() => {
    if (!storeId) return
    update({ storeId, access: 'unknown', checkedAt: null, error: null, progress: null })
    const check = () => void checkPriceFiles(storeId, queryClient)
    check()
    const timer = window.setInterval(check, CHECK_INTERVAL_MS)
    const onVisible = () => {
      const due = state.checkedAt === null || Date.now() - state.checkedAt >= CHECK_INTERVAL_MS
      if (document.visibilityState === 'visible' && due) check()
    }
    document.addEventListener('visibilitychange', onVisible)
    const onFirstClick = () => {
      if (state.storeId !== storeId || state.access !== 'prompt') return
      document.removeEventListener('pointerdown', onFirstClick, { capture: true })
      requestPriceFolderAccess()
    }
    document.addEventListener('pointerdown', onFirstClick, { capture: true })
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
      document.removeEventListener('pointerdown', onFirstClick, { capture: true })
    }
  }, [storeId, queryClient])
}
