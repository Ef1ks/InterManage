import type { SalesComparisonLevel } from '@/lib/types'

export type NomenclatureLevel = Exclude<SalesComparisonLevel, 'PRODUCT'>

/**
 * Kolor poziomu nomenklatury: pasek przy nazwie i tło wiersza (mocniejsze, gdy grupa jest otwarta). Tła są pełne,
 * bo przyklejona kolumna nazwy przykrywa przewijane w bok liczby. Czerwień zostaje dla spadków.
 */
export const LEVEL_STYLES: Record<NomenclatureLevel, { label: string; row: string; open: string; bar: string; dot: string }> = {
  REGION: {
    label: 'Rejon',
    row: 'bg-sky-50 hover:bg-sky-100 dark:bg-sky-950 dark:hover:bg-sky-900',
    open: 'bg-sky-100 hover:bg-sky-100 dark:bg-sky-900 dark:hover:bg-sky-900',
    bar: 'before:bg-sky-500',
    dot: 'bg-sky-500',
  },
  FAMILY: {
    label: 'Rodzina',
    row: 'bg-violet-50 hover:bg-violet-100 dark:bg-violet-950 dark:hover:bg-violet-900',
    open: 'bg-violet-100 hover:bg-violet-100 dark:bg-violet-900 dark:hover:bg-violet-900',
    bar: 'before:bg-violet-500',
    dot: 'bg-violet-500',
  },
  SUBFAMILY: {
    label: 'Podrodzina',
    row: 'bg-teal-50 hover:bg-teal-100 dark:bg-teal-950 dark:hover:bg-teal-900',
    open: 'bg-teal-100 hover:bg-teal-100 dark:bg-teal-900 dark:hover:bg-teal-900',
    bar: 'before:bg-teal-500',
    dot: 'bg-teal-500',
  },
}

/** Pasek koloru poziomu przy lewej krawędzi komórki nazwy. */
export const barClass = 'before:absolute before:inset-y-0 before:left-0 before:w-1'

/** Kod nomenklatury w parach cyfr: "010203" → "01 02 03". */
export const displayCode = (code: string) => code.match(/\d{2}/g)?.join(' ') ?? code
