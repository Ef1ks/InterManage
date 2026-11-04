import { Popover } from '@base-ui/react/popover'
import { ListFilterIcon, SearchIcon } from 'lucide-react'
import { useMemo, useState } from 'react'

import { Button } from '@/components/ui/button'
import { plain } from '@/features/data-entry/rows'
import { cn } from '@/lib/utils'

export type FilterValue = { value: string; count: number }

type Props = {
  label: string
  /**
   * Wartości kolumny w tabeli z liczbą wierszy, w kolejności do wyświetlenia. Liczone dopiero po otwarciu listy, bo
   * przejście po wszystkich wierszach przy każdej zmianie tabeli spowalniało edycję.
   */
  getValues: () => FilterValue[]
  /** Zaznaczone wartości; {@code null} = bez filtra (wszystkie). */
  selected: string[] | null
  onChange: (selected: string[] | null) => void
}

/**
 * Filtr kolumny jak w Excelu: lista wartości z polami wyboru i wyszukiwarka nad nią. Zmiana działa od razu;
 * „Tylko wyszukane” zostawia same pasujące do wpisanego tekstu.
 */
export function ColumnFilter({ label, getValues, selected, onChange }: Props) {
  const [query, setQuery] = useState('')
  const [values, setValues] = useState<FilterValue[]>(NO_VALUES)
  const active = selected !== null
  const chosen = useMemo(() => new Set(selected ?? values.map((item) => item.value)), [selected, values])
  const needle = plain(query.trim())
  const shown = needle ? values.filter((item) => plain(item.value).includes(needle)) : values

  const toggle = (value: string) => {
    const next = new Set(chosen)
    if (next.has(value)) next.delete(value)
    else next.add(value)
    onChange(next.size === values.length ? null : values.map((item) => item.value).filter((item) => next.has(item)))
  }

  return (
    <Popover.Root
      onOpenChange={(open) => {
        if (open) setValues(getValues())
        else setQuery('')
      }}
    >
      <Popover.Trigger
        aria-label={`Filtr kolumny ${label}`}
        title={
          active ? `Filtr: ${selected.length} ${selected.length === 1 ? 'wartość' : 'wartości'}` : 'Filtruj kolumnę'
        }
        // Widoczny po najechaniu na nagłówek, z klawiatury i zawsze, gdy filtr działa (jak strzałka filtra w Excelu).
        className={cn(
          'grid size-5 shrink-0 place-items-center rounded bg-muted text-muted-foreground shadow-xs ring-1 ring-border hover:bg-background hover:text-foreground focus-visible:opacity-100 data-popup-open:opacity-100',
          active
            ? 'bg-blue-600 text-white ring-blue-700 hover:bg-blue-700 hover:text-white dark:bg-blue-500'
            : 'opacity-0 group-hover/th:opacity-100',
        )}
      >
        <ListFilterIcon className="size-[1.05em]" />
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Positioner side="bottom" align="start" sideOffset={4} collisionPadding={12} className="z-50">
          <Popover.Popup className="flex w-72 flex-col gap-2 rounded-xl border bg-popover p-2 text-sm text-popover-foreground shadow-lg outline-none">
            <p className="px-1 text-xs font-medium text-muted-foreground">{label}</p>
            <label className="flex items-center gap-2 rounded-lg border border-input bg-background px-2 dark:bg-input/30">
              <SearchIcon aria-hidden className="size-4 text-muted-foreground" />
              <input
                autoFocus
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && needle) onChange(shown.map((item) => item.value))
                }}
                placeholder="Szukaj"
                className="h-8 min-w-0 flex-1 bg-transparent outline-none"
              />
            </label>
            <ul className="max-h-72 overflow-y-auto" aria-label={`Wartości: ${label}`}>
              {!needle && (
                <li>
                  <label className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 font-medium hover:bg-accent">
                    <input
                      type="checkbox"
                      checked={!active}
                      onChange={() => onChange(active ? null : [])}
                      className="size-4 accent-blue-600"
                    />
                    Zaznacz wszystko
                  </label>
                </li>
              )}
              {shown.map((item) => (
                <li key={item.value}>
                  <label className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 hover:bg-accent">
                    <input
                      type="checkbox"
                      checked={chosen.has(item.value)}
                      onChange={() => toggle(item.value)}
                      className="size-4 shrink-0 accent-blue-600"
                    />
                    <span className="min-w-0 flex-1 truncate">{item.value}</span>
                    <span className="text-xs text-muted-foreground tabular-nums">{item.count}</span>
                  </label>
                </li>
              ))}
              {shown.length === 0 && <li className="px-1.5 py-2 text-muted-foreground">Brak wartości.</li>}
            </ul>
            <div className="flex gap-2">
              {needle && (
                <Button size="sm" variant="outline" onClick={() => onChange(shown.map((item) => item.value))}>
                  Tylko wyszukane ({shown.length})
                </Button>
              )}
              <Button size="sm" variant="ghost" className="ml-auto" disabled={!active} onClick={() => onChange(null)}>
                Wyczyść filtr
              </Button>
            </div>
          </Popover.Popup>
        </Popover.Positioner>
      </Popover.Portal>
    </Popover.Root>
  )
}

const NO_VALUES: FilterValue[] = []
