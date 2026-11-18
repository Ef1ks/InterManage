import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'

import { meQueryKey, useMe } from '@/features/auth/api'
import { api } from '@/lib/api'

/** Ostatni ruch użytkownika, wspólny dla wszystkich kart aplikacji w tej przeglądarce. */
const STORAGE_KEY = 'intermanage.lastActivity'
const CHECK_EVERY_MS = 15_000
/** Zapis do localStorage najwyżej co tyle, bo ruch myszy przychodzi dziesiątki razy na sekundę. */
const WRITE_EVERY_MS = 5_000
const EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart'] as const

function readLastActivity(): number {
  try {
    const value = Number(window.localStorage.getItem(STORAGE_KEY))
    return Number.isFinite(value) ? value : 0
  } catch {
    return 0
  }
}

function writeLastActivity(at: number) {
  try {
    window.localStorage.setItem(STORAGE_KEY, String(at))
  } catch {
    // Bez localStorage liczy się tylko ruch w tej karcie.
  }
}

/**
 * Blokada po bezczynności: po `idleLockMinutes` bez ruchu myszy, klawiatury i dotyku (w żadnej karcie aplikacji)
 * konto idzie do tła (`POST /api/auth/lock`) i strona przechodzi na logowanie z listą kont, gdzie wraca się PIN-em.
 * Odświeżanie danych w tle nie liczy się jako ruch. Po uśpieniu komputera timer sprawdza czas od razu po powrocie
 * (zegar, nie liczba ticków), a backend i tak odkłada konto, z którego długo nic nie przyszło.
 */
export function useIdleLock() {
  const queryClient = useQueryClient()
  const { data: me } = useMe()
  const minutes = me?.idleLockMinutes ?? null

  useEffect(() => {
    if (!minutes) return
    const limitMs = minutes * 60_000
    let lastActivity = Date.now()
    let lastWrite = 0
    let locking = false
    writeLastActivity(lastActivity)

    const onActivity = () => {
      lastActivity = Date.now()
      if (lastActivity - lastWrite >= WRITE_EVERY_MS) {
        lastWrite = lastActivity
        writeLastActivity(lastActivity)
      }
    }

    const check = () => {
      if (locking) return
      const last = Math.max(lastActivity, readLastActivity())
      if (Date.now() - last < limitMs) return
      locking = true
      void api('/auth/lock', { method: 'POST' })
        .catch(() => undefined)
        .finally(() => {
          // Jak przy 401: dane konta znikają z pamięci, RequireAuth przenosi na logowanie z kontami w tle.
          queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== meQueryKey[0] })
          queryClient.setQueryData(meQueryKey, null)
        })
    }

    for (const event of EVENTS) window.addEventListener(event, onActivity, { passive: true, capture: true })
    const onVisible = () => {
      if (document.visibilityState === 'visible') check()
    }
    document.addEventListener('visibilitychange', onVisible)
    const timer = window.setInterval(check, CHECK_EVERY_MS)
    return () => {
      for (const event of EVENTS) window.removeEventListener(event, onActivity, { capture: true })
      document.removeEventListener('visibilitychange', onVisible)
      window.clearInterval(timer)
    }
  }, [minutes, queryClient])
}
