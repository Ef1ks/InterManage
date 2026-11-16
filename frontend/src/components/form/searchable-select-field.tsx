import { useId, useMemo, useState, type ReactNode } from 'react'
import { Combobox } from '@base-ui/react/combobox'
import { CheckIcon, ChevronDownIcon } from 'lucide-react'

import { FieldShell } from '@/components/form/field-shell'
import { describedBy, fieldControlClass, floatedLabelClass, floatingLabelClass } from '@/components/form/field-styles'
import type { SelectOption } from '@/components/form/select-field'
import { matchesSearch } from '@/lib/search'
import { cn } from '@/lib/utils'

type SearchableSelectFieldProps = {
  label: string
  options: SelectOption[]
  value: string
  onChange: (value: string) => void
  hint?: ReactNode
  className?: string
}

/**
 * Lista wyboru z wyszukiwarką (np. dostawca w raportach, gdy pozycji są setki): otwarte pole to wyszukiwarka,
 * każde wpisane słowo musi pasować do etykiety, Enter wybiera pierwszą pozycję. Pływająca etykieta jak w
 * {@link SelectField}.
 */
export function SearchableSelectField({
  label,
  options,
  value,
  onChange,
  hint,
  className,
}: SearchableSelectFieldProps) {
  const id = useId()
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)

  const visible = useMemo(() => options.filter((option) => matchesSearch([option.label], query)), [options, query])
  const selected = options.find((option) => option.value === value) ?? null
  const floated = open || selected != null

  return (
    <FieldShell id={id} hint={hint} className={className}>
      <Combobox.Root
        items={visible}
        filter={null}
        autoHighlight
        value={selected}
        open={open}
        onOpenChange={(next) => {
          setOpen(next)
          setQuery('')
        }}
        inputValue={open ? query : (selected?.label ?? '')}
        onInputValueChange={(next) => {
          setQuery(next)
          setOpen(true)
        }}
        onValueChange={(option: SelectOption | null) => {
          if (!option) return
          onChange(option.value)
          setQuery('')
          setOpen(false)
        }}
        itemToStringLabel={(option: SelectOption) => option.label}
        isItemEqualToValue={(a: SelectOption, b: SelectOption) => a.value === b.value}
      >
        <Combobox.Input
          id={id}
          aria-describedby={describedBy(id, undefined, hint)}
          autoComplete="off"
          placeholder={open && selected ? selected.label : undefined}
          onFocus={(event) => event.currentTarget.select()}
          className={cn(fieldControlClass, 'truncate pr-11 placeholder:text-muted-foreground')}
        />
        <label htmlFor={id} className={cn(floatingLabelClass, floated && floatedLabelClass, open && 'text-foreground')}>
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
                Nic nie pasuje
              </Combobox.Empty>
              <Combobox.List className="max-h-80 scroll-py-1 overflow-y-auto overscroll-contain p-1 data-empty:p-0">
                {(option: SelectOption) => (
                  <Combobox.Item
                    key={option.value}
                    value={option}
                    className="relative flex cursor-pointer items-center gap-2 rounded-lg py-2 pr-9 pl-2.5 text-sm outline-none select-none data-highlighted:bg-muted"
                  >
                    <span className="min-w-0 truncate">{option.label}</span>
                    <Combobox.ItemIndicator className="absolute top-1/2 right-2.5 -translate-y-1/2">
                      <CheckIcon className="size-4" />
                    </Combobox.ItemIndicator>
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
