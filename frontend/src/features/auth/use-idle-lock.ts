import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'

import { meQueryKey, useMe } from '@/features/auth/api'
import { api } from '@/lib/api'
import { lastActivity, markActivity } from '@/lib/last-activity'
import type { Me } from '@/lib/types'

const CHECK_EVERY_MS = 15_000

/**
 * Blokada po bezczynności: po `idleLockMinutes` bez ruchu myszy, klawiatury i dotyku (w żadnej karcie aplikacji)
 * konto idzie do tła (`POST /api/auth/lock`) i strona przechodzi na logowanie z listą kont, gdzie wraca się PIN-em.
 * Czas ustawia każdy na swoim koncie (Moje konto, `null` = bez wylogowywania), a karta przed wylogowaniem sprawdza, czy
 * w międzyczasie (w innej karcie albo na innym komputerze) nie zmieniono go ani nie przełączono konta.
 * Odświeżanie danych w tle nie liczy się jako ruch. Po uśpieniu komputera timer sprawdza czas od razu po powrocie
 * (zegar, nie liczba ticków), a backend i tak odkłada konto, z którego długo nic nie przyszło.
 */
export function useIdleLock() {
  const queryClient = useQueryClient()
  const { data: me } = useMe()
  const minutes = me?.idleLockMinutes ?? null
  const userId = me?.id

  useEffect(() => {
    if (!minutes) return
    const limitMs = minutes * 60_000
    let locking = false
    // Wejście, zmiana konta albo czasu blokady liczy czas od nowa.
    markActivity()

    const lock = async () => {
      try {
        const current = await api<Me>('/auth/me')
        if (current.idleLockMinutes !== minutes) {
          if (current.id !== userId) queryClient.clear()
          queryClient.setQueryData(meQueryKey, current)
          locking = false
          return
        }
        await api('/auth/lock', { method: 'POST' })
      } catch {
        // Sesja już wygasła albo nie ma połączenia: ekran i tak się blokuje.
      }
      // Jak przy 401: dane konta znikają z pamięci, RequireAuth przenosi na logowanie z kontami w tle.
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== meQueryKey[0] })
      queryClient.setQueryData(meQueryKey, null)
    }

    const check = () => {
      if (locking) return
      if (Date.now() - lastActivity() < limitMs) return
      locking = true
      void lock()
    }

    const onVisible = () => {
      if (document.visibilityState === 'visible') check()
    }
    document.addEventListener('visibilitychange', onVisible)
    const timer = window.setInterval(check, CHECK_EVERY_MS)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      window.clearInterval(timer)
    }
  }, [minutes, userId, queryClient])
}
