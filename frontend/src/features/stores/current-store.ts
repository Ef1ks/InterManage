import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router'

import { useCurrentUser } from '@/features/auth/api'
import type { StoreSummary } from '@/lib/types'
import { useAppDispatch, useAppSelector } from '@/store'
import { persistCurrentStoreId, setCurrentStore } from '@/store/uiSlice'

/**
 * Sklep, na którym teraz pracujemy. Gdy zapamiętany sklep przestał być dostępny (odebrany dostęp, wyłączony),
 * wracamy do pierwszego z listy. `null` = użytkownik nie ma dostępu do żadnego sklepu.
 */
export function useCurrentStore(): StoreSummary | null {
  const me = useCurrentUser()
  const currentStoreId = useAppSelector((state) => state.ui.currentStoreId)
  return me.stores.find((store) => store.id === currentStoreId) ?? me.stores[0] ?? null
}

export function useSelectStore() {
  const dispatch = useAppDispatch()
  return (storeId: string) => {
    persistCurrentStoreId(storeId)
    dispatch(setCurrentStore(storeId))
  }
}

/** Parametr adresu, który wskazuje sklep (odnośnik zadania otwarty z telefonu albo z innego sklepu). */
export const STORE_PARAM = 'w_sklepie'

/** Dokleja do ścieżki w aplikacji sklep, w którym ma się otworzyć. */
export function inStore(path: string, storeId: string) {
  return `${path}${path.includes('?') ? '&' : '?'}${STORE_PARAM}=${encodeURIComponent(storeId)}`
}

/**
 * Wejście z adresem z `?w_sklepie=`: przełącza na ten sklep (gdy jest dostępny) i zdejmuje parametr, żeby strona
 * widziała swój zwykły adres. Wołane raz w `AppLayout`.
 */
export function useStoreFromAddress() {
  const me = useCurrentUser()
  const select = useSelectStore()
  const location = useLocation()
  const navigate = useNavigate()
  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const storeId = params.get(STORE_PARAM)
    if (!storeId) return
    if (me.stores.some((store) => store.id === storeId)) select(storeId)
    params.delete(STORE_PARAM)
    const search = params.toString()
    navigate(
      { pathname: location.pathname, search: search ? `?${search}` : '', hash: location.hash },
      { replace: true },
    )
  }, [location, me.stores, select, navigate])
}

/** "10023 · Zielona Góra"; sklep z domyślną nazwą "Sklep 10023" nie powtarza numeru. */
export function storeLabel(store: Pick<StoreSummary, 'number' | 'name'>) {
  return store.name.trim() === `Sklep ${store.number}` ? store.name.trim() : `${store.number} · ${store.name}`
}
