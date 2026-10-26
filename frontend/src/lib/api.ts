/**
 * Klient API. JWT siedzi w ciasteczku AUTH_TOKEN (HttpOnly), więc JS go nie widzi, a przeglądarka dołącza je sama.
 * Ochrona CSRF: backend ustawia ciasteczko XSRF-TOKEN, a my odsyłamy je w nagłówku X-XSRF-TOKEN
 * przy żądaniach modyfikujących.
 */

export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors: Record<string, string>
  /** Cała odpowiedź ProblemDetail, np. `rowErrors` przy imporcie pliku. */
  readonly problem: Record<string, unknown>

  constructor(
    status: number,
    message: string,
    fieldErrors: Record<string, string> = {},
    problem: Record<string, unknown> = {},
  ) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
    this.problem = problem
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
  /** Wysyłane jako multipart/form-data (pliki). Nagłówek z granicą części ustawia przeglądarka. */
  formData?: FormData
}

export async function api<T = void>(path: string, { method = 'GET', json, formData }: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  let body: BodyInit | undefined

  if (json !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(json)
  } else if (formData) {
    body = formData
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
      problem,
    )
  } catch {
    return new ApiError(response.status, fallback[response.status] ?? 'Wystąpił błąd serwera.')
  }
}

/**
 * Pobiera plik z API (np. PDF zamówienia) i zapisuje go w przeglądarce pod nazwą z nagłówka Content-Disposition.
 * Idzie przez fetch, a nie zwykły link, żeby błędy (np. wygasła sesja) wracały jako ApiError.
 */
export async function downloadFile(path: string, fallbackName = 'plik'): Promise<void> {
  const response = await fetch(`/api${path}`, { credentials: 'same-origin' })
  if (!response.ok) {
    throw await toApiError(response)
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const match = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i.exec(disposition)
  const fileName = match ? decodeURIComponent(match[1] ?? match[2]) : fallbackName
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.append(link)
  link.click()
  link.remove()
  // Przeglądarka zaczyna pobieranie asynchronicznie - zwalniamy adres chwilę później.
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}
