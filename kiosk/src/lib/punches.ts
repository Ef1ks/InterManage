import { api, ApiError } from './api'
import { readStored, writeStored } from './storage'
import type { PunchResult } from './types'

const QUEUE_KEY = 'kiosk.pendingPunches'
/** Ta sama karta przyłożona drugi raz w tym czasie to jedno odbicie (karta przytrzymana przy czytniku). */
const REPEAT_WINDOW_MS = 5_000
const MAX_QUEUE = 500

type Pending = { cardUid: string; punchedAt: string }

export type PunchOutcome =
  | { kind: 'ok'; result: PunchResult }
  | { kind: 'queued' }
  | { kind: 'error'; message: string }
  | { kind: 'repeat' }

let queue: Pending[] = []
let lastCard: { uid: string; at: number } | null = null
let flushing: Promise<void> | null = null

export async function loadQueue() {
  try {
    queue = JSON.parse((await readStored(QUEUE_KEY)) ?? '[]') as Pending[]
  } catch {
    queue = []
  }
  return queue.length
}

export const pendingCount = () => queue.length

function persist() {
  return writeStored(QUEUE_KEY, queue.length ? JSON.stringify(queue) : null)
}

const send = (pending: Pending) =>
  api<PunchResult>('/time-clock/punches', { method: 'POST', json: pending, auth: { kind: 'reader' } })

/**
 * Odbicie karty. Czas to chwila przyłożenia, więc bez internetu odbicie czeka w kolejce i trafia na serwer później
 * z prawdziwą godziną; kierunek (wejście, wyjście) liczy serwer z kolejnych odbić.
 */
export async function punch(cardUid: string): Promise<PunchOutcome> {
  const now = Date.now()
  if (lastCard && lastCard.uid === cardUid && now - lastCard.at < REPEAT_WINDOW_MS) return { kind: 'repeat' }
  lastCard = { uid: cardUid, at: now }
  const pending = { cardUid, punchedAt: new Date(now).toISOString() }
  // Starsze odbicia najpierw, inaczej serwer pomyliłby wejście z wyjściem.
  await flushQueue()
  if (queue.length) return enqueue(pending)
  try {
    return { kind: 'ok', result: await send(pending) }
  } catch (error) {
    if (error instanceof ApiError && error.status === 0) return enqueue(pending)
    if (error instanceof ApiError && error.status >= 500) return enqueue(pending)
    return { kind: 'error', message: error instanceof Error ? error.message : 'Nie udało się odbić karty.' }
  }
}

/** Wybór „Przerwa” zaraz po wyjściu: serwer zamienia to wyjście na wyjście na przerwę. */
export async function markBreak(punchId: string): Promise<PunchOutcome> {
  try {
    return {
      kind: 'ok',
      result: await api<PunchResult>(`/time-clock/punches/${punchId}/break`, {
        method: 'POST',
        auth: { kind: 'reader' },
      }),
    }
  } catch (error) {
    return { kind: 'error', message: error instanceof Error ? error.message : 'Nie udało się zapisać przerwy.' }
  }
}

async function enqueue(pending: Pending): Promise<PunchOutcome> {
  queue = [...queue, pending].slice(-MAX_QUEUE)
  await persist()
  return { kind: 'queued' }
}

/** Wysyła zaległe odbicia po kolei; przerywa przy braku połączenia, odrzucone (np. nieznana karta) pomija. */
export function flushQueue(): Promise<void> {
  if (!queue.length) return Promise.resolve()
  flushing ??= (async () => {
    try {
      while (queue.length) {
        try {
          await send(queue[0])
        } catch (error) {
          if (error instanceof ApiError && (error.status === 0 || error.status >= 500 || error.status === 403)) break
        }
        queue = queue.slice(1)
        await persist()
      }
    } finally {
      flushing = null
    }
  })()
  return flushing
}
