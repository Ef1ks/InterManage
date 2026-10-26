import { useMemo, useState } from 'react'

export type SortDirection = 'asc' | 'desc'
export type SortState<K extends string> = { key: K; direction: SortDirection }
export type SortValue = string | number | null | undefined
/** Wartość, po której sortuje kolumna; obiekt trzymaj poza komponentem, żeby nie sortować przy każdym renderze. */
export type SortAccessors<T, K extends string> = Record<K, (row: T) => SortValue>

const collator = new Intl.Collator('pl', { numeric: true, sensitivity: 'base' })

function compare(a: SortValue, b: SortValue): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return collator.compare(String(a), String(b))
}

/**
 * Sortowanie tabeli po kliknięciu nagłówka: pierwsze kliknięcie rosnąco, drugie malejąco.
 * Teksty po polsku i "po ludzku" (numeric: "PZ 9" przed "PZ 10"), puste wartości zawsze na końcu.
 */
export function useTableSort<T, K extends string>(
  rows: T[],
  accessors: SortAccessors<T, K>,
  initial: SortState<NoInfer<K>>,
) {
  const [sort, setSort] = useState(initial)

  const sorted = useMemo(() => {
    const accessor = accessors[sort.key]
    const factor = sort.direction === 'asc' ? 1 : -1
    return [...rows].sort((rowA, rowB) => {
      const a = accessor(rowA)
      const b = accessor(rowB)
      const aEmpty = a == null || a === ''
      const bEmpty = b == null || b === ''
      if (aEmpty || bEmpty) return aEmpty === bEmpty ? 0 : aEmpty ? 1 : -1
      return compare(a, b) * factor
    })
  }, [rows, accessors, sort])

  const toggle = (key: K) => setSort((current) => nextSort(current, key))

  return { sorted, sort, toggle }
}

/** Kliknięcie nagłówka: ta sama kolumna odwraca kierunek, inna sortuje rosnąco (także przy sortowaniu na serwerze). */
export function nextSort<K extends string>(current: SortState<K>, key: K): SortState<K> {
  return current.key === key ? { key, direction: current.direction === 'asc' ? 'desc' : 'asc' } : { key, direction: 'asc' }
}
