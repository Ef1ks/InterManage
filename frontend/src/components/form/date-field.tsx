import { useId, useState, type ReactNode } from 'react'
import { Popover } from '@base-ui/react/popover'
import { CalendarDaysIcon } from 'lucide-react'

import { FieldShell } from '@/components/form/field-shell'
import { describedBy, fieldControlClass, floatedLabelClass, floatingLabelClass } from '@/components/form/field-styles'
import { Calendar } from '@/components/ui/calendar'
import { addDays, formatLongDate, todayIso } from '@/lib/dates'
import { cn } from '@/lib/utils'

type DateFieldProps = {
  label: string
  /** RRRR-MM-DD albo pusty tekst. */
  value: string
  onChange: (value: string) => void
  /** Najwcześniejsza dozwolona data (RRRR-MM-DD). */
  min?: string
  /** Najpóźniejsza dozwolona data (RRRR-MM-DD). */
  max?: string
  error?: string
  hint?: ReactNode
  /** Skróty nad kalendarzem, np. Jutro / Pojutrze. Domyślnie dzisiaj, jutro i pojutrze. */
  shortcuts?: { label: string; value: string }[]
  className?: string
}

/** Pole daty z pływającą etykietą, spójne z {@link TextField}; kliknięcie otwiera kalendarz marki. */
export function DateField({ label, value, onChange, min, max, error, hint, shortcuts, className }: DateFieldProps) {
  const id = useId()
  const [open, setOpen] = useState(false)
  const today = todayIso()
  const quickPicks = (
    shortcuts ?? [
      { label: 'Dziś', value: today },
      { label: 'Jutro', value: addDays(today, 1) },
      { label: 'Pojutrze', value: addDays(today, 2) },
    ]
  ).filter((pick) => (!min || pick.value >= min) && (!max || pick.value <= max))

  const pick = (next: string) => {
    onChange(next)
    setOpen(false)
  }

  return (
    <FieldShell id={id} error={error} hint={hint} className={className}>
      <Popover.Root open={open} onOpenChange={setOpen}>
        <Popover.Trigger
          id={id}
          aria-labelledby={`${id}-label`}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, error, hint)}
          className={cn(
            fieldControlClass,
            'flex items-center justify-between gap-2 py-0 pr-3 text-left data-popup-open:border-ring data-popup-open:ring-3 data-popup-open:ring-ring/30',
          )}
        >
          <span className={cn('truncate pt-3.5', !value && 'invisible')}>{value ? formatLongDate(value) : '-'}</span>
          <CalendarDaysIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Positioner sideOffset={6} align="start" className="isolate z-50">
            <Popover.Popup className="origin-(--transform-origin) rounded-2xl bg-popover p-4 text-popover-foreground shadow-[0_12px_32px_-12px_rgb(0_0_0/0.25)] ring-1 ring-foreground/10 transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.97] data-ending-style:opacity-0 data-starting-style:scale-[0.97] data-starting-style:opacity-0 motion-reduce:transition-none">
              {quickPicks.length > 0 && (
                <div className="mb-3 flex gap-1.5">
                  {quickPicks.map((quick) => (
                    <button
                      key={quick.value}
                      type="button"
                      onClick={() => pick(quick.value)}
                      className={cn(
                        'rounded-full border px-3 py-1 text-xs font-medium transition-colors duration-150 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50',
                        quick.value === value && 'border-brand/40 bg-brand/10 text-brand hover:bg-brand/15',
                      )}
                    >
                      {quick.label}
                    </button>
                  ))}
                </div>
              )}
              <Calendar value={value} onChange={pick} min={min} max={max} />
            </Popover.Popup>
          </Popover.Positioner>
        </Popover.Portal>
      </Popover.Root>
      <label
        id={`${id}-label`}
        htmlFor={id}
        className={cn(
          floatingLabelClass,
          (open || value) && floatedLabelClass,
          open && 'text-foreground',
          error && 'text-destructive',
        )}
      >
        {label}
      </label>
    </FieldShell>
  )
}
