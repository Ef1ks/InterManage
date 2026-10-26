import { useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { Combobox } from '@base-ui/react/combobox'
import { CheckIcon, XIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export type ComboboxOption = {
  value: string
  /** Tekst pozycji; po nim działa wyszukiwanie, więc dołącz do niego wszystko, po czym szukamy (np. numer i nazwę). */
  label: string
  hint?: string
}

type MultiComboboxFieldProps = {
  label: string
  description?: ReactNode
  options: ComboboxOption[]
  /** Wybrane wartości. */
  value: string[]
  onChange: (value: string[]) => void
  placeholder?: string
  /** Gdy wyszukiwanie nic nie znalazło. */
  emptyText?: string
  error?: string
  className?: string
}

/**
 * Wybór wielu pozycji z długiej listy z wyszukiwarką (np. sklepy pracownika). Wybrane pozycje są etykietami
 * w polu, lista podpowiedzi filtruje się po wpisanym tekście. Z react-hook-form używaj przez `Controller`.
 */
export function MultiComboboxField({
  label,
  description,
  options,
  value,
  onChange,
  placeholder = 'Szukaj…',
  emptyText = 'Nic nie znaleziono',
  error,
  className,
}: MultiComboboxFieldProps) {
  const id = useId()
  const anchorRef = useRef<HTMLDivElement | null>(null)
  const selected = useMemo(() => options.filter((option) => value.includes(option.value)), [options, value])
  // Lista zostaje otwarta po każdym zaznaczeniu, więc zamyka ją też przycisk „Gotowe” na dole.
  const [open, setOpen] = useState(false)

  return (
    <div className={cn('grid gap-2', className)}>
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {description && <p className="-mt-1 text-xs text-muted-foreground">{description}</p>}

      <Combobox.Root
        multiple
        open={open}
        onOpenChange={setOpen}
        items={options}
        value={selected}
        onValueChange={(next) => onChange(next.map((option) => option.value))}
        itemToStringLabel={(option) => option.label}
        isItemEqualToValue={(a, b) => a.value === b.value}
      >
        <Combobox.Chips
          ref={anchorRef}
          className={cn(
            'flex min-h-12 cursor-text flex-wrap items-center gap-1.5 rounded-xl border border-input bg-background px-2 py-2 text-sm transition-[border-color,box-shadow] duration-150',
            'focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/30',
            error && 'border-destructive focus-within:border-destructive focus-within:ring-destructive/20',
          )}
        >
          <Combobox.Value>
            {(chosen: ComboboxOption[]) =>
              chosen.map((option) => (
                <Combobox.Chip
                  key={option.value}
                  aria-label={option.label}
                  className="flex h-7 max-w-full items-center gap-1 rounded-lg bg-muted pr-1 pl-2.5 text-xs font-medium tabular-nums data-highlighted:ring-2 data-highlighted:ring-ring/40"
                >
                  <span className="truncate">{option.label}</span>
                  <Combobox.ChipRemove
                    aria-label={`Usuń ${option.label}`}
                    className="flex size-5 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-foreground/10 hover:text-foreground"
                  >
                    <XIcon className="size-3.5" />
                  </Combobox.ChipRemove>
                </Combobox.Chip>
              ))
            }
          </Combobox.Value>
          <Combobox.Input
            id={id}
            placeholder={selected.length === 0 ? placeholder : undefined}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${id}-error` : undefined}
            className="h-7 min-w-24 flex-1 bg-transparent px-1.5 outline-none placeholder:text-muted-foreground"
          />
        </Combobox.Chips>

        <Combobox.Portal>
          <Combobox.Positioner anchor={anchorRef} sideOffset={6} align="start" className="isolate z-50">
            <Combobox.Popup className="max-h-(--available-height) w-(--anchor-width) max-w-(--available-width) origin-(--transform-origin) overflow-hidden rounded-xl bg-popover text-popover-foreground shadow-[0_8px_24px_-8px_rgb(0_0_0/0.18)] ring-1 ring-foreground/10 transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.98] data-ending-style:opacity-0 data-starting-style:scale-[0.98] data-starting-style:opacity-0 motion-reduce:transition-none">
              <Combobox.Empty className="px-3 py-3 text-sm text-muted-foreground empty:hidden">{emptyText}</Combobox.Empty>
              <Combobox.List className="max-h-72 scroll-py-1 overflow-y-auto overscroll-contain p-1 data-empty:p-0">
                {(option: ComboboxOption) => (
                  <Combobox.Item
                    key={option.value}
                    value={option}
                    className="relative grid cursor-pointer gap-0.5 rounded-lg py-2 pr-9 pl-2.5 text-sm outline-none select-none data-highlighted:bg-muted"
                  >
                    <span className="truncate tabular-nums">{option.label}</span>
                    {option.hint && <span className="text-xs text-muted-foreground">{option.hint}</span>}
                    <Combobox.ItemIndicator className="absolute top-1/2 right-2.5 -translate-y-1/2">
                      <CheckIcon className="size-4" />
                    </Combobox.ItemIndicator>
                  </Combobox.Item>
                )}
              </Combobox.List>
              <div className="flex items-center justify-between gap-2 border-t px-3 py-2">
                <span className="text-xs text-muted-foreground tabular-nums">Wybrano: {selected.length}</span>
                <Button type="button" size="sm" onClick={() => setOpen(false)}>
                  Gotowe
                </Button>
              </div>
            </Combobox.Popup>
          </Combobox.Positioner>
        </Combobox.Portal>
      </Combobox.Root>

      {error && (
        <p id={`${id}-error`} className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
