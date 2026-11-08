import { storeLabel } from '@/features/stores/current-store'
import type { StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Wybór sklepu firmy w panelu pracowników: sklepów jest kilka, więc numery stoją obok siebie i wybiera się je jednym
 * kliknięciem (pełna nazwa w podpowiedzi). Ta sama wysokość co wyszukiwarka i przyciski paska.
 */
export function StoreSwitch({
  stores,
  value,
  onChange,
  label,
  all,
}: {
  stores: StoreSummary[]
  value: string
  onChange: (value: string) => void
  label: string
  /** Wartość opcji „Wszystkie”; bez niej wybiera się zawsze jeden sklep. */
  all?: string
}) {
  const options = [
    ...stores.map((store) => ({ id: store.id, text: store.number, title: storeLabel(store) })),
    ...(all ? [{ id: all, text: 'Wszystkie', title: 'Wszystkie sklepy firmy' }] : []),
  ]
  return (
    <div role="group" aria-label={label} className="flex h-10 max-w-full items-center gap-1">
      <span className="hidden shrink-0 pr-1 text-xs text-muted-foreground sm:inline">{label}</span>
      <div className="flex h-10 min-w-0 gap-0.5 overflow-x-auto rounded-xl bg-muted p-1">
        {options.map((option) => (
          <button
            key={option.id}
            type="button"
            title={option.title}
            aria-pressed={value === option.id}
            onClick={() => onChange(option.id)}
            className={cn(
              'shrink-0 rounded-lg px-3 text-sm font-medium text-muted-foreground tabular-nums outline-none transition-[background-color,color,box-shadow] duration-150 hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
              value === option.id &&
                'bg-background text-foreground shadow-[0_1px_2px_rgb(0_0_0/0.08)] hover:text-foreground dark:bg-input/60',
            )}
          >
            {option.text}
          </button>
        ))}
      </div>
    </div>
  )
}
