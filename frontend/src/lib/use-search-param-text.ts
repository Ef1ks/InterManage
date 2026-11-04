import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'

import { useDebouncedValue } from '@/lib/use-debounced-value'

/**
 * Pole tekstowe zapamiętane w adresie (np. {@code ?szukaj=}): pole ma własny stan, a do adresu trafia po chwili bez
 * pisania. Pole sterowane prosto z adresu gubiło znaki przy skanowaniu kodu, bo adres nie nadążał za czytnikiem
 * (5905689314873 dawało 5989314873). Zmiana adresu z zewnątrz (wyczyszczenie filtrów, wstecz) trafia do pola.
 */
export function useSearchParamText(key: string, delayMs = 250) {
  const [params, setParams] = useSearchParams()
  const fromUrl = params.get(key) ?? ''
  const [text, setText] = useState(fromUrl)
  const [seen, setSeen] = useState(fromUrl)
  const debounced = useDebouncedValue(text.trim(), delayMs)
  // Własny zapis wraca z adresem jako ta sama wartość; inna wartość przyszła z zewnątrz.
  if (fromUrl !== seen) {
    setSeen(fromUrl)
    if (fromUrl !== debounced) setText(fromUrl)
  }
  const settled = debounced === text.trim()
  useEffect(() => {
    if (!settled || fromUrl === debounced) return
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (debounced) next.set(key, debounced)
        else next.delete(key)
        return next
      },
      { replace: true },
    )
  }, [settled, fromUrl, debounced, key, setParams])
  return { text, setText, debounced }
}
