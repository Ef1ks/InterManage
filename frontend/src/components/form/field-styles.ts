import type { ReactNode } from 'react'

/**
 * Wspólne klasy pól z pływającą etykietą: etykieta leży w środku pola, a po kliknięciu lub wpisaniu wartości
 * przesuwa się do góry i zmniejsza. Animujemy tylko transform (bez layoutu), 200 ms ease-out.
 */
export const fieldControlClass =
  'peer h-12 w-full min-w-0 rounded-xl border border-input bg-background px-3 pt-5 pb-1.5 text-base text-foreground transition-[border-color,box-shadow] duration-150 outline-none md:text-sm ' +
  'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30 ' +
  'aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive/20 ' +
  'disabled:cursor-not-allowed disabled:bg-muted/60 disabled:opacity-70 dark:bg-input/30'

export const floatingLabelClass =
  'pointer-events-none absolute top-3.5 left-3 origin-left truncate text-sm text-muted-foreground transition-[transform,color] duration-200 ease-snappy select-none motion-reduce:transition-none ' +
  'max-w-[calc(100%-1.5rem)]'

/** Pozycja etykiety „u góry” - nakładana przez warianty peer albo atrybut data-floated. */
export const floatedLabelClass = '-translate-y-2.5 scale-[0.8]'

export function describedBy(id: string, error?: string, hint?: ReactNode) {
  return error || hint ? `${id}-message` : undefined
}
