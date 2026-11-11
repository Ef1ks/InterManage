import { useQueryClient } from '@tanstack/react-query'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import { api, ApiError, readStored, restoreSession, setToken, setUnauthorizedHandler, writeStored } from './api'
import type { Me, MobileLogin, StoreSummary } from './types'

const STORE_KEY = 'intermanage.store'

type AuthState = {
  /** `undefined` = jeszcze sprawdzamy zapamiętaną sesję. */
  me: Me | null | undefined
  store: StoreSummary | null
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  selectStore: (storeId: string) => void
  /** Karta artykułu jak w API (`Access.ARTICLE_STATS`): Magazyn, raporty sprzedaży albo wprowadzanie danych. */
  canSeeArticles: boolean
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [me, setMe] = useState<Me | null | undefined>(undefined)
  const [storeId, setStoreId] = useState<string | null>(null)

  const signOut = useCallback(async () => {
    setToken(null)
    queryClient.clear()
    setMe(null)
  }, [queryClient])

  useEffect(() => {
    setUnauthorizedHandler(() => {
      queryClient.clear()
      setMe(null)
    })
    let cancelled = false
    void (async () => {
      const [token, savedStore] = await Promise.all([restoreSession(), readStored(STORE_KEY)])
      if (cancelled) return
      setStoreId(savedStore)
      if (!token) return setMe(null)
      try {
        const current = await api<Me>('/auth/me')
        if (!cancelled) setMe(current)
      } catch (error) {
        // Bez internetu nie wylogowujemy: token zostaje, a ekran logowania pozwoli spróbować ponownie.
        if (!cancelled) setMe(null)
        if (error instanceof ApiError && error.status === 401) setToken(null)
      }
    })()
    return () => {
      cancelled = true
      setUnauthorizedHandler(null)
    }
  }, [queryClient])

  const login = useCallback(async (username: string, password: string) => {
    const result = await api<MobileLogin>('/auth/mobile/login', {
      method: 'POST',
      json: { username, password },
    })
    if (result.me.passwordChangeRequired) {
      throw new ApiError('Najpierw ustaw własne hasło w aplikacji w przeglądarce, potem zaloguj się tutaj.', 403)
    }
    setToken(result.token)
    setMe(result.me)
  }, [])

  const logout = useCallback(async () => {
    try {
      await api('/auth/logout', { method: 'POST' })
    } catch {
      // Token jest bezstanowy, wystarczy go zapomnieć.
    }
    await signOut()
  }, [signOut])

  const selectStore = useCallback((id: string) => {
    setStoreId(id)
    writeStored(STORE_KEY, id)
  }, [])

  const value = useMemo<AuthState>(() => {
    const stores = me?.stores ?? []
    // Zapamiętany sklep mógł przestać być dostępny: wtedy pierwszy z listy, jak useCurrentStore w aplikacji.
    const store = stores.find((item) => item.id === storeId) ?? stores[0] ?? null
    const permissions = me?.permissions ?? {}
    return {
      me,
      store,
      login,
      logout,
      selectStore,
      canSeeArticles: !!me && (me.owner || !!permissions.INVENTORY || !!permissions.SALES || !!permissions.DATA_ENTRY),
    }
  }, [me, storeId, login, logout, selectStore])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth poza AuthProvider')
  return context
}
