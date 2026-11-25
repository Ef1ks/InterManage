/*
 * Nowe artykuły dodane do bazy po wczytaniu pliku na komputerze sklepu (albo cofnięte): dowiaduje się o tym strumień na
 * żywo w układzie aplikacji, a tabela wprowadzania danych musi wtedy sprawdzić swoje EAN-y od nowa.
 */

/** Jedno powiadomienie o wczytywaniu artykułów na sklep: od czekania do wyniku. */
export const productFilesToast = (storeId: string) => `product-files:${storeId}`

type Listener = (storeId: string, eans: string[]) => void

const listeners = new Set<Listener>()

export function emitDraftsChanged(storeId: string, eans: string[]) {
  listeners.forEach((listener) => listener(storeId, eans))
}

export function onDraftsChanged(listener: Listener) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
