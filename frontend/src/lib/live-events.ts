/*
 * Wiadomości na żywo z serwera (Server-Sent Events, `GET /api/stores/{storeId}/live`): agent sklepu zapisał plik,
 * program sklepu wczytał zmianę cen, przyszły pliki cen z kasy albo dziennik ATHOS, agent jest online / offline.
 * Wiadomość to sam sygnał; odbiorca odświeża po niej swoje zapytania (features/store-agent/use-live-events).
 *
 * Jedno połączenie na przeglądarkę (i sklep, i konto), a nie na kartę: karty wybierają przewodnika przez Web Locks API,
 * przewodnik trzyma `EventSource`, a pozostałe karty dostają wiadomości przez `BroadcastChannel`. Po zamknięciu
 * przewodnika blokadę przejmuje kolejna karta. Bez Web Locks (starsze przeglądarki) każda karta łączy się sama.
 * Połączenie nie odnawia tokenu i nie liczy się jako aktywność (blokada po bezczynności działa jak dotąd).
 */

export type LiveMessage = { topic: string; [field: string]: unknown }

type Listener = (storeId: string, message: LiveMessage) => void

const listeners = new Set<Listener>()

export function onLiveMessage(listener: Listener) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function dispatch(storeId: string, message: LiveMessage) {
  listeners.forEach((listener) => {
    try {
      listener(storeId, message)
    } catch {
      // Błąd jednego odbiorcy nie zatrzymuje pozostałych.
    }
  })
}

const RETRY_MIN_MS = 5_000
const RETRY_MAX_MS = 60_000

/** Otwiera EventSource z ponawianiem; zwraca funkcję zamykającą. */
function openStream(storeId: string, onMessage: (message: LiveMessage) => void): () => void {
  let source: EventSource | null = null
  let retry = RETRY_MIN_MS
  let timer: ReturnType<typeof setTimeout> | undefined
  let closed = false

  const connect = () => {
    if (closed) return
    source = new EventSource(`/api/stores/${storeId}/live`)
    source.onopen = () => {
      retry = RETRY_MIN_MS
    }
    source.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data) as LiveMessage
        if (message && typeof message.topic === 'string') onMessage(message)
      } catch {
        // Nie-JSON (np. pusta wiadomość) pomijamy.
      }
    }
    source.onerror = () => {
      // Przeglądarka sama ponawia zerwane połączenie; po odmowie (401, 403) zamyka je, wtedy ponawiamy my.
      if (source?.readyState === EventSource.CLOSED) {
        source = null
        timer = setTimeout(connect, retry)
        retry = Math.min(retry * 2, RETRY_MAX_MS)
      }
    }
  }
  connect()
  return () => {
    closed = true
    clearTimeout(timer)
    source?.close()
  }
}

/**
 * Łączy tę kartę z wiadomościami sklepu (jako przewodnik albo przez kanał innych kart). Zwraca funkcję rozłączającą
 * (zmiana sklepu, konta, zamknięcie układu aplikacji).
 */
export function connectLive(storeId: string, userId: string): () => void {
  if (typeof window === 'undefined' || typeof EventSource === 'undefined') return () => {}
  const name = `intermanage-live:${storeId}:${userId}`
  const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel(name) : null
  if (channel) {
    channel.onmessage = (event: MessageEvent<LiveMessage>) => dispatch(storeId, event.data)
  }
  const lead = (message: LiveMessage) => {
    dispatch(storeId, message)
    channel?.postMessage(message)
  }

  const locks = (navigator as Navigator & { locks?: LockManager }).locks
  if (!locks || !channel) {
    const close = openStream(storeId, (message) => dispatch(storeId, message))
    return () => {
      close()
      channel?.close()
    }
  }

  const abort = new AbortController()
  locks
    .request(name, { signal: abort.signal }, () => {
      const close = openStream(storeId, lead)
      return new Promise<void>((resolve) => {
        abort.signal.addEventListener('abort', () => {
          close()
          resolve()
        })
      })
    })
    .catch(() => {
      // AbortError: karta zmieniła sklep albo konto, zanim została przewodnikiem.
    })
  return () => {
    abort.abort()
    channel.close()
  }
}
