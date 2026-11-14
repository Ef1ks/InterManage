import { useId, useMemo, useState, type ReactNode } from 'react'
import { Combobox } from '@base-ui/react/combobox'
import { CheckIcon, ChevronDownIcon, PlusIcon } from 'lucide-react'

import { FieldShell } from '@/components/form/field-shell'
import { describedBy, fieldControlClass, floatedLabelClass, floatingLabelClass } from '@/components/form/field-styles'
import { matchesSearch } from '@/lib/search'
import { cn } from '@/lib/utils'

type Item = { value: string; create: boolean }

type CreatableComboboxFieldProps = {
  label: string
  /** Pozycje do wyboru (tekst = wartość). */
  options: string[]
  value: string
  onChange: (value: string) => void
  onBlur?: () => void
  error?: string
  hint?: ReactNode
  /** Najdłuższa nowa pozycja; dłuższego tekstu nie da się dodać. */
  maxLength?: number
  className?: string
}

const same = (a: string, b: string) => a.trim().toLocaleLowerCase('pl') === b.trim().toLocaleLowerCase('pl')

/**
 * Lista wyboru z wyszukiwarką i dodawaniem nowej pozycji: wpisany tekst zawęża listę, a gdy takiej pozycji nie ma,
 * na końcu listy jest „Dodaj „…”” (Enter wybiera pierwszą pozycję). Pływająca etykieta jak w {@link SelectField}.
 * Z react-hook-form używaj przez `Controller`.
 */
export function CreatableComboboxField({
  label,
  options,
  value,
  onChange,
  onBlur,
  error,
  hint,
  maxLength,
  className,
}: CreatableComboboxFieldProps) {
  const id = useId()
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)

  const all = useMemo(() => {
    // Wybrana wartość spoza listy (np. dodana przed chwilą) też jest pozycją.
    const list = value.trim() && !options.some((option) => same(option, value)) ? [...options, value] : options
    return list.map((option): Item => ({ value: option, create: false }))
  }, [options, value])

  const visible = useMemo(() => {
    const matching = all.filter((item) => matchesSearch([item.value], query))
    const text = query.trim().replace(/\s+/g, ' ')
    const canCreate =
      text !== '' && (maxLength === undefined || text.length <= maxLength) && !all.some((item) => same(item.value, text))
    return canCreate ? [...matching, { value: text, create: true }] : matching
  }, [all, query, maxLength])

  const selected = all.find((item) => item.value === value) ?? null
  const floated = open || value !== ''

  return (
    <FieldShell id={id} error={error} hint={hint} className={className}>
      <Combobox.Root
        items={visible}
        filter={null}
        autoHighlight
        value={selected}
        open={open}
        onOpenChange={(next) => {
          setOpen(next)
          setQuery('')
          if (!next) onBlur?.()
        }}
        // Zamknięte pole pokazuje wybraną pozycję, otwarte to wyszukiwarka.
        inputValue={open ? query : value}
        onInputValueChange={(next) => {
          setQuery(next)
          setOpen(true)
        }}
        onValueChange={(item: Item | null) => {
          if (!item) return
          onChange(item.value)
          setQuery('')
          setOpen(false)
        }}
        itemToStringLabel={(item: Item) => item.value}
        isItemEqualToValue={(a: Item, b: Item) => a.value === b.value}
      >
        <Combobox.Input
          id={id}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, error, hint)}
          autoComplete="off"
          placeholder={open && value ? value : undefined}
          className={cn(fieldControlClass, 'pr-11 placeholder:text-muted-foreground')}
        />
        <label
          htmlFor={id}
          className={cn(
            floatingLabelClass,
            floated && floatedLabelClass,
            open && 'text-foreground',
            error && 'text-destructive',
          )}
        >
          {label}
        </label>
        <Combobox.Trigger
          aria-label={`Pokaż: ${label}`}
          className="absolute top-1/2 right-2 flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/40"
        >
          <ChevronDownIcon className="size-4" />
        </Combobox.Trigger>

        <Combobox.Portal>
          <Combobox.Positioner sideOffset={6} align="start" className="isolate z-50">
            <Combobox.Popup className="max-h-(--available-height) w-(--anchor-width) max-w-(--available-width) origin-(--transform-origin) overflow-hidden rounded-xl glass-float text-popover-foreground transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.98] data-ending-style:opacity-0 data-starting-style:scale-[0.98] data-starting-style:opacity-0 motion-reduce:transition-none">
              <Combobox.Empty className="px-3 py-3 text-sm text-muted-foreground empty:hidden">
                Wpisz nazwę, żeby dodać nową pozycję
              </Combobox.Empty>
              <Combobox.List className="max-h-72 scroll-py-1 overflow-y-auto overscroll-contain p-1 data-empty:p-0">
                {(item: Item) => (
                  <Combobox.Item
                    key={`${item.create ? 'new' : 'old'}:${item.value}`}
                    value={item}
                    className="relative flex cursor-pointer items-center gap-2 rounded-lg py-2 pr-9 pl-2.5 text-sm outline-none select-none data-highlighted:bg-muted"
                  >
                    {item.create ? (
                      <>
                        <PlusIcon className="size-4 shrink-0 text-muted-foreground" />
                        <span className="min-w-0 truncate">
                          Dodaj <span className="font-medium">„{item.value}”</span>
                        </span>
                      </>
                    ) : (
                      <>
                        <span className="min-w-0 truncate">{item.value}</span>
                        <Combobox.ItemIndicator className="absolute top-1/2 right-2.5 -translate-y-1/2">
                          <CheckIcon className="size-4" />
                        </Combobox.ItemIndicator>
                      </>
                    )}
                  </Combobox.Item>
                )}
              </Combobox.List>
            </Combobox.Popup>
          </Combobox.Positioner>
        </Combobox.Portal>
      </Combobox.Root>
    </FieldShell>
  )
}
