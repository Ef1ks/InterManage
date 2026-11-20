import { useCallback, useEffect, useRef, useState } from 'react'

import { ApiError, fetchNamedFile } from '@/lib/api'
import { saveViaAgent } from '@/lib/agent-save'
import { enqueueFile, queueStoreFor } from '@/lib/file-queue'
import { agentStoreFor } from '@/lib/store-agent'

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
  /** Domyślna ścieżka, gdy sklep nie ustawił swojej (`folder:<key>` w ścieżkach sklepu, `lib/store-locations`). */
  suggestedPath?: string
  /**
   * Ścieżka ustawiana gdzie indziej (Ustawienia sklepu → Pliki PROTO): `suggestedPath` jest już ścieżką sklepu,
   * więc okno folderu nie ma własnego pola.
   */
  pathInSettings?: boolean
}

/** Zapis plików do folderu albo samo czytanie (pliki z systemu sklepu, np. TRACEART). */
export type FolderMode = 'read' | 'readwrite'

type PermissionMode = { mode: FolderMode }

/** Metody spoza typów TypeScriptu (dostępne w Chrome i Edge). */
type DirectoryHandle = FileSystemDirectoryHandle & {
  queryPermission(options: PermissionMode): Promise<PermissionState>
  requestPermission(options: PermissionMode): Promise<PermissionState>
  values(): AsyncIterable<FileSystemHandle>
}

declare global {
  interface Window {
    showDirectoryPicker?: (options?: { id?: string; mode?: FolderMode }) => Promise<FileSystemDirectoryHandle>
  }
}

export const folderSavingSupported = typeof window !== 'undefined' && 'showDirectoryPicker' in window

const DB_NAME = 'intermanage'
const STORE = 'folders'

/*
 * Limity czasu: operacje na folderze (dysk sieciowy, IndexedDB przeglądarki) potrafią zawisnąć bez błędu, a przycisk
 * zapisu kręciłby się wtedy bez końca. Po limicie zapis kończy się błędem, który mówi, na czym stanął. Zapis pliku,
 * który przekroczył limit, przeglądarka może jeszcze dokończyć, więc komunikat każe najpierw zajrzeć do folderu.
 */
const DB_TIMEOUT_MS = 15_000
const PERMISSION_CHECK_TIMEOUT_MS = 15_000
/** Okno zgody czeka na użytkownika, więc dłużej. */
const PERMISSION_PROMPT_TIMEOUT_MS = 120_000
const WRITE_TIMEOUT_MS = 60_000

class FolderTimeout extends ApiError {
  constructor(message: string) {
    super(0, message)
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new FolderTimeout(message)), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

const seconds = (ms: number) => `${Math.round(ms / 1000)} s`

function openDb(): Promise<IDBDatabase> {
  const opening = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1)
    request.onupgradeneeded = () => request.result.createObjectStore(STORE)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
    request.onblocked = () => reject(new ApiError(0, 'Zamknij inne karty z aplikacją i spróbuj ponownie.'))
  })
  return withTimeout(
    opening,
    DB_TIMEOUT_MS,
    `Przeglądarka nie odczytała zapamiętanych folderów w ${seconds(DB_TIMEOUT_MS)}. Zamknij inne karty z aplikacją albo uruchom przeglądarkę ponownie.`,
  )
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
  } catch (error) {
    // Zawieszona przeglądarka to nie „brak folderu”: inaczej zapis po cichu przeszedłby na pobieranie.
    if (error instanceof FolderTimeout) throw error
    return null
  }
}

/**
 * Okno wyboru folderu. Zaczyna od ostatnio wybranego dla tego klucza (`id`), ale ścieżki nie da się podać z kodu:
 * folder za skrótem (np. N:\avisoconfig na zablokowanym dysku N) wybiera się, wklejając ścieżkę w pasek adresu okna.
 * `null`, gdy użytkownik zamknął okno.
 */
export async function pickFolder(
  key: string,
  mode: FolderMode = 'readwrite',
): Promise<FileSystemDirectoryHandle | null> {
  if (!window.showDirectoryPicker) {
    throw new ApiError(0, 'Ta przeglądarka nie zapisuje do wybranego folderu. Użyj Chrome albo Edge.')
  }
  let handle: FileSystemDirectoryHandle
  try {
    handle = await window.showDirectoryPicker({ id: key.replace(/[^\w-]/g, '').slice(0, 32), mode })
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
export async function ensureFolderAccess(key: string, folderMode: FolderMode = 'readwrite'): Promise<boolean> {
  // Kolejka plików sklepu (`lib/file-queue`) albo agent sklepu (`lib/store-agent`): zapis idzie na serwer, folder
  // nie jest potrzebny. Zgody pyta dopiero zapas z przeglądarki, gdy agent nie odpowie (po kliknięciu w toaście).
  if (folderMode === 'readwrite' && (queueStoreFor(key) || agentStoreFor(key))) return true
  return requestFolderAccess(key, folderMode)
}

/** {@link ensureFolderAccess} bez kolejki plików: zgoda na prawdziwy folder (zapis plików z kolejki). */
export async function requestFolderAccess(key: string, folderMode: FolderMode = 'readwrite'): Promise<boolean> {
  const handle = await readFolder(key)
  if (!handle) return false
  const mode: PermissionMode = { mode: folderMode }
  const state = await withTimeout(
    handle.queryPermission(mode),
    PERMISSION_CHECK_TIMEOUT_MS,
    `Folder ${handle.name} nie odpowiada (${seconds(PERMISSION_CHECK_TIMEOUT_MS)}). Sprawdź, czy dysk sieciowy jest podłączony.`,
  )
  if (state === 'granted') return true
  try {
    return (
      (await withTimeout(
        handle.requestPermission(mode),
        PERMISSION_PROMPT_TIMEOUT_MS,
        `Brak odpowiedzi na pytanie o zgodę na folder ${handle.name}. Kliknij jeszcze raz i zezwól w okienku przy pasku adresu.`,
      )) === 'granted'
    )
  } catch (error) {
    if (error instanceof FolderTimeout) throw error
    return false
  }
}

/** Czy ta przeglądarka ma już wybrany folder pod tym kluczem (bez względu na kolejkę plików i zgodę). */
export async function hasSavedFolder(key: string): Promise<boolean> {
  return (await readFolder(key).catch(() => null)) !== null
}

/**
 * Stan folderu bez pytania o zgodę (do pracy w tle): `missing` = nie wybrano, `prompt` = przeglądarka zapyta przy
 * kolejnym kliknięciu ({@link ensureFolderAccess}).
 */
export async function folderAccess(key: string, folderMode: FolderMode): Promise<PermissionState | 'missing'> {
  if (folderMode === 'readwrite' && (queueStoreFor(key) || agentStoreFor(key))) return 'granted'
  try {
    const handle = await readFolder(key)
    if (!handle) return 'missing'
    return await withTimeout(handle.queryPermission({ mode: folderMode }), PERMISSION_CHECK_TIMEOUT_MS, '')
  } catch {
    return 'prompt'
  }
}

/**
 * Prosi o zgodę na wszystkie zapamiętane foldery, których klucz zaczyna się od `prefix` (np. przy logowaniu, bo to
 * kliknięcie). Folder ze zgodą „przy każdej wizycie” przechodzi bez okna. `true`, gdy którykolwiek ma zgodę.
 */
export async function requestStoredFolderAccess(prefix: string, folderMode: FolderMode): Promise<boolean> {
  let keys: IDBValidKey[]
  try {
    keys = await transaction('readonly', (store) => store.getAllKeys())
  } catch {
    return false
  }
  let granted = false
  for (const key of keys) {
    if (
      typeof key === 'string' &&
      key.startsWith(prefix) &&
      (await requestFolderAccess(key, folderMode).catch(() => false))
    )
      granted = true
  }
  return granted
}

/**
 * Uchwyty plików w folderze klucza, których nazwa pasuje do `match`, bez czytania ich treści. Z podfolderów tylko
 * `subfolder` (bez względu na wielkość liter) razem z całą jego zawartością, np. BACKUP z plikami z poprzednich dni.
 * Wymaga zgody na odczyt.
 */
export async function listFolderFiles(
  key: string,
  match: (name: string) => boolean,
  subfolder?: string,
): Promise<FileSystemFileHandle[]> {
  const handle = await readFolder(key)
  if (!handle) throw new ApiError(0, 'Nie wybrano folderu.')
  const files: FileSystemFileHandle[] = []
  try {
    await collectFiles(
      handle,
      match,
      files,
      (name) => subfolder !== undefined && name.toUpperCase() === subfolder.toUpperCase(),
    )
  } catch (error) {
    if (error instanceof DOMException && error.name === 'NotFoundError') {
      throw new ApiError(0, `Folderu ${handle.name} już nie ma (albo dysk jest odłączony). Wybierz go ponownie.`)
    }
    throw new ApiError(0, `Nie udało się odczytać folderu ${handle.name}.`)
  }
  return files
}

const MAX_SUBFOLDER_DEPTH = 3

async function collectFiles(
  folder: DirectoryHandle,
  match: (name: string) => boolean,
  files: FileSystemFileHandle[],
  enter: (name: string) => boolean,
  depth = 0,
): Promise<void> {
  try {
    for await (const entry of folder.values()) {
      if (entry.kind === 'file') {
        if (match(entry.name)) files.push(entry as FileSystemFileHandle)
      } else if (depth < MAX_SUBFOLDER_DEPTH && enter(entry.name)) {
        // W podfolderze wchodzimy już wszędzie (np. BACKUP/<dzień>/…); błąd podfolderu nie psuje reszty.
        await collectFiles(entry as DirectoryHandle, match, files, () => true, depth + 1).catch(() => {})
      }
    }
  } catch (error) {
    // Plik przeniesiony do podfolderu w trakcie czytania: reszta pójdzie przy kolejnym sprawdzeniu.
    if (depth === 0 && !(error instanceof DOMException && error.name === 'NotReadableError')) throw error
  }
}

/**
 * Zapisuje plik w folderze klucza, nadpisując plik o tej samej nazwie. Przy włączonej kolejce plików sklepu
 * (`lib/file-queue`) plik trafia do kolejki na serwerze i zapisze się w folderze sklepu później, a przy pracy przez
 * agenta sklepu (`lib/store-agent`) zapisuje go agent (bez nadpisywania istniejącego pliku).
 */
export async function saveToFolder(key: string, fileName: string, data: Blob): Promise<void> {
  const queueStore = queueStoreFor(key)
  if (queueStore) {
    await enqueueFile(queueStore, key, fileName, data)
    return
  }
  // Sklep pracuje przez agenta, a agent jest online: plik idzie przez kolejkę do agenta (zapas: ta przeglądarka).
  const agentStore = agentStoreFor(key)
  if (agentStore) {
    const item = await enqueueFile(agentStore, key, fileName, data)
    await saveViaAgent(agentStore, item.id, fileName, () => writeToFolder(key, fileName, data))
    return
  }
  await writeToFolder(key, fileName, data)
}

/** Zapis w prawdziwym folderze, z pominięciem kolejki plików. */
export async function writeToFolder(key: string, fileName: string, data: Blob): Promise<void> {
  const handle = await readFolder(key)
  if (!handle) throw new ApiError(0, 'Nie wybrano folderu do zapisu.')
  if (!(await requestFolderAccess(key))) {
    throw new ApiError(0, `Brak zgody na zapis w folderze ${handle.name}.`)
  }
  const write = async () => {
    const file = await handle.getFileHandle(fileName, { create: true })
    const writable = await file.createWritable()
    await writable.write(data)
    await writable.close()
  }
  try {
    await withTimeout(
      write(),
      WRITE_TIMEOUT_MS,
      `Zapis pliku ${fileName} w folderze ${handle.name} nie skończył się w ${seconds(WRITE_TIMEOUT_MS)} (dysk sieciowy nie odpowiada?). Zanim spróbujesz ponownie, sprawdź, czy plik nie pojawił się w folderze.`,
    )
  } catch (error) {
    if (error instanceof FolderTimeout) throw error
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
export function useSavedFolder(key: string, mode: FolderMode = 'readwrite') {
  const [name, setName] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  // Folder wybrany, zanim skończył się odczyt zapamiętanego (bywa długi): spóźniony odczyt go nie nadpisze.
  const picked = useRef(false)

  useEffect(() => {
    let active = true
    picked.current = false
    readFolder(key)
      .catch(() => null)
      .then((handle) => {
        if (!active || picked.current) return
        setName(handle?.name ?? null)
        setLoaded(true)
      })
    return () => {
      active = false
    }
  }, [key])

  const pick = useCallback(async () => {
    const handle = await pickFolder(key, mode)
    if (handle) {
      picked.current = true
      setName(handle.name)
      setLoaded(true)
    }
    return handle
  }, [key, mode])

  return { name, loaded, pick }
}
