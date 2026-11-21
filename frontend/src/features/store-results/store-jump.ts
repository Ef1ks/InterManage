import { useCallback } from 'react'
import { useNavigate } from 'react-router'

import { inStore, STORE_PARAM, useCurrentStore } from '@/features/stores/current-store'

/**
 * Wpis historii, z którego wychodzimy do innego sklepu, zapamiętuje swój sklep w adresie (`?w_sklepie=`). „Wstecz” w
 * przeglądarce wraca wtedy na tę stronę i `useStoreFromAddress` przełącza z powrotem na ten sklep.
 */
export function rememberStoreForBack(storeId: string) {
  const url = new URL(window.location.href)
  url.searchParams.set(STORE_PARAM, storeId)
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`)
}

/** Adres strony w sklepie `storeId`: w bieżącym sklepie zwykła ścieżka, w innym z przełączeniem sklepu. */
export function useStoreHref() {
  const current = useCurrentStore()
  return useCallback(
    (path: string, storeId: string) => (current && storeId !== current.id ? inStore(path, storeId) : path),
    [current],
  )
}

/** Otwiera stronę w sklepie `storeId`; z innego sklepu „Wstecz” wraca do bieżącego sklepu i tej strony. */
export function useOpenInStore() {
  const navigate = useNavigate()
  const current = useCurrentStore()
  const href = useStoreHref()
  const prepare = useCallback(
    (storeId: string) => {
      if (current && storeId !== current.id) rememberStoreForBack(current.id)
    },
    [current],
  )
  const open = useCallback(
    (path: string, storeId: string) => {
      prepare(storeId)
      navigate(href(path, storeId))
    },
    [prepare, navigate, href],
  )
  return { open, href, prepare }
}
