import { useCallback, useEffect, useState } from 'react'

import { ApiError, fetchNamedFile } from '@/lib/api'

/*
 * Foldery na komputerze wybrane raz przez użytkownika (File System Access API, Chrome i Edge). Uchwyt folderu
 * zostaje w IndexedDB przeglądarki, więc kolejny zapis idzie prosto do niego. Każdy rodzaj pliku ma własny klucz
 * (np. "deli-order-lst"), bo pliki trafiają do różnych folderów. Firefox i Safari tego nie mają - wtedy zostaje
 * zwykłe pobieranie.
 */

/** Rodzaj pliku z własnym folderem. `suggestedPath` podpowiadamy w oknie wyboru (np. folder za skrótem). */
export type SavedFolderSpec = {
  key: string
  /** Co trafia do folderu, np. „Plik .lst”. */
  label: string
  suggestedPath?: string
}

type PermissionMode = { mode: 'readwrite' }

/** Metody spoza typów TypeScriptu (dostępne w Chrome i Edge). */
type DirectoryHandle = FileSystemDirectoryHandle & {
  queryPermission(options: PermissionMode): Promise<PermissionState>
  requestPermission(options: PermissionMode): Promise<PermissionState>
}

declare global {
  interface Window {
    showDirectoryPicker?: (options?: { id?: string; mode?: 'readwrite' }) => Promise<FileSystemDirectoryHandle>
  }
}

export const folderSavingSupported = typeof window !== 'undefined' && 'showDirectoryPicker' in window

const DB_NAME = 'intermanage'
const STORE = 'folders'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1)
    request.onupgradeneeded = () => request.result.createObjectStore(STORE)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

async function transaction<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb()
  try {
    return await new Promise<T>((resolve, reject) => {
      const request = run(db.transaction(STORE, mode).objectStore(STORE))
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
  } finally {
    db.close()
  }
}

async function readFolder(key: string): Promise<DirectoryHandle | null> {
  try {
    return ((await transaction('readonly', (store) => store.get(key))) as DirectoryHandle | undefined) ?? null
  } catch {
    return null
  }
}

/**
 * Okno wyboru folderu. Zaczyna od ostatnio wybranego dla tego klucza (`id`), ale ścieżki nie da się podać z kodu:
 * folder za skrótem (np. N:\avisoconfig na zablokowanym dysku N) wybiera się, wklejając ścieżkę w pasek adresu okna.
 * `null`, gdy użytkownik zamknął okno.
 */
export async function pickFolder(key: string): Promise<FileSystemDirectoryHandle | null> {
  if (!window.showDirectoryPicker) {
    throw new ApiError(0, 'Ta przeglądarka nie zapisuje do wybranego folderu. Użyj Chrome albo Edge.')
  }
  let handle: FileSystemDirectoryHandle
  try {
    handle = await window.showDirectoryPicker({ id: key.replace(/[^\w-]/g, '').slice(0, 32), mode: 'readwrite' })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return null
    throw new ApiError(0, 'Nie można użyć tego folderu. Przeglądarka nie pozwala zapisywać w folderach systemowych.')
  }
  await transaction('readwrite', (store) => store.put(handle, key))
  return handle
}

/**
 * Pyta o zgodę na zapis, jeśli przeglądarka jej jeszcze nie ma (po ponownym otwarciu pyta raz, chyba że wybrano
 * „Zezwalaj przy każdej wizycie”). Wywołuj od razu po kliknięciu, zanim cokolwiek pójdzie do serwera: okno zgody
 * działa tylko bezpośrednio po geście użytkownika.
 */
export async function ensureFolderAccess(key: string): Promise<boolean> {
  const handle = await readFolder(key)
  if (!handle) return false
  const mode: PermissionMode = { mode: 'readwrite' }
  if ((await handle.queryPermission(mode)) === 'granted') return true
  try {
    return (await handle.requestPermission(mode)) === 'granted'
  } catch {
    return false
  }
}

/** Zapisuje plik w folderze klucza, nadpisując plik o tej samej nazwie. */
export async function saveToFolder(key: string, fileName: string, data: Blob): Promise<void> {
  const handle = await readFolder(key)
  if (!handle) throw new ApiError(0, 'Nie wybrano folderu do zapisu.')
  if (!(await ensureFolderAccess(key))) {
    throw new ApiError(0, `Brak zgody na zapis w folderze ${handle.name}.`)
  }
  try {
    const file = await handle.getFileHandle(fileName, { create: true })
    const writable = await file.createWritable()
    await writable.write(data)
    await writable.close()
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotFoundError') {
      throw new ApiError(0, `Folderu ${handle.name} już nie ma (albo dysk jest odłączony). Wybierz go ponownie.`)
    }
    throw new ApiError(0, `Nie udało się zapisać pliku w folderze ${handle.name}.`)
  }
}

/** Pobiera plik z API i zapisuje go w folderze klucza. Zwraca nazwę zapisanego pliku. */
export async function saveApiFileToFolder(key: string, path: string, fallbackName?: string): Promise<string> {
  const { fileName, blob } = await fetchNamedFile(path, fallbackName)
  await saveToFolder(key, fileName, blob)
  return fileName
}

/** Nazwa wybranego folderu (bez pełnej ścieżki - przeglądarka jej nie zdradza) i jego zmiana. */
export function useSavedFolder(key: string) {
  const [name, setName] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    let active = true
    readFolder(key).then((handle) => {
      if (!active) return
      setName(handle?.name ?? null)
      setLoaded(true)
    })
    return () => {
      active = false
    }
  }, [key])

  const pick = useCallback(async () => {
    const handle = await pickFolder(key)
    if (handle) setName(handle.name)
    return handle
  }, [key])

  return { name, loaded, pick }
}
