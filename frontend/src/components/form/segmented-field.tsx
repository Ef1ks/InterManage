import { Radio } from '@base-ui/react/radio'
import { RadioGroup } from '@base-ui/react/radio-group'

import { cn } from '@/lib/utils'

export type SegmentOption<T extends string> = { value: T; label: string }

type SegmentedFieldProps<T extends string> = {
  /** Etykieta dla czytników ekranu (wizualnie opisuje ją otaczający wiersz). */
  label: string
  options: SegmentOption<T>[]
  value: T
  onChange: (value: T) => void
  className?: string
}

/** Kilka wzajemnie wykluczających się opcji obok siebie (np. Brak / Podgląd / Edycja). Strzałki zmieniają wybór. */
export function SegmentedField<T extends string>({ label, options, value, onChange, className }: SegmentedFieldProps<T>) {
  return (
    <RadioGroup
      value={value}
      onValueChange={(next) => onChange(next as T)}
      aria-label={label}
      className={cn('inline-flex shrink-0 rounded-lg bg-muted p-0.5', className)}
    >
      {options.map((option) => (
        <Radio.Root
          key={option.value}
          value={option.value}
          className="rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-[color,background-color,box-shadow,transform] duration-150 ease-snappy outline-none select-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/40 active:scale-[0.97] data-checked:bg-background data-checked:font-medium data-checked:text-foreground data-checked:shadow-[0_1px_2px_rgb(0_0_0/0.08),0_1px_1px_rgb(0_0_0/0.04)] motion-reduce:active:scale-100"
        >
          {option.label}
        </Radio.Root>
      ))}
    </RadioGroup>
  )
}
