import { ArrowDownIcon, ArrowUpIcon, ChevronsUpDownIcon, SearchIcon, XIcon } from 'lucide-react'
import { type ReactNode, type RefObject, useEffect, useId, useState } from 'react'

import { useLoadMore } from '@/lib/use-load-more'
import { nextSort, type SortState } from '@/lib/table-sort'
import { cn } from '@/lib/utils'

/** Ile wierszy listy liczonej w przeglądarce (historia cen) rysujemy na raz; kolejne przy przewijaniu. */
export const LOCAL_PAGE_SIZE = 15

/**
 * Sortowanie i wyszukiwanie listy w karcie artykułu: tekst pola od razu, {@code query} z opóźnieniem 250 ms (do
 * zapytania), żeby pisanie nie wysyłało żądania przy każdym znaku.
 */
export function useListControls<K extends string>(initial: SortState<K>) {
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState(initial)
  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(text), 250)
    return () => window.clearTimeout(timer)
  }, [text])
  return {
    text,
    setText,
    query,
    sort,
    toggleSort: (key: K) => setSort((current) => nextSort(current, key)),
  }
}

/**
 * Sekcja listy w karcie artykułu: tytuł, liczba wierszy i lupa w tym samym wierszu (pole wyszukiwania rozwija się w
 * nagłówku, więc nie zabiera miejsca), pod nimi tabela we własnym obszarze przewijania.
 */
export function ArticleListSection({
  title,
  note,
  searchLabel,
  search,
  onSearch,
  toolbar,
  scrollRef,
  className,
  children,
}: {
  title: string
  note?: ReactNode
  /** Podpowiedź pola wyszukiwania, np. „Szukaj: bon, dostawca”. */
  searchLabel: string
  search: string
  onSearch: (text: string) => void
  /** Dodatkowe pola w nagłówku (np. wybór EAN-u). */
  toolbar?: ReactNode
  scrollRef: RefObject<HTMLDivElement | null>
  className?: string
  children: ReactNode
}) {
  const id = useId()
  const [open, setOpen] = useState(search !== '')
  const close = () => {
    onSearch('')
    setOpen(false)
  }

  return (
    // min-w-0: element siatki okna nie może rozpychać go szerokością tabeli.
    <section className="grid min-w-0 gap-2" aria-labelledby={id}>
      <div className="flex min-h-8 items-center gap-3">
        <h3 id={id} className="text-sm font-semibold">
          {title}
        </h3>
        {toolbar}
        <div className="ml-auto flex min-w-0 items-center gap-2">
          {note && <p className="truncate text-xs text-muted-foreground">{note}</p>}
          {open ? (
            <div className="relative w-56">
              <SearchIcon
                aria-hidden
                className="pointer-events-none absolute top-1/2 left-2 size-3.5 -translate-y-1/2 text-muted-foreground"
              />
              <input
                autoFocus
                type="search"
                value={search}
                onChange={(event) => onSearch(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Escape') {
                    event.stopPropagation()
                    close()
                  }
                }}
                placeholder={searchLabel}
                aria-label={searchLabel}
                className="h-8 w-full rounded-md border border-input bg-background py-1 pr-7 pl-7 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30 [&::-webkit-search-cancel-button]:hidden"
              />
              <button
                type="button"
                onClick={close}
                aria-label="Wyczyść wyszukiwanie"
                className="absolute top-1/2 right-1 grid size-6 -translate-y-1/2 place-items-center rounded text-muted-foreground hover:text-foreground"
              >
                <XIcon className="size-3.5" />
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setOpen(true)}
              aria-label={searchLabel}
              title={searchLabel}
              className="grid size-8 place-items-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
            >
              <SearchIcon className="size-4" />
            </button>
          )}
        </div>
      </div>
      <div ref={scrollRef} className={cn('min-w-0 overflow-auto rounded-lg border bg-card', className)}>
        {children}
      </div>
    </section>
  )
}

/** Nagłówek kolumny listy w karcie artykułu: klik sortuje (rosnąco, potem malejąco), strzałka pokazuje kierunek. */
export function SortTh<K extends string>({
  column,
  sort,
  onSort,
  align = 'left',
  title,
  children,
}: {
  column: K
  sort: SortState<K>
  onSort: (column: K) => void
  align?: 'left' | 'right'
  title?: string
  children: ReactNode
}) {
  const active = sort.key === column
  const Icon = !active ? ChevronsUpDownIcon : sort.direction === 'asc' ? ArrowUpIcon : ArrowDownIcon
  return (
    <th
      aria-sort={active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}
      className={cn('px-3 py-1 font-medium', align === 'right' ? 'text-right' : 'text-left')}
      title={title}
    >
      <button
        type="button"
        onClick={() => onSort(column)}
        className={cn(
          '-mx-1.5 inline-flex items-center gap-1 rounded-md px-1.5 py-1 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
          align === 'right' && 'flex-row-reverse',
          active ? 'text-foreground' : 'text-muted-foreground',
        )}
      >
        {children}
        <Icon aria-hidden className={cn('size-3.5 shrink-0', !active && 'opacity-50')} />
      </button>
    </th>
  )
}

/**
 * Ostatni wiersz tabeli: przy zbliżeniu do dołu obszaru przewijania wywołuje {@code onLoadMore}; w trakcie
 * doczytywania pokazuje zaślepkę.
 */
export function LoadMoreRow({
  colSpan,
  hasMore,
  loading,
  loaded,
  onLoadMore,
  root,
}: {
  colSpan: number
  hasMore: boolean
  loading: boolean
  loaded: number
  onLoadMore: () => void
  root: RefObject<HTMLDivElement | null>
}) {
  const ref = useLoadMore<HTMLTableRowElement>(onLoadMore, hasMore && !loading, loaded, root)
  if (!hasMore) return null
  return (
    <tr ref={ref} aria-hidden className="border-t">
      <td colSpan={colSpan} className="px-3 py-1.5">
        <div className="h-4 loading-shimmer rounded" />
      </td>
    </tr>
  )
}

/** Komunikat zamiast tabeli: brak wyników wyszukiwania albo brak danych. */
export function EmptyListRow({ colSpan, children }: { colSpan: number; children: ReactNode }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-3 text-sm text-muted-foreground">
        {children}
      </td>
    </tr>
  )
}

/** Wiersze listy liczonej w przeglądarce rysowane porcjami po {@link LOCAL_PAGE_SIZE}; zmiana listy wraca do pierwszej. */
export function useLocalPages<T>(rows: T[]) {
  const [shown, setShown] = useState(LOCAL_PAGE_SIZE)
  const [source, setSource] = useState(rows)
  if (source !== rows) {
    setSource(rows)
    setShown(LOCAL_PAGE_SIZE)
  }
  return {
    visible: rows.slice(0, shown),
    hasMore: shown < rows.length,
    loadMore: () => setShown((count) => count + LOCAL_PAGE_SIZE),
  }
}
