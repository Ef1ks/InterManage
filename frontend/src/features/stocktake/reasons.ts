import type { StocktakeReason } from '@/lib/types'

/** Kolejność jak na liście: od twardych dowodów do samej kwoty. */
export const REASONS: Record<StocktakeReason, { label: string; hint: string; tone: string }> = {
  SOLD_WHEN_ZERO: {
    label: 'Sprzedane, a policzono 0',
    hint: 'Artykuł sprzedawał się po inwenturze bez dostawy, więc towar był na sklepie.',
    tone: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  },
  SOLD_MORE_THAN_COUNTED: {
    label: 'Sprzedano więcej, niż policzono',
    hint: 'Po inwenturze sprzedało się więcej, niż policzono, a nie było dostawy.',
    tone: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  },
  SWAP_STRONG: {
    label: 'Mocna para plus/minus',
    hint: 'Podobny artykuł ma taką samą różnicę w drugą stronę: pewnie policzono jeden zamiast drugiego.',
    tone: 'bg-violet-300 text-violet-950 dark:bg-violet-800/70 dark:text-violet-50',
  },
  SWAP_POSSIBLE: {
    label: 'Możliwa para plus/minus',
    hint: 'Podobny artykuł ma zbliżoną różnicę w drugą stronę. Warto sprawdzić, ale często to przypadek.',
    tone: 'bg-violet-200 text-violet-950 dark:bg-violet-900/60 dark:text-violet-100',
  },
  DIGITS_SWAPPED: {
    label: 'Zamienione cyfry',
    hint: 'Policzona ilość to stan z systemu z zamienionymi cyframi, np. 27 i 72.',
    tone: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
  WRONG_SCALE: {
    label: 'Pomyłka ×10',
    hint: 'Policzona ilość jest 10, 100 albo 1000 razy inna niż stan z systemu.',
    tone: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
  NOT_COUNTED: {
    label: 'Policzono 0',
    hint: 'System ma stan, a policzono zero: towar pominięty albo rzeczywiście go nie ma.',
    tone: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  },
  LARGE_DIFFERENCE: {
    label: 'Duża kwota',
    hint: 'Różnica od 100 zł bez innego wyjaśnienia.',
    tone: 'bg-muted text-foreground',
  },
}

export const REASON_ORDER = Object.keys(REASONS) as StocktakeReason[]
