import { config } from './config'
import type { ReaderInfo } from './types'

/** Błąd z API: komunikat z ProblemDetail (detail) i kod HTTP; 0 = brak połączenia. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

const REQUEST_TIMEOUT_MS = 15_000

/**
 * Kiosk zna dwa klucze: klucz czytnika (odbicia, stały, w SecureStore) i token kierownika (tylko w pamięci, na czas
 * programowania kart). `auth` mówi, którym przedstawia się żądanie.
 */
export type Auth = { kind: 'reader'; key?: string; server?: string } | { kind: 'manager'; token: string } | null

export async function api<T>(path: string, init: { method?: string; json?: unknown; auth?: Auth } = {}): Promise<T> {
  const auth = init.auth ?? null
  const server = (auth?.kind === 'reader' ? auth.server : undefined) ?? config().server
  const headers: Record<string, string> = { Accept: 'application/json' }
  const bearer =
    auth?.kind === 'manager' ? auth.token : auth?.kind === 'reader' ? (auth.key ?? config().readerKey) : null
  if (bearer) headers.Authorization = `Bearer ${bearer}`
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
    throw new ApiError(`Brak połączenia z serwerem ${server}.`, 0)
  } finally {
    clearTimeout(timeout)
  }
  if (!response.ok) throw new ApiError(await problemMessage(response), response.status)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

async function problemMessage(response: Response) {
  try {
    const body = (await response.json()) as { detail?: string }
    if (body.detail) return body.detail
  } catch {
    // treść bez JSON-a
  }
  if (response.status === 401) return 'Sesja wygasła. Zaloguj się ponownie.'
  if (response.status === 403) return 'Brak uprawnień.'
  if (response.status === 404) return 'Nie znaleziono.'
  return `Błąd serwera (${response.status}).`
}

/** Sprawdza klucz czytnika na podanym serwerze (konfiguracja) albo zapisanym (start aplikacji). */
export const fetchReader = (key?: string, server?: string) =>
  api<ReaderInfo>('/time-clock/reader', { auth: { kind: 'reader', key, server } })
