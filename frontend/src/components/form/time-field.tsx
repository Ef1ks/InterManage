import { useId, useState, type ReactNode } from 'react'
import { Popover } from '@base-ui/react/popover'
import { ClockIcon } from 'lucide-react'

import { FieldShell } from '@/components/form/field-shell'
import { describedBy, fieldControlClass, floatedLabelClass, floatingLabelClass } from '@/components/form/field-styles'
import { cn } from '@/lib/utils'

const HOURS = Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, '0'))
const MINUTES = Array.from({ length: 12 }, (_, step) => String(step * 5).padStart(2, '0'))

type TimeFieldProps = {
  label: string
  /** GG:MM albo pusty tekst. */
  value: string
  onChange: (value: string) => void
  error?: string
  hint?: ReactNode
  /** Otwiera wybór od razu, gdy pole pojawia się po kliknięciu. */
  defaultOpen?: boolean
  className?: string
}

/**
 * Pole godziny z pływającą etykietą, spójne z {@link DateField}. Godzina zostawia okienko otwarte, bo minuty są
 * opcjonalne (domyślnie :00), a klik obok od razu trafia w kolejne pole; wybór minut je zamyka.
 */
export function TimeField({ label, value, onChange, error, hint, defaultOpen = false, className }: TimeFieldProps) {
  const id = useId()
  const [open, setOpen] = useState(defaultOpen)
  const [hour = '', minute = ''] = value ? value.split(':') : []

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
          <span className={cn('truncate pt-3.5 tabular-nums', !value && 'invisible')}>{value || '-'}</span>
          <ClockIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Positioner sideOffset={6} align="start" collisionPadding={8} className="isolate z-50">
            <Popover.Popup className="origin-(--transform-origin) rounded-2xl bg-popover p-3 text-popover-foreground shadow-[0_12px_32px_-12px_rgb(0_0_0/0.25)] ring-1 ring-foreground/10 transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.97] data-ending-style:opacity-0 data-starting-style:scale-[0.97] data-starting-style:opacity-0 motion-reduce:transition-none">
              <TimeGrid
                title="Godzina"
                values={HOURS}
                selected={hour}
                onPick={(next) => onChange(`${next}:${minute || '00'}`)}
              />
              <TimeGrid
                title="Minuty"
                values={MINUTES}
                selected={minute}
                disabled={!hour}
                onPick={(next) => {
                  onChange(`${hour}:${next}`)
                  setOpen(false)
                }}
                className="mt-3"
              />
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

function TimeGrid({
  title,
  values,
  selected,
  disabled,
  onPick,
  className,
}: {
  title: string
  values: string[]
  selected: string
  disabled?: boolean
  onPick: (value: string) => void
  className?: string
}) {
  return (
    <div role="group" aria-label={title} className={className}>
      <p className="mb-1.5 px-1 text-xs text-muted-foreground">{title}</p>
      <div className="grid grid-cols-6 gap-1">
        {values.map((value) => (
          <button
            key={value}
            type="button"
            disabled={disabled}
            aria-pressed={value === selected}
            aria-label={`${title} ${value}`}
            onClick={() => onPick(value)}
            className={cn(
              'h-9 w-10 rounded-lg text-sm tabular-nums outline-none select-none transition-[background-color,color,transform] duration-150 ease-snappy focus-visible:ring-3 focus-visible:ring-ring/40 active:scale-[0.94] disabled:pointer-events-none disabled:opacity-40 motion-reduce:active:scale-100',
              value === selected ? 'bg-primary font-medium text-primary-foreground' : 'hover:bg-muted',
            )}
          >
            {value}
          </button>
        ))}
      </div>
    </div>
  )
}
