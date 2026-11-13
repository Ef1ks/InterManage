import { cn } from '@/lib/utils'

/**
 * Przy korekcie z odczytanym XML: czy zmienia ilości (do wprowadzenia w sklepie), czy tylko kwoty. Przed odczytem
 * i przy zwykłej fakturze nic nie pokazuje.
 */
export function QuantityChangeBadge({ value, className }: { value: boolean | null; className?: string }) {
  if (value == null) return null
  return (
    <span
      title={
        value ? 'Korekta zmienia ilości (zwrot, brak, zamiana artykułu)' : 'Korekta zmienia tylko kwoty (cena, rabat)'
      }
      className={cn(
        'shrink-0 rounded-md px-1.5 py-0.5 text-[0.7rem] font-medium',
        value
          ? 'bg-sky-200 text-sky-950 dark:bg-sky-900 dark:text-sky-50'
          : 'bg-stone-300 text-stone-950 dark:bg-stone-700 dark:text-stone-50',
        className,
      )}
    >
      {value ? 'zmiana ilości' : 'tylko kwota'}
    </span>
  )
}
