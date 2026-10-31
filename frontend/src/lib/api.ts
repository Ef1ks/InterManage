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
  const { fileName, blob } = await fetchNamedFile(path, fallbackName)
  saveBlob(blob, fileName)
}

function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.append(link)
  link.click()
  link.remove()
  // Przeglądarka zaczyna pobieranie asynchronicznie - zwalniamy adres chwilę później.
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

/**
 * Pobiera plik, który serwer robi z danych wysłanych w żądaniu (POST z JSON-em, np. tabela do Excela), i zapisuje go
 * jak {@link downloadFile}.
 */
export async function downloadPostedFile(path: string, json: unknown, fallbackName = 'plik'): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = await csrfToken()
  if (token) headers['X-XSRF-TOKEN'] = token
  const { fileName, blob } = await namedFile(
    await fetchFile(path, { method: 'POST', headers, body: JSON.stringify(json) }),
    fallbackName,
  )
  saveBlob(blob, fileName)
}

/** Plik z API z nazwą z nagłówka Content-Disposition (np. do zapisu w wybranym folderze). */
export async function fetchNamedFile(path: string, fallbackName = 'plik'): Promise<{ fileName: string; blob: Blob }> {
  return namedFile(await fetchFile(path), fallbackName)
}

async function namedFile(response: Response, fallbackName: string): Promise<{ fileName: string; blob: Blob }> {
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const match = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i.exec(disposition)
  const fileName = match ? decodeURIComponent(match[1] ?? match[2]) : fallbackName
  return { fileName, blob: await response.blob() }
}

/**
 * Otwiera okno drukowania pliku PDF z API bez zapisywania go na dysku: plik ładuje się w ukrytej ramce,
 * a przeglądarka drukuje go swoją przeglądarką PDF. Kończy się, gdy okno drukowania jest już otwarte.
 */
export async function printFile(path: string): Promise<void> {
  const response = await fetchFile(path)
  const blob = new Blob([await response.blob()], { type: 'application/pdf' })
  const url = URL.createObjectURL(blob)
  const frame = document.createElement('iframe')
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden'
  frame.src = url
  await new Promise<void>((resolve, reject) => {
    frame.onload = () => {
      try {
        frame.contentWindow?.focus()
        frame.contentWindow?.print()
        resolve()
      } catch {
        reject(new ApiError(0, 'Przeglądarka nie pozwoliła wydrukować pliku. Pobierz go i wydrukuj z dysku.'))
      }
    }
    document.body.append(frame)
  })
  // Okno drukowania korzysta z ramki do końca wydruku - sprzątamy dopiero po dłuższej chwili.
  setTimeout(() => {
    frame.remove()
    URL.revokeObjectURL(url)
  }, 60_000)
}

async function fetchFile(path: string, init: RequestInit = {}): Promise<Response> {
  const response = await fetch(`/api${path}`, { ...init, credentials: 'same-origin' })
  if (!response.ok) {
    throw await toApiError(response)
  }
  return response
}
