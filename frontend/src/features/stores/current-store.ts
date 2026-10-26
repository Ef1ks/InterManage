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

/** "10023 · Zielona Góra"; sklep z domyślną nazwą "Sklep 10023" nie powtarza numeru. */
export function storeLabel(store: Pick<StoreSummary, 'number' | 'name'>) {
  return store.name.trim() === `Sklep ${store.number}` ? store.name.trim() : `${store.number} · ${store.name}`
}
