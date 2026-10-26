import { useEffect, useState } from 'react'

/** Wartość, która nadąża za {@code value} dopiero po {@code delayMs} bez zmian (np. wyszukiwanie w trakcie pisania). */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timeout = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timeout)
  }, [value, delayMs])
  return debounced
}
