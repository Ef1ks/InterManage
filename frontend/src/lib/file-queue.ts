import { useSyncExternalStore } from 'react'

import { api } from '@/lib/api'

/*
 * Kolejka plików sklepu. Kto pracuje dla innego sklepu bez połączenia z jego komputerem (np. wprowadza faktury
 * innego sklepu u siebie), włącza w tej przeglądarce kolejkę dla tego sklepu: pliki, które poszłyby do folderów
 * programu sklepu (XML faktury, nowe artykuły, protokoły, zmiana cen, etykiety, zamówienia, .PRO, .lst…), trafiają
 * na serwer do kolejki sklepu. Pod koniec pracy, po połączeniu z komputerem sklepu, „Zapisz wszystkie” zapisuje je
 * tam w folderach (features/file-queue). Ustawienie jest per przeglądarka, bo komputer sklepu zapisuje od razu.
 *
 * Zapis przechwytuje `saveToFolder` w `lib/saved-folders`, więc narzędzia nic o kolejce nie wiedzą. Pliki ATHOS
 * (klucze `mea-…`) mają własną kolejkę i idą jak dotąd.
 */

const STORAGE_KEY = 'intermanage.fileQueueStores'

/** Opisy folderów zapisu, pokazywane w kolejce (klucz folderu → co do niego trafia). */
export const QUEUE_FOLDER_LABELS: Record<string, string> = {
  'deli-order-proto': 'Protokoły, zmiana cen, etykiety, zamówienia',
  'data-entry-xml': 'Pliki XML faktur',
  'data-entry-product-file': 'Nowe i zmienione artykuły',
  'data-entry-lst': 'Listy EAN-ów',
  'leaflet-pro': 'Ceny promocji',
  'base-delivery-note-file': 'Plik dostawy dla programu sklepu',
  'base-delivery-plu': 'Sprawdź PLU (lista EAN-ów)',
}

function readStores(): string[] {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')
    return Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : []
  } catch {
    return []
  }
}

let enabledStores = readStores()
let currentStoreId: string | null = null
const listeners = new Set<() => void>()
const queuedListeners = new Set<(storeId: string) => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key !== STORAGE_KEY) return
    enabledStores = readStores()
    emit()
  })
}

/** Włącza albo wyłącza kolejkę dla sklepu w tej przeglądarce. */
export function setFileQueueEnabled(storeId: string, enabled: boolean) {
  enabledStores = enabled ? [...new Set([...enabledStores, storeId])] : enabledStores.filter((id) => id !== storeId)
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(enabledStores))
  } catch {
    // Bez localStorage ustawienie działa do zamknięcia karty.
  }
  emit()
}

/** Czy kolejka jest włączona dla sklepu w tej przeglądarce. */
export function useFileQueueEnabled(storeId: string | undefined): boolean {
  return useSyncExternalStore(subscribe, () => (storeId ? enabledStores.includes(storeId) : false))
}

/** Bieżący sklep aplikacji (ustawia go `AppLayout`), bo zapis do folderu nie zna sklepu. */
export function setFileQueueStore(storeId: string | null) {
  currentStoreId = storeId
}

/** Sklep, do którego kolejki trafi plik z tego folderu, albo `null`, gdy zapis idzie do folderu. */
export function queueStoreFor(folderKey: string): string | null {
  if (!currentStoreId || !enabledStores.includes(currentStoreId)) return null
  if (folderKey.startsWith('mea-')) return null
  return currentStoreId
}

/** Po dodaniu pliku do kolejki (np. odświeżenie licznika). */
export function onFileQueued(listener: (storeId: string) => void) {
  queuedListeners.add(listener)
  return () => {
    queuedListeners.delete(listener)
  }
}

/** Wysyła plik do kolejki sklepu zamiast zapisu w folderze. */
export async function enqueueFile(storeId: string, folderKey: string, fileName: string, data: Blob) {
  const formData = new FormData()
  formData.append('folderKey', folderKey)
  formData.append('folderLabel', QUEUE_FOLDER_LABELS[folderKey] ?? folderKey)
  formData.append('fileName', fileName)
  formData.append('file', data, fileName)
  await api(`/stores/${storeId}/file-queue`, { method: 'POST', formData })
  queuedListeners.forEach((listener) => listener(storeId))
}
