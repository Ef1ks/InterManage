/**
 * Klient API. JWT siedzi w ciasteczku AUTH_TOKEN (HttpOnly), więc JS go nie widzi, a przeglądarka dołącza je sama.
 * Ochrona CSRF: backend ustawia ciasteczko XSRF-TOKEN, a my odsyłamy je w nagłówku X-XSRF-TOKEN
 * przy żądaniach modyfikujących.
 */

export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: Record<string, string>

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

function readCookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1)
}

/** Backend czyści token przy logowaniu i wylogowaniu, więc pobieramy nowy, gdy go brak. */
async function csrfToken(): Promise<string | undefined> {
  if (!readCookie('XSRF-TOKEN')) {
    await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  }
  const token = readCookie('XSRF-TOKEN')
  return token && decodeURIComponent(token)
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** Wysyłane jako JSON. */
  json?: unknown
}

export async function api<T = void>(path: string, { method = 'GET', json }: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  let body: BodyInit | undefined

  if (json !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(json)
  }

  if (!SAFE_METHODS.has(method)) {
    const token = await csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }

  const response = await fetch(`/api${path}`, { method, headers, body, credentials: 'same-origin' })

  if (!response.ok) {
    throw await toApiError(response)
  }
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T
  }
  return (await response.json()) as T
}

async function toApiError(response: Response): Promise<ApiError> {
  const fallback: Record<number, string> = {
    401: 'Sesja wygasła. Zaloguj się ponownie.',
    403: 'Brak uprawnień do tej operacji.',
    404: 'Nie znaleziono zasobu.',
  }
  try {
    const problem = (await response.json()) as { detail?: string; errors?: Record<string, string> }
    return new ApiError(
      response.status,
      problem.detail ?? fallback[response.status] ?? 'Wystąpił błąd serwera.',
      problem.errors ?? {},
    )
  } catch {
    return new ApiError(response.status, fallback[response.status] ?? 'Wystąpił błąd serwera.')
  }
}
