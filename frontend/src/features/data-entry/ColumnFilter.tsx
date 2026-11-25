import { Popover } from '@base-ui/react/popover'
import { ArrowDownIcon, ArrowUpIcon, ListFilterIcon, SearchIcon } from 'lucide-react'
import { useMemo, useState } from 'react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import {
  type ColumnCondition,
  type ColumnRule,
  conditionLabel,
  type FilterValue,
  hasCondition,
  type SortDirection,
} from './column-filters'
import { plain } from './rows'

export type { FilterValue } from './column-filters'

type Props = {
  label: string
  /**
   * Wartości kolumny w tabeli z liczbą wierszy, w kolejności do wyświetlenia. Liczone dopiero po otwarciu listy, bo
   * przejście po wszystkich wierszach przy każdej zmianie tabeli spowalniało edycję.
   */
  getValues: () => FilterValue[]
} & (
  | {
      /** Sama lista wartości. Zaznaczone wartości; {@code null} = bez filtra (wszystkie). */
      selected: string[] | null
      onChange: (selected: string[] | null) => void
      rule?: never
      onRuleChange?: never
    }
  | {
      /** Lista wartości i warunek (zawiera / od-do); {@code null} = bez filtra. */
      rule: ColumnRule | null
      onRuleChange: (rule: ColumnRule | null) => void
      selected?: never
      onChange?: never
    }
) & {
    /** Kolumna liczb: warunek to przedział od-do, sortowanie od najmniejszej. */
    numeric?: boolean
    /** Kierunek, gdy tabela jest posortowana po tej kolumnie. */
    sort?: SortDirection | null
    onSort?: (direction: SortDirection) => void
  }

/**
 * Filtr kolumny jak w Excelu: sortowanie, warunek („zawiera” albo przedział liczb), lista wartości z polami wyboru i
 * wyszukiwarka nad nią. Zmiana działa od razu; „Tylko wyszukane” zostawia same pasujące do wpisanego tekstu.
 */
export function ColumnFilter(props: Props) {
  const { label, getValues, numeric = false, sort = null, onSort } = props
  const withCondition = props.onRuleChange !== undefined
  const selected = withCondition ? (props.rule?.values ?? null) : props.selected
  const condition = withCondition ? (props.rule?.condition ?? null) : null

  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [values, setValues] = useState<FilterValue[]>(NO_VALUES)
  // Pola przedziału jako tekst, żeby „2,” w trakcie pisania nie znikało.
  const [range, setRange] = useState({ from: '', to: '' })
  const conditionActive = hasCondition(condition ?? undefined)
  const active = selected !== null || conditionActive
  const chosen = useMemo(() => new Set(selected ?? values.map((item) => item.value)), [selected, values])
  const needle = plain(query.trim())
  const shown = needle ? values.filter((item) => plain(item.value).includes(needle)) : values

  const emit = (nextSelected: string[] | null, nextCondition: ColumnCondition | null) => {
    if (props.onRuleChange) {
      const rule: ColumnRule = {}
      if (nextSelected !== null) rule.values = nextSelected
      if (nextCondition && hasCondition(nextCondition)) rule.condition = nextCondition
      props.onRuleChange(Object.keys(rule).length > 0 ? rule : null)
    } else props.onChange(nextSelected)
  }
  const choose = (next: string[] | null) => emit(next, condition)

  const toggle = (value: string) => {
    const next = new Set(chosen)
    if (next.has(value)) next.delete(value)
    else next.add(value)
    choose(next.size === values.length ? null : values.map((item) => item.value).filter((item) => next.has(item)))
  }

  const setBound = (bound: 'from' | 'to', text: string) => {
    const next = { ...range, [bound]: text }
    setRange(next)
    emit(selected, { from: parseNumber(next.from), to: parseNumber(next.to) })
  }

  const title = active
    ? [
        conditionActive && condition ? conditionLabel(condition) : null,
        selected !== null ? `${selected.length} ${selected.length === 1 ? 'wartość' : 'wartości'}` : null,
      ]
        .filter(Boolean)
        .join(', ')
    : 'Filtruj i sortuj kolumnę'

  return (
    <Popover.Root
      open={open}
      onOpenChange={(next) => {
        setOpen(next)
        if (next) {
          setValues(getValues())
          setRange(
            condition && !('contains' in condition)
              ? { from: formatNumber(condition.from), to: formatNumber(condition.to) }
              : { from: '', to: '' },
          )
        } else setQuery('')
      }}
    >
      <Popover.Trigger
        aria-label={`Filtr kolumny ${label}`}
        title={active ? `Filtr: ${title}` : title}
        // Widoczny po najechaniu na nagłówek, z klawiatury i zawsze, gdy filtr działa (jak strzałka filtra w Excelu).
        className={cn(
          'grid size-5 shrink-0 place-items-center rounded bg-muted text-muted-foreground shadow-xs ring-1 ring-border hover:bg-background hover:text-foreground focus-visible:opacity-100 data-popup-open:opacity-100',
          active
            ? 'bg-blue-600 text-white ring-blue-700 hover:bg-blue-700 hover:text-white dark:bg-blue-500'
            : sort
              ? 'opacity-100'
              : 'opacity-0 group-hover/th:opacity-100',
        )}
      >
        <ListFilterIcon className="size-[1.05em]" />
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Positioner side="bottom" align="start" sideOffset={4} collisionPadding={12} className="z-50">
          <Popover.Popup className="flex w-72 flex-col gap-2 rounded-xl glass-float p-2 text-sm text-popover-foreground outline-none">
            <p className="px-1 text-xs font-medium text-muted-foreground">{label}</p>
            {onSort && (
              <div className="grid grid-cols-2 gap-1">
                {(['asc', 'desc'] as const).map((direction) => (
                  <Button
                    key={direction}
                    size="sm"
                    variant={sort === direction ? 'secondary' : 'ghost'}
                    aria-pressed={sort === direction}
                    className="justify-start"
                    onClick={() => {
                      onSort(direction)
                      setOpen(false)
                    }}
                  >
                    {direction === 'asc' ? <ArrowUpIcon /> : <ArrowDownIcon />}
                    {SORT_LABELS[numeric ? 'number' : 'text'][direction]}
                  </Button>
                ))}
              </div>
            )}
            {withCondition &&
              (numeric ? (
                <div className="flex flex-col gap-1">
                  <div className="flex items-center gap-2">
                    {(['from', 'to'] as const).map((bound) => (
                      <label
                        key={bound}
                        className="flex min-w-0 flex-1 items-center gap-1.5 rounded-lg border border-input bg-background px-2 dark:bg-input/30"
                      >
                        <span className="text-xs text-muted-foreground">{bound === 'from' ? 'Od' : 'Do'}</span>
                        <input
                          autoFocus={bound === 'from'}
                          inputMode="decimal"
                          value={range[bound]}
                          onChange={(event) => setBound(bound, event.target.value)}
                          onKeyDown={(event) => {
                            if (event.key === 'Enter') setOpen(false)
                          }}
                          className="h-8 w-full min-w-0 bg-transparent text-right tabular-nums outline-none"
                        />
                      </label>
                    ))}
                  </div>
                  <p className="px-1 text-xs text-muted-foreground">
                    Ta sama liczba w obu polach = dokładnie ta wartość.
                  </p>
                </div>
              ) : (
                <label className="flex items-center gap-2 rounded-lg border border-input bg-background px-2 dark:bg-input/30">
                  <span className="text-xs text-muted-foreground">Zawiera</span>
                  <input
                    autoFocus
                    value={condition && 'contains' in condition ? condition.contains : ''}
                    onChange={(event) => emit(selected, { contains: event.target.value })}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') setOpen(false)
                    }}
                    placeholder="np. herbata 20g"
                    title="Każde słowo musi wystąpić, w dowolnej kolejności; * = dowolny ciąg, ? = jeden znak"
                    className="h-8 min-w-0 flex-1 bg-transparent outline-none"
                  />
                </label>
              ))}
            <label className="flex items-center gap-2 rounded-lg border border-input bg-background px-2 dark:bg-input/30">
              <SearchIcon aria-hidden className="size-4 text-muted-foreground" />
              <input
                autoFocus={!withCondition}
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && needle) choose(shown.map((item) => item.value))
                }}
                placeholder={withCondition ? 'Szukaj na liście' : 'Szukaj'}
                className="h-8 min-w-0 flex-1 bg-transparent outline-none"
              />
            </label>
            <ul className="max-h-72 overflow-y-auto" aria-label={`Wartości: ${label}`}>
              {!needle && (
                <li>
                  <label className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 font-medium hover:bg-accent">
                    <input
                      type="checkbox"
                      checked={selected === null}
                      onChange={() => choose(selected !== null ? null : [])}
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
                <Button size="sm" variant="outline" onClick={() => choose(shown.map((item) => item.value))}>
                  Tylko wyszukane ({shown.length})
                </Button>
              )}
              <Button
                size="sm"
                variant="ghost"
                className="ml-auto"
                disabled={!active}
                onClick={() => {
                  setRange({ from: '', to: '' })
                  emit(null, null)
                }}
              >
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

const SORT_LABELS = {
  text: { asc: 'Od A do Z', desc: 'Od Z do A' },
  number: { asc: 'Rosnąco', desc: 'Malejąco' },
} as const

/** Liczba z pola przedziału: przecinek albo kropka, bez „zł”, spacji i „%”; pusta albo błędna = bez granicy. */
function parseNumber(text: string) {
  const cleaned = text.replace(/\s|zł|%/gi, '').replace(',', '.')
  if (cleaned === '' || cleaned === '-') return null
  const value = Number(cleaned)
  return Number.isFinite(value) ? value : null
}

const formatNumber = (value: number | null) =>
  value === null ? '' : value.toLocaleString('pl-PL', { maximumFractionDigits: 4, useGrouping: false })
