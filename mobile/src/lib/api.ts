import Constants from 'expo-constants'
import * as SecureStore from 'expo-secure-store'

const TOKEN_KEY = 'intermanage.token'
const SERVER_KEY = 'intermanage.server'

export const DEFAULT_SERVER: string =
  (Constants.expoConfig?.extra as { apiUrl?: string } | undefined)?.apiUrl ?? 'https://intermanage.pl'

/** Błąd z API: komunikat z ProblemDetail (detail) i kod HTTP. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

// Raporty roku potrafią liczyć się kilka sekund, ale dłużej niż 30 s to już brak połączenia.
const REQUEST_TIMEOUT_MS = 30_000

let token: string | null = null
let server = DEFAULT_SERVER
let onUnauthorized: (() => void) | null = null

/**
 * SecureStore (Keystore Androida) potrafi na emulatorze nie odpowiadać, więc nigdy na nim nie czekamy dłużej niż
 * kilka sekund: wartości działają z pamięci, a zapis na telefonie to tylko zapamiętanie na kolejne uruchomienie.
 */
const STORAGE_TIMEOUT_MS = 3_000

function withTimeout<T>(promise: Promise<T>, fallback: T): Promise<T> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(fallback), STORAGE_TIMEOUT_MS)
    promise.then(
      (value) => {
        clearTimeout(timer)
        resolve(value)
      },
      () => {
        clearTimeout(timer)
        resolve(fallback)
      },
    )
  })
}

export const readStored = (key: string) => withTimeout(SecureStore.getItemAsync(key), null)

/** Zapis w tle: błąd albo brak odpowiedzi schowka nie blokuje aplikacji. */
export function writeStored(key: string, value: string | null) {
  const write = value != null ? SecureStore.setItemAsync(key, value) : SecureStore.deleteItemAsync(key)
  void withTimeout(write, undefined)
}

/** Wczytuje zapamiętany token i adres serwera; zwraca token (pusty = trzeba się zalogować). */
export async function restoreSession() {
  const [savedToken, savedServer] = await Promise.all([readStored(TOKEN_KEY), readStored(SERVER_KEY)])
  token = savedToken
  server = savedServer || DEFAULT_SERVER
  return token
}

export const currentServer = () => server

/**
 * Źródło obrazka z API: adres z CDN (R2) jest publiczny, a względny (zdjęcie zapisane w bazie) idzie na serwer
 * z tokenem, bo bez niego backend go nie odda.
 */
export function imageSource(url: string) {
  if (/^https?:\/\//i.test(url)) return { uri: url }
  return { uri: server + url, headers: token ? { Authorization: `Bearer ${token}` } : undefined }
}

export function setServer(url: string) {
  server = normalizeServer(url) || DEFAULT_SERVER
  writeStored(SERVER_KEY, server)
}

export function normalizeServer(url: string) {
  let value = url.trim().replace(/\/+$/, '')
  if (value && !/^https?:\/\//i.test(value)) {
    // Adres w sieci lokalnej (emulator 10.0.2.2, 192.168…, localhost) to zwykle backend bez HTTPS.
    const local = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/i.test(value)
    value = `${local ? 'http' : 'https'}://${value}`
  }
  return value
}

export function setToken(value: string | null) {
  token = value
  writeStored(TOKEN_KEY, value)
}

/** Wołane po 401 (token wygasł, zmiana hasła, blokada konta): wraca do logowania. */
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

/**
 * Żądanie do API z tokenem w `Authorization: Bearer`. Backend odnawia starszy token i oddaje go w `X-Auth-Token`,
 * więc aplikacja wylogowuje dopiero po 30 dniach bez korzystania.
 */
export async function api<T>(path: string, init: { method?: string; json?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (init.json !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  try {
    response = await fetch(`${server}/api${path}`, {
      method: init.method ?? 'GET',
      headers,
      body: init.json !== undefined ? JSON.stringify(init.json) : undefined,
      credentials: 'omit',
      signal: controller.signal,
    })
  } catch {
    throw new ApiError(
      controller.signal.aborted
        ? `Serwer ${server} nie odpowiada. Sprawdź adres serwera i internet.`
        : `Brak połączenia z serwerem ${server}. Sprawdź adres i internet.`,
      0,
    )
  } finally {
    clearTimeout(timeout)
  }

  const refreshed = response.headers.get('X-Auth-Token')
  if (refreshed) setToken(refreshed)

  if (!response.ok) {
    const hadToken = token != null
    if (response.status === 401 && hadToken) {
      setToken(null)
      onUnauthorized?.()
    }
    throw new ApiError(await problemMessage(response, hadToken), response.status)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

async function problemMessage(response: Response, hadToken: boolean) {
  try {
    const body = (await response.json()) as { detail?: string; title?: string }
    if (body.detail) return body.detail
  } catch {
    // treść bez JSON-a
  }
  // 401 bez komunikatu przy logowaniu: serwer nie ma jeszcze POST /api/auth/mobile/login.
  if (response.status === 401 && !hadToken) {
    return 'Serwer nie obsługuje jeszcze logowania z aplikacji mobilnej (wdróż nowszy backend).'
  }
  if (response.status === 401) return 'Sesja wygasła. Zaloguj się ponownie.'
  if (response.status === 403) return 'Brak uprawnień.'
  if (response.status === 404) return 'Nie znaleziono.'
  return `Błąd serwera (${response.status}).`
}
