import * as SecureStore from 'expo-secure-store'

/**
 * SecureStore (Keystore Androida) potrafi nie odpowiadać, więc nigdy na nim nie czekamy dłużej niż kilka sekund:
 * wartości działają z pamięci, a zapis to tylko zapamiętanie na kolejne uruchomienie.
 */
const STORAGE_TIMEOUT_MS = 3_000

function withTimeout<T>(promise: Promise<T>, fallback: T): Promise<T> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(fallback), STORAGE_TIMEOUT_MS)
    promise.then(
      (value) => {
        clearTimeout(timer)
        resolve(value)
      },
      () => {
        clearTimeout(timer)
        resolve(fallback)
      },
    )
  })
}

export const readStored = (key: string) => withTimeout(SecureStore.getItemAsync(key), null)

export function writeStored(key: string, value: string | null) {
  const write = value != null ? SecureStore.setItemAsync(key, value) : SecureStore.deleteItemAsync(key)
  return withTimeout(write, undefined)
}
