import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'

import { meQueryKey } from '@/features/auth/api'

/** Godzina blokady po pracy, ta sama co `app.auth.workday-lock-at` w backendzie (czas sklepu). */
const LOCK_HOUR = 17
const CHECK_EVERY_MS = 60_000

const warsawParts = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Europe/Warsaw',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  hourCycle: 'h23',
})

/** Dzień ostatniej godziny blokady w czasie sklepu: po 17:00 dziś, wcześniej wczoraj (wystarczy do porównania). */
function lockPeriod(now: Date) {
  const parts = Object.fromEntries(warsawParts.formatToParts(now).map((part) => [part.type, part.value]))
  const day = `${parts.year}-${parts.month}-${parts.day}`
  return Number(parts.hour) >= LOCK_HOUR ? day : `przed ${day}`
}

/**
 * Konto otwarte przed 17:00 backend odkłada po 17:00 do tła (wraca po PIN-ie). Bez kliknięć nie idzie żadne
 * żądanie, więc o 17:00 sami pytamy o konto: odpowiedź 401 przenosi na logowanie z listą kont w tle.
 */
export function useWorkdayLock() {
  const queryClient = useQueryClient()

  useEffect(() => {
    let period = lockPeriod(new Date())
    const timer = window.setInterval(() => {
      const current = lockPeriod(new Date())
      if (current === period) return
      period = current
      void queryClient.invalidateQueries({ queryKey: meQueryKey })
    }, CHECK_EVERY_MS)
    return () => window.clearInterval(timer)
  }, [queryClient])
}
