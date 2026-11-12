import { router } from 'expo-router'
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'

import { api, ApiError, type Auth } from './api'
import type { Me, MobileLogin } from './types'

/** Tryb kart zamyka się sam po tej przerwie, żeby kiosk nie został otwarty po odejściu kierownika. */
const IDLE_MS = 3 * 60 * 1000

type ManagerState = {
  /** Zalogowany kierownik; token jest tylko w pamięci, restart aplikacji go zapomina. */
  session: { token: string; me: Me } | null
  auth: Auth
  login: (username: string, password: string, storeId: string) => Promise<void>
  logout: () => void
  /** Każdy dotyk i każda karta w trybie kart odsuwa automatyczne wylogowanie. */
  touch: () => void
}

const ManagerContext = createContext<ManagerState | null>(null)

/**
 * Karty przypisuje to samo uprawnienie co w przeglądarce (Czas pracy → Ustawienia): Kadry z edycją i wgląd w czas
 * pracy (`StaffTimeController.CONFIGURE`), w sklepie czytnika.
 */
export function canManageCards(me: Me, storeId: string) {
  if (!me.stores.some((store) => store.id === storeId)) return false
  return me.owner || (me.permissions.STAFF === 'EDIT' && me.permissions.STAFF_TIME != null)
}

export function ManagerProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<ManagerState['session']>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const logout = useCallback(() => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
    setSession(null)
    if (router.canDismiss()) router.dismissAll()
    router.replace('/')
  }, [])

  const touch = useCallback(() => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(logout, IDLE_MS)
  }, [logout])

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )

  const login = useCallback(
    async (username: string, password: string, storeId: string) => {
      const result = await api<MobileLogin>('/auth/mobile/login', { method: 'POST', json: { username, password } })
      if (result.me.passwordChangeRequired) {
        throw new ApiError('Najpierw ustaw hasło w przeglądarce.', 403)
      }
      if (!canManageCards(result.me, storeId)) {
        throw new ApiError('To konto nie może przypisywać kart w tym sklepie (potrzebne Kadry z edycją).', 403)
      }
      setSession({ token: result.token, me: result.me })
      touch()
    },
    [touch],
  )

  const value = useMemo<ManagerState>(
    () => ({
      session,
      auth: session ? { kind: 'manager', token: session.token } : null,
      login,
      logout,
      touch,
    }),
    [session, login, logout, touch],
  )
  return <ManagerContext.Provider value={value}>{children}</ManagerContext.Provider>
}

export function useManager() {
  const value = useContext(ManagerContext)
  if (!value) throw new Error('useManager poza ManagerProvider')
  return value
}
