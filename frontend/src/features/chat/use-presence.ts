import { useEffect } from 'react'

import { useMe } from '@/features/auth/api'
import { api } from '@/lib/api'

const EVERY_MS = 60_000
/** Ruch w którejkolwiek karcie w tym czasie = ktoś korzysta z aplikacji (zapis z blokady po bezczynności). */
const ACTIVE_WITHIN_MS = 2 * 60_000

function lastActivity(): number {
  try {
    const value = Number(window.localStorage.getItem('intermanage.lastActivity'))
    return Number.isFinite(value) ? value : 0
  } catch {
    return Date.now()
  }
}

/**
 * Obecność do czatu („online”, „ostatnio 5 min temu”): co minutę, gdy karta jest widoczna i ktoś niedawno ruszał
 * myszą albo klawiaturą. Samo odświeżanie danych w tle obecności nie daje.
 */
export function usePresenceHeartbeat() {
  const { data: me } = useMe()
  const userId = me?.id
  useEffect(() => {
    if (!userId) return
    const beat = () => {
      if (document.visibilityState !== 'visible') return
      if (Date.now() - lastActivity() > ACTIVE_WITHIN_MS) return
      void api('/chat/presence', { method: 'POST' }).catch(() => undefined)
    }
    beat()
    const timer = window.setInterval(beat, EVERY_MS)
    document.addEventListener('visibilitychange', beat)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', beat)
    }
  }, [userId])
}
