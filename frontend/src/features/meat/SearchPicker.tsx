import { useMemo, useState, type ReactNode } from 'react'
import { Combobox } from '@base-ui/react/combobox'
import { ChevronDownIcon, SearchIcon } from 'lucide-react'

import { matchesSearch } from '@/lib/search'
import { cn } from '@/lib/utils'

type Props<T> = {
  label: string
  items: T[]
  value: T | null
  onChange: (item: T) => void
  itemKey: (item: T) => string
  itemLabel: (item: T) => string
  /** Pola, po których działa wyszukiwanie. */
  searchFields: (item: T) => (string | null | undefined)[]
  renderItem: (item: T) => ReactNode
  placeholder: string
  emptyText: string
  invalid?: boolean
  /** `lg` = wysokość pól formularza z pływającą etykietą (h-12), gdy wyszukiwarka stoi obok nich. */
  size?: 'default' | 'lg'
  className?: string
}

/**
 * Wybór jednej pozycji z listy, która przychodzi w całości (mięso z kartoteki, dostawcy sklepu): lista rozwija się
 * od razu, a wpisane słowa zawężają ją w przeglądarce. Wygląd jak {@code FinishedProductPicker}.
 */
export function SearchPicker<T>({
  label,
  items,
  value,
  onChange,
  itemKey,
  itemLabel,
  searchFields,
  renderItem,
  placeholder,
  emptyText,
  invalid,
  size = 'default',
  className,
}: Props<T>) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const visible = useMemo(
    () => items.filter((item) => matchesSearch(searchFields(item), query)),
    [items, query, searchFields],
  )

  return (
    <Combobox.Root
      items={visible}
      filter={null}
      value={value}
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        setQuery('')
      }}
      // Zamknięte pole pokazuje wybraną pozycję, otwarte to wyszukiwarka.
      inputValue={open ? query : value ? itemLabel(value) : ''}
      onInputValueChange={(next) => {
        setQuery(next)
        setOpen(true)
      }}
      onValueChange={(item: T | null) => {
        if (item) {
          onChange(item)
          setQuery('')
          setOpen(false)
        }
      }}
      itemToStringLabel={itemLabel}
      isItemEqualToValue={(a: T, b: T) => itemKey(a) === itemKey(b)}
    >
      <div className={cn('relative', className)}>
        <SearchIcon
          aria-hidden
          className={cn(
            'pointer-events-none absolute top-1/2 size-4 -translate-y-1/2 text-muted-foreground',
            size === 'lg' ? 'left-3' : 'left-2.5',
          )}
        />
        <Combobox.Input
          aria-label={label}
          aria-invalid={invalid || undefined}
          placeholder={open && value ? itemLabel(value) : placeholder}
          className={cn(
            size === 'lg' ? 'h-12 rounded-xl pl-9' : 'h-10 rounded-lg pl-8',
            'w-full border border-input bg-background pr-10 text-sm transition-[border-color,box-shadow] duration-150 outline-none placeholder:text-muted-foreground focus:border-ring focus:ring-3 focus:ring-ring/30 dark:bg-input/30',
            invalid && 'border-amber-500 focus:border-amber-500 focus:ring-amber-500/20',
          )}
        />
        <Combobox.Trigger
          aria-label={`Pokaż: ${label}`}
          className="absolute top-1/2 right-1.5 flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/40"
        >
          <ChevronDownIcon className="size-4" />
        </Combobox.Trigger>
      </div>

      <Combobox.Portal>
        <Combobox.Positioner sideOffset={6} align="start" className="isolate z-50">
          <Combobox.Popup className="max-h-(--available-height) w-max min-w-(--anchor-width) max-w-[min(var(--available-width),36rem)] origin-(--transform-origin) overflow-hidden rounded-xl glass-float text-popover-foreground transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.98] data-ending-style:opacity-0 data-starting-style:scale-[0.98] data-starting-style:opacity-0 motion-reduce:transition-none">
            <Combobox.Empty className="px-3 py-3 text-sm text-muted-foreground empty:hidden">{emptyText}</Combobox.Empty>
            <Combobox.List className="max-h-80 scroll-py-1 overflow-y-auto overscroll-contain p-1 data-empty:p-0">
              {(item: T) => (
                <Combobox.Item
                  key={itemKey(item)}
                  value={item}
                  className="cursor-pointer rounded-lg px-2.5 py-2 text-sm outline-none select-none data-highlighted:bg-muted"
                >
                  {renderItem(item)}
                </Combobox.Item>
              )}
            </Combobox.List>
          </Combobox.Popup>
        </Combobox.Positioner>
      </Combobox.Portal>
    </Combobox.Root>
  )
}
