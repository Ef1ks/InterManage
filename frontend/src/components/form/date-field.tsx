import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { Popover } from '@base-ui/react/popover'
import { CalendarDaysIcon } from 'lucide-react'

import { FieldShell } from '@/components/form/field-shell'
import { describedBy, fieldControlClass, floatedLabelClass, floatingLabelClass } from '@/components/form/field-styles'
import { Calendar } from '@/components/ui/calendar'
import { addDays, formatTypedDate, parseIsoDate, parseTypedDate, todayIso } from '@/lib/dates'
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

const weekday = new Intl.DateTimeFormat('pl-PL', { weekday: 'short' })

/**
 * Pole daty z pływającą etykietą, spójne z {@link TextField}: datę wpisuje się w całym polu (25.10.2026, 2510, 25),
 * a ikona kalendarza otwiera kalendarz marki. Wpisana data zapisuje się po Enterze albo wyjściu z pola.
 */
export function DateField({ label, value, onChange, min, max, error, hint, shortcuts, className }: DateFieldProps) {
  const id = useId()
  const anchorRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const [open, setOpen] = useState(false)
  const [focused, setFocused] = useState(false)
  const [draft, setText] = useState(() => formatTypedDate(value))
  const [typedError, setTypedError] = useState<string | null>(null)
  // Bez fokusu pole pokazuje wartość z formularza; zły wpis zostaje w polu, póki ma opis błędu.
  const text = focused || typedError ? draft : formatTypedDate(value)

  const today = todayIso()
  const quickPicks = (
    shortcuts ?? [
      { label: 'Dziś', value: today },
      { label: 'Jutro', value: addDays(today, 1) },
      { label: 'Pojutrze', value: addDays(today, 2) },
    ]
  ).filter((pick) => (!min || pick.value >= min) && (!max || pick.value <= max))

  const pick = (next: string) => {
    setTypedError(null)
    setText(formatTypedDate(next))
    onChange(next)
    setOpen(false)
  }

  /** Sprawdza wpisany tekst; poprawna data trafia do formularza, zła zostaje w polu z opisem. */
  const commit = () => {
    const parsed = parseTypedDate(text)
    if (parsed === null) {
      setTypedError('Wpisz datę, np. 25.10.2026')
      return
    }
    if (parsed && ((min && parsed < min) || (max && parsed > max))) {
      setTypedError(
        min && parsed < min ? `Najwcześniej ${formatTypedDate(min)}` : `Najpóźniej ${formatTypedDate(max ?? '')}`,
      )
      return
    }
    setTypedError(null)
    setText(formatTypedDate(parsed))
    if (parsed !== value) onChange(parsed)
  }

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') {
      event.preventDefault()
      commit()
    } else if (event.key === 'Escape' && !open) {
      setTypedError(null)
      setText(formatTypedDate(value))
    } else if (event.key === 'ArrowDown' && event.altKey) {
      event.preventDefault()
      setOpen(true)
    }
  }

  const shownError = typedError ?? error
  const floated = focused || open || text !== ''

  return (
    <FieldShell id={id} error={shownError ?? undefined} hint={hint} className={className}>
      <Popover.Root open={open} onOpenChange={setOpen}>
        <div ref={anchorRef} className="relative">
          <input
            ref={inputRef}
            id={id}
            type="text"
            inputMode="numeric"
            autoComplete="off"
            value={text}
            placeholder={focused ? 'DD.MM.RRRR' : undefined}
            aria-invalid={shownError ? true : undefined}
            aria-describedby={describedBy(id, shownError ?? undefined, hint)}
            onChange={(event) => {
              setText(event.target.value)
              if (typedError) setTypedError(null)
            }}
            onFocus={() => {
              if (!typedError) setText(formatTypedDate(value))
              setFocused(true)
            }}
            onBlur={() => {
              setFocused(false)
              commit()
            }}
            onKeyDown={onKeyDown}
            className={cn(
              fieldControlClass,
              'pr-20 tabular-nums placeholder:text-muted-foreground/50',
              open && 'border-ring ring-3 ring-ring/30',
            )}
          />
          {value && !focused && text === formatTypedDate(value) && (
            <span
              aria-hidden
              className="pointer-events-none absolute top-1/2 right-11 pt-2.5 text-xs text-muted-foreground -translate-y-1/2"
            >
              {weekday.format(parseIsoDate(value))}
            </span>
          )}
          <Popover.Trigger
            aria-label="Otwórz kalendarz"
            title="Otwórz kalendarz"
            className="absolute top-1/2 right-1.5 flex size-9 -translate-y-1/2 items-center justify-center rounded-lg text-muted-foreground transition-colors duration-150 outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 data-popup-open:bg-muted data-popup-open:text-foreground"
          >
            <CalendarDaysIcon aria-hidden className="size-4" />
          </Popover.Trigger>
        </div>
        <Popover.Portal>
          <Popover.Positioner anchor={anchorRef} sideOffset={6} align="start" className="isolate z-50">
            <Popover.Popup
              finalFocus={inputRef}
              className="origin-(--transform-origin) rounded-2xl glass-float p-4 text-popover-foreground transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.97] data-ending-style:opacity-0 data-starting-style:scale-[0.97] data-starting-style:opacity-0 motion-reduce:transition-none"
            >
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
        htmlFor={id}
        className={cn(
          floatingLabelClass,
          floated && floatedLabelClass,
          (focused || open) && 'text-foreground',
          shownError && 'text-destructive',
        )}
      >
        {label}
      </label>
    </FieldShell>
  )
}
