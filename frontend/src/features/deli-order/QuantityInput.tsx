import { cn } from '@/lib/utils'
import type { OrderUnit } from '@/lib/types'

export const UNIT_LABELS: Record<OrderUnit, string> = { KG: 'kg', SZT: 'szt.' }

/** Ilość wpisana przez użytkownika: przecinek albo kropka, kg do 3 miejsc po przecinku, sztuki tylko całe. */
export function parseQuantity(raw: string, unit: OrderUnit): { value?: number; error?: string } {
  const text = raw.trim().replace(',', '.')
  if (!/^\d+(\.\d+)?$/.test(text)) return { error: 'Wpisz liczbę' }
  const decimals = text.split('.')[1]?.length ?? 0
  if (unit === 'SZT' && decimals > 0) return { error: 'Sztuki tylko w całości' }
  if (unit === 'KG' && decimals > 3) return { error: 'Najwyżej 3 miejsca po przecinku' }
  return { value: Number(text) }
}

const quantityFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 })

export function formatQuantity(value: number, unit: OrderUnit) {
  return `${quantityFormat.format(value)} ${UNIT_LABELS[unit]}`
}

type Props = {
  unit: OrderUnit
  value: string
  onChange: (value: string) => void
  /** Nazwa produktu do etykiety dostępności. */
  label: string
  invalid?: boolean
  disabled?: boolean
  className?: string
}

/** Pole ilości w wierszu produktu, z jednostką na stałe w polu (kg albo szt.). */
export function QuantityInput({ unit, value, onChange, label, invalid, disabled, className }: Props) {
  return (
    <label
      className={cn(
        'flex h-10 w-32 shrink-0 items-center rounded-xl border border-input bg-background pr-3 transition-[border-color,box-shadow] duration-150',
        'focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/30',
        value.trim() !== '' && !invalid && 'border-foreground/25 bg-muted/40',
        invalid && 'border-destructive focus-within:border-destructive focus-within:ring-destructive/20',
        disabled && 'opacity-60',
        className,
      )}
    >
      <input
        type="text"
        inputMode={unit === 'KG' ? 'decimal' : 'numeric'}
        autoComplete="off"
        aria-label={`Ilość: ${label} (${UNIT_LABELS[unit]})`}
        aria-invalid={invalid || undefined}
        placeholder="0"
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        className="h-full w-full min-w-0 bg-transparent pl-3 text-right text-sm font-medium tabular-nums outline-none placeholder:text-muted-foreground/50"
      />
      <span aria-hidden className="ml-1.5 w-7 text-xs text-muted-foreground">
        {UNIT_LABELS[unit]}
      </span>
    </label>
  )
}

/** Przełącznik kg / szt. przy produkcie (układanie formularza, dodatkowe pozycje zamówienia). */
export function UnitToggle({
  value,
  onChange,
  disabled,
  label,
}: {
  value: OrderUnit
  onChange: (value: OrderUnit) => void
  disabled?: boolean
  label: string
}) {
  return (
    <div role="radiogroup" aria-label={`Jednostka zamawiania: ${label}`} className="inline-flex rounded-lg bg-muted p-0.5">
      {(['KG', 'SZT'] as const).map((unit) => (
        <button
          key={unit}
          type="button"
          role="radio"
          aria-checked={value === unit}
          disabled={disabled}
          onClick={() => value !== unit && onChange(unit)}
          className={cn(
            'rounded-md px-2.5 py-1 text-xs font-medium text-muted-foreground transition-[background-color,color,box-shadow] duration-150 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-60',
            value === unit && 'bg-background text-foreground shadow-[0_1px_2px_rgb(0_0_0/0.08)]',
          )}
        >
          {UNIT_LABELS[unit]}
        </button>
      ))}
    </div>
  )
}
